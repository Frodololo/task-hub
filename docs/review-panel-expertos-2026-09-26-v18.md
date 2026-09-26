# Panel de expertos v18 — Auditoría integral (2026-09-26)

Auditoría integral nueva de Task Hub. HEAD real de partida: `95f6aef` (v0.7.49) —
4 commits por delante del `9609a17` citado en el encargo original (`718a4bf`,
`f767b2b`, `01f3cf4`, `95f6aef`: informe CF transaccionales, App Check +
SecureStore + deleteAccount server-side, AdMob UMP/TCF, analytics opt-out — ninguno
auditado hasta ahora). 13 especialistas + 1 verificador dedicado del estado de
v17, coordinados por Claude, en 3 oleadas (5+4+4, más 3 relanzados tras un corte
por límite de sesión de la API a mitad de la 3ª oleada — se retomaron sin
pérdida de contexto). Cada hallazgo indica archivo:línea, y su estado final:
**APLICADO** (fix ya en el código, verificado con build+test), **PROPUESTA**
(requiere decisión de producto/diseño/legal/infraestructura o refactor amplio,
no aplicado), o **NO APLICADO** (bajo riesgo/cosmético, documentado).

## Resumen ejecutivo

- **Aplicados en esta ronda:** 1 bug CRÍTICO de integridad de puntos
  (`undoTaskCompletion.ts` podía dejar `totalPoints` negativo), 1 cota de
  seguridad en `firestore.rules` (racha sin tope, mismo patrón que
  `totalPoints`/`appreciationGiven`), 2 fixes de accesibilidad WCAG AA
  (contraste `colorScheme.tertiary` crudo en `WelcomeScreen.kt`/
  `TaskDetailScreen.kt`), 1 affordance de UX (`RankingScreen` ahora navega al
  perfil público, igual que `HouseholdMemberList`), 1 fix de UX menor
  (diálogo de transferencia no se cierra tocando fuera mientras está en
  vuelo), 1 bug funcional menor (`HouseholdScreenModel.sendMessage` podía
  pisar un mensaje nuevo con uno viejo fallido), 2 fixes de rendimiento
  (`ProfileScreen` con el mismo `remember` hoisted que ya se aplicó en
  `HomeScreen` en v17), 7 fixes de red/offline (invalidación de caché movida
  a `try/finally` en 6 escrituras de `MemberRepository`/`HouseholdRepository`/
  `RewardsRepository`/`NotificationRepository`, más reintento en 2 lecturas
  de `GoogleCalendarRepository`), 1 deduplicación (`streakFireFontSize`
  compartida), 1 limpieza de código muerto (`CompleteAssignmentRequest.
  expectedUpdateTime`, nunca leído en servidor) y 1 limpieza de
  instanciación manual fuera de Koin (`MainActivity`).
- **Hallazgos nuevos de alto impacto dejados como PROPUESTA** (requieren
  refactor no trivial o decisión de producto, no correcciones puntuales
  seguras): App Check instalado en cliente pero **decorativo** — nunca se
  adjunta a ninguna petición REST/Ktor y ningún Cloud Function lo exige;
  activar "enforcement" en Firebase Console hoy rompería el 100% de las
  peticiones. `idempotencyKey` soportado en las 4 Cloud Functions
  transaccionales pero **nunca generado/enviado por el cliente Kotlin** —
  la única defensa contra doble-ejecución por reintento tras fallo ambiguo
  está desconectada. `checkAndAwardAchievements` relee `taskHistory`
  completo (sin `limit`) en el hot path de `completeTask()`, no solo al
  abrir Stats. `HomeScreenModel.loadAllTasks` muta un `MutableSet` desde
  `async{}` en paralelo sin sincronización, y el resultado (qué hogares
  fallaron al cargar) nunca llega a la UI. Abandonar a medias el wizard de
  creación/unión a hogar deja "hogares fantasma" sin miembro.
- **Verificación final:** `compileDebugKotlinAndroid` → BUILD SUCCESSFUL.
  `jvmTest` → BUILD SUCCESSFUL, **289/289 tests verdes** (conteo real de los
  XML, no solo UP-TO-DATE). `functions: npm run build` → sin errores.
  `functions: npm test` → 54/54 unitarios verdes. `functions: npm run
  test:integration` (contra emulador real de Firestore) → **8/8 suites,
  40/40 tests verdes**, incluido `undoTaskCompletion.integration.test.ts`
  tras el fix de clamp de puntos.

---

## Estado de los hallazgos de la ronda v17 (verificación punto por punto contra el código real)

### Estética
1. `SemanticColors` fija verde/ámbar/azul en los 3 temas — **SIGUE ABIERTO** (`ui/theme/SemanticColors.kt:22-83`).
2. Sin `Spacing.kt` — **SIGUE ABIERTO** (no existe el archivo).
3. Elevación de card inconsistente (0/1/2/4dp) — **SIGUE ABIERTO**.
4. `HomeScreen` con `TopAppBar` propia en vez de `TaskHubTopBar` — **SIGUE ABIERTO** (`HomeScreen.kt:107`).
5-8. Menores (fontSize literal Splash, iconos sin escala, empty states inline Calendar, comentarios changelog acumulados) — **SIGUEN ABIERTOS**, sin cambios.

