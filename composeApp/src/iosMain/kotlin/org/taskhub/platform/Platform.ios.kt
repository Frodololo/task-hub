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

/**
 * Implementación iOS del `expect` [shareText] (`platform/Platform.kt`) con el
 * share sheet nativo (`UIActivityViewController`).
 *
 * `title` no tiene un equivalente directo en `UIActivityViewController`
 * (no acepta asunto/título como tal, a diferencia del Intent de Android) —
 * se ignora, igual que en wasmJs. Devuelve `false`: la propia hoja nativa ya
 * es la confirmación visual — ver KDoc del `expect`.
 */
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

/** Recorre `presentedViewController` desde la key window hasta el controlador visible más arriba. */
private fun topMostViewController(): UIViewController? {
    var topController = UIApplication.sharedApplication.keyWindow?.rootViewController
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
 * con la URL de autorización OAuth 2.0 de Google pidiendo un `id_token`
 * directamente (implicit flow), usando el client ID de tipo iOS
 * ([GOOGLE_IOS_CLIENT_ID]). El callback llega por URL scheme
 * ([GOOGLE_IOS_REVERSED_CLIENT_ID]) y lo procesa Swift en
 * `ContentView.swift` (`onOpenURL`), que publica el resultado aquí mismo
 * vía [GoogleSignInResultHolder] — este método NO bloquea ni resuelve el
 * resultado, solo lanza la URL. Único caso en el que sí publica resultado:
 * si `openURL` falla al instante (Safari no se pudo abrir), para no dejar
 * el flujo colgado en SigningIn.
 */
actual fun launchGoogleSignIn() {
    val nonce = (1..32).joinToString("") { secureRandomInt(16).toString(16) }
    val redirectUri = "$GOOGLE_IOS_REVERSED_CLIENT_ID:/oauth2redirect"
    val authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
        "?client_id=$GOOGLE_IOS_CLIENT_ID" +
        "&redirect_uri=$redirectUri" +
        "&response_type=id_token" +
        "&scope=openid%20email%20profile" +
        "&nonce=$nonce" +
        "&prompt=select_account"
    val url = NSURL(string = authUrl)
    // Se usa la variante síncrona (deprecada desde iOS 10, pero todavía
    // soportada) de `openURL` en vez de la moderna con `options`/
    // `completionHandler`: su firma de interop con Kotlin/Native no se ha
    // podido validar en este entorno (sin Xcode/klibs de iOS disponibles) y
    // esta variante es sencilla y de comportamiento bien conocido. El valor
    // de retorno indica si Safari pudo abrirse, no si el login tuvo éxito.
    val opened = url != null && UIApplication.sharedApplication.openURL(url)
    if (!opened) {
        GoogleSignInResultHolder.setResult("")
    }
}

/** iOS no distingue motivos de fallo — ver KDoc de [consumeLastSignInFailureReason] en Platform.kt. */
actual fun consumeLastSignInFailureReason(): String? = null

/**
 * No-op: el flujo abre Safari (`openURL`) y no hay forma de cerrarlo desde
 * aquí — ver KDoc del `expect` en Platform.kt.
 */
actual fun cancelGoogleSignIn() {
    // iOS: sin forma programática de cerrar Safari — no-op.
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