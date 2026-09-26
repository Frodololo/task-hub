// Clase `Application` de Task Hub para Android: punto de arranque del
// proceso, antes de cualquier Activity o composición de Compose. Configura
// AdMob (con las restricciones de contenido infantil del hogar) y registra
// el sondeo periódico de notificaciones ([NotificationPollWorker]) para que
// siga funcionando aunque la app no esté en primer plano.
package org.taskhub

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.firebase.FirebaseApp
import java.util.concurrent.TimeUnit

/**
 * Application principal de Task Hub.
 *
 * Firebase se inicializa automáticamente vía el plugin google-services
 * (FirebaseInitProvider). Crashlytics también se auto-inicializa y reporta
 * los crashes de forma automática.
 *
 * Mantenemos esta clase para controlar la inicialización y para un punto
 * central donde añadir futura configuración (p. ej. desactivar Crashlytics
 * en debug).
 */
class TaskHubApplication : Application() {

    /**
     * Se ejecuta una única vez al crear el proceso, antes de cualquier
     * Activity. Inicializa Firebase, fija la configuración de contenido apto
     * para menores de AdMob (la inicialización del SDK en sí se difiere a
     * ConsentManager, ver más abajo) y deja programado el sondeo periódico de
     * notificaciones.
     */
    override fun onCreate() {
        super.onCreate()

        // Asegurar que FirebaseApp se inicialice (normalmente ya lo hace
        // FirebaseInitProvider antes de onCreate, pero es idempotente).
        FirebaseApp.initializeApp(this)

        // Task Hub es una app de uso familiar con perfiles infantiles ("Miembro"
        // con role="child", ver docs/privacy.html) — señalizar todo el
        // inventario de anuncios como dirigido a menores para que AdMob NO
        // sirva publicidad conductual/personalizada, con independencia de qué
        // perfil esté activo en el dispositivo (panel de revisión 2026-09-03,
        // Experto 10, CRÍTICO #2). Debe fijarse ANTES de MobileAds.initialize().
        // TFCD por sí solo evita anuncios personalizados/conductuales, pero no
        // garantiza que el CONTENIDO del anuncio sea apto para audiencia
        // infantil — se fija explícitamente la clasificación máxima "G" (panel
        // 2026-09-04, Experto 10).
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE)
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .build()
        )

        // La inicialización de MobileAds.initialize() se difiere a
        // ConsentManager (ver ConsentManager.android.kt), que la dispara solo
        // tras obtener el consentimiento TCF v2 vía UMP — requiere una
        // Activity y se llama desde MainActivity.onCreate(), no aquí. Esta
        // configuración (TFCD/rating) es solo metadatos locales, sin llamada
        // de red, así que puede fijarse antes de esa gate sin riesgo de
        // cumplimiento.

        scheduleNotificationPolling()
    }

    /**
     * Sondeo periódico de "tarea asignada"/"mensaje nuevo" (ver
     * [NotificationPollWorker]) — sin backend propio ni Cloud Functions no
     * hay forma de un push FCM dirigido real, así que esto es lo que entrega
     * la notificación al dispositivo cuando la app no está en primer plano
     * (panel de notificaciones 2026-09-05, gap B).
     *
     * `enqueueUniquePeriodicWork` + `KEEP`: se registra una única vez por
     * instalación (cada arranque del proceso vuelve a llamar a esto, pero
     * KEEP no reemplaza el trabajo ya en cola, solo lo crea si falta). 30 min
     * balancea batería vs. frescura — WorkManager no permite menos de 15 min
     * para trabajo periódico.
     */
    private fun scheduleNotificationPolling() {
        val request = PeriodicWorkRequestBuilder<NotificationPollWorker>(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "notification_poll",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}