// Hoja de ajustes de la app: cuenta de Google, Google Calendar, notificaciones,
// tema, idioma, tema del widget, sonido/vibración, exportar CSV, privacidad y
// eliminar cuenta. Se muestra desde HouseholdSettingsDialog y desde el resto
// de pantallas con acceso a ajustes.
package org.taskhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.taskhub.storage.SettingsStore
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.GoogleAuthManager
import org.taskhub.ui.models.GoogleAuthState
import org.taskhub.ui.models.ProfileScreenModel
import org.taskhub.ui.theme.MidnightBlue500
import org.taskhub.ui.theme.MonoGray600
import org.taskhub.ui.theme.OceanBlue600
import org.taskhub.ui.theme.SunsetPurple600
import org.taskhub.ui.theme.Green600
import org.taskhub.ui.theme.Teal600
import org.taskhub.ui.theme.TaskHubThemeType
import org.taskhub.platform.hasCalendarSupport
import org.taskhub.platform.hasHomeScreenWidget
import org.taskhub.platform.hasNotificationSupport
import org.taskhub.platform.saveWidgetThemeToCache

/** Misma URL publicada en la ficha de Play (ver docs/play-store-listing.md). */
private const val PRIVACY_POLICY_URL = "https://libertobaltasar.github.io/task-hub/privacy.html"

/**
 * Callbacks que [SettingsSheet] necesita de la pantalla que la aloja.
 *
 * @param onExportCsv acción de exportar tareas a CSV (solo se invoca si [showExportCsv] es `true`).
 * @param onDismiss cierra la hoja de ajustes.
 * @param onEditProfile navega a la pantalla de editar perfil (la hoja se cierra antes).
 */
data class SettingsCallbacks(
    val onExportCsv: () -> Unit,
    val onDismiss: () -> Unit,
    val onEditProfile: () -> Unit = {},
    // false en pantallas sin contexto de tareas cargado (Home, Perfil): antes
    // el botón se mostraba igual en las 4 pantallas con onExportCsv = {} (no-op)
    // en 3 de ellas — un control muerto real. Exportar sigue disponible desde
    // la lista de tareas, que sí tiene los datos.
    val showExportCsv: Boolean = true
)

