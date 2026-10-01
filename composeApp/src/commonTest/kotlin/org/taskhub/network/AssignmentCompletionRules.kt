/**
 * Regla pura (sin I/O) sobre asignaciones "hermanas" de una misma tarea
 * recurrente/compartida. Huérfana: [FirestoreRepository.completeAssignment]
 * delega ahora en la Cloud Function `completeAssignment`/`completeRecurringTask`
 * (lógica server-side, panel v10-v12), así que ningún call-site del cliente
 * invoca [siblingsToClose] hoy — confirmado sin referencias fuera de este
 * archivo y su test (panel v15, oleada 2, hallazgo de KDoc desactualizado).
 */
package org.taskhub.network

import org.taskhub.network.models.TaskAssignmentResponse

/**
 * Regla pura de "qué asignaciones hermanas cerrar" al completar una — extraída
 * en su día de `FirestoreRepository` (donde vivía como método `private`) para
 * poder testearla sin red (panel de revisión 2026-09-03/04, Experto 13: hueco
 * CRÍTICO, solo cubrible con `ktor-client-mock` o extrayendo la lógica a
 * función pura; panel v7, #31, opción elegida). Ver KDoc de cabecera del
 * archivo: actualmente sin call-sites de producción.
 */
object AssignmentCompletionRules {

    /**
     * De todas las asignaciones de la tarea ([allAssignments]), cuáles son
     * "hermanas" del mismo ciclo que deben marcarse completadas (con 0
     * puntos, ver KDoc de `completeAssignment`) tras completar
     * [completedAssignmentId]: todas las que sigan "assigned" salvo la que
     * ya se completó explícitamente.
     */
    fun siblingsToClose(
        allAssignments: List<TaskAssignmentResponse>,
        completedAssignmentId: String
    ): List<TaskAssignmentResponse> =
        allAssignments.filter { it.id != completedAssignmentId && it.status == "assigned" }
}
