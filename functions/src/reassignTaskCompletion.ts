/**
 * `reassignTaskCompletion` — ver sección 2.3 del diseño. Reemplaza las 4
 * escrituras HTTP secuenciales de `FirestoreRepository.reassignTaskCompletion`
 * por una única transacción: actualiza `completedBy`, transfiere puntos,
 * reasigna el registro de `taskHistory` y el documento de `assignments` de
 * esa misma compleción.
 *
 * Requiere llamador `isTrusted` (owner o admin) — igual que hoy en
 * `firestore.rules` para corregir quién completó una tarea.
 *
 * A diferencia de `completeAssignment`/`donatePoints`/`redeemReward`, esta
 * función NO usa `idempotencyKey`: es auto-idempotente por diseño porque lee
 * `task.completedBy` fresco dentro de la propia transacción y solo mueve
 * puntos si difiere de `newMemberId` (ver `oldMemberId !== newMemberId` más
 * abajo) — un reintento con el mismo `newMemberId` tras un timeout ambiguo
 * converge a no-op. Si en el futuro se cambia esa lectura por un
 * `FieldValue.increment` ciego, esta propiedad se rompe y haría falta
 * `idempotencyKey` como en sus hermanas (panel v23, hallazgo QA #8).
 */
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { QueryDocumentSnapshot } from "firebase-admin/firestore";
import { db, REGION } from "./admin.js";
import { requireAuth, requireTrusted, loadActiveMember } from "./auth.js";
import { clampTotalPoints } from "./points.js";
import { TaskAssignmentDoc, TaskDoc, TaskHistoryDoc, MemberDoc } from "./types.js";

export interface ReassignTaskCompletionRequest {
  householdId: string;
  taskId: string;
  newMemberId: string;
}

export interface ReassignTaskCompletionResponse {
  previousMemberId: string | null;
  pointsTransferred: number;
}

export const reassignTaskCompletion = onCall<ReassignTaskCompletionRequest, Promise<ReassignTaskCompletionResponse>>(
  { region: REGION, enforceAppCheck: true },
  async (request) => {
    const uid = requireAuth(request.auth?.uid);
    const { householdId, taskId, newMemberId } = request.data;
    if (!householdId || !taskId || !newMemberId) {
      throw new HttpsError("invalid-argument", "householdId/taskId/newMemberId son obligatorios");
    }
    const now = Date.now();

    return db.runTransaction(async (tx) => {
      await requireTrusted(tx, householdId, uid);
      const newMember = await loadActiveMember(tx, householdId, newMemberId);

      const taskRef = db.doc(`households/${householdId}/tasks/${taskId}`);
      const taskSnap = await tx.get(taskRef);
      if (!taskSnap.exists) throw new HttpsError("not-found", "task-not-found");
      const task = taskSnap.data() as TaskDoc;

      const oldMemberId = task.completedBy ?? null;
      const completedAt = task.lastCompletedDate ?? now;

      // Panel v17 (hallazgo CRÍTICO de seguridad): antes se restaba/sumaba
      // con FieldValue.increment ciego, sin tope — leer el doc del miembro
      // saliente aquí (dentro de la transacción) para poder clampar el
      // resultado igual que en completeRecurringTask/completeAssignment.
      let oldMemberSnap = null;
      if (oldMemberId !== null && oldMemberId !== newMemberId) {
        oldMemberSnap = await tx.get(db.doc(`households/${householdId}/members/${oldMemberId}`));
      }

      const historySnap = await tx.get(
        db
          .collection(`households/${householdId}/taskHistory`)
          .where("taskId", "==", taskId)
          .where("completedAt", "==", completedAt)
      );
      const historyDoc = historySnap.docs[0];
      const historyRecord = historyDoc?.data() as TaskHistoryDoc | undefined;
      const actualPoints = historyRecord?.points ?? task.points;

      let matchingAssignment: QueryDocumentSnapshot | undefined;
      if (oldMemberId !== null && oldMemberId !== newMemberId) {
        const assignmentsSnap = await tx.get(
          db
            .collection(`households/${householdId}/tasks/${taskId}/assignments`)
            .where("status", "==", "completed")
            .where("completedAt", "==", completedAt)
            .where("memberId", "==", oldMemberId)
        );
        matchingAssignment = assignmentsSnap.docs[0];
      }

      // ── Escritura (todo o nada) ──
      tx.update(taskRef, { completedBy: newMemberId });

      if (oldMemberId !== null && oldMemberId !== newMemberId) {
        if (oldMemberSnap?.exists) {
          const oldMember = oldMemberSnap.data() as MemberDoc;
          tx.update(oldMemberSnap.ref, { totalPoints: clampTotalPoints(oldMember.totalPoints, -actualPoints) });
        }
        tx.update(db.doc(`households/${householdId}/members/${newMemberId}`), {
          totalPoints: clampTotalPoints(newMember.totalPoints, actualPoints)
        });
        if (historyDoc) {
          tx.update(historyDoc.ref, { memberId: newMemberId });
        }
        if (matchingAssignment) {
          tx.update(matchingAssignment.ref, { memberId: newMemberId });
        }
      }

      return {
        previousMemberId: oldMemberId,
        pointsTransferred: oldMemberId !== null && oldMemberId !== newMemberId ? actualPoints : 0
      };
    });
  }
);