/**
 * Hoja de ajustes a pantalla casi completa (se muestra dentro de un `Dialog`,
 * ver [HouseholdSettingsDialog]).
 *
 * Secciones: cuenta de Google, Google Calendar, notificaciones, tema, idioma,
 * tema del widget, sonido/vibración, exportar CSV (opcional) y privacidad/RGPD.
 * Lee y escribe preferencias vía [SettingsStore]; los cambios se aplican de
 * inmediato (sin botón "Guardar"). Los cambios de tema/idioma se propagan al
 * resto de la app a través de [LocalAppSettings].
 *
 * @param callbacks acciones que debe resolver la pantalla anfitriona (cerrar, exportar, editar perfil).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    callbacks: SettingsCallbacks
) {
    val settingsStore = koinInject<SettingsStore>()
    val appSettings = LocalAppSettings.current
    val authManager = koinInject<GoogleAuthManager>()
    val profileScreenModel = koinInject<ProfileScreenModel>()
    val authState by authManager.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    val navigator = LocalNavigator.currentOrThrow

    var notificationsEnabled by remember {
        mutableStateOf(settingsStore.isNotificationsEnabled())
    }

    var widgetTheme by remember {
        mutableStateOf(settingsStore.getWidgetTheme())
    }

    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Title
        Text(
            text = s("settings_title"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(24.dp))

        // ── Cuenta de Google ──────────────────────────────
        ExpandableSection(
            title = s("settings_account_title"),
            icon = Icons.Filled.Person,
            defaultExpanded = true
        ) {
            when (val state = authState) {
                is GoogleAuthState.SignedIn -> {
                    Text(
                        text = s("settings_account_connected_prefix").replace("%s", state.email ?: "Google"),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = s("settings_account_cloud_note"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { authManager.signOut() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(s("settings_account_sign_out"))
                    }
                }

                is GoogleAuthState.SigningIn -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(s("settings_account_connecting"))
                    }
                }

                is GoogleAuthState.Error -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = s("error_icon_content_desc"),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { authManager.signIn() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(s("settings_account_retry"))
                    }
                }

                else -> {
                    Text(
                        text = s("settings_account_no_session"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { authManager.signIn() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(s("settings_account_sign_in_google"))
                    }
                }
            }

            // Botón de editar perfil (dentro de la sección Cuenta)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    callbacks.onDismiss()
                    callbacks.onEditProfile()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(s("settings_account_edit_profile"))
            }

            // ── Eliminar cuenta (RGPD) ─────────────────────
            // Vive en el grupo Cuenta junto al resto de acciones de cuenta
            // (antes vivía suelta al final de Privacidad y datos).
            Spacer(Modifier.height(16.dp))
            DeleteAccountSection(
                s = s,
                authManager = authManager,
                navigator = navigator,
                onDismiss = callbacks.onDismiss
            )
        }

        Spacer(Modifier.height(8.dp))

        // Solo Android puede vincular Google Calendar hoy (ver
        // hasCalendarSupport en platform/Platform.kt) — en JVM/wasmJs,
        // getGoogleCalendarAccessToken() está hardcodeado a null, así que
        // mostrar esta sección solo llevaba al usuario a completar un flujo
        // OAuth entero (en desktop, real) para acabar viendo un error.
        // ── Calendario ────────────────────────────────────
        ExpandableSection(
            title = s("calendar_settings_title"),
            icon = Icons.Filled.DateRange,
            isVisible = hasCalendarSupport
        ) {
            var isCalendarLinked by remember { mutableStateOf(settingsStore.hasGoogleLinked()) }
            var isCalendarSyncEnabled by remember { mutableStateOf(settingsStore.isCalendarSyncEnabled()) }
            var showUnlinkConfirm by remember { mutableStateOf(false) }
            var isLinkingCalendar by remember { mutableStateOf(false) }
            var calendarLinkError by remember { mutableStateOf<String?>(null) }
            val calendarScope = rememberCoroutineScope()

            if (isCalendarLinked) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = s("calendar_sync_toggle_label"),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = s("calendar_sync_toggle_desc"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isCalendarSyncEnabled,
                        onCheckedChange = {
                            isCalendarSyncEnabled = it
                            settingsStore.setCalendarSyncEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = s("calendar_linked_as").replace("%s", settingsStore.getGoogleEmail() ?: "Google"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = s("calendar_independent_note"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showUnlinkConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(s("calendar_unlink_button"))
                }
                if (showUnlinkConfirm) {
                    DestructiveConfirmDialog(
                        title = s("calendar_unlink_confirm_title"),
                        text = s("calendar_unlink_confirm_text"),
                        s = s,
                        confirmLabel = s("calendar_unlink_button"),
                        // No destructivo: se puede volver a vincular cuando se
                        // quiera, a diferencia de borrar hogar/cuenta/tarea.
                        destructive = false,
                        onDismiss = { showUnlinkConfirm = false },
                        onConfirm = {
                            showUnlinkConfirm = false
                            settingsStore.unlinkGoogleCalendar()
                            isCalendarLinked = false
                            isCalendarSyncEnabled = false
                        }
                    )
                }
            } else {
                Text(
                    text = s("calendar_link_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = s("calendar_independent_note"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        isLinkingCalendar = true
                        calendarLinkError = null
                        calendarScope.launch {
                            val linked = authManager.linkCalendar()
                            isLinkingCalendar = false
                            if (linked) {
                                isCalendarLinked = true
                                isCalendarSyncEnabled = true
                                settingsStore.setCalendarSyncEnabled(true)
                            } else {
                                // Antes fallaba en silencio: solo se apagaba el
                                // spinner y volvía a "No vinculado" sin ninguna
                                // pista de si el usuario canceló o hubo un fallo
                                // real (p. ej. plataformas sin soporte aún, ver
                                // getGoogleCalendarAccessToken) — mismo mensaje
                                // que ya usa TaskDetailScreen para este flujo.
                                calendarLinkError = s("calendar_link_error")
                            }
                        }
                    },
                    enabled = !isLinkingCalendar,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isLinkingCalendar) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(s("calendar_linking"))
                    } else {
                        Text(s("calendar_link_button"))
                    }
                }
                if (calendarLinkError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "⚠️ $calendarLinkError",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Notificaciones y sonido ────────────────────────
        ExpandableSection(
            title = s("settings_notifications_section"),
            icon = Icons.Filled.Notifications
        ) {
        // Solo Android agenda recordatorios locales reales hoy (ver
        // hasNotificationSupport en platform/Platform.kt) — en iOS/JVM/web,
        // createNotificationScheduler() siempre es un no-op, así que este
        // interruptor no tenía ningún efecto ahí.
        if (hasNotificationSupport) {
        SettingsSection(title = s("settings_notifications")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s("settings_notifications_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = notificationsEnabled,
                    onCheckedChange = { enabled ->
                        notificationsEnabled = enabled
                        settingsStore.setNotificationsEnabled(enabled)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
        }

        Spacer(Modifier.height(16.dp))

        // ── Sound & Vibration ────────────────────────────
        SettingsSection(title = s("settings_sound_vibration")) {
            var soundEnabled by remember {
                mutableStateOf(settingsStore.isSoundEnabled())
            }
            var vibrationEnabled by remember {
                mutableStateOf(settingsStore.isVibrationEnabled())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s("settings_sound"),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = s("settings_sound_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = soundEnabled,
                    onCheckedChange = {
                        soundEnabled = it
                        settingsStore.setSoundEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s("settings_vibration"),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = s("settings_vibration_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = vibrationEnabled,
                    onCheckedChange = {
                        vibrationEnabled = it
                        settingsStore.setVibrationEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
        }

        Spacer(Modifier.height(8.dp))

        // ── Personalización ────────────────────────────────
        ExpandableSection(
            title = s("settings_personalization_section"),
            icon = Icons.Filled.Settings
        ) {

        // ── Theme ────────────────────────────────────────
        // RadioOptionRow (no dropdown): consistente con los selectores de
        // Idioma y Tema del widget de más abajo — mismo patrón de 2-3 opciones
        // pero con 6 temas (panel v21).
        SettingsSection(title = s("settings_theme")) {
            Column(modifier = Modifier.selectableGroup()) {
                TaskHubThemeType.entries.forEach { theme ->
                    RadioOptionRow(
                        label = getThemeLabel(theme, s),
                        selected = appSettings.currentTheme == theme,
                        onClick = { appSettings.onThemeChanged(theme) },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(themeColorFor(theme))
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Language ─────────────────────────────────────
        SettingsSection(title = s("settings_language")) {
            Column(modifier = Modifier.selectableGroup()) {
                RadioOptionRow(
                    label = s("lang_spanish"),
                    selected = appSettings.currentLanguage == "es",
                    onClick = { appSettings.onLanguageChanged("es") }
                )
                RadioOptionRow(
                    label = s("lang_english"),
                    selected = appSettings.currentLanguage == "en",
                    onClick = { appSettings.onLanguageChanged("en") }
                )
            }
        }

        // Solo Android tiene widget de pantalla de inicio real — en el resto
        // de plataformas esta sección no tenía ningún efecto (ver
        // hasHomeScreenWidget en platform/Platform.kt).
        if (hasHomeScreenWidget) {
            Spacer(Modifier.height(24.dp))

            // ── Widget Theme ────────────────────────────────
            SettingsSection(title = s("settings_widget_theme_title")) {
                Column(modifier = Modifier.selectableGroup()) {
                    RadioOptionRow(
                        label = s("widget_theme_light"),
                        selected = widgetTheme == "light",
                        onClick = {
                            widgetTheme = "light"
                            settingsStore.setWidgetTheme("light")
                            saveWidgetThemeToCache("light")
                        }
                    )
                    RadioOptionRow(
                        label = s("widget_theme_dark"),
                        selected = widgetTheme == "dark",
                        onClick = {
                            widgetTheme = "dark"
                            settingsStore.setWidgetTheme("dark")
                            saveWidgetThemeToCache("dark")
                        }
                    )
                    RadioOptionRow(
                        label = s("widget_theme_system"),
                        selected = widgetTheme == "system",
                        onClick = {
                            widgetTheme = "system"
                            settingsStore.setWidgetTheme("system")
                            saveWidgetThemeToCache("system")
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Modo simple (delight, panel 2026-09-18) ───────
        SettingsSection(title = s("simple_mode")) {
            var simpleModeEnabled by remember { mutableStateOf(settingsStore.isSimpleModeEnabled()) }
            var fxAnimationsEnabled by remember { mutableStateOf(settingsStore.isFxAnimationsEnabled()) }
            var fxEffectsEnabled by remember { mutableStateOf(settingsStore.isFxEffectsEnabled()) }
            var fxHapticsEnabled by remember { mutableStateOf(settingsStore.isFxHapticsEnabled()) }

            Text(
                text = s("simple_mode_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // Interruptor maestro: anula los 3 de categoría siguientes sin
            // borrar su valor guardado (se restauran tal cual al desactivarlo).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = s("simple_mode"),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = simpleModeEnabled,
                    onCheckedChange = {
                        simpleModeEnabled = it
                        settingsStore.setSimpleModeEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = s("fx_animations"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = fxAnimationsEnabled,
                    enabled = !simpleModeEnabled,
                    onCheckedChange = {
                        fxAnimationsEnabled = it
                        settingsStore.setFxAnimationsEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = s("fx_effects"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = fxEffectsEnabled,
                    enabled = !simpleModeEnabled,
                    onCheckedChange = {
                        fxEffectsEnabled = it
                        settingsStore.setFxEffectsEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = s("fx_haptics"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = fxHapticsEnabled,
                    enabled = !simpleModeEnabled,
                    onCheckedChange = {
                        fxHapticsEnabled = it
                        settingsStore.setFxHapticsEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
        }

        Spacer(Modifier.height(8.dp))

        // ── Privacidad y datos (enlace a la política + RGPD) ──────────────
        ExpandableSection(
            title = s("settings_privacy_data_title"),
            icon = Icons.Filled.Lock
        ) {
            OutlinedButton(
                onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(s("settings_privacy_policy"))
            }

            Spacer(Modifier.height(16.dp))

            // ── Analytics opt-out (RGPD) ──────────────────────
            var analyticsOptOut by remember { mutableStateOf(settingsStore.isAnalyticsOptOut()) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s("settings_analytics_opt_out"),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = s("settings_analytics_opt_out_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = analyticsOptOut,
                    onCheckedChange = { optOut ->
                        analyticsOptOut = optOut
                        profileScreenModel.setAnalyticsOptOut(optOut)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            // ── Export CSV ───────────────────────────────────
            if (callbacks.showExportCsv) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        callbacks.onDismiss()
                        callbacks.onExportCsv()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = s("settings_export_csv"),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Close button
        OutlinedButton(
            onClick = callbacks.onDismiss,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        ) {
            Text(s("settings_close"))
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Grupo de secciones de [SettingsSheet] en formato acordeón: cabecera
 * clicable (icono + título + chevron, vía [ExpandableSectionHeader]) que
 * expande/contrae su contenido. El cambio es instantáneo (sin
 * `AnimatedVisibility`) para que siga siendo compatible con Modo Simple sin
 * necesitar su propio toggle.
 *
 * @param isVisible si es `false`, el grupo entero no se muestra (p. ej. grupos
 *   condicionados a soporte de plataforma como Calendario).
 * @param defaultExpanded estado inicial al abrir la hoja de ajustes; el resto
 *   de grupos arrancan contraídos.
 */
