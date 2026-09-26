/**
 * `undoTaskCompletion` — ver sección 2.4 del diseño, opción (ii) DECIDIDA
 * por Liberto: el servidor deriva el estado previo (en vez de depender de
 * `UndoState` en memoria del cliente, volátil) leyendo:
 *
 * - El registro de `taskHistory` INMEDIATAMENTE ANTERIOR a `completedAt`
 *   para esta tarea -> de ahí salen `previousLastCompletedDate`
 *   (= su `completedAt`) y `previousCompletedBy` (= su `memberId`). `null`
 *   en ambos si esta era la primera compleción registrada.
 * - Las asignaciones que ESTA compleción cerró (`status == 'completed' &&
 *   completedAt == completedAt`): su `dueDate` es, por construcción, la
 *   fecha límite del ciclo que se completó — exactamente el valor que tenía
 *   `task.nextDueAt` ANTES de esta compleción. Si la tarea no tenía
 *   asignaciones (completada solo vía `completeRecurringTask`, sin flujo de
 *   asignación), se recalcula con la MISMA fórmula que se usó al completar:
 *   `nextOccurrence` anclado en la compleción anterior (o en `createdAt` si
 *   esta era la primera).
 *
 * Idempotente por diseño de producto: si el registro de `taskHistory` para
 * `completedAt` ya no existe (undo repetido desde dos dispositivos, o
 * doble-tap ya resuelto por la guarda de reentrancia del cliente),
 * devuelve `{ reverted: false }` en vez de un error — deshacer dos veces no
 * debe ser una operación destructiva ni sorprender al usuario.
 */
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { DocumentSnapshot } from "firebase-admin/firestore";
import { db, REGION } from "./admin.js";
import { requireAuth, loadActiveMember, requireTrusted, loadHouseholdTimezone } from "./auth.js";
import { calculateNextDueDate } from "./completionHelpers.js";
import { clampTotalPoints } from "./points.js";
import { MemberDoc, TaskAssignmentDoc, TaskDoc, TaskHistoryDoc } from "./types.js";

export interface UndoTaskCompletionRequest {
  householdId: string;
  taskId: string;
  completedAt: number;
}

export interface UndoTaskCompletionResponse {
  reverted: boolean;
}

