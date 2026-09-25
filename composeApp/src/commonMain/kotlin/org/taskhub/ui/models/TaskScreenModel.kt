// ScreenModel principal de tareas: listado/detalle, crear/editar/borrar,
// completar (con puntos/penalización/racha/logros), deshacer compleción,
// reasignar quién la hizo y sincronización best-effort con Google Calendar.
// Ver el diagrama de arquitectura más abajo para cómo encaja con
// [FirestoreRepository] y Firestore.
package org.taskhub.ui.models

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.taskhub.network.ErrorCategory
import org.taskhub.network.errorCategory
import org.taskhub.network.FirestoreRepository
import org.taskhub.network.StreakRules
import org.taskhub.network.TaskConflictException
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.i18n.toUserMessage
import org.taskhub.network.models.TaskResponse
import org.taskhub.network.models.TaskAssignmentResponse
import org.taskhub.network.models.MemberResponse
import org.taskhub.network.models.AssignmentSlot
import org.taskhub.network.models.Subtask
import org.taskhub.platform.NotificationScheduler
import org.taskhub.platform.DebugFlags
import org.taskhub.platform.AdController
import org.taskhub.platform.HapticKind
import org.taskhub.ui.components.hapticsEnabled
import org.taskhub.platform.logAnalyticsEvent
import org.taskhub.platform.vibrate
import org.taskhub.storage.SettingsStore
import kotlinx.datetime.*

/**
 * Arquitectura de la app Task Hub (para devs nuevos):
 *
 * ┌──────────────────────────────────────────────────────────┐
 * │  UI (Voyager Screens)                                   │
 * │  TaskListScreen, HouseholdScreen, CreateTaskScreen...    │
 * │  → Observan StateFlows del ScreenModel                  │
 * │  → Toda la lógica de filtro/agrupación/vencimiento      │
 * │    está en TaskListScreen.kt (funciones privadas)       │
 * └────────────┬─────────────────────────────────────────────┘
 *              │
 * ┌────────────▼─────────────────────────────────────────────┐
 * │  ScreenModels (ViewModels de Voyager)                   │
 * │  TaskScreenModel ← este archivo                         │
 * │  → loadTasks(): dispara fetch a Firestore + actualiza   │
 * │    widget y estado                                      │
 * │  → createTask()/completeTask(): escritura + refresh     │
 * └────────────┬─────────────────────────────────────────────┘
 *              │
 * ┌────────────▼─────────────────────────────────────────────┐
 * │  FirestoreRepository (REST API directa, sin servidor)   │
 * │  → Auth Google (signInWithIdp → idToken → Bearer)       │
 * │  → CRUD: households, tasks, members, assignments        │
 * │  → Los tasks son documentos planos (no instancias por   │
 * │    día). La recurrencia se calcula en cliente.          │
 * └────────────┬─────────────────────────────────────────────┘
 *              │
 * ┌────────────▼─────────────────────────────────────────────┐
 * │  Firestore (NoSQL)                                      │
 * │  Estructura:                                            │
 * │  households/{id}/                                       │
 * │    ├── fields: name, inviteCode, createdAt              │
 * │    ├── tasks/{id}/                                      │
 * │    │   ├── fields: title, frequency, lastCompletedDate, │
 * │    │   │          dueDate, points, tags, penalty...      │
 * │    │   └── assignments/{id}/                            │
 * │    │       └── fields: memberId, dueDate, status        │
 * │    ├── members/{id}/                                    │
 * │    │   └── fields: displayName, role, totalPoints       │
 * │    └── taskHistory/{id}/                                │
 * │        └── fields: taskId, memberId, points, completedAt│
 * └──────────────────────────────────────────────────────────┘
 *
 * Modelo de datos simplificado (sin instancias):
 *   - Las tareas NO generan documentos por cada ocurrencia.
 *   - Una tarea "daily" es UN solo documento con lastCompletedDate.
 *   - isTaskDueToday() calcula en cliente si toca hoy.
 *   - Esto evita el problema de las instancias huérfanas/duplicadas.
 *
 * Flujo de la app:
 *   1. App.kt → HomeScreen (dashboard unificado con espacio Personal + hogares)
 *   2. HouseholdScreen → botón "Ver Tareas" → TaskListScreen
 *   3. TaskListScreen → carga tareas → filtra por PENDING (default) → agrupa por día
 *   4. Al crear/completar tarea → loadTasks() refresca + actualiza widget Android
 */

// ── UI State ──────────────────────────────────────────────

/** Estados de carga del listado de tareas de un hogar. */
sealed class TaskListUiState {
    data object Idle : TaskListUiState()
    data object Loading : TaskListUiState()
    data class Success(
        val tasks: List<TaskResponse>,
        val assignments: List<TaskAssignmentResponse>,
        val members: List<MemberResponse>
    ) : TaskListUiState()
    data class Error(val message: String) : TaskListUiState()
}

/** Estados de carga del detalle de una tarea concreta. */
sealed class TaskDetailUiState {
    data object Idle : TaskDetailUiState()
    data object Loading : TaskDetailUiState()
    data class Success(
        val task: TaskResponse,
        val assignments: List<TaskAssignmentResponse>,
        val members: List<MemberResponse>
    ) : TaskDetailUiState()
    data class Error(val message: String) : TaskDetailUiState()
}

/** Estado genérico de una mutación en curso (crear/completar/asignar/editar/borrar). */
sealed class TaskActionState {
    data object Idle : TaskActionState()
    data object Loading : TaskActionState()
    data object Success : TaskActionState()
    data class Error(val message: String) : TaskActionState()
}

// ── Filter & Sort ─────────────────────────────────────────

/** Filtro aplicado al listado de tareas (aplicado en `TaskListScreen`, no aquí). */
enum class TaskFilter {
    ALL,
    PENDING,
    COMPLETED,
    MINE
}

/** Orden aplicado al listado de tareas (aplicado en `TaskListScreen`, no aquí). */
enum class TaskSort {
    DEADLINE_ASC,
    DEADLINE_DESC,
    POINTS_DESC,
    CREATED_DESC
}

// ── ScreenModel ───────────────────────────────────────────

/**
 * ScreenModel de `TaskListScreen`/`TaskDetailScreen`. Expone por separado el
 * estado del listado ([listState]), el del detalle ([detailState]) y el de
 * la última mutación disparada ([actionState]/[reassignState]), para que
 * ambas pantallas puedan compartir instancia (vía Koin) sin pisarse el
 * estado entre sí.
 */
