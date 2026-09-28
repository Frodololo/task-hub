# Panel de expertos v20 — Auditoría del código nuevo + estado abierto (2026-09-27)

HEAD de partida: `75243ed` (v0.7.52). Cambios desde v19 (`8fa123a`): 3 bloques
"APLICA YA" de las PROPUESTAS v18 — `idempotencyKey` en los 4 DTOs de Cloud
Functions transaccionales + generación de UUID en sus 4 call-sites de
`FirestoreRepository`, helper `bestEffort` en `platform/AppLog.kt` sustituyendo
~18 `try/catch` manuales de "best-effort, no romper cancelación" en 6 archivos
de `ui/models/` (con `network/FirestoreClient.orDefault` delegando en él), y el
opt-out de analytics de `SettingsSheet` movido a
`ProfileScreenModel.setAnalyticsOptOut()`. 11 especialistas, coordinados por
Claude, en 2 oleadas (6+5), con verificación directa contra el código real
(diffs, greps y trazado manual de las 4 Cloud Functions server-side) en vez de
inspección superficial — foco explícito en NO repetir el trabajo de v18/v19.

## Resumen ejecutivo

**Hallazgo principal (IMPORTANTE, PROPUESTA — el commit de esta ventana no
cierra lo que dice cerrar):** el `idempotencyKey` generado en los 4 call-sites
de `FirestoreRepository` (`donatePoints`/`completeTask`/`completeAssignment`/
`redeemReward`) usa `Uuid.random()` **en cada invocación de la función del
repositorio**, no una clave reutilizada a través de reintentos de la misma
acción lógica. `CloudFunctionsClient.call()` hace una única llamada `POST` sin
ningún reintento automático (ni a nivel de Ktor —no hay `HttpRequestRetry`
instalado— ni a nivel de `CloudFunctionsClient`), y los 4 `ScreenModel`
correspondientes (`TaskScreenModel`, `MemberScreenModel`) dejan el botón de
acción disponible de nuevo tras CUALQUIER error (incluido el `AMBIGUOUS`/
`UNCERTAIN` que es exactamente el caso que `idempotencyKey` dice resolver). Si
el usuario reintenta a mano tras un timeout, el `ScreenModel` vuelve a invocar
el método del repositorio, que genera una clave **nueva**, y el servidor
(`withIdempotency`, verificado en `functions/src/idempotency.ts`) la trata como
una operación nueva sin ninguna deduplicación — el riesgo de duplicar
puntos/canjes en un reintento manual, que es el escenario que motivó el
hallazgo CRÍTICO de v18, sigue exactamente igual de abierto que antes de este
commit. La infraestructura del servidor (probada en v18, 8/8 suites de
integración) es correcta; lo que falta es que el cliente reutilice la clave a
través de los reintentos, lo cual requiere decidir un contrato de estado
("acción pendiente" con su clave, reutilizada mientras no haya confirmación
definitiva) — no es un fix de una línea. **No es una regresión** (el riesgo
preexistía sin ninguna mitigación); es una sobre-estimación de lo que el
commit realmente arregla.

**Aplicado en esta ronda:** 1 fix mecánico y de riesgo cero — los ~18 sitios
migrados a `bestEffort` en la ronda anterior compartían el mismo `tag` genérico
por clase (p. ej. `"TaskScreenModel"` en los 13 sitios de ese archivo) y el
mismo mensaje de log fijo (`"block failed, returning default"`), perdiendo la
capacidad de distinguir en logs de producción CUÁL de los ~13 best-effort de
`TaskScreenModel` falló sin expandir el stack trace completo — antes, cada
sitio tenía su propio mensaje descriptivo (`"completeTask: cancelReminder
failed for task $taskId"`, etc.). **APLICADO**: cada `tag` ahora identifica
`Clase.método.operación` (p. ej. `"TaskScreenModel.completeTask.
cancelReminder"`), sin cambiar ningún comportamiento observable ni añadir
alocaciones nuevas (son literales de compilación).

