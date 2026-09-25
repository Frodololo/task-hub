/**
 * Reglas puras (sin I/O) de quién puede completar/reasignar una asignación
 * de tarea — replican en el cliente, para gatear la UI, las mismas
 * comprobaciones que las Cloud Functions `completeAssignment`/
 * `completeRecurringTask` ya exigen server-side (ver `functions/src/auth.ts`,
 * `isTrustedMember`): el servidor es la autoridad real, esto solo evita
 * ofrecer en la UI una acción que el servidor rechazaría.
 */
package org.taskhub.network

import org.taskhub.network.models.TaskAssignmentResponse

object TaskListRules {

    /**
     * Si el usuario actual puede completar [assignment]: debe seguir
     * "assigned" (no completarla dos veces) y quien la completa debe ser
     * el propio miembro asignado o un admin/owner del hogar.
     */
    fun canComplete(
        assignment: TaskAssignmentResponse,
        currentMemberId: String?,
        isAdmin: Boolean
    ): Boolean =
        assignment.status == "assigned" &&
            currentMemberId != null &&
            (assignment.memberId == currentMemberId || isAdmin)

    /** Revertir una compleción exige lo mismo que completarla: autor o admin/owner. */
    fun canRevert(
        assignment: TaskAssignmentResponse,
        currentMemberId: String?,
        isAdmin: Boolean
    ): Boolean =
        assignment.status == "completed" &&
            currentMemberId != null &&
            (assignment.memberId == currentMemberId || isAdmin)

    /** Reasignar una tarea a otro miembro — solo owner/admin (ver `reassignTaskCompletion.ts`, `requireTrusted`). */
    fun canReassign(isAdmin: Boolean): Boolean = isAdmin
}