class TaskScreenModel(
    private val repo: FirestoreRepository,
    private val notificationScheduler: NotificationScheduler,
    private val calendarSync: CalendarSyncManager,
    private val adController: AdController,
    private val settingsStore: SettingsStore
) : ScreenModel {

    private fun buzz(kind: HapticKind) {
        if (hapticsEnabled(settingsStore)) vibrate(kind)
    }

    private fun s(key: String) = AppStrings.get(key, settingsStore.getLanguage())

    /** Ver [FirestoreRepository.isHouseholdOwner]. */
    suspend fun isHouseholdOwner(householdId: String): Boolean = repo.isHouseholdOwner(householdId)

    /** Ver [FirestoreRepository.getAssignments]. Usado por [EditTaskScreen] para precargar asignaciones. */
    suspend fun getAssignments(householdId: String, taskId: String): List<TaskAssignmentResponse> =
        repo.getAssignments(householdId, taskId)

    /** Ver [SettingsStore.hasGoogleLinked]. */
    fun hasGoogleLinked(): Boolean = settingsStore.hasGoogleLinked()

    /** Ver [SettingsStore.isCalendarSyncEnabled]. */
    fun isCalendarSyncEnabled(): Boolean = settingsStore.isCalendarSyncEnabled()

    /** Ver [SettingsStore.setCalendarSyncEnabled]. */
    fun setCalendarSyncEnabled(enabled: Boolean) = settingsStore.setCalendarSyncEnabled(enabled)

    private val _listState = MutableStateFlow<TaskListUiState>(TaskListUiState.Idle)
    val listState: StateFlow<TaskListUiState> = _listState.asStateFlow()

    private val _detailState = MutableStateFlow<TaskDetailUiState>(TaskDetailUiState.Idle)
    val detailState: StateFlow<TaskDetailUiState> = _detailState.asStateFlow()

    private val _actionState = MutableStateFlow<TaskActionState>(TaskActionState.Idle)
    val actionState: StateFlow<TaskActionState> = _actionState.asStateFlow()

    // Estado específico para "cambiar quién hizo la tarea". Separado de
    // actionState para no disparar la navegación de vuelta (navigator.pop)
    // que sí disparan completar/eliminar.
    private val _reassignState = MutableStateFlow<TaskActionState>(TaskActionState.Idle)
    val reassignState: StateFlow<TaskActionState> = _reassignState.asStateFlow()

    // Asignación de la tarea del detalle actual que pertenece al usuario en
    // sesión — base para el indicador de estado de sincronización con Calendar.
    // Null mientras se resuelve o si la tarea no está asignada a este usuario.
    private val _myAssignment = MutableStateFlow<TaskAssignmentResponse?>(null)
    val myAssignment: StateFlow<TaskAssignmentResponse?> = _myAssignment.asStateFlow()

    // Logro desbloqueado más reciente, para que la UI (TaskListScreen,
    // TaskDetailScreen) muestre el toast de celebración (delight fase 2,
    // §2.8). Solo el PRIMERO si se desbloquean varios a la vez (moderación —
    // evita apilar toasts). La propia UI lo limpia a null tras mostrarlo
    // (ver [clearNewlyUnlockedAchievement]) para que no reaparezca en una
    // recomposición posterior.
    private val _newlyUnlockedAchievement = MutableStateFlow<Achievement?>(null)
    val newlyUnlockedAchievement: StateFlow<Achievement?> = _newlyUnlockedAchievement.asStateFlow()

    // Filter & sort state
    private val _filter = MutableStateFlow(TaskFilter.MINE)
    val filter: StateFlow<TaskFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(TaskSort.DEADLINE_ASC)
    val sort: StateFlow<TaskSort> = _sort.asStateFlow()

    private val _selectedTagFilter = MutableStateFlow<String?>(null)
    val selectedTagFilter: StateFlow<String?> = _selectedTagFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Current member ID (for "mine" filter). Set externally.
    private val _currentMemberId = MutableStateFlow<String?>(null)
    val currentMemberId: StateFlow<String?> = _currentMemberId.asStateFlow()

    // Offline state — true when last load served from cache
    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    // All available tags (collected from tasks)
    private val _allTags = MutableStateFlow<List<String>>(emptyList())
    val allTags: StateFlow<List<String>> = _allTags.asStateFlow()

    // ── Load tasks ──────────────────────────────────────────

    private var loadTasksJob: Job? = null

    fun loadTasks(householdId: String) {
        // Cancela la carga anterior en curso: sin esto, dos loadTasks() solapadas
        // (p.ej. pull-to-refresh seguido de un cambio de filtro) pueden resolverse
        // fuera de orden y la respuesta más antigua sobrescribe con datos stale
        // el StateFlow tras la más reciente.
        loadTasksJob?.cancel()
        loadTasksJob = screenModelScope.launch {
            _listState.value = TaskListUiState.Loading
            try {
                // getAllAssignments(tasks) reutiliza la lista ya cargada en vez
                // de volver a pedirla internamente, y las lecturas de
                // asignaciones/miembros se lanzan en paralelo en vez de
                // encadenarse en serie (panel v7, #18/#19).
                val tasks = repo.getTasks(householdId)
                val assignmentsDeferred = async { repo.getAllAssignments(householdId, tasks) }
                val membersDeferred = async { repo.getMembers(householdId) }
                val assignments = assignmentsDeferred.await()
                val members = membersDeferred.await()

                // Check connectivity to update offline banner
                _isOffline.value = !repo.isOnline()

                // ── DEBUG LOG ──
                if (DebugFlags.isEnabled) {
                    println("[TaskScreenModel] loadTasks: ${tasks.size} tasks, ${assignments.size} assignments, ${members.size} members for household=$householdId offline=${_isOffline.value}")
                    for (t in tasks) {
                        println("[TaskScreenModel]   task: id=${t.id}, title=${t.title}, freq=${t.frequency}, lastCompleted=${t.lastCompletedDate}, dueDate=${t.dueDate}")
                    }
                }

                // Collect all unique tags
                val tagSet = mutableSetOf<String>()
                for (t in tasks) {
                    tagSet.addAll(t.tags)
                }
                _allTags.value = tagSet.toList().sorted()

                _listState.value = TaskListUiState.Success(tasks, assignments, members)

                // NOTA: la reconciliación automática de puntos
                // (`reconcileMissingTaskPoints`) ya NO se dispara desde aquí
                // — ver `docs/recurrencia-backend-cloud-functions-diseno-2026-09-11.md`,
                // sección 2.5: `completeTask`/`completeAssignment` ahora son
                // transacciones reales del servidor (todo o nada), así que el
                // hueco entre "tarea completada" y "puntos otorgados" que
                // reparaba esta llamada ya no puede producirse por ese flujo.
                // La función `reconcileMissingTaskPoints` sigue existiendo
                // como scheduled en el backend (`functions/`) para el resto
                // de registros legacy (creados antes de esta migración).
            } catch (e: CancellationException) {
                // Relanzar: si no, una loadTasks() más reciente que ya canceló este
                // Job ve su propia cancelación tratada como un error normal aquí
                // (ver `loadTasksJob?.cancel()` arriba) y puede sobrescribir el
                // resultado correcto de la carga nueva con un "Error" obsoleto.
                throw e
            } catch (e: Exception) {
                // Solo se marca offline si el fallo es de TRANSPORTE (sin
                // respuesta HTTP) — un 403/404/5xx significa que la conexión
                // llegó al servidor, así que no es "sin conexión" (evita
                // confundir "sin acceso"/"servidor caído" con "sin conexión",
                // ver HouseholdScreenModel.loadHousehold).
                _isOffline.value = e.errorCategory() == ErrorCategory.NO_CONNECTION
                _listState.value = TaskListUiState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "task_error_loading")
                )
            }
        }
    }

    fun setFilter(filter: TaskFilter) {
        _filter.value = filter
    }

    fun setSort(sort: TaskSort) {
        _sort.value = sort
    }

    fun setTagFilter(tag: String?) {
        _selectedTagFilter.value = tag
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setCurrentMemberId(memberId: String?) {
        _currentMemberId.value = memberId
    }

    /**
     * Resuelve el miembro actual cuando el CALLER no pudo pasarlo ya resuelto
     * — ver [org.taskhub.ui.screens.CreateTaskScreen], que puede empujarse
     * antes de que `currentMemberId` esté disponible en la pantalla de
     * origen (p. ej. justo tras entrar a un hogar) y necesita resolverlo por
     * su cuenta en vez de crear la tarea con `createdBy=""` (panel de
     * notificaciones 2026-09-05, QA, MENOR: creador vacío + auto-notificación
     * falsa al asignarse la propia tarea).
     */
    suspend fun resolveCurrentMemberId(householdId: String): String = repo.resolveCurrentMember(householdId)

    // ── Create task ─────────────────────────────────────────

    /**
     * Crea la tarea y la asigna: si [memberIds] viene vacío, se asigna a
     * TODOS los miembros del hogar (auto-assign) en vez de dejarla sin
     * asignar. Si [dueDate] es futuro, programa además un recordatorio local
     * ([NotificationScheduler]) y sincroniza el evento de Google Calendar de
     * cada asignación creada (best-effort, ver [syncCalendarOnAssigned]).
     */
    fun createTask(
        householdId: String,
        createdBy: String,
        title: String,
        description: String,
        points: Int,
        frequency: String,
        recurrenceDays: List<Int>,
        recurrenceDay: Int? = null,
        tags: List<String>,
        subtasks: List<Subtask> = emptyList(),
        penaltyMode: String?,
        penaltyValue: Int,
        penaltyInterval: String,
        penaltyMax: Int,
        memberIds: List<String>,
        mandatory: Boolean,
        dueDate: Long,
        assignmentRotation: List<AssignmentSlot> = emptyList()
    ) {
        if (_actionState.value == TaskActionState.Loading) return
        screenModelScope.launch {
            _actionState.value = TaskActionState.Loading
            try {
                val task = repo.createTask(
                    householdId = householdId,
                    createdBy = createdBy,
                    title = title,
                    description = description,
                    points = points,
                    frequency = frequency,
                    recurrenceDays = recurrenceDays,
                    recurrenceDay = recurrenceDay,
                    tags = tags,
                    subtasks = subtasks,
                    penaltyMode = penaltyMode,
                    penaltyValue = penaltyValue,
                    penaltyInterval = penaltyInterval,
                    penaltyMax = penaltyMax,
                    dueDate = dueDate,
                    assignmentRotation = assignmentRotation
                )

                // Auto-assign: if no specific members selected, assign to ALL members
                val membersToAssign = if (memberIds.isNotEmpty()) {
                    memberIds
                } else {
                    repo.getMembers(householdId).map { it.id }
                }
                if (membersToAssign.isNotEmpty()) {
                    // Mitigación de atomicidad createTask+assignTask (ver KDoc
                    // de [rollbackUnassignedTask]): son dos escrituras REST
                    // independientes — si esta segunda falla (aunque sea a
                    // mitad de camino, con solo algunos miembros ya
                    // asignados), se deshace la tarea recién creada en vez de
                    // dejarla huérfana sin asignar.
                    val created = try {
                        repo.assignTask(
                            householdId = householdId,
                            taskId = task.id,
                            memberIds = membersToAssign,
                            mandatory = mandatory,
                            dueDate = dueDate,
                            taskTitle = title,
                            assignedByMemberId = createdBy
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        rollbackUnassignedTask(householdId, task.id)
                        throw e
                    }
                    syncCalendarOnAssigned(householdId, created)
                }

                // Schedule reminder if task has a future deadline
                if (dueDate > 0) {
                    notificationScheduler.scheduleReminder(
                        taskId = task.id,
                        householdId = householdId,
                        taskTitle = title,
                        dueDateEpochMs = dueDate
                    )
                }

                _actionState.value = TaskActionState.Success
                buzz(HapticKind.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionState.value = TaskActionState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "task_error_creating")
                )
                buzz(HapticKind.ERROR)
            }
        }
    }

    /**
     * Deshace una tarea recién creada cuya asignación inicial ([createTask])
     * falló: `repo.createTask` + `repo.assignTask` son dos escrituras REST
     * independientes (no hay endpoint `:commit` verificable desde este
     * cliente — ver `docs/atomicidad-commit-pendiente.md` para el motivo, ya
     * evaluado y descartado), así que un fallo de red justo entre ambas (o a
     * mitad de `assignTask`, con solo algunos de varios miembros ya
     * asignados) dejaba una tarea "huérfana": creada, pero sin ningún miembro
     * asignado — invisible como pendiente para nadie, solo recuperable
     * reeditándola a mano.
     *
     * Compensación, no transacción real (best-effort, igual que el resto de
     * cascades de este archivo/`FirestoreRepository`): borra primero las
     * asignaciones parciales que sí llegaron a crearse y, después, la propia
     * tarea — dejando el estado tal como estaba ANTES de intentar crearla, en
     * vez de "creada pero sin asignar". Si el propio rollback falla (p. ej.
     * sin conexión en ese instante), la tarea huérfana queda igual que antes
     * de este fix — limitación conocida, nunca peor que el comportamiento
     * previo. El error original de `assignTask` es el que se propaga al
     * caller (ver [createTask]), no uno de este rollback.
     */
    private suspend fun rollbackUnassignedTask(householdId: String, taskId: String) {
        try {
            repo.deleteAssignments(householdId, taskId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort: intentamos borrar la tarea igualmente.
        }
        try {
            repo.deleteTask(householdId, taskId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No crítico: ver KDoc de la función (limitación conocida).
        }
    }

    // ── Complete task (sets lastCompletedDate) ───────────────

    /**
     * Info for undo: guarda lo mínimo para poder invocar
     * [FirestoreRepository.undoTaskCompletion] y revertir la racha (best-effort,
     * ver KDoc de [undoCompleteTask]) — desde
     * `docs/recurrencia-backend-cloud-functions-diseno-2026-09-11.md`, ya NO
     * se guardan `previousLastCompletedDate`/`previousCompletedBy`/
     * `previousNextDueAt`/`pointsAwarded`: el servidor los deriva leyendo el
     * registro de `taskHistory` anterior a `completedAt` dentro de su propia
     * transacción, en vez de depender de este estado volátil en memoria (que
     * antes desaparecía si el usuario recargaba la pantalla entre completar y
     * deshacer). La racha SÍ sigue siendo responsabilidad del cliente — la
     * función no la toca — así que sus valores previos se mantienen aquí.
     */
    data class UndoState(
        val householdId: String,
        val taskId: String,
        val memberId: String,
        val previousStreak: Int,
        val previousBestStreak: Int,
        val previousLastStreakDate: Long,
        /** `completedAt` devuelto por [FirestoreRepository.completeTask] — identifica la compleción a deshacer. */
        val completedAt: Long
    )

    private val _undoState = MutableStateFlow<UndoState?>(null)
    val undoState: StateFlow<UndoState?> = _undoState.asStateFlow()

    /**
     * Panel v16 (2026-09-24, hallazgo C4): [completeTask] publica
     * [_undoState] de forma optimista (con `completedAt = 0L`) ANTES de que
     * la llamada de red real (Cloud Function) resuelva. Si [undoCompleteTask]
     * se invocaba en ese margen, veía `completedAt == 0L` y se saltaba
     * SILENCIOSAMENTE la llamada real a `repo.undoTaskCompletion` (aunque sí
     * revertía la racha y daba feedback háptico de éxito) — la tarea quedaba
     * completada y los puntos otorgados en el servidor de forma permanente,
     * sin ningún error visible. [completeTaskJob]/[pendingCompletion]
     * permiten que [undoCompleteTask] espere el resultado real antes de
     * decidir qué revertir, en vez de asumir que no hay nada que deshacer.
     */
    private var completeTaskJob: Job? = null
    private var pendingCompletion: UndoState? = null

    private val _undoError = MutableStateFlow<String?>(null)
    /**
     * Error de [undoCompleteTask] — antes un fallo ahí quedaba silencioso
     * (solo un comentario documentando que la tarea podía quedar
     * "completada" con los puntos ya revertidos), sin ninguna señal
     * observable para que la UI informe al usuario o le permita reintentar
     * manualmente recargando (ronda de deuda aplicable 2026-09-12, punto
     * A4). La UI debe llamar [clearUndoError] al mostrar/descartar el aviso.
     */
    val undoError: StateFlow<String?> = _undoError.asStateFlow()

    /** Descarta el error de deshacer ya mostrado (ver [undoError]). */
    fun clearUndoError() {
        _undoError.value = null
    }

    /**
     * Completa una tarea desde la lista principal (a diferencia de
     * [completeAssignment], no requiere que exista una asignación previa).
     * Guarda [UndoState] (racha previa) ANTES de completar por si el usuario
     * deshace la acción, delega en [FirestoreRepository.completeTask] la
     * transacción del servidor (puntos + historial + asignaciones + siguiente
     * ciclo — ver su KDoc), y encadena como efectos best-effort: cancelar el
     * recordatorio pendiente, sincronizar Calendar y actualizar racha/logros.
     * Ninguno de esos efectos secundarios puede convertir la acción en error
     * una vez que el servidor ya otorgó los puntos (evita el reintento manual
     * que los duplicaría).
     */
    fun completeTask(householdId: String, taskId: String) {
        if (_actionState.value == TaskActionState.Loading) return // evita doble-tap / doble suma de puntos
        completeTaskJob = screenModelScope.launch {
            _actionState.value = TaskActionState.Loading
            try {
                // Resolución robusta del miembro: si la UI no lo ha establecido
                // (p.ej. navegación directa al detalle desde Home o Calendario),
                // se deduce del usuario autenticado en Firestore. Así completar una
                // tarea en el espacio Personal nunca falla por falta de miembro.
                val memberId = _currentMemberId.value
                    ?: repo.resolveCurrentMember(householdId)

                // Fetch the task to get its points + save previous state for undo
                // (getTask, no getTasks().find() — evita releer la colección
                // completa de tareas del hogar para localizar una sola, panel
                // 2026-09-04, Experto 11 CRÍTICO).
                val task = repo.getTask(householdId, taskId)
                val memberBefore = repo.getMembers(householdId).find { it.id == memberId }

                // Save undo info BEFORE completing (racha previa incluida —
                // ver KDoc de [UndoState]: el resto lo deriva el servidor).
                val undoState = UndoState(
                    householdId = householdId,
                    taskId = taskId,
                    memberId = memberId,
                    previousStreak = memberBefore?.currentStreak ?: 0,
                    previousBestStreak = memberBefore?.bestStreak ?: 0,
                    previousLastStreakDate = memberBefore?.lastStreakDate ?: 0L,
                    completedAt = 0L
                )
                pendingCompletion = undoState
                _undoState.value = undoState

                val result = repo.completeTask(
                    householdId = householdId,
                    taskId = taskId,
                    memberId = memberId,
                    task = task
                )
                val completedAt = result.completedAt
                pendingCompletion = pendingCompletion?.copy(completedAt = completedAt)
                _undoState.value = _undoState.value?.copy(completedAt = completedAt)

                // Nota: sincronizar la asignación pendiente de este miembro como
                // completada, y regenerar la de la siguiente ocurrencia respetando
                // assignmentRotation, ya lo hace `repo.completeTask` internamente
                // (ver su KDoc) — antes era una llamada aparte aquí
                // (`syncAssignmentOnTaskCompleted`) que solo sincronizaba sin
                // regenerar, dejando huérfana la asignación a partir del segundo
                // ciclo de una tarea recurrente asignada.

                // Cancel any scheduled reminder for this task.
                // La tarea ya está completada y los puntos ya se otorgaron en el
                // servidor en este punto: un fallo aquí (WorkManager/AlarmManager)
                // es un efecto secundario no crítico, nunca debe marcar la acción
                // como error (eso invitaría a reintentar completeTask() y duplicar
                // los puntos ya otorgados).
                try {
                    notificationScheduler.cancelReminder(taskId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }

                // Tarea hecha → borrar el evento de Calendar vinculado, si lo
                // hay, y sincronizar ya la asignación de la siguiente
                // ocurrencia (recurrentes) que `repo.completeTask` acaba de
                // regenerar — antes había que esperar a reabrir
                // HouseholdScreen/PersonalSpaceScreen (`CalendarSyncManager.
                // reconcile()`) para que apareciera en Calendar (panel v4,
                // Experto 2 hallazgo #3 PROPUESTA aceptada).
                try {
                    val currentAssignments = repo.getAssignments(householdId, taskId)
                    val myAssignment = currentAssignments.find { it.memberId == memberId }
                    if (myAssignment != null) {
                        calendarSync.onTaskCompleted(householdId, myAssignment)
                    }
                    val regenerated = currentAssignments.filter { it.status == "assigned" }
                    if (regenerated.isNotEmpty()) {
                        syncCalendarOnAssigned(householdId, regenerated)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }

                // Update streak + achievements reusing memberBefore (evita 2
                // lecturas extra de getMembers): la racha aún no se ha tocado
                // en el servidor, así que memberBefore es el estado correcto de
                // partida; el total de puntos post-premio se calcula en local.
                try {
                    if (memberBefore != null) {
                        val streakUpdated = updateMemberStreak(householdId, memberBefore)
                        val memberForAchievements = streakUpdated.copy(
                            totalPoints = memberBefore.totalPoints + result.pointsAwarded
                        )
                        checkAndAwardAchievements(householdId, memberForAchievements)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }

                _actionState.value = TaskActionState.Success
                buzz(HapticKind.SUCCESS)

                // Registrar el evento (métrica clave de engagement/racha) y mostrar
                // el interstitial son efectos secundarios no críticos — un fallo
                // aquí (analytics no inicializado, error interno de AdMob) no debe
                // sobrescribir el TaskActionState.Success que ya se ha publicado.
                try {
                    logAnalyticsEvent("task_completed")
                    // Panel v16, hallazgo C5: nunca mostrar anuncios a perfiles
                    // infantiles (docs/guia-publicacion.md exige explícitamente
                    // no hacerlo, y la app ya marca
                    // TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE reconociendo que hay
                    // menores usando la app).
                    if (memberBefore?.role != "child") {
                        adController.maybeShowInterstitial()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingCompletion = null
                _undoState.value = null
                if (e is FirestoreRepository.TaskCompletionConflictException) {
                    // Mensaje vía AppStrings (no e.message, que viene fijo en
                    // español desde el repo) y recarga de la lista: el error
                    // significa que OTRO dispositivo ya completó esta tarea,
                    // así que el estado en memoria (botón "completar" activo)
                    // queda obsoleto — sin recargar, el usuario probablemente
                    // reintenta y vuelve a chocar con el mismo conflicto
                    // (panel v4, UX hallazgos ALTA #1 y #2).
                    _actionState.value = TaskActionState.Error(
                        AppStrings.get("task_completion_conflict_error", settingsStore.getLanguage())
                    )
                    loadTasks(householdId)
                } else {
                    // Panel v16, hallazgo I18: un timeout (AMBIGUOUS) puede
                    // significar que el servidor SÍ completó la tarea —
                    // recargar para no dejar el botón "Completar" activo
                    // sobre una tarea ya completada (lo que empujaba al
                    // reintento manual que duplica puntos, ver hallazgo C3),
                    // y usar un mensaje específico de tareas en vez del
                    // texto de "transferencia"/"saldo" pensado para donar
                    // puntos.
                    if (e.errorCategory() == ErrorCategory.AMBIGUOUS) {
                        loadTasks(householdId)
                    }
                    _actionState.value = TaskActionState.Error(
                        e.toUserMessage(settingsStore.getLanguage(), "task_error_completing", ambiguousKey = "task_error_uncertain")
                    )
                }
                buzz(HapticKind.ERROR)
            }
        }
    }

    /**
     * Undo a task completion: delega en la Cloud Function `undoTaskCompletion`
     * (ver `docs/recurrencia-backend-cloud-functions-diseno-2026-09-11.md`,
     * sección 2.4) para revertir puntos/historial/`lastCompletedDate`/
     * `completedBy`/`nextDueAt`/asignaciones en UNA transacción del servidor
     * — reemplaza las 3 llamadas secuenciales de antes
     * (`deleteTaskHistoryRecord`/`revertTaskCompletion`/
     * `undoTaskCompletionAssignments`, todas retiradas). Al ser una
     * transacción real, ya no hay ventana en la que la tarea quede
     * "completada" con los puntos ya revertidos (el riesgo que documentaba
     * esta función antes de la migración).
     *
     * La racha SÍ sigue revirtiéndose desde el cliente (la función no la
     * toca) — best-effort, un fallo aquí no deshace lo que la función ya
     * confirmó.
     */
    fun undoCompleteTask() {
        val state = _undoState.value ?: return
        _undoState.value = null
        val jobToAwait = completeTaskJob
        screenModelScope.launch {
            try {
                var target = state
                if (target.completedAt == 0L) {
                    // completeTask() todavía no ha recibido respuesta del
                    // servidor (ver KDoc de [completeTaskJob]/[pendingCompletion]
                    // arriba) — esperar su resultado real antes de decidir qué
                    // deshacer, en vez de asumir que no hay nada que revertir.
                    jobToAwait?.join()
                    target = pendingCompletion?.takeIf {
                        it.householdId == state.householdId && it.taskId == state.taskId
                    } ?: target
                }
                if (target.completedAt != 0L) {
                    // Panel v16, hallazgo I10: el servidor puede hacer un
                    // no-op idempotente (`reverted = false`, p.ej. undo ya
                    // aplicado desde otro dispositivo) — solo revertir la
                    // racha/dar feedback háptico si de verdad se revirtió
                    // algo, para no desincronizar la racha mostrada con el
                    // resto del estado (puntos/historial) que el servidor
                    // conservó intacto.
                    val reverted = repo.undoTaskCompletion(target.householdId, target.taskId, target.completedAt)
                    if (reverted) {
                        repo.updateMemberStreak(
                            householdId = target.householdId,
                            memberId = target.memberId,
                            currentStreak = target.previousStreak,
                            bestStreak = target.previousBestStreak,
                            lastStreakDate = target.previousLastStreakDate
                        )
                        buzz(HapticKind.LIGHT)
                    }
                }
                // Si completeTask() terminó en error (nunca llegó a
                // completarse en el servidor), no hay nada que deshacer.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // La tarea puede quedar como completada con la racha ya
                // revertida (ver KDoc arriba) — no crítico para la
                // integridad de datos, pero SÍ debe ser visible: antes no
                // había ninguna señal observable de este fallo parcial.
                _undoError.value = e.toUserMessage(settingsStore.getLanguage(), "task_error_undo")
            }
        }
    }

    fun clearUndoState() {
        _undoState.value = null
    }

    // ── Reasignar quién completó (corrección de errores) ─────

    /**
     * Cambia quién ha hecho una tarea ya completada. Transfiere los puntos del
     * miembro anterior al nuevo (coherente con "quien marca hecho recibe los
     * puntos") y refresca el detalle al terminar.
     */
    fun reassignTaskCompletion(
        householdId: String,
        taskId: String,
        taskPoints: Int,
        newMemberId: String
    ) {
        if (_reassignState.value == TaskActionState.Loading) return
        screenModelScope.launch {
            _reassignState.value = TaskActionState.Loading
            try {
                repo.reassignTaskCompletion(
                    householdId = householdId,
                    taskId = taskId,
                    taskPoints = taskPoints,
                    newMemberId = newMemberId
                )
                _reassignState.value = TaskActionState.Success
                loadTaskDetail(householdId, taskId)

                // Los puntos/historial ya se transfirieron en el servidor,
                // pero a diferencia de completeTask/completeAssignment nadie
                // revisaba logros para el nuevo autor de la compleción — un
                // miembro podía cruzar un umbral (p.ej. "10 tareas") por una
                // reasignación y no desbloquear el logro hasta su siguiente
                // compleción propia. Best-effort, igual que en los otros dos
                // flujos: un fallo aquí no debe pisar el Success ya publicado.
                try {
                    val newMember = repo.getMembers(householdId).find { it.id == newMemberId }
                    if (newMember != null) {
                        checkAndAwardAchievements(householdId, newMember)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _reassignState.value = TaskActionState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "task_error_reassigning")
                )
            }
        }
    }

    // ── Complete assignment (existing, keeps working) ────────

    /**
     * Completa una asignación concreta (flujo alternativo a [completeTask]
     * cuando la UI ya tiene la [TaskAssignmentResponse] en mano, p.ej. desde
     * el calendario o la vista de asignaciones). Delega en
     * [FirestoreRepository.completeAssignment] el otorgamiento de puntos y la
     * concurrencia optimista; encadena Calendar y racha/logros como efectos
     * best-effort igual que [completeTask], y refresca el detalle al terminar.
     */
    fun completeAssignment(
        householdId: String,
        taskId: String,
        task: TaskResponse,
        assignmentId: String,
        assignment: TaskAssignmentResponse
    ) {
        if (_actionState.value == TaskActionState.Loading) return
        screenModelScope.launch {
            _actionState.value = TaskActionState.Loading
            try {
                val memberBefore = repo.getMembers(householdId).find { it.id == assignment.memberId }

                val result = repo.completeAssignment(
                    householdId = householdId,
                    taskId = taskId,
                    task = task,
                    assignmentId = assignmentId,
                    assignment = assignment
                )
                // Borrar el evento de Calendar vinculado y sincronizar ya la
                // asignación de la siguiente ocurrencia regenerada — mismo
                // motivo que en completeTask (panel v4, Experto 2 hallazgo
                // #3 PROPUESTA aceptada).
                try {
                    calendarSync.onTaskCompleted(householdId, assignment)
                    val regenerated = repo.getAssignments(householdId, taskId).filter { it.status == "assigned" }
                    if (regenerated.isNotEmpty()) {
                        syncCalendarOnAssigned(householdId, regenerated)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }

                // Racha + logros: mismo patrón que completeTask (ver su comentario) —
                // antes esta función solo otorgaba puntos sin actualizar racha ni
                // desbloquear logros, así que un miembro que solo completa tareas
                // asignadas (recurrentes con rotación, p.ej.) nunca acumulaba racha.
                try {
                    if (memberBefore != null) {
                        val pointsAwarded = result.pointsAwarded ?: 0
                        val streakUpdated = updateMemberStreak(householdId, memberBefore)
                        val memberForAchievements = streakUpdated.copy(
                            totalPoints = memberBefore.totalPoints + pointsAwarded
                        )
                        checkAndAwardAchievements(householdId, memberForAchievements)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }

                _actionState.value = TaskActionState.Success
                buzz(HapticKind.SUCCESS)

                // Refresh detail
                loadTaskDetail(householdId, taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is FirestoreRepository.AssignmentCompletionConflictException) {
                    // Mismo motivo que en completeTask: mensaje vía AppStrings
                    // (no el string fijo en español del repo) y recarga del
                    // detalle para no dejar la UI con el botón "completar"
                    // activo sobre una asignación que otro dispositivo ya
                    // completó (panel v4, UX hallazgos ALTA #1 y #2).
                    _actionState.value = TaskActionState.Error(
                        AppStrings.get("task_completion_conflict_error", settingsStore.getLanguage())
                    )
                    loadTaskDetail(householdId, taskId)
                } else {
                    // Panel v16, hallazgo I18: ver el mismo tratamiento en completeTask().
                    if (e.errorCategory() == ErrorCategory.AMBIGUOUS) {
                        loadTaskDetail(householdId, taskId)
                    }
                    _actionState.value = TaskActionState.Error(
                        e.toUserMessage(settingsStore.getLanguage(), "task_error_completing", ambiguousKey = "task_error_uncertain")
                    )
                }
                buzz(HapticKind.ERROR)
            }
        }
    }

    // ── Load task detail ────────────────────────────────────

    /**
     * Carga la tarea, sus asignaciones y los miembros del hogar para
     * `TaskDetailScreen`. También resuelve [currentMemberId]/[myAssignment]
     * (la asignación del usuario en sesión, si la hay) — base para el
     * indicador de sincronización con Google Calendar.
     */
    fun loadTaskDetail(householdId: String, taskId: String) {
        screenModelScope.launch {
            _detailState.value = TaskDetailUiState.Loading
            _myAssignment.value = null
            try {
                // getTask, no getTasks().find() — misma razón que completeTask
                // (panel 2026-09-04, Experto 11 CRÍTICO): loadTaskDetail se
                // dispara en cada apertura de TaskDetailScreen y tras casi
                // cualquier mutación.
                val task = repo.getTask(householdId, taskId)

                val assignments = repo.getAssignments(householdId, taskId)
                val members = repo.getMembers(householdId)

                _detailState.value = TaskDetailUiState.Success(task, assignments, members)

                val myMemberId = try {
                    repo.resolveCurrentMember(householdId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                _currentMemberId.value = myMemberId
                _myAssignment.value = assignments.find { it.memberId == myMemberId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _detailState.value = TaskDetailUiState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "task_error_loading_single")
                )
            }
        }
    }

    // ── Assign task to members ──────────────────────────────

    /** Crea nuevas asignaciones para [memberIds] (no sustituye las existentes; ver [updateTask]/[replaceAssignments] para eso). */
    fun assignMembers(
        householdId: String,
        taskId: String,
        memberIds: List<String>,
        mandatory: Boolean,
        dueDate: Long
    ) {
        if (_actionState.value == TaskActionState.Loading) return
        screenModelScope.launch {
            _actionState.value = TaskActionState.Loading
            try {
                repo.assignTask(
                    householdId = householdId,
                    taskId = taskId,
                    memberIds = memberIds,
                    mandatory = mandatory,
                    dueDate = dueDate,
                    assignedByMemberId = _currentMemberId.value
                )
                _actionState.value = TaskActionState.Success

                // Refresh detail
                loadTaskDetail(householdId, taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionState.value = TaskActionState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "task_error_assigning")
                )
            }
        }
    }

    // ── Update task ─────────────────────────────────────────

    /**
     * Edita una tarea existente y sustituye sus asignaciones ([replaceAssignments]
     * — crea las nuevas antes de borrar las antiguas para no dejar la tarea
     * sin ninguna si la creación falla a medias). Igual que [createTask], si
     * no se selecciona a nadie en [memberIds] se asigna a todos los miembros
     * del hogar. Antes de tocar las asignaciones, borra el evento de Calendar
     * de las actuales ([syncCalendarOnUnassigned]) y, tras crear las nuevas,
     * las sincroniza de nuevo ([syncCalendarOnAssigned]).
     */
    fun updateTask(
        householdId: String,
        taskId: String,
        title: String,
        description: String,
        points: Int,
        frequency: String,
        recurrenceDays: List<Int>,
        recurrenceDay: Int? = null,
        tags: List<String>,
        subtasks: List<Subtask> = emptyList(),
        penaltyMode: String?,
        penaltyValue: Int,
        penaltyInterval: String,
        penaltyMax: Int,
        assignmentRotation: List<AssignmentSlot> = emptyList(),
        memberIds: List<String> = emptyList(),
        mandatory: Boolean = false,
        dueDate: Long = 0,
        lastCompletedDate: Long? = null
    ) {
        if (_actionState.value == TaskActionState.Loading) return
        screenModelScope.launch {
            _actionState.value = TaskActionState.Loading
            try {
                val nextDueAt = repo.updateTask(
                    householdId = householdId,
                    taskId = taskId,
                    title = title,
                    description = description,
                    points = points,
                    frequency = frequency,
                    recurrenceDays = recurrenceDays,
                    recurrenceDay = recurrenceDay,
                    tags = tags,
                    subtasks = subtasks,
                    penaltyMode = penaltyMode,
                    penaltyValue = penaltyValue,
                    penaltyInterval = penaltyInterval,
                    penaltyMax = penaltyMax,
                    assignmentRotation = assignmentRotation,
                    dueDate = dueDate,
                    lastCompletedDate = lastCompletedDate
                )

                // Sincronizar asignaciones: crear las nuevas antes de borrar las
                // antiguas (ver FirestoreRepository.replaceAssignments — deja la
                // tarea con asignaciones previas en vez de sin ninguna si la
                // creación falla a mitad de camino). Si no se seleccionó a nadie,
                // se asigna a todos (misma semántica que al crear).
                syncCalendarOnUnassigned(householdId, taskId)
                val membersToAssign = if (memberIds.isNotEmpty()) {
                    memberIds
                } else {
                    repo.getMembers(householdId).map { it.id }
                }
                // Sin fecha límite manual (`dueDate == 0`) en una tarea
                // recurrente: usar el `nextDueAt` que `repo.updateTask` acaba
                // de recalcular y persistir en la tarea, en vez de 0. Antes se
                // pasaba `dueDate` (0) tal cual a `replaceAssignments`, así
                // que la asignación de ese ciclo se quedaba sin fecha límite
                // propia y `completeAssignment` nunca podía penalizar el
                // retraso de ese ciclo (`assignment.dueDate == 0L -> 0L` en su
                // cálculo de `effectiveDueDate`) — panel v4, Experto 8
                // hallazgo #4 MEDIO.
                val assignmentDueDate = if (dueDate > 0) dueDate else (nextDueAt ?: dueDate)
                val created = repo.replaceAssignments(
                    householdId = householdId,
                    taskId = taskId,
                    memberIds = membersToAssign,
                    mandatory = mandatory,
                    dueDate = assignmentDueDate,
                    taskTitle = title
                )
                if (created.isNotEmpty()) {
                    syncCalendarOnAssigned(householdId, created)
                }

                _actionState.value = TaskActionState.Success
                // Refresh detail
                loadTaskDetail(householdId, taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TaskConflictException) {
                // Por tipo, no por e.message (fijo en español desde el repo)
                // — mismo patrón que InsufficientBalanceException en
                // MemberScreenModel.redeemReward. Ver KDoc de
                // [org.taskhub.network.TaskRepository.updateTask]: la tarea
                // fue editada por otro miembro entre nuestra lectura y
                // escritura, repetidamente, hasta agotar los reintentos de
                // concurrencia optimista.
                _actionState.value = TaskActionState.Error(s("task_error_conflict"))
                // Recargar el detalle para que la UI muestre la versión real
                // (la de quien ganó la carrera) en vez de dejar en pantalla
                // los campos que el usuario intentó guardar sin éxito.
                loadTaskDetail(householdId, taskId)
            } catch (e: Exception) {
                _actionState.value = TaskActionState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "task_error_updating")
                )
            }
        }
    }

    // ── Delete task ──────────────────────────────────────────

    /** Borra la tarea; antes limpia el evento de Calendar de sus asignaciones actuales (best-effort). */
    fun deleteTask(householdId: String, taskId: String) {
        if (_actionState.value == TaskActionState.Loading) return
        screenModelScope.launch {
            _actionState.value = TaskActionState.Loading
            try {
                syncCalendarOnUnassigned(householdId, taskId)
                repo.deleteTask(householdId, taskId)
                _actionState.value = TaskActionState.Success
                buzz(HapticKind.WARNING)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionState.value = TaskActionState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "task_error_deleting")
                )
                buzz(HapticKind.ERROR)
            }
        }
    }

    // ── CSV Export ─────────────────────────────────────────

    /** Ver [TaskCsvExporter.generateCsv]. */
    fun generateCsv(tasks: List<TaskResponse>): String = TaskCsvExporter.generateCsv(tasks)

    // ── Helpers ─────────────────────────────────────────────

    /**
     * Actualiza la racha del miembro con [StreakRules.nextStreak] (R19: lógica
     * pura extraída a `network/StreakRules.kt` para poder testearla sin red).
     *
     * Recibe [member] ya cargado (en vez de volver a pedirlo a Firestore) y
     * devuelve la versión actualizada, para que el llamador pueda encadenar
     * [checkAndAwardAchievements] sin otra llamada de red redundante.
     */
    private suspend fun updateMemberStreak(householdId: String, member: MemberResponse): MemberResponse {
        val tz = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(tz).date

        val update = StreakRules.nextStreak(
            lastStreakDateEpoch = member.lastStreakDate,
            currentStreak = member.currentStreak,
            bestStreak = member.bestStreak,
            today = today,
            tz = tz
        ) ?: return member // ya contada hoy

        repo.updateMemberStreak(
            householdId = householdId,
            memberId = member.id,
            currentStreak = update.currentStreak,
            bestStreak = update.bestStreak,
            lastStreakDate = update.lastStreakDate
        )
        return member.copy(
            currentStreak = update.currentStreak,
            bestStreak = update.bestStreak,
            lastStreakDate = update.lastStreakDate
        )
    }

    /**
     * Check for newly unlocked achievements after completing a task.
     * Recibe [member] ya actualizado (puntos/racha post-premio) para no volver
     * a pedirlo a Firestore.
     */
    private suspend fun checkAndAwardAchievements(householdId: String, member: MemberResponse) {
        val tz = TimeZone.currentSystemDefault()
        val now = Clock.System.now()
        val currentHour = now.toLocalDateTime(tz).hour

        // Conteo desde `taskHistory` (no desde `assignments`) — ver KDoc de
        // [AchievementChecker.countCompletedFromHistory] para el motivo
        // (panel de revisión 2026-09-04, Experto 8, IMPORTANTE), extraído
        // como función pura testeable sin mocks (panel v7, Exp. 13).
        val history = repo.getTaskHistory(householdId)
        val completedCount = AchievementChecker.countCompletedFromHistory(history, member.id)

        val alreadyUnlocked = repo.getMemberAchievements(householdId, member.id)

        val newlyUnlocked = AchievementChecker.checkNewAchievements(
            totalTasksCompleted = completedCount,
            totalPoints = member.totalPoints,
            currentStreak = member.currentStreak,
            lastCompletedHour = currentHour,
            alreadyUnlocked = alreadyUnlocked
        )

        for (achievementId in newlyUnlocked) {
            try {
                repo.addMemberAchievement(householdId, member.id, achievementId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-critical failure
            }
        }

        // Publicar solo el primero para el toast de celebración (moderación —
        // apilar varios toasts violaría lo pedido en el informe de delight).
        newlyUnlocked.firstOrNull()?.let { firstId ->
            AchievementChecker.ALL_ACHIEVEMENTS.find { it.id == firstId }?.let { achievement ->
                _newlyUnlockedAchievement.value = achievement
            }
        }
    }

    /** Limpia el logro mostrado en el toast — llamado por la UI tras el auto-descarte. */
    fun clearNewlyUnlockedAchievement() {
        _newlyUnlockedAchievement.value = null
    }

    /**
     * Returns the member ID responsible for this task today based on assignmentRotation.
     * Falls back to null if no rotation is defined — then use fixed assignments.
     */
    fun getTodayAssignee(task: TaskResponse): String? {
        if (task.assignmentRotation.isEmpty()) return null

        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()
        val today = now.toLocalDateTime(tz).date
        val todayDow = today.dayOfWeek.ordinal + 1 // 1=Monday..7=Sunday

        val slot = task.assignmentRotation.find { it.dayOfWeek == todayDow }
        return slot?.memberId
    }

    fun resetActionState() {
        _actionState.value = TaskActionState.Idle
    }

    // ── Toggle subtask ──────────────────────────────────────

    // Tareas con un toggle de subtarea en curso: evita el lost-update de marcar
    // dos subtareas de la misma tarea con taps rápidos (la segunda llamada leía
    // la lista antes de que la primera escritura se confirmara y la sobrescribía).
    private val subtaskTogglesInFlight = mutableSetOf<String>()

    /**
     * Marca/desmarca una subtarea. [FirestoreRepository.updateSubtasks] relee
     * el array fresco dentro de su propio reintento (concurrencia optimista,
     * panel v17) y aplica el toggle sobre ESE array, no sobre uno capturado
     * aquí — así dos dispositivos alternando subtareas distintas de la misma
     * tarea no se pisan entre sí.
     */
    fun toggleSubtask(householdId: String, taskId: String, subtaskId: String) {
        if (taskId in subtaskTogglesInFlight) return
        subtaskTogglesInFlight += taskId
        screenModelScope.launch {
            try {
                repo.updateSubtasks(householdId, taskId) { current ->
                    current.map { st -> if (st.id == subtaskId) st.copy(completed = !st.completed) else st }
                }
                buzz(HapticKind.SELECTION)
                // Refresh detail
                loadTaskDetail(householdId, taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-critical; detail will be stale until next load
            } finally {
                subtaskTogglesInFlight -= taskId
            }
        }
    }

    /** Vuelve todos los StateFlows a su valor inicial — usado al reutilizar el ScreenModel al cambiar de hogar. */
    fun reset() {
        // Cancela una loadTasks() en vuelo: si no, puede resolver después de este
        // reset() y sobrescribir el Idle recién puesto con datos del hogar anterior
        // (reutilización del ScreenModel al cambiar de hogar).
        loadTasksJob?.cancel()
        _listState.value = TaskListUiState.Idle
        _detailState.value = TaskDetailUiState.Idle
        _actionState.value = TaskActionState.Idle
        _reassignState.value = TaskActionState.Idle
        _undoState.value = null
        _myAssignment.value = null
        _filter.value = TaskFilter.MINE
        _sort.value = TaskSort.DEADLINE_ASC
        _selectedTagFilter.value = null
        _searchQuery.value = ""
        _currentMemberId.value = null
        _isOffline.value = false
        _allTags.value = emptyList()
        _calendarActionState.value = CalendarActionState.Idle
    }

    // ── Google Calendar ──────────────────────────────────────

    /** Sealed state for Google Calendar send operations. */
    sealed class CalendarActionState {
        data object Idle : CalendarActionState()
        data object Sending : CalendarActionState()
        data object Success : CalendarActionState()
        data class Error(val message: String) : CalendarActionState()
    }

    private val _calendarActionState = MutableStateFlow<CalendarActionState>(CalendarActionState.Idle)
    val calendarActionState: StateFlow<CalendarActionState> = _calendarActionState.asStateFlow()

    /**
     * Sincroniza manualmente la asignación actual del usuario ("Sincronizar
     * ahora" en el detalle de tarea) cuando tiene fecha pero aún no tiene
     * evento en Calendar. Al terminar, recarga el detalle para que el estado
     * de sincronización mostrado se actualice con el `googleEventId` nuevo (o
     * con el flag "vinculado" si el token resultó revocado).
     */
    fun syncTaskToCalendarNow(householdId: String, task: TaskResponse) {
        val assignment = _myAssignment.value
        if (assignment == null) {
            // Antes retornaba en silencio, dejando el botón "Sincronizar ahora"
            // sin ningún feedback si el usuario lo pulsa antes de que se resuelva
            // su asignación (o si la tarea no está asignada a él).
            _calendarActionState.value = CalendarActionState.Error(s("calendar_sync_no_assignment"))
            return
        }
        screenModelScope.launch {
            _calendarActionState.value = CalendarActionState.Sending
            try {
                val household = repo.getHousehold(householdId)
                val synced = calendarSync.syncNow(
                    householdId = householdId,
                    householdName = household.name,
                    isPersonal = household.isPersonal,
                    assignment = assignment,
                    task = task
                )
                _calendarActionState.value = if (synced) {
                    CalendarActionState.Success
                } else {
                    CalendarActionState.Error(s("calendar_sync_error"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _calendarActionState.value = CalendarActionState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "calendar_sync_error")
                )
            }
            loadTaskDetail(householdId, task.id)
        }
    }

    fun resetCalendarActionState() {
        _calendarActionState.value = CalendarActionState.Idle
    }

    /**
     * Reporta un fallo del flujo de "Vincular cuenta" de Google Calendar (fuera
     * del propio ScreenModel, ya que el consentimiento OAuth lo dispara
     * [org.taskhub.ui.models.GoogleAuthManager] desde la pantalla). Sin esto,
     * un `linkCalendar()` fallido solo apagaba el spinner y volvía a "No
     * vinculado" sin explicación, a diferencia de "Sincronizar ahora" que sí
     * reutiliza esta misma tarjeta de error.
     */
    fun setCalendarLinkError(message: String) {
        _calendarActionState.value = CalendarActionState.Error(message)
    }

    // ── Sync automático con Google Calendar (best-effort) ────

    /** Tras asignar/reasignar: crea eventos para las asignaciones mías con fecha. */
    private suspend fun syncCalendarOnAssigned(householdId: String, assignments: List<TaskAssignmentResponse>) {
        try {
            val household = repo.getHousehold(householdId)
            calendarSync.onTaskAssigned(householdId, household.name, household.isPersonal, assignments)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort: se reintenta en el próximo reconcile.
        }
    }

    /** Antes de desasignar/borrar: borra los eventos de Calendar vinculados a las asignaciones actuales. */
    private suspend fun syncCalendarOnUnassigned(householdId: String, taskId: String) {
        try {
            val assignments = repo.getAssignments(householdId, taskId)
            for (assignment in assignments) {
                calendarSync.onTaskUnassigned(householdId, assignment)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort: el evento huérfano queda hasta el próximo reconcile.
        }
    }
}