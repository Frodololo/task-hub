package org.taskhub.ui.models

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import org.taskhub.network.FirestoreRepository
import org.taskhub.network.models.AssignmentSlot
import org.taskhub.network.models.HouseholdResponse
import org.taskhub.network.models.MemberResponse
import org.taskhub.network.models.Subtask
import org.taskhub.network.models.TaskAssignmentResponse
import org.taskhub.network.models.TaskHistoryResponse
import org.taskhub.network.models.TaskResponse
import org.taskhub.storage.SettingsStore
import org.taskhub.storage.TaskCache

/**
 * Doble de prueba de [FirestoreRepository] para [TaskScreenModelTest]: la
 * clase real es un `final` que habla HTTP de verdad, así que [TaskScreenModel]
 * nunca tuvo un test de clase (tarjeta kanban "TaskScreenModel god object sin
 * test de clase"). [FirestoreRepository] se marcó `open` (igual que los
 * métodos que usa este ScreenModel) únicamente para permitir este subclaseo —
 * sin lógica de producción tocada, solo el modificador de visibilidad de
 * override.
 *
 * Los métodos que [TaskScreenModel] NO usa (la inmensa mayoría de la fachada,
 * ~100 métodos) se dejan sin overridear a propósito: seguirían intentando I/O
 * real si algún test los alcanzara, lo cual es la señal correcta de que ese
 * escenario no está cubierto por este doble.
 */
