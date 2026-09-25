/**
 * Test de integración contra el emulador de Firestore — ver
 * `jest.integration.config.mjs` / `npm run test:integration`.
 */
import { db } from "./admin.js";
import { redeemReward } from "./redeemReward.js";
import { callAs, callUnauthenticated, clearFirestoreEmulator } from "./testUtils/emulatorHelpers.js";
import { MemberDoc, RewardDoc } from "./types.js";

const HID = "h1";
const UID = "member-1";
const RID = "reward-1";

async function seedHousehold(totalPoints = 100): Promise<void> {
  await db.doc(`households/${HID}`).set({ ownerId: UID, timezone: "Europe/Madrid" });
  await db
    .doc(`households/${HID}/members/${UID}`)
    .set({ role: "admin", totalPoints, leftAt: 0 } satisfies MemberDoc);
}

async function seedReward(cost = 30): Promise<void> {
  await db.doc(`households/${HID}/rewards/${RID}`).set({ title: "Cine", cost } satisfies RewardDoc);
}

beforeEach(async () => {
  await clearFirestoreEmulator();
  await seedHousehold();
  await seedReward();
});

describe("redeemReward — caso feliz", () => {
  test("descuenta el coste real de la recompensa y registra el canje", async () => {
    const result = await redeemReward.run(callAs(UID, { householdId: HID, rewardId: RID, memberId: UID }));

    expect(result.pointsSpent).toBe(30);
    expect(result.memberNewTotal).toBe(70);

    const memberSnap = await db.doc(`households/${HID}/members/${UID}`).get();
    expect((memberSnap.data() as MemberDoc).totalPoints).toBe(70);

    const redemptionSnap = await db.doc(`households/${HID}/rewardRedemptions/${result.redemptionId}`).get();
    expect(redemptionSnap.data()).toMatchObject({ rewardId: RID, memberId: UID, pointsSpent: 30 });
  });

  test("ignora un pointsSpent falseado por el cliente — usa el cost real leído del servidor", async () => {
    // El request no acepta pointsSpent en absoluto (a diferencia del REST
    // directo de hoy): el coste SIEMPRE se lee de `rewards/{rewardId}`
    // dentro de la transacción, así que no hay campo que falsear.
    const result = await redeemReward.run(callAs(UID, { householdId: HID, rewardId: RID, memberId: UID }));
    expect(result.pointsSpent).toBe(30);
  });
});

describe("redeemReward — condiciones de error", () => {
  test("sin autenticación -> unauthenticated", async () => {
    await expect(
      redeemReward.run(callUnauthenticated({ householdId: HID, rewardId: RID, memberId: UID }))
    ).rejects.toMatchObject({ code: "unauthenticated" });
  });

  test("recompensa inexistente -> not-found", async () => {
    await expect(
      redeemReward.run(callAs(UID, { householdId: HID, rewardId: "no-existe", memberId: UID }))
    ).rejects.toMatchObject({ code: "not-found" });
  });

  test("saldo insuficiente -> failed-precondition, sin descontar ni registrar canje", async () => {
    await seedReward(500); // más que los 100 puntos sembrados

    await expect(
      redeemReward.run(callAs(UID, { householdId: HID, rewardId: RID, memberId: UID }))
    ).rejects.toMatchObject({ code: "failed-precondition" });

    const memberSnap = await db.doc(`households/${HID}/members/${UID}`).get();
    expect((memberSnap.data() as MemberDoc).totalPoints).toBe(100);

    const redemptionsSnap = await db.collection(`households/${HID}/rewardRedemptions`).get();
    expect(redemptionsSnap.size).toBe(0);
  });
});

describe("redeemReward — idempotencyKey (safe replay)", () => {
  test("reintentar con la misma key devuelve el mismo resultado sin descontar dos veces", async () => {
    const request = callAs(UID, { householdId: HID, rewardId: RID, memberId: UID, idempotencyKey: "key-1" });

    const first = await redeemReward.run(request);
    const second = await redeemReward.run(request);

    expect(second).toEqual(first);

    const memberSnap = await db.doc(`households/${HID}/members/${UID}`).get();
    expect((memberSnap.data() as MemberDoc).totalPoints).toBe(70);

    const redemptionsSnap = await db.collection(`households/${HID}/rewardRedemptions`).get();
    expect(redemptionsSnap.size).toBe(1);
  });
});