@Composable
private fun ExpandableSection(
    title: String,
    icon: ImageVector,
    isVisible: Boolean = true,
    defaultExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!isVisible) return
    var expanded by remember { mutableStateOf(defaultExpanded) }

    Column(modifier = Modifier.fillMaxWidth()) {
        ExpandableSectionHeader(
            expanded = expanded,
            onToggle = { expanded = !expanded }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp)
            ) {
                content()
            }
        }
    }
}

/** Bloque de sección con título en `primary` + contenido, reutilizado por las sub-secciones de un [ExpandableSection]. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** String i18n del nombre de cada [TaskHubThemeType], usado en el [RadioOptionRow] del selector de tema. */
private fun getThemeLabel(theme: TaskHubThemeType, s: (String) -> String): String =
    when (theme) {
        TaskHubThemeType.DEFAULT -> s("theme_default")
        TaskHubThemeType.NATURALEZA -> s("theme_naturaleza")
        TaskHubThemeType.MINIMAL -> s("theme_minimal")
        TaskHubThemeType.OCEANO -> s("theme_oceano")
        TaskHubThemeType.ATARDECER -> s("theme_atardecer")
        TaskHubThemeType.MIDNIGHT -> s("theme_midnight")
    }

/** Color representativo de cada [TaskHubThemeType], usado en el swatch del [RadioOptionRow] del selector de tema. */
private fun themeColorFor(theme: TaskHubThemeType): Color =
    when (theme) {
        TaskHubThemeType.DEFAULT -> Teal600
        TaskHubThemeType.NATURALEZA -> Green600
        TaskHubThemeType.MINIMAL -> MonoGray600
        TaskHubThemeType.OCEANO -> OceanBlue600
        TaskHubThemeType.ATARDECER -> SunsetPurple600
        TaskHubThemeType.MIDNIGHT -> MidnightBlue500
    }

/**
 * Fila de opción con RadioButton reutilizada por los selectores de esta
 * pantalla (tema, idioma, tema del widget) — antes eran composables
 * idénticos letra por letra (ThemeOption/LanguageOption/WidgetThemeOption).
 *
 * @param leadingContent contenido opcional entre el RadioButton y el label
 *   (p. ej. el swatch de color del selector de tema).
 */
@Composable
private fun RadioOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingContent: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // `selectable` (no `clickable` genérico): semántica correcta de
            // grupo de radio-botones — expone `role = RadioButton` y el
            // estado `selected` a TalkBack/VoiceOver, que antes solo se
            // anunciaba a través del propio `RadioButton` hijo, no de toda la
            // fila clicable (panel v7, Exp. 3, MENOR).
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary
            )
        )
        Spacer(Modifier.width(8.dp))
        leadingContent?.invoke(this)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}