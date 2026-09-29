# Panel de expertos v21 — Auditoría completa post-cambios 4-en-1 + pendientes v20 (2026-09-29)

HEAD de partida: `f1ecbd5` (v0.7.54), el commit final del encargo
`docs/pendientes-post-v20-2026-09-29.md`. Base de comparación: `75243ed`
(v0.7.52, HEAD de v20). Diff completo revisado: 36 archivos, ~1932
inserciones / 364 borrados, cubriendo 4 unidades de trabajo desde v20:

1. **"3 temas nuevos"** (Océano, Atardecer, Medianoche) + dropdown en Ajustes, commit `03f79c9` — sin auditoría de panel previa.
2. **Fix de build web** (`ByteArray`→`String` en `SecureStore.wasmJs.kt`), commit `2e7224d` — sin auditoría previa.
3. **4-en-1 técnico** (`docs/cambios-4-en-1-tecnico-2026-09-29.md`, commit `a7d083e`): auto-relleno de nombre desde Google, selector de emoji manual para hogares, idempotencyKey real reutilizable en reintentos, migración de `CalendarSyncManager` a `bestEffort`.
4. **Pendientes post-v20** (`docs/pendientes-post-v20-2026-09-29.md`, commit `f1ecbd5`): extracción de `TaskListRules.kt`, tests del TOP-10 v20, cierre de 2 tarjetas kanban.

11 especialistas, coordinados por Claude, en 3 oleadas (5+5+1, respetando el
límite de 5 subagentes simultáneos), con verificación directa contra el
código real (diffs, greps, lectura completa, cálculo independiente de
contraste WCAG, trazado de flujos servidor incluido) en vez de confiar en el
self-report de los 2 encargos.

## Resumen ejecutivo

**Verificación independiente del orquestador, ANTES de lanzar los agentes**
(no solo confiando en los informes de los encargos):
```
./gradlew :composeApp:compileDebugKotlinAndroid --rerun-tasks --console=plain → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain                    → BUILD SUCCESSFUL, 297/297 tests, 0 failures
cd functions && npm test                                                       → 54/54 passed
cd functions && npm run test:integration                                       → 9 suites, 45/45 passed
```
GraphQL contra el proyecto Kanban (`PVT_kwHOCXo7m84BjLGt`): las 2 tarjetas
que el encargo de pendientes dice haber cerrado (`[CF] Idempotency key en
completeTask`, `[Arquitectura] Extraer TaskListRules.kt`) están confirmadas
en Status=Completado. **Sin discrepancias** entre lo que ambos informes de
encargo afirman y el estado real del código — los 4+2 cambios descritos
funcionan tal como se documentan.

**Hallazgo real más importante de esta ronda (CRÍTICO/IMPORTANTE, aplicado en
esta misma ventana):** `TaskScreenModel.completeAssignment` nunca pasaba
`idempotencyKey` a `FirestoreRepository.completeAssignment` pese a que este
último lo acepta desde el propio commit `a7d083e` — cada reintento manual
tras un fallo ambiguo generaba una clave NUEVA, exactamente el mismo riesgo
de duplicar puntos que el 4-en-1 se propuso resolver, pero sin cubrir este
segundo call-site (usado en producción desde `TaskDetailScreen.kt`, tareas
con asignación). Encontrado independientemente por el especialista
"Programador senior" y confirmado por "Cobertura de tests" (sin ningún test
que lo protegiera). **Aplicado en esta ronda**: se replicó exactamente el
mismo mecanismo ya validado en `completeTask` (mapa `pendingIdempotencyKeys`,
ahora namespaced por `taskId`/`assignmentId`) + 3 tests de contrato
espejando los ya existentes. Verificado: `compileDebugKotlinAndroid` BUILD
SUCCESSFUL, `jvmTest --rerun-tasks` → **300/300 tests, 0 failures** (297 + 3
nuevos).

