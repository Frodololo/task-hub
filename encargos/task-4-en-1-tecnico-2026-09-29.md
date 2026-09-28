# Encargo 4-en-1: Cambios técnicos sin decisión de diseño (2026-09-29)

HEAD: `2e7224d` (v0.7.53). Todos los cambios requieren:
```
./gradlew :composeApp:compileDebugKotlinAndroid --console=plain   → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain       → BUILD SUCCESSFUL
```

---

## 1. Auto-rellenar nombre desde Google al crear/unirse a grupo

**Contexto:** `GoogleSignInResult.displayName` ya viene de Firebase Auth pero solo se usa en `syncGoogleAvatar`. No se guarda en `SettingsStore` ni se pre-rellena en los formularios.

**Archivos a tocar:**

### SettingsStore.kt (`storage/`)
- Añadir clave `KEY_GOOGLE_DISPLAY_NAME = "taskhub_google_display_name"`
- Métodos: `fun getGoogleDisplayName(): String?` y `fun setGoogleDisplayName(name: String?)`
- En `clearGoogleAuth()`: añadir `settings.remove(KEY_GOOGLE_DISPLAY_NAME)`

### GoogleAuthManager.kt (`ui/models/`)
- `handleGoogleToken`: tras `settingsStore.setGoogleAuth(result.uid, result.email)`, añadir `settingsStore.setGoogleDisplayName(result.displayName)`
- `GoogleAuthState.SignedIn`: añadir campo `val displayName: String?` (junto a `email`). El estado inicial desde `settingsStore` también debe pasar el displayName.
- `signOut`: `clearGoogleAuth()` ya limpia todo, no necesita cambio adicional.

### GoogleAuthManager.kt — referencias al SignedIn
- Buscar TODOS los sitios que construyen `GoogleAuthState.SignedIn(email)` y añadir `displayName`. Hay al menos 2: el init block y handleGoogleToken.

### CreateProfileScreen.kt (`ui/screens/`)
- Inyectar `GoogleAuthManager` vía `koinInject()` y leer `authManager.state.value`
- Si `displayName` está vacío al entrar y `authState` es `SignedIn` con `displayName != null`, pre-rellenar `displayName` con ese valor.
- Añadir hint visual tipo "desde tu cuenta de Google" si se auto-rellenó (opcional, texto i18n existente o inline).

### JoinHouseholdScreen.kt (`ui/screens/`)
- Mismo patrón: inyectar `GoogleAuthManager`, pre-rellenar `displayName` del paso 2 si el campo está vacío y el Google user tiene displayName.

### AppStrings.kt (`ui/i18n/`)
- NO es necesario añadir claves nuevas si usas el placeholder existente y el nombre se pre-rellena sin texto adicional. Si quieres añadir un subtítulo sutil, usa un string inline en español.

---

## 2. Selector de emoji manual para grupos en CreateHouseholdScreen

**Contexto:** Hoy el emoji del grupo es fijo según `SpaceType.emoji`. Se quiere un selector de emoji adicional (no reemplazar el SpaceType, sino permitir sobreescribir el emoji). Se usará el mismo patrón visual que `EditProfileScreen` (grid 6 columnas, 24 emojis).

**Cambios:**

### DTOs.kt (`network/models/`)
- `HouseholdResponse`: añadir campo `val emoji: String? = null` (opcional, hogares legacy sin él)
- Este campo se persistirá en Firestore como campo `emoji` del documento `households/{hid}`. Si es null, se usa el emoji del `SpaceType` como hoy.

### HouseholdRepository.kt (`network/`)
- `createHousehold`: añadir parámetro `emoji: String? = null`. Si no es null, incluirlo en el body del POST a Firestore.
- La respuesta `HouseholdResponse` ya incluirá el campo igual que el resto.

### FirestoreRepository.kt (`network/`)
- `createHousehold`: propagar el parámetro `emoji` a `householdRepository.createHousehold`.

### HouseholdScreenModel.kt (`ui/models/`)
- `createHousehold`: añadir parámetro `emoji: String? = null` y propagarlo a `repo.createHousehold`.

### CreateHouseholdScreen.kt (`ui/screens/`)
- Añadir sección entre el selector de SpaceType y el campo de nombre: botón "Elegir emoji" + grid desplegable (mismo patrón que `EditProfileScreen.kt` líneas 81-248)
- Misma lista de emojis que EditProfileScreen: 24 emojis, grid 6 columnas
- Por defecto: el emoji del SpaceType seleccionado (`selectedType.emoji`)
- Si el usuario elige otro manualmente, ese se envía en `model.createHousehold(name, selectedType, emoji)`
- Añadir indicador visual de qué emoji está seleccionado (mismo patrón: borde primario + fondo)

### HouseholdTaskSection.kt (`ui/components/`)
- Donde se muestra el emoji del hogar en cards: si `household.emoji` no es null, usarlo. Si es null, usar `spaceType.emoji` como antes.
- Verificar cómo se pasa `HouseholdResponse` a esta card (puede que reciba un `SavedHousehold` de caché local — si `SavedHousehold` no tiene `emoji`, añadírselo)

### HouseholdStore.kt (`storage/`)
- `SavedHousehold`: añadir campo `val emoji: String? = null`
- `saveHousehold`: añadir parámetro `emoji: String?` y guardarlo.
- Todos los call-sites de `saveHousehold` en `HouseholdScreenModel`, `GoogleAuthManager.restoreHouseholds`, etc. deben propagar el emoji (mismo patrón que `spaceType`).

