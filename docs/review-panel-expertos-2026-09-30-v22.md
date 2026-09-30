# Panel de expertos v22 — Verificación de 7 propuestas aplicadas del panel v21 + estado general (2026-09-30)

HEAD de partida: `976299b` (v0.7.54), commit que aplicó 7 propuestas del panel v21
(`docs/review-panel-expertos-2026-09-29-v21.md`, encargo
`docs/aplicar-7-propuestas-v21-2026-09-29.md`). Base de comparación: `cfcfa13`
(HEAD del panel v21). Diff real revisado con `git diff 96ab3ae..976299b`
(96ab3ae = bump de versión entre ambos): 12 archivos, 212 inserciones / 61
borrados.

Verificación directa contra el código real (diffs línea a línea, greps,
lectura completa de los ficheros tocados y de sus dependencias) en vez de
confiar en el self-report del encargo.

## Verificación de build/tests

```
./gradlew :composeApp:compileDebugKotlinAndroid --rerun-tasks --console=plain → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain                    → BUILD SUCCESSFUL, 303/303 tests, 0 failures, 0 errors
```
(300 tests de v21 + 3 nuevos de `TaskListRulesTest.kt`, contados directamente
de `composeApp/build/test-results/jvmTest/*.xml`.)

**Dos hallazgos APLICA YA de bajo riesgo se corrigieron en esta misma
ronda** (mismo patrón que v21, que corrigió su hallazgo crítico dentro del
propio panel):

