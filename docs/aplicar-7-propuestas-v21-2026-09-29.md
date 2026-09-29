# Aplicación de 7 propuestas del panel v21 (2026-09-29)

Base: `96ab3ae` (v0.7.54). Encargo del dueño del proyecto: aplicar las 7 decisiones descritas en el ticket, todas con veredicto APLICA/MEJORA.

## Cambios por propuesta

### 1. Auto-relleno sin liveRegion → APLICA
- `ui/screens/CreateProfileScreen.kt`: nuevo estado `nameAutoFilled`, marcado a `true` cuando el `LaunchedEffect(authState)` rellena `displayName` desde Google. El `OutlinedTextField` del nombre añade `semantics { liveRegion = LiveRegionMode.Polite }` solo mientras `nameAutoFilled` es `true`.
- `ui/screens/JoinHouseholdScreen.kt`: mismo patrón para el campo de nombre del paso 2 (`nameAutoFilled`).

### 2. Botón emoji cerrado dice "Elegir emoji" → APLICA
- `ui/screens/CreateHouseholdScreen.kt`: el botón del emoji ahora siempre muestra `create_household_emoji_selected` con `effectiveEmoji` (el del `SpaceType` si no hay elección manual, o el elegido), en vez de caer a `create_household_choose_emoji` cuando `customEmoji == null`.

### 3. `emoji == ""` no cae al fallback → APLICA
- `ui/components/HouseholdTaskSection.kt` y `ui/screens/ProfileScreen.kt`: `household.emoji ?: household.spaceType.emoji` → `if (!household.emoji.isNullOrBlank()) household.emoji else household.spaceType.emoji`, para que una cadena vacía también use el emoji del tipo de espacio.

### 4. `hasGoogleLinked` no se degrada tras fallo persistente → APLICA
- `ui/models/CalendarSyncManager.kt`: nuevo campo `consecutiveTokenFailures` (privado) y método privado `ensureCalendarAccessToken()` que envuelve `authManager.ensureCalendarAccessToken()`. Incrementa el contador en cada fallo; al llegar a `MAX_CONSECUTIVE_TOKEN_FAILURES = 3` llama a `settingsStore.unlinkGoogleCalendar()`. Se resetea a 0 en cualquier éxito. Los 5 puntos de llamada de `sync()`/`reconcile()`/`syncNow()`/`onDueDateChanged()` que antes llamaban a `authManager.ensureCalendarAccessToken()` directamente ahora pasan por el wrapper.

### 5. Dropdown de temas a RadioOptionRow → APLICA
- `ui/components/SettingsSheet.kt`: la sección "Tema" reemplaza el `ExposedDropdownMenuBox` por un `Column(selectableGroup())` con un `RadioOptionRow` por cada `TaskHubThemeType`, mismo patrón que Idioma/Tema del widget. `RadioOptionRow` gana un parámetro opcional `leadingContent: (@Composable RowScope.() -> Unit)?` para mantener el swatch de color de cada tema. No hizo falta añadir claves i18n nuevas (`getThemeLabel`/`theme_*` ya existían en ambos idiomas).

### 6. Medianoche: iconos de barra de estado con bajo contraste → MEJORA
- `androidMain/.../MainActivity.kt`: en `onCreate`, tras `enableEdgeToEdge()`, si `settingsStore.getTheme() == TaskHubThemeType.MIDNIGHT.name` se fuerza `WindowInsetsControllerCompat(window, window.decorView)` con `isAppearanceLightStatusBars = false` e `isAppearanceLightNavigationBars = false`, para que los iconos salgan claros sobre el fondo oscuro de Medianoche aunque el sistema esté en modo claro.

### 7. `todayStartEpoch` intestable → MEJORA
- `ui/components/TaskListRules.kt`: nueva función pura `internal fun todayStartEpoch(): Long` (sin parámetros Compose).
- `ui/screens/TaskListScreen.kt`: el cálculo inline dentro de `TaskListContent` se sustituye por la llamada `todayStartEpoch()`.
- Nuevo test `composeApp/src/commonTest/kotlin/org/taskhub/ui/components/TaskListRulesTest.kt` con 3 casos (medianoche exacta, mismo día que "ahora", no está en el futuro).

## Archivos tocados

```
composeApp/src/androidMain/kotlin/org/taskhub/MainActivity.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/components/HouseholdTaskSection.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/components/SettingsSheet.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/components/TaskListRules.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/models/CalendarSyncManager.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/screens/CreateHouseholdScreen.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/screens/CreateProfileScreen.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/screens/JoinHouseholdScreen.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/screens/ProfileScreen.kt
composeApp/src/commonMain/kotlin/org/taskhub/ui/screens/TaskListScreen.kt
composeApp/src/commonTest/kotlin/org/taskhub/ui/components/TaskListRulesTest.kt (nuevo)
```

## Verificación

```bash
./gradlew :composeApp:compileDebugKotlinAndroid --console=plain
# BUILD SUCCESSFUL in 57s — 25 actionable tasks: 7 executed, 18 up-to-date
# Solo warnings preexistentes (GoogleSignIn deprecated, `when` exhaustivo, etc.), ninguno de este cambio.

./gradlew :composeApp:jvmTest --rerun-tasks --console=plain
# BUILD SUCCESSFUL in 28s — 15 actionable tasks: 15 executed
# TaskListRulesTest (org.taskhub.ui.components): 3 tests, 0 fallos, 0 errores.
```