class FakeFirestoreRepository(
    settingsStore: SettingsStore = SettingsStore(InMemorySettings())
) : FirestoreRepository(taskCache = TaskCache(InMemorySettings()), settingsStore = settingsStore) {

    var tasks: List<TaskResponse> = emptyList()
    var assignments: List<TaskAssignmentResponse> = emptyList()
    var members: List<MemberResponse> = emptyList()
    var online: Boolean = true
    var household: HouseholdResponse = HouseholdResponse(id = "h1", name = "Hogar", inviteCode = "ABC123")
    var taskHistory: List<TaskHistoryResponse> = emptyList()
    var memberAchievements: Set<String> = emptySet()
    var currentMemberId: String = "m1"

    var getTasksError: Throwable? = null
    var completeTaskResult = TaskCompletionResult(completedAt = 1_000L, pointsAwarded = 10, onTime = true)
    var completeTaskError: Throwable? = null

    /** Si es `true`, [getTask] se queda colgada indefinidamente — usado para simular una llamada "en vuelo" y poder testear guards anti-doble-tap sin condiciones de carrera reales. */
    var hangGetTask: Boolean = false
    private val getTaskHangGate = CompletableDeferred<Unit>()

    /** Si es `true`, [completeTask] se queda colgada hasta [releaseCompleteTask] — usado para reproducir el hallazgo C4 (panel v16): undo disparado mientras completeTask() sigue en vuelo. */
    var hangCompleteTask: Boolean = false
    private val completeTaskHangGate = CompletableDeferred<Unit>()
    fun releaseCompleteTask() {
        completeTaskHangGate.complete(Unit)
    }

    /** Valor que devuelve [undoTaskCompletion] — controla si el servidor "revirtió" de verdad (ver KDoc de `UndoTaskCompletionResult`). */
    var undoTaskCompletionReverted: Boolean = true

    val createTaskCalls = mutableListOf<String>()
    val assignTaskCalls = mutableListOf<List<String>>()
    val completeTaskCalls = mutableListOf<Triple<String, String, String>>()
    /** `idempotencyKey` recibido en cada llamada a [completeTask], en orden — ver `TaskScreenModelTest` (contrato de idempotencia). */
    val completeTaskIdempotencyKeys = mutableListOf<String>()
    val undoTaskCompletionCalls = mutableListOf<Long>()
    val updateMemberStreakCalls = mutableListOf<Int>()
    val addMemberAchievementCalls = mutableListOf<String>()
    val reassignTaskCompletionCalls = mutableListOf<String>()
    val deleteTaskCalls = mutableListOf<String>()
    val updateSubtasksCalls = mutableListOf<List<Subtask>>()

    override suspend fun isHouseholdOwner(householdId: String): Boolean = false

    override suspend fun getHousehold(id: String): HouseholdResponse = household

    override suspend fun getMembers(householdId: String): List<MemberResponse> = members

    override suspend fun isOnline(): Boolean = online

    override suspend fun resolveCurrentMember(householdId: String): String = currentMemberId

    override suspend fun updateMemberStreak(
        householdId: String,
        memberId: String,
        currentStreak: Int,
        bestStreak: Int,
        lastStreakDate: Long
    ) {
        updateMemberStreakCalls += currentStreak
    }

    override suspend fun getMemberAchievements(householdId: String, memberId: String): Set<String> = memberAchievements

    override suspend fun addMemberAchievement(householdId: String, memberId: String, achievementId: String) {
        addMemberAchievementCalls += achievementId
    }

    override suspend fun createTask(
        householdId: String,
        createdBy: String,
        title: String,
        description: String,
        points: Int,
        frequency: String,
        recurrenceDays: List<Int>,
        recurrenceDay: Int?,
        tags: List<String>,
        subtasks: List<Subtask>,
        penaltyMode: String?,
        penaltyValue: Int,
        penaltyInterval: String,
        penaltyMax: Int,
        dueDate: Long,
        assignmentRotation: List<AssignmentSlot>
    ): TaskResponse {
        createTaskCalls += title
        return TaskResponse(
            id = "new-task",
            householdId = householdId,
            createdBy = createdBy,
            title = title,
            points = points,
            frequency = frequency,
            dueDate = dueDate
        )
    }

    override suspend fun getTasks(householdId: String): List<TaskResponse> {
        getTasksError?.let { throw it }
        return tasks
    }

    override suspend fun getTask(householdId: String, taskId: String): TaskResponse {
        if (hangGetTask) getTaskHangGate.await()
        return tasks.find { it.id == taskId } ?: error("FakeFirestoreRepository: task $taskId not stubbed")
    }

    override suspend fun completeTask(
        householdId: String,
        taskId: String,
        memberId: String,
        task: TaskResponse,
        idempotencyKey: String
    ): TaskCompletionResult {
        completeTaskCalls += Triple(householdId, taskId, memberId)
        completeTaskIdempotencyKeys += idempotencyKey
        if (hangCompleteTask) completeTaskHangGate.await()
        completeTaskError?.let { throw it }
        return completeTaskResult
    }

    override suspend fun undoTaskCompletion(householdId: String, taskId: String, completedAt: Long): Boolean {
        undoTaskCompletionCalls += completedAt
        return undoTaskCompletionReverted
    }

    override suspend fun getTaskHistory(householdId: String): List<TaskHistoryResponse> = taskHistory

    override suspend fun reassignTaskCompletion(
        householdId: String,
        taskId: String,
        taskPoints: Int,
        newMemberId: String
    ) {
        reassignTaskCompletionCalls += newMemberId
    }

    override suspend fun assignTask(
        householdId: String,
        taskId: String,
        memberIds: List<String>,
        mandatory: Boolean,
        dueDate: Long,
        taskTitle: String,
        assignedByMemberId: String?
    ): List<TaskAssignmentResponse> {
        assignTaskCalls += memberIds
        return memberIds.mapIndexed { i, memberId ->
            TaskAssignmentResponse(id = "assignment-$i", taskId = taskId, memberId = memberId, dueDate = dueDate)
        }
    }

    override suspend fun getAssignments(householdId: String, taskId: String): List<TaskAssignmentResponse> =
        assignments.filter { it.taskId == taskId }

    override suspend fun replaceAssignments(
        householdId: String,
        taskId: String,
        memberIds: List<String>,
        mandatory: Boolean,
        dueDate: Long,
        taskTitle: String
    ): List<TaskAssignmentResponse> = memberIds.mapIndexed { i, memberId ->
        TaskAssignmentResponse(id = "replaced-$i", taskId = taskId, memberId = memberId, dueDate = dueDate)
    }

    override suspend fun getAllAssignments(householdId: String, tasks: List<TaskResponse>): List<TaskAssignmentResponse> =
        assignments

    override suspend fun completeAssignment(
        householdId: String,
        taskId: String,
        task: TaskResponse,
        assignmentId: String,
        assignment: TaskAssignmentResponse,
        idempotencyKey: String
    ): TaskAssignmentResponse = assignment.copy(
        status = "completed",
        completedAt = 1_000L,
        pointsAwarded = task.points,
        onTime = true
    )

    override suspend fun updateAssignmentGoogleEventId(
        householdId: String,
        taskId: String,
        assignmentId: String,
        googleEventId: String?
    ) = Unit

    override suspend fun updateTask(
        householdId: String,
        taskId: String,
        title: String,
        description: String,
        points: Int,
        frequency: String,
        recurrenceDays: List<Int>,
        recurrenceDay: Int?,
        tags: List<String>,
        subtasks: List<Subtask>,
        penaltyMode: String?,
        penaltyValue: Int,
        penaltyInterval: String,
        penaltyMax: Int,
        assignmentRotation: List<AssignmentSlot>,
        dueDate: Long,
        lastCompletedDate: Long?
    ): Long? = null

    override suspend fun updateSubtasks(householdId: String, taskId: String, transform: (List<Subtask>) -> List<Subtask>) {
        val current = tasks.find { it.id == taskId }?.subtasks ?: emptyList()
        updateSubtasksCalls += transform(current)
    }

    override suspend fun deleteTask(householdId: String, taskId: String) {
        deleteTaskCalls += taskId
    }
}

