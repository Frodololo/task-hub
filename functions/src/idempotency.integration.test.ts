/**
 * Test de integración contra el emulador de Firestore — ver
 * `jest.integration.config.mjs` / `npm run test:integration`. Cubre
 * `withIdempotency` (`idempotency.ts`) end-to-end a través de `redeemReward`
 * (cualquiera de las 4 CF envueltas serviría igual, ver su KDoc): reserva
 * nueva, replay seguro de una key `completed`, conflicto sobre una key
 * `in_progress`, y limpieza de la reserva cuando la lógica de negocio falla
 * (para permitir reintentar sin esperar el TTL de 24h).
 */
import { db } from "./admin.js";
import { redeemReward } from "./redeemReward.js";
import { callAs, clearFirestoreEmulator } from "./testUtils/emulatorHelpers.js";
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

describe("withIdempotency — reserva nueva y replay", () => {
  test("crea el doc idempotency en estado completed tras el éxito", async () => {
    const result = await redeemReward.run(
      callAs(UID, { householdId: HID, rewardId: RID, memberId: UID, idempotencyKey: "k1" })
    );

    const idemSnap = await db.doc("idempotency/k1").get();
    expect(idemSnap.exists).toBe(true);
    const data = idemSnap.data();
    expect(data?.status).toBe("completed");
    expect(data?.functionName).toBe("redeemReward");
    expect(data?.result).toMatchObject(result);
  });

  test("una key completed devuelve el resultado guardado SIN ejecutar la lógica de negocio de nuevo", async () => {
    const request = callAs(UID, { householdId: HID, rewardId: RID, memberId: UID, idempotencyKey: "k2" });
    const first = await redeemReward.run(request);

    // Segunda llamada, saldo YA descontado: si volviera a ejecutar la
    // transacción, el saldo (70) seguiría cubriendo el coste (30) y
    // descontaría una segunda vez — la aserción de abajo distingue eso de un
    // replay real (memberNewTotal SIN cambiar respecto al primero).
    const second = await redeemReward.run(request);
    expect(second).toEqual(first);

    const memberSnap = await db.doc(`households/${HID}/members/${UID}`).get();
    expect((memberSnap.data() as MemberDoc).totalPoints).toBe(70);
  });
});

describe("withIdempotency — conflicto con una key in_progress", () => {
  test("una key ya reservada pero no completada -> aborted (409)", async () => {
    await db.doc("idempotency/k3").set({
      status: "in_progress",
      functionName: "redeemReward",
      createdAt: Date.now(),
      expiresAt: Date.now() + 24 * 60 * 60 * 1000
    });

    await expect(
      redeemReward.run(callAs(UID, { householdId: HID, rewardId: RID, memberId: UID, idempotencyKey: "k3" }))
    ).rejects.toMatchObject({ code: "aborted" });

    // No se tocó el saldo: el conflicto se detecta ANTES de abrir la
    // transacción de negocio.
    const memberSnap = await db.doc(`households/${HID}/members/${UID}`).get();
    expect((memberSnap.data() as MemberDoc).totalPoints).toBe(100);
  });
});

describe("withIdempotency — limpieza tras fallo", () => {
  test("si la lógica de negocio falla, borra la reserva para permitir reintentar de inmediato", async () => {
    await seedReward(500); // supera el saldo sembrado (100) -> failed-precondition

    await expect(
      redeemReward.run(callAs(UID, { householdId: HID, rewardId: RID, memberId: UID, idempotencyKey: "k4" }))
    ).rejects.toMatchObject({ code: "failed-precondition" });

    const idemSnap = await db.doc("idempotency/k4").get();
    expect(idemSnap.exists).toBe(false);

    // Reintentar con la MISMA key tras corregir el saldo debe funcionar
    // (no debe quedar atascado en "in_progress").
    await seedHousehold(1000);
    const retry = await redeemReward.run(
      callAs(UID, { householdId: HID, rewardId: RID, memberId: UID, idempotencyKey: "k4" })
    );
    expect(retry.pointsSpent).toBe(500);
  });
});