**Segundo hallazgo real (MENOR/UX, PROPUESTA, no aplicado — requiere
decisión de diseño):** `SettingsStore.hasGoogleLinked()` es un flag
persistido que nunca se degrada a `false` cuando `ensureCalendarAccessToken()`
falla de forma persistente (p. ej. consentimiento de Calendar revocado desde
la cuenta de Google) — con la migración a `bestEffort` de esta ronda, esos
fallos ahora quedan en el log pero siguen sin ningún efecto en el flag, así
que el usuario puede creer indefinidamente que su Calendar está sincronizado
cuando lleva tiempo sin estarlo. Encontrado por el especialista de UX.

Ningún otro hallazgo de las 11 áreas resultó en un bug explotable o una
regresión funcional — el resto son PROPUESTAS de mejora (duplicación de
código, deuda documental) o confirmaciones de que la deuda ya conocida de
v17-v20 sigue exactamente igual, sin agravarse salvo el crecimiento normal de
`TaskScreenModel.completeTask` documentado abajo.

---

## 1. Funcionalidad end-to-end

Verificados los 4 flujos del 4-en-1 leyendo el código real en cada capa, no
solo el informe del encargo:

- **Auto-relleno de nombre Google** (`CreateProfileScreen.kt:72-77`,
  `JoinHouseholdScreen.kt:77-82`): sin condición de carrera — `authState` ya
  es `SignedIn` desde el primer frame (Task Hub es Google-only, el estado se
  inicializa síncronamente desde `SettingsStore` en el constructor de
  `GoogleAuthManager`), y `GoogleAuthState.SignedIn` es `data class`, así que
  el `LaunchedEffect(authState)` no se relanza en recomposiciones sin cambio
  real de valor. El campo sigue editable.
- **Selector de emoji**: cadena completa trazada
  `DTOs.kt → FirestoreParsers.kt → HouseholdRepository.kt/FirestoreRepository.kt
  → HouseholdScreenModel.kt → HouseholdStore.kt → UI`, sin saltos de capa.
  Confirmado que `joinHousehold` (no solo `createHousehold`) también propaga
  el emoji correctamente — quien se une a un hogar ve el mismo emoji que
  eligió el creador. Hogares legacy sin el campo caen correctamente al
  fallback `?: spaceType.emoji` sin excepción.
- **idempotencyKey real**: ciclo completo verificado en `TaskScreenModel` —
  éxito nunca reinserta clave (siguiente acción genera una nueva), catch
  general sí reinserta (reintento reutiliza). Guard de doble-tap
  (`_actionState == Loading → return`) impide colisiones entre acciones
  simultáneas. **Matiz documentado, no un bug**: `TaskScreenModel.reset()`
  (que limpia el mapa) no tiene ningún call-site en pantallas de producción
  — inocuo porque `TaskScreenModel` está registrado `factory` en Koin
  (instancia nueva por pantalla vía Voyager), pero el informe del encargo da
  la falsa impresión de un hook de ciclo de vida activo que no existe.
- **CalendarSyncManager bestEffort**: confirmados los 10 sitios exactos con
  sus tags `Clase.método.operación`. El logging nuevo (`AppLog.w`) solo se
  alcanza tras superar los `?: return@bestEffort` de "sin token"/"sin
  calendarId" (casos esperables, silenciosos), así que no genera ruido para
  estados normales — solo para fallos reales de red.

**[APLICADO — ver resumen ejecutivo]** `completeAssignment` no propagaba
`idempotencyKey`, corregido en esta misma ronda.

## 2. Seguridad / AppSec (OWASP MASVS)

Sin hallazgos explotables nuevos. Foco especial en las 2 áreas sin auditoría
previa:

- **`SecureStore.wasmJs.kt`** (fix de build web, `ByteArray`→`String` para
  cruzar la frontera `@JsFun` de Kotlin/Wasm): el roundtrip vía
  `charCodeAt`/`fromCharCode` no corrompe bytes — todo el manejo ocurre en JS
  puro sobre `Uint8Array` (unsigned), sin paso por `Byte` con signo de la
  JVM/Kotlin nativo, así que el rango 0-255 está garantizado sin truncamiento.
  Sí aumenta el número de copias transitorias de la clave efímera en memoria
  JS (cada llamada reconstruye un `Uint8Array` nuevo desde el string, antes
  era una referencia directa) — higiene menor (MSTG-CRYPTO-1), sin cambio de
  modelo de amenaza: la clave ya vivía como variable de módulo accesible
  durante toda la sesión en ambas versiones, y cualquier XSS que pudiera leer
  el heap ya tendría acceso directo sin necesitar las copias extra.
