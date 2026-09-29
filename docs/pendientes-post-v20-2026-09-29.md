# Pendientes técnicos post-v20 — 2026-09-29

Encargo sobre HEAD `a7d083e` (v0.7.54): extraer `TaskListRules.kt`, cubrir el
TOP-10 v20 con tests donde sea viable, y cerrar tarjetas kanban obsoletas.

## 1. Refactor: `TaskListRules.kt`

`TaskListScreen.kt` tenía ~1476 líneas mezclando UI (Compose) con lógica pura
de filtrado/agrupación. Se extrajo la lógica sin UI a un fichero nuevo:

- **Nuevo:** `composeApp/src/commonMain/kotlin/org/taskhub/ui/components/TaskListRules.kt`
  — `TaskWithStatus`, `TaskGroup`, `isTaskDueToday`, `isTaskCompletedToday`,
  `groupTasksByStatus` (+ helpers privados `taskComparator`,
  `localizedDayName`). Sin dependencias de Compose.
- **Modificado:** `ui/screens/TaskListScreen.kt` — se retira ese bloque
  (-198 líneas netas) y se importan los símbolos desde `ui.components`
  (quedan `internal`, visibles entre paquetes del mismo módulo).
- **Modificado:** `ui/models/TaskScreenModel.kt` — actualizado el comentario
  de arquitectura de cabecera, que apuntaba a la ubicación antigua de esta
  lógica.
- **Modificado:** `commonTest/.../ui/screens/TaskListScreenTest.kt` — añadidos
  los `import` de `TaskWithStatus`/`groupTasksByStatus` desde su nuevo
  paquete (el test sigue en `ui.screens`, la lógica que ejercita ahora vive
  en `ui.components`).

Sin cambios de comportamiento: `TaskListScreenTest` (4 tests existentes) sigue
pasando tal cual, solo con el `import` añadido.

`TaskFilter`/`TaskSort` **no** se movieron — ya vivían en
`ui/models/TaskScreenModel.kt` desde antes de este encargo, no en
`TaskListScreen.kt`.

## 2. Tests del TOP-10 v20

### Prioridad #1 — Contrato `idempotencyKey` (Kotlin, unitario con fake)

`FirestoreRepository.completeTask`/`donatePoints`/`completeAssignment`/
`redeemReward` ya aceptaban `idempotencyKey: String = Uuid.random().toString()`
desde el commit `a7d083e`, pero el contrato de negocio real —**por qué**
importa ese parámetro— vive en `TaskScreenModel.completeTask` (mapa
`pendingIdempotencyKeys`): un reintento tras un fallo AMBIGUO debe reutilizar
la MISMA clave (para que el servidor pueda deduplicar y no duplicar puntos);
una acción nueva tras un ÉXITO debe generar una clave distinta. Ese mecanismo
no tenía ningún test.

- **Modificado:** `commonTest/.../ui/models/FakeFirestoreRepository.kt` —
  añadida `completeTaskIdempotencyKeys: MutableList<String>` que registra la
  clave recibida en cada llamada a `completeTask` (antes se descartaba).
- **Modificado:** `commonTest/.../ui/models/TaskScreenModelTest.kt` — 3 tests
  nuevos:
  - `completeTask_exito_propagaUnaIdempotencyKeyNoVaciaAlRepo`
  - `completeTask_fallaAmbiguoYReintenta_reutilizaLaMismaIdempotencyKey`
    (regresión directa del mecanismo `pendingIdempotencyKeys`)
  - `completeTask_exito_laSiguienteAccionGeneraClaveNueva`

### Prioridad #2 — `reconcileMissingTaskPoints.ts` (TypeScript, Jest + emulador)

Sí había infraestructura preparada (`jest.integration.config.mjs`,
`testUtils/emulatorHelpers.ts`, patrón ya usado por
`purgeOldRecords.integration.test.ts`), así que se optó por un test de
**integración** contra el emulador de Firestore en vez de documentar un
hueco: la función usa `db.runTransaction`/`collectionGroup`, no es viable
como unitario puro sin reescribir su firma.

