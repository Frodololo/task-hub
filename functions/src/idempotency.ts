/**
 * Idempotency key para las Cloud Functions callable transaccionales
 * (`completeRecurringTask`, `completeAssignment`, `redeemReward`,
 * `donatePoints`) — ver kanban "CF Idempotency". Un reintento del cliente
 * (timeout, error de red) tras una llamada que SÍ llegó a completarse en el
 * servidor no debe ejecutar la operación una segunda vez.
 *
 * El cliente genera un UUID v4 (`idempotencyKey`) y lo manda como parte del
 * payload; esta capa vive ENTERAMENTE en `functions/` — el cliente Kotlin
 * solo añade el campo, no implementa ninguna lógica de reserva/replay.
 *
 * Diseño en dos fases (no una única `runTransaction`, ver informe de la
 * tarea): una transacción de "reserva" corta decide si la llamada es nueva,
 * un replay seguro de una ya completada, o un conflicto con una todavía en
 * curso; solo si es nueva se ejecuta `fn` (que abre su PROPIA transacción de
 * negocio) y se marca `completed` con el resultado al terminar. Intentar
 * meter la reserva y la lógica de negocio en una sola transacción violaría
 * la regla de Firestore "todas las lecturas antes de cualquier escritura"
 * (la reserva escribe `idempotency/{key}` antes de que la lógica de negocio
 * haya podido leer sus propios documentos).
 */
import { HttpsError } from "firebase-functions/v2/https";
import { db } from "./admin.js";

const COLLECTION = "idempotency";
/** TTL: ver purga async en `purgeOldRecords.ts` (mismo patrón que `taskHistory`/`messages`, sin política nativa de Firestore TTL). */
export const IDEMPOTENCY_TTL_MS = 24 * 60 * 60 * 1000;

interface IdempotencyDoc {
  status: "in_progress" | "completed";
  functionName: string;
  createdAt: number;
  expiresAt: number;
  result?: unknown;
}

/**
 * Ejecuta `fn` (la transacción de negocio) protegida por `idempotencyKey`:
 * - Sin key: ejecuta `fn` tal cual, sin ningún registro (comportamiento
 *   actual, para callers que no la manden).
 * - Key nueva: reserva `idempotency/{key}` en estado `in_progress`, ejecuta
 *   `fn`, y la marca `completed` con el resultado. Si `fn` lanza, borra la
 *   reserva (permite reintentar de inmediato en vez de esperar el TTL).
 * - Key ya `completed`: devuelve el resultado guardado tal cual, SIN volver
 *   a ejecutar `fn` (safe replay).
 * - Key `in_progress` (otra invocación concurrente con la misma key todavía
 *   no ha terminado): `HttpsError('aborted', ...)` — el SDK de Functions lo
 *   traduce a 409 Conflict.
 */
export async function withIdempotency<T>(
  idempotencyKey: string | null | undefined,
  functionName: string,
  fn: () => Promise<T>
): Promise<T> {
  if (!idempotencyKey) return fn();

  const ref = db.doc(`${COLLECTION}/${idempotencyKey}`);
  const now = Date.now();

  const reservation = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (snap.exists) {
      const data = snap.data() as IdempotencyDoc;
      if (data.status === "completed") return { replay: true as const, result: data.result as T };
      throw new HttpsError("aborted", "idempotency-in-progress");
    }
    const doc: IdempotencyDoc = { status: "in_progress", functionName, createdAt: now, expiresAt: now + IDEMPOTENCY_TTL_MS };
    tx.create(ref, doc);
    return { replay: false as const };
  });

  if (reservation.replay) return reservation.result;

  try {
    const result = await fn();
    await ref.update({ status: "completed", result });
    return result;
  } catch (err) {
    await ref.delete().catch(() => {});
    throw err;
  }
}