### AppStrings.kt (`ui/i18n/`)
- Añadir clave `"create_household_emoji_label"` → "Emoji del grupo" / EN "Group emoji"
- Añadir clave `"create_household_emoji_selected"` → "Emoji: %s" / EN "Emoji: %s"
- Añadir clave `"create_household_choose_emoji"` → "Elegir emoji" / EN "Choose emoji"

### firestore.rules
- NO requiere cambio: el campo `emoji` entra dentro del match genérico `households/{hid}` que ya permite write sin whitelist de campos (ver decisión documentada). Si quieres añadirlo explícito, es en la rama de create/update de households.

---

## 3. IdempotencyKey real — reutilizar entre reintentos

**Contexto (panel v20):** Hoy `idempotencyKey` se genera NUEVO en cada llamada del repositorio (`Uuid.random().toString()` dentro de cada función). El servidor (`withIdempotency`) sí funciona, pero si el usuario reintenta manualmente tras un error ambiguo, el ScreenModel genera una llamada al repositorio con clave DISTINTA → el servidor la trata como operación nueva → riesgo de duplicar puntos/canjes sigue abierto.

**Fix:** El ScreenModel debe guardar el `idempotencyKey` entre reintentos de la misma acción lógica.

**Archivos a tocar:**

### TaskScreenModel.kt (`ui/models/`)
- `completeTask`: guardar `idempotencyKey` como campo de clase (no local). Inicializar solo cuando se inicia una acción nueva (no en reintento). Reutilizarlo si la última acción no tuvo confirmación definitiva.
- Esquema:
  ```kotlin
  private var pendingIdempotencyKeys = mutableMapOf<String, String>() // actionId → key
  
  fun completeTask(taskId: String, ...) {
      val key = pendingIdempotencyKeys.remove(taskId) ?: Uuid.random().toString()
      // usar key en la llamada al repositorio
      // si falla con error ambiguo, volver a guardar: pendingIdempotencyKeys[taskId] = key
      // si éxito, no guardar (la clave ya se consumió, se generará nueva)
  }
  ```
- Usar `taskId` como clave lógica de la acción (cada tarea tiene su propia idempotencyKey pendiente)

### MemberScreenModel.kt (`ui/models/`)
- `donatePoints`: mismo patrón. Usar `"donate:${householdId}:${targetMemberId}"` como clave lógica.
- `redeemReward`: mismo patrón. Usar `"redeem:${rewardId}"` como clave lógica.

### FirestoreRepository.kt (`network/`)
- Las 4 funciones (`donatePoints`, `completeTask`, `completeAssignment`, `redeemReward`) ya aceptan `idempotencyKey` como parámetro (añadido en v19). No cambiar nada aquí —solo asegurar que el parámetro se propaga correctamente.

### CloudFunctionsClient.kt (`network/`)
- Verificar que `call()` lanza una excepción distinguible cuando el servidor responde con `UNCERTAIN`/`AMBIGUOUS` (para que el ScreenModel pueda saber que debe reutilizar la clave). Si no hay excepción específica, el catch general ya funciona —la clave se reutiliza en CUALQUIER fallo. Eso es aceptable (el riesgo de duplicar es solo en fallo ambiguo, pero reutilizar la clave en cualquiera fallo no duele: si la peración no llegó al servidor, la clave nueva da igual que la reutilizada —el servidor la tratará como peración nueva igalmente y no duplicará).

---

## 4. CalendarSyncManager → migrar 8 sitios a bestEffort (panel v20 hallazgo MENOR)

**Contexto:** `CalendarSyncManager.kt` tiene 8 sitios con patrón `try/catch { CancellationException throw; else AppLog }` o incluso sin log. Migrar todos al helper `bestEffort` que ya existe en `platform/AppLog.kt`.

**Patrón de cada sitio:**
```kotlin
// ANTES
try {
    algunaOperación()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppLog.w("CalendarSyncManager", "descripción", e)
}

// DESPUÉS (conservando el log con tag específico)
bestEffort(Unit, "CalendarSyncManager.método.descripción") {
    algunaOperación()
}
```

**Nota (panel v20):** algunos sitios HOY no tienen log (`catch` vacío o sin `AppLog`) —al migrar a `bestEffort` se AÑADE logging (comportamiento observable nuevo aceptado por ser panico v20). Si el site ya tenía `AppLog`, mantener el mismo mensaje descriptivo en el tag.

**Archivo a tocar:** solo `CalendarSyncManager.kt`.

---

## Orden de implementación

1. SettingsStore + GoogleAuthManager (preparar displayName)
2. CreateProfileScreen + JoinHouseholdScreen (auto-rellenar)
3. DTOs + HouseholdRepository + HouseholdStore (emoji field)
4. CreateHouseholdScreen (selector emoji)
5. HouseholdTaskSection (mostrar emoji custom)
6. TaskScreenModel + MemberScreenModel (idempotencyKey real)
7. CalendarSyncManager (bestEffort migration)

## Verificación

```bash
./gradlew :composeApp:compileDebugKotlinAndroid --console=plain   → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain       → BUILD SUCCESSFUL
```

## No hacer
- No tocar diseño visual existente (el grid de emoji es calco del de EditProfileScreen)
- No cambiar `firestore.rules` (el campo `emoji` entra en el match genérico)
- No reintentar si el ScreenModel detecta que el usuario navegó a otra pantalla (limpiar `pendingIdempotencyKeys` en `reset()`)
- No hacer release/bump — solo dejar compilando y tests verdes.