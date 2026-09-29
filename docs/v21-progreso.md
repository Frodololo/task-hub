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

## Siguiente paso
Oleada B (5 especialistas): UI/Material3, Accesibilidad, UX, Rendimiento,
Seguridad/AppSec. Luego oleada C (1): Cobertura de tests, que sintetiza los
huecos identificados por las 10 anteriores.
