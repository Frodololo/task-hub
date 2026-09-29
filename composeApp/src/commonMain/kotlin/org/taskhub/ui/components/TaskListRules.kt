/**
 * Lógica de filtrado/agrupación de [TaskListScreen][org.taskhub.ui.screens.TaskListScreen]
 * extraída de la pantalla para dejarla solo con UI (panel v18/v20, pendiente
 * técnico). Sin cambios de comportamiento respecto al código original.
 */
package org.taskhub.ui.components

import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.taskhub.network.RecurrenceRules
import org.taskhub.network.models.TaskResponse
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.TaskSort

// ────────────────────────────────────────────────────────────
//  Task is-due-today logic (local calculation, no instances)
// ────────────────────────────────────────────────────────────

/**
 * Modelo de datos simplificado para la UI: une la tarea con su estado calculado.
 *
 * El estado (isDueToday, isCompletedToday, isOverdue) se calcula en cliente
 * a partir de task.frequency + task.lastCompletedDate + task.dueDate.
 * No hay "instancias" en Firestore — una tarea recurrente es UN solo documento.
 */
internal data class TaskWithStatus(
    val task: TaskResponse,
    val isDueToday: Boolean,
    val isCompletedToday: Boolean,
    val isOverdue: Boolean,
    /**
     * Completada y no vuelve a estar pendiente ahora mismo — a diferencia de
     * [isCompletedToday] (solo HOY), cubre cualquier compleción pasada: una
     * tarea "once" ya hecha, o una recurrente completada pero que aún no ha
     * reabierto su ventana de "debida" (p.ej. semanal completada el lunes,
     * consultada el martes). Antes del fix (bug 2026-09-12) no existía este
     * estado y esas tareas desaparecían de TODA la pantalla — ni pendientes
     * ni en el filtro "Completadas" — hasta que la recurrencia las reabría.
     */
    val isCompleted: Boolean
)

internal data class TaskGroup(
    val label: String,
    val sortKey: Int,
    val dateKey: String,
    val isOverdue: Boolean,
    val isDueSoon: Boolean = false,
    val isNoDate: Boolean,
    val items: List<TaskWithStatus>
)

/**
 * Determines if a task is due today based on frequency + lastCompletedDate.
 *
 * - daily: always due (if not completed today)
 * - weekly: due if today matches recurrenceDays and not completed today
 * - monthly: due if not completed this month
 * - once: due if has dueDate and not completed yet
 */
internal fun isTaskDueToday(task: TaskResponse, todayStartEpoch: Long): Boolean =
    RecurrenceRules.isDueToday(
        frequency = task.frequency,
        recurrenceDays = task.recurrenceDays,
        recurrenceDay = task.recurrenceDay,
        lastCompletedDate = task.lastCompletedDate,
        nowEpochMs = todayStartEpoch,
        createdAt = task.createdAt,
        dueDate = task.dueDate
    )

/**
 * Determines if a task was completed today.
 */
internal fun isTaskCompletedToday(task: TaskResponse, todayStartEpoch: Long): Boolean {
    val lcd = task.lastCompletedDate ?: return false
    return lcd >= todayStartEpoch
}

// ────────────────────────────────────────────────────────────
//  Localized day-of-week helper
// ────────────────────────────────────────────────────────────

private fun localizedDayName(dayOfWeek: DayOfWeek, lang: String): String = when (dayOfWeek) {
    DayOfWeek.MONDAY -> AppStrings.get("recurrence_day_monday", lang)
    DayOfWeek.TUESDAY -> AppStrings.get("recurrence_day_tuesday", lang)
    DayOfWeek.WEDNESDAY -> AppStrings.get("recurrence_day_wednesday", lang)
    DayOfWeek.THURSDAY -> AppStrings.get("recurrence_day_thursday", lang)
    DayOfWeek.FRIDAY -> AppStrings.get("recurrence_day_friday", lang)
    DayOfWeek.SATURDAY -> AppStrings.get("recurrence_day_saturday", lang)
    DayOfWeek.SUNDAY -> AppStrings.get("recurrence_day_sunday", lang)
    else -> ""
}

// ────────────────────────────────────────────────────────────
//  Group tasks by status (not instances — calculated locally)
// ────────────────────────────────────────────────────────────

