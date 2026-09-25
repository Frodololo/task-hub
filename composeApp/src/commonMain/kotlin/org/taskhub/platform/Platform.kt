/**
 * Colección de puentes expect/actual sin agrupar en su propia interfaz:
 * compartir texto, caché del widget, Google Sign-In/Calendar, aleatoriedad
 * segura y flags de debug. Cada `actual` vive en el `*Main` de su plataforma.
 */
package org.taskhub.platform

import kotlin.concurrent.Volatile

/**
 * Comparte texto mediante la hoja de compartir nativa de cada plataforma.
 * Devuelve `true` si la plataforma no tiene una hoja de compartir nativa y en
 * su lugar copió [text] al portapapeles en silencio (hoy solo JVM/desktop) —
 * en ese caso el llamante debe mostrar su propia confirmación (p.ej. un
 * snackbar), porque copiar al portapapeles no da ninguna pista visual de que
 * ha ocurrido algo (a diferencia de abrir un chooser/share sheet, donde la UI
 * nativa ya es la confirmación). `false` en el resto de casos (Android/iOS: se
 * abrió la hoja nativa; web: no-op sin implementar todavía).
 */
expect fun shareText(text: String, title: String): Boolean

/** Guarda la preferencia de tema del widget en la caché específica de la plataforma. */
expect fun saveWidgetThemeToCache(theme: String)

/** Actualiza el widget con la lista actual de tareas pendientes (una por línea). */
expect fun updateWidgetPendingTasks(taskList: String)

/**
 * `true` solo en Android, la única plataforma con widget de pantalla de
 * inicio real — en el resto, [saveWidgetThemeToCache]/[updateWidgetPendingTasks]
 * son no-ops. Permite ocultar la sección "Tema del widget" de Ajustes donde
 * no tiene ningún efecto, en vez de dejar que el usuario configure algo que
 * no existe en su plataforma.
 */
expect val hasHomeScreenWidget: Boolean

/**
 * `true` solo donde [getGoogleCalendarAccessToken] puede devolver un token
 * real (Android/iOS) — en JVM/wasmJs está hardcodeado a `null` (soporte de
 * Calendar aún no implementado ahí). Permite ocultar la sección "Google
 * Calendar" de Ajustes donde vincular nunca puede tener éxito: sin esto, en
 * desktop el usuario completaba el flujo OAuth entero en el navegador (que sí
 * funciona) solo para que la app descartara el resultado al no poder pedir
 * el access token de Calendar.
 */
expect val hasCalendarSupport: Boolean

/**
 * `true` solo donde [org.taskhub.platform.createNotificationScheduler]
 * devuelve un scheduler real (Android, ver `NotificationScheduler.android.kt`)
 * — en iOS/JVM/wasmJs siempre devuelve `NoOpNotificationScheduler`. Permite
 * ocultar el interruptor "Notificaciones" de Ajustes donde no tiene ningún
 * efecto: sin esto, activar/desactivar ese interruptor en esas 3 plataformas
 * no cambiaba nada, sin ninguna pista de que los recordatorios locales no
 * están implementados ahí todavía.
 */
expect val hasNotificationSupport: Boolean

/** Lanza el flujo de Google Sign-In para vincular una cuenta de Google (integración con Calendar). */
expect fun launchGoogleSignIn()

/**
 * Aborta, si es posible, el flujo nativo de [launchGoogleSignIn] en curso —
 * usado por el botón "Cancelar" de `AuthGateScreen` durante
 * [org.taskhub.ui.models.GoogleAuthState.SigningIn], para no dejar al usuario
 * sin salida hasta que expire el timeout de
 * [org.taskhub.ui.models.GoogleAuthManager] (antes 60s, ahora alineado con el
 * peor caso real de 5 min del flujo OAuth de escritorio — ver
 * `GoogleDesktopSignInHelper.CALLBACK_TIMEOUT_MILLIS`).
 *
 * Solo JVM/wasmJs tienen un flujo de fondo de larga duración que cancelar de
 * verdad (navegador+socket loopback / sondeo de GIS): ahí, cancelar detiene
 * ese trabajo para que no publique un resultado tardío en
 * [GoogleSignInResultHolder] después de que el usuario ya haya vuelto a
 * [org.taskhub.ui.models.GoogleAuthState.SignedOut]. En Android/iOS, cuyo
 * flujo nativo (selector de cuenta) resuelve casi al instante por su cuenta,
 * es un no-op: no existe una forma programática de cerrar esa UI del sistema
 * desde aquí, y la ventana de una respuesta tardía tras cancelar es
 * insignificante en la práctica.
 */
expect fun cancelGoogleSignIn()

/**
 * Motivo específico (si se conoce) del último fallo/cancelación de
 * [launchGoogleSignIn] reportado con un token vacío, consumiéndolo (lecturas
 * repetidas devuelven `null` hasta el próximo fallo). `null` = sin motivo
 * específico (cancelación normal del usuario) o plataforma sin esta
 * distinción (Android/iOS/JVM: [GoogleSignInResultHolder] siempre ha
 * contratado token vacío = cancelado, sin más detalle).
 *
 * Solo wasmJs distingue motivos hoy — ver `Platform.wasmJs.kt` — porque solo
 * ahí el origin/carga de la librería (GIS) puede fallar de formas que no son
 * "el usuario canceló" (Android/iOS validan el cliente OAuth en tiempo de
 * compilación/consola de Firebase, no en cada intento).
 */
expect fun consumeLastSignInFailureReason(): String?

/**
 * Obtiene (o refresca de forma transparente) un **access token** OAuth de
 * Google Calendar para la cuenta vinculada, pidiendo consentimiento con UI
 * nativa si hace falta. Devuelve null si no hay cuenta vinculada o si no se
 * pudo obtener el token. De vida corta (~1h): pedirlo bajo demanda, no
 * tratarlo como duradero.
 */
expect suspend fun getGoogleCalendarAccessToken(): String?

/**
 * Revoca el consentimiento OAuth (idToken + scope de Calendar) concedido a
 * la app para la cuenta de Google vinculada actualmente — usado al eliminar
 * la cuenta (panel v4, Experto 10 hallazgo #5), para que la app deje de
 * tener acceso al Calendar del usuario tras el borrado. Best-effort: no
 * lanza si falla (offline, sin cuenta vinculada, etc.).
 */
expect suspend fun revokeGoogleCalendarAccess()

/**
 * Índice aleatorio criptográficamente seguro en [0, bound). Usar en vez de
 * kotlin.random.Random para valores con implicaciones de seguridad (p.ej.
 * códigos de invitación a hogar), ya que Random no es un CSPRNG y sus
 * salidas son predecibles a partir de pocas observaciones.
 */
expect fun secureRandomInt(bound: Int): Int

/**
 * Flag de debug — true en builds debug, false en release.
 * Se usa para condicionar logs con println() y elementos de UI de debug
 * (contador rojo, etc.). Se fija desde MainActivity en onCreate() vía
 * BuildConfig.DEBUG.
 */
object DebugFlags {
    @Volatile
    var isEnabled: Boolean = false
}