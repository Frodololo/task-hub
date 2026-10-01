/**
 * `completeRecurringTask` — ver sección 2.1 y el diagrama de transacción de
 * la sección 4 de `docs/recurrencia-backend-cloud-functions-diseno-2026-09-11.md`.
 *
 * Reemplaza `FirestoreRepository.completeTask` (secuencia de N llamadas
 * REST) por una única `runTransaction`: lee tarea + miembro + asignaciones
 * del ciclo, valida, calcula el resultado con las MISMAS reglas puras que el
 * cliente (`rules.ts`/`penalty.ts`, portadas de Kotlin) y escribe todo junto
 * o nada.
 *
 * `now` se lee UNA vez fuera de `runTransaction` y se pasa como parámetro
 * puro (sección 8 del diseño: evita efectos no deterministas entre
 * reintentos automáticos de la transacción).
 */
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { db, REGION } from "./admin.js";
import { requireAuth, loadActiveMember, loadHouseholdTimezone, isTrustedMember } from "./auth.js";
import { resolveCompletionOutcome } from "./penalty.js";
import { resolveNextAssignmentDecision } from "./rules.js";
import { clampTotalPoints } from "./points.js";
import { calculateNextDueDate, effectiveDueDateForTask } from "./completionHelpers.js";
import { epochToLocalDate, compareLocalDate } from "./dates.js";
import { withIdempotency } from "./idempotency.js";
import { TaskAssignmentDoc, TaskDoc } from "./types.js";

export interface CompleteRecurringTaskRequest {
  householdId: string;
  taskId: string;
  memberId: string;
  /**
   * Concurrencia optimista opcional: `lastCompletedDate` que el caller tenía
   * cargado antes de completar. Si no coincide con el valor fresco leído en
   * la transacción, es que otro dispositivo ya completó/modificó la tarea
   * entretanto -> HttpsError('aborted').
   *
   * Nota de fidelidad con el diseño: la sección 2.1 nombra este campo
   * `expectedUpdateTime` (string RFC3339, heredado del mecanismo REST
   * `currentDocument.updateTime` que usa hoy el cliente), pero el diagrama
   * de transacción de la sección 4 compara literalmente
   * `expectedLastCompletedDate` contra `task.lastCompletedDate` (un long).
   * Dentro de una `runTransaction` real ya tenemos el valor de campo fresco
   * a mano (no hace falta ir a buscar el `updateTime` del documento), así
   * que se implementa la sección 4 tal cual — ver informe de la fase 2a.
   */
  expectedLastCompletedDate?: number | null;
  /** UUID v4 opcional — ver `idempotency.ts`. Un reintento del cliente con la MISMA key devuelve el resultado ya guardado en vez de completar la tarea dos veces. */
  idempotencyKey?: string;
}

export interface CompleteRecurringTaskResponse {
  completedAt: number;
  pointsAwarded: number;
  onTime: boolean;
  nextDueAt: number | null;
}

