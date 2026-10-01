/**
 * `donatePoints` — ver kanban "CF donatePoints". Migra
 * `MemberRepository.donatePoints` (dos PATCH REST secuenciales: restar al
 * donante, luego sumar al receptor — con reversión best-effort si el
 * segundo falla, ver su KDoc para el catálogo de fallos parciales que esto
 * podía dejar) a una única transacción del Admin SDK: ambos lados se leen y
 * escriben juntos, o ninguno.
 *
 * Reglas de negocio portadas de `PointsRules.kt` (self / importe / saldo) +
 * la cota de seguridad `isPeerPointsTransfer` de `firestore.rules` (tope de
 * 1000 pts por transferencia de un donante NO `isTrusted`, ver
 * `points.ts#MAX_PEER_TRANSFER_AMOUNT`): el Admin SDK salta
 * `firestore.rules` por completo, así que esa cota hay que reimplementarla
 * aquí o desaparecería para todo el mundo, no solo para quien hoy la tiene
 * (donante sin rol admin/owner).
 */
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { db, REGION } from "./admin.js";
import { requireAuth, loadActiveMember, isTrustedMember } from "./auth.js";
import { clampTotalPoints, MAX_PEER_TRANSFER_AMOUNT } from "./points.js";
import { withIdempotency } from "./idempotency.js";

export interface DonatePointsRequest {
  householdId: string;
  fromMemberId: string;
  toMemberId: string;
  amount: number;
  idempotencyKey?: string;
}

export interface DonatePointsResponse {
  donorNewTotal: number;
  receptorNewTotal: number;
}

export const donatePoints = onCall<DonatePointsRequest, Promise<DonatePointsResponse>>(
  { region: REGION },
  async (request) => {
    const uid = requireAuth(request.auth?.uid);
    const { householdId, fromMemberId, toMemberId, amount, idempotencyKey } = request.data;
    if (!householdId || !fromMemberId || !toMemberId) {
      throw new HttpsError("invalid-argument", "householdId/fromMemberId/toMemberId son obligatorios");
    }
    if (fromMemberId === toMemberId) throw new HttpsError("invalid-argument", "self-donation");
    if (!Number.isInteger(amount) || amount < 1) throw new HttpsError("invalid-argument", "invalid-amount");

    return withIdempotency(idempotencyKey, "donatePoints", () =>
      db.runTransaction(async (tx) => {
        await loadActiveMember(tx, householdId, uid);
        const trusted = await isTrustedMember(tx, householdId, uid);

        // Un donante SIN rol de confianza solo puede mover su PROPIO saldo —
        // mismo límite que `firestore.rules` impone hoy sobre el PATCH del
        // documento del donante (`isTrusted(hid) || request.auth.uid == mid`).
        if (!trusted && uid !== fromMemberId) {
          throw new HttpsError("permission-denied", "not-trusted");
        }
        if (!trusted && amount > MAX_PEER_TRANSFER_AMOUNT) {
          throw new HttpsError("permission-denied", "amount-exceeds-limit");
        }

        const fromMember = await loadActiveMember(tx, householdId, fromMemberId);
        const toMember = await loadActiveMember(tx, householdId, toMemberId);

        if (amount > fromMember.totalPoints) {
          throw new HttpsError("failed-precondition", "insufficient-balance");
        }

        // ── Escritura (todo o nada) ──
        const donorNewTotal = clampTotalPoints(fromMember.totalPoints, -amount);
        const receptorNewTotal = clampTotalPoints(toMember.totalPoints, amount);
        tx.update(db.doc(`households/${householdId}/members/${fromMemberId}`), { totalPoints: donorNewTotal });
        tx.update(db.doc(`households/${householdId}/members/${toMemberId}`), { totalPoints: receptorNewTotal });

        return { donorNewTotal, receptorNewTotal };
      })
    );
  }
);
