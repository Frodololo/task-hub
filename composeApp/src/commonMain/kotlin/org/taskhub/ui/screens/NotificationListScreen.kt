/**
 * Bandeja de notificaciones de un hogar (tareas asignadas, mensajes nuevos
 * del chat, etc.). Se navega aquí desde el icono de campana en las pantallas
 * del hogar (Home/TaskList/Household) y desde deep links push. Usa
 * [org.taskhub.ui.models.NotificationScreenModel] para cargar/marcar como
 * leídas, y [org.taskhub.ui.i18n.NotificationText] para traducir el
 * título/mensaje al idioma del lector (no al de quien la generó).
 */
package org.taskhub.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.taskhub.network.models.NotificationResponse
import org.taskhub.ui.components.EmptyNotificationsIllustration
import org.taskhub.ui.components.EmptyState
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.ShimmerList
import org.taskhub.ui.components.TaskHubTopBar
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.i18n.NotificationText
import org.taskhub.ui.models.NotificationScreenModel
import org.taskhub.ui.models.NotificationUiState
import org.taskhub.ui.components.rememberHouseholdName
import org.taskhub.ui.models.HouseholdScreenModel
import org.taskhub.ui.models.MemberScreenModel
import org.taskhub.ui.models.MemberUiState
import org.taskhub.ui.theme.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Muestra la lista de notificaciones del [memberId] indicado dentro del
 * hogar [householdId], con estados de carga/vacío/error y contador de no
 * leídas.
 */
