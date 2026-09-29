/**
 * Test de integración contra el emulador de Firestore — ver
 * `jest.integration.config.mjs` / `npm run test:integration`. Cubre la red
 * de seguridad para `taskHistory` LEGACY con `pointsApplied == false` (ver
 * KDoc de `reconcileMissingTaskPoints`): repara aplicando el clamp de
 * `points.ts`, ignora los registros ya reconciliados por otra pasada, y no
 * revienta si el household/member referenciado ya no existe.
 */
import { db } from "./admin.js";
import { reconcileMissingTaskPoints } from "./reconcileMissingTaskPoints.js";
import { MAX_TOTAL_POINTS } from "./points.js";
import { clearFirestoreEmulator } from "./testUtils/emulatorHelpers.js";

const HID = "h1";

beforeEach(async () => {
  await clearFirestoreEmulator();
});

describe("reconcileMissingTaskPoints — caso feliz", () => {
  test("aplica los puntos pendientes y marca pointsApplied=true", async () => {
    await db.doc(`households/${HID}/members/m1`).set({ role: "child", totalPoints: 10, leftAt: 0 });
    await db.doc(`households/${HID}/taskHistory/legacy1`).set({
      taskId: "t1",
      memberId: "m1",
      points: 15,
      completedAt: 1_000,
      onTime: true,
      pointsApplied: false
    });

    const reconciled = await reconcileMissingTaskPoints();

    expect(reconciled).toBe(1);
    const member = (await db.doc(`households/${HID}/members/m1`).get()).data();
    expect(member?.totalPoints).toBe(25);
    const history = (await db.doc(`households/${HID}/taskHistory/legacy1`).get()).data();
    expect(history?.pointsApplied).toBe(true);
  });

  test("acota al tope MAX_TOTAL_POINTS igual que las 4 funciones transaccionales", async () => {
    await db.doc(`households/${HID}/members/m1`).set({ role: "child", totalPoints: MAX_TOTAL_POINTS - 5, leftAt: 0 });
    await db.doc(`households/${HID}/taskHistory/legacy1`).set({
      taskId: "t1",
      memberId: "m1",
      points: 50,
      completedAt: 1_000,
      onTime: true,
      pointsApplied: false
    });

    await reconcileMissingTaskPoints();

    const member = (await db.doc(`households/${HID}/members/m1`).get()).data();
    expect(member?.totalPoints).toBe(MAX_TOTAL_POINTS);
  });
});

describe("reconcileMissingTaskPoints — idempotencia", () => {
  test("un registro ya reconciliado (pointsApplied=true) no se reprocesa en la siguiente pasada", async () => {
    await db.doc(`households/${HID}/members/m1`).set({ role: "child", totalPoints: 10, leftAt: 0 });
    await db.doc(`households/${HID}/taskHistory/legacy1`).set({
      taskId: "t1",
      memberId: "m1",
      points: 15,
      completedAt: 1_000,
      onTime: true,
      pointsApplied: false
    });

    const first = await reconcileMissingTaskPoints();
    expect(first).toBe(1);

    const second = await reconcileMissingTaskPoints();
    expect(second).toBe(0);

    // La segunda pasada no debe volver a sumar los mismos puntos.
    const member = (await db.doc(`households/${HID}/members/m1`).get()).data();
    expect(member?.totalPoints).toBe(25);
  });
});

describe("reconcileMissingTaskPoints — casos borde", () => {
  test("member borrado: no revienta, deja el registro reparado igualmente (pointsApplied=true sin tocar puntos)", async () => {
    // Sin doc en `members/m1` — simula un miembro eliminado del hogar entre
    // la compleción legacy y esta pasada de reconciliación.
    await db.doc(`households/${HID}/taskHistory/legacy1`).set({
      taskId: "t1",
      memberId: "m1",
      points: 15,
      completedAt: 1_000,
      onTime: true,
      pointsApplied: false
    });

    const reconciled = await reconcileMissingTaskPoints();

    expect(reconciled).toBe(1);
    const history = (await db.doc(`households/${HID}/taskHistory/legacy1`).get()).data();
    expect(history?.pointsApplied).toBe(true);
  });

  test("sin registros pendientes, no hace nada", async () => {
    await db.doc(`households/${HID}/members/m1`).set({ role: "child", totalPoints: 10, leftAt: 0 });
    await db.doc(`households/${HID}/taskHistory/ok`).set({
      taskId: "t1",
      memberId: "m1",
      points: 15,
      completedAt: 1_000,
      onTime: true,
      pointsApplied: true
    });

    const reconciled = await reconcileMissingTaskPoints();

    expect(reconciled).toBe(0);
  });
});