- **`firestore.rules` — campo `emoji`**: confirmado por lectura directa
  (`firestore.rules:486-498`) que `households/{hid}` no valida tipo ni
  longitud de NINGÚN campo (ni `name` ni ahora `emoji`) — la afirmación del
  encargo ("entra en el match genérico") es correcta. Es una ausencia de
  validación preexistente (ya aplicaba a `name` desde antes de v18), no una
  regresión de este cambio; se deja documentada explícitamente por primera
  vez (severidad baja/informativa — abuso de almacenamiento, no XSS, ya que
  Compose no interpreta el valor como HTML/JS).
- **`SettingsStore.getGoogleDisplayName`**: persistido en texto plano
  (`settings`, no `secureStore`), consistente con el patrón ya usado para
  `email`/`uid` — correcto, un nombre no es secreto.
- **idempotencyKey**: sigue usando `Uuid.random()` (CSPRNG por plataforma).
  Los únicos call-sites reales (`TaskScreenModel`, `MemberScreenModel`) nunca
  aceptan una clave controlada por el usuario. Nota colateral verificada, no
  de esta ronda: `withIdempotency` (`functions/src/idempotency.ts:64`) no
  valida que `functionName` coincida en el replay — autolesión posible si un
  cliente reutilizara deliberadamente una clave entre dos funciones
  distintas, sin fuga entre usuarios.
- **App Check** re-verificado por el especialista de deuda abierta: sigue
  siendo la deuda de mayor entidad de esta área — solo configurado en cliente
  (Play Integrity), sin enforcement en Cloud Functions ni `firestore.rules`.

## 3. Programador senior

**[APLICADO en esta ronda]** `TaskScreenModel.completeAssignment` (línea
874) nunca pasaba `idempotencyKey` a `repo.completeAssignment` — ver resumen
ejecutivo. Fix + 3 tests de contrato aplicados, mismo patrón que
`completeTask`.

**[APLICADO, sin acción — refactor limpio]** `TaskListRules.kt`: extracción
limpia, cero imports de Compose, nombres claros, helpers privados bien
acotados. `TaskListScreen.kt` importa los símbolos sin residuos.

**[PROPUESTA]** La extracción de `TaskListRules.kt` quedó incompleta: el
cálculo de `isOverdue` (`TaskListScreen.kt:396-433`, usa
`RecurrenceRules.isOverdueOccurrence`) es lógica pura del mismo tipo que la
ya extraída, pero se quedó dentro del `remember` de la pantalla — un tercio
del cálculo de estado de tarea sigue mezclado con Compose.

**[PROPUESTA]** Asimetría menor entre `MemberScreenModel.donatePoints`
(reutiliza la clave incluso en errores de validación deterministas, p. ej.
`AMOUNT_EXCEEDS_LIMIT`) y `redeemReward` (no la reutiliza en
`InsufficientBalanceException`). Verificado contra
`functions/src/idempotency.ts` que ambos comportamientos son inofensivos
(el servidor libera la reserva en cualquier excepción), pero el criterio no
es el mismo entre los dos métodos gemelos.

**[APLICADO, sin acción]** Migración de `CalendarSyncManager.kt` a
`bestEffort`: los ~10 tags siguen el patrón `Clase.método.operación`
exigido en v20, todos los `default` coinciden exactamente con el `catch`
manual original (verificado contra `git show a7d083e`). Como bonus no
documentado en el encargo: `GoogleAuthManager.kt` y
`HouseholdScreenModel.kt` también migraron sus tags genéricos al mismo
estándar en este commit.

