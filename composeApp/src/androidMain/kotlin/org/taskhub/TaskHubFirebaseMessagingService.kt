package org.taskhub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.taskhub.platform.AppLog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Servicio FCM de Task Hub. El sistema instancia esta clase y llama a
 * [onNewToken] cuando se genera o rota el token de push del dispositivo, y a
 * [onMessageReceived] cuando llega un mensaje mientras el proceso de la app
 * está vivo (con el proceso muerto, Android construye la notificación
 * directamente desde el payload "notification" sin pasar por aquí).
 *
 * Hoy no hay backend propio ni Cloud Functions que envíen pushes dirigidos —
 * ver [org.taskhub.NotificationPollWorker] para el mecanismo real de entrega
 * (sondeo periódico) de "tarea asignada"/"mensaje nuevo". Este servicio queda
 * preparado (token guardado, canal creado) para cuando exista ese backend.
 */
class TaskHubFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "TaskHubFCM"
        const val CHANNEL_ID = "fcm_general"
    }

    /** Crea (si no existe) el canal de notificación FCM al arrancar el servicio. */
    override fun onCreate() {
        super.onCreate()
        createFcmChannel()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // No se loguea el token en sí: es una credencial de targeting de push
        // (permite enviar notificaciones a este dispositivo concreto).
        // AppLog.d ya no emite en release (ver DebugFlags.isEnabled), pero se
        // mantiene solo el largo por si acaso, no el valor.
        AppLog.d(TAG, "New FCM token received (length=${token.length})")
        // Antes solo se logueaba: el comentario decía "lo guarda la lógica
        // principal de la app", pero esa lógica no existía en ningún sitio
        // (verificado por grep) — ningún token llegaba nunca a persistirse,
        // así que las notificaciones push de "tarea asignada" no podían
        // funcionar. App.kt sube este valor a Firestore (users/{uid}) en el
        // arranque, una vez resuelta la identidad del usuario.
        org.taskhub.platform.AndroidSchedulerHolder.scheduler?.saveFcmToken(token)
    }

    /**
     * Se dispara con la app en primer o segundo plano cuando llega un mensaje
     * FCM con payload "notification": muestra una notificación del sistema
     * con el título/cuerpo recibidos. El payload "data" (si lo hay) solo se
     * loguea por ahora — no dispara ninguna acción personalizada.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        AppLog.d(TAG, "FCM message from ${message.from}")

        message.notification?.let { notification ->
            showNotification(
                title = notification.title ?: "Task Hub",
                body = notification.body ?: ""
            )
        }

        if (message.data.isNotEmpty()) {
            // Solo las CLAVES, no los valores: sin R8 que elimine Log en
            // release (ver KDoc de la clase), un backend futuro podría meter
            // aquí householdId/taskId/nombres — mismo criterio que ya se
            // aplica al token (arriba, se trunca a su longitud) pero que no
            // se había extendido a este payload (panel 2026-09-11, MENOR).
            AppLog.d(TAG, "Message data keys: ${message.data.keys}")
            // Handle data payload for custom actions
        }
    }

    /**
     * Construye y muestra la notificación del sistema en el canal
     * [CHANNEL_ID]. Al tocarla, reabre la app (o la trae a primer plano) sin
     * extras de deep link — a diferencia de [org.taskhub.NotificationHelper],
     * no dirige a ningún hogar/tarea concretos porque el payload FCM no
     * incluye esos IDs.
     */
    private fun showNotification(title: String, body: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(1001, notification)
        } catch (_: SecurityException) {
            AppLog.w(TAG, "Notification permission not granted")
        }
    }

    private fun createFcmChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Notificaciones generales",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificaciones push de Task Hub"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}