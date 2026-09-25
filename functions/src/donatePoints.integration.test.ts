/**
 * Test de integración contra el emulador de Firestore — ver
 * `jest.integration.config.mjs` / `npm run test:integration`.
 */
import { db } from "./admin.js";
import { donatePoints } from "./donatePoints.js";
import { callAs, callUnauthenticated, clearFirestoreEmulator } from "./testUtils/emulatorHelpers.js";
import { MemberDoc } from "./types.js";
import { MAX_PEER_TRANSFER_AMOUNT } from "./points.js";

const HID = "h1";
const OWNER = "owner-1";
const CHILD = "child-1";
const CHILD2 = "child-2";

async function seedHousehold(): Promise<void> {
  await db.doc(`households/${HID}`).set({ ownerId: OWNER, timezone: "Europe/Madrid" });
  await db
    .doc(`households/${HID}/members/${OWNER}`)
    .set({ role: "admin", totalPoints: 0, leftAt: 0 } satisfies MemberDoc);
  await db
    .doc(`households/${HID}/members/${CHILD}`)
    .set({ role: "child", totalPoints: 200, leftAt: 0 } satisfies MemberDoc);
  await db
    .doc(`households/${HID}/members/${CHILD2}`)
    .set({ role: "child", totalPoints: 0, leftAt: 0 } satisfies MemberDoc);
}

beforeEach(async () => {
  await clearFirestoreEmulator();
  await seedHousehold();
});

describe("donatePoints — caso feliz", () => {
  test("transfiere el saldo del donante al receptor", async () => {
    const result = await donatePoints.run(
      callAs(CHILD, { householdId: HID, fromMemberId: CHILD, toMemberId: CHILD2, amount: 50 })
    );

    expect(result).toEqual({ donorNewTotal: 150, receptorNewTotal: 50 });

    const fromSnap = await db.doc(`households/${HID}/members/${CHILD}`).get();
    expect((fromSnap.data() as MemberDoc).totalPoints).toBe(150);
    const toSnap = await db.doc(`households/${HID}/members/${CHILD2}`).get();
    expect((toSnap.data() as MemberDoc).totalPoints).toBe(50);
  });

  test("un owner puede donar en nombre de otro miembro sin tope de 1000", async () => {
    await db.doc(`households/${HID}/members/${CHILD}`).set({ role: "child", totalPoints: 5000, leftAt: 0 } satisfies MemberDoc);

    const result = await donatePoints.run(
      callAs(OWNER, { householdId: HID, fromMemberId: CHILD, toMemberId: CHILD2, amount: 2000 })
    );

    expect(result).toEqual({ donorNewTotal: 3000, receptorNewTotal: 2000 });
  });
});

describe("donatePoints — condiciones de error", () => {
  test("sin autenticación -> unauthenticated", async () => {
    await expect(
      donatePoints.run(callUnauthenticated({ householdId: HID, fromMemberId: CHILD, toMemberId: CHILD2, amount: 10 }))
    ).rejects.toMatchObject({ code: "unauthenticated" });
  });

  test("donarse a sí mismo -> invalid-argument", async () => {
    await expect(
      donatePoints.run(callAs(CHILD, { householdId: HID, fromMemberId: CHILD, toMemberId: CHILD, amount: 10 }))
    ).rejects.toMatchObject({ code: "invalid-argument" });
  });

  test("importe no positivo -> invalid-argument", async () => {
    await expect(
      donatePoints.run(callAs(CHILD, { householdId: HID, fromMemberId: CHILD, toMemberId: CHILD2, amount: 0 }))
    ).rejects.toMatchObject({ code: "invalid-argument" });
  });

  test("saldo insuficiente -> failed-precondition, sin mover puntos", async () => {
    await expect(
      donatePoints.run(callAs(CHILD, { householdId: HID, fromMemberId: CHILD, toMemberId: CHILD2, amount: 999 }))
    ).rejects.toMatchObject({ code: "failed-precondition" });

    const fromSnap = await db.doc(`households/${HID}/members/${CHILD}`).get();
    expect((fromSnap.data() as MemberDoc).totalPoints).toBe(200);
  });

  test("un donante sin rol de confianza no puede donar el saldo de OTRO miembro -> permission-denied", async () => {
    await expect(
      donatePoints.run(callAs(CHILD2, { householdId: HID, fromMemberId: CHILD, toMemberId: CHILD2, amount: 10 }))
    ).rejects.toMatchObject({ code: "permission-denied" });
  });

  test("un donante sin rol de confianza no puede superar MAX_PEER_TRANSFER_AMOUNT -> permission-denied", async () => {
    await db
      .doc(`households/${HID}/members/${CHILD}`)
      .set({ role: "child", totalPoints: 5000, leftAt: 0 } satisfies MemberDoc);

    await expect(
      donatePoints.run(
        callAs(CHILD, {
          householdId: HID,
          fromMemberId: CHILD,
          toMemberId: CHILD2,
          amount: MAX_PEER_TRANSFER_AMOUNT + 1
        })
      )
    ).rejects.toMatchObject({ code: "permission-denied" });

    const fromSnap = await db.doc(`households/${HID}/members/${CHILD}`).get();
    expect((fromSnap.data() as MemberDoc).totalPoints).toBe(5000);
  });
});

describe("donatePoints — idempotencyKey (safe replay)", () => {
  test("reintentar con la misma key devuelve el mismo resultado sin duplicar la transferencia", async () => {
    const request = callAs(CHILD, {
      householdId: HID,
      fromMemberId: CHILD,
      toMemberId: CHILD2,
      amount: 50,
      idempotencyKey: "donate-key-1"
    });

    const first = await donatePoints.run(request);
    const second = await donatePoints.run(request);

    expect(second).toEqual(first);

    const fromSnap = await db.doc(`households/${HID}/members/${CHILD}`).get();
    expect((fromSnap.data() as MemberDoc).totalPoints).toBe(150);
    const toSnap = await db.doc(`households/${HID}/members/${CHILD2}`).get();
    expect((toSnap.data() as MemberDoc).totalPoints).toBe(50);
  });
});
