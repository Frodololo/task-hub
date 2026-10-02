package org.taskhub.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

/**
 * Implementación iOS del `expect` [shareText] (`platform/Platform.kt`) con el
 * share sheet nativo (`UIActivityViewController`).
 *
 * `title` no tiene un equivalente directo en `UIActivityViewController`
 * (no acepta asunto/título como tal, a diferencia del Intent de Android) —
 * se ignora, igual que en wasmJs. Devuelve `false`: la propia hoja nativa ya
 * es la confirmación visual — ver KDoc del `expect`.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun shareText(text: String, title: String): Boolean {
    val rootViewController = topMostViewController() ?: return false
    // Cast documentado de Kotlin/Native: kotlin.String es toll-free-bridged a
    // NSString en interop con Objective-C, por lo que `as NSString` es válido
    // y necesario aquí porque `activityItems` espera un `List<Any>` de tipos
    // Objective-C (id), no un `String` de Kotlin.
    val activityViewController = UIActivityViewController(
        activityItems = listOf(text as NSString),
        applicationActivities = null,
    )
    // En iPad, UIActivityViewController se presenta en un popover que exige
    // un ancla (sourceView/sourceRect); sin esto la app crashea en iPad. En
    // iPhone estas propiedades simplemente no se usan (present modal normal).
    activityViewController.popoverPresentationController?.let { popover ->
        popover.sourceView = rootViewController.view
        popover.sourceRect = rootViewController.view.bounds
    }
    rootViewController.presentViewController(activityViewController, animated = true, completion = null)
    return false
}

/**
 * Recorre `presentedViewController` desde la ventana principal hasta el
 * controlador visible más arriba.
 *
 * Toma la primera ventana de la primera escena conectada
 * (`windows.filterIsInstance<UIWindow>().firstOrNull()`), en vez de filtrar
 * por "key window": `UIWindow.isKeyWindow`/`keyWindow` existen en el SDK
 * real de iOS, pero no están expuestos en el binding de Kotlin/Native de
 * este `platform.UIKit` (confirmado: ni siquiera el import
 * `platform.UIKit.keyWindow` resuelve) — para una app de una sola ventana
 * como esta (sin soporte de Stage Manager/multi-window), la primera ventana
 * de la escena ES la key window en la práctica, así que el resultado es el
 * mismo sin depender de un símbolo no disponible. `windows` además devuelve
 * `List<*>` (sin tipo genérico concreto en este binding — confirmado
 * forzando un error de tipos deliberado contra el compilador), así que hace
 * falta `filterIsInstance<UIWindow>()` para recuperar el tipo concreto en
 * vez de un simple cast `as UIWindow` elemento a elemento.
 */
private fun topMostViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull()?.windows?.filterIsInstance<UIWindow>()?.firstOrNull()
    var topController = window?.rootViewController
    while (topController?.presentedViewController != null) {
        topController = topController.presentedViewController
    }
    return topController
}

/** Todavía no existe un widget de iOS (WidgetKit). */
actual val hasHomeScreenWidget: Boolean = false

/** iOS: Google Sign-In ya funciona (ver [launchGoogleSignIn]), pero la integración con Calendar no está implementada todavía. */
actual val hasCalendarSupport: Boolean = false

/** iOS: createNotificationScheduler() devuelve NoOpNotificationScheduler. */
actual val hasNotificationSupport: Boolean = false

/**
 * Implementación iOS del `expect` [saveWidgetThemeToCache] (`platform/Platform.kt`).
 *
 * No-op: todavía no existe un widget de iOS (WidgetKit) que consuma este tema.
 */
actual fun saveWidgetThemeToCache(theme: String) {
    // iOS: no widget cache yet — no-op for now
}

/**
 * Implementación iOS del `expect` [updateWidgetPendingTasks] (`platform/Platform.kt`).
 *
 * No-op: todavía no existe un widget de iOS (WidgetKit) que consuma esta lista.
 */
actual fun updateWidgetPendingTasks(taskList: String) {
    // iOS: no widget yet — no-op
}

/**
 * Implementación iOS del `expect` [launchGoogleSignIn] (`platform/Platform.kt`).
 *
 * Sin SDK de Google (no se añade GIDSignIn ni Firebase SDK): se abre Safari
 * con la URL de autorización OAuth 2.0 de Google (authorization-code + PKCE,
 * [GoogleIosSignInHelper]), usando el client ID de tipo iOS
 * ([GOOGLE_IOS_CLIENT_ID]). El callback llega por URL scheme
 * ([GOOGLE_IOS_REVERSED_CLIENT_ID]) y lo procesa `ContentView.swift`
 * (`onOpenURL`), que lo reenvía a [GoogleIosSignInHelper.processCallback] —
 * este método NO bloquea ni resuelve el resultado, solo lanza la URL. Único
 * caso en el que sí publica resultado directamente: si `openURL` falla al
 * instante (Safari no se pudo abrir), para no dejar el flujo colgado en
 * SigningIn.
 *
 * Se usó antes el flujo implícito (`response_type=id_token` directo, sin
 * `code`): Google lo rechaza con `Error 400: unsupported_response_type` para
 * clientes OAuth de tipo iOS (deprecado por Google, no es un bug de esta
 * app) — de ahí el authorization-code + PKCE de [GoogleIosSignInHelper].
 */
