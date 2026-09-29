package org.taskhub.ui.screens

import org.taskhub.network.models.TaskResponse
import org.taskhub.ui.components.TaskWithStatus
import org.taskhub.ui.components.groupTasksByStatus
import org.taskhub.ui.models.TaskSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test de regresión para el bug reportado 2026-09-12: una tarea "once"
 * completada en un día ANTERIOR a hoy (o una recurrente completada pero
 * aún no vuelta a estar "debida") no caía en ningún grupo de
 * [groupTasksByStatus] — ni pendiente, ni "Completadas hoy" — y
 * desaparecía de la pantalla por completo, incluido el filtro
 * "Completadas" (que antes del fix solo miraba [TaskWithStatus.isCompletedToday]).
 * Ver `ui/screens/TaskListScreen.kt`, [TaskWithStatus.isCompleted].
 */
class TaskListScreenTest {

    private fun task(id: String = "t1") = TaskResponse(
        id = id,
        householdId = "h1",
        createdBy = "m1",
        title = "Tarea $id"
    )

    private fun status(
        task: TaskResponse,
        isDueToday: Boolean,
        isCompletedToday: Boolean,
        isOverdue: Boolean = false,
        isCompleted: Boolean
    ) = TaskWithStatus(task, isDueToday, isCompletedToday, isOverdue, isCompleted)

    @Test
    fun `tarea once completada ayer aparece en un grupo, no desaparece`() {
        val t = task("once-ayer")
        // isDueToday=false ("once" ya completada nunca vuelve a estar debida),
        // isCompletedToday=false (se completó AYER, no hoy), isCompleted=true.
        val items = listOf(status(t, isDueToday = false, isCompletedToday = false, isCompleted = true))

        val groups = groupTasksByStatus(items, TaskSort.DEADLINE_ASC, "es")

        val allGroupedIds = groups.flatMap { g -> g.items.map { it.task.id } }
        assertTrue(t.id in allGroupedIds, "la tarea completada ayer debe aparecer en algún grupo")
    }

    @Test
    fun `tarea completada ayer va al grupo completedOther, no al de hoy`() {
        val t = task("once-ayer")
        val items = listOf(status(t, isDueToday = false, isCompletedToday = false, isCompleted = true))

        val groups = groupTasksByStatus(items, TaskSort.DEADLINE_ASC, "es")

        val other = groups.single { it.dateKey == "completed_other" }
        assertEquals(listOf(t.id), other.items.map { it.task.id })
        assertTrue(groups.none { it.dateKey == "completed_today" })
    }

    @Test
    fun `tarea completada hoy sigue yendo al grupo completed_today`() {
        val t = task("hoy")
        val items = listOf(status(t, isDueToday = false, isCompletedToday = true, isCompleted = true))

        val groups = groupTasksByStatus(items, TaskSort.DEADLINE_ASC, "es")

        val today = groups.single { it.dateKey == "completed_today" }
        assertEquals(listOf(t.id), today.items.map { it.task.id })
        assertTrue(groups.none { it.dateKey == "completed_other" })
    }

    @Test
    fun `tarea recurrente pendiente hoy no cuenta como completada aunque tenga lastCompletedDate previo`() {
        val t = task("recurrente-debida-hoy")
        // Una diaria completada ayer y de nuevo debida hoy: isDueToday=true, no completada.
        val items = listOf(status(t, isDueToday = true, isCompletedToday = false, isCompleted = false))

        val groups = groupTasksByStatus(items, TaskSort.DEADLINE_ASC, "es")

        assertTrue(groups.none { it.dateKey == "completed_other" || it.dateKey == "completed_today" })
        val pending = groups.single { it.dateKey == "today" }
        assertEquals(listOf(t.id), pending.items.map { it.task.id })
    }
}