**[PROPUESTA]** `App.kt`: el `try/catch valueOf` de mapeo de
`TaskHubThemeType` está duplicado 2 veces dentro del propio archivo (splash
+ estado reactivo) — 3 líneas × 2, no urge extraer, pero es candidato a una
función `themeTypeFromSettings(store)`.

**Sin hallazgos**: `SettingsStore.kt`/`DTOs.kt`/`HouseholdStore.kt` para
Google display name/emoji sin code smells. `SecureStore.wasmJs.kt`: el
cambio central es correcto; el rename `enc`→`encDecrypt` es churn cosmético
sin relación con el fix puntual, ruido menor sin impacto.

## 4. Jefe de arquitectura

**[SIGUE ABIERTO, empeoró levemente]** `TaskScreenModel.completeTask` creció
a 161-163 líneas (antes ~150 en v20) por el añadido de
`pendingIdempotencyKeys` — sigue siendo, con margen, la función más larga
del archivo (1434 líneas). El hallazgo v18 ("extraer lógica") no solo sigue
sin resolver sino que la brecha creció en esta ventana.

**[PROPUESTA]** Duplicación literal (carácter por carácter, no solo el
`?:`) del fallback `household.emoji ?: household.spaceType.emoji` +
interpolación de nombre en `HouseholdTaskSection.kt:91` y
`ProfileScreen.kt:204` — candidato directo a una propiedad de extensión
`SavedHousehold.displayLabel` en `storage/HouseholdStore.kt`, centralizando
la regla de negocio en una capa en vez de en cada Composable.

**[APLICADO, sin acción]** `TaskListRules.kt` respeta el límite de capa
"lógica pura sin Compose" de CLAUDE.md, siguiendo el precedente ya existente
de `DateHelpers.kt` (lógica atada a pantallas concretas en `ui/components/`,
distinto del precedente de `Achievement.kt`/`TaskCsvExporter.kt` en
`ui/models/` para lógica de dominio reutilizable). **[PROPUESTA]**: el
repo tiene ambos precedentes sin una regla documentada que distinga cuándo
usar cada uno — valdría la pena fijarlo en CLAUDE.md.

**[APLICADO, sin acción]** `GoogleAuthManager.kt` se mantiene en las mismas
~4 responsabilidades del veredicto v20 (auth Google, sync de hogares,
Calendar/token revocation, persistencia de settings) — el nuevo campo
`displayName` es un dato adicional dentro de la responsabilidad de login ya
existente, no una responsabilidad nueva. Registro Koin (`single` +
`koinInject`, no `factory`) correcto, sin riesgo de instancia duplicada.

## 5. QA y bugs

Sin regresiones funcionales verificadas contra el diff completo.

**[APLICADO, sin acción]** Guard de doble-tap en `completeTask` correcto
(síncrono, `screenModelScope` con `Dispatchers.Main.immediate`) — aunque es
global al ScreenModel (no por `taskId`), más restrictivo de lo
estrictamente necesario, no es un bug.

**[APLICADO, sin acción]** Reutilización de idempotencyKey tras error real
de `donatePoints` verificada inofensiva contra el servidor real
(`functions/src/idempotency.ts:74-81` libera la reserva ante cualquier
excepción de `fn()`).

**[SIGUE ABIERTO, riesgo bajo]** `emoji == ""` (en vez de `null`) no cae al
fallback `?: spaceType.emoji` — hoy inalcanzable desde la UI (el grid de 24
emojis nunca produce cadena vacía, no hay botón de deselección), pero sin
test que fije el contrato para cuando se añada esa opción en el futuro.

**[SIGUE ABIERTO]** `isTaskDueToday`/`isTaskCompletedToday` se extrajeron a
`TaskListRules.kt`, pero el cálculo de "medianoche local de hoy"
(`todayStartEpoch`, sensible a zona horaria/DST) se quedó dentro del
`@Composable` de `TaskListScreen.kt:396-400` — sigue intestable con unit
test pese al objetivo declarado del refactor. Contraste útil:
`HomeScreenModel.isPending()` sí extrajo ese mismo tipo de cálculo fuera del
Composable.

