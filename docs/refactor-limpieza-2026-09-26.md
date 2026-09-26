# Refactor y limpieza — Analytics opt-out + GoogleSignInResultHolder sealed class (2026-09-26)

Encargo de dos mejoras de bajo riesgo (tarjeta primaria: `[Analytics] Opt-out
en Ajustes`, hallazgo #2 de `review-panel-expertos-v17-2026-09-24.md`).

## A) Analytics opt-out en Ajustes

- `SettingsStore` → nueva clave `taskhub_analytics_opt_out` (`isAnalyticsOptOut()`
  / `setAnalyticsOptOut()`, default `false`).
- `platform/Analytics.kt` → nuevo `expect fun setAnalyticsCollectionEnabled(enabled: Boolean)`,
  con `actual` en las 4 plataformas: Android delega en
  `FirebaseAnalytics.setAnalyticsCollectionEnabled`, iOS/JVM/wasmJs son no-op
  (mismo patrón que `logAnalyticsEvent`, que ya era no-op ahí).
- `AppStrings.kt` → `settings_analytics_opt_out` / `settings_analytics_opt_out_desc` (ES/EN).
- `SettingsSheet.kt` → toggle `Switch` dentro de la sección "Privacidad y
  datos", justo antes de "Eliminar cuenta": persiste en `SettingsStore` y
  llama a `setAnalyticsCollectionEnabled(!optOut)` al cambiar.
- `MainActivity.onCreate` → tras fijar `AndroidContextHolder.context` (lo que
  necesita la implementación Android), aplica la preferencia guardada con
  `setAnalyticsCollectionEnabled(!SettingsStore(Settings()).isAnalyticsOptOut())`
  — mismo patrón ya usado en este archivo para `householdStore` (instanciar
  `Settings()`/`SettingsStore` directamente antes de que Koin esté disponible,
  ver el comentario de `householdStore` en `MainActivity.kt`).

Sin cambios en el flujo de consentimiento TCF/UMP existente (`ConsentManager`):
este toggle es un opt-out adicional específico de Analytics, no sustituye el
consentimiento de anuncios en EEE/Reino Unido.

## B) GoogleSignInResultHolder → sealed class

`GoogleSignInResultHolder` (commonMain) migra su representación interna de
`String?` (`null` = en curso, `""` = cancelado, resto = idToken) a:

```kotlin
sealed class GoogleSignInResult {
    data object Loading : GoogleSignInResult()
    data class Success(val token: String) : GoogleSignInResult()
    data object Cancelled : GoogleSignInResult()
}
```

**Decisión de diseño — se mantuvo `setResult(token: String?)` sin cambiar su
firma pública.** El único consumidor de `GoogleSignInResultHolder.result` es
`GoogleAuthManager.kt` (el `init` que hacía `when { token == null -> ...;
token.isEmpty() -> ...; else -> ... }`, la parte realmente propensa a error del
patrón anterior): ahí sí se migró a `when (result) { is Loading -> ...; is
Cancelled -> ...; is Success -> ... }`, con matching exhaustivo del compilador
en vez de comparaciones `null`/`isEmpty()`.

Los 8 call-sites que *publican* el resultado (`setResult(...)`) se dejaron
intactos, porque siguen usando el mismo contrato `String?` que ya usa
`setResult` internamente para construir el `GoogleSignInResult`:

- `GoogleSignInHelper.kt` (Android) — 4 sitios (`launch`, `handleSignInResult` x3).
- `Platform.ios.kt:112` — 1 sitio (fallback si `openURL` falla).
- `Platform.jvm.kt:79` — 1 sitio (fin del flujo OAuth loopback).
- `Platform.wasmJs.kt:115` — 1 sitio (fin del polling de GIS).
- `iosApp/ContentView.swift:30` — `GoogleSignInResultHolder.shared.setResult(token: idToken ?? "")`.

Migrar además `setResult` para que acepte directamente el sealed class (en vez
de `String?`) habría obligado a tocar los 8 sitios en 4 lenguajes/plataformas
distintas — incluida la exportación Kotlin/Native del sealed class hacia
Swift (`GoogleSignInResultSuccess`/`GoogleSignInResultCancelled.shared` o
similar, según cómo el compilador aplane los objetos anidados), que **no se
puede compilar ni verificar en este entorno Linux (sin Xcode)**. El riesgo de
romper el build de iOS sin poder comprobarlo localmente no se justificaba
frente al beneficio: el problema real que motivaba la migración (la ambigüedad
`null` vs `""` en el *consumo*, con su historial de bugs documentado en el
KDoc de `GoogleAuthManager.kt`) ya queda resuelto migrando solo el lado que
consume el `StateFlow`.

**Pendiente de validar en Mac:** si en el futuro se decide migrar también
`setResult` a recibir `GoogleSignInResult` directamente (eliminando el
`String?` por completo), habrá que:
1. Comprobar cómo Xcode expone el sealed class Kotlin/Native a Swift (nombre
   de las subclases aplanadas, si los `data object` exponen `.shared`).
2. Actualizar `ContentView.swift:30` al tipo que resulte.
3. Recompilar el framework iOS (`./gradlew :composeApp:linkDebugFrameworkIosX64`
   o equivalente) y correr la app en simulador/dispositivo.

Ninguno de estos pasos era alcanzable en esta sesión (sin macOS/Xcode
disponibles) — por eso se optó por el diseño de menor riesgo de arriba, que
cumple el requisito "no cambiar el comportamiento observable de la app" con
certeza total en las 4 plataformas.

## Verificación

- `./gradlew :composeApp:compileDebugKotlinAndroid --console=plain` → `BUILD SUCCESSFUL`.
- `./gradlew :composeApp:jvmTest --rerun-tasks --console=plain` → `BUILD SUCCESSFUL`, 27 XMLs de resultado, 0 fallos.
- iOS/wasmJs no tienen suite de compilación en este entorno (sin Xcode/toolchain
  wasm configurados aquí) — los cambios en `Platform.ios.kt`/`Platform.wasmJs.kt`
  son no-ops en la firma (`setResult(String?)` sin tocar), y `Analytics.ios.kt`/
  `Analytics.wasmJs.kt` añaden un `actual fun` no-op siguiendo el patrón exacto
  ya existente de `logAnalyticsEvent` en esos mismos archivos.

## Commit

`feat: analytics opt-out + GoogleSignInResultHolder sealed class` — sin push.
