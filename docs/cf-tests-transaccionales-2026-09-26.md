# CF: tests de integración + migrar donatePoints/appreciateMember — 2026-09-26

## Veredicto: tarjeta obsoleta, movida a "Requiere decisión"

La tarjeta describe el estado del repo de forma incorrecta en varios puntos.
No se ha tocado ningún archivo de código: no hay commit asociado a este informe.

## Discrepancias encontradas

1. **Directorio equivocado.** La tarjeta pide trabajar en `firebase-functions/`.
   Ese directorio no existe — las Cloud Functions viven en `functions/`
   (`functions/src/index.ts`, `functions/package.json`). Crear un directorio
   `firebase-functions/` paralelo habría duplicado el código fuente real sin
   ejercitarlo, así que no se ha creado.

2. **`completeTask` no existe.** No hay ninguna CF con ese nombre. Lo más
   parecido son `completeAssignment` y `completeRecurringTask`
   (`functions/src/completeAssignment.ts`, `functions/src/completeRecurringTask.ts`),
   ambas ya transaccionales (`db.runTransaction`) y ya cubiertas por tests de
   integración.

3. **`redeemReward` y `donatePoints` ya están migradas.** Ambas usan
   `db.runTransaction` + `withIdempotency` (`functions/src/redeemReward.ts`,
   `functions/src/donatePoints.ts`). `donatePoints` ya incluye el tope
   server-side `MAX_PEER_TRANSFER_AMOUNT` (`functions/src/points.ts`) para
   donantes sin rol de confianza. Esto ya se hizo en un encargo anterior
   (commit `1562b81`, tarjeta kanban "CF donatePoints"
   `PVTI_lAHOCXo7m84BjLGtzg8ujUE`, ver
   `~/.hermes/claude-queue/done/kanban-cf-transaccionales.md`).

4. **`appreciateMember` no es una Cloud Function — nunca lo ha sido.** Es una
   función 100% cliente en
   `composeApp/src/commonMain/kotlin/org/taskhub/network/MemberRepository.kt:662`
   ("agradecer": acuñar puntos con tope semanal de 50 pts, PATCH REST directo
   con concurrencia optimista contra `firestore.rules`). El propio KDoc de
   `FirestoreRepository.donatePoints` (línea ~1083) ya documenta esta decisión
   explícitamente:

   > `appreciateMember` NO se migra en este cambio: a diferencia de
   > `donatePoints`, no existe ninguna Cloud Function equivalente todavía —
   > escribirla es infraestructura nueva que requiere despliegue externo,
   > fuera del alcance de "conectar una función ya existente".

   La tarjeta actual afirma que las 4 CF "están desplegadas... y funcionando",
   lo cual es falso para `appreciateMember`: convertirla en CF implica (a)
   nueva infraestructura desplegable, (b) reescribir el call-site en
   `MemberRepository.kt`/`FirestoreRepository.kt` para llamar a la CF en vez
   de hacer PATCH REST directo, y (c) decidir si el tope semanal server-side
   debe vivir en Firestore rules (como ahora) o en la CF. Es justo el tipo de
   trabajo ("infraestructura nueva, despliegue externo") que la tarjeta
   hermana `kanban-[CF] Migrar donatePoints-appreciateMember a CF
   transaccional.md` ya marcaba como "NO implementar, mover a Requiere
   decisión".

5. **Los tests de integración con emulador ya existen** — y no usan
   `@firebase/rules-unit-testing` ni `firebase-functions-test` (ninguna de
   las dos está en `functions/package.json`). En su lugar, `functions/`
   ya tiene un setup propio y funcional:
   - `functions/jest.integration.config.mjs` + `functions/src/testUtils/emulatorHelpers.ts`
     (arranca contra el emulador Firestore real vía `firebase-admin`).
   - `npm run test:integration` (envuelve todo en `firebase emulators:exec --only firestore`).
   - Un `*.integration.test.ts` por CF que mueve puntos:
     `completeAssignment`, `completeRecurringTask`, `redeemReward`,
     `donatePoints`, `undoTaskCompletion`, `reassignTaskCompletion`,
     `purgeOldRecords`, más `idempotency.integration.test.ts` dedicado.

   Añadir `@firebase/rules-unit-testing`/`firebase-functions-test` habría
   sido una segunda infraestructura de test redundante con la que ya
   funciona, sin cubrir ningún hueco real.

## Verificación ejecutada (sin cambios de código)

1. `cd functions && npm test` → **54/54 tests unitarios en verde**
   (`rules.test.ts`, `penalty.test.ts`).
2. `cd functions && npm run test:integration` → **8 suites / 40 tests en
   verde** contra el emulador de Firestore real (arranca y para el emulador
   solo). Cubre las 4 CF que mueven puntos + `undoTaskCompletion` +
   `reassignTaskCompletion` + `purgeOldRecords` + `idempotency`.
3. `cd functions && npx tsc --noEmit` → sin errores.
4. No se ha tocado `composeApp/` — no aplica recompilar
   `:composeApp:compileDebugKotlinAndroid` ni `:composeApp:jvmTest` porque no
   hay cambios Kotlin que verificar.

## Archivos tocados

Ninguno. `git status` queda limpio — no hay commit.

## Kanban

Tarjeta `[CF] Tests de integración con emulador para Cloud Functions`
(`PVTI_lAHOCXo7m84BjLGtzg8uiUk`) movida a **Requiere decisión**: la única
pieza de trabajo real pendiente (convertir `appreciateMember` en Cloud
Function) es una decisión de infraestructura/producto, no una migración
mecánica — necesita que alguien decida si merece la pena el despliegue nuevo
y la reescritura del cliente antes de que un encargo automático la
implemente.
