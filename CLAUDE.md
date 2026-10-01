# Task Hub — memoria del proyecto

Gestor de tareas del hogar (puntos + recompensas). GitHub: `LibertoBaltasar/task-hub`. Firebase: `task-hub-62f98`.
Mapa completo de documentación: `docs/INDICE.md`.

## Stack
- Compose Multiplatform (Kotlin 2.1, CMP 1.8.0) → Android (minSdk 26, target 36) + iOS + JVM desktop + Web (wasmJs).
- Navegación: Voyager (`Screen`, `navigator.push/pop/replaceAll`).
- DI: Koin (`koinInject`, `koinScreenModel`).
- Datos: Firestore vía REST (`network/FirestoreRepository.kt`, Ktor) — **NO** Firestore SDK.
- Persistencia: multiplatform-settings (`storage/`: SettingsStore, HouseholdStore, ThemeStore, TaskCache).
- i18n: `ui/i18n/AppStrings.kt` (ES + EN), `AppStrings.get(key, lang)`.
- Auth: Google Sign-In (`signInWithIdp`), obligatorio — sin auth anónima desde 2026-09-12. Ads: AdMob (solo androidMain). Analytics: Firebase (eventos custom vía `platform/Analytics.kt`).

## Estructura
`composeApp/src/commonMain/kotlin/org/taskhub/`
- `App.kt` (raíz), `di/AppModule.kt` (Koin), `ui/screens/`, `ui/components/`, `ui/models/`, `ui/theme/Theme.kt` (Teal600, Coral500, Teal50), `network/`, `platform/`.

## Comandos
```bash
cd ~/task-hub && ./gradlew :composeApp:compileDebugKotlinAndroid --console=plain
```
`BUILD SUCCESSFUL` = compila commonMain + androidMain. Bundle release ~5 min (R8).

## Convenciones
- Mensajes de commit en español: `feat: ...` / `fix: ...` / `chore: bump versión X.Y.Z`.
- Solo `material-icons-core` (NO extended).
- Iconos espejados → `Icons.AutoMirrored.Filled.*` (el alias `Icons.Default.*` está deprecado).
- Código comentado, KDoc en modelos. Ver skill `task-hub` para pitfalls detallados.
