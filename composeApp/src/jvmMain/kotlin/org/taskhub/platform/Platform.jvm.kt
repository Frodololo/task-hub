/**
 * `actual` JVM (desktop) de las declaraciones sueltas de [Platform.kt].
 * Escritorio no tiene hoja de compartir nativa ni widget de home screen, así
 * que `shareText` cae a portapapeles y el widget queda no-op; Google
 * Sign-In usa el flujo OAuth loopback de [GoogleDesktopSignInHelper] (sin
 * SDK nativo en desktop); `secureRandomInt` sí usa un CSPRNG real
 * (`java.security.SecureRandom`).
 */
package org.taskhub.platform

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Sin hoja de compartir nativa en desktop: copia [text] al portapapeles del
 * sistema (java.awt) como fallback razonable. [title] no se usa (no hay
 * chooser al que ponerle asunto).
 */
actual fun shareText(text: String, title: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

/** JVM: no hay widget de escritorio. */
actual val hasHomeScreenWidget: Boolean = false

/** JVM: getGoogleCalendarAccessToken() está hardcodeado a null (ver abajo). */
actual val hasCalendarSupport: Boolean = false

/** JVM: createNotificationScheduler() devuelve NoOpNotificationScheduler. */
actual val hasNotificationSupport: Boolean = false

/** JVM: no hay widget de escritorio — no-op. */
actual fun saveWidgetThemeToCache(theme: String) {
    // JVM: no widget — no-op
}

/** JVM: no hay widget de escritorio — no-op. */
actual fun updateWidgetPendingTasks(taskList: String) {
    // JVM: no widget — no-op
}

/** Scope dedicado al flujo de sign-in (navegador + callback + intercambio de token), fuera del hilo de UI. */
private val signInScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private var signInJob: Job? = null

/**
 * Lanza el flujo OAuth 2.0 authorization-code + PKCE con redirect loopback
 * de [GoogleDesktopSignInHelper] en segundo plano. Cualquier fallo (config
 * vacía, sin navegador, timeout, error de red) se traduce en "sin token" —
 * nunca se deja [GoogleSignInResultHolder] sin resultado, para no colgar
 * `GoogleAuthManager` en `SigningIn` para siempre (mismo contrato que el
 * no-op anterior, ver `docs/macos-desktop-2026-09-13.md`).
 */
actual fun launchGoogleSignIn() {
    signInJob?.cancel()
    signInJob = signInScope.launch {
        val token = try {
            GoogleDesktopSignInHelper.signIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Config vacía (IllegalStateException) o fallo del flujo — ver KDoc
            // de GoogleDesktopSignInHelper.signIn. AppLog.e emite siempre
            // (a diferencia de AppLog.d): DebugFlags (Platform.kt) solo se
            // activa desde MainActivity de Android, así que en desktop
            // siempre está en false y AppLog.d no serviría de canal aquí.
            AppLog.e("GoogleSignIn", "Google Sign-In (desktop) falló", e)
            null
        }
        GoogleSignInResultHolder.setResult(token ?: "")
    }
}

/** JVM: Google Sign-In no soportado — siempre devuelve null (sin token). */
actual suspend fun getGoogleCalendarAccessToken(): String? {
    // JVM: Google Sign-In not supported — no-op
    return null
}

/** JVM: Google Sign-In no soportado — no hay nada que revocar, no-op. */
actual suspend fun revokeGoogleCalendarAccess() {
    // JVM: Google Sign-In not supported — no-op
}

private val secureRandom = SecureRandom()

/** Implementación JVM: delega en `java.security.SecureRandom`, un CSPRNG del JDK. */
actual fun secureRandomInt(bound: Int): Int = secureRandom.nextInt(bound)