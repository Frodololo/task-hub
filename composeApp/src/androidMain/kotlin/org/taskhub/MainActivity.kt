package org.taskhub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import org.taskhub.platform.AppLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.russhwolf.settings.Settings
import org.taskhub.platform.AndroidNotificationScheduler
import org.taskhub.platform.AndroidSchedulerHolder
import org.taskhub.platform.AndroidContextHolder
import org.taskhub.platform.DebugFlags
import org.taskhub.storage.HouseholdStore
import org.taskhub.BuildConfig

/**
 * Activity única de Task Hub — punto de entrada Android que aloja la raíz de
 * Compose ([App]). En `onCreate` deja listos los holders estáticos de
 * contexto/Activity que usan los helpers de plataforma (AdMob, notificaciones,
 * Google Sign-In/Calendar), registra los launchers de permisos/login/
 * actualización, y resuelve el deep link de notificaciones (ver
 * [consumeDeepLink]) antes de montar Compose.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // Resultado del permiso POST_NOTIFICATIONS ignorado a propósito: la app
    // funciona igual con o sin notificaciones locales, solo cambia si
    // NotificationHelper puede llegar a mostrarlas (ver
    // NotificationHelper.canShowNotifications).
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permissions result — notifications will work or skip */ }

    // Deep link de tocar una notificación local (tarea asignada, mensaje
    // nuevo, recordatorio de tarea) — ver `NotificationHelper`/
    // `NotificationPollWorker`/`TaskReminderScheduler`, todos ponen
    // "householdId"/"taskId" como extras del mismo Intent hacia esta
    // Activity. `mutableStateOf` (no un simple `var`) porque `onNewIntent`
    // puede llegar con la Activity ya compuesta (FLAG_ACTIVITY_CLEAR_TOP con
    // launchMode "standard" reutiliza la instancia en vez de recrearla) — el
    // árbol de Compose necesita observar el cambio para volver a navegar.
    private var deepLinkHouseholdId by mutableStateOf<String?>(null)
    private var deepLinkTaskId by mutableStateOf<String?>(null)
    private var deepLinkNotificationId by mutableStateOf<String?>(null)

    // Instancia compartida para toda la vida de la Activity — `consumeDeepLink`
    // se llama tanto desde `onCreate` como desde cada `onNewIntent` (una
    // notificación nueva con la Activity ya viva), y antes creaba un
    // `HouseholdStore(Settings())` nuevo en cada llamada. El Koin de la app
    // vive solo dentro del árbol de Compose (`KoinApplication` en `App.kt`, ver
    // su comentario) y no está disponible aquí, antes de `setContent`, así que
    // no se puede inyectar vía Koin sin mover su instalación fuera de Compose
    // — cambio de mayor riesgo, descartado por ahora (panel de revisión
    // 2026-09-11). `Settings()` sin argumentos es segura de invocar aquí (ver
    // comentario de `NotificationPollWorker`): mismo backing store que usa Koin.
    private val householdStore by lazy { HouseholdStore(Settings()) }

    /**
     * Extrae de [intent] los extras de deep link puestos por una notificación
     * local (ver el comentario de las propiedades `deepLink*` arriba) y
     * actualiza el estado observable por Compose. Se llama tanto desde
     * `onCreate` (arranque en frío) como desde [onNewIntent] (Activity ya
     * viva, reutilizada por `FLAG_ACTIVITY_CLEAR_TOP`).
     */
    private fun consumeDeepLink(intent: Intent?) {
        val householdId = intent?.getStringExtra("householdId")?.ifBlank { null }
        // MainActivity es `exported` (obligatorio por el intent-filter
        // LAUNCHER) sin comprobación de firmante: cualquier app instalada en
        // el dispositivo puede lanzar un Intent explícito con extras
        // arbitrarios. Antes de confiar en ellos, comprobamos que el hogar
        // corresponde a uno que este dispositivo ya conoce — un deep link
        // legítimo (notificación propia) siempre apunta a un hogar guardado
        // localmente (panel de revisión 2026-09-10, Experto 9, MENOR).
        val isKnownHousehold = householdId != null &&
            householdStore.getSavedHouseholds().any { it.id == householdId }
        if (isKnownHousehold) {
            deepLinkHouseholdId = householdId
            deepLinkTaskId = intent?.getStringExtra("taskId")
            deepLinkNotificationId = intent?.getStringExtra("notificationId")
        } else {
            deepLinkHouseholdId = null
            deepLinkTaskId = null
            deepLinkNotificationId = null
        }
    }

    /**
     * Launcher para el flujo de actualización in-app (Play Core).
     *
     * El modo IMMEDIATE muestra un diálogo de pantalla completa que bloquea la
     * app hasta que la actualización se descarga e instala (o el usuario la
     * cancela). Si el usuario cancela, forzamos el cierre: no se debe seguir
     * ejecutando una versión obsoleta.
     */
    private val appUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        when {
            result.resultCode == Activity.RESULT_OK -> {
                // Aceptada: el sistema gestiona la descarga/instalación y reinicia la app.
            }
            result.resultCode == Activity.RESULT_CANCELED -> {
                // El usuario canceló la actualización — forzamos el cierre: no se debe
                // seguir ejecutando una versión obsoleta.
                AppLog.w(TAG, "In-App Update: cancelada por el usuario. Cerrando la app.")
                finish()
            }
            else -> {
                // Fallo de la actualización (p. ej. RESULT_IN_APP_UPDATE_FAILED) o
                // cualquier otro código: NO forzamos el cierre, para no dejar la app
                // en un bucle de cierre. Se reintentará en el siguiente arranque.
                AppLog.w(TAG, "In-App Update: fallo (resultCode=${result.resultCode}). Continuando.")
            }
        }
    }

    /**
     * Secuencia de arranque: tema y edge-to-edge, holders de contexto/
     * Activity, registro de los launchers de Google Sign-In/Calendar, flag de
     * debug, inicialización del scheduler de notificaciones y su canal,
     * permiso de notificaciones (Android 13+), comprobación de actualización
     * in-app, resolución del deep link inicial y, por último, montaje de
     * [App] con ese deep link como parámetro.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Switch from splash theme to normal app theme before rendering
        setTheme(R.style.Theme_TaskHub)

        enableEdgeToEdge()

        // Hold a static reference to the app context for platform helpers
        AndroidContextHolder.context = applicationContext

        // Hold a reference to the current Activity (para mostrar el interstitial de AdMob)
        AndroidContextHolder.activity = this

        // Registrar el launcher de Google Sign-In (requisito para el login con Google)
        GoogleSignInHelper.register(this)

        // Registrar el launcher de consentimiento OAuth para el scope de Calendar
        GoogleCalendarAuthHelper.register(this)

        // Set debug mode from BuildConfig (false in release builds)
        DebugFlags.isEnabled = BuildConfig.DEBUG

        // Initialize the notification scheduler
        AndroidSchedulerHolder.scheduler = AndroidNotificationScheduler(applicationContext)

        // Create FCM notification channel early
        NotificationHelper.createNotificationChannel(applicationContext)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Comprobar si hay una actualización disponible en Play Store.
        // Solo se dispara en builds instalados desde Play Store; un debug
        // sideload no reporta actualización (esperado) y NO bloquea la ejecución.
        checkForInAppUpdate()

        consumeDeepLink(intent)

        setContent {
            App(
                deepLinkHouseholdId = deepLinkHouseholdId,
                deepLinkTaskId = deepLinkTaskId,
                deepLinkNotificationId = deepLinkNotificationId
            )
        }
    }

    /**
     * Nueva notificación tocada con la Activity ya en memoria (launchMode
     * "standard" + `FLAG_ACTIVITY_CLEAR_TOP` reutiliza la instancia en vez de
     * recrearla). Actualiza el Intent de la Activity y vuelve a extraer el
     * deep link para que Compose reaccione al cambio de estado.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDeepLink(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Limpiar la referencia a la Activity para evitar leaks
        AndroidContextHolder.activity = null
    }

    /**
     * Consulta [AppUpdateManager] y, si hay una actualización disponible que
     * permite el modo IMMEDIATE, lanza el flujo de actualización forzada.
     *
     * Si no hay actualización o falla la consulta, la app continúa con normalidad
     * (solo se registra en log).
     */
    private fun checkForInAppUpdate() {
        val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(this)

        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                ) {
                    Log.i(TAG, "In-App Update: actualización inmediata disponible. Lanzando flujo.")
                    val started = appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        appUpdateLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                    if (!started) {
                        AppLog.w(TAG, "In-App Update: no se pudo lanzar el flujo de actualización.")
                    }
                } else {
                    Log.i(TAG, "In-App Update: sin actualización disponible (o no permite IMMEDIATE).")
                }
            }
            .addOnFailureListener { e ->
                AppLog.w(TAG, "In-App Update: error al consultar appUpdateInfo", e)
            }
    }
}
