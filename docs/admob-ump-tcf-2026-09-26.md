# AdMob UMP/TCF — consentimiento UE (2026-09-26)

## Motivación

Riesgo de suspensión de la cuenta de AdMob en EEE/Reino Unido sin un CMP compatible con TCF v2. Aprobado por Liberto (2026-09-26).

## Cambios

- **`composeApp/build.gradle.kts`**: añadida dependencia `com.google.android.ump:user-messaging-platform:3.1.0` en `androidMain`.
- **`platform/ConsentManager.kt` (nuevo, androidMain)**: pide/actualiza la info de consentimiento vía `UserMessagingPlatform.requestConsentInfoUpdate`, muestra el formulario TCF v2 con `loadAndShowConsentFormIfRequired` si la región lo exige, y solo si `consentInformation.canRequestAds()` es `true` inicializa AdMob (`MobileAds.initialize()`, en hilo de fondo como antes) y avisa a `AdControllerImpl.onConsentReady()` para precargar el interstitial. Incluye (comentado, no activo) un `resetForTesting()` para forzar el formulario en desarrollo.
- **`TaskHubApplication.kt`**: eliminada la llamada a `MobileAds.initialize()` (antes incondicional, en `Application.onCreate()`). Se mantiene `MobileAds.setRequestConfiguration()` (TFCD + rating "G" para contenido infantil) porque es solo configuración local, sin llamada de red, y debe fijarse antes de `initialize()` igual que antes.
- **`MainActivity.kt`**: `ConsentManager.requestConsent(this)` se llama en `onCreate()`, tras registrar los launchers de Google Sign-In/Calendar y antes del resto de arranque (permiso de notificaciones, in-app update, `setContent`).
- **`AdController.android.kt`**: se quita el `init { loadInterstitial() }` que cargaba un interstitial de forma incondicional al primer acceso al objeto. Ahora:
  - `loadInterstitial()` (ahora también expuesta como `onConsentReady()`) no hace nada si `ConsentManager.canRequestAds` es `false`.
  - `maybeShowInterstitial()` es no-op sin consentimiento.
  - `isBannerEnabled()` exige `AdConfig.bannerEnabled && ConsentManager.canRequestAds`.

## No tocado

- iOS y JVM (AdMob no existe ahí).
- `firestore.rules`, sin despliegues.
- Lógica de anuncios más allá del gate de consentimiento (cooldown, TFCD/rating, IDs de test).

## Verificación

1. `./gradlew :composeApp:compileDebugKotlinAndroid --console=plain` → `BUILD SUCCESSFUL`.
2. `./gradlew :composeApp:jvmTest --rerun-tasks --console=plain` → `BUILD SUCCESSFUL`; los 27 XML en `composeApp/build/test-results/jvmTest/` reportan `failures="0" errors="0"`.

## Pendiente / notas

- La versión de UMP (`3.1.0`) es la indicada en la tarjeta; no se verificó contra Maven Central si hay una más reciente (sin acceso a red para comprobarlo en esta sesión).
- Sin commit todavía (SIN push, según instrucción de la tarjeta).