1. `liveRegion` de auto-relleno no se desactivaba nunca tras el primer
   anuncio (ver §Accesibilidad #1) → corregido en
   `CreateProfileScreen.kt`/`JoinHouseholdScreen.kt`.
2. Clave i18n `create_household_choose_emoji` (ES+EN) quedó huérfana tras la
   propuesta 2 → eliminada de `AppStrings.kt`.

Re-verificado tras el fix: `compileDebugKotlinAndroid` BUILD SUCCESSFUL,
`jvmTest --rerun-tasks` → **303/303 tests, 0 failures, 0 errors** (sin
cambio de conteo, ningún test cubría el bug de liveRegion — ver
§Cobertura).

**No se corrigió** el hallazgo CRÍTICO de `CalendarSyncManager` (§Funcionalidad/QA
#4) porque requiere una decisión de diseño explícita sobre qué tipos de
fallo deben contar para la degradación — se deja documentado como PROPUESTA
urgente, no como mecánico.

---

## 1. Funcionalidad / QA

### 1. LiveRegion en auto-relleno — **[CORREGIDO EN ESTA RONDA]**

`CreateProfileScreen.kt`/`JoinHouseholdScreen.kt`: el `semantics { liveRegion
= LiveRegionMode.Polite }` se activaba correctamente solo cuando
`nameAutoFilled == true` (nunca en el caso general), **pero `nameAutoFilled`
nunca volvía a `false`** — ni en el `onValueChange` del campo, ni en ningún
otro punto. Efecto real: una vez que Google auto-rellena el nombre, el campo
queda marcado como región activa (`liveRegion`) **para el resto de su vida**,
incluida cualquier edición manual posterior del usuario. Contrastado contra
el único otro uso de `liveRegion` en el mismo archivo
(`CreateProfileScreen.kt:255`, mensaje de error): ese caso es correcto porque
el `Text` con `liveRegion` solo existe mientras el composable padre está
condicionado a `MemberUiState.Error` — transitorio por construcción. El caso
del nombre no lo era: el campo persiste siempre, así que el flag debía
resetearse explícitamente y no lo hacía.

**Fix aplicado**: `onValueChange = { displayName = it; nameAutoFilled =
false }` en ambos archivos — la primera interacción real del usuario con el
campo (cualquier tecleo) apaga la liveRegion, que ya no hace falta porque el
usuario está editando activamente el campo con foco. Verificado
`compileDebugKotlinAndroid`/`jvmTest` en verde tras el cambio.

### 2. Botón emoji muestra emoji efectivo — verificado correcto

`CreateHouseholdScreen.kt:81` define `effectiveEmoji = customEmoji ?:
selectedType.emoji`; el botón (línea 198) ahora usa siempre
`s("create_household_emoji_selected").replace("%s", effectiveEmoji)`, sin la
rama condicional `if (customEmoji != null) ... else "Elegir emoji"` que
existía antes. Efecto correcto: el botón muestra desde el primer frame el
emoji del `SpaceType` elegido (p. ej. 🏠 para `HOME`) hasta que el usuario
elige uno manual del grid, momento en el que `customEmoji` deja de ser
`null` y el texto pasa a mostrar ese. Sin condición de carrera: `selectedType`
y `customEmoji` son `remember { mutableStateOf(...) }` locales, actualizados
síncronamente en `onClick`.

### 3. `emoji == ""` fallback — verificado correcto y completo

`HouseholdTaskSection.kt:91` y `ProfileScreen.kt:204`: el `?:` simple se
sustituyó por `if (!household.emoji.isNullOrBlank()) household.emoji else
household.spaceType.emoji`, que cubre `null` y `""` (y además cualquier
cadena solo-espacios, más estricto que lo pedido). Grep exhaustivo de
`\.emoji ?: \|emoji ?:` y de `spaceType.emoji` en todo `commonMain`/
`androidMain`: **no queda ningún otro sitio** con el `?:` simple para
`household.emoji` — los otros usos de `.emoji` en el código
(`CreateHouseholdScreen.kt:81` `customEmoji ?: selectedType.emoji`,
`StatsScreen.kt`/`CreateTaskScreen.kt`/`AchievementToast.kt`) son de otros
modelos (`Achievement.emoji`, `TaskCategory.emoji`, emoji temporal de
creación) sin la misma ambigüedad null/vacío de `SavedHousehold.emoji`.

### 4. `hasGoogleLinked` se degrada tras 3 fallos — **[CRÍTICO, no corregido, requiere decisión de diseño]**

Implementación real (`CalendarSyncManager.kt:112-144`): `consecutiveTokenFailures`
se incrementa en cada fallo de `ensureCalendarAccessToken()` (wrapper privado)
y, al llegar a `MAX_CONSECUTIVE_TOKEN_FAILURES = 3`, llama a
`settingsStore.unlinkGoogleCalendar()` — no solo apaga `hasGoogleLinked()`
(que en efecto pasa a `false`, porque internamente comprueba
`getGoogleAccessToken() != null` y `unlinkGoogleCalendar()` borra ese token),
sino que además **borra los `calendarId` cacheados y pone
`isCalendarSyncEnabled = false`** (`SettingsStore.kt:185-190`) — una
desvinculación completa, no solo un flag informativo.

El problema real está en **qué cuenta como "fallo"**. Rastreado
`ensureCalendarAccessToken()` hasta la implementación Android
(`GoogleCalendarAuthHelper.kt:70-90`): devuelve `null` tanto si el
consentimiento fue denegado/revocado **como si hay un `IOException`** (sin
red, timeout) — el propio KDoc lo documenta explícitamente: *"or the token
could not be obtained (denied consent, offline, revoked access, etc)"*. El
wrapper nuevo de `CalendarSyncManager` no distingue estos casos: cualquier
`null`, sea por qué sea, cuenta igual para el contador.

Los 5 call-sites que pasan por el wrapper no están todos protegidos por el
mismo throttle: `reconcile()` tiene `RECONCILE_THROTTLE_MS = 15 min`, pero
`onTaskAssigned`, `onDueDateChanged`, `syncNow` y
`deleteEventForAssignment` se disparan **inmediatamente** en cada acción del
usuario (asignar tarea, cambiar fecha, pulsar "sincronizar ahora", borrar
asignación). **Escenario real y nada exótico**: un usuario sin conexión (modo
avión, túnel, wifi caído) que cambia la fecha de vencimiento de 3 tareas
distintas en unos segundos, o que asigna una tarea y luego cambia su fecha
dos veces mientras el móvil no tiene datos, agota el contador y queda
**desvinculado de Google Calendar sin haber revocado nada y sin ningún
aviso** — tendría que volver a Ajustes y re-vincular manualmente, perdiendo
además los `calendarId` cacheados (recreará los calendarios).

Esto es un caso peor que el que la propuesta v21 quería resolver: antes el
usuario podía creer erróneamente que seguía sincronizado cuando llevaba
tiempo sin estarlo (falso positivo "vinculado"); ahora un usuario
perfectamente vinculado puede quedar desvinculado por una simple
desconexión transitoria (falso positivo "roto"), con una consecuencia más
destructiva (pierde el ajuste de sync y el caché, no solo un flag).

**PROPUESTA de remediación** (requiere decisión de producto, no se aplica en
esta ronda):
- Opción A (mínimo cambio): contar el fallo solo en `reconcile()` (que ya
  tiene throttle de 15 min — 3 fallos ahí implican ~45 min de fallo
  sostenido, señal mucho más fiable de revocación real) y dejar que los 4
  call-sites interactivos usen `authManager.ensureCalendarAccessToken()`
  directamente, sin contarlos.
- Opción B: distinguir en `GoogleCalendarAuthHelper.getAccessToken` entre
  "sin red" (no cuenta) y "consentimiento denegado/revocado" (cuenta),
  devolviendo un resultado tipado en vez de `String?`.
- Opción C: subir el umbral y añadir una notificación al usuario antes de
  desvincular (p. ej. banner en Ajustes "no hemos podido sincronizar tu
  Calendar, revisa tu conexión o el acceso" en vez de desvincular en
  silencio).

### Regresiones en flujos tocados

- **SettingsSheet** (dropdown → `RadioOptionRow`): sin regresión. La
  selección se lee de `appSettings.currentTheme` (estado de `App.kt`, no
  estado local de la hoja) y `onThemeChanged` persiste inmediatamente vía
  `settingsStore.setTheme(...)` — cerrar/reabrir la hoja no pierde ni
  revierte la selección, verificado leyendo `App.kt:296-320`.
- **CalendarSyncManager**: el contador puede desincronizar el estado
  percibido por el usuario sin notificación — ver hallazgo #4 arriba,
  reclasificado CRÍTICO.

## 2. Programador senior

- **`CreateProfileScreen.kt`/`JoinHouseholdScreen.kt`** — bug de
  null-safety/estado descrito en §1.1, corregido en esta ronda.
- **`CalendarSyncManager.ensureCalendarAccessToken()` (línea 134)** — ¿hilo
  seguro? El campo `consecutiveTokenFailures` es un `var` simple sin
  `Mutex`/`@Volatile`, pero **no hace falta**: es un `private suspend fun`
  cuyo único punto de suspensión es la llamada interna a
  `authManager.ensureCalendarAccessToken()`; el resto de la función
  (comparar, incrementar, comparar contra el umbral) se ejecuta síncrono sin
  otro punto de suspensión intermedio. Todos los call-sites cuelgan de
  `screenModelScope`/`Dispatchers.Main.immediate` (mismo patrón ya validado
  en v21 para `pendingIdempotencyKeys`) — sin paralelismo real de hilos, solo
  intercalado cooperativo en el punto de `await`, así que no hay carrera de
  datos. Correcto, sin acción.
- **Reset en éxito**: correcto — `if (token != null) consecutiveTokenFailures
  = 0` antes de cualquier otra comprobación, en la misma función.
- **`MainActivity.kt` — acceso a `settingsStore` desde `onCreate` (línea
  178)**: **no usa Koin** en absoluto para este campo —
  `private val settingsStore by lazy { SettingsStore(Settings()) }`
  (línea 88), un `lazy` normal de Kotlin sobre un constructor directo. La
  preocupación de "¿Koin ya inicializado?" del encargo de auditoría no
  aplica: no hay injección aquí, es una instancia standalone. Sin riesgo de
  orden de inicialización.
- **`SettingsSheet.kt` — nuevo parámetro `leadingContent` en
  `RadioOptionRow`**: verificados los otros 5 call-sites existentes
  (`Idioma` ×2, `Tema del widget` ×3) — ninguno pasa `leadingContent`, todos
  usan el valor por defecto `null`, sin cambio de comportamiento. Compatible.
- **[NUEVO, aplicado en esta ronda]** Clave i18n
  `create_household_choose_emoji` (ES: "Elegir emoji", EN: "Choose emoji")
  quedó **sin ningún call-site** tras la propuesta 2 (el botón ya no tiene
  rama que la use) — código muerto. Eliminada de `AppStrings.kt` (ambos
  idiomas).
- **[MENOR, no aplicado, IMPORTANTE]** `MainActivity.kt:178-183`: el forzado
  de iconos claros para Medianoche solo se ejecuta **una vez, en
  `onCreate`**. Si el usuario cambia de tema en caliente (Ajustes →
  `SettingsSheet`, sin recrear la `Activity`) hacia o desde Medianoche
  durante la misma sesión, `WindowInsetsControllerCompat` no se vuelve a
  invocar — los iconos de la barra de estado quedan fijados según el tema
  que estaba activo al arrancar la app, no el tema actual. Ejemplo: usuario
  abre la app con tema Default (iconos según el sistema), cambia a
  Medianoche en Ajustes sin cerrar la app → los iconos NO se fuerzan claros,
  reproduciendo exactamente el problema de bajo contraste que esta propuesta
  pretendía resolver, solo que ahora en runtime en vez de en el arranque.
  PROPUESTA: mover la lógica a un punto reactivo (p. ej. un
  `DisposableEffect`/`SideEffect` en `App.kt` que reaccione a `themeType` y
  actualice el `WindowInsetsControllerCompat` del `LocalView.current`,
  compartido con la `Activity` vía `AndroidContextHolder`).

## 3. Accesibilidad

- **(a) LiveRegion en auto-relleno** — bug real encontrado y corregido, ver
  §1.1. Antes del fix: un usuario de TalkBack que edite el nombre después
  del auto-relleno (p. ej. corrige un apellido) habría seguido escuchando el
  campo marcado como "región activa" en cada cambio, en lugar del
  comportamiento normal de edición de texto con foco — ruido/confusión
  potencial, no solo la ausencia de anuncio que v21 quería arreglar.
- **(b) RadioOptionRow + `selectableGroup()` en tema**: correcto.
  `SettingsSheet.kt:822-831`: cada fila usa `Row.selectable(selected =
  selected, role = Role.RadioButton, onClick = onClick)` (no `clickable`
  genérico) dentro de un `Column.selectableGroup()` — exactamente el patrón
  M3 recomendado; TalkBack anunciará "botón de radio, seleccionado/no
  seleccionado, <nombre del tema>" y permitirá navegación secuencial dentro
  del grupo. El swatch de color (`leadingContent`) es puramente decorativo,
  sin necesidad de `contentDescription` propio porque el nombre del tema ya
  se anuncia vía el `Text` hermano.
- **(c) Botón emoji sin semántica expandido/colapsado**: confirmado sin
  cambios — `CreateHouseholdScreen.kt:191-198`, el `OutlinedButton` que
  alterna `showEmojiGrid` sigue sin `stateDescription`/semántica de
  expandido-colapsado, mismo patrón heredado de `EditProfileScreen.kt`. Sin
  agravar, sin cambio.

## 4. UX

- **(a) Auto-relleno detectable por TalkBack pero sin indicio visual para
  usuario vidente**: sigue siendo cierto tras esta ronda — el campo no
  cambia de estilo, borde ni icono cuando se auto-rellena, solo cambia (ahora
  correctamente) la semántica de accesibilidad. Sigue siendo una oportunidad
  de bajo coste sin aplicar (p. ej. un `supportingText` breve la primera vez,
  o un icono ⓘ). Aceptable como está, no bloqueante.
- **(b) Botón emoji con emoji efectivo**: resuelve la fricción descrita en
  v21 — el usuario ya no ve "Elegir emoji" cuando en realidad ya hay uno
  (el del `SpaceType`) aplicado por defecto. Mejora real, confirmada.
- **(c) Degradación de `hasGoogleLinked` tras 3 fallos**: **mala experiencia
  tal como está implementada**, no buena — ver hallazgo CRÍTICO de
  §Funcionalidad/QA #4. El usuario puede perder su vinculación de Calendar
  sin ninguna acción propia y sin ningún aviso, por una simple
  desconexión momentánea. Peor que el problema original (creer que estás
  sincronizado cuando no lo estás) porque además es una acción destructiva
  silenciosa (borra caché y desactiva el ajuste). Se necesita, como mínimo,
  notificar al usuario antes o en el momento de desvincular — ver
  PROPUESTA de remediación arriba.

## 5. Rendimiento

Sin hallazgos nuevos en los 5 focos de la ventana:

- **SettingsSheet con 6 `RadioOptionRow`**: 6 filas siempre compuestas (antes:
  1 `OutlinedTextField` + `ExposedDropdownMenu` que solo compone sus items al
  desplegarse) — coste de composición trivial (6 `Row` con `Text`+`RadioButton`+
  `Box` de swatch, sin `LazyColumn`, sin imágenes), aceptable para una hoja de
  ajustes que se abre con poca frecuencia y sin scroll interno pesado.
- **CalendarSyncManager.ensureCalendarAccessToken()**: wrapper de una sola
  llamada extra (comparación de `Int`, sin I/O), coste despreciable frente a
  la llamada de red que envuelve.
- **`todayStartEpoch()`**: función pura, trivialmente inlineable por el
  compilador (marcada `internal`, sin captura de estado de Compose); el
  cambio en `TaskListScreen.kt` es un *move* semántico sin coste añadido.
- **`WindowInsetsControllerCompat` en `MainActivity.onCreate`**: una sola
  invocación en el arranque, coste despreciable, sin bucles ni recomposición
  repetida (justo por eso, además, no se reevalúa en cambios de tema en
  caliente — ver hallazgo de Programador senior).

## 6. Cobertura de pruebas

- **`todayStartEpoch()`**: 3 tests en `TaskListRulesTest.kt`, cubren
  medianoche exacta (hora/minuto/segundo = 0), mismo día calendario que
  "ahora", y no-futuro. **Robustos a zona horaria/DST**: usan
  `TimeZone.currentSystemDefault()` y `Clock.System.now()` en el momento de
  la ejecución, sin fechas hardcodeadas ni fixtures de instante fijo — no
  hay escenario de cambio de horario de verano que los rompa porque no
  comparan contra un instante fijo, solo contra el reloj real en el momento
  del test.
- **RadioOptionRow / SettingsSheet**: sin test — coherente con la nota ya
  documentada en v21 ("cero Compose UI testing en el proyecto"); la
  persistencia de selección se verificó por lectura de código (§Funcionalidad/QA),
  no por test automatizado.
- **liveRegion**: sin test, esperable (UI de accesibilidad Compose, mismo
  motivo). El bug de esta ronda (§1.1) no habría sido detectado por ningún
  test existente ni futuro dentro del patrón actual del proyecto — solo por
  lectura de código, como en esta auditoría.
- **CalendarSyncManager / `consecutiveTokenFailures`**: **sin test alguno**,
  ni del contador ni de la distinción (inexistente) entre fallo por red y
  fallo por consentimiento. Dado que el hallazgo CRÍTICO de esta ronda vive
  exactamente aquí, este es ahora el hueco de cobertura de mayor prioridad
  del proyecto — más urgente que los de la lista TOP-10 heredada de v21.
- **`MemberScreenModel`**: sigue en 3 tests, sin cambios (no tocado en este
  commit).

### TOP-10 actualizado (riesgo dinero/puntos y sincronización primero)

1. **[NUEVO, máxima prioridad]** `CalendarSyncManager.consecutiveTokenFailures`
   sin test que documente que cuenta fallos de red igual que fallos de
   consentimiento — directamente ligado al hallazgo CRÍTICO de esta ronda.
   Un test de contrato (`GoogleAuthManager` fake que devuelve `null` 3 veces
   por "sin red" simulado → verificar que `unlinkGoogleCalendar()` se llama)
   documentaría el comportamiento actual y protegería cualquier fix futuro.
2. Contrato `idempotencyKey` sin test para `MemberScreenModel.donatePoints`/
   `redeemReward` (heredado v21, sin cambios esta ronda).
3. `HouseholdScreenModel` sin ningún archivo de test (heredado v21, sin
   cambios).
4. `GoogleAuthManager`/`CalendarSyncManager` sin test propio de sus
   call-sites (heredado v21, agravado en el sentido de que ahora protegen
   menos: el bug crítico de esta ronda vive justo en el código sin cubrir).
5. `emoji == ""` no cae al fallback — **[CERRADO en el código, sigue sin
   test]**: el comportamiento ya es correcto (§Funcionalidad/QA #3) pero
   sigue sin un test que fije el contrato para el futuro.
6. `MemberRepository.updateMemberStreak` sin test de carrera (heredado v18/v20).
7. `TaskRepository.kt`/`FirestoreRepository.kt` sin test directo (heredado v17/v20).
8. Cierre de asignaciones "hermanas" sin acotar por ciclo (heredado v18).
9. `RewardsRepository.kt` sin test (heredado v17).
10. `todayStartEpoch` — **[CERRADO]** ya tiene 3 tests (`TaskListRulesTest.kt`),
    retirado del TOP-10 activo, se mantiene aquí solo como referencia de cierre.

---

## Estado de propuestas v21 (re-verificación exhaustiva)

- **[CERRADO por commit 976299b]** LiveRegion en auto-relleno de nombre
  Google — aplicado, pero con un bug nuevo (liveRegion permanente) detectado
  y **corregido en esta ronda** (panel v22).
- **[CERRADO por commit 976299b]** Botón emoji muestra emoji efectivo por
  defecto — aplicado correctamente, sin hallazgos.
- **[CERRADO por commit 976299b]** `emoji == ""` fallback en
  `HouseholdTaskSection.kt`/`ProfileScreen.kt` — aplicado correctamente y sin
  otros sitios pendientes.
- **[CERRADO por commit 976299b, pero con regresión nueva más severa]**
  `hasGoogleLinked` se degrada tras 3 fallos consecutivos — el mecanismo
  existe y funciona tal como se describió, pero introduce un CRÍTICO nuevo
  (desvinculación por fallos de red transitorios, no solo por consentimiento
  revocado) — ver §Funcionalidad/QA #4. No se reabre la propuesta original,
  pero se abre una PROPUESTA nueva y más urgente de remediación.
- **[CERRADO por commit 976299b]** Dropdown de temas a `RadioOptionRow` —
  aplicado correctamente, sin regresión de persistencia de selección, sin
  romper otros call-sites del componente compartido.
- **[CERRADO por commit 976299b]** Medianoche: iconos claros en barra de
  estado — aplicado y funciona en el arranque (compatible con minSdk 26 sin
  matices de versión), pero **incompleto**: no se reaplica si el usuario
  cambia de tema en caliente sin reiniciar la app — ver hallazgo de
  Programador senior (nueva PROPUESTA, no reabre la original).
- **[CERRADO por commit 976299b]** `todayStartEpoch` extraída a función pura
  — aplicado correctamente, función pura sin dependencias Compose, 3 tests
  con casos de borde razonables y robustos a zona horaria/DST.

### Pendientes de v21 sin cambios (re-verificados, ninguno agravado salvo lo ya notado)

- **[SIGUE ABIERTO]** Extracción incompleta de `isOverdue` en
  `TaskListScreen.kt:413-427` — sigue dentro del `remember`/Composable, no
  extraída a `TaskListRules.kt` a diferencia de `todayStartEpoch`.
- **[SIGUE ABIERTO]** Asimetría idempotencyKey en
  `MemberScreenModel.donatePoints` (línea 439, reutiliza en errores
  deterministas) vs `redeemReward` (línea 336, no reutiliza en
  `InsufficientBalanceException`) — sin cambios.
- **[SIGUE ABIERTO]** `try/catch valueOf` duplicado en `App.kt` (líneas
  285-287 y 301-304, `TaskHubThemeType.valueOf`) — sin extraer a
  `themeTypeFromSettings(store)`, sin cambios.
- **[SIGUE ABIERTO]** `TaskScreenModel.completeTask` sigue en ~161-163
  líneas — archivo no tocado en este commit, confirmado por
  `git diff 96ab3ae..976299b -- .../TaskScreenModel.kt` sin salida.
- **[SIGUE ABIERTO]** Duplicación literal emoji+nombre en
  `HouseholdTaskSection.kt:91`/`ProfileScreen.kt:204` — **la duplicación
  creció textualmente** (el fallback ahora es más largo:
  `if (!household.emoji.isNullOrBlank()) household.emoji else
  household.spaceType.emoji` en vez de `household.emoji ?:
  household.spaceType.emoji`, repetido igual en ambos archivos) —
  refuerza el caso para la propiedad `SavedHousehold.displayLabel`
  propuesta en v21, ahora con más texto duplicado que antes.
- **[SIGUE ABIERTO]** Regla de capas para lógica pura tipo `TaskListRules.kt`
  sin documentar en `CLAUDE.md` — sin cambios; de hecho la nueva función
  `todayStartEpoch()` refuerza el precedente sin resolver la ambigüedad
  documental.
- **[SIGUE ABIERTO]** `SemanticColors.info` coincide con `OceanBlue800` —
  archivo `Theme.kt` no tocado en este commit, confirmado por diff vacío.
- **[SIGUE ABIERTO]** Medianoche con sistema en modo claro (efecto
  colateral de `enableEdgeToEdge()`) — la propuesta original ya fue resuelta
  para el caso de arranque (ver arriba, "CERRADO por commit"), pero el
  matiz de runtime (cambio de tema en caliente) sigue sin cubrir — ver
  Programador senior.
- **[SIGUE ABIERTO]** Inconsistencia de paradigma dropdown vs
  `RadioOptionRow` — **resuelta de facto**: tras esta ronda ya no queda
  ningún dropdown de tema en `SettingsSheet.kt`, los 3 selectores (Tema,
  Idioma, Tema del widget) usan ahora el mismo componente
  `RadioOptionRow`. Se marca como cerrada la inconsistencia, aunque no fue
  el objetivo explícito de la propuesta 5 (era solo "mover Tema a
  RadioOptionRow", pero el efecto colateral resuelve también esta deuda
  estética de v21).
- **[SIGUE ABIERTO]** Clave i18n `edit_profile_emoji_content_desc` reusada en
  `CreateHouseholdScreen.kt:219` — sin cambios, sigue acoplada a
  `EditProfileScreen`.
- **[SIGUE ABIERTO, heredado, no agravado]** Botón emoji sin semántica
  expandido/colapsado.
- **[SIGUE ABIERTO, menor]** Sin indicio visual de autorelleno para usuario
  vidente.
- **TOP-10 de v21**: ver sección de Cobertura arriba, TOP-10 actualizado con
  el hallazgo #1 nuevo (CalendarSyncManager sin test del contador) como
  máxima prioridad.

---

## Archivos modificados en esta ronda (v22)

- `composeApp/src/commonMain/kotlin/org/taskhub/ui/screens/CreateProfileScreen.kt`
  — `onValueChange` del campo de nombre apaga `nameAutoFilled` en la primera
  edición real del usuario.
- `composeApp/src/commonMain/kotlin/org/taskhub/ui/screens/JoinHouseholdScreen.kt`
  — mismo fix, paso 2 del flujo de unión.
- `composeApp/src/commonMain/kotlin/org/taskhub/ui/i18n/AppStrings.kt` —
  eliminada la clave huérfana `create_household_choose_emoji` (ES + EN).
- `docs/review-panel-expertos-2026-09-30-v22.md` (este informe).

No se tocó `functions/` en esta ronda (todos los cambios son Kotlin
`commonMain`), así que no hizo falta re-ejecutar `npm test`/
`npm run test:integration`.

## Verificación final

```
./gradlew :composeApp:compileDebugKotlinAndroid --rerun-tasks --console=plain → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain                    → BUILD SUCCESSFUL, 303/303 tests, 0 failures, 0 errors
```

No se hizo bump de versión ni push, según el encargo — el orquestador se
encarga de eso tras verificar este informe. El hallazgo CRÍTICO de
`CalendarSyncManager` (§Funcionalidad/QA #4) queda pendiente de decisión de
diseño antes de aplicarse; se recomienda priorizarlo antes del siguiente
release dado que puede desvincular Google Calendar a usuarios legítimos sin
ninguna acción suya.