### Funcionalidad
3. `FLUJOS-PRINCIPALES.md` desactualizado — **SIGUE ABIERTO, y PEOR**: tras `c400bed`/`e697c10` cita código de `redeemReward` que ya no existe (se migró a Cloud Function).
4. `reassignTaskCompletion.ts` sin rechazo explícito de `completedBy == null` — **SIGUE ABIERTO** (línea 48).

### Accesibilidad
4. `ShouldReduceMotion.jvm.kt` siempre `false` — **SIGUE ABIERTO** (limitación de plataforma documentada, sin cambios).

### UI/componentes
1. `Card(...).clickable(role=Button)` en 6+ archivos en vez de `Card(onClick=...)` — **SIGUE ABIERTO** (incluso más sitios que en v17: CalendarScreen, EditProfileScreen, CreateRewardScreen, ExpandableSectionHeader se suman a los 6 originales).
3. `taskHubTextFieldColors()` infrautilizado — **SIGUE ABIERTO**, sin cambios.

### UX
2. Deep link a hogar sin autorización → snackbar transitorio — **SIGUE ABIERTO**.
3. `CalendarScreen` `Column().forEach` en vez de `LazyColumn` (2 secciones) — **SIGUE ABIERTO**.
5. `EditProfileScreen` selector de emoji con chunking manual — **SIGUE ABIERTO**.

### Programador senior
1. AES-256-CTR manual en `SecureStore.wasmJs.kt` — **SIGUE ABIERTO**. El commit `f767b2b` (título "SecureStore SubtleCrypto") en realidad **documentó que la migración NO es viable** (`docs/seguridad-privacidad-2026-09-26.md`) y no tocó el código — el título del commit es engañoso respecto al alcance real.
2. Logger multiplataforma (Napier) — **YA RESUELTO** (de hecho ya lo estaba antes de v17: `platform/AppLog.kt` existía commiteado, mezclado en `da49aa8 wip: checkpoint kanban-refactor-ux.md`; ver `docs/logger-napier-2026-09-26.md`).
5. `TaskScreenModel.completeTask` larga — **SIGUE ABIERTO**, ha crecido a ~174 líneas.
6. Casts `as` redundantes en `when` — sin evidencia de cambio, se asume **SIGUE ABIERTO**.
7. 8 `!!` redundantes — **SIGUE ABIERTO**, mismo conteo exacto; revisados uno a uno en esta ronda, todos objetivamente seguros (detrás de guard explícito en el mismo scope).
8. `Clock.System.now()` no inyectable — **SIGUE ABIERTO** en 20 archivos.

### Arquitectura
2. Divergencia TZ cliente/servidor — **SIGUE ABIERTO** (decisión de producto pendiente).
3. Port manual TS/Kotlin sin test de paridad; `PenaltyRules.kt` huérfano — **SIGUE ABIERTO** en ambos aspectos, confirmado sin call-sites de producción.
4. Lógica de negocio en `TaskListScreen.kt` — **SIGUE ABIERTO**, el archivo creció a 1476 líneas. Existe un `TaskListRules.kt` nuevo pero es OTRA cosa (reglas de permisos, no la extracción de lógica pura pedida) — no cierra el hallazgo.
7. `wasmJsMain` no documentado en `CLAUDE.md` — **SIGUE ABIERTO**.

### QA
2. Cierre de asignaciones "hermanas" sin acotar por `dueDate`/ciclo — **SIGUE ABIERTO**, prioridad alta sin aplicar.

### Seguridad
2. Ausencia de Firebase App Check — **SIGUE ABIERTO, PARCIAL Y AHORA MÁS PELIGROSO**: el commit `f767b2b` instaló el SDK cliente (`PlayIntegrityAppCheckProviderFactory` en Android) pero **ningún header de App Check se adjunta jamás** en `FirestoreClient.kt`/`CloudFunctionsClient.kt`, y **ninguna Cloud Function exige `enforceAppCheck`**. Es decorativo hoy — ver hallazgo nuevo de arquitectura más abajo (landmine operativo).
3. `isPeerPointsTransfer` permite auto-editar `totalPoints` vía REST directo — **SIGUE ABIERTO EN LAS REGLAS**: los commits `e697c10`/`c400bed` migraron el CLIENTE (donatePoints/redeemReward ya no llaman a esa ruta), pero `firestore.rules` no se tocó — un script/cliente modificado que llame a la ruta REST directa sigue pudiendo escribir `totalPoints` igual que antes.
4. `SecureStore` Android fallback sin cifrar — **SIGUE ABIERTO, PARCIAL**: solo se añadió logging (`AppLog.w`), no cambió el comportamiento.
5. Códigos de invitación sin rate-limit — **SIGUE ABIERTO**.

