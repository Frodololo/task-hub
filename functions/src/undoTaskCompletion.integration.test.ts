/**
 * Test de integración contra el emulador de Firestore — ver
 * `jest.integration.config.mjs` / `npm run test:integration`. `undoTaskCompletion`
 * es idempotente POR DISEÑO (ver KDoc de `undoTaskCompletion.ts`): repetir la
 * invocación no debe ser un error, solo un no-op (`reverted: false`).
 */
import { db } from "./admin.js";
import { completeRecurringTask } from "./completeRecurringTask.js";
import { undoTaskCompletion } from "./undoTaskCompletion.js";
import { callAs, callUnauthenticated, clearFirestoreEmulator } from "./testUtils/emulatorHelpers.js";
import { TaskDoc, MemberDoc } from "./types.js";

const HID = "h1";
const OWNER = "owner-1";
const CHILD = "child-1";
const TID = "task-1";

async function seedHousehold(): Promise<void> {
  await db.doc(`households/${HID}`).set({ ownerId: OWNER, timezone: "Europe/Madrid" });
  await db
    .doc(`households/${HID}/members/${OWNER}`)
    .set({ role: "admin", totalPoints: 0, leftAt: 0 } satisfies MemberDoc);
  await db.doc(`households/${HID}/members/${CHILD}`).set({ role: "child", totalPoints: 0, leftAt: 0 } satisfies MemberDoc);
}

async function seedTask(): Promise<void> {
  const task: TaskDoc = {
    points: 20,
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

describe("undoTaskCompletion — caso feliz", () => {
  test("revierte puntos, borra el historial y limpia la tarea", async () => {
    const completion = await completeRecurringTask.run(
      callAs(CHILD, { householdId: HID, taskId: TID, memberId: CHILD })
    );

    const result = await undoTaskCompletion.run(
      callAs(CHILD, { householdId: HID, taskId: TID, completedAt: completion.completedAt })
    );
    expect(result.reverted).toBe(true);

    const memberSnap = await db.doc(`households/${HID}/members/${CHILD}`).get();
    expect((memberSnap.data() as MemberDoc).totalPoints).toBe(0);

    const taskSnap = await db.doc(`households/${HID}/tasks/${TID}`).get();
    const task = taskSnap.data() as TaskDoc;
    expect(task.lastCompletedDate).toBeNull();
    expect(task.completedBy).toBeNull();

    const historySnap = await db.collection(`households/${HID}/taskHistory`).get();
    expect(historySnap.size).toBe(0);
  });
});

describe("undoTaskCompletion — doble invocación (idempotente por diseño)", () => {
  test("segunda llamada con el mismo completedAt es un no-op, no un error", async () => {
    const completion = await completeRecurringTask.run(
      callAs(CHILD, { householdId: HID, taskId: TID, memberId: CHILD })
    );
    const request = callAs(CHILD, { householdId: HID, taskId: TID, completedAt: completion.completedAt });

    const first = await undoTaskCompletion.run(request);
    expect(first.reverted).toBe(true);

    const second = await undoTaskCompletion.run(request);
    expect(second.reverted).toBe(false);

    // No se resta dos veces: los puntos ya estaban en 0 tras el primer undo.
    const memberSnap = await db.doc(`households/${HID}/members/${CHILD}`).get();
    expect((memberSnap.data() as MemberDoc).totalPoints).toBe(0);
  });
});

describe("undoTaskCompletion — condiciones de error", () => {
  test("sin autenticación -> unauthenticated", async () => {
    await expect(
      undoTaskCompletion.run(callUnauthenticated({ householdId: HID, taskId: TID, completedAt: Date.now() }))
    ).rejects.toMatchObject({ code: "unauthenticated" });
  });

  test("miembro sin permiso intenta deshacer la compleción de otro -> permission-denied", async () => {
    const outsider = "outsider-1";
    await db
      .doc(`households/${HID}/members/${outsider}`)
      .set({ role: "child", totalPoints: 0, leftAt: 0 } satisfies MemberDoc);

    const completion = await completeRecurringTask.run(
      callAs(CHILD, { householdId: HID, taskId: TID, memberId: CHILD })
    );

    await expect(
      undoTaskCompletion.run(
        callAs(outsider, { householdId: HID, taskId: TID, completedAt: completion.completedAt })
      )
    ).rejects.toMatchObject({ code: "permission-denied" });
  });

  test("tarea inexistente -> not-found", async () => {
    await expect(
      undoTaskCompletion.run(callAs(OWNER, { householdId: HID, taskId: "no-existe", completedAt: Date.now() }))
    ).rejects.toMatchObject({ code: "not-found" });
  });
});