**Hallazgo secundario (MENOR, PROPUESTA):** la migración a `bestEffort` de v18
quedó incompleta — `CalendarSyncManager.kt` tiene 8 sitios con el mismo patrón
exacto "best-effort, no romper cancelación" (algunos incluso sin ningún
`AppLog`, ver detalle abajo) que no se tocaron. No se aplica en esta ronda
porque migrarlos AÑADE logging donde hoy no lo hay (comportamiento
observable nuevo, no solo refactor), lo cual es una decisión de ruido de logs,
no un fix puramente mecánico.

**Verificación final:**
```
./gradlew :composeApp:compileDebugKotlinAndroid --console=plain   → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain       → BUILD SUCCESSFUL
```
Conteo real de `composeApp/build/test-results/jvmTest/*.xml`: **291/291 tests
verdes**, 0 failures, 0 errors — sin cambio respecto a v19 (ningún test nuevo
para `idempotencyKey`/`bestEffort`/`setAnalyticsOptOut`, ver sección de
cobertura). No se tocó `functions/` en esta ventana (confirmado con `git show
--stat 75243ed`), así que no se re-ejecutó `npm test`/`npm run test:integration`.

---

## 1. Funcionalidad end-to-end

**CRÍTICO/IMPORTANTE — PROPUESTA (ver resumen ejecutivo):** `idempotencyKey`
regenerado por llamada, no reutilizado en reintentos manuales — el hallazgo
CRÍTICO de v18 sigue funcionalmente abierto pese al commit. Verificado con
evidencia directa:
- `CloudFunctionsClient.call()` (`network/CloudFunctionsClient.kt:51-62`): un
  único `client.post(...)`, sin bucle de reintento ni plugin
  `HttpRequestRetry` instalado en el `HttpClient` compartido
  (`network/FirestoreClient.kt:79-90`, confirmado sin ese `install`).
- `FirestoreRepository.donatePoints`/`completeTask`/`completeAssignment`/
  `redeemReward`: `idempotencyKey = Uuid.random().toString()` está **dentro**
  del cuerpo de cada función, generado de nuevo en cada invocación.
- `MemberScreenModel.donatePoints`/`redeemReward` (líneas 412-440, 311-350):
  tras CUALQUIER `catch (e: Exception)`, incluido el camino `UNCERTAIN` que
  explícitamente recarga el saldo porque "un error ambiguo... puede haber
  descontado puntos igualmente en el servidor", el estado vuelve a
  `Error(...)` (no bloqueante) y un nuevo tap del usuario ejecuta la función
  del repositorio otra vez, con una clave distinta.
- Ningún `ScreenModel` (`TaskScreenModel`/`MemberScreenModel`) guarda o
  reutiliza un `idempotencyKey` entre intentos (`grep -rn "idempotencyKey" ui/
  models/` → 0 resultados).
- Confirmado server-side (`functions/src/idempotency.ts:55`): `if
  (!idempotencyKey) return fn()` y, con clave, una clave `completed` hace
  replay seguro — el mecanismo del servidor SÍ funciona; el cliente
  simplemente nunca le da la oportunidad de detectar el reintento porque cada
  intento parece una operación distinta.

Verificado sin hallazgos nuevos: los 4 DTOs (`FunctionDtos.kt`) tienen el
campo; los 4 call-sites lo pueblan; los nombres de campo coinciden
exactamente con lo que leen las 4 Cloud Functions (`completeAssignment.ts`,
`completeRecurringTask.ts`, `donatePoints.ts`, `redeemReward.ts`, confirmado
por grep cruzado). `bestEffort` no cambia ningún comportamiento observable:
mismo `try/catch(CancellationException)/catch(Exception)` que antes, mismo
valor por defecto en cada sitio (`Unit`, `null`, `s("profile_default_name")`),
verificado uno a uno contra el diff de `75243ed`. El opt-out de analytics
sigue funcionando igual (mismo par de llamadas —persistencia + SDK—, ahora
en `ProfileScreenModel` en vez de en el Composable).

## 2. Seguridad / AppSec (OWASP MASVS)