### Privacidad
1. Sin flujo UMP/TCF — **APLICADO Y COMPLETO** (`platform/ConsentManager.kt`, gating real verificado en `AdController.android.kt` y `MainActivity.kt:184`).
2. Analytics sin opt-out — **APLICADO Y COMPLETO** (`SettingsSheet.kt:695-718` + `SettingsStore.isAnalyticsOptOut()` + `Analytics.android.kt`).
3. Scope de Google Calendar completo (`auth/calendar`) — **SIGUE ABIERTO**, sin cambios.
4. Purga TTL lazy — **APLICADO** (fuera de v17, en commits intermedios no auditados hasta ahora): `functions/src/purgeOldRecords.ts` ya es un `onSchedule("every 24 hours")` real.
5. `deleteMember` conserva `userId` real — **SIGUE ABIERTO**, sin cambios.
6. Texto de borrado no menciona Calendar — **SIGUE ABIERTO**, sin cambios.

### Red/offline
3. `updateTask` sin concurrencia optimista — **APLICADO** (fuera de v17, commit `ced623b`): relee documento, `currentDocument.updateTime` como precondición, `TaskConflictException` tras agotar reintentos.
4. `createTask` + `assignTask` no atómico — **SIGUE ABIERTO**.
5. `isOnline()` no consultado antes de escrituras — **SIGUE ABIERTO en general**, con una excepción nueva puntual (`GoogleAuthManager.deleteAccount` ya lo consulta).
6. Escrituras no reintentadas — **SIGUE ABIERTO** (decisión deliberada documentada).

---

## 1. Estética y diseño visual

### IMPORTANTE — PROPUESTA (nuevo)
1. `UserAvatar` (`ui/components/UserAvatar.kt:77-86`) usa `titleMedium` fijo para el fallback (emoji/inicial) sin escalar con el parámetro `size` — desproporcionado en el avatar "hero" de 96dp (`EditProfileScreen.kt:178`, `PublicProfileScreen.kt`) y con riesgo de recorte en el de 32dp (`TaskDetailScreen.kt:1472`). PROPUESTA: escalar el estilo según `size`, requiere verificación visual en los 3 puntos de uso.
2. `HouseholdScreen.kt:546-557` reimplementa a mano el mismo gradiente "hero" que `PointsBadge.kt:122-125` ya resolvió con salvaguarda de luminancia (tras un incidente documentado de contraste roto en Naturaleza oscuro) — aquí se reintrodujo el patrón antiguo sin esa salvaguarda. Contraste hoy ≥4.5:1 en los 6 temas/modo, pero con margen reducido (4.89:1 en Naturaleza claro, el más ajustado). PROPUESTA: extraer `gradientBrush()` a un sitio compartido.

### MENOR — PROPUESTA (nuevo)
3. `PublicProfileScreen.kt:79,120`/`EditProfileScreen.kt:128` siguen con `CircularProgressIndicator` genérico en su carga a página completa, mientras 8 pantallas ya migraron a `ShimmerList`. PROPUESTA: diseñar un skeleton para el layout de perfil.

*(El resto de hallazgos de esta categoría — SemanticColors fija, sin Spacing.kt, elevación inconsistente, etc. — ver "Estado de v17" arriba, siguen abiertos sin cambio de veredicto.)*

---

## 2. Funcionalidad end-to-end

### CRÍTICO — PROPUESTA (nuevo, alcance mayor)
1. **Toda la infraestructura de reintento seguro (`idempotencyKey`) del servidor está muerta en el cliente.** Las 4 Cloud Functions transaccionales (`completeRecurringTask.ts`, `completeAssignment.ts`, `redeemReward.ts`, `donatePoints.ts`) aceptan `idempotencyKey?: string` vía `withIdempotency()`, con tests de integración dedicados — pero ningún DTO cliente (`network/models/FunctionDtos.kt`) tiene ese campo, y `CloudFunctionsClient.call()` nunca lo genera. Cuando una llamada falla de forma AMBIGUA (timeout/IOException), el usuario puede reintentar manualmente y, si la primera llamada sí se aplicó en el servidor, duplicar la donación/canje/compleción. PROPUESTA de prioridad alta: añadir el campo a los 4 DTOs + generar UUID v4 en cada call-site.

### MENOR — Fix aplicable (aplicado en esta ronda, ver sección de fixes)
2. `HouseholdScreenModel.sendMessage` (`ui/models/HouseholdScreenModel.kt:329-351`) podía sobreescribir en silencio un mensaje NUEVO que el usuario ya había empezado a escribir con el texto de un intento fallido anterior. **APLICADO**: el `catch` ahora solo restaura si el campo sigue vacío.

### MENOR — SIGUE ABIERTO / agravado (ver "Estado de v17")
3. `FLUJOS-PRINCIPALES.md` ahora describe código eliminado, no solo líneas desactualizadas.

---

## 3. Accesibilidad WCAG AA

