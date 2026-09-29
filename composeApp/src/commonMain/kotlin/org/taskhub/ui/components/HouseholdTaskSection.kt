// Tarjeta de resumen de tareas pendientes de un hogar, usada en la pantalla
// principal (Home) para dar una vista previa de cada hogar del usuario sin
// tener que entrar a su lista de tareas completa.
package org.taskhub.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.taskhub.network.models.TaskResponse
import org.taskhub.storage.SavedHousehold
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.HouseholdPreviewState
import org.taskhub.ui.screens.TaskDetailScreen

/**
 * Tarjeta desplegable con las primeras tareas sin completar de un hogar.
 * Puramente presentacional: [previewState] llega ya resuelto desde
 * [org.taskhub.ui.models.HomeScreenModel.loadHouseholdPreview] — antes este
 * composable inyectaba `FirestoreRepository` directamente y hacía su propio
 * fetch en un `LaunchedEffect`, el único sitio del árbol que no pasaba por
 * un ScreenModel (panel v7, #15).
 */
/**
 * Tarjeta desplegable de un hogar con sus tareas pendientes.
 *
 * @param household hogar a mostrar (nombre + si es el espacio Personal).
 * @param previewState estado de carga de las tareas pendientes (ya resuelto
 *   por el ScreenModel llamante); `null` se trata igual que `Loading`.
 * @param onViewAll callback al pulsar "ver todas", recibe el id del hogar.
 */
@Composable
fun HouseholdTaskSection(
    household: SavedHousehold,
    previewState: HouseholdPreviewState?,
    onViewAll: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val navigator = LocalNavigator.currentOrThrow
    val lang = LocalAppSettings.current.currentLanguage
    val s = { key: String -> AppStrings.get(key, lang) }
    val reduceMotion = shouldReduceMotion()

    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (household.isPersonal)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.06f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ExpandableSectionHeader(
                expanded = expanded,
                onToggle = { expanded = !expanded }
            ) {
                // Etiqueta visual para espacio Personal
                if (household.isPersonal) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            "👤",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = if (household.isPersonal) household.name
                           else "${if (!household.emoji.isNullOrBlank()) household.emoji else household.spaceType.emoji} ${household.name}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (previewState is HouseholdPreviewState.Success) {
                    Text(
                        text = s("household_task_section_pending_count").replace("%d", "${previewState.tasks.size}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(4.dp))
            }

            AnimatedVisibility(
                visible = expanded,
                enter = if (reduceMotion) EnterTransition.None else expandVertically(),
                exit = if (reduceMotion) ExitTransition.None else shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))

                    when (previewState) {
                        null, is HouseholdPreviewState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = if (household.isPersonal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                        is HouseholdPreviewState.Error -> {
                            Text(
                                text = previewState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        is HouseholdPreviewState.Success -> {
                            if (previewState.tasks.isEmpty()) {
                                Text(
                                    text = if (household.isPersonal) s("household_task_section_empty_personal")
                                           else s("household_task_section_empty_shared"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                previewState.tasks.forEach { task ->
                                    TaskRow(task = task) {
                                        navigator.push(
                                            TaskDetailScreen(household.id, task.id)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    TextButton(
                        onClick = { onViewAll(household.id) },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(s("household_task_section_view_all"))
                    }
                }
            }
        }
    }
}

/** Fila compacta de una tarea pendiente dentro de [HouseholdTaskSection]: puntos + título/tags + fecha límite formateada. */
@Composable
private fun TaskRow(task: TaskResponse, onClick: () -> Unit) {
    val lang = LocalAppSettings.current.currentLanguage
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // role = Button: semántica estructurada para TalkBack/VoiceOver
            // (panel v7, Exp. 3, MENOR).
            .clickable(role = Role.Button, onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "+${task.points}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (task.tags.isNotEmpty()) {
                    Text(
                        text = task.tags.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Progreso de subtareas — mismo badge que TaskListScreen.TaskCard,
            // para que la vista previa de Home no quede desactualizada respecto
            // a la lista completa (revisión UX 2026-09-17, H1).
            if (task.subtasks.isNotEmpty()) {
                val completedSubtasks = task.subtasks.count { it.completed }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = AppStrings.get("task_list_subtask_badge", lang)
                            .replace("%1", completedSubtasks.toString())
                            .replace("%2", task.subtasks.size.toString()),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            if (task.dueDate > 0) {
                Text(
                    text = formatDueDate(task.dueDate, lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** "Hoy"/"Mañana"/"Vencida" para fechas cercanas; "dd/mm" en el resto de casos. */
private fun formatDueDate(epochMillis: Long, lang: String): String {
    val now = Clock.System.now().toEpochMilliseconds()
    val diffDays = (epochMillis - now) / (24 * 60 * 60 * 1000)
    return when {
        diffDays < 0 -> AppStrings.get("due_date_overdue", lang)
        diffDays == 0L -> AppStrings.get("tasks_due_today", lang)
        diffDays == 1L -> AppStrings.get("due_date_tomorrow", lang)
        else -> {
            val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMillis)
            val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
            "${local.dayOfMonth}/${local.monthNumber}"
        }
    }
}
