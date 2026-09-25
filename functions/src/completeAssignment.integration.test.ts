/**
 * Test de integración contra el emulador de Firestore — ver
 * `jest.integration.config.mjs` / `npm run test:integration`.
 */
import { db } from "./admin.js";
import { completeAssignment } from "./completeAssignment.js";
import { callAs, clearFirestoreEmulator } from "./testUtils/emulatorHelpers.js";
import { TaskDoc, TaskAssignmentDoc, MemberDoc } from "./types.js";

const HID = "h1";
const UID = "member-1";
const TID = "task-1";
const AID = "assignment-1";

async function seedHousehold(): Promise<void> {
  await db.doc(`households/${HID}`).set({ ownerId: UID, timezone: "Europe/Madrid" });
  await db.doc(`households/${HID}/members/${UID}`).set({ role: "admin", totalPoints: 0, leftAt: 0 } satisfies MemberDoc);
}

async function seedTaskAndAssignment(): Promise<void> {
  const task: TaskDoc = {
    points: 15,
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

  const assignment: TaskAssignmentDoc = {
    taskId: TID,
    memberId: UID,
    mandatory: false,
    dueDate: Date.now() + 24 * 60 * 60 * 1000,
    status: "assigned",
    completedAt: null,
    pointsAwarded: null,
    onTime: null,
    assignedAt: Date.now()
  };
  await db.doc(`households/${HID}/tasks/${TID}/assignments/${AID}`).set(assignment);
}

beforeEach(async () => {
  await clearFirestoreEmulator();
  await seedHousehold();
  await seedTaskAndAssignment();
});

describe("completeAssignment — caso feliz", () => {
  test("completa la asignación, otorga puntos y crea taskHistory", async () => {
    const result = await completeAssignment.run(callAs(UID, { householdId: HID, taskId: TID, assignmentId: AID }));

    expect(result.pointsAwarded).toBe(15);
    expect(result.assignmentId).toBe(AID);

    const assignmentSnap = await db.doc(`households/${HID}/tasks/${TID}/assignments/${AID}`).get();
    expect((assignmentSnap.data() as TaskAssignmentDoc).status).toBe("completed");

    const memberSnap = await db.doc(`households/${HID}/members/${UID}`).get();
    expect((memberSnap.data() as MemberDoc).totalPoints).toBe(15);

    const historySnap = await db.collection(`households/${HID}/taskHistory`).get();
    expect(historySnap.size).toBe(1);
  });
});

describe("completeAssignment — doble invocación (safe replay)", () => {
  test("segunda llamada con el mismo assignmentId aborta sin duplicar puntos", async () => {
    const request = callAs(UID, { householdId: HID, taskId: TID, assignmentId: AID });
    await completeAssignment.run(request);

    await expect(completeAssignment.run(request)).rejects.toMatchObject({ code: "aborted" });

    const memberSnap = await db.doc(`households/${HID}/members/${UID}`).get();
    expect((memberSnap.data() as MemberDoc).totalPoints).toBe(15);

    const historySnap = await db.collection(`households/${HID}/taskHistory`).get();
    expect(historySnap.size).toBe(1);
  });
});

describe("completeAssignment — condiciones de error", () => {
  test("asignación inexistente -> not-found", async () => {
    await expect(
      completeAssignment.run(callAs(UID, { householdId: HID, taskId: TID, assignmentId: "no-existe" }))
    ).rejects.toMatchObject({ code: "not-found" });
  });

  test("assignment.taskId no coincide con taskId -> invalid-argument", async () => {
    const otherTaskId = "otra-tarea";
    await db.doc(`households/${HID}/tasks/${otherTaskId}`).set({
      points: 5,
      frequency: "once",
      recurrenceDays: [],
      recurrenceDay: null,
      penaltyMode: null,
      penaltyValue: 0,
      penaltyInterval: "day",
      penaltyMax: 0,
      dueDate: Date.now(),
      lastCompletedDate: null,
      completedBy: null,
      assignmentRotation: [],
      nextDueAt: null,
      createdAt: Date.now()
    } satisfies TaskDoc);
    // Doc en la ruta anidada de `otherTaskId`, pero con el campo interno
    // `taskId` apuntando a TID (datos corruptos/legacy) — así se ejercita el
    // guard `assignment.taskId !== taskId` en vez del `not-found` por ruta.
    await db.doc(`households/${HID}/tasks/${otherTaskId}/assignments/${AID}`).set({
      taskId: TID,
      memberId: UID,
      mandatory: false,
      dueDate: Date.now(),
      status: "assigned",
      completedAt: null,
      pointsAwarded: null,
      onTime: null,
      assignedAt: Date.now()
    } satisfies TaskAssignmentDoc);

    await expect(
      completeAssignment.run(callAs(UID, { householdId: HID, taskId: otherTaskId, assignmentId: AID }))
    ).rejects.toMatchObject({ code: "invalid-argument" });
  });
});