Sin hallazgos explotables nuevos. Verificado con evidencia:
- `kotlin.uuid.Uuid.random()` (stdlib multiplatform desde Kotlin 1.9.20, sin
  necesidad de `expect`/`actual`) usa la fuente de aleatoriedad segura de cada
  plataforma (`SecureRandom` en JVM/Android, `arc4random_buf` en Apple,
  `crypto.getRandomValues` en JS/wasmJs) — apropiado incluso siendo solo una
  clave de deduplicación, no un secreto. `@file:OptIn(kotlin.uuid.
  ExperimentalUuidApi::class)` correctamente acotado a `FirestoreRepository.kt`.
- `bestEffort` (`platform/AppLog.kt`) relanza `CancellationException` ANTES
  del `catch (e: Exception)` genérico, igual que el patrón manual que
  sustituye — verificado que ninguno de los ~18 call-sites migrados perdió
  esa cláusula (confirmado contra el diff completo de `75243ed`, no solo el
  código final).
- El opt-out de analytics no introduce ninguna superficie nueva: mismas dos
  llamadas de antes (`settingsStore.setAnalyticsOptOut`/
  `setAnalyticsCollectionEnabled`), sin acceso a datos adicionales.

## 3. Programador senior

**MENOR — Fix aplicable (APLICADO en esta ronda):** los ~18 sitios migrados a
`bestEffort` en `TaskScreenModel.kt` (13), `GoogleAuthManager.kt` (4),
`NotificationScreenModel.kt` (3), `HouseholdScreenModel.kt`/
`StatsScreenModel.kt`/`TaskCommentsScreenModel.kt` (1 cada uno) compartían un
`tag` genérico por clase y el mensaje fijo `"block failed, returning
default"` de `bestEffort` — una regresión real de trazabilidad en logs de
producción respecto al código anterior (cada sitio tenía su propio mensaje
descriptivo con contexto, p. ej. `"reassignTaskCompletion:
checkAndAwardAchievements failed"`). **APLICADO**: cada `tag` pasado a
`bestEffort` ahora es `Clase.método.operación` (p. ej.
`"TaskScreenModel.rollbackUnassignedTask.deleteAssignments"`,
`"GoogleAuthManager.deleteAccount.revokeGoogleCalendarAccess"`), sin tocar
ningún `default` ni la lógica de los bloques — cambio de solo literales de
`String`, riesgo cero, verificado con `compileDebugKotlinAndroid` +
`jvmTest --rerun-tasks` (291/291 verdes, sin cambios).