export const completeRecurringTask = onCall<CompleteRecurringTaskRequest, Promise<CompleteRecurringTaskResponse>>(
  { region: REGION, enforceAppCheck: true },
  async (request) => {
    const uid = requireAuth(request.auth?.uid);
    const { householdId, taskId, memberId, expectedLastCompletedDate, idempotencyKey } = request.data;
    if (!householdId || !taskId || !memberId) {
      throw new HttpsError("invalid-argument", "householdId/taskId/memberId son obligatorios");
    }
    const now = Date.now();

    return withIdempotency(idempotencyKey, "completeRecurringTask", () =>
      db.runTransaction(async (tx) => {
        const taskRef = db.doc(`households/${householdId}/tasks/${taskId}`);
        const taskSnap = await tx.get(taskRef);
        if (!taskSnap.exists) throw new HttpsError("not-found", "task-not-found");
        const task = taskSnap.data() as TaskDoc;

        // Validación de seguridad (sección 2.1): llamador miembro activo +
        // memberId (quien recibe los puntos) también miembro activo del mismo hogar.
        await loadActiveMember(tx, householdId, uid);
        const targetMember = await loadActiveMember(tx, householdId, memberId);
        // Solo el propio miembro (documento keyed por su UID) o un admin/owner
        // del hogar pueden completar la tarea en nombre de `memberId` — sin
        // esto, cualquier miembro activo podía otorgar puntos a OTRO miembro
        // por una tarea que no completó, sin autorización.
        if (uid !== memberId && !(await isTrustedMember(tx, householdId, uid))) {
          throw new HttpsError("permission-denied", "not-self-or-trusted");
        }
        const memberRef = db.doc(`households/${householdId}/members/${memberId}`);
        const tz = await loadHouseholdTimezone(tx, householdId);

        const assignmentsSnap = await tx.get(
          db.collection(`households/${householdId}/tasks/${taskId}/assignments`).where("status", "==", "assigned")
        );

        // Panel v16 (2026-09-24, hallazgo C1/C3): antes, este guard solo se
        // ejecutaba si el cliente mandaba un `expectedLastCompletedDate` no
        // nulo — para una tarea nunca completada (o justo tras un undo, que
        // deja `lastCompletedDate = null`), el guard se saltaba por completo,
        // permitiendo llamadas repetidas/duplicadas sin detectar el conflicto.
        // Ahora la comparación es SIEMPRE obligatoria: `undefined`/`null`
        // significa "el caller espera que la tarea nunca se haya completado".
        const expectedValue = expectedLastCompletedDate ?? null;
        if ((task.lastCompletedDate ?? null) !== expectedValue) {
          throw new HttpsError("aborted", "conflict");
        }

        // Panel v16, hallazgo C1: sin esto, un caller que refresque el estado
        // antes de cada llamada (script directo con el ID token del usuario,
        // sin pasar por `isDueToday` del cliente) podía volver a completar la
        // MISMA tarea recurrente tantas veces como quisiera en el mismo día,
        // otorgándose puntos sin límite. `isDueToday`/`isDueOn` completas (con
        // ventana de "atrasada") siguen siendo responsabilidad del cliente
        // (ver cabecera de `rules.ts`), pero este chequeo mínimo —no se puede
        // volver a completar la MISMA tarea el MISMO día de calendario (zona
        // del hogar, D1: `households/{hid}.timezone`, fallback `DEFAULT_TZ`)—
        // acota el abuso a como máximo 1 otorgamiento por
        // tarea y día, cerrando el vector de farming ilimitado.
        if (task.lastCompletedDate != null) {
          const lastLocalDate = epochToLocalDate(task.lastCompletedDate, tz);
          const nowLocalDate = epochToLocalDate(now, tz);
          if (compareLocalDate(lastLocalDate, nowLocalDate) === 0) {
            throw new HttpsError("failed-precondition", "already-completed-today");
          }
        }

        const effectiveDueDate = effectiveDueDateForTask(task, tz);
        const outcome = resolveCompletionOutcome(task, effectiveDueDate, now);
        const nextDueDate = calculateNextDueDate(task, now, tz);

        const assignedThisCycle = assignmentsSnap.docs.map((d) => ({ ref: d.ref, data: d.data() as TaskAssignmentDoc }));
        const existingAssignmentForMember = assignedThisCycle.find((a) => a.data.memberId === memberId);

        // ── Escritura (todo o nada) ──
        const taskUpdate: Record<string, unknown> = { lastCompletedDate: now, completedBy: memberId };
        if (task.frequency !== "once") taskUpdate.nextDueAt = nextDueDate;
        tx.update(taskRef, taskUpdate);

        // Panel v17 (hallazgo CRÍTICO de seguridad): clamp en vez de
        // FieldValue.increment ciego — ver KDoc de [clampTotalPoints].
        tx.update(memberRef, { totalPoints: clampTotalPoints(targetMember.totalPoints, outcome.pointsAwarded) });

        const historyRef = db.collection(`households/${householdId}/taskHistory`).doc();
        tx.set(historyRef, {
          taskId,
          memberId,
          points: outcome.pointsAwarded,
          completedAt: now,
          onTime: outcome.onTime,
          pointsApplied: true
        });

        for (const a of assignedThisCycle) {
          tx.update(a.ref, {
            status: "completed",
            completedAt: now,
            pointsAwarded: a.data.memberId === memberId ? outcome.pointsAwarded : 0,
            onTime: outcome.onTime
          });
        }

        if (task.frequency !== "once" && nextDueDate !== null) {
          // Panel v17 (hallazgo CRÍTICO de arquitectura): faltaba pasar `tz`
          // — sin ella, la decisión de a quién asignar el siguiente ciclo caía
          // silenciosamente al DEFAULT_TZ ("Europe/Madrid") aunque `tz` ya se
          // hubiera cargado arriba para el resto de esta misma transacción.
          const decision = resolveNextAssignmentDecision(
            task.assignmentRotation ?? [],
            nextDueDate,
            memberId,
            assignedThisCycle.map((a) => a.data),
            tz
          );
          if (decision.shouldCreate) {
            const nextRef = db.doc(`households/${householdId}/tasks/${taskId}/assignments/next_${taskId}_${nextDueDate}`);
            tx.create(nextRef, {
              taskId,
              memberId: decision.memberId,
              mandatory: existingAssignmentForMember?.data.mandatory ?? false,
              dueDate: nextDueDate,
              status: "assigned",
              assignedAt: now
            });
          }
        }

        return {
          completedAt: now,
          pointsAwarded: outcome.pointsAwarded,
          onTime: outcome.onTime,
          nextDueAt: task.frequency !== "once" ? nextDueDate : null
        };
      })
    );
  }
);