/** Doble de prueba mínimo de [Settings], en memoria (mismo patrón que `SettingsStoreTest.FakeSettings`: `MapSettings` no está entre las dependencias del proyecto). */
class InMemorySettings(initial: MutableMap<String, Any> = mutableMapOf()) : Settings {
    private val map = initial

    override val keys: Set<String> get() = map.keys
    override val size: Int get() = map.size

    override fun clear() { map.clear() }
    override fun remove(key: String) { map.remove(key) }
    override fun hasKey(key: String): Boolean = map.containsKey(key)

    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getInt(key: String, defaultValue: Int): Int = map[key] as? Int ?: defaultValue
    override fun getIntOrNull(key: String): Int? = map[key] as? Int

    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getLong(key: String, defaultValue: Long): Long = map[key] as? Long ?: defaultValue
    override fun getLongOrNull(key: String): Long? = map[key] as? Long

    override fun putString(key: String, value: String) { map[key] = value }
    override fun getString(key: String, defaultValue: String): String = map[key] as? String ?: defaultValue
    override fun getStringOrNull(key: String): String? = map[key] as? String

    override fun putFloat(key: String, value: Float) { map[key] = value }
    override fun getFloat(key: String, defaultValue: Float): Float = map[key] as? Float ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = map[key] as? Float

    override fun putDouble(key: String, value: Double) { map[key] = value }
    override fun getDouble(key: String, defaultValue: Double): Double = map[key] as? Double ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = map[key] as? Double

    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = map[key] as? Boolean ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = map[key] as? Boolean
}

/** Doble de prueba de [CalendarSyncManager] — trivial gracias a la interfaz extraída de [CalendarSyncManagerImpl] para este fin. */
class FakeCalendarSyncManager : CalendarSyncManager {
    val onTaskAssignedCalls = mutableListOf<List<TaskAssignmentResponse>>()
    val onTaskUnassignedCalls = mutableListOf<TaskAssignmentResponse>()
    val onTaskCompletedCalls = mutableListOf<TaskAssignmentResponse>()
    var syncNowResult: Boolean = true

    override suspend fun onTaskAssigned(
        householdId: String,
        householdName: String,
        isPersonal: Boolean,
        assignments: List<TaskAssignmentResponse>
    ) {
        onTaskAssignedCalls += assignments
    }

    override suspend fun onTaskUnassigned(householdId: String, assignment: TaskAssignmentResponse) {
        onTaskUnassignedCalls += assignment
    }

    override suspend fun onTaskCompleted(householdId: String, assignment: TaskAssignmentResponse) {
        onTaskCompletedCalls += assignment
    }

    override suspend fun onDueDateChanged(
        householdId: String,
        householdName: String,
        isPersonal: Boolean,
        assignment: TaskAssignmentResponse,
        newDueDate: Long,
        taskTitle: String,
        taskDescription: String
    ) = Unit

    override suspend fun reconcile(householdId: String, householdName: String, isPersonal: Boolean) = Unit

    override suspend fun syncNow(
        householdId: String,
        householdName: String,
        isPersonal: Boolean,
        assignment: TaskAssignmentResponse,
        task: TaskResponse
    ): Boolean = syncNowResult
}

/** Doble de prueba de [org.taskhub.platform.NotificationScheduler] con conteo de llamadas. */
class FakeNotificationScheduler : org.taskhub.platform.NotificationScheduler {
    val scheduledTaskIds = mutableListOf<String>()
    val cancelledTaskIds = mutableListOf<String>()

    override fun scheduleReminder(taskId: String, householdId: String, taskTitle: String, dueDateEpochMs: Long) {
        scheduledTaskIds += taskId
    }

    override fun cancelReminder(taskId: String) {
        cancelledTaskIds += taskId
    }

    override fun saveFcmToken(token: String) = Unit
    override fun getFcmToken(): String? = null
}