### IMPORTANTE — Fix aplicable (APLICADO en esta ronda)
1. `WelcomeScreen.kt:149` — botón "Unirme a un hogar" con `colorScheme.tertiary` crudo: **4.19:1** en Naturaleza claro, bajo el umbral 4.5:1 — mismo anti-patrón ya corregido en v17 en otro sitio de `TaskDetailScreen.kt`, reaparecido aquí sin tocar. **APLICADO**: sustituido por `semanticColors.info`.
2. `TaskDetailScreen.kt:844` — texto "Pendiente" con `colorScheme.tertiary` crudo, misma cifra de contraste, a 20 líneas del fix ya aplicado en v17 para el badge "obligatoria" del mismo archivo. **APLICADO**: sustituido por `semanticColors.warning`.

### MENOR — PROPUESTA (nuevo)
3. Tema Minimal: par `tertiary`/`onTertiary` (`MonoGray400`/`MonoWhite`) con margen de contraste muy ajustado (4.61:1 claro, 4.56:1 oscuro — cumple, pero por menos de 0.15). PROPUESTA: documentar el margen o dar colchón bajando `MonoGray400` ligeramente.

### Verificado exhaustivamente sin hallazgos nuevos
Touch targets ≥48dp, `contentDescription`, reduce-motion (todas las animaciones gatean correctamente `shouldReduceMotion()`/`effectsEnabled`), navegación por teclado — sin violaciones nuevas encontradas tras revisión sistemática.

---

## 4. UI y componentes Material3

### CRÍTICO — PROPUESTA (nuevo, refactor amplio)
1. `CreateTaskScreen.kt` y `EditTaskScreen.kt` duplican ~330 líneas casi idénticas (bloque de frecuencia/recurrencia, incluidos comentarios de negocio letra por letra) — ya han divergido parcialmente (1580 vs 1313 líneas). PROPUESTA: extraer un composable compartido (`RecurrenceFrequencySelector`), alto riesgo sin verificación visual de ambos formularios.