**MENOR — PROPUESTA (nuevo, migración incompleta de v18):**
`CalendarSyncManager.kt` tiene 8 sitios con el patrón exacto que motivó
`bestEffort` (`ensureCalendarId`, `onTaskAssigned`, `onDueDateChanged`,
`reconcile` ×2, `syncNow`, `createEventForAssignment`,
`deleteEventForAssignment`) que no se migraron. Se deja como PROPUESTA en vez
de aplicar porque la mitad de estos sitios usan `catch (_: Exception) { }` sin
ningún `AppLog` — migrarlos a `bestEffort` añadiría logging donde hoy no lo
hay (`AppLog.w` es incondicional dentro del helper), lo cual es un cambio de
comportamiento observable (nuevas líneas de log en producción para fallos que
hoy son deliberadamente silenciosos, según el propio KDoc de la clase: "Toda
operación es best-effort: nunca lanza excepciones... se salta en silencio"),
no un refactor mecánico puro — decisión de ruido de logs pendiente de
confirmar antes de aplicar.

Verificado sin hallazgos: `orDefault` en `FirestoreClient.kt` delega
correctamente en `bestEffort` (mismo comportamiento, import de
`CancellationException` sigue en uso en otros 4 sitios del archivo, sin
warning de import muerto); `MemberScreenModel.kt`/`HomeScreenModel.kt` NO
tienen sitios candidatos a `bestEffort` mal dejados — sus `catch` construyen
estado de error real (`_uiState.value = ...Error(...)`), un patrón distinto
que `bestEffort` no debe sustituir (confirmado leyendo los ~10 `catch` de
ambos archivos uno a uno).

## 4. Jefe de arquitectura

**Cierra un hallazgo de v18 (Arquitectura #2):** el opt-out de analytics ya
no invoca el SDK de plataforma directamente desde un lambda de Composable —
`SettingsSheet.kt` ahora delega en `ProfileScreenModel.setAnalyticsOptOut()`,
restaurando el patrón Screen→ScreenModel que el resto de la pantalla sí
respeta. Verificado: `ProfileScreenModel` ya recibía `settingsStore` en su
constructor (sin cambio de firma ni de registro Koin —
`factory { ProfileScreenModel(repo = get(), settingsStore = get()) }`, sin
dependencia circular). Nota menor sin acción: `koinInject<ProfileScreenModel>()`
en `SettingsSheet.kt` usa el registro `factory` (una instancia nueva por
inyección, no el `koinScreenModel()` ligado al ciclo de vida de Voyager que
usan las pantallas reales) — inocuo aquí porque `setAnalyticsOptOut` es
síncrono y no abre ninguna corrutina (`screenModelScope` nunca se crea), pero
es una construcción de objeto ligeramente atípica para invocar un solo método;
no amerita cambio.

`GoogleAuthManager.kt` no creció en esta ventana — al contrario, la migración
a `bestEffort` lo redujo netamente (35 líneas eliminadas, sin líneas de
lógica nueva), coherente con que el commit es un refactor, no una feature.

Sin cambios en los límites de capa: `network/` sigue exponiendo únicamente
DTOs+funciones puras hacia `ui/models/`; ningún `ui/screens/` toca `network/`
directamente en este diff.

## 5. QA y bugs

Sin regresión funcional. Verificado explícitamente:
- El `Uuid.random()` de cada call-site se genera dentro de una función
  `suspend` de `FirestoreRepository`, invocada desde `screenModelScope.launch`
  — nunca dentro de un `@Composable`, por lo que no hay riesgo de
  regeneración en cada recomposición (confirmado leyendo las 4 llamadas y sus
  call-sites en `ui/models/`).
- `bestEffort` captura exactamente las mismas excepciones que el patrón
  manual que sustituye — verificado los ~18 sitios migrados contra el diff
  completo, ningún `catch` adicional ni ausente.
- `SpaceTypeTest` (v19) intacto, 2/2 tests siguen verdes dentro del recuento
  de 291.
- Build/test re-ejecutados de forma independiente en esta ronda (no solo
  confiando en el mensaje del commit): `compileDebugKotlinAndroid` y
  `jvmTest --rerun-tasks` ambos BUILD SUCCESSFUL, 291/291 antes y después del
  fix de `tag` aplicado en esta ronda.

**PROPUESTA (ligada al hallazgo de Funcionalidad #1):** priorizar en el
backlog un diseño de "acción pendiente con clave reutilizable" para los 4
flujos transaccionales antes de considerar cerrado el hallazgo CRÍTICO de v18
— hoy el 100% del valor de `idempotencyKey` se limita a proteger contra un
reintento transparente del *engine* HTTP (p. ej. un `connection reset` antes
de que el request saliera, si el engine subyacente lo reintenta de forma
transparente dentro de la misma llamada a `call()`), un caso mucho más
estrecho que el que motivó la tarjeta.

## 6. Rendimiento

Sin hallazgos. Verificado con evidencia: `Uuid.random()` es una operación
O(1) sobre una fuente de entropía local, fuera de cualquier bucle o hot path
de recomposición (una vez por acción de usuario real: donar, canjear,
completar). `bestEffort` es `suspend inline` — igual que el `orDefault` al
que sustituye, sin alocación de lambda adicional (`inline` elimina el objeto
función en tiempo de compilación, verificado que la declaración conserva
`inline` en `AppLog.kt`). El toggle de analytics sigue siendo una llamada
síncrona sin `launch`; `koinInject<ProfileScreenModel>()` con scope `factory`
crea un objeto trivial (dos referencias ya inyectadas como singletons) en
cada recomposición del `Switch`, coste despreciable y sin recomposición
adicional causada por el cambio en sí.

## 7. Estado abierto de v17/v18 (re-verificación)

Ningún hallazgo "SIGUE ABIERTO" de v18 se cierra implícitamente con los
cambios de esta ventana (ninguno tocaba las áreas afectadas). Re-verificado
contra el código real, no solo releído del informe anterior:
- `TaskScreenModel.completeTask` (`ui/models/TaskScreenModel.kt:564-713`,
  antes de esta ronda): ahora ocupa ~150 líneas (antes ~174) — la migración a
  `bestEffort` recortó boilerplate de `catch`, pero **sigue abierto**, sigue
  siendo la función más larga del archivo y el hallazgo de v18 (extraer
  lógica) no está resuelto por una reducción incidental de líneas de
  logging.
- `ui/screens/TaskListScreen.kt`: exactamente 1476 líneas, sin cambio.
- `ui/screens/CalendarScreen.kt`: `Column().forEach` confirmado todavía en
  uso para las secciones de agenda (líneas 981, 1123 entre otras), sin
  cambio — el archivo no aparece en el diff de `75243ed`.
- `Card.clickable(role=Button)` en vez de `Card(onClick=...)`: confirmado en
  11 archivos (`EditTaskScreen`, `NotificationListScreen`, `ProfileScreen`,
  `EditProfileScreen`, `CreateRewardScreen`, `HouseholdScreen`,
  `CalendarScreen`, `CreateTaskScreen`, `TaskDetailScreen`,
  `ExpandableSectionHeader`, `HouseholdTaskSection`) — mismo conteo que el
  ya actualizado en v18, sin variación en esta ventana.
- App Check decorativo, `taskHistory` sin `limit`, hogar fantasma,
  `GoogleAuthManager` con 4 responsabilidades: sin cambios (ninguno de los 12
  archivos tocados por `75243ed` se solapa con estas áreas), **SIGUEN
  ABIERTOS** sin variación de veredicto.

## 8. Estética / diseño visual

Sin cambios en esta ventana: `75243ed` no toca ningún archivo de
`ui/screens/`/`ui/components/` salvo `SettingsSheet.kt`, y ahí solo el cuerpo
de un `onCheckedChange` (lógica, no estructura visual). Todas las PROPUESTAS
estéticas de v17/v18 (`SemanticColors` fija, sin `Spacing.kt`, elevación
inconsistente, `HomeScreen` con `TopAppBar` propia, gradiente hero duplicado
en `HouseholdScreen`, avatar sin escalar, etc.) permanecen exactamente en el
estado descrito en v18 — re-verificado que ninguna requiere actualización de
veredicto.

## 9. Accesibilidad

Sin regresiones. El único archivo de UI tocado (`SettingsSheet.kt`) no
cambia el árbol de composables del `Switch` de analytics — mismo
`SwitchDefaults.colors`, mismo `checked`/`onCheckedChange` como parámetros,
solo el cuerpo interno de la lambda. Sin nuevos composables introducidos por
`75243ed` que pudieran necesitar `contentDescription`. El selector de
`SpaceType` de v19 (`FilterChip` + `clearAndSetSemantics`) no se toca en esta
ventana — sigue como quedó verificado en v19.

## 10. UX

`idempotencyKey` no tiene ningún efecto observable en la UI, confirmado: el
campo viaja en el DTO pero ningún `ScreenModel` lee ni muestra nada
relacionado con él — comportamiento idéntico al percibido por el usuario
antes y después del commit (esto es exactamente lo esperado, no un
hallazgo). El opt-out de analytics se sigue sintiendo instantáneo — la
llamada es síncrona, sin `launch`, mismo `Switch` que antes.

**PROPUESTA (sin cambio, re-listada):** hogar fantasma al abandonar el
wizard a medias, deep link a hogar sin autorización → snackbar transitorio —
siguen abiertas exactamente como en v18, ningún archivo de este commit las
toca.

## 11. Cobertura de pruebas

**Cambio desde v19:** ningún test nuevo — 291/291 sin variación, el commit
`75243ed` no añadió tests para ninguno de sus 3 cambios.

**TOP-10 actualizado (riesgo dinero/puntos primero):**
1. **Nuevo, prioridad alta:** el contrato cliente↔servidor de
   `idempotencyKey` no tiene ningún test — ni de que el campo se genera y
   viaja (unit test de `FirestoreRepository`, con un fake de
   `CloudFunctionsClient` capturando el DTO), ni, más importante, del
   hallazgo de esta ronda: no existe ningún test que documente que dos
   llamadas consecutivas a `donatePoints()`/`completeTask()` generan claves
   DISTINTAS (que es precisamente el comportamiento a corregir cuando se
   diseñe la reutilización de clave).
2. `reconcileMissingTaskPoints.ts` — sigue sin ningún test (v17 #4, v18 #TOP-1,
   sin cerrar).
3. `bestEffort` (`platform/AppLog.kt`) no tiene ningún test unitario propio
   (no existe directorio `commonTest/.../platform/`) — riesgo bajo (es un
   `try/catch` de 6 líneas) pero es la única pieza de esta ventana sin
   ninguna cobertura directa, ni siquiera indirecta vía los tests existentes
   de los `ScreenModel` que la usan (los mocks de esos tests no fuerzan el
   camino de excepción en los sitios migrados).
4. `ProfileScreenModel.setAnalyticsOptOut` sin test — no existe
   `ProfileScreenModelTest.kt` (a diferencia de `TaskScreenModelTest.kt`/
   `MemberScreenModelTest.kt`/`StatsScreenModelTest.kt`/`HomeScreenModelTest.kt`
   que sí existen); sería trivial en JVM (`setAnalyticsCollectionEnabled` es
   no-op en ese target).
5. Cierre de asignaciones "hermanas" sin acotar por ciclo (v18 QA #2, sin
   cerrar).
6. `MemberRepository.updateMemberStreak` sin test de carrera (v18, sin
   cerrar).
7. `TaskRepository.kt`/`FirestoreRepository.kt` sin test directo (v17 #5,
   sigue igual).
8. `HomeScreenModel.loadAllTasks` — `MutableSet` compartido mutado en
   paralelo sin sincronización (v18, sin cerrar).
9. `RewardsRepository.kt` sin test (v17 #8, sigue igual).
10. `CalendarSyncManager.kt`/`GoogleAuthManager.kt` sin test — agravado en
    esta ronda: ahora también sin test de que `bestEffort` se invoca
    correctamente en los 4 sitios de `GoogleAuthManager` migrados.

---

## Archivos modificados en esta ronda

**Composeapp (`composeApp/src/commonMain/`):**
- `ui/models/TaskScreenModel.kt`: 13 `tag` de `bestEffort` especificados
  (`Clase.método.operación` en vez de solo `Clase`).
- `ui/models/GoogleAuthManager.kt`: 4 `tag` especificados.
- `ui/models/NotificationScreenModel.kt`: 3 `tag` especificados.
- `ui/models/HouseholdScreenModel.kt`, `ui/models/StatsScreenModel.kt`,
  `ui/models/TaskCommentsScreenModel.kt`: 1 `tag` especificado cada uno.

Ningún cambio de lógica, tipos ni comportamiento observable — solo literales
de `String` usados como `tag` de logging.

## Verificación

```
./gradlew :composeApp:compileDebugKotlinAndroid --console=plain   → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain       → BUILD SUCCESSFUL
```
Conteo real de `composeApp/build/test-results/jvmTest/*.xml`: **291/291
tests verdes**, 0 failures, 0 errors — sin cambio antes/después del fix de
esta ronda. No se tocó `functions/` (sin cambios en esta ventana fuera de
`composeApp/`), así que no se re-ejecutó `npm test`/`npm run test:integration`.

No se hizo bump de versión ni push, según el encargo.
