/**
 * Reglas puras (sin I/O) de puntuación al completar tareas: puntualidad y
 * penalización por retraso. Huérfana: `completeAssignment` delega ahora en
 * la Cloud Function `completeAssignment`/`completeRecurringTask` (lógica
 * server-side, panel v10-v12), así que ningún call-site del cliente invoca
 * estas reglas hoy — confirmado sin referencias fuera de este archivo y su
 * test (panel v15, oleada 2, hallazgo de KDoc desactualizado).
 */
package org.taskhub.network

import org.taskhub.network.models.TaskResponse

/**
 * Reglas puras de puntuación al completar una tarea (puntualidad +
 * penalización por retraso). Sin I/O — testable directamente en `commonTest`.
 *
 * Extraído de `FirestoreRepository` (donde vivía como métodos `private`)
 * para poder testear esta lógica de negocio sin depender de red — panel v4,
 * Experto 13, hueco #1. Ver KDoc de cabecera del archivo: actualmente sin
 * call-sites de producción.
 */
object PenaltyRules {

    /** Resultado de resolver puntos otorgados + puntualidad al completar una tarea. */
    data class CompletionOutcome(val onTime: Boolean, val pointsAwarded: Int)

    /**
     * Calcula si se completó a tiempo + los puntos a otorgar (con penalización
     * por retraso si toca), a partir de una fecha límite concreta.
     *
     * `dueDate == 0` significa "sin fecha límite" y nunca penaliza — incluye
     * tareas recurrentes antiguas sin `nextDueAt` todavía (migración
     * aditiva: fallback al comportamiento previo, sin penalización, hasta
     * que la próxima compleción puebla el campo).
     */
    fun resolveCompletionOutcome(task: TaskResponse, dueDate: Long, now: Long): CompletionOutcome {
        val onTime = dueDate == 0L || now <= dueDate
        val pointsAwarded = if (onTime) {
            task.points
        } else {
            val penalty = calculatePenalty(task, dueDate, now)
            maxOf(task.points - penalty, 0)
        }
        return CompletionOutcome(onTime, pointsAwarded)
    }

    /**
     * Calcula los puntos de penalización de una tarea vencida.
     *
     * - modo `fixed`: resta `penaltyValue` por cada intervalo vencido.
     * - modo `percentage`: resta `penaltyValue`% de `task.points` por cada intervalo vencido.
     * - el resultado se limita a `penaltyMax` (si está fijado; no debería superar `task.points`)
     *   y nunca supera `task.points` (la penalización nunca deja el resultado en negativo).
     */
    fun calculatePenalty(task: TaskResponse, dueDate: Long, now: Long): Int {
        val mode = task.penaltyMode ?: return 0
        if (now <= dueDate) return 0

        val overdueMs = now - dueDate
        val intervalMs = when (task.penaltyInterval) {
            "week" -> 7L * 24 * 60 * 60 * 1000
            "month" -> 30L * 24 * 60 * 60 * 1000
            else -> 24L * 60 * 60 * 1000 // día
        }

        // +1 porque el primer intervalo de penalización empieza en cuanto se vence,
        // no hay que esperar a que transcurra un intervalo completo para penalizar.
        val intervals = (overdueMs / intervalMs).toInt() + 1

        val penalty = when (mode) {
            "fixed" -> task.penaltyValue * intervals
            "percentage" -> (task.points * task.penaltyValue * intervals) / 100
            else -> 0
        }

        // Se limita a penaltyMax (si está fijado) y nunca por debajo de 0 ni por encima de task.points.
        val capped = if (task.penaltyMax > 0) minOf(penalty, task.penaltyMax) else penalty
        return minOf(capped, task.points)
    }
}