/**
 * Comparador según la opción de orden elegida por el usuario en el menú
 * desplegable. Antes se ignoraba por completo: el icono del menú cambiaba
 * pero la lista siempre se ordenaba por fecha límite + puntos.
 */
private fun taskComparator(sort: TaskSort): Comparator<TaskWithStatus> = when (sort) {
    TaskSort.DEADLINE_ASC -> compareBy { if (it.task.dueDate > 0) it.task.dueDate else Long.MAX_VALUE }
    TaskSort.DEADLINE_DESC -> compareByDescending { it.task.dueDate }
    TaskSort.POINTS_DESC -> compareByDescending { it.task.points }
    TaskSort.CREATED_DESC -> compareByDescending { it.task.createdAt }
}

internal fun groupTasksByStatus(
    items: List<TaskWithStatus>,
    sort: TaskSort,
    lang: String
): List<TaskGroup> {
    val dueItems = items.filter { it.isDueToday && !it.isCompletedToday }
    val overdueItems = dueItems.filter { it.isOverdue }
    val pendingToday = dueItems.filter { !it.isOverdue }
    val completedToday = items.filter { it.isCompletedToday }
    // Completadas en días ANTERIORES (bug 2026-09-12): sin este grupo, estas
    // tareas no caían en ningún bucket de arriba y desaparecían de la
    // pantalla por completo, incluido el filtro "Completadas" (ver [isCompleted]).
    val completedOther = items.filter { it.isCompleted && !it.isCompletedToday }

    val groups = mutableListOf<TaskGroup>()
    val comparator = taskComparator(sort)

    // Overdue
    if (overdueItems.isNotEmpty()) {
        val sorted = overdueItems.sortedWith(comparator)
        groups.add(TaskGroup(
            label = AppStrings.get("tasks_overdue", lang),
            sortKey = 0,
            dateKey = "overdue",
            isOverdue = true,
            isNoDate = false,
            items = sorted
        ))
    }

    // Due today
    if (pendingToday.isNotEmpty()) {
        val tz = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(tz).date
        val dow = today.dayOfWeek
        val dayStr = localizedDayName(dow, lang)
        val sorted = pendingToday.sortedWith(comparator)
        groups.add(TaskGroup(
            label = AppStrings.get("task_list_today_header", lang)
                .replace("%1", dayStr)
                .replace("%2", today.dayOfMonth.toString()),
            sortKey = 1,
            dateKey = "today",
            isOverdue = false,
            isDueSoon = true,
            isNoDate = false,
            items = sorted
        ))
    }

    // Pending other: tasks not due today and not completed (future dates,
    // or no-date tasks that pass the MINE filter). Without this group,
    // tasks assigned to the current member with a future dueDate or no
    // dueDate pass the MINE filter but disappear because no group matches.
    val pendingOther = items.filter { !it.isDueToday && !it.isCompleted }
    if (pendingOther.isNotEmpty()) {
        val sorted = pendingOther.sortedWith(comparator)
        groups.add(TaskGroup(
            label = AppStrings.get("calendar_pending_section", lang),
            sortKey = 2,
            dateKey = "pending_other",
            isOverdue = false,
            isDueSoon = false,
            isNoDate = true,
            items = sorted
        ))
    }

    // Completed today
    if (completedToday.isNotEmpty()) {
        val sorted = completedToday.sortedByDescending { it.task.lastCompletedDate ?: 0 }
        groups.add(TaskGroup(
            label = AppStrings.get("tasks_completed_today", lang),
            sortKey = 99,
            dateKey = "completed_today",
            isOverdue = false,
            isNoDate = false,
            items = sorted
        ))
    }

    // Completed on a previous day — ver KDoc de [TaskWithStatus.isCompleted].
    if (completedOther.isNotEmpty()) {
        val sorted = completedOther.sortedByDescending { it.task.lastCompletedDate ?: 0 }
        groups.add(TaskGroup(
            label = AppStrings.get("tasks_completed_other", lang),
            sortKey = 100,
            dateKey = "completed_other",
            isOverdue = false,
            isNoDate = false,
            items = sorted
        ))
    }

    return groups
}
