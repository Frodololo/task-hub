# Cambios — 4 en 1 técnico (2026-09-29)

Encargo: `encargos/task-4-en-1-tecnico-2026-09-29.md`, sobre HEAD `2e7224d` (v0.7.53). Cuatro cambios técnicos sin decisión de diseño pendiente.

## 1. Auto-rellenar nombre desde Google al crear/unirse a grupo

- `SettingsStore.kt`: nueva clave `KEY_GOOGLE_DISPLAY_NAME` + `getGoogleDisplayName()`/`setGoogleDisplayName()`; se limpia en `clearGoogleAuth()`.
- `GoogleAuthManager.kt`: `handleGoogleToken` guarda el `displayName` de Google en `SettingsStore`; `GoogleAuthState.SignedIn` gana un campo `displayName: String?` (ambos sitios que construían `SignedIn` — el `init` y `handleGoogleToken` — lo propagan).
- `CreateProfileScreen.kt` / `JoinHouseholdScreen.kt` (paso 2): inyectan `GoogleAuthManager` vía `koinInject()` y, si el campo de nombre sigue vacío, lo pre-rellenan con el `displayName` de la cuenta de Google en cuanto llega el estado `SignedIn`. Sigue siendo editable — no se ha tocado el diseño visual.

## 2. Selector de emoji manual para grupos

- `DTOs.kt`: `HouseholdResponse.emoji: String? = null` (hogares legacy sin el campo caen a `null`).
- `FirestoreParsers.kt`: `toHouseholdResponse` parsea `emoji` del documento Firestore.
- `HouseholdRepository.kt` / `FirestoreRepository.kt`: `createHousehold` acepta `emoji: String? = null` y lo incluye en el POST solo si no es null.
- `HouseholdScreenModel.kt`: `createHousehold` y `joinHousehold` propagan `emoji` a `repo`/`householdStore`.
- `HouseholdStore.kt`: `SavedHousehold.emoji` + parámetro en `saveHousehold`; todos los call-sites (`HouseholdScreenModel`, `GoogleAuthManager.restoreHouseholds`) propagan el valor recibido del hogar.
- `CreateHouseholdScreen.kt`: selector de emoji (botón + grid desplegable de 6 columnas, 24 emojis) entre el selector de `SpaceType` y el campo de nombre — mismo patrón visual que `EditProfileScreen.kt`. Por defecto usa el emoji del `SpaceType` seleccionado; si el usuario elige uno manualmente, ese es el que se envía a `createHousehold`.
- `HouseholdTaskSection.kt` y `ProfileScreen.kt`: al pintar el emoji de un hogar en las tarjetas, usan `household.emoji ?: household.spaceType.emoji` (fallback al comportamiento anterior).
- `AppStrings.kt` (ES/EN): 3 claves nuevas — `create_household_emoji_label`, `create_household_emoji_selected`, `create_household_choose_emoji`.
- `firestore.rules`: sin cambios (el campo `emoji` entra en el match genérico de `households/{hid}`, como ya preveía el encargo).

## 3. IdempotencyKey real (reutilizar entre reintentos)

**Hallazgo durante la implementación:** el encargo asumía que `FirestoreRepository.{completeTask, completeAssignment, donatePoints, redeemReward}` ya aceptaban `idempotencyKey` como parámetro (añadido en v19). En el código real, las 4 funciones generaban `Uuid.random()` **internamente** en cada llamada — sin exponerlo, la clave nunca podía reutilizarse desde el ScreenModel por mucho que este guardara algo. Se ha añadido el parámetro `idempotencyKey: String = Uuid.random().toString()` a las 4 funciones (el valor por defecto preserva el comportamiento para cualquier otro llamante) para que el fix del ScreenModel tenga efecto real.

