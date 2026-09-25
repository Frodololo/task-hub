/**
 * `actual` Android de las declaraciones sueltas de [Platform.kt]: usa
 * `Intent.ACTION_SEND` para compartir, `SharedPreferences` para la caché del
 * widget, los helpers de Google Sign-In/Calendar del módulo Android y
 * `java.security.SecureRandom` como CSPRNG.
 */
package org.taskhub.platform

import android.content.Context
import android.content.Intent
import android.app.Activity
import org.taskhub.TaskHubWidgetProvider
import org.taskhub.GoogleSignInHelper
import org.taskhub.GoogleCalendarAuthHelper
import java.security.SecureRandom

/**
 * Comparte [text] con el chooser nativo de Android (`Intent.ACTION_SEND`).
 * Devuelve `false`: el propio chooser ya es la confirmación visual, no hace
 * falta una adicional — ver KDoc del `expect`.
 */
actual fun shareText(text: String, title: String): Boolean {
    val context = AndroidContextHolder.context ?: return false
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, title)
    }
    // AndroidContextHolder.context es el applicationContext (no una Activity),
    // así que startActivity() exige FLAG_ACTIVITY_NEW_TASK. Sin ella lanza
    // AndroidRuntimeException y la app se cierra al pulsar "Compartir".
    val chooser = Intent.createChooser(sendIntent, title).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
    return false
}

actual val hasHomeScreenWidget: Boolean = true

actual val hasCalendarSupport: Boolean = true

actual val hasNotificationSupport: Boolean = true

/** Guarda el tema del widget en SharedPreferences ("widget_cache"), leídas por [TaskHubWidgetProvider]. */
actual fun saveWidgetThemeToCache(theme: String) {
    val context = AndroidContextHolder.context ?: return
    context.getSharedPreferences("widget_cache", Context.MODE_PRIVATE)
        .edit()
        .putString("widget_theme", theme)
        .apply()
}

/**
 * Guarda la lista de tareas pendientes en SharedPreferences y notifica al
 * widget con un broadcast propio (`org.taskhub.WIDGET_REFRESH`) para que
 * [TaskHubWidgetProvider] se repinte con los datos nuevos.
 */
actual fun updateWidgetPendingTasks(taskList: String) {
    val context = AndroidContextHolder.context ?: return
    context.getSharedPreferences("widget_cache", Context.MODE_PRIVATE)
        .edit()
        .putString("pending_tasks", taskList)
        .apply()
    val intent = Intent(context, TaskHubWidgetProvider::class.java).apply {
        action = "org.taskhub.WIDGET_REFRESH"
    }
    context.sendBroadcast(intent)
}

/** Delega en [GoogleSignInHelper], que gestiona el flujo nativo de Google Sign-In. */
actual fun launchGoogleSignIn() {
    val context = AndroidContextHolder.context ?: return
    GoogleSignInHelper.launch(context)
}

/** Android no distingue motivos de fallo — ver KDoc de [consumeLastSignInFailureReason] en Platform.kt. */
actual fun consumeLastSignInFailureReason(): String? = null

/**
 * No-op: el selector de cuenta nativo de Android es una Activity del sistema
 * sin ninguna API para cerrarla mediante `GoogleSignInHelper` desde aquí, y
 * resuelve casi al instante por su cuenta (éxito o cancelación) — ver KDoc
 * del `expect` en Platform.kt.
 */
actual fun cancelGoogleSignIn() {
    // Android: sin forma programática de cerrar el selector nativo — no-op.
}

/** Delega en [GoogleCalendarAuthHelper] para obtener/refrescar el access token de Calendar. */
actual suspend fun getGoogleCalendarAccessToken(): String? {
    val context = AndroidContextHolder.context ?: return null
    return GoogleCalendarAuthHelper.getAccessToken(context)
}

/** Delega en [GoogleSignInHelper] para revocar el consentimiento OAuth concedido. */
actual suspend fun revokeGoogleCalendarAccess() {
    val context = AndroidContextHolder.context ?: return
    GoogleSignInHelper.revokeAccess(context)
}

private val secureRandom = SecureRandom()

/** Implementación Android: delega en `java.security.SecureRandom`, un CSPRNG del JDK. */
actual fun secureRandomInt(bound: Int): Int = secureRandom.nextInt(bound)

/** Contenedor estático simple del contexto/activity, fijado desde MainActivity. */
object AndroidContextHolder {
    @Volatile
    var context: Context? = null

    /**
     * Activity actual en primer plano (null si no hay ninguna).
     * Se setea en MainActivity.onCreate y se limpia en onDestroy.
     * Se usa para mostrar el interstitial de AdMob, que requiere una Activity.
     */
    @Volatile
    var activity: Activity? = null
}