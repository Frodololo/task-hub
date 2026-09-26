// Capa de persistencia (storage/): preferencias de usuario y credenciales
// de sesión. Combina [com.russhwolf.settings.Settings] (texto plano, para
// datos no sensibles) con [SecureStore] (cifrado, solo para tokens).

package org.taskhub.storage

import com.russhwolf.settings.Settings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists user settings: notifications, language, and theme preference.
 *
 * [secureStore] guarda SOLO el refresh token de Google cifrado — ver
 * [SecureStore] y el hallazgo de seguridad B1. Migración aditiva: si
 * un token todavía vive en texto plano en [settings] (versión anterior a
 * este cambio), se traslada automáticamente al leerlo por primera vez, sin
 * cerrar la sesión de usuarios ya autenticados.
 *
 * [secureStore] es perezoso (`by secureStoreProvider`): construirlo toca
 * Keystore/Keychain, trabajo que no hace falta para leer preferencias como
 * el idioma — antes se construía síncronamente en la primera composición de
 * `App()` (antes del splash) solo por ser un parámetro de constructor con
 * valor por defecto (panel v4, Experto 11 #6).
 */
class SettingsStore(
    private val settings: Settings,
    secureStoreProvider: Lazy<SecureStore> = lazy { createSecureStore() }
) {
    private val secureStore: SecureStore by secureStoreProvider

    private val json = Json { ignoreUnknownKeys = true }

    // ── Notifications ─────────────────────────────────────

    /** Interruptor global de notificaciones del usuario (activado por defecto). */
    fun isNotificationsEnabled(): Boolean =
        settings.getBoolean(KEY_NOTIFICATIONS, true)

    fun setNotificationsEnabled(enabled: Boolean) =
        settings.putBoolean(KEY_NOTIFICATIONS, enabled)

    // ── Language ──────────────────────────────────────────

    /**
     * Idioma preferido de la UI ("es"/"en"). Español por defecto si no se ha
     * elegido ninguno, o si el storage subyacente no está disponible — se lee
     * en la primera composición de `App()`, ANTES de montar el splash; en web
     * `localStorage` puede lanzar (storage bloqueado por el navegador/
     * política de privacidad), y sin capturarlo aquí la app entera se quedaba
     * sin montar nada, en vez de arrancar con el idioma por defecto (panel
     * v12, Web).
     */
    fun getLanguage(): String =
        try {
            settings.getString(KEY_LANGUAGE, "es")
        } catch (_: Throwable) {
            "es"
        }

    fun setLanguage(lang: String) =
        settings.putString(KEY_LANGUAGE, lang)

    // ── Theme ─────────────────────────────────────────────

    /** Nombre del tema visual elegido ("DEFAULT"/"NATURALEZA"/"MINIMAL" — ver [org.taskhub.ui.theme.TaskHubThemeType]). Mismo fallback que [getLanguage] si el storage no está disponible. */
    fun getTheme(): String =
        try {
            settings.getString(KEY_THEME, "DEFAULT")
        } catch (_: Throwable) {
            "DEFAULT"
        }

    fun setTheme(theme: String) =
        settings.putString(KEY_THEME, theme)

    // ── Widget Theme ──────────────────────────────────────

    /** Tema del widget de escritorio/pantalla de inicio (independiente del tema de la app). */
    fun getWidgetTheme(): String =
        settings.getString(KEY_WIDGET_THEME, "system")

    fun setWidgetTheme(theme: String) =
        settings.putString(KEY_WIDGET_THEME, theme)

    // ── Sound & Haptics ─────────────────────────────────

    /** Si se reproducen sonidos al completar acciones (activado por defecto). */
    fun isSoundEnabled(): Boolean =
        settings.getBoolean(KEY_SOUND_ENABLED, true)

    fun setSoundEnabled(enabled: Boolean) =
        settings.putBoolean(KEY_SOUND_ENABLED, enabled)

    /** Si se usa vibración háptica al completar acciones (activado por defecto). */
    fun isVibrationEnabled(): Boolean =
        settings.getBoolean(KEY_VIBRATION_ENABLED, true)

    fun setVibrationEnabled(enabled: Boolean) =
        settings.putBoolean(KEY_VIBRATION_ENABLED, enabled)

    // ── Analytics (RGPD) ─────────────────────────────────

    /** Si el usuario ha desactivado la recolección de Firebase Analytics (desactivado por defecto, opt-out). */
    fun isAnalyticsOptOut(): Boolean =
        settings.getBoolean(KEY_ANALYTICS_OPT_OUT, false)

    fun setAnalyticsOptOut(optOut: Boolean) =
        settings.putBoolean(KEY_ANALYTICS_OPT_OUT, optOut)

    // ── Modo simple (delight, panel 2026-09-18) ──────────
    //
    // Interruptor maestro + 3 de categoría para desactivar el "delight"
    // (animaciones/efectos/háptica) añadido a partir de esta fase, sin tocar
    // el ajuste de vibración ya existente ([isVibrationEnabled]) ni la señal
    // de accesibilidad [org.taskhub.ui.components.shouldReduceMotion]. Ver
    // [org.taskhub.ui.components.effectsEnabled] para la fórmula de gating
    // que combina estas 4 claves.

    /** Interruptor maestro del modo simple (desactivado por defecto): anula las 3 categorías siguientes. */
    fun isSimpleModeEnabled(): Boolean =
        settings.getBoolean(KEY_SIMPLE_MODE, false)

    fun setSimpleModeEnabled(enabled: Boolean) =
        settings.putBoolean(KEY_SIMPLE_MODE, enabled)

    /** Animaciones y transiciones decorativas (activado por defecto salvo modo simple). */
    fun isFxAnimationsEnabled(): Boolean =
        settings.getBoolean(KEY_FX_ANIMATIONS, true)

    fun setFxAnimationsEnabled(enabled: Boolean) =
        settings.putBoolean(KEY_FX_ANIMATIONS, enabled)

    /** Efectos visuales de celebración (confeti, check con rebote, etc.), activado por defecto salvo modo simple. */
    fun isFxEffectsEnabled(): Boolean =
        settings.getBoolean(KEY_FX_EFFECTS, true)

    fun setFxEffectsEnabled(enabled: Boolean) =
        settings.putBoolean(KEY_FX_EFFECTS, enabled)

    /** Vibración/háptica ligada al "delight" (activado por defecto salvo modo simple); se combina con [isVibrationEnabled]. */
    fun isFxHapticsEnabled(): Boolean =
        settings.getBoolean(KEY_FX_HAPTICS, true)

    fun setFxHapticsEnabled(enabled: Boolean) =
        settings.putBoolean(KEY_FX_HAPTICS, enabled)

    // ── Google Calendar ──────────────────────────────────

    /** True si hay un token de acceso de Google Calendar guardado (cuenta vinculada). */
    fun hasGoogleLinked(): Boolean =
        getGoogleAccessToken() != null

    /**
     * Cifrado en [secureStore] — ver [getGoogleRefreshToken] (mismo mecanismo
     * y migración automática de un valor legado en texto plano). Antes se
     * guardaba sin cifrar en [settings] pese a ser un token OAuth real con
     * scope de Calendar (panel de revisión 2026-09-03/04, Experto 9): de vida
     * corta (~1h) y mitigado parcialmente por excluir `sharedpref` de los
     * backups de Android, pero sigue siendo un secreto en texto plano en
     * disco mientras dura.
     */
    fun getGoogleAccessToken(): String? =
        secureStore.getString(KEY_GOOGLE_ACCESS_TOKEN) ?: migrateLegacyToken(KEY_GOOGLE_ACCESS_TOKEN)

    /** Guarda (o borra, si [token] es `null`) el access token de Google Calendar. */
    fun setGoogleAccessToken(token: String?) {
        if (token != null) {
            secureStore.putString(KEY_GOOGLE_ACCESS_TOKEN, token)
        } else {
            secureStore.remove(KEY_GOOGLE_ACCESS_TOKEN)
        }
        settings.remove(KEY_GOOGLE_ACCESS_TOKEN) // por si quedaba el valor legado sin cifrar
    }

    /** Interruptor del usuario para la sincronización automática con Calendar. */
    fun isCalendarSyncEnabled(): Boolean =
        settings.getBoolean(KEY_CALENDAR_SYNC_ENABLED, false)

    fun setCalendarSyncEnabled(enabled: Boolean) =
        settings.putBoolean(KEY_CALENDAR_SYNC_ENABLED, enabled)

    /** Desvincula Calendar: borra el token, los calendarIds cacheados y desactiva el sync. */
    fun unlinkGoogleCalendar() {
        secureStore.remove(KEY_GOOGLE_ACCESS_TOKEN)
        settings.remove(KEY_GOOGLE_ACCESS_TOKEN)
        settings.remove(KEY_CALENDAR_IDS)
        settings.putBoolean(KEY_CALENDAR_SYNC_ENABLED, false)
    }

    // ── Google Auth (login) ──────────────────────────────

    /** True si hay una sesión de Google guardada localmente (UID no nulo). */
    fun isGoogleLoggedIn(): Boolean =
        settings.getStringOrNull(KEY_GOOGLE_UID) != null

    fun getGoogleUid(): String? =
        settings.getStringOrNull(KEY_GOOGLE_UID)

    fun getGoogleEmail(): String? =
        settings.getStringOrNull(KEY_GOOGLE_EMAIL)

    /** Guarda (o borra, si algún parámetro es `null`) el UID/email de la sesión de Google. */
    fun setGoogleAuth(uid: String?, email: String?) {
        if (uid != null) {
            settings.putString(KEY_GOOGLE_UID, uid)
        } else {
            settings.remove(KEY_GOOGLE_UID)
        }
        if (email != null) {
            settings.putString(KEY_GOOGLE_EMAIL, email)
        } else {
            settings.remove(KEY_GOOGLE_EMAIL)
        }
    }

    /**
     * Refresh token de Firebase del login Google (para restaurar la sesión).
     * Cifrado en [secureStore] — ver hallazgo B1. Migra automáticamente un
     * valor legado guardado en texto plano antes de este cambio.
     */
    fun getGoogleRefreshToken(): String? =
        secureStore.getString(KEY_GOOGLE_REFRESH_TOKEN) ?: migrateLegacyToken(KEY_GOOGLE_REFRESH_TOKEN)

    /** Guarda (o borra, si [token] es `null`) el refresh token de Google en [secureStore]. */
    fun setGoogleRefreshToken(token: String?) {
        if (token != null) {
            secureStore.putString(KEY_GOOGLE_REFRESH_TOKEN, token)
        } else {
            secureStore.remove(KEY_GOOGLE_REFRESH_TOKEN)
        }
        settings.remove(KEY_GOOGLE_REFRESH_TOKEN) // por si quedaba el valor legado sin cifrar
    }

    /** Borra toda la sesión de Google (UID, email y refresh token cifrado/legado). */
    fun clearGoogleAuth() {
        settings.remove(KEY_GOOGLE_UID)
        settings.remove(KEY_GOOGLE_EMAIL)
        secureStore.remove(KEY_GOOGLE_REFRESH_TOKEN)
        settings.remove(KEY_GOOGLE_REFRESH_TOKEN)
    }

    /**
     * Migra un refresh token guardado en texto plano (versión anterior al
     * cifrado de [secureStore]) al almacén cifrado, y borra el original. Solo
     * se ejecuta una vez por token: tras la primera lectura ya vive cifrado.
     */
    private fun migrateLegacyToken(key: String): String? {
        val legacy = settings.getStringOrNull(key) ?: return null
        secureStore.putString(key, legacy)
        settings.remove(key)
        return legacy
    }

    // ── Google Calendar sync (calendarId por hogar) ──────
    //
    // Puntero LOCAL a un calendario ya creado en Google (uno por hogar/espacio
    // Personal, por-dispositivo). El calendario en sí vive en la cuenta de
    // Google del usuario; esto solo evita crear uno nuevo cada vez. Limitación
    // conocida: un segundo dispositivo crearía su propio calendario — aceptado
    // para el MVP.

    /** Calendario de Google ya vinculado a [householdId] en este dispositivo, o `null` si no hay ninguno. */
    fun getCalendarId(householdId: String): String? = getCalendarIdMap()[householdId]

    /** Asocia (o reemplaza) el calendario de Google de [householdId] en este dispositivo. */
    fun setCalendarId(householdId: String, calendarId: String) {
        val map = getCalendarIdMap().toMutableMap()
        map[householdId] = calendarId
        settings.putString(KEY_CALENDAR_IDS, json.encodeToString(map))
    }

    /** Deserializa el mapa householdId→calendarId; mapa vacío si no hay datos o están corruptos. */
    private fun getCalendarIdMap(): Map<String, String> {
        val raw = settings.getString(KEY_CALENDAR_IDS, "")
        if (raw.isEmpty()) return emptyMap()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Última vez (epoch millis) que [org.taskhub.ui.models.CalendarSyncManager.reconcile]
     * terminó con éxito para [householdId] en este dispositivo — `0` si nunca.
     * Panel v17 (hallazgo IMPORTANTE de rendimiento): `reconcile()` se
     * disparaba en CADA apertura de `HouseholdScreen`/`PersonalSpaceScreen`
     * (Voyager crea una instancia nueva de `Screen` en cada `push`, no solo
     * la primera vez de la sesión), repitiendo `getTasks` + `getAllAssignments`
     * de todo el hogar aunque no hubiera nada pendiente el 99% de las veces.
     */
    fun getLastCalendarReconcileAt(householdId: String): Long = getCalendarReconcileMap()[householdId] ?: 0L

    /** Marca [householdId] como reconciliado ahora mismo en este dispositivo. */
    fun setLastCalendarReconcileAt(householdId: String, epochMs: Long) {
        val map = getCalendarReconcileMap().toMutableMap()
        map[householdId] = epochMs
        settings.putString(KEY_CALENDAR_RECONCILE_AT, json.encodeToString(map))
    }

    private fun getCalendarReconcileMap(): Map<String, Long> {
        val raw = settings.getString(KEY_CALENDAR_RECONCILE_AT, "")
        if (raw.isEmpty()) return emptyMap()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ── Sondeo de notificaciones (IDs ya notificados por hogar) ──────────
    //
    // El polling periódico en Android (`NotificationPollWorker`) usa este
    // conjunto de IDs para saber qué notificaciones de
    // `households/{id}/notifications` son nuevas desde la última pasada.
    //
    // Se probó primero un marcador de tiempo (`createdAt` más reciente
    // visto), pero `createdAt` lo genera el RELOJ LOCAL del dispositivo que
    // crea la notificación (asignador o autor del mensaje, no el del
    // destinatario) — un dispositivo con el reloj adelantado podía "inflar"
    // el marcador del destinatario y ocultar PARA SIEMPRE una notificación
    // genuinamente posterior creada desde otro dispositivo con
    // `createdAt` menor (panel de notificaciones 2026-09-05, Experto
    // Notificaciones/Push, CRÍTICO). Un conjunto de IDs ya vistos no depende
    // en absoluto del orden temporal para decidir "es nueva", solo de si ya
    // se mostró antes — inmune al desfase de reloj entre dispositivos.

    /** IDs de `notifications` de [householdId] ya mostrados por el polling en este dispositivo. */
    fun getNotifiedNotificationIds(householdId: String): Set<String> =
        getNotifiedNotificationIdsMap()[householdId]?.toSet() ?: emptySet()

    /** Reemplaza el conjunto completo de IDs ya notificados de [householdId] (tras una pasada del polling). */
    fun setNotifiedNotificationIds(householdId: String, ids: Set<String>) {
        val map = getNotifiedNotificationIdsMap().toMutableMap()
        map[householdId] = ids.toList()
        settings.putString(KEY_NOTIFICATION_POLL_MARKERS, json.encodeToString(map))
    }

    /**
     * Borra todo el estado de sondeo (todos los hogares). Llamado en
     * [org.taskhub.ui.models.GoogleAuthManager.signOut] — sin esto, en un
     * dispositivo familiar compartido, tras cambiar de cuenta el estado de la
     * cuenta anterior podría ocultar notificaciones nuevas legítimas de la
     * cuenta entrante (mismo householdId, p.ej. el espacio Personal) o, al
     * revés, filtrar datos de la cuenta saliente si el proceso no se reinicia.
     */
    fun clearNotificationPollState() {
        settings.remove(KEY_NOTIFICATION_POLL_MARKERS)
    }

    /** Deserializa el mapa householdId→IDs notificados; mapa vacío si no hay datos o están corruptos. */
    private fun getNotifiedNotificationIdsMap(): Map<String, List<String>> {
        val raw = settings.getString(KEY_NOTIFICATION_POLL_MARKERS, "")
        if (raw.isEmpty()) return emptyMap()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        private const val KEY_NOTIFICATIONS = "taskhub_notifications"
        private const val KEY_LANGUAGE = "taskhub_language"
        private const val KEY_THEME = "taskhub_theme"
        private const val KEY_WIDGET_THEME = "taskhub_widget_theme"
        private const val KEY_SOUND_ENABLED = "taskhub_sound_enabled"
        private const val KEY_VIBRATION_ENABLED = "taskhub_vibration_enabled"
        private const val KEY_ANALYTICS_OPT_OUT = "taskhub_analytics_opt_out"
        private const val KEY_SIMPLE_MODE = "simple_mode"
        private const val KEY_FX_ANIMATIONS = "fx_animations"
        private const val KEY_FX_EFFECTS = "fx_effects"
        private const val KEY_FX_HAPTICS = "fx_haptics"
        private const val KEY_GOOGLE_ACCESS_TOKEN = "taskhub_google_token"
        private const val KEY_CALENDAR_SYNC_ENABLED = "taskhub_calendar_sync_enabled"
        private const val KEY_GOOGLE_UID = "taskhub_google_uid"
        private const val KEY_GOOGLE_EMAIL = "taskhub_google_email"
        private const val KEY_GOOGLE_REFRESH_TOKEN = "taskhub_google_refresh_token"
        private const val KEY_CALENDAR_IDS = "taskhub_calendar_ids"
        private const val KEY_CALENDAR_RECONCILE_AT = "taskhub_calendar_reconcile_at"
        private const val KEY_NOTIFICATION_POLL_MARKERS = "taskhub_notification_poll_markers"
    }
}