**[APLICADO, sin acción]** `CalendarSyncManager` bestEffort: los 10
`default` migrados coinciden exactamente con el `catch` original,
`return@bestEffort` produce el mismo efecto observable que el `return`
previo en los 10 casos (siempre última sentencia de la función).

**[APLICADO, sin acción]** Los 3 temas nuevos (`Theme.kt`) sin placeholders
ni campos vacíos — los 26 slots de M3 rellenos en los 6 esquemas.

## 6. Rendimiento

**Sin hallazgos en ninguno de los 5 focos de esta ventana.**

- `groupTasksByStatus`: confirmado *move* byte-idéntico (diff línea a línea
  contra la versión pre-refactor), misma memoización (`remember` con las
  mismas keys) preservada.
- `pendingIdempotencyKeys`: `mutableMapOf` normal correcto — todo acceso
  confinado a `screenModelScope` (dispatcher único), sin necesidad de
  estructura thread-safe.
- Grid de emoji (24 items, no lazy): correcto por tamaño trivial y por vivir
  dentro de `AnimatedVisibility` (solo se compone al desplegar).
- Los 12 `ColorScheme` (6 temas × claro/oscuro) son `private val` top-level,
  construidos una sola vez — sin riesgo de reconstrucción en recomposición.
- `AppLog.w` de `bestEffort` sin coste en el camino feliz (no se evalúa en
  éxito); en fallo, coste proporcional a errores reales de I/O ya fallido,
  sin interpolación cara.

## 7. Deuda abierta v17-v20 (re-verificación dedicada)

Re-verificados los 7 puntos contra el código actual, uno por uno, con
evidencia fresca (no releída del informe anterior):

1. **App Check decorativo — SIGUE ABIERTO, sin cambio.** Solo cliente
   (`MainActivity.kt:162-164`, Play Integrity); cero enforcement en
   `functions/` ni `firestore.rules`.
2. **`taskHistory` sin `limit` — SIGUE ABIERTO, sin cambio.**
   `TaskRepository.kt:322` sigue sin paginación.
3. **Hogar fantasma — SIGUE ABIERTO, confirmado el flujo completo.** El
   hogar se crea en Firestore (`HouseholdRepository.createHousehold`) antes
   de que `CreateProfileScreen` pida crear el `member` — abandonar el wizard
   entre esos dos puntos deja un hogar huérfano. Sin mitigación nueva.
4. **`GoogleAuthManager` con 4 responsabilidades — SIN CAMBIO SUSTANCIAL.**
   +2 líneas netas en el diff real; el campo `displayName` es dato adicional
   de una responsabilidad ya existente, no una quinta responsabilidad.
5. **`Card.clickable(role=Button)` — MISMO CONTEO (11) PERO COMPOSICIÓN
   DISTINTA.** Salieron `EditTaskScreen`/`CreateTaskScreen`/`TaskDetailScreen`
   (ahora usan `Role.Checkbox`/`Role.RadioButton`), entraron
   `HouseholdMemberList`/`CreateHouseholdScreen`(selector de emoji nuevo)
   /`TaskListScreen`. Magnitud igual, composición del conjunto distinta.
6. **`CalendarScreen.kt` con `Column().forEach` — SIGUE ABIERTO, archivo no
   tocado.** `git log 75243ed..HEAD -- CalendarScreen.kt` no devuelve ningún
   commit — 1458 líneas sin cambio.
7. **`TaskScreenModel.completeTask` — CRECIÓ (161-163 líneas, antes ~150).**
   Coincide con el hallazgo de Arquitectura §4 — el crecimiento es
   exactamente el mecanismo de `pendingIdempotencyKeys` añadido en el 4-en-1.

Ninguno de los 7 puntos se cerró en esta ventana; el refactor de
`TaskListRules.kt` no tocó ninguno de ellos, tal como predecía el propio
encargo.

## 8. Estética / diseño visual (UI/Material3)

**[APLICADO, sin acción]** Los 6 `ColorScheme` (3 nuevos + 3 existentes)
completos y coherentes, mismo patrón de construcción, sin roles de M3
huérfanos.