- **Nuevo:** `functions/src/reconcileMissingTaskPoints.integration.test.ts`
  — 5 tests: caso feliz (aplica puntos + marca `pointsApplied=true`), clamp a
  `MAX_TOTAL_POINTS`, idempotencia (repetir la pasada no vuelve a sumar),
  member borrado (no revienta, repara igualmente el registro), y "sin
  pendientes no hace nada".

No se ejecuta con `npm test` (esa suite ignora `*.integration.test.ts` a
propósito); corre con `npm run test:integration` (levanta el emulador vía
`firebase emulators:exec`).

### Prioridad #3 — `bestEffort` (Kotlin, `commonTest/.../platform/AppLogTest.kt`)

- **Nuevo:** `composeApp/src/commonTest/kotlin/org/taskhub/platform/AppLogTest.kt`
  — 3 tests: éxito devuelve el resultado del bloque, excepción no fatal
  devuelve `default` en silencio, `CancellationException` se relanza (nunca
  se traga, para no romper la cancelación cooperativa).

## 3. Tarjetas kanban cerradas

Proyecto `PVT_kwHOCXo7m84BjLGt`, campo Status → `Completado` (`43ad04c4`):

| Tarjeta | Item ID | Antes | Después |
|---|---|---|---|
| `[CF] Idempotency key en completeTask` | `PVTI_lAHOCXo7m84BjLGtzg8ujRE` | En curso | Completado |
| `[Arquitectura] Extraer TaskListRules.kt` | `PVTI_lAHOCXo7m84BjLGtzg8ujY4` | En curso | Completado |

La segunda no estaba en el encargo original pero coincide exactamente con el
refactor del punto 1 de este mismo informe (ya estaba "En curso" en el
tablero) — se cerró junto con la de idempotencyKey en la misma mutación
GraphQL.

## 4. Verificación

```
./gradlew :composeApp:compileDebugKotlinAndroid --console=plain
BUILD SUCCESSFUL in 40s

./gradlew :composeApp:jvmTest --rerun-tasks --console=plain
BUILD SUCCESSFUL in 22s
  TaskScreenModelTest: 15 tests, 0 failures (incluye los 3 nuevos)
  AppLogTest: 3 tests, 0 failures

cd functions && npm test
Test Suites: 2 passed, 2 total — Tests: 54 passed, 54 total

cd functions && npm run test:integration
Test Suites: 9 passed, 9 total — Tests: 45 passed, 45 total
  (incluye reconcileMissingTaskPoints.integration.test.ts: 5/5)
```

## 5. Archivos tocados (resumen)

- `composeApp/src/commonMain/kotlin/org/taskhub/ui/components/TaskListRules.kt` (nuevo)
- `composeApp/src/commonMain/kotlin/org/taskhub/ui/screens/TaskListScreen.kt`
- `composeApp/src/commonMain/kotlin/org/taskhub/ui/models/TaskScreenModel.kt`
- `composeApp/src/commonTest/kotlin/org/taskhub/ui/screens/TaskListScreenTest.kt`
- `composeApp/src/commonTest/kotlin/org/taskhub/ui/models/FakeFirestoreRepository.kt`
- `composeApp/src/commonTest/kotlin/org/taskhub/ui/models/TaskScreenModelTest.kt`
- `composeApp/src/commonTest/kotlin/org/taskhub/platform/AppLogTest.kt` (nuevo)
- `functions/src/reconcileMissingTaskPoints.integration.test.ts` (nuevo)
- `docs/pendientes-post-v20-2026-09-29.md` (este informe)

Commit final sin push (histórico previo del checkpoint automático
`wip: checkpoint 00-pendientes-post-v20-2026-09-29.md` en `f14983d` ya
contenía el grueso del refactor Kotlin + tests; este commit añade el test de
integración de `functions/` y este informe).
