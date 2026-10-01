/**
 * `completeAssignment` — ver sección 2.2 del diseño. Transacción análoga a
 * `completeRecurringTask` pero ancla la lectura en el documento de
 * asignación, no en `task.lastCompletedDate` — replica
 * `FirestoreRepository.completeAssignment` + `regenerateNextAssignment`.
 */
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { db, REGION } from "./admin.js";
import { requireAuth, loadActiveMember, loadHouseholdTimezone, isTrustedMember } from "./auth.js";
import { resolveCompletionOutcome } from "./penalty.js";
import { resolveNextAssignmentDecision } from "./rules.js";
import { clampTotalPoints } from "./points.js";
import { calculateNextDueDate, effectiveDueDateForAssignment } from "./completionHelpers.js";
import { withIdempotency } from "./idempotency.js";
import { TaskAssignmentDoc, TaskDoc } from "./types.js";

export interface CompleteAssignmentRequest {
  householdId: string;
  taskId: string;
  assignmentId: string;
  /** UUID v4 opcional — ver `idempotency.ts`. Un reintento del cliente con la MISMA key devuelve el resultado ya guardado en vez de completar la asignación dos veces. */
  idempotencyKey?: string;
}

export interface CompleteAssignmentResponse {
  completedAt: number;
  pointsAwarded: number;
  onTime: boolean;
  nextDueAt: number | null;
  assignmentId: string;
}

export const completeAssignment = onCall<CompleteAssignmentRequest, Promise<CompleteAssignmentResponse>>(
  { region: REGION },
  async (request) => {
    const uid = requireAuth(request.auth?.uid);
    const { householdId, taskId, assignmentId, idempotencyKey } = request.data;
    if (!householdId || !taskId || !assignmentId) {
      throw new HttpsError("invalid-argument", "householdId/taskId/assignmentId son obligatorios");
    }
    const now = Date.now();

    return withIdempotency(idempotencyKey, "completeAssignment", () =>
      db.runTransaction(async (tx) => {
        await loadActiveMember(tx, householdId, uid);

        const taskRef = db.doc(`households/${householdId}/tasks/${taskId}`);
        const taskSnap = await tx.get(taskRef);
        if (!taskSnap.exists) throw new HttpsError("not-found", "task-not-found");
        const task = taskSnap.data() as TaskDoc;

        const assignmentRef = db.doc(`households/${householdId}/tasks/${taskId}/assignments/${assignmentId}`);
        const assignmentSnap = await tx.get(assignmentRef);
        if (!assignmentSnap.exists) throw new HttpsError("not-found", "assignment-not-found");
        const assignment = assignmentSnap.data() as TaskAssignmentDoc;
        if (assignment.taskId !== taskId) throw new HttpsError("invalid-argument", "assignment-task-mismatch");
        if (assignment.status !== "assigned") throw new HttpsError("aborted", "conflict");

        // Solo el miembro asignado (documento de miembro keyed por su UID,
        // ver `MemberRepository.createMember`) o un admin/owner del hogar
        // pueden completar esta asignación — sin esto, cualquier miembro
        // activo del hogar podía completar la asignación de OTRO miembro y
        // otorgarle puntos en su nombre sin autorización.
        if (uid !== assignment.memberId && !(await isTrustedMember(tx, householdId, uid))) {
          throw new HttpsError("permission-denied", "not-assignee-or-trusted");
        }

        const targetMember = await loadActiveMember(tx, householdId, assignment.memberId);
        const tz = await loadHouseholdTimezone(tx, householdId);

        const siblingsSnap = await tx.get(
          db.collection(`households/${householdId}/tasks/${taskId}/assignments`).where("status", "==", "assigned")
        );

        const effectiveDueDate = effectiveDueDateForAssignment(task, assignment.dueDate, tz);
        const outcome = resolveCompletionOutcome(task, effectiveDueDate, now);
        const nextDueDate = calculateNextDueDate(task, now, tz);

        const siblings = siblingsSnap.docs.map((d) => ({ ref: d.ref, data: d.data() as TaskAssignmentDoc }));

        // ── Escritura (todo o nada) ──
        tx.update(assignmentRef, {
          status: "completed",
          completedAt: now,
          pointsAwarded: outcome.pointsAwarded,
          onTime: outcome.onTime
        });

        const memberRef = db.doc(`households/${householdId}/members/${assignment.memberId}`);
        // Panel v17 (hallazgo CRÍTICO de seguridad): clamp en vez de
        // FieldValue.increment ciego — ver KDoc de [clampTotalPoints].
        tx.update(memberRef, { totalPoints: clampTotalPoints(targetMember.totalPoints, outcome.pointsAwarded) });

        const historyRef = db.collection(`households/${householdId}/taskHistory`).doc();
        tx.set(historyRef, {
          taskId,
          memberId: assignment.memberId,
          points: outcome.pointsAwarded,
          completedAt: now,
          onTime: outcome.onTime,
          pointsApplied: true
        });

        const taskUpdate: Record<string, unknown> = { lastCompletedDate: now, completedBy: assignment.memberId };
        if (task.frequency !== "once") taskUpdate.nextDueAt = nextDueDate;
        tx.update(taskRef, taskUpdate);

        // Asignaciones hermanas del mismo ciclo (excluyendo la que ya
        // procesamos arriba) -> completadas con pointsAwarded=0 (ver KDoc de
        // `FirestoreRepository.completeAssignment`, no infla estadísticas de
        // quien no recibió puntos).
        for (const sibling of siblings) {
          if (sibling.ref.id === assignmentId) continue;
          tx.update(sibling.ref, { status: "completed", completedAt: now, pointsAwarded: 0, onTime: outcome.onTime });
        }

        if (task.frequency !== "once" && nextDueDate !== null) {
          // Panel v17 (hallazgo CRÍTICO de arquitectura): ver comentario
          // equivalente en completeRecurringTask.ts — faltaba `tz`.
          const decision = resolveNextAssignmentDecision(
            task.assignmentRotation ?? [],
            nextDueDate,
            assignment.memberId,
            siblings.map((s) => s.data),
            tz
          );
          if (decision.shouldCreate) {
            const nextRef = db.doc(`households/${householdId}/tasks/${taskId}/assignments/next_${taskId}_${nextDueDate}`);
            tx.create(nextRef, {
              taskId,
              memberId: decision.memberId,
              mandatory: assignment.mandatory,
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
          nextDueAt: task.frequency !== "once" ? nextDueDate : null,
          assignmentId
        };
      })
    );
  }
);