export const undoTaskCompletion = onCall<UndoTaskCompletionRequest, Promise<UndoTaskCompletionResponse>>(
  { region: REGION },
  async (request) => {
    const uid = requireAuth(request.auth?.uid);
    const { householdId, taskId, completedAt } = request.data;
    if (!householdId || !taskId || completedAt === undefined || completedAt === null) {
      throw new HttpsError("invalid-argument", "householdId/taskId/completedAt son obligatorios");
    }

    return db.runTransaction(async (tx) => {
      await loadActiveMember(tx, householdId, uid);
      const tz = await loadHouseholdTimezone(tx, householdId);

      const taskRef = db.doc(`households/${householdId}/tasks/${taskId}`);
      const taskSnap = await tx.get(taskRef);
      if (!taskSnap.exists) throw new HttpsError("not-found", "task-not-found");
      const task = taskSnap.data() as TaskDoc;

      const historySnap = await tx.get(
        db
          .collection(`households/${householdId}/taskHistory`)
          .where("taskId", "==", taskId)
          .where("completedAt", "==", completedAt)
      );
      const historyDoc = historySnap.docs[0];
      if (!historyDoc) {
        // Ya deshecho (o nunca existió) — no-op idempotente, ver KDoc de arriba.
        return { reverted: false };
      }
      const historyRecord = historyDoc.data() as TaskHistoryDoc;

      // Panel v16 (2026-09-24, hallazgo C2): antes, cualquier miembro activo
      // del hogar podía deshacer la compleción de CUALQUIER otro miembro
      // (leer su `taskHistory` ya es de lectura amplia, `isMember(hid)`), sin
      // ser el autor ni admin/owner — restando puntos ajenos a voluntad.
      // Antes de la migración a Cloud Functions, `firestore.rules` exigía
      // `isTrusted(hid) || resource.data.memberId == request.auth.uid` para
      // borrar un registro de `taskHistory`; se restaura la misma regla aquí.
      if (uid !== historyRecord.memberId) {
        await requireTrusted(tx, householdId, uid);
      }

      const previousHistorySnap = await tx.get(
        db
          .collection(`households/${householdId}/taskHistory`)
          .where("taskId", "==", taskId)
          .where("completedAt", "<", completedAt)
          .orderBy("completedAt", "desc")
          .limit(1)
      );
      const previousRecord = previousHistorySnap.docs[0]?.data() as TaskHistoryDoc | undefined;

      const memberRef = db.doc(`households/${householdId}/members/${historyRecord.memberId}`);
      const memberSnap = await tx.get(memberRef);
      const memberExists = memberSnap.exists;

      const cycleAssignmentsSnap = await tx.get(
        db
          .collection(`households/${householdId}/tasks/${taskId}/assignments`)
          .where("status", "==", "completed")
          .where("completedAt", "==", completedAt)
      );
      const cycleAssignments = cycleAssignmentsSnap.docs.map((d) => ({ ref: d.ref, data: d.data() as TaskAssignmentDoc }));

      // Fecha límite del ciclo SIGUIENTE al que se completó (la que creó la
      // asignación `next_{taskId}_{...}` a regenerar/borrar) — misma fórmula
      // que se usó al completar: `calculateNextDueDate(task, completedAt)`.
      const nextCycleDueDate = task.frequency !== "once" ? calculateNextDueDate(task, completedAt, tz) : null;
      let nextAssignmentSnap: DocumentSnapshot | null = null;
      if (nextCycleDueDate !== null) {
        nextAssignmentSnap = await tx.get(
          db.doc(`households/${householdId}/tasks/${taskId}/assignments/next_${taskId}_${nextCycleDueDate}`)
        );
      }

      // Fecha límite del ciclo que se COMPLETÓ (lo que `task.nextDueAt` valía
      // antes de esta compleción) — ver KDoc de arriba.
      let previousNextDueAt: number | null = null;
      if (task.frequency !== "once") {
        if (cycleAssignments.length > 0) {
          previousNextDueAt = cycleAssignments[0].data.dueDate;
        } else if (previousRecord) {
          previousNextDueAt = calculateNextDueDate(task, previousRecord.completedAt, tz);
        } else {
          previousNextDueAt = calculateNextDueDate(task, task.createdAt, tz);
        }
      }

      // ── Escritura (todo o nada) ──
      if (memberExists) {
        // Panel v18 (seguridad/QA CRÍTICO): esta era la única función que movía
        // `totalPoints` con `FieldValue.increment` ciego en vez de `clampTotalPoints`
        // (ver KDoc en points.ts) — podía dejar el saldo negativo si el miembro ya
        // había gastado los puntos que esta compleción le dio antes de deshacerla.
        tx.update(memberRef, {
          totalPoints: clampTotalPoints((memberSnap.data() as MemberDoc).totalPoints, -historyRecord.points)
        });
      }
      tx.delete(historyDoc.ref);

      const taskUpdate: Record<string, unknown> = {
        lastCompletedDate: previousRecord?.completedAt ?? null,
        completedBy: previousRecord?.memberId ?? null
      };
      if (task.frequency !== "once") taskUpdate.nextDueAt = previousNextDueAt;
      tx.update(taskRef, taskUpdate);

      for (const a of cycleAssignments) {
        tx.update(a.ref, { status: "assigned", completedAt: null, pointsAwarded: null, onTime: null });
      }

      if (nextAssignmentSnap?.exists && (nextAssignmentSnap.data() as TaskAssignmentDoc).status === "assigned") {
        tx.delete(nextAssignmentSnap.ref);
      }

      return { reverted: true };
    });
  }
);