actual fun launchGoogleSignIn() {
    // runCatching: generar el code_verifier/code_challenge depende del
    // CSPRNG del sistema (SecRandomCopyBytes), que en teoría puede fallar
    // (ver randomUrlSafeString) — si eso pasa, se publica "" en vez de
    // dejar propagar la excepción y crashear la app.
    val authUrl = runCatching { GoogleIosSignInHelper.buildAuthorizationUrl() }.getOrNull()
    if (authUrl == null) {
        GoogleIosSignInHelper.clearPending()
        GoogleSignInResultHolder.setResult("")
        return
    }
    // NSURL(string:) no es nullable en este binding de Kotlin/Native
    // (a diferencia del inicializador failable de Swift `NSURL(string:)?`).
    val url = NSURL(string = authUrl)
    // La variante síncrona de un solo parámetro (`openURL(url)`, deprecada
    // desde iOS 10) dejó de abrir Safari en SDKs recientes: iOS la
    // "hard-failea" devolviendo `false` siempre, sin ni intentar abrir la
    // URL (log de sistema: "BUG IN CLIENT OF UIKIT: ... Force returning
    // false (NO)"). Se usa la variante moderna con `completionHandler`; el
    // booleano que recibe indica si Safari pudo abrirse, no si el login
    // tuvo éxito — solo publicamos resultado aquí cuando falla, para no
    // dejar el flujo colgado en SigningIn.
    UIApplication.sharedApplication.openURL(
        url = url,
        options = emptyMap<Any?, Any>(),
        completionHandler = { opened ->
            if (!opened) {
                GoogleIosSignInHelper.clearPending()
                GoogleSignInResultHolder.setResult("")
            }
        },
    )
}

/** iOS no distingue motivos de fallo — ver KDoc de [consumeLastSignInFailureReason] en Platform.kt. */
actual fun consumeLastSignInFailureReason(): String? = null

/**
 * No-op: el flujo abre Safari (`openURL`) y no hay forma de cerrarlo desde
 * aquí — ver KDoc del `expect` en Platform.kt.
 */
actual fun cancelGoogleSignIn() {
    // iOS: sin forma programática de cerrar Safari, pero sí se puede
    // descartar el code_verifier pendiente y liberar el resultado para no
    // dejar el flujo colgado en SigningIn si el usuario cancela desde la UI.
    GoogleIosSignInHelper.clearPending()
    GoogleSignInResultHolder.setResult("")
}

/**
 * Implementación iOS del `expect` [getGoogleCalendarAccessToken] (`platform/Platform.kt`).
 *
 * No-op: aunque Google Sign-In ya funciona en iOS (ver [launchGoogleSignIn]
 * arriba), la integración con Calendar no está implementada — el idToken
 * obtenido en el login no da acceso a la API de Calendar (haría falta un
 * scope adicional y su propio flujo de consentimiento, fuera de alcance).
 * Siempre devuelve null.
 */
actual suspend fun getGoogleCalendarAccessToken(): String? {
    // iOS: integración con Calendar no implementada — no-op
    return null
}

/**
 * Implementación iOS del `expect` [revokeGoogleCalendarAccess] (`platform/Platform.kt`).
 *
 * No-op por el mismo motivo que [getGoogleCalendarAccessToken]: la
 * integración con Calendar no está implementada, así que no hay
 * consentimiento OAuth de Calendar que revocar.
 */
actual suspend fun revokeGoogleCalendarAccess() {
    // iOS: integración con Calendar no implementada — no-op
}

/**
 * Genera un entero uniforme en [0, bound) usando el CSPRNG del sistema
 * (Security.framework), con rejection sampling (mismo algoritmo que
 * `java.util.Random.nextInt(bound)`/Android `SecureRandom.nextInt(bound)`).
 *
 * La implementación anterior pedía un solo byte y aplicaba `% bound`: con
 * bound=36 (alfabeto del código de invitación) eso sesgaba ~14% a favor de
 * los primeros 4 caracteres del alfabeto (256 % 36 = 4), y para bound > 256
 * jamás podía devolver valores >= 256. El rejection sampling sobre 31 bits
 * evita ambos problemas.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomInt(bound: Int): Int {
    require(bound > 0) { "bound debe ser positivo" }
    val bytes = ByteArray(4)
    var bits: Int
    var value: Int
    do {
        val status = SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), bytes.refTo(0))
        check(status == 0) { "SecRandomCopyBytes falló con status $status" }
        bits = (((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)) and 0x7fffffff
        value = bits % bound
    } while (bits - value + (bound - 1) < 0)
    return value
}