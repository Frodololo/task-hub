/**
 * `actual` web (wasmJs) de las declaraciones sueltas de [Platform.kt].
 * El navegador no tiene hoja de compartir nativa fiable ni widget de home
 * screen, así que `shareText` queda como no-op (log a consola) y el widget
 * no-op; Google Sign-In usa Google Identity Services (GIS, ver
 * `launchGoogleSignIn` más abajo — puente JS en `index.html`); Calendar sigue
 * sin soporte web (GIS solo cubre login, no scopes OAuth de Calendar);
 * `secureRandomInt` usa el CSPRNG del navegador (`crypto.getRandomValues`),
 * igual que el resto de plataformas.
 */
package org.taskhub.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Web: no hay Web Share API cableada todavía (queda como no-op con log a
 * consola). [title] no se usa.
 */
actual fun shareText(text: String, title: String) {
    AppLog.d("ShareText", "shareText not implemented on web: $title")
}

/** Web: no hay widget de home screen. */
actual val hasHomeScreenWidget: Boolean = false

/** Web: getGoogleCalendarAccessToken() está hardcodeado a null (ver abajo). */
actual val hasCalendarSupport: Boolean = false

/** Web: createNotificationScheduler() devuelve NoOpNotificationScheduler. */
actual val hasNotificationSupport: Boolean = false

/** Web: no hay widget de home screen — no-op. */
actual fun saveWidgetThemeToCache(theme: String) {
    // Web: no widget — no-op
}

/** Web: no hay widget de home screen — no-op. */
actual fun updateWidgetPendingTasks(taskList: String) {
    // Web: no widget — no-op
}

/**
 * Arranca el flujo GIS del lado JS (`__taskhubStartGoogleSignIn`, definido en
 * `index.html`) con el mismo Web Client ID que usa Android
 * ([org.taskhub.GoogleSignInHelper.WEB_CLIENT_ID]).
 */
@JsFun("(clientId) => { window.__taskhubStartGoogleSignIn(clientId); }")
private external fun jsStartGoogleSignIn(clientId: String)

/** `true` cuando el puente JS ya resolvió (éxito o cancelación/timeout de GIS). */
@JsFun("() => !!(window.__taskhubGis && window.__taskhubGis.state === 'done')")
private external fun jsGoogleSignInDone(): Boolean

/** idToken JWT si GIS tuvo éxito, o `""` si canceló/falló. Solo válido tras [jsGoogleSignInDone]. */
@JsFun("() => (window.__taskhubGis && window.__taskhubGis.value) ? window.__taskhubGis.value : ''")
private external fun jsGoogleSignInValue(): String

/**
 * Scope propio (no el de [org.taskhub.ui.models.GoogleAuthManager], que vive
 * en commonMain y no conoce este polling) para el sondeo de
 * [jsGoogleSignInDone] — se cancela y relanza en cada [launchGoogleSignIn]
 * para que una segunda invocación no dispare dos publicaciones a
 * [GoogleSignInResultHolder] (la guarda de reentrancia real ya vive en
 * `GoogleAuthManager.signIn()`, esto es solo higiene del propio scope).
 */
private val gisScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private var gisPollJob: Job? = null

/** Cada cuánto se sondea `window.__taskhubGis.state` mientras el prompt de GIS está abierto. */
private const val GIS_POLL_INTERVAL_MS = 200L

/**
 * Timeout de seguridad: si GIS ni resuelve con credential ni dispara
 * `isDismissedMoment`/`isSkippedMoment` (p.ej. un bloqueador de popups/cookies
 * de terceros que silencia el prompt sin notificar), esto evita que
 * `GoogleAuthManager` quede colgado en `SigningIn` para siempre.
 */
private const val GIS_TIMEOUT_MS = 120_000L

/**
 * Web: Google Sign-In real vía Google Identity Services (GIS). El puente
 * JS↔Kotlin/Wasm vive en `index.html` (`__taskhubStartGoogleSignIn` +
 * `window.__taskhubGis`) porque Kotlin/Wasm no puede pasar un lambda propio
 * como callback de una función JS externa de forma fiable — en su lugar, el
 * resultado se publica en una variable global y se sondea por polling desde
 * aquí. Al resolver (éxito, cancelación o timeout), publica el resultado en
 * [GoogleSignInResultHolder] con el mismo contrato que Android: idToken en
 * éxito, `""` en cancelación/fallo/timeout.
 */
actual fun launchGoogleSignIn() {
    gisPollJob?.cancel()
    jsStartGoogleSignIn(GOOGLE_WEB_CLIENT_ID)
    gisPollJob = gisScope.launch {
        var waited = 0L
        while (!jsGoogleSignInDone() && waited < GIS_TIMEOUT_MS) {
            delay(GIS_POLL_INTERVAL_MS)
            waited += GIS_POLL_INTERVAL_MS
        }
        val token = if (jsGoogleSignInDone()) jsGoogleSignInValue() else ""
        GoogleSignInResultHolder.setResult(token)
    }
}

/** Web: Google Sign-In no soportado todavía — siempre devuelve null (sin token). */
actual suspend fun getGoogleCalendarAccessToken(): String? {
    return null
}

/** Web: Google Sign-In no soportado todavía — no hay nada que revocar, no-op. */
actual suspend fun revokeGoogleCalendarAccess() {
    // Web: Google Sign-In not supported yet — no-op
}

/**
 * Índice aleatorio en [0, bound) usando `crypto.getRandomValues` del
 * navegador (Web Crypto API, disponible en todos los navegadores modernos
 * incluidos los que ejecutan Wasm) — antes usaba `kotlin.random.Random`, que
 * NO es un CSPRNG, para `inviteCode` (`HouseholdRepository.generateInviteCode`),
 * la única barrera para unirse a un hogar ajeno sin invitación explícita.
 * Sesgo de módulo despreciable para los `bound` pequeños usados hoy (36, el
 * tamaño del alfabeto de `inviteCode`).
 */
@JsFun("(bound) => { const arr = new Uint32Array(1); crypto.getRandomValues(arr); return Math.floor((arr[0] / 4294967296) * bound); }")
private external fun jsSecureRandomInt(bound: Int): Int

actual fun secureRandomInt(bound: Int): Int = jsSecureRandomInt(bound)
