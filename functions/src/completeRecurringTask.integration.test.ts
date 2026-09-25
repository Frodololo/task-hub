/**
 * Test de integración contra el emulador de Firestore — ver
 * `jest.integration.config.mjs` / `npm run test:integration`. Cubre el
 * caso feliz, doble invocación (concurrencia optimista + guarda "mismo día"
 * de panel v16 C1, ver `completeRecurringTask.ts`) y condiciones de error.
 */
import { db } from "./admin.js";
import { completeRecurringTask } from "./completeRecurringTask.js";
import { callAs, callUnauthenticated, clearFirestoreEmulator } from "./testUtils/emulatorHelpers.js";
import { TaskDoc, MemberDoc } from "./types.js";

const HID = "h1";
const UID = "member-1";
const TID = "task-1";

function seedTask(overrides: Partial<TaskDoc> = {}): Promise<FirebaseFirestore.WriteResult> {
  const task: TaskDoc = {
    points: 10,
    frequency: "once",
    recurrenceDays: [],
    recurrenceDay: null,
    penaltyMode: null,
    penaltyValue: 0,
    penaltyInterval: "day",
    penaltyMax: 0,
    dueDate: Date.now() + 24 * 60 * 60 * 1000,
    lastCompletedDate: null,
    completedBy: null,
    assignmentRotation: [],
    nextDueAt: null,
    createdAt: Date.now(),
    ...overrides
  };
  return db.doc(`households/${HID}/tasks/${TID}`).set(task);
}

async function seedHousehold(): Promise<void> {
  await db.doc(`households/${HID}`).set({ ownerId: UID, timezone: "Europe/Madrid" });
  await db.doc(`households/${HID}/members/${UID}`).set({ role: "admin", totalPoints: 0, leftAt: 0 } satisfies MemberDoc);
}

beforeEach(async () => {
  await clearFirestoreEmulator();
  await seedHousehold();
  await seedTask();
});

describe("completeRecurringTask — caso feliz", () => {
  test("otorga puntos, crea taskHistory y actualiza la tarea", async () => {
    const result = await completeRecurringTask.run(callAs(UID, { householdId: HID, taskId: TID, memberId: UID }));

    expect(result.pointsAwarded).toBe(10);
    expect(result.onTime).toBe(true);
    expect(result.nextDueAt).toBeNull();

    const memberSnap = await db.doc(`households/${HID}/members/${UID}`).get();
    expect((memberSnap.data() as MemberDoc).totalPoints).toBe(10);

    const taskSnap = await db.doc(`households/${HID}/tasks/${TID}`).get();
    const task = taskSnap.data() as TaskDoc;
    expect(task.completedBy).toBe(UID);
    expect(task.lastCompletedDate).toBe(result.completedAt);

    const historySnap = await db.collection(`households/${HID}/taskHistory`).get();
    expect(historySnap.size).toBe(1);
    expect(historySnap.docs[0].data().pointsApplied).toBe(true);
    expect(historySnap.docs[0].data().points).toBe(10);
  });
});

describe("completeRecurringTask — doble invocación (safe replay)", () => {
  test("segunda llamada con expectedLastCompletedDate desfasado aborta sin duplicar puntos", async () => {
    const request = callAs(UID, { householdId: HID, taskId: TID, memberId: UID });
    await completeRecurringTask.run(request);

    await expect(completeRecurringTask.run(request)).rejects.toMatchObject({ code: "aborted" });

    const memberSnap = await db.doc(`households/${HID}/members/${UID}`).get();
    expect((memberSnap.data() as MemberDoc).totalPoints).toBe(10); // no se duplicó

    const historySnap = await db.collection(`households/${HID}/taskHistory`).get();
    expect(historySnap.size).toBe(1);
  });

  test("segunda llamada con expectedLastCompletedDate fresco pero mismo día falla por guarda anti-farming", async () => {
    const first = await completeRecurringTask.run(callAs(UID, { householdId: HID, taskId: TID, memberId: UID }));

    await expect(
      completeRecurringTask.run(
        callAs(UID, {
          householdId: HID,
          taskId: TID,
          memberId: UID,
          expectedLastCompletedDate: first.completedAt
        })
      )
    ).rejects.toMatchObject({ code: "failed-precondition" });

    const memberSnap = await db.doc(`households/${HID}/members/${UID}`).get();
    expect((memberSnap.data() as MemberDoc).totalPoints).toBe(10);
  });
});

describe("completeRecurringTask — condiciones de error", () => {
  test("sin autenticación -> unauthenticated", async () => {
    await expect(
      completeRecurringTask.run(callUnauthenticated({ householdId: HID, taskId: TID, memberId: UID }))
    ).rejects.toMatchObject({ code: "unauthenticated" });
  });

  test("tarea inexistente -> not-found", async () => {
    await expect(
      completeRecurringTask.run(callAs(UID, { householdId: HID, taskId: "no-existe", memberId: UID }))
    ).rejects.toMatchObject({ code: "not-found" });
  });

  test("memberId inexistente -> not-found", async () => {
    await expect(
      completeRecurringTask.run(callAs(UID, { householdId: HID, taskId: TID, memberId: "fantasma" }))
    ).rejects.toMatchObject({ code: "not-found" });
  });
});