### IMPORTANTE — PROPUESTA (nuevo)
2. `HomeScreen` no usa `TaskHubTopBar` (mismo hallazgo de estética #4) y por tanto pierde el `Modifier.semantics { heading() }` que ese componente sí aplica desde v9 — TalkBack pierde el gesto de "saltar a encabezados" justo en la pantalla principal.

### MENOR — Fix aplicable (APLICADO en esta ronda)
3. `streakFireFontSize()` duplicada byte a byte en `RankingScreen.kt` y `StatsScreen.kt`. **APLICADO**: movida a `ui/components/AnimatedCounter.kt`, ambos call-sites actualizados.

### MENOR — PROPUESTA (nuevo)
4. `SettingsSheet` se implementa como `Dialog` + `Surface` a pantalla casi completa, no como `ModalBottomSheet` M3 — decisión ya documentada y deliberada, pero el nombre induce a confusión. PROPUESTA de renombrado, sin tocar comportamiento.

*(Card.clickable(role=Button), taskHubTextFieldColors infrautilizado: ver "Estado de v17", sin cambio.)*

---

## 5. UX

### CRÍTICO/IMPORTANTE — PROPUESTA (nuevo, decisión de flujo)
1. **Alta/unión a hogar abandonada a medias deja un "hogar fantasma" sin miembro, sin ningún aviso.** `HouseholdScreenModel.createHousehold`/`joinHousehold` guardan el hogar en `HouseholdStore` ANTES de que exista ningún documento de miembro; si el usuario pulsa "atrás" en el paso final de cualquiera de los dos wizards (`CreateProfileScreen`/`JoinHouseholdScreen` paso 2), el hogar queda huérfano y reaparece en Home como un espacio normal pero vacío, con "Salir del hogar" visible sin explicación de qué pasó. PROPUESTA: impedir el "atrás" en el paso final, o borrar automáticamente el hogar huérfano, o mostrar un banner explícito — decisión de diseño de flujo.

### IMPORTANTE — Fix aplicable (APLICADO en esta ronda)
2. `RankingScreen` no permitía ver el perfil de ningún miembro pese a ser un listado comparativo — inconsistente con `HouseholdMemberList`, que sí navega a `PublicProfileScreen`. **APLICADO**: `RankingRow` ahora usa `Card(onClick=...)` (el patrón correcto, no `.clickable(role=Button)`) navegando al perfil cuando `member.userId != null`.

### IMPORTANTE — PROPUESTA (nuevo)
3. `CreateProfileScreen` — el único paso obligatorio tras crear un hogar no avisa al pulsar "atrás" que se pierde el hogar recién creado (ligado al hallazgo #1). PROPUESTA: diálogo de confirmación específico de este paso.

### MENOR — Fix aplicable (APLICADO en esta ronda)
4. `TransferAmountDialog` (Agradecer/Donar, `HouseholdDialogs.kt`) se podía cerrar tocando fuera mientras la transferencia seguía en vuelo. **APLICADO**: `DialogProperties(dismissOnClickOutside = !isLoading)`.

### MENOR — PROPUESTA (nuevo)
5. `TaskDetailScreen` — diálogo de "quién lo hizo" sin estado de carga propio (el usuario puede pulsar dos veces si la red tarda).
6. `JoinHouseholdScreen` paso 2 — sin forma de volver al paso 1 para cambiar de código sin reiniciar todo el flujo.

---

## 6. Programador senior

### CRÍTICO — PROPUESTA (nuevo, requiere cambio de contrato)
1. `HomeScreenModel.loadAllTasks` (`ui/models/HomeScreenModel.kt:85-108`) mutaba `failedHouseholdIds` (un `MutableSet` no thread-safe) desde N corrutinas `async{}` en paralelo — y ese set, pensado para "que la UI pueda avisar" de hogares que fallaron al cargar, **nunca se lee**: no está en `HomeScreenUiState`, no se loguea. Hoy un hogar que falla al cargar se muestra en silencio como "0 tareas". PROPUESTA: no es un fix de una línea (falta decidir el contrato de `HomeScreenUiState`); envolver el resultado en un `Result`/sealed por hogar en vez de un `Set` compartido, y exponerlo a la UI.

### IMPORTANTE — PROPUESTA (nuevo, mecánico pero no puntual)
2. El patrón "best-effort, no romper cancelación" (`try { } catch (e: CancellationException) { throw e } catch (_: Exception) { }`) está repetido a mano ~10 veces en `ui/models/` (`TaskScreenModel.kt`, `HouseholdScreenModel.kt`, etc.) mientras `network/FirestoreClient.kt:512-520` ya tiene el helper genérico `orDefault` para el mismo problema, pero es `internal` a `network/` y nunca se promovió a un paquete compartido. PROPUESTA: exponer un `bestEffort {}` equivalente en `platform/` y sustituir los ~10 sitios.

### Verificado exhaustivamente SIN HALLAZGOS
3. **Ninguna `CancellationException` tragada en todo el repo.** Se revisaron los ~150 `catch (e: Exception)`/`catch (_: Throwable)` de `commonMain`+`androidMain`+`iosMain`+`jvmMain`+`wasmJsMain`+`functions/src`: en cada función `suspend` la cláusula de cancelación precede correctamente a la genérica. Los únicos `catch` sin esa cláusula están en funciones NO-`suspend` sin punto de suspensión posible — cosmético, no bug.
4. Inmutabilidad: sin `var` evitables ni colecciones mutables expuestas como propiedad pública en `commonMain`.

### MENOR — sin cambio de veredicto (ver "Estado de v17")
5. Los 8 `!!` revisados uno a uno: todos objetivamente seguros (guard explícito en el mismo scope). `Clock.System.now()` sigue sin inyectar en 20 archivos — 3 candidatos concretos identificados (`HouseholdRepository.kt:494`, `TaskRepository.kt:357`, `NotificationRepository.kt:185`) que podrían replicar el patrón de default-param ya usado en `appreciationRemaining`. PROPUESTA de bajo esfuerzo para la siguiente ronda.

---

## 7. Jefe de arquitectura

### CRÍTICO — PROPUESTA (nuevo, landmine operativo — NO aplicar enforcement sin este trabajo)
1. **App Check se instala en el cliente Android pero nunca se adjunta a ninguna petición — activar "enforcement" en Firebase Console rompería el 100% de las peticiones, Android incluido.** `MainActivity.kt:156-158` instala `PlayIntegrityAppCheckProviderFactory`, pero Task Hub no usa los SDK oficiales de Firestore/Functions (todo va por Ktor/REST crudo, decisión arquitectónica documentada) — ningún `HttpClient` de Ktor adjunta la cabecera `X-Firebase-AppCheck` automáticamente, y en efecto no hay ningún `getToken()` en todo el repo. Ninguna de las 7 Cloud Functions `onCall` declara `enforceAppCheck: true`. **Advertencia explícita para el dueño**: NO activar la aplicación de App Check en Firebase Console hasta completar la mitad que falta (obtener el token y adjuntarlo como header en `FirestoreClient`/`CloudFunctionsClient`, más `enforceAppCheck` en servidor).

### IMPORTANTE — PROPUESTA (nuevo)
2. El toggle de analytics opt-out (`SettingsSheet.kt:713-718`) es el primer control de esa pantalla que invoca un SDK de plataforma (`setAnalyticsCollectionEnabled`) directamente desde un lambda de Composable, sin pasar por ningún ScreenModel — rompe el patrón que el resto de toggles de la pantalla sí respeta, y deja este flujo RGPD sin ningún test. PROPUESTA: mover la llamada a un método del ScreenModel de Ajustes.
3. `TaskRepository.getTaskHistory` carga la colección `taskHistory` completa sin `limit` (coincide con el hallazgo de rendimiento #1 de más abajo — misma causa raíz, dos síntomas).
4. `GoogleAuthManager.kt` creció un 25% desde v17 (525→657 líneas, el mayor crecimiento relativo de `ui/models/`+`network/` en esta ventana) y ya absorbe 4 responsabilidades independientes (auth, borrado en cascada de cuenta, OAuth Calendar, sync de hogares) — mismo patrón de god-object ya corregido una vez en `FirestoreRepository`. PROPUESTA: extraer `AccountDeletionManager`.

### MENOR — Fix aplicable (APLICADO en esta ronda)
5. `MainActivity.kt:170` construía un `SettingsStore(Settings())` ad-hoc inline (tercera instancia manual fuera de Koin, sin guardarla en propiedad) solo para leer `isAnalyticsOptOut()`. **APLICADO**: propiedad `by lazy` reutilizable, mismo patrón que `householdStore`.

### Veredicto por subsistema
`network/` limpio en capas pero con un landmine operativo (App Check) y un problema de escalabilidad real (`taskHistory` sin cota); `ui/models/` con `StateFlow` bien encapsulado pero `GoogleAuthManager` concentrando demasiado; `ui/screens/` respeta el límite Screen→ScreenModel→network sin excepciones (la única grieta de capas de esta ronda está en `ui/components/SettingsSheet.kt`); `functions/` sin cambios de lógica en esta ronda, coherente con que ninguna `onCall` declara `enforceAppCheck`.

---

## 8. QA y bugs

### CRÍTICO — Fix aplicable (APLICADO en esta ronda)
1. **`undoTaskCompletion.ts` era la única de las 7 Cloud Functions que mueven `totalPoints` sin pasar por `clampTotalPoints()`** (`FieldValue.increment(-historyRecord.points)` ciego) — podía dejar el saldo negativo si el miembro ya había gastado los puntos que esa compleción le dio, antes de deshacerla. **APLICADO**: mismo clamp `[0, 100000]` que las otras 6 funciones, verificado contra el emulador de Firestore (`undoTaskCompletion.integration.test.ts`, 40/40 tests de integración verdes).

### IMPORTANTE — PROPUESTA (nuevo, requiere refactor no trivial)
2. `MemberRepository.updateMemberStreak` sin concurrencia optimista (lost-update de racha entre dos dispositivos) — mismo patrón que ya se arregló para `toggleSubtask`/`addMemberPoints`, pero aplicarlo aquí requiere mover la lógica de cálculo de racha (hoy en el call-site) dentro de un bucle de reintento con lectura fresca, no es un cambio puntual. Se aplicó sí el fix mecánico más simple y seguro: invalidación de caché en `try/finally` (antes se saltaba si el PATCH fallaba).
3. Confirmado que `completeRecurringTask`/`completeAssignment` SÍ tienen un guard efectivo contra duplicar puntos en un reintento (distinto del `idempotencyKey`, ver hallazgo #1 de funcionalidad) — "mismo día de calendario" + status transicionado — con un hueco residual: un reintento que llega en un día de calendario DISTINTO al de la compleción ambigua original no sería detectado por ningún guard. Ligado a la PROPUESTA del `idempotencyKey`.

### MENOR — Fix aplicable (APLICADO en esta ronda)
4. `CompleteAssignmentRequest.expectedUpdateTime` (`network/models/FunctionDtos.kt`) era un campo muerto — nunca poblado por el cliente NI leído por `completeAssignment.ts` (confirmado: el servidor solo destructura `householdId, taskId, assignmentId, idempotencyKey`). **APLICADO**: campo eliminado del DTO.

### MENOR — sin cambio (documentado)
5. Mensaje de conflicto ("se modificó en OTRO dispositivo") engañoso cuando el conflicto lo dispara el propio reintento del mismo usuario — no es bug de integridad, solo de claridad del mensaje.

### Veredicto de integridad de puntos
La cadena está bien protegida en casi todas las rutas tras esta ronda (clamp `[0,100000]` ahora en las 7 funciones que tocan `totalPoints`, concurrencia optimista en `addMemberPoints`/`updateTask`/`updateSubtasks`/`redeemReward`/`donatePoints`/`reassignTaskCompletion`); el riesgo restante de mayor prioridad es el `idempotencyKey` desconectado (funcionalidad #1) y la racha sin concurrencia optimista (#2).

---

## 9. Seguridad / AppSec (OWASP MASVS)

### MENOR/IMPORTANTE — Fix aplicable (APLICADO en esta ronda)
1. **`currentStreak`/`bestStreak`/`lastStreakDate` auto-editables sin ningún tope vía REST directo**, a diferencia de `totalPoints`/`appreciationGiven` (D10). Un cliente modificado podía fijar la racha a cualquier valor manipulando ranking y desbloqueo de logros. **APLICADO**: `firestore.rules` v13 — mismo tipo de cota superior (`currentStreak`/`bestStreak` ≤ 3650, ~10 años).

### Confirmado sin hallazgos nuevos explotables
Secretos (sin `.env`/credenciales/tokens hardcodeados fuera de `node_modules`), App Check (ya cubierto en arquitectura #1), módulos de Cloud Functions sin superficie huérfana (`functions/src/index.ts` exporta exactamente lo esperado), `withIdempotency` con clave opcional evaluado y descartado como vulnerabilidad real (protege igual con o sin key, solo afecta deduplicación de reintentos), `AndroidManifest`/backup rules (`allowBackup=true` mitigado explícitamente con exclusión de `sharedpref` en cloud-backup y device-transfer), SecureStore en las 4 plataformas sin hallazgos nuevos, alineación cliente↔reglas para el resto de colecciones sin rutas de escritura directa adicionales.

---

## 10. Privacidad / RGPD / menores

### IMPORTANTE — PROPUESTA (legal/producto, no técnica)
1. Analytics es "opt-out" (recolección activa desde el primer arranque) mientras Ads es "opt-in" real vía UMP — asimetría de estándar de consentimiento para EEE. PROPUESTA: evaluar base legal o gate equivalente.
2. El toggle "Desactivar análisis de uso" no cubre Crashlytics, que se auto-inicializa sin conmutador propio — el propio código ya reconoce el hueco en comentario. PROPUESTA: añadir `setCrashlyticsCollectionEnabled` al mismo toggle, o aclarar el texto — decisión de alcance del control de privacidad, se deja para decisión explícita en vez de aplicar sin confirmar el criterio deseado.
3. `privacy.html` no menciona transferencias internacionales de datos (Art. 13.1.f RGPD) pese a que toda la infraestructura corre en servidores de Google. PROPUESTA de redacción legal.

### MENOR — PROPUESTA (copy)
4. `privacy.html` §6 describe la purga como dependiente de abrir pantallas — desactualizado, ya existe purga server-side garantizada (~24h). PROPUESTA de actualización de copy (en la dirección de MÁS protección de la documentada, no menos).
5. `privacy.html` §8 no cubre la base legal para los datos de perfiles infantiles (`role="child"`) que sí persisten `displayName`/avatar. PROPUESTA de copy legal.

### Confirmado sin hallazgos nuevos
Sin ventana de carga de anuncio antes de resolver consentimiento UMP; reintento de consentimiento ante cambio de región cubierto por el propio SDK; TFCD global e incondicional sin bypass por rol (el bypass que documentaba una auditoría de 2026-09-06 ya no existe en el código); único evento de Analytics revisado en detalle sin PII.

---

## 11. Rendimiento

### CRÍTICO — PROPUESTA (nuevo, requiere rediseño de datos)
1. **`checkAndAwardAchievements` relee la colección `taskHistory` COMPLETA (sin `limit`) en CADA `completeTask()`** (`TaskScreenModel.kt:1211` → `TaskRepository.getTaskHistory`), no solo al abrir `StatsScreen` — es el hot path de escritura más frecuente de toda la app. Con retención de 90 días, un hogar activo puede acumular cientos de documentos redescargados enteros solo para contar cuántas tareas completó un miembro. PROPUESTA: mantener un contador `completedTasksCount` por miembro (incrementado atómicamente junto a `totalPoints`), o paginar con `limit`+orden descendente parando en el umbral más alto pendiente.

### MENOR — Fix aplicable (APLICADO en esta ronda)
2. `ProfileScreen.kt:98,110` recalculaba `households.find`/`households.filter` en cada recomposición dentro del contenido de `LazyColumn` — mismo antipatrón que `HomeScreen` (arreglado en v17) sin replicar aquí. **APLICADO**: `remember(households)` hoisted al scope `@Composable` padre.

### Verificado sin regresión
Los 4 fixes de rendimiento de v17 (Semaphore N+1, throttle de reconcile, poll paralelo, remember de HomeScreen) siguen intactos.

---

## 12. Red / offline / sincronización

### IMPORTANTE — Fix aplicable (APLICADO en esta ronda)
1. `MemberRepository.deleteMember` invalidaba caché solo tras éxito, no en `try/finally` — un fallo ambiguo podía dejar servido indefinidamente (caché en disco) el nombre/avatar reales que esta anonimización RGPD existe para ocultar. **APLICADO**.
2. `HouseholdRepository.updateHouseholdOwner` mismo patrón — riesgo sobre `ownerId`, que gatea `isOwner(hid)` en las reglas. **APLICADO**.
3. `GoogleCalendarRepository.findCalendarIdByName`/`validateToken` no usaban el wrapper de reintento (`retryTransientReadFailure`) que sí usa cualquier lectura de Firestore — un timeout transitorio de la API de Calendar abortaba de inmediato toda la sincronización. **APLICADO**.

### MENOR — Fix aplicable (APLICADO en esta ronda)
4. Mismo patrón de invalidación fuera de `try/finally` en `MemberRepository.updateMemberRole`/`updateMemberStreak`, `RewardsRepository.createReward`/`deleteReward`, `NotificationRepository.markNotificationRead` — impacto menor (colecciones que se refrescan con alta frecuencia), pero mismo riesgo sistemático. **APLICADO** en los 6 sitios.

### MENOR — PROPUESTA (nuevo, bajo impacto)
5. `MemberRepository.updateMemberRole` sin concurrencia optimista — ventana muy estrecha (acción de administración infrecuente, PATCH parcial sin riesgo de pisar otros campos).

*(updateTask ya resuelto fuera de v17, createTask+assignTask no atómico y demás: ver "Estado de v17", sin cambio.)*

---

## 13. Cobertura de pruebas (solo mapa, sin cambios de código)

**Cambio grande desde v17:** los 3 huecos #1-3 del TOP-10 anterior (Cloud Functions transaccionales sin test de integración) se cerraron el 2026-09-25 — ahora existen 7 archivos `*.integration.test.ts` (`completeAssignment`, `completeRecurringTask`, `undoTaskCompletion`, `reassignTaskCompletion`, `donatePoints`, `redeemReward`, `idempotency`), corriendo contra el emulador real de Firestore (`npm run test:integration`, verificado en esta ronda: 8/8 suites, 40/40 tests verdes).

**TOP-10 actualizado (riesgo dinero/puntos primero):**
1. `reconcileMissingTaskPoints.ts` — sigue sin ningún test (v17 #4, sin cerrar).
2. Cierre de asignaciones "hermanas" sin acotar por ciclo (`completeRecurringTask.ts`/`completeAssignment.ts`) — hay integration test del happy path pero no de este edge case.
3. `MemberRepository.updateMemberStreak` sin test de carrera (hallazgo QA #2 de esta ronda).
4. `idempotencyKey` nunca generado/enviado desde el cliente — sin test de ese contrato cliente↔servidor (hallazgo funcionalidad #1).
5. `TaskRepository.kt`/`FirestoreRepository.kt` sin test directo (v17 #5, sigue igual).
6. `HomeScreenModel.loadAllTasks` — `MutableSet` compartido mutado en paralelo sin sincronización (hallazgo programador senior #1 de esta ronda).
7. `HouseholdScreenModel`/`NotificationScreenModel`/`ProfileScreenModel`/`TaskCommentsScreenModel` sin test (v17 #7, sigue igual).
8. `RewardsRepository.kt` sin test (v17 #8, prioridad alta por tocar puntos).
9. `CalendarSyncManager.kt`/`GoogleAuthManager.kt` sin test (v17 #9, sigue igual).
10. Ausencia total de tests de UI/Compose (v17 #10, confirmado que los tests "de pantalla" existentes son de lógica pura, no renderizado).

Recomendación: priorizar 1-4 con test de integración contra emulador (mismo arnés ya construido), dado que son exactamente la superficie de puntos/racha sin red de seguridad automatizada.

---

## Archivos modificados en esta ronda

**Cloud Functions (`functions/src/`):**
- `undoTaskCompletion.ts`: clamp de puntos (`clampTotalPoints`), import de `FieldValue` eliminado (ya no se usa).

**Reglas de seguridad:**
- `firestore.rules` (v12 → v13): cota superior en `currentStreak`/`bestStreak`.

**Composeapp (`composeApp/src/commonMain/`):**
- `ui/screens/WelcomeScreen.kt`: fix de contraste (botón "Unirme a un hogar").
- `ui/screens/TaskDetailScreen.kt`: fix de contraste (texto de estado "Pendiente").
- `ui/screens/RankingScreen.kt`: `Card(onClick=...)` navega a `PublicProfileScreen`; `streakFireFontSize` movida a componente compartido.
- `ui/screens/StatsScreen.kt`: `streakFireFontSize` movida a componente compartido.
- `ui/screens/ProfileScreen.kt`: `remember(households)` hoisted.
- `ui/components/AnimatedCounter.kt`: nueva función compartida `streakFireFontSize`.
- `ui/components/HouseholdDialogs.kt`: `TransferAmountDialog` no se cierra tocando fuera mientras está en vuelo.
- `ui/models/HouseholdScreenModel.kt`: `sendMessage` ya no pisa un mensaje nuevo con el texto de un intento fallido.
- `network/MemberRepository.kt`: `try/finally` en `deleteMember`/`updateMemberRole`/`updateMemberStreak`.
- `network/HouseholdRepository.kt`: `try/finally` en `updateHouseholdOwner`.
- `network/RewardsRepository.kt`: `try/finally` en `createReward`/`deleteReward`.
- `network/NotificationRepository.kt`: `try/finally` en `markNotificationRead`.
- `network/GoogleCalendarRepository.kt`: `retryTransientReadFailure` en `findCalendarIdByName`/`validateToken`.
- `network/models/FunctionDtos.kt`: campo muerto `CompleteAssignmentRequest.expectedUpdateTime` eliminado.

**Android (`composeApp/src/androidMain/`):**
- `MainActivity.kt`: `SettingsStore` como propiedad `by lazy` en vez de instancia ad-hoc inline.

## Verificación

```
./gradlew :composeApp:compileDebugKotlinAndroid --console=plain   → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --console=plain                     → BUILD SUCCESSFUL, 289/289 tests verdes (conteo real de XML)
cd functions && npm run build                                     → sin errores
cd functions && npm test                                          → 54/54 tests unitarios verdes
cd functions && npm run test:integration (emulador Firestore)      → 8/8 suites, 40/40 tests verdes
```

No se hizo bump de versión (corresponde al orquestador tras revisar este informe, según el encargo).
