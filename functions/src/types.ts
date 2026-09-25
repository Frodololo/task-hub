/**
 * Formas mínimas de los documentos de Firestore que necesitan las funciones
 * y las reglas puras. Reflejan `composeApp/.../network/models/DTOs.kt`
 * (`TaskResponse`, `AssignmentSlot`, `TaskAssignmentResponse`,
 * `TaskHistoryResponse`, `MemberResponse`) — no son DTOs de red (no hay
 * protocolo REST aquí, el Admin SDK entrega objetos ya deserializados), solo
 * los campos que esta carpeta lee o escribe.
 */

/** Elemento de `TaskResponse.assignmentRotation` — 1=Lunes..7=Domingo. */
export interface AssignmentSlot {
  dayOfWeek: number;
  memberId: string;
}

/** Subconjunto de `households/{hid}/tasks/{id}` usado por las reglas y funciones. */
export interface TaskDoc {
  points: number;
  frequency: string;
  recurrenceDays: number[];
  recurrenceDay: number | null;
  penaltyMode: string | null;
  penaltyValue: number;
  penaltyInterval: string;
  penaltyMax: number;
  dueDate: number;
  lastCompletedDate: number | null;
  completedBy: string | null;
  assignmentRotation: AssignmentSlot[];
  nextDueAt: number | null;
  createdAt: number;
}

/** Subconjunto de `households/{hid}/tasks/{tid}/assignments/{id}`. */
export interface TaskAssignmentDoc {
  taskId: string;
  memberId: string;
  mandatory: boolean;
  dueDate: number;
  status: string; // "assigned" | "completed"
  completedAt: number | null;
  pointsAwarded: number | null;
  onTime: boolean | null;
  assignedAt: number;
}

/** Subconjunto de `households/{hid}/taskHistory/{id}`. */
export interface TaskHistoryDoc {
  taskId: string;
  memberId: string;
  points: number;
  completedAt: number;
  onTime: boolean;
  pointsApplied: boolean;
}

/** Subconjunto de `households/{hid}/members/{id}`. */
export interface MemberDoc {
  role: string; // "admin" | "child"
  totalPoints: number;
  leftAt: number;
}

/** Subconjunto de `households/{hid}/rewards/{id}`. */
export interface RewardDoc {
  title: string;
  cost: number;
}

/** Subconjunto de `households/{hid}`. */
export interface HouseholdDoc {
  ownerId: string;
  /** IANA (ej. "Europe/Madrid"). Opcional: hogares creados antes de D1 no lo tienen — fallback a `DEFAULT_TZ`. */
  timezone?: string | null;
}

/** Decisión de a quién asignar la siguiente ocurrencia, y si hace falta crearla. */
export interface NextAssignmentDecision {
  shouldCreate: boolean;
  memberId: string;
}

/** Resultado de resolver puntos otorgados + puntualidad al completar una tarea. */
export interface CompletionOutcome {
  onTime: boolean;
  pointsAwarded: number;
}
