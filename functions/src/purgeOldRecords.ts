/**
 * `purgeOldRecords` — purga server-side (TTL) de `taskHistory`/`messages`
 * con más de 90 días, y de `idempotency/{key}` (ver `idempotency.ts`) ya
 * caducados (`expiresAt`, 24h desde su creación — sin política nativa de
 * TTL de Firestore configurada). Complementa, no sustituye, la purga
 * best-effort del cliente (`FirestoreRepository.purgeOldTaskHistory`/
 * `purgeOldMessages`, disparada desde `NotificationPollWorker`) — esta pasa
 * corre sí o sí, sin depender de que algún dispositivo abra la app.
 *
 * Idempotente: repetir la ejecución no tiene efecto distinto de ejecutarla
 * una vez (los documentos ya borrados simplemente no aparecen en pasadas
 * posteriores). Borra en lotes de `BATCH_SIZE` (límite real de Firestore:
 * 500 escrituras por batch) y respeta un presupuesto de tiempo por debajo
 * del `timeoutSeconds` real de la función, para cortar de forma ordenada en
 * vez de que Cloud Functions aborte a mitad de un batch — cualquier resto
 * queda para la siguiente pasada programada (24h), sin acumular sin límite.
 */
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions/v2";
import { db, REGION } from "./admin.js";

const RETENTION_MS = 90 * 24 * 60 * 60 * 1000;
const BATCH_SIZE = 500;
const TIME_BUDGET_MS = 8 * 60 * 1000;

export interface PurgeResult {
  taskHistoryDeleted: number;
  messagesDeleted: number;
  idempotencyDeleted: number;
}

async function purgeCollectionGroup(
  collectionId: string,
  timestampField: string,
  cutoff: number,
  deadline: number
): Promise<number> {
  let deleted = 0;
  while (Date.now() < deadline) {
    const snap = await db.collectionGroup(collectionId).where(timestampField, "<", cutoff).limit(BATCH_SIZE).get();
    if (snap.empty) break;
    const batch = db.batch();
    for (const doc of snap.docs) batch.delete(doc.ref);
    await batch.commit();
    deleted += snap.size;
    if (snap.size < BATCH_SIZE) break; // última página
  }
  return deleted;
}

/**
 * Igual que [purgeCollectionGroup] pero sobre una colección de nivel raíz
 * (`idempotency` no es una subcolección de `households/{hid}`, así que
 * `collectionGroup` no aplica aquí).
 */
async function purgeRootCollection(
  collectionId: string,
  timestampField: string,
  cutoff: number,
  deadline: number
): Promise<number> {
  let deleted = 0;
  while (Date.now() < deadline) {
    const snap = await db.collection(collectionId).where(timestampField, "<", cutoff).limit(BATCH_SIZE).get();
    if (snap.empty) break;
    const batch = db.batch();
    for (const doc of snap.docs) batch.delete(doc.ref);
    await batch.commit();
    deleted += snap.size;
    if (snap.size < BATCH_SIZE) break; // última página
  }
  return deleted;
}

/** Ejecuta una pasada de purga completa. Exportado para test — `now` inyectable para fijar el cutoff. */
export async function purgeOldRecords(now = Date.now()): Promise<PurgeResult> {
  const cutoff = now - RETENTION_MS;
  const deadline = Date.now() + TIME_BUDGET_MS;
  const taskHistoryDeleted = await purgeCollectionGroup("taskHistory", "completedAt", cutoff, deadline);
  const messagesDeleted = await purgeCollectionGroup("messages", "createdAt", cutoff, deadline);
  // Cutoff propio (`now`, no `now - RETENTION_MS`): `idempotency/{key}.expiresAt`
  // ya es el instante absoluto de caducidad (creado como `createdAt + 24h`),
  // no una fecha de creación a la que aplicar la retención de 90 días.
  const idempotencyDeleted = await purgeRootCollection("idempotency", "expiresAt", now, deadline);
  logger.info("purgeOldRecords: pasada completada", { taskHistoryDeleted, messagesDeleted, idempotencyDeleted });
  return { taskHistoryDeleted, messagesDeleted, idempotencyDeleted };
}

export const purgeOldRecordsScheduled = onSchedule(
  { region: REGION, schedule: "every 24 hours", timeoutSeconds: 540 },
  async () => {
    await purgeOldRecords();
  }
);