data class NotificationListScreen(
    val householdId: String,
    val memberId: String
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = koinScreenModel<NotificationScreenModel>()
        val state by model.uiState.collectAsState()
        val appSettings = LocalAppSettings.current
        val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

        val householdModel = koinScreenModel<HouseholdScreenModel>()
        val householdName = rememberHouseholdName(householdId, householdModel)

        // Miembros del hogar, para resolver el nombre del autor de una
        // notificación de chat contra su estado ACTUAL (ver KDoc de
        // NotificationText.message) en vez del nombre ya congelado en
        // `message` — ronda de deuda aplicable 2026-09-12, punto B10.
        val memberModel = koinScreenModel<MemberScreenModel>()
        val memberState by memberModel.uiState.collectAsState()
        val resolveAuthorName = remember(memberState) {
            val members = (memberState as? MemberUiState.Success)?.members
            if (members != null) {
                val byId = members.associateBy { it.id }
                ({ id: String -> byId[id]?.displayName })
            } else {
                null
            }
        }

        LaunchedEffect(householdId, memberId) {
            model.loadNotifications(householdId, memberId)
            memberModel.loadMembers(householdId)
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                TaskHubTopBar(
                    title = s("notifications_title"),
                    subtitle = householdName,
                    onBack = { navigator.pop() }
                )

                // Content
                when (val st = state) {
                    is NotificationUiState.Loading -> {
                        // ShimmerList en vez de CircularProgressIndicator genérico —
                        // mismo patrón ya usado en TaskListScreen/HouseholdScreen/
                        // HomeScreen/RankingScreen para listas cargando de red
                        // (ronda de deuda aplicable 2026-09-12, punto C15).
                        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            ShimmerList(count = 5, itemHeight = 72.dp)
                        }
                    }

                    is NotificationUiState.Success -> {
                        if (st.notifications.isEmpty()) {
                            EmptyState(
                                modifier = Modifier.fillMaxSize(),
                                illustration = { EmptyNotificationsIllustration() },
                                message = s("notifications_empty"),
                                messageStyle = MaterialTheme.typography.bodyLarge
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item {
                                    Text(
                                        text = s("notifications_unread_count").replace("%d", st.unreadCount.toString()),
                                        style = MaterialTheme.typography.labelMedium,
                                        // Coral500 fijo sobre `background` fallaba AA (2.83-3.07:1) en los 3
                                        // temas claros; semanticColors.info ya está auditado y es el mismo
                                        // tono que usa el punto indicador de "no leída" más abajo.
                                        color = if (st.unreadCount > 0) MaterialTheme.semanticColors.info else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }

                                items(st.notifications, key = { it.id }) { notification ->
                                    NotificationCard(
                                        notification = notification,
                                        resolveAuthorName = resolveAuthorName,
                                        onMarkRead = {
                                            model.markAsRead(householdId, notification.id)
                                        },
                                        onClick = {
                                            if (!notification.read) {
                                                model.markAsRead(householdId, notification.id)
                                            }
                                            // taskId vacío = notificación de un mensaje de chat
                                            // (ver HouseholdRepository.sendMessage) → abre el chat
                                            // del hogar en vez del detalle de una tarea.
                                            if (notification.taskId.isEmpty()) {
                                                navigator.push(HouseholdScreen(householdId))
                                            } else {
                                                navigator.push(TaskDetailScreen(householdId, notification.taskId))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    is NotificationUiState.Error -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = s("error_icon_content_desc"),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = st.message,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = {
                                    model.loadNotifications(householdId, memberId)
                                }) {
                                    Text(s("tasks_retry"))
                                }
                            }
                        }
                    }

                    is NotificationUiState.Idle -> {}
                }
            }
        }
    }
}

/** Tarjeta de una notificación individual; resalta si no está leída y ofrece marcarla como leída. */
@Composable
private fun NotificationCard(
    notification: NotificationResponse,
    onMarkRead: () -> Unit,
    onClick: () -> Unit,
    resolveAuthorName: ((String) -> String?)? = null
) {
    val appSettings = LocalAppSettings.current
    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }
    // primaryContainer/onPrimaryContainer (en vez del antiguo par fijo
    // Teal50/Teal800/900): sigue el tema activo (Naturaleza/Minimal, no solo
    // Default) y ya es un par accesible auditado en las 6 combinaciones
    // tema/modo, mismo criterio aplicado a otras 6+ cards de la app.
    val titleColor = if (!notification.read) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    // Sin alpha: onPrimaryContainer.copy(alpha=0.8f) caía por debajo de 4.5:1
    // en 4/6 combinaciones tema/modo (Default claro 4.06:1, Default oscuro
    // 3.50:1, Naturaleza claro 3.89:1, Naturaleza oscuro 3.56:1) pese a que el
    // par sólido subyacente sí está auditado — el alpha reintroducía el fallo.
    val secondaryColor = if (!notification.read) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    // Idioma del LECTOR (este dispositivo), no el de quien la escribió — ver
    // NotificationText KDoc (panel de notificaciones 2026-09-05, IMPORTANTE).
    val displayTitle = remember(notification, appSettings.currentLanguage) {
        NotificationText.title(notification, appSettings.currentLanguage)
    }
    val displayMessage = remember(notification, appSettings.currentLanguage, resolveAuthorName) {
        NotificationText.message(notification, appSettings.currentLanguage, resolveAuthorName)
    }
    // La card se "apaga" suavemente al marcar leída en vez de cambiar de golpe
    // (informe delight §2.13); el punto indicador de 8dp NO se anima (decisión
    // de diseño del informe: elemento demasiado pequeño para justificarlo).
    val reduceMotion = shouldReduceMotion()
    val animatedContainerColor by animateColorAsState(
        targetValue = if (!notification.read)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surface,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 200)
    )

    Card(
        // role = Button: semántica estructurada para TalkBack/VoiceOver
        // (panel v7 2026-09-10, Exp. 3, IMPORTANTE).
        // customActions: expone "marcar leída" como acción accesible del propio
        // card en vez de un botón anidado dentro del área clicable (panel
        // kanban 2026-09-10, [Accesibilidad] botón anidado); el TextButton
        // interno se oculta del árbol de semántica con clearAndSetSemantics.
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!notification.read) {
                    Modifier.semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(s("notifications_mark_read")) {
                                onMarkRead()
                                true
                            }
                        )
                    }
                } else Modifier
            )
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = animatedContainerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (!notification.read) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Unread indicator
            if (!notification.read) {
                Surface(
                    modifier = Modifier
                        .size(8.dp)
                        .offset(y = 6.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.semanticColors.info
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (!notification.read) FontWeight.Bold else FontWeight.Normal,
                        color = titleColor
                    )

                    // Time ago text
                    Text(
                        text = formatTimeAgo(notification.createdAt, appSettings.currentLanguage),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryColor
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = displayMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryColor
                )

                // Mark as read button for unread notifications
                if (!notification.read) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onMarkRead,
                        contentPadding = PaddingValues(0.dp),
                        // Oculto para TalkBack/VoiceOver: la acción ya está expuesta
                        // como customAction del Card (evita botón anidado dentro
                        // del área clicable). Sigue funcionando al tacto.
                        modifier = Modifier.clearAndSetSemantics {}
                    ) {
                        Text(
                            text = s("notifications_mark_read"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Formatea la fecha de una notificación como texto relativo ("hace X min/h/días")
 * y cae a fecha absoluta dd/mm/aaaa pasada una semana.
 */
private fun formatTimeAgo(epochMs: Long, lang: String): String {
    if (epochMs == 0L) return ""
    val now = Clock.System.now().toEpochMilliseconds()
    val diffMs = now - epochMs
    val diffMin = diffMs / (60 * 1000)
    val diffHours = diffMin / 60
    val diffDays = diffHours / 24

    return when {
        diffMin < 1 -> AppStrings.get("time_ago_now", lang)
        diffMin < 60 -> AppStrings.get("time_ago_minutes", lang).replace("%d", diffMin.toString())
        diffHours < 24 -> AppStrings.get("time_ago_hours", lang).replace("%d", diffHours.toString())
        diffDays < 7 -> AppStrings.get("time_ago_days", lang).replace("%d", diffDays.toString())
        else -> {
            val instant = Instant.fromEpochMilliseconds(epochMs)
            val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
            "${local.dayOfMonth}/${local.monthNumber}/${local.year}"
        }
    }
}