**[PROPUESTA]** `SemanticColors.info` (0xFF1565C0) coincide exactamente con
`OceanBlue800`, el `primary` del tema Océano — en ese tema, cualquier
badge/chip "info" es del mismo color que los botones primarios, pierde
distinción semántica (sin ser un problema de contraste). Mismo KDoc
desactualizado de `SemanticColors.kt` ("común a los 3 themes") ya señalado
como deuda en v20, ahora con un caso concreto y visible.

**[PROPUESTA]** El tema Medianoche usa fondo oscuro incluso en su variante
"clara" (decisión de diseño documentada) — efecto colateral: en Android,
`enableEdgeToEdge()` fija el color de los iconos de la barra de estado
según el modo día/noche del SISTEMA, no según el tema elegido en la app. Un
usuario con sistema en modo claro que elija Medianoche podría tener iconos
de barra de estado con bajo contraste. No introducido por este commit
(mecanismo preexistente), pero Medianoche es el primer tema que lo expone.

**[PROPUESTA]** Inconsistencia de patrón en `SettingsSheet.kt`: el nuevo
dropdown de Tema convive con `RadioOptionRow` para Idioma/Tema del widget en
la misma hoja — dos paradigmas visuales distintos para el mismo tipo de
decisión, defendible por número de opciones (6 vs 2-3) pero sin criterio
documentado.

**[APLICADO, sin acción]** Selector de emoji en `CreateHouseholdScreen.kt`:
réplica fiel línea a línea de `EditProfileScreen.kt`, sin divergencia de
estilo, buena integración de spacing con el resto de la pantalla.

**[PROPUESTA, menor]** `CreateHouseholdScreen.kt` reutiliza la clave i18n
`edit_profile_emoji_content_desc` (de `EditProfileScreen`) para su propio
`contentDescription` — acoplamiento de nomenclatura entre pantallas no
relacionadas semánticamente.

## 9. Accesibilidad

**[APLICADO, sin acción]** Contraste WCAG de los 3 temas nuevos verificado
**independientemente** (fórmula de contraste relativo WCAG 2.1 sobre los 34
pares texto/fondo más usados, no solo los comentarios del código): mínimo
4.97:1 para texto normal (umbral 4.5:1), mínimo 3.79:1 para elementos
no-textuales (umbral 3:1) — todos los pares pasan AA.

**[APLICADO, sin acción]** Selector de emoji: cada celda con
`contentDescription` accesible (no depende de interpretar el glifo),
`selected` semántico, touch target 48dp. Dropdown de tema usa el patrón M3
correcto (`ExposedDropdownMenuBox`), expone rol de combo-box a TalkBack,
selección actual anunciada vía el valor del `OutlinedTextField`.

**[PROPUESTA]** Auto-relleno de nombre Google sin `liveRegion` — un usuario
de TalkBack que no enfoque explícitamente el campo no se entera de que se
rellenó solo, riesgo de enviar un nombre sin revisar.

**[SIGUE ABIERTO, heredado, no agravado]** El botón que despliega el grid de
emoji no lleva semántica de estado expandido/colapsado — mismo patrón
preexistente en `EditProfileScreen.kt` (no tocado en esta ventana),
replicado literalmente en el componente nuevo.

