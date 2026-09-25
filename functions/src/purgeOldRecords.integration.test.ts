/**
 * Test de integración contra el emulador de Firestore — ver
 * `jest.integration.config.mjs` / `npm run test:integration`. Cubre la
 * purga TTL de `taskHistory`/`messages` (>90 días) e `idempotency` (>24h,
 * ver `idempotency.ts`) y su idempotencia: correr la pasada dos veces no
 * debe fallar ni volver a borrar nada.
 */
import { db } from "./admin.js";
import { purgeOldRecords } from "./purgeOldRecords.js";
import { clearFirestoreEmulator } from "./testUtils/emulatorHelpers.js";

const HID = "h1";
const NOW = Date.parse("2026-09-25T12:00:00Z");
const DAY_MS = 24 * 60 * 60 * 1000;
const OLD_COMPLETED_AT = NOW - 91 * DAY_MS;
const RECENT_COMPLETED_AT = NOW - 30 * DAY_MS;

beforeEach(async () => {
  await clearFirestoreEmulator();
  await db.doc(`households/${HID}/taskHistory/old`).set({
    taskId: "t1",
    memberId: "m1",
    points: 10,
    completedAt: OLD_COMPLETED_AT,
    onTime: true,
    pointsApplied: true
  });
  await db.doc(`households/${HID}/taskHistory/recent`).set({
    taskId: "t1",
    memberId: "m1",
    points: 10,
    completedAt: RECENT_COMPLETED_AT,
    onTime: true,
    pointsApplied: true
  });
  await db.doc(`households/${HID}/messages/old`).set({
    memberId: "m1",
    authorName: "M1",
    text: "hola",
    createdAt: OLD_COMPLETED_AT
  });
  await db.doc(`households/${HID}/messages/recent`).set({
    memberId: "m1",
    authorName: "M1",
    text: "hola reciente",
    createdAt: RECENT_COMPLETED_AT
  });
  await db.doc("idempotency/expired-key").set({
    status: "completed",
    functionName: "redeemReward",
    createdAt: NOW - 25 * 60 * 60 * 1000,
    expiresAt: NOW - 60 * 60 * 1000,
    result: { ok: true }
  });
  await db.doc("idempotency/fresh-key").set({
    status: "in_progress",
    functionName: "donatePoints",
    createdAt: NOW,
    expiresAt: NOW + 23 * 60 * 60 * 1000
  });
});

describe("purgeOldRecords — caso feliz", () => {
  test("borra solo los registros con más de 90 días y las idempotency keys caducadas", async () => {
    const result = await purgeOldRecords(NOW);
    expect(result).toEqual({ taskHistoryDeleted: 1, messagesDeleted: 1, idempotencyDeleted: 1 });

    const historySnap = await db.collection(`households/${HID}/taskHistory`).get();
    expect(historySnap.docs.map((d) => d.id).sort()).toEqual(["recent"]);

    const messagesSnap = await db.collection(`households/${HID}/messages`).get();
    expect(messagesSnap.docs.map((d) => d.id).sort()).toEqual(["recent"]);

    const idempotencySnap = await db.collection("idempotency").get();
    expect(idempotencySnap.docs.map((d) => d.id).sort()).toEqual(["fresh-key"]);
  });
});

describe("purgeOldRecords — idempotencia", () => {
  test("repetir la pasada no falla ni vuelve a borrar nada", async () => {
    const first = await purgeOldRecords(NOW);
    expect(first).toEqual({ taskHistoryDeleted: 1, messagesDeleted: 1, idempotencyDeleted: 1 });

    const second = await purgeOldRecords(NOW);
    expect(second).toEqual({ taskHistoryDeleted: 0, messagesDeleted: 0, idempotencyDeleted: 0 });

    const historySnap = await db.collection(`households/${HID}/taskHistory`).get();
    expect(historySnap.size).toBe(1);
  });
});
