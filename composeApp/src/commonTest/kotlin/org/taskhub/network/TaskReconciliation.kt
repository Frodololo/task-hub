// Lógica pura (sin I/O) de detección de tareas "completadas sin puntos".
// Huérfana en el cliente (sin call-sites fuera de su propio test): la
// reparación real la hace la Cloud Function `reconcileMissingTaskPoints`
// (`functions/src/reconcileMissingTaskPoints.ts`, job programado en backend),
// no este objeto — confirmado por [org.taskhub.ui.models.TaskScreenModel],
// que documenta que la reconciliación ya no se dispara desde el cliente
// (panel v15, oleada 2, hallazgo de KDoc desactualizado).
package org.taskhub.network

import org.taskhub.network.models.TaskHistoryResponse
import org.taskhub.network.models.TaskResponse

/**
 * Detecta tareas marcadas como completadas (`completedBy`/`lastCompletedDate`
 * fijados) cuyo registro de `taskHistory` correspondiente falta, o existe
 * pero quedó con `pointsApplied=false` — el rastro de un fallo entre el PATCH
 * que marca la tarea completada y el otorgamiento de puntos/historial (ver
 * KDoc de [FirestoreRepository.completeTask]).
 *
 * Identifica el registro de una compleción por (taskId, completedAt) — igual
 * que el resto del código de deshacer/reasignar (ver
 * [FirestoreRepository.undoTaskCompletionAssignments]), así que no puede
 * confundir dos compleciones distintas de la misma tarea entre sí.
 */
object TaskReconciliation {
    fun findTasksNeedingPointsReconciliation(
        tasks: List<TaskResponse>,
        history: List<TaskHistoryResponse>
    ): List<TaskResponse> {
        return tasks.filter { task ->
            val completedAt = task.lastCompletedDate
            if (task.completedBy == null || completedAt == null) {
                false
            } else {
                val record = history.find { it.taskId == task.id && it.completedAt == completedAt }
                record == null || !record.pointsApplied
            }
        }
    }
}
