/**
 * Helpers compartidos por los tests de integración (`*.integration.test.ts`)
 * contra el emulador de Firestore (`firebase emulators:start --only
 * functions,firestore` / `firebase emulators:exec`, ver
 * `jest.integration.config.mjs` y `package.json#scripts.test:integration`).
 */
import type { CallableRequest } from "firebase-functions/v2/https";
import type { DecodedIdToken } from "firebase-admin/auth";

/**
 * Borra TODOS los documentos del proyecto emulado entre tests — el emulador
 * expone un endpoint REST dedicado para esto (no hay equivalente en el
 * Admin SDK). Sin esto, cada test heredaría el estado que dejó el anterior.
 */
export async function clearFirestoreEmulator(): Promise<void> {
  const host = process.env.FIRESTORE_EMULATOR_HOST ?? "127.0.0.1:8180";
  const projectId = process.env.GCLOUD_PROJECT ?? "demo-task-hub";
  const res = await fetch(`http://${host}/emulator/v1/projects/${projectId}/databases/(default)/documents`, {
    method: "DELETE"
  });
  if (!res.ok) {
    throw new Error(`clearFirestoreEmulator: fallo limpiando el emulador (${res.status})`);
  }
}

/**
 * Construye un `CallableRequest<T>` mínimo para invocar `.run(...)` de una
 * función `onCall` directamente (sin pasar por el protocolo HTTP callable),
 * autenticado como `uid`. Los handlers de este paquete solo leen
 * `request.auth?.uid` (ver `auth.ts#requireAuth`), así que `token`/`rawToken`
 * no necesitan ser válidos de verdad para los tests.
 */
export function callAs<T>(uid: string, data: T): CallableRequest<T> {
  return {
    data,
    auth: { uid, token: {} as DecodedIdToken, rawToken: "" }
  } as CallableRequest<T>;
}

/** Igual que [callAs] pero sin autenticar — para probar el guard `requireAuth`. */
export function callUnauthenticated<T>(data: T): CallableRequest<T> {
  return { data } as CallableRequest<T>;
}
