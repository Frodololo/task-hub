package org.taskhub.network

import org.taskhub.network.models.TaskAssignmentResponse
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TaskListRules] replica en cliente la autorización que las Cloud Functions
 * `completeAssignment`/`completeRecurringTask`/`reassignTaskCompletion` ya
 * exigen server-side: no completar dos veces, y solo el miembro asignado o
 * un admin/owner puede completar/revertir/reasignar.
 */
class TaskListRulesTest {

    private fun assignment(
        memberId: String = "m1",
        status: String = "assigned"
    ) = TaskAssignmentResponse(
        id = "a1",
        taskId = "t1",
        memberId = memberId,
        status = status
    )

    @Test
    fun canComplete_assignedMemberOnPendingAssignment_true() {
        assertTrue(TaskListRules.canComplete(assignment(memberId = "m1"), currentMemberId = "m1", isAdmin = false))
    }

    @Test
    fun canComplete_adminOnSomeoneElsesAssignment_true() {
        assertTrue(TaskListRules.canComplete(assignment(memberId = "m1"), currentMemberId = "m2", isAdmin = true))
    }

    @Test
    fun canComplete_nonAssignedNonAdmin_false() {
        assertFalse(TaskListRules.canComplete(assignment(memberId = "m1"), currentMemberId = "m2", isAdmin = false))
    }

    @Test
    fun canComplete_alreadyCompleted_falseEvenForAssignee() {
        assertFalse(
            TaskListRules.canComplete(
                assignment(memberId = "m1", status = "completed"),
                currentMemberId = "m1",
                isAdmin = false
            )
        )
    }

    @Test
    fun canComplete_noCurrentMember_false() {
        assertFalse(TaskListRules.canComplete(assignment(memberId = "m1"), currentMemberId = null, isAdmin = true))
    }

    @Test
    fun canRevert_assigneeOnCompleted_true() {
        assertTrue(
            TaskListRules.canRevert(
                assignment(memberId = "m1", status = "completed"),
                currentMemberId = "m1",
                isAdmin = false
            )
        )
    }

    @Test
    fun canRevert_stillPending_false() {
        assertFalse(
            TaskListRules.canRevert(assignment(memberId = "m1", status = "assigned"), currentMemberId = "m1", isAdmin = false)
        )
    }

    @Test
    fun canRevert_notAssigneeNorAdmin_false() {
        assertFalse(
            TaskListRules.canRevert(
                assignment(memberId = "m1", status = "completed"),
                currentMemberId = "m2",
                isAdmin = false
            )
        )
    }

    @Test
    fun canReassign_onlyAdmin() {
        assertTrue(TaskListRules.canReassign(isAdmin = true))
        assertFalse(TaskListRules.canReassign(isAdmin = false))
    }
}