**[APLICADO, sin acción]** Ningún código nuevo de esta ventana extiende el
antipatrón `Card.clickable(role=Button)` — el selector de emoji usa
`Surface.clickable`, el dropdown de tema usa el componente M3 correcto.
Ítems históricos ("placeholder sin label persistente", "mensajes de error
sin liveRegion") confirmados sin regresión en el código nuevo.

## 10. UX

**[APLICADO, sin acción]** Auto-relleno de nombre Google: sin parpadeo
perceptible (estado ya estable al montar la pantalla), edición tan fácil
como cualquier campo normal. **[PROPUESTA, menor]**: sin indicio visual de
que el campo se autorellenó — oportunidad de claridad perdida de bajo coste.

**[APLICADO, sin acción]** Selector de emoji: descubrible, proporcional a su
importancia (0-2 taps). **[PROPUESTA, menor]**: el botón cerrado dice
"Elegir emoji" en vez de mostrar el emoji efectivo por defecto (el del
`SpaceType`), dando la falsa impresión de que no hay ninguno elegido.

**[APLICADO, sin acción]** Dropdown de 6 temas: preview de color en la
propia lista (sin fricción de prueba-error), cambio instantáneo sin
reinicio.

**[PROPUESTA — ver resumen ejecutivo]** `hasGoogleLinked` nunca se degrada
ante fallo persistente de token de Calendar — el usuario puede creer que su
sincronización sigue activa sin ninguna señal de que lleva tiempo fallando
en silencio vía `bestEffort`. Requiere decisión de diseño (¿degradar el
flag tras N reconciles fallidos consecutivos? ¿mostrar un aviso en
Ajustes?) — no aplicado en esta ronda.

**[SIGUE ABIERTO, confirmado sin cambios]** Hogar fantasma y deep link sin
autorización → snackbar transitorio: ningún archivo de esos flujos se tocó
en esta ventana (confirmado con `git diff --stat`).

## 11. Cobertura de pruebas

**Cambio desde v20:** el hueco #1 de v20 ("idempotencyKey sin test") se
cierra para `completeTask` (3 tests nuevos en `TaskScreenModelTest.kt`,
verificados leyendo el código) y, tras el fix aplicado en esta misma ronda,
también para `completeAssignment` (3 tests más, mismo patrón). El hueco #2
de v20 (`reconcileMissingTaskPoints.ts` sin test) se cierra con 5 tests de
integración contra emulador. El hueco #3 de v20 (`bestEffort` sin test
unitario) se cierra con `AppLogTest.kt` (3 tests). **Total tras esta
ronda: 300 tests Kotlin** (291 en v20 → 297 tras el encargo de pendientes →
300 tras el fix de `completeAssignment` de este panel), **45 tests de
integración** en `functions/` (antes 40).

Ningún test nuevo cubre `MemberScreenModel` (sigue en 3 tests, sin tocar
pese a tener el mismo patrón de `pendingIdempotencyKeys` que `TaskScreenModel`),
`HouseholdScreenModel` (0 tests, sin archivo) ni `GoogleAuthManager` (0
tests, sin archivo) — los cambios de esta ventana en esas tres áreas quedan
sin cobertura directa, solo verificados por lectura de código en esta
auditoría.

**TOP-10 actualizado (riesgo dinero/puntos primero):**

1. **[Cerrado en esta ronda]** ~~`completeAssignment` sin `idempotencyKey`~~
   — corregido + 3 tests añadidos (ver resumen ejecutivo).
2. **Contrato `idempotencyKey` cubierto solo para `TaskScreenModel`, NO para
   `MemberScreenModel.donatePoints`/`redeemReward`.** Misma lógica de
   reutilización de clave (líneas 336-357, 439-466), verificada por LECTURA
   de código en esta ronda como inofensiva contra el servidor, pero sin
   ningún test que lo documente ni proteja contra regresión futura — el
   patrón de los 3 tests de `TaskScreenModelTest` es directamente replicable.
3. **`todayStartEpoch` (medianoche local) sigue intestable con unit test.**
   El refactor de `TaskListRules.kt` no llegó a extraer el cálculo real de
   "hoy", solo las funciones que lo consumen — contraste con
   `HomeScreenModel.isPending()`, que sí extrajo ese mismo tipo de cálculo.
4. **`hasGoogleLinked` sin degradar tras fallo persistente — sin test.**
   Ligado al hallazgo de UX de esta ronda; sin `GoogleAuthManagerTest.kt`
   que documente el comportamiento actual (ni el deseado).
5. **`HouseholdScreenModel` sin ningún archivo de test.** La propagación de
   `emoji` en `createHousehold`/`joinHousehold` (feature completa de esta
   ventana) solo se verificó por lectura de código, nunca por test
   automatizado — a diferencia de `TaskScreenModelTest`/
   `MemberScreenModelTest`/`StatsScreenModelTest`/`HomeScreenModelTest`, que
   sí existen.
