/**
 * Test de integración contra el emulador de Firestore — ver
 * `jest.integration.config.mjs` / `npm run test:integration`.
 */
import { db } from "./admin.js";
import { completeRecurringTask } from "./completeRecurringTask.js";
import { reassignTaskCompletion } from "./reassignTaskCompletion.js";
import { callAs, clearFirestoreEmulator } from "./testUtils/emulatorHelpers.js";
import { TaskDoc, MemberDoc } from "./types.js";

const HID = "h1";
const OWNER = "owner-1";
const CHILD = "child-1";
const CHILD2 = "child-2";
const TID = "task-1";

async function seedHousehold(): Promise<void> {
  await db.doc(`households/${HID}`).set({ ownerId: OWNER, timezone: "Europe/Madrid" });
  await db
    .doc(`households/${HID}/members/${OWNER}`)
    .set({ role: "admin", totalPoints: 0, leftAt: 0 } satisfies MemberDoc);
  await db.doc(`households/${HID}/members/${CHILD}`).set({ role: "child", totalPoints: 0, leftAt: 0 } satisfies MemberDoc);
  await db.doc(`households/${HID}/members/${CHILD2}`).set({ role: "child", totalPoints: 0, leftAt: 0 } satisfies MemberDoc);
}

async function seedTask(): Promise<void> {
  const task: TaskDoc = {
    points: 25,
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
    createdAt: Date.now()
  };
  await db.doc(`households/${HID}/tasks/${TID}`).set(task);
}

beforeEach(async () => {
  await clearFirestoreEmulator();
  await seedHousehold();
  await seedTask();
});

describe("reassignTaskCompletion — caso feliz", () => {
  test("transfiere los puntos de completedBy al nuevo miembro", async () => {
    await completeRecurringTask.run(callAs(CHILD, { householdId: HID, taskId: TID, memberId: CHILD }));

    const result = await reassignTaskCompletion.run(
      callAs(OWNER, { householdId: HID, taskId: TID, newMemberId: CHILD2 })
    );
    expect(result.previousMemberId).toBe(CHILD);
    expect(result.pointsTransferred).toBe(25);

    const oldMemberSnap = await db.doc(`households/${HID}/members/${CHILD}`).get();
    expect((oldMemberSnap.data() as MemberDoc).totalPoints).toBe(0);
    const newMemberSnap = await db.doc(`households/${HID}/members/${CHILD2}`).get();
    expect((newMemberSnap.data() as MemberDoc).totalPoints).toBe(25);

    const taskSnap = await db.doc(`households/${HID}/tasks/${TID}`).get();
    expect((taskSnap.data() as TaskDoc).completedBy).toBe(CHILD2);
  });
});

describe("reassignTaskCompletion — doble invocación (safe replay)", () => {
  test("reasignar dos veces al mismo miembro no transfiere puntos de más", async () => {
    await completeRecurringTask.run(callAs(CHILD, { householdId: HID, taskId: TID, memberId: CHILD }));
    const request = callAs(OWNER, { householdId: HID, taskId: TID, newMemberId: CHILD2 });

    const first = await reassignTaskCompletion.run(request);
    expect(first.pointsTransferred).toBe(25);

    const second = await reassignTaskCompletion.run(request);
    expect(second.pointsTransferred).toBe(0); // ya era el completedBy actual, no-op

    const newMemberSnap = await db.doc(`households/${HID}/members/${CHILD2}`).get();
    expect((newMemberSnap.data() as MemberDoc).totalPoints).toBe(25);
  });
});

describe("reassignTaskCompletion — condiciones de error", () => {
  test("llamador sin rol de confianza -> permission-denied", async () => {
    await completeRecurringTask.run(callAs(CHILD, { householdId: HID, taskId: TID, memberId: CHILD }));

    await expect(
      reassignTaskCompletion.run(callAs(CHILD, { householdId: HID, taskId: TID, newMemberId: CHILD2 }))
    ).rejects.toMatchObject({ code: "permission-denied" });
  });

  test("newMemberId inexistente -> not-found", async () => {
    await expect(
      reassignTaskCompletion.run(callAs(OWNER, { householdId: HID, taskId: TID, newMemberId: "fantasma" }))
    ).rejects.toMatchObject({ code: "not-found" });
  });
});
