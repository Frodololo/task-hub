/**
 * Validación de seguridad compartida por las funciones callable — ver
 * sección 2 del diseño de fase 2a: `context.auth != null` + membresía
 * activa (`leftAt == 0`, equivalente a `isMember(hid)` en `firestore.rules`)
 * + para `reassignTaskCompletion`, rol owner/admin del llamador
 * (`isTrusted(hid)`).
 *
 * El Admin SDK ignora `firestore.rules` por completo (ver sección 5 del
 * diseño): toda esta validación vive aquí, en código de la función, no en
 * las reglas.
 */
import type { Transaction } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { db } from "./admin.js";
import { HouseholdDoc, MemberDoc } from "./types.js";
import { DEFAULT_TZ } from "./rules.js";

/** `context.auth != null` — sin esto no hay `uid` que validar contra el hogar. */
export function requireAuth(uid: string | undefined): string {
  if (!uid) throw new HttpsError("unauthenticated", "auth-required");
  return uid;
}

/** Lee `households/{householdId}/members/{memberId}` dentro de la transacción y exige que sea un miembro activo (`leftAt == 0`). */
export async function loadActiveMember(tx: Transaction, householdId: string, memberId: string): Promise<MemberDoc> {
  const snap = await tx.get(db.doc(`households/${householdId}/members/${memberId}`));
  if (!snap.exists) throw new HttpsError("not-found", "member-not-found");
  const data = snap.data() as MemberDoc;
  if ((data.leftAt ?? 0) !== 0) throw new HttpsError("permission-denied", "member-not-active");
  return data;
}

/**
 * TZ IANA del hogar (D1: `households/{hid}.timezone`) dentro de la
 * transacción, con fallback a `DEFAULT_TZ` si el hogar no existe, el campo
 * no está poblado (hogares creados antes de D1), o el valor no es un
 * identificador IANA válido.
 *
 * Panel v17 (hallazgo IMPORTANTE de programador senior): antes se devolvía
 * `data.timezone` tal cual, sin validar. Un valor corrupto/no-IANA (el campo
 * no tiene ninguna validación de formato en `firestore.rules`, se escribe
 * tal cual desde `deviceTimezone` del cliente que crea el hogar) hacía que
 * `new Intl.DateTimeFormat(..., { timeZone: tz })` lanzara un `RangeError`
 * SIN CAPTURAR más adelante en la transacción — la función entera fallaba
 * con un 500 `internal` genérico en vez de completar la tarea con el
 * fallback ya documentado.
 */
export async function loadHouseholdTimezone(tx: Transaction, householdId: string): Promise<string> {
  const householdSnap = await tx.get(db.doc(`households/${householdId}`));
  const data = householdSnap.data() as HouseholdDoc | undefined;
  const tz = data?.timezone;
  if (!tz) return DEFAULT_TZ;
  try {
    // eslint-disable-next-line no-new -- solo se usa para validar el formato
    new Intl.DateTimeFormat("en-US", { timeZone: tz });
    return tz;
  } catch {
    return DEFAULT_TZ;
  }
}

/**
 * `isOwner(hid) || isAdminMember(hid)` — ver `firestore.rules`. No lanza:
 * usada por `donatePoints` para decidir el tope de transferencia entre
 * iguales (`MAX_PEER_TRANSFER_AMOUNT`) en vez de bloquear la operación.
 */
export async function isTrustedMember(tx: Transaction, householdId: string, uid: string): Promise<boolean> {
  const householdSnap = await tx.get(db.doc(`households/${householdId}`));
  if (!householdSnap.exists) throw new HttpsError("not-found", "household-not-found");
  const ownerId = householdSnap.data()?.ownerId as string | undefined;
  if (ownerId === uid) return true;
  const memberSnap = await tx.get(db.doc(`households/${householdId}/members/${uid}`));
  if (!memberSnap.exists) return false;
  const data = memberSnap.data() as MemberDoc;
  return (data.leftAt ?? 0) === 0 && data.role === "admin";
}

/** `isOwner(hid) || isAdminMember(hid)` — ver `firestore.rules`. */
export async function requireTrusted(tx: Transaction, householdId: string, uid: string): Promise<void> {
  if (await isTrustedMember(tx, householdId, uid)) return;
  throw new HttpsError("permission-denied", "not-trusted");
}