6. **`emoji == ""` no cae al fallback — sin test que fije el contrato.**
   Hoy inalcanzable desde la UI, pero sin protección si se añade una opción
   de deseleccionar en el futuro.
7. **`CalendarSyncManager.kt`/`GoogleAuthManager.kt` sin test propio**
   (heredado v20) — agravado: el fix `bestEffort` de esta ronda en
   `GoogleAuthManager` sigue sin test de sus call-sites, solo cubierto en
   aislamiento por `AppLogTest.kt`.
8. **`MemberRepository.updateMemberStreak` sin test de carrera** (heredado
   v18/v20) — relevante porque `completeAssignment` (ahora corregido en
   idempotencia) también invoca este método en el mismo flujo.
9. **`TaskRepository.kt`/`FirestoreRepository.kt` sin test directo**
   (heredado v17/v20) — el nuevo parámetro `idempotencyKey` de
   `completeAssignment` tampoco tiene test de `FirestoreRepository` en
   aislamiento (solo vía los fakes de `ScreenModelTest`).
10. **Cierre de asignaciones "hermanas" sin acotar por ciclo** (heredado
    v18) y **`RewardsRepository.kt` sin test** (heredado v17) — sin cambios
    ni tests nuevos esta ronda, fuera del alcance de los cambios de esta
    ventana.

**Nota de infraestructura:** el proyecto no tiene Compose UI testing (cero
`createComposeRule`/`runComposeUiTest` en todo `composeApp/src/`) — todos
los 300 tests son JVM unit tests sobre `ScreenModel`/funciones extraídas.
El selector de emoji y el auto-relleno de nombre Google no son candidatos
realistas a test dedicado dentro del patrón actual del proyecto; su
cobertura seguirá dependiendo de extraer lógica pura (como ya se hizo con
`TaskListRules.kt`), no de tests de UI.

---

## Archivos modificados en esta ronda

Fix aplicado tras el hallazgo de "Programador senior" (§3) y confirmado por
"Cobertura de tests" (§11):

- `composeApp/src/commonMain/kotlin/org/taskhub/ui/models/TaskScreenModel.kt`
  — `completeAssignment` ahora reutiliza `pendingIdempotencyKeys` (namespaced
  por `assignmentId`), mismo mecanismo que `completeTask`.
- `composeApp/src/commonTest/kotlin/org/taskhub/ui/models/FakeFirestoreRepository.kt`
  — `completeAssignmentError` + `completeAssignmentIdempotencyKeys` para
  soportar los tests nuevos.
- `composeApp/src/commonTest/kotlin/org/taskhub/ui/models/TaskScreenModelTest.kt`
  — 3 tests nuevos: `completeAssignment_exito_propagaUnaIdempotencyKeyNoVaciaAlRepo`,
  `completeAssignment_fallaAmbiguoYReintenta_reutilizaLaMismaIdempotencyKey`,
  `completeAssignment_exito_laSiguienteAccionGeneraClaveNueva`.
- `docs/v21-progreso.md` (checkpoints intermedios de las 3 oleadas).
- `docs/review-panel-expertos-2026-09-29-v21.md` (este informe).

Ningún otro hallazgo de las 11 áreas se aplicó en esta ronda — el resto son
PROPUESTAS que requieren decisión de diseño/producto (listadas arriba,
sección por sección) o confirmaciones de deuda ya abierta sin variación.

## Verificación final

```
./gradlew :composeApp:compileDebugKotlinAndroid --rerun-tasks --console=plain → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain                    → BUILD SUCCESSFUL
```
Conteo real de `composeApp/build/test-results/jvmTest/*.xml`: **300/300
tests verdes**, 0 failures, 0 errors (297 antes del fix de esta ronda + 3
nuevos de `completeAssignment`).

No se tocó `functions/` en esta ronda (el fix es exclusivamente Kotlin), así
que `npm test` (54/54) y `npm run test:integration` (45/45, 9 suites) se
mantienen en el estado ya verificado al inicio de la ronda, sin necesidad de
re-ejecución.

No se hizo bump de versión ni push, según el encargo.