- `FirestoreRepository.kt`: `completeTask`, `completeAssignment`, `donatePoints`, `redeemReward` aceptan `idempotencyKey` como parámetro en vez de generarlo dentro.
- `TaskScreenModel.kt`: nuevo campo `pendingIdempotencyKeys: MutableMap<String, String>` (clave lógica = `taskId`). `completeTask` reutiliza la clave pendiente si existe, o genera una nueva; en el `catch` general la vuelve a guardar (reutilizable en cualquier fallo, tal y como acepta el encargo); se limpia en `reset()`.
- `MemberScreenModel.kt`: mismo patrón. `donatePoints` usa `"donate:$householdId:$toMemberId"` como clave lógica (reutiliza también en el branch `DonateResult.Error`, ya que el propio `FirestoreRepository.donatePoints` atrapa el fallo de red internamente y lo traduce a un resultado, no a una excepción). `redeemReward` usa `"redeem:$rewardId"`. Ambas se limpian en `reset()`.
- `CloudFunctionsClient.kt`: sin cambios — no expone una excepción específica de "ambiguo/incierto"; el catch general del ScreenModel ya cubre el caso (reutilizar la clave en cualquier fallo es inofensivo si la llamada nunca llegó al servidor).
- `FakeFirestoreRepository.kt` (test): actualizado el `override` de `completeTask`/`completeAssignment` para el nuevo parámetro `idempotencyKey`.

## 4. CalendarSyncManager → migración a `bestEffort`

`CalendarSyncManager.kt` tenía **10** sitios con el patrón `try { ... } catch (e: CancellationException) { throw e } catch (_: Exception) { ... }` (el encargo estimaba 8; se migraron todos los que existían realmente, no solo 8, para dejar el archivo consistente). Todos migrados a `bestEffort(default, tag) { ... }` (`platform/AppLog.kt`), con tags descriptivos `"CalendarSyncManager.<método>.<operación>"`:

- `ensureCalendarId.createOrFind`
- `onTaskAssigned.sync` y `onTaskAssigned.getTasks` (anidado)
- `onDueDateChanged.sync`
- `reconcile.sync` y `reconcile.getTasks` (anidado)
- `syncNow.createEvent`
- `createEventForAssignment.createEvent`
- `deleteEventForAssignment.cleanup` y `deleteEventForAssignment.deleteEvent` (anidado)

Algunos de estos sitios no tenían log antes (catch silencioso) — al migrar a `bestEffort` ganan logging vía `AppLog.w`, comportamiento observable nuevo aceptado explícitamente por el encargo. Se retiró el import `kotlinx.coroutines.CancellationException`, ya sin uso tras la migración.

## Archivos tocados

```
composeApp/src/commonMain/kotlin/org/taskhub/storage/SettingsStore.kt
composeApp/src/commonMain/kotlin/org/taskhub/storage/HouseholdStore.kt
composeApp/src/commonMain/kotlin/org/taskhub/network/models/DTOs.kt
composeApp/src/commonMain/kotlin/org/taskhub/network/FirestoreParsers.kt
composeApp/src/commonMain/kotlin/org/taskhub/network/FirestoreRepository.kt
composeApp/src/commonMain/kotlin/org/taskhub/network/HouseholdRepository.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/models/GoogleAuthManager.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/models/HouseholdScreenModel.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/models/TaskScreenModel.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/models/MemberScreenModel.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/models/CalendarSyncManager.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/screens/CreateProfileScreen.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/screens/JoinHouseholdScreen.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/screens/CreateHouseholdScreen.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/screens/ProfileScreen.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/components/HouseholdTaskSection.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/i18n/AppStrings.kt
composeApp/src/commonTest/kotlin/org/taskhub/ui/models/FakeFirestoreRepository.kt
encargos/task-4-en-1-tecnico-2026-09-29.md
```

## Verificación

```
./gradlew :composeApp:compileDebugKotlinAndroid --console=plain   → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain       → BUILD SUCCESSFUL
```

`composeApp/build/test-results/jvmTest/*.xml`: **291 tests, 0 failures, 0 errors**.

## Commit final

```
$ git log --oneline -1
<pendiente — ver el commit que sigue a este informe>
```
