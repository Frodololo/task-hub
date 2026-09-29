# Panel v21 — progreso (oleada A de 3)

HEAD auditado: `f1ecbd5`. Base de comparación: `75243ed` (v20).

## Verificación independiente del orquestador (antes de lanzar agentes)

```
./gradlew :composeApp:compileDebugKotlinAndroid --rerun-tasks --console=plain → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain                    → BUILD SUCCESSFUL
  composeApp/build/test-results/jvmTest/*.xml: 297 tests, 0 failures, 0 errors
cd functions && npm test                                                       → 54/54 passed
cd functions && npm run test:integration                                       → 9 suites, 45/45 passed
```
GraphQL contra el proyecto Kanban (`PVT_kwHOCXo7m84BjLGt`): las 2 tarjetas que
`docs/pendientes-post-v20-2026-09-29.md` dice haber cerrado
(`PVTI_lAHOCXo7m84BjLGtzg8ujRE` "[CF] Idempotency key en completeTask",
`PVTI_lAHOCXo7m84BjLGtzg8ujY4` "[Arquitectura] Extraer TaskListRules.kt")
están confirmadas en Status=Completado. Sin discrepancias entre el self-report
de los 2 encargos y el estado real verificado.

## Oleada A (5 especialistas, completada)

### Funcionalidad end-to-end
Los 4 flujos del 4-en-1 (auto-fill nombre Google, emoji de hogar, idempotencyKey,
bestEffort en CalendarSyncManager) funcionan tal como se documentan. Sin
condiciones de carrera en el auto-fill (LaunchedEffect keyed por data class,
sin retrigger espurio). `joinHousehold` SÍ propaga emoji (no solo `createHousehold`).
Matiz: `TaskScreenModel.reset()` (limpia `pendingIdempotencyKeys`) no tiene
ningún call-site en pantallas de producción — inocuo porque `factory` en Koin
da una instancia nueva por pantalla, pero el informe sugiere un hook de
ciclo de vida que no existe.

### Programador senior
Refactor `TaskListRules.kt` limpio. **Hallazgo real de riesgo**:
`TaskScreenModel.completeAssignment` (línea ~887) NUNCA pasa `idempotencyKey`
al repo pese a que `FirestoreRepository.completeAssignment` ya lo acepta desde
`a7d083e` — mismo riesgo de duplicar puntos en reintento que motivó el fix de
`completeTask`, sin cubrir (fuera del alcance literal del encargo, pero es una
asimetría real en producción, usado desde `TaskDetailScreen.kt:257`). Sin test
de este camino. Asimetría menor `donatePoints` vs `redeemReward` al decidir
cuándo reutilizar la idempotencyKey en error determinista (inofensiva,
verificada contra el servidor).

### Jefe de arquitectura
`TaskScreenModel.completeTask` creció a 161-163 líneas (antes ~150) — sigue
siendo la función más larga del archivo, hallazgo v18 "extraer lógica" sigue
sin resolver y empeoró levemente. Duplicación literal del fallback
`emoji ?: spaceType.emoji` + interpolación de nombre, carácter por carácter,
en `HouseholdTaskSection.kt:91` y `ProfileScreen.kt:204` — candidato a
extensión `SavedHousehold.displayLabel`. `GoogleAuthManager` se mantiene en
las mismas ~4 responsabilidades (sin empeorar). Registro Koin correcto
(`single` + `koinInject`, sin riesgo de instancia duplicada).

### QA y bugs
Sin regresiones. Guard de doble-tap en `completeTask` es correcto (síncrono,
`screenModelScope` con `Dispatchers.Main.immediate`) aunque es global al
ScreenModel, no por `taskId` (más restrictivo de lo necesario, no es un bug).
Reutilización de idempotencyKey tras error real de `donatePoints` verificada
inofensiva contra `functions/src/idempotency.ts` (el servidor libera la
reserva en cualquier excepción). Hueco defensivo menor: `emoji == ""` (en vez
de `null`) no cae al fallback — hoy inalcanzable desde la UI (grid fija sin
opción de deseleccionar). `isTaskDueToday`/`isTaskCompletedToday` extraídas a
`TaskListRules.kt` pero el cálculo de "medianoche local de hoy"
(`todayStartEpoch`) se quedó dentro del `@Composable` — sigue intestable con
unit test pese al objetivo del refactor.

### Deuda abierta v17-v20 (re-verificación dedicada)
Los 7 puntos re-verificados contra el código actual, todos SIGUEN ABIERTOS sin
cierre: App Check decorativo (solo cliente, sin enforcement en
functions/reglas), `taskHistory` sin `limit` (`TaskRepository.kt:322`), hogar
fantasma (confirmado el flujo completo: hogar se crea en Firestore antes de
que el wizard pida crear el `member`), `GoogleAuthManager` con las mismas ~4
responsabilidades (sin subir a 5), `Card.clickable(role=Button)` en 11
archivos (mismo conteo que v20 pero composición distinta: salieron
`EditTaskScreen`/`CreateTaskScreen`/`TaskDetailScreen`, entraron
`HouseholdMemberList`/`CreateHouseholdScreen`/`TaskListScreen`),
`CalendarScreen.kt` con `Column().forEach` sin tocar (archivo no modificado
desde v20), `TaskScreenModel.completeTask` en 161 líneas (creció, coincide
con el hallazgo de Arquitectura).

## Oleada B (5 especialistas, completada)

### Estética / UI Material3
Los 6 `ColorScheme` (3 nuevos + 3 existentes) completos y coherentes.
Hallazgo nuevo: `SemanticColors.info` (0xFF1565C0) coincide exactamente con
`OceanBlue800` (primary de Océano) — pierde distinción semántica en ese tema.
Tema Medianoche usa fondo oscuro incluso en modo "claro", lo que puede
desalinear los iconos de la barra de estado (`enableEdgeToEdge()` sigue el
modo del sistema, no el tema de la app). Inconsistencia de patrón: el
dropdown nuevo de Tema convive con `RadioOptionRow` para Idioma/Widget en el
mismo `SettingsSheet`. Selector de emoji es réplica fiel de
`EditProfileScreen.kt`, sin discrepancias.

### Accesibilidad
Contraste WCAG de los 3 temas nuevos verificado independientemente (34/34
pares medidos, mínimo 4.97:1 texto/3.79:1 no-textual) — todos pasan AA. Sin
regresiones nuevas. Auto-relleno de nombre Google sin `liveRegion` (el
usuario no se entera si no enfoca el campo) — SIGUE ABIERTO/PROPUESTA. Botón
de desplegar grid de emoji sin `expanded` state — heredado de
`EditProfileScreen.kt`, no introducido en esta ronda.

### UX
Auto-relleno sin indicio visual de que el campo se rellenó solo (bajo
impacto, editable). Selector de emoji: botón cerrado dice "Elegir emoji" en
vez de mostrar el emoji efectivo por defecto. Dropdown de 6 temas con
preview de color, cambio instantáneo sin reinicio — buena UX. **Hallazgo
real**: `hasGoogleLinked` (flag persistido) nunca se degrada si
`ensureCalendarAccessToken()` falla de forma persistente (token revocado) —
el usuario puede creer que su Calendar sigue sincronizado cuando lleva
tiempo fallando en silencio vía `bestEffort`. Hogar fantasma / deep link sin
autorización: confirmado sin cambios (archivos no tocados en esta ventana).

### Rendimiento
Sin hallazgos en ningún punto. `groupTasksByStatus` es un *move* byte-idéntico
con la misma memoización (`remember` con las mismas keys). `pendingIdempotencyKeys`
correctamente confinado a `screenModelScope`, sin necesidad de estructura
thread-safe. Grid de emoji (24 items, no lazy) es correcto por tamaño y por
vivir dentro de `AnimatedVisibility`. Los 12 `ColorScheme` son `val`
top-level, sin reconstrucción en recomposición. Logging de `bestEffort` sin
coste en el camino feliz.

### Seguridad / AppSec
Sin hallazgos explotables. `SecureStore.wasmJs.kt` (fix build web): el
roundtrip `ByteArray↔String` vía `charCodeAt`/`fromCharCode` no corrompe
bytes (0-255 sin signo, todo en JS puro) — aumenta copias transitorias de la
clave en memoria JS, higiene menor sin cambio de modelo de amenaza.
`firestore.rules`: confirmado que `households/{hid}` no valida tipo/longitud
de NINGÚN campo (ni `name` ni ahora `emoji`) — deuda preexistente documentada
por primera vez explícitamente, severidad baja. `displayName` de Google se
persiste en texto plano (correcto, no es secreto, mismo patrón que
email/uid). `idempotencyKey` sigue usando `Uuid.random()` CSPRNG; los únicos
call-sites reales nunca aceptan input de usuario. Nota colateral (no de esta
ronda): `withIdempotency` no valida que `functionName` coincida en el replay
— autolesión posible, no fuga entre usuarios.

## Siguiente paso
Oleada C (1 especialista): Cobertura de tests, sintetizando los huecos
identificados por las 10 anteriores (completeAssignment sin idempotencyKey
sin test, hasGoogleLinked sin degradar, todayStartEpoch intestable, emoji
"" edge case, etc.). Luego: aplicar los [APLICA YA] seguros, compilar el
informe final `docs/review-panel-expertos-2026-09-29-v21.md` y verificación
final.
