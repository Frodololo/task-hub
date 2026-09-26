# Seguridad y privacidad — 2026-09-26

Informe del encargo "App Check + SecureStore SubtleCrypto + deleteAccount server-side".

## A) Firebase App Check (Play Integrity) — IMPLEMENTADO

**Archivos modificados:**
- `composeApp/build.gradle.kts` — añadida `implementation("com.google.firebase:firebase-appcheck-playintegrity:18.0.0")` en `androidMain.dependencies`.
- `composeApp/src/androidMain/kotlin/org/taskhub/MainActivity.kt` — en `onCreate`, justo tras `super.onCreate()` y antes de cualquier otra inicialización, se instala el proveedor Play Integrity:
  ```kotlin
  FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
      PlayIntegrityAppCheckProviderFactory.getInstance()
  )
  ```

Solo lado cliente Android, como se pidió. La activación en Firebase Console (enforcement) y el registro del SHA-256 de la upload key en Play Console quedan para el orquestador.

## B) SecureStore.wasmJs → SubtleCrypto nativo — NO VIABLE

**Sin cambios de código** (se evaluó y se descartó antes de tocar el archivo).

**Motivo exacto:** `crypto.subtle.encrypt`/`decrypt` (Web Crypto API) son siempre asíncronos — devuelven una `Promise`, incluso para AES-GCM. La interfaz pública `SecureStore` (`getString`/`putString`/`remove`) es **síncrona** y así debía mantenerse según el encargo. El caso de uso real que rompe la migración es la lectura tras recarga de página: `SettingsStore` (p. ej. `getGoogleRefreshToken()`) llama a `secureStore.getString(...)` de forma síncrona desde propiedades no-`suspend`, esperando el valor descifrado en el mismo tick. Para poder devolver ese valor de una `Promise` de `crypto.subtle.decrypt` de forma síncrona harían falta o bien:
- Convertir `getString`/`putString`/`remove` a `suspend fun` — prohibido explícitamente por el encargo ("el contrato público NO cambia"), y además propagaría `suspend` a todos los call sites no-`suspend` de `SettingsStore`.
- Bloquear el hilo principal de JS hasta que la `Promise` resuelva — imposible en el hilo principal del navegador sin `Atomics.wait` + `SharedArrayBuffer` + cross-origin isolation (cabeceras `COOP`/`COEP` no configuradas hoy en el hosting), un cambio de infraestructura fuera del alcance de este encargo.

Esta misma conclusión ya estaba documentada en el propio archivo (cabecera de `SecureStore.wasmJs.kt`, líneas 9-11): la elección de AES-256-CTR "hecho a mano" en vez de AES-GCM vía SubtleCrypto fue deliberada, exactamente por esta razón. El encargo pedía revisitar esa decisión; tras reevaluarla, la restricción sigue vigente sin cambios en el entorno (no hay `SharedArrayBuffer`/COOP+COEP disponibles), así que se mantiene la implementación actual sin modificar.

Ningún cambio que revertir: no se llegó a escribir código para esta parte.

## C) deleteAccount desde servidor — IMPLEMENTADO

**Archivo modificado:** `composeApp/src/commonMain/kotlin/org/taskhub/ui/models/GoogleAuthManager.kt` (`deleteAccount()`).

**Cambio:** la lista de hogares a dar de baja/borrar ya no se lee de `householdStore.getSavedHouseholds()` (caché local, que podía faltar hogares si `syncHouseholdsToCloud` nunca completó en este dispositivo), sino de Firestore:

```kotlin
val myId = currentUserId()
if (myId != null && !repo.isOnline()) {
    return Result.failure(AccountDeletionCascadeException())
}
val households = myId?.let { uid ->
    val ids = (repo.loadUserHouseholds(uid) + repo.personalHouseholdId(uid)).distinct()
    repo.getHouseholds(ids)
} ?: emptyList()
```

- `repo.loadUserHouseholds(uid)` — hogares compartidos guardados en `users/{uid}.householdIds`.
- `repo.personalHouseholdId(uid)` — ID determinista del espacio Personal (`personal_{uid}`); se incluye siempre y `getHouseholds` lo descarta en silencio si no existiera (mismo comportamiento tolerante que ya usa `restoreHouseholds` para IDs obsoletos).
- **Guarda de red añadida:** `loadUserHouseholds` traga cualquier excepción y devuelve lista vacía (`orDefault`, uso compartido con otros callers que no se tocó). Sin la comprobación `repo.isOnline()` previa, un dispositivo offline habría interpretado "no se pudo leer Firestore" como "sin hogares" y habría borrado la cuenta Auth dejando el UID huérfano en hogares reales — la regresión exacta que la migración a Firestore quería evitar, solo que por la vía offline en vez de por caché desactualizada. Se trata igual que un fallo de cascada: se devuelve `AccountDeletionCascadeException` y el usuario conserva la sesión para reintentar con conexión.
- El resto de la función (orden de borrado, manejo de fallos parciales, borrado de perfil/cuenta Auth, limpieza local) no cambia.

## Verificación

1. `./gradlew :composeApp:compileDebugKotlinAndroid --console=plain` → **BUILD SUCCESSFUL** (52s).
2. `./gradlew :composeApp:jvmTest --rerun-tasks --console=plain` → **BUILD SUCCESSFUL**. 27 test suites en `build/test-results/jvmTest/`, 54 `<testsuite>` en total, 0 con `failures`/`errors`.
3. wasmJs no se tocó (parte B no viable) → no aplica el paso 3 de verificación.

## Kanban

Tarjeta primaria `[Seguridad] Firebase App Check` movida a **Completado** (proyecto `PVT_kwHOCXo7m84BjLGt`, item `PVTI_lAHOCXo7m84BjLGtzg8uiPY`) vía `gh api graphql`.
