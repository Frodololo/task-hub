/**
 * Punto de entrada de Cloud Functions — ver
 * `docs/recurrencia-backend-cloud-functions-diseno-2026-09-11.md`.
 * Las 5 funciones de la fase 2a, todas en `europe-west1` (ver `admin.ts`).
 */
export { completeRecurringTask } from "./completeRecurringTask.js";
export { completeAssignment } from "./completeAssignment.js";
export { reassignTaskCompletion } from "./reassignTaskCompletion.js";
export { undoTaskCompletion } from "./undoTaskCompletion.js";
export { reconcileMissingTaskPointsScheduled as reconcileMissingTaskPoints } from "./reconcileMissingTaskPoints.js";
export { purgeOldRecordsScheduled as purgeOldRecords } from "./purgeOldRecords.js";
