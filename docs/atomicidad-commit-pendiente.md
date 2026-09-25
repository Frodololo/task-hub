# Atomicidad real de `completeTask`/`reassignTaskCompletion` vía `:commit` — evaluado y descartado (2026-08-30)

## Encargo

Sustituir las secuencias multi-escritura de `completeTask` y `reassignTaskCompletion`
(`network/FirestoreRepository.kt`) por una transacción atómica usando el endpoint
`:commit` de la API REST de Firestore con `fieldTransforms` (increment,
setToServerValue, etc.), documentando cada transform con KDoc.

## Decisión: NO implementado

Se revisó el código y se decidió **no** escribir el payload `:commit`. Motivo:
ni este repositorio ni ningún test de este proyecto han hecho nunca una llamada
al endpoint `:commit` de Firestore, y este entorno no tiene acceso a un proyecto
Firestore real (ni emulador) contra el que verificar el payload. El propio
encargo lo advierte explícitamente: *"si no puedes garantizar un `:commit`
correcto sin verificarlo contra la API, NO hagas un fix mal formado"*.

Esto coincide con la conclusión independiente de la primera auditoría
(`docs/audit-2026-08-30.md`, hallazgo A21 y nota 3 de "Deuda técnica
pendiente"), que evaluó lo mismo y lo descartó por el mismo motivo. Esta
segunda revisión confirma que nada ha cambiado desde entonces: sigue sin existir
en el repo ningún uso probado de `:commit`, `Write`, `DocumentTransform` ni
`Precondition` sobre el que apoyarse (solo existe el patrón PATCH +
`currentDocument.updateTime` como query param, usado en `addMemberPoints`/
`addMemberAchievement` — un mecanismo distinto: precondition de una escritura
REST individual, no de un batch `:commit`).

### Por qué no basta con "lo sé de memoria"

El formato de `:commit` es razonablemente conocido (`writes[]`, cada `Write`
con `update`/`transform`/`delete`, `currentDocument` como precondition,
`updateMask`, y dentro de `transform.fieldTransforms[]` las primitivas
`increment`, `setToServerValue`, `maximum`, `minimum`,
`appendMissingElements`, `removeAllFromArray`). Pero construir el payload
completo implica acertar, sin poder probarlo:

- La URL exacta: es `.../databases/(default)/documents:commit` — **hermano**
  de `.../documents`, no un sufijo de `baseUrl` (`baseUrl` en este repo ya
  incluye `/documents`, así que sería `baseUrl.removeSuffix("/documents") +
  ":commit"`, un detalle fácil de dejar mal si no se ejecuta nunca).
- La forma exacta de `increment` (`{"integerValue": "N"}` anidado bajo
  `increment`, no un entero plano).
- Si `transform` y `update` pueden ir en el mismo `Write` o requieren dos
  `Write` separados apuntando al mismo documento dentro del mismo `writes[]`.
- El comportamiento exacto de `updateMask` combinado con `transform` (si se
  omite mal, un `update` parcial mal enmascarado podría pisar campos que no
  debían tocarse — justo el tipo de fallo silencioso e irreversible que un
  sistema de puntos no puede permitirse).
- Los DTOs (`FirestoreValue`, `FirestoreDocument` en `FirestoreDtos.kt`) no
  tienen hoy representación para `Write`/`DocumentTransform`/`FieldTransform`;
  habría que añadirlos enteros de cero, sin ningún test existente que ejercite
  su (de)serialización contra la API real.

Ninguno de estos puntos es "inventar la primitiva" en el sentido de
imaginar una feature que no existe — son detalles de forma del payload que sí
existen mal documentados. Es exactamente el tipo de payload potencialmente mal
formado contra el que advierte el encargo, sobre un sistema de puntos en
producción.

## Qué haría falta para hacerlo con seguridad

1. **Firestore Emulator Suite** (`firebase emulators:start --only firestore`)
   corriendo en un entorno con acceso a él, para poder golpear `:commit` de
   verdad y ver la respuesta/errores reales antes de tocar producción.
2. Un test de integración (`commonTest` o un script standalone) que:
   - Cree un documento de miembro con `totalPoints` conocido.
   - Envíe un `:commit` con un `Write` de `transform` (`increment`) + un
     `Write` de `update` sobre el documento de tarea, en la misma petición.
   - Verifique el `totalPoints` resultante y que ambos documentos cambiaron
     atómicamente (o ninguno, forzando un error de precondition).
3. Con eso verificado, extender `FirestoreDtos.kt` con los DTOs de
   `Write`/`DocumentTransform`/`FieldTransform`/`Precondition`, y solo
   entonces reescribir `completeTask`/`reassignTaskCompletion` para usarlos.
4. Cada `fieldTransform` documentado con KDoc en español, como pide el
   encargo original.

## Estado actual (sin cambios funcionales en este encargo)

`completeTask` y `reassignTaskCompletion` **no se han tocado**. Siguen con las
mitigaciones ya existentes de las dos pasadas de auditoría previas:

- Guarda de reentrancia en `TaskScreenModel` (`A8`, evita doble-tap).
- Orden de escrituras invertido donde aplica para que un fallo a mitad de
  camino deje el "peor" estado recuperable en vez de puntos duplicados (`A3`,
  `A4`, `A5`).
- Concurrencia optimista (`currentDocument.updateTime` + reintento) dentro de
  `addMemberPoints`/`addMemberAchievement`, que sí protege contra la pérdida
  de un incremento concurrente en el documento del miembro — aunque no hace
  atómica la secuencia completa frente al resto de escrituras de
  `completeTask`/`reassignTaskCompletion`.

No hay atomicidad de extremo a extremo entre "marcar tarea completada",
"sumar puntos" y "guardar historial" (o, en `reassignTaskCompletion`, entre
las dos transferencias de puntos y la actualización de `completedBy`/
historial). Un fallo de red a mitad de secuencia sigue siendo posible y sigue
dejando estado parcial, mitigado pero no eliminado por lo anterior.

## Añadido (2026-08-31) — `updateTask` / reasignación de miembros al editar

Hallazgo nuevo del panel de expertos v2 (Experto 7, #12): `TaskScreenModel.updateTask`
sincronizaba las asignaciones de una tarea editada con `repo.deleteAssignments()`
+ `repo.assignTask()` como dos llamadas HTTP independientes. Si la segunda
fallaba a mitad de camino (p. ej. tras crear la asignación del primer
miembro de tres), la tarea ya se había quedado sin ninguna asignación previa
por el `deleteAssignments` anterior — peor caso: tarea completamente
desasignada tras un fallo de red al guardar un simple cambio de título.

**Mitigado** (no atómico de extremo a extremo, mismo motivo que el resto de
este documento — sin acceso a `:commit`/emulador para verificar un payload
transaccional): se añadió `FirestoreRepository.replaceAssignments()`, que
invierte el orden — crea las asignaciones nuevas primero y solo borra las
antiguas si esa creación no lanzó excepción. Si el paso de creación falla, la
tarea conserva sus asignaciones anteriores (estado recuperable, el usuario
puede reintentar) en vez de quedarse sin ninguna. El peor caso posible ahora
es un fallo justo en el borrado de las antiguas tras crear las nuevas con
éxito, que deja asignaciones antiguas + nuevas duplicadas — un estado
extraño pero recuperable (basta con reeditar y guardar de nuevo), muy
preferible a perder todas las asignaciones.

`TaskScreenModel.updateTask` se actualizó para llamar a `replaceAssignments`
en vez de las dos funciones por separado.

## Añadido (2026-09-25) — Concurrencia optimista en `updateTask`

Tarjeta kanban "Red updateTask": `TaskRepository.updateTask` reescribía el
documento completo de la tarea sin ninguna protección frente a escrituras
concurrentes — dos miembros editando la MISMA tarea casi a la vez (uno
cambia el título, otro los puntos) sufrían last-writer-wins puro: el segundo
PATCH en llegar pisaba el documento entero con su propia foto, revirtiendo
en silencio el cambio del primero.

**Implementado** (patrón de cliente, NO transacción server-side — mismo
motivo que el resto de este documento): se aplicó el mismo patrón ya
existente en `addMemberPoints`/`addMemberAchievement`/`updateSubtasks`
(`currentDocument.updateTime` como precondición de la escritura REST
individual). Cada intento relee el documento para obtener su `updateTime`
fresco y repite el mismo PATCH; si Firestore rechaza la escritura
(`FAILED_PRECONDITION`/`ABORTED`, es decir, otro escritor ganó la carrera
entretanto) se reintenta hasta `FirestoreClient.OPTIMISTIC_WRITE_MAX_RETRIES`
(3) veces. A diferencia de `updateSubtasks` (que recalcula su array sobre el
documento fresco en cada intento), aquí los campos a escribir ya son el
estado final decidido por quien edita — no dependen del documento leído, así
que "re-aplicar cambios locales" en el reintento es, en la práctica, repetir
el mismo PATCH con una precondición nueva.

Si se agotan los 3 intentos, se lanza `TaskConflictException` (nueva, en
`network/TaskRepository.kt`) en vez de la `FirestoreException` cruda;
`TaskScreenModel.updateTask` la captura por tipo (mismo patrón que
`InsufficientBalanceException` en `MemberScreenModel.redeemReward`) y muestra
el mensaje específico `task_error_conflict` ("La tarea fue modificada por
otro miembro. Recarga e inténtalo de nuevo.", ES/EN en `AppStrings.kt`),
recargando además el detalle para que la UI muestre la versión real en vez
de los campos que el usuario intentó guardar sin éxito.

## Añadido (2026-09-25) — `createTask` + `assignTask` no atómico

Tarjeta kanban "Red createTask+assign": `TaskScreenModel.createTask` crea la
tarea (`repo.createTask`) y la asigna (`repo.assignTask`) como dos
escrituras REST independientes. Si la segunda falla — de red, a mitad de
camino con solo algunos de varios miembros ya asignados, o directamente
antes de crear ninguna asignación — la tarea queda huérfana: creada pero sin
nadie asignado, invisible como pendiente para cualquiera y solo recuperable
reeditándola a mano.

**Opción evaluada y descartada — A (fusionar en una sola escritura):** no
hay forma de crear la tarea Y sus documentos de asignación (subcolección
`tasks/{id}/assignments`, con su propio `dueDate`/`status`/`mandatory` por
miembro) en una única petición REST sin el endpoint transaccional `:commit`
— ya descartado en este mismo documento por falta de acceso a un proyecto
Firestore real/emulador contra el que verificar el payload. Embeber los
datos de asignación como campos denormalizados en el propio documento de
tarea (variante de "A" sin `:commit`) exigiría además una migración de todo
el camino de lectura (`getAssignments`/`getAllAssignments`, notificaciones
de asignación, sincronización de Calendar, todo lo que hoy asume
`assignments` como subcolección) para un problema que no lo requiere.

**Opción evaluada y descartada — B (Cloud Function `createTaskAndAssign`):**
descartada por el mismo motivo que "A" con `:commit": añadir infraestructura
nueva (con su propio despliegue/latencia) para un caso cuya mitigación de
cliente ya dispone de primitivas idempotentes y ya probadas
(`deleteAssignments`/`deleteTask`) — desproporcionado para el encargo.

**Implementado — variante de C (compensación inmediata, no reconciliación
diferida):** en vez de un worker en segundo plano que detecte tareas
huérfanas más tarde, `TaskScreenModel` deshace la creación EN EL MOMENTO si
`assignTask` falla: nueva función `rollbackUnassignedTask` que borra (best-
effort) las asignaciones parciales que sí llegaron a crearse y, después, la
propia tarea — dejando el estado tal como estaba ANTES de intentar crearla,
en vez de "creada pero sin asignar". El error original de `assignTask` se
sigue propagando al caller (mensaje `task_error_creating` ya existente: "No
se pudo crear la tarea", coherente con el resultado neto tras el rollback).

Sigue sin ser atómico de extremo a extremo: si el propio rollback falla
(p. ej. se pierde la conexión justo en ese instante), la tarea huérfana
queda exactamente igual que ANTES de este fix — nunca peor, pero tampoco
garantizado. Sin acceso a un emulador de Firestore para verificar un
`:commit` real, esta compensación de cliente es la mitigación más fuerte que
se puede implementar y probar con confianza hoy.
