/**
 * `redeemReward` — ver kanban "CF redeemReward". Migra el canje de
 * recompensas de `FirestoreRepository.redeemReward` (2 llamadas REST
 * secuenciales: crear `rewardRedemptions`, luego descontar puntos — ver su
 * KDoc para el riesgo de dejarlo a medias) a una única transacción del
 * Admin SDK: se lee el coste real de `rewards/{rewardId}` (no se confía en
 * un `pointsSpent` mandado por el cliente, a diferencia de `firestore.rules`
 * v7 que sí lo validaba contra el cliente), se valida saldo, y se descuenta
 * + registra el canje atómicamente.
 *
 * Sin campo `stock` — el modelo de datos actual (`RewardResponse`) no lo
 * tiene; añadirlo sería un cambio de producto/esquema fuera del alcance de
 * "mover la lógica existente a una Cloud Function transaccional".
 */
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { db, REGION } from "./admin.js";
import { requireAuth, loadActiveMember } from "./auth.js";
import { clampTotalPoints } from "./points.js";
import { withIdempotency } from "./idempotency.js";
import { RewardDoc } from "./types.js";

export interface RedeemRewardRequest {
  householdId: string;
  rewardId: string;
  memberId: string;
  idempotencyKey?: string;
}

export interface RedeemRewardResponse {
  redemptionId: string;
  pointsSpent: number;
  redeemedAt: number;
  memberNewTotal: number;
}

export const redeemReward = onCall<RedeemRewardRequest, Promise<RedeemRewardResponse>>(
  { region: REGION },
  async (request) => {
    const uid = requireAuth(request.auth?.uid);
    const { householdId, rewardId, memberId, idempotencyKey } = request.data;
    if (!householdId || !rewardId || !memberId) {
      throw new HttpsError("invalid-argument", "householdId/rewardId/memberId son obligatorios");
    }
    const now = Date.now();

    return withIdempotency(idempotencyKey, "redeemReward", () =>
      db.runTransaction(async (tx) => {
        // Validación de seguridad (mismo patrón que completeRecurringTask):
        // el llamador debe ser miembro activo del hogar; `memberId` (quien
        // gasta los puntos) no tiene por qué ser el llamador — `firestore.rules`
        // (rewardRedemptions create, v7) tampoco lo exige hoy.
        await loadActiveMember(tx, householdId, uid);
        const targetMember = await loadActiveMember(tx, householdId, memberId);

        const rewardRef = db.doc(`households/${householdId}/rewards/${rewardId}`);
        const rewardSnap = await tx.get(rewardRef);
        if (!rewardSnap.exists) throw new HttpsError("not-found", "reward-not-found");
        const reward = rewardSnap.data() as RewardDoc;

        if (targetMember.totalPoints < reward.cost) {
          throw new HttpsError("failed-precondition", "insufficient-balance");
        }

        // ── Escritura (todo o nada) ──
        const memberRef = db.doc(`households/${householdId}/members/${memberId}`);
        tx.update(memberRef, { totalPoints: clampTotalPoints(targetMember.totalPoints, -reward.cost) });

        const redemptionRef = db.collection(`households/${householdId}/rewardRedemptions`).doc();
        tx.set(redemptionRef, {
          rewardId,
          memberId,
          redeemedAt: now,
          pointsSpent: reward.cost
        });

        return {
          redemptionId: redemptionRef.id,
          pointsSpent: reward.cost,
          redeemedAt: now,
          memberNewTotal: clampTotalPoints(targetMember.totalPoints, -reward.cost)
        };
      })
    );
  }
);
