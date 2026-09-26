// CRUD de tareas, historial de compleciones, asignaciones y comentarios
// (subcolecciones bajo `households/{id}`). Ver el KDoc de la clase para lo
// que NO vive aquí (completar/reasignar, que orquestan puntos de miembro a
// la vez y se quedan en [FirestoreRepository]).
package org.taskhub.network

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import org.taskhub.network.models.AssignmentSlot
import org.taskhub.network.models.CommentResponse
import org.taskhub.network.models.Subtask
import org.taskhub.network.models.TaskAssignmentResponse
import org.taskhub.network.models.TaskHistoryResponse
import org.taskhub.network.models.TaskResponse
import org.taskhub.platform.AppLog
import org.taskhub.storage.SettingsStore
import org.taskhub.storage.TaskCache
import org.taskhub.ui.i18n.AppStrings

/**
 * Lanzada por [TaskRepository.updateTask] cuando se agotan los reintentos de
 * concurrencia optimista (ver su KDoc): otro miembro modificó la misma tarea
 * entre la lectura y la escritura, repetidamente, en las
 * [FirestoreClient.OPTIMISTIC_WRITE_MAX_RETRIES] ventanas de oportunidad
 * disponibles. Vive a nivel de paquete (no anidada) para que los `ScreenModel`
 * puedan capturarla POR TIPO, igual que [InsufficientBalanceException] en
 * [MemberRepository] — el mensaje real, traducido, se resuelve en la capa de
 * UI (`task_error_conflict`), no aquí.
 */
class TaskConflictException(taskId: String) : Exception("La tarea $taskId fue modificada por otro miembro")

/**
 * Tareas de un hogar (subcolecciones `households/{id}/tasks`, `taskHistory`,
 * `tasks/{taskId}/assignments` y `tasks/{taskId}/comments`). Extraído de
 * [FirestoreRepository] (ver docs/refactor-arquitectura-2026-08-31.md, punto
 * 6, fase 2.3). Lógica movida tal cual, sin cambios de comportamiento.
 *
 * NO incluye `completeTask`/`completeAssignment`/`reassignTaskCompletion`/
 * `undoTaskCompletion`: desde
 * `docs/recurrencia-backend-cloud-functions-diseno-2026-09-11.md`, esas 4
 * operaciones delegan en Cloud Functions (`functions/`, vía
 * `CloudFunctionsClient`) que hacen la transacción completa (tarea +
 * historial + puntos + asignaciones) en el servidor — [FirestoreRepository]
 * solo llama a la función correspondiente, ya no orquesta HTTP secuencial
 * ni necesita helpers propios para ello.
 */
class TaskRepository(
    private val baseUrl: String,
    private val firestoreClient: FirestoreClient,
    private val taskCache: TaskCache,
    private val notificationRepository: NotificationRepository,
    private val settingsStore: SettingsStore
) {
    private val client = firestoreClient.client

    private suspend fun HttpRequestBuilder.withAuth() = with(firestoreClient) { withAuth() }
    private fun HttpRequestBuilder.updateMaskFieldPaths(vararg fields: String) =
        with(firestoreClient) { updateMaskFieldPaths(*fields) }
    private fun HttpRequestBuilder.updateMaskFieldPaths(fields: Collection<String>) =
        with(firestoreClient) { updateMaskFieldPaths(fields) }
    private fun extractDocId(resourceName: String, operation: String): String =
        firestoreClient.extractDocId(resourceName, operation)

    /**
     * Panel v17 (hallazgo CRÍTICO de rendimiento): [getAllAssignments] hacía
     * una petición HTTP por tarea, todas lanzadas a la vez sin límite — un
     * hogar con 30 tareas disparaba 30 peticiones concurrentes cada vez que
     * se abría TaskListScreen/StatsScreen/se reconciliaba Calendar. Mismo
     * patrón que [org.taskhub.ui.models.CalendarSyncManager.createEventSemaphore]
     * para acotar la concurrencia sin serializar del todo.
     */
    private val getAllAssignmentsSemaphore = Semaphore(4)

    // ────────────────────────────────────────────────────────
    //  Serialización compartida entre createTask/updateTask
    // ────────────────────────────────────────────────────────
    // Extraídos porque createTask y updateTask reimplementaban el mismo
    // mapeo (tags/recurrenceDays/subtasks/assignmentRotation/penalty) byte a
    // byte. Cada función sigue decidiendo POR SU CUENTA si incluye el campo
    // (createTask lo omite cuando está vacío/null; updateTask siempre lo
    // incluye, con NULL_VALUE explícito para borrar el valor previo vía
    // updateMask) — solo se comparte la construcción del valor en sí, sin
    // cambiar ese comportamiento observable.

    private fun tagsField(tags: List<String>): FirestoreValue =
        FirestoreValue(arrayValue = FirestoreArrayValue(values = tags.map { FirestoreValue(stringValue = it) }))

    private fun recurrenceDaysField(recurrenceDays: List<Int>): FirestoreValue =
        FirestoreValue(arrayValue = FirestoreArrayValue(values = recurrenceDays.map { FirestoreValue(integerValue = it.toString()) }))

    private fun subtasksField(subtasks: List<Subtask>): FirestoreValue = FirestoreValue(
        arrayValue = FirestoreArrayValue(
            values = subtasks.map { st ->
                FirestoreValue(
                    mapValue = FirestoreMapValue(
                        fields = mapOf(
                            "id" to FirestoreValue(stringValue = st.id),
                            "text" to FirestoreValue(stringValue = st.text),
                            "completed" to FirestoreValue(booleanValue = st.completed)
                        )
                    )
                )
            }
        )
    )

    private fun assignmentRotationField(assignmentRotation: List<AssignmentSlot>): FirestoreValue = FirestoreValue(
        arrayValue = FirestoreArrayValue(
            values = assignmentRotation.map { slot ->
                FirestoreValue(
                    mapValue = FirestoreMapValue(
                        fields = mapOf(
                            "dayOfWeek" to FirestoreValue(integerValue = slot.dayOfWeek.toString()),
                            "memberId" to FirestoreValue(stringValue = slot.memberId)
                        )
                    )
                )
            }
        )
    )

    /** Campos de penalización cuando [mode] no es null; mapa vacío si lo es (el caller decide si añadirlos u omitirlos). */
    private fun penaltyFields(mode: String?, value: Int, interval: String, max: Int): Map<String, FirestoreValue> =
        if (mode == null) emptyMap() else mapOf(
            "penaltyMode" to FirestoreValue(stringValue = mode),
            "penaltyValue" to FirestoreValue(integerValue = value.toString()),
            "penaltyInterval" to FirestoreValue(stringValue = interval),
            "penaltyMax" to FirestoreValue(integerValue = max.toString())
        )

    /** Igual que [penaltyFields], pero para updates: si [mode] es null, limpia los 4 campos con NULL_VALUE en vez de omitirlos. */
    private fun penaltyFieldsOrClear(mode: String?, value: Int, interval: String, max: Int): Map<String, FirestoreValue> =
        if (mode != null) penaltyFields(mode, value, interval, max) else mapOf(
            "penaltyMode" to FirestoreValue(nullValue = "NULL_VALUE"),
            "penaltyValue" to FirestoreValue(nullValue = "NULL_VALUE"),
            "penaltyInterval" to FirestoreValue(nullValue = "NULL_VALUE"),
            "penaltyMax" to FirestoreValue(nullValue = "NULL_VALUE")
        )

    /**
     * Calcula `nextDueAt` (fecha límite de la ocurrencia pendiente) anclada en
     * [anchorMs] — la creación de la tarea o su última compleción. null para
     * "once" (usa `dueDate`). Ver KDoc de [org.taskhub.network.models.TaskResponse.nextDueAt].
     */
    private fun computeNextDueAt(
        frequency: String,
        recurrenceDay: Int?,
        recurrenceDays: List<Int>,
        anchorMs: Long
    ): Long? {
        if (frequency !in setOf("daily", "weekly", "monthly")) return null
        return RecurrenceRules.nextOccurrence(
            nowEpochMs = anchorMs,
            frequency = frequency,
            day = recurrenceDay,
            weeklyDays = recurrenceDays
        )
    }

    /** [FirestoreValue] para `nextDueAt`: entero si no es null, `NULL_VALUE` si lo es (limpia el campo previo). */
    private fun nextDueAtField(nextDueAt: Long?): FirestoreValue =
        if (nextDueAt != null) FirestoreValue(integerValue = nextDueAt.toString()) else FirestoreValue(nullValue = "NULL_VALUE")

    // ────────────────────────────────────────────────────────
    //  Tasks (subcollection under households/{id})
    // ────────────────────────────────────────────────────────

    /** Create a task. Returns the created task. */
    suspend fun createTask(
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
        dueDate: Long = 0,
        assignmentRotation: List<AssignmentSlot> = emptyList()
    ): TaskResponse {
        val now = Clock.System.now().toEpochMilliseconds()

        val fields = mutableMapOf<String, FirestoreValue>(
            "householdId" to FirestoreValue(stringValue = householdId),
            "createdBy" to FirestoreValue(stringValue = createdBy),
            "title" to FirestoreValue(stringValue = title),
            "description" to FirestoreValue(stringValue = description),
            "points" to FirestoreValue(integerValue = points.toString()),
            "frequency" to FirestoreValue(stringValue = frequency),
            "dueDate" to FirestoreValue(integerValue = dueDate.toString()),
            "createdAt" to FirestoreValue(integerValue = now.toString()),
            "updatedAt" to FirestoreValue(integerValue = now.toString())
        )

        // Tags as array
        fields["tags"] = tagsField(tags)

        // Recurrence days as array
        if (recurrenceDays.isNotEmpty()) {
            fields["recurrenceDays"] = recurrenceDaysField(recurrenceDays)
        }

        // Recurrence day of month (solo "monthly")
        if (recurrenceDay != null) {
            fields["recurrenceDay"] = FirestoreValue(integerValue = recurrenceDay.toString())
        }

        // Penalty configuration
        fields.putAll(penaltyFields(penaltyMode, penaltyValue, penaltyInterval, penaltyMax))

        // Assignment rotation as array of maps
        if (assignmentRotation.isNotEmpty()) {
            fields["assignmentRotation"] = assignmentRotationField(assignmentRotation)
        }

        // Subtasks as array of maps
        fields["subtasks"] = subtasksField(subtasks)

        // Fecha límite de la primera ocurrencia pendiente (recurrentes only).
        val nextDueAt = computeNextDueAt(frequency, recurrenceDay, recurrenceDays, now)
        if (nextDueAt != null) {
            fields["nextDueAt"] = nextDueAtField(nextDueAt)
        }

        val response: FirestoreDocumentResponse = client.post("$baseUrl/households/$householdId/tasks") {
            withAuth()
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }.body()

        val id = extractDocId(response.name, "createTask")
        taskCache.clearTasks(householdId)
        return TaskResponse(
            id = id, householdId = householdId, createdBy = createdBy,
            title = title, description = description, points = points,
            frequency = frequency, recurrenceDays = recurrenceDays,
            recurrenceDay = recurrenceDay, tags = tags,
            subtasks = subtasks,
            penaltyMode = penaltyMode, penaltyValue = penaltyValue,
            penaltyInterval = penaltyInterval, penaltyMax = penaltyMax,
            dueDate = dueDate, lastCompletedDate = null,
            assignmentRotation = assignmentRotation,
            nextDueAt = nextDueAt,
            createdAt = now, updatedAt = now
        )
    }

    /**
     * List all tasks for a household. Falls back to local cache if offline.
     *
     * Un 404/403 es una señal DEFINITIVA (hogar borrado / acceso perdido, igual
     * que en `getHousehold`) y se relanza en vez de devolver la caché stale;
     * solo fallos de transporte/servidor caen a caché.
     */
    suspend fun getTasks(householdId: String): List<TaskResponse> {
        return try {
            val documents = client.listAllDocuments("$baseUrl/households/$householdId/tasks") {
                withAuth()
            }

            val tasks = documents.map { toTaskResponse(it, householdId) }
            taskCache.cacheTasks(householdId, tasks)
            tasks
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirestoreException) {
            AppLog.w("TaskRepository", "getTasks: fetch failed for householdId=$householdId, falling back to cache", e)
            if (e.statusCode == 404 || e.statusCode == 403) throw e
            taskCache.getCachedTasks(householdId) ?: throw e
        } catch (e: Exception) {
            AppLog.e("TaskRepository", "getTasks: fetch failed for householdId=$householdId, falling back to cache", e)
            taskCache.getCachedTasks(householdId) ?: throw e
        }
    }

    /** Get a single task by id. Used where only one task is needed (avoids an N+1 full-list fetch). */
    suspend fun getTask(householdId: String, taskId: String): TaskResponse {
        val response: FirestoreDocumentResponse = client.getWithRetry("$baseUrl/households/$householdId/tasks/$taskId") {
            withAuth()
        }.body()
        return toTaskResponse(response, householdId)
    }

    // NOTA (ver `docs/recurrencia-backend-cloud-functions-diseno-2026-09-11.md`):
    // `revertTaskCompletion`/`saveTaskHistory`/`markTaskHistoryPointsApplied`
    // vivían aquí y se retiraron — la Cloud Function `undoTaskCompletion`
    // revierte `lastCompletedDate`/`completedBy`/`nextDueAt` dentro de su
    // propia transacción, y `completeRecurringTask`/`completeAssignment`
    // crean el registro de `taskHistory` con `pointsApplied: true` directo
    // (ya no hace falta el patrón "false → true" de dos escrituras).

    /**
     * Get all task history records for a household. Paginada
     * ([listAllDocuments]): es la colección que más crece sin techo natural
     * (un registro por compleción, para siempre) — mismo patrón ya aplicado
     * a `getTasks`/`getAssignments`/`messages` (panel v4, Experto 7 #1).
     *
     * Cache-first ante fallo (ronda de deuda aplicable 2026-09-12, punto
     * B11): antes usaba `orDefault(emptyList())`, que no distingue "el hogar
     * de verdad no tiene historial" de "no se pudo leer" — StatsScreen
     * (rachas/últimos 7 días) mostraba un hogar sin actividad en vez de la
     * última foto conocida ante un fallo de red puntual. Mismo patrón que
     * [getTasks]: un 404/403 es una señal DEFINITIVA y se relanza en vez de
     * caer a caché stale.
     */
    suspend fun getTaskHistory(householdId: String): List<TaskHistoryResponse> {
        return try {
            val documents = client.listAllDocuments("$baseUrl/households/$householdId/taskHistory") {
                withAuth()
            }
            val history = documents.map { doc -> FirestoreParsers.toTaskHistoryResponse(doc) }
            taskCache.cacheTaskHistory(householdId, history)
            history
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirestoreException) {
            AppLog.w("TaskRepository", "getTaskHistory: fetch failed for householdId=$householdId, falling back to cache", e)
            if (e.statusCode == 404 || e.statusCode == 403) throw e
            taskCache.getCachedTaskHistory(householdId) ?: emptyList()
        } catch (e: Exception) {
            AppLog.e("TaskRepository", "getTaskHistory: fetch failed for householdId=$householdId, falling back to cache", e)
            taskCache.getCachedTaskHistory(householdId) ?: emptyList()
        }
    }

    /**
     * Purga best-effort de registros de `taskHistory` con más de [maxAgeMillis]
     * de antigüedad (por [TaskHistoryResponse.completedAt]) — mismo patrón que
     * [NotificationRepository.purgeOldRead] (ronda de deuda aplicable
     * 2026-09-12, punto B9): TTL de retención de 90 días, a partir de una
     * lista ya obtenida con [getTaskHistory] (evita un segundo fetch; el
     * caller — `StatsScreenModel.loadStats`, que ya trae la colección
     * completa para calcular estadísticas — la pasa directamente).
     *
     * No rompe StatsScreen: la racha vive en el documento del miembro (no en
     * el historial) y la serie de 7 días/tasa de puntualidad de
     * `computeStats` solo miran compleciones RECIENTES, muy por debajo de
     * 90 días. Coste: 1 DELETE secuencial por registro purgado, igual que
     * [NotificationRepository.purgeOldRead] (sin límite de concurrencia: a
     * diferencia del cascade-delete de `deleteHousehold`, aquí no hay prisa
     * y se prioriza no saturar la cuota de escrituras) — en el primer ciclo
     * tras desplegar puede purgar un backlog considerable (la colección
     * creció sin límite hasta ahora); en ciclos posteriores, solo los
     * registros que acaban de cruzar el umbral de 90 días en cada household
     * cuyo StatsScreen se abra.
     */
    suspend fun purgeOldTaskHistory(householdId: String, all: List<TaskHistoryResponse>, maxAgeMillis: Long) {
        val cutoff = Clock.System.now().toEpochMilliseconds() - maxAgeMillis
        val stale = all.filter { it.completedAt < cutoff }
        if (stale.isEmpty()) return
        for (record in stale) {
            try {
                client.delete("$baseUrl/households/$householdId/taskHistory/${record.id}") {
                    withAuth()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("TaskRepository", "purgeOldTaskHistory: delete failed for householdId=$householdId, recordId=${record.id}", e)
                // Best-effort, ver KDoc de la función.
            }
        }
        taskCache.clearTaskHistory(householdId)
    }

    /**
     * Assign a task to one or more members with a due date.
     *
     * [assignedByMemberId], si se indica, no recibe notificación de su propia
     * asignación (p.ej. alguien que se auto-asigna una tarea) — panel de
     * notificaciones 2026-09-05, gap B.
     */
    suspend fun assignTask(
        householdId: String,
        taskId: String,
        memberIds: List<String>,
        mandatory: Boolean,
        dueDate: Long,
        taskTitle: String = "",
        assignedByMemberId: String? = null
    ): List<TaskAssignmentResponse> {
        val now = Clock.System.now().toEpochMilliseconds()
        val results = mutableListOf<TaskAssignmentResponse>()

        for (memberId in memberIds) {
            val fields = mutableMapOf<String, FirestoreValue>(
                "taskId" to FirestoreValue(stringValue = taskId),
                "memberId" to FirestoreValue(stringValue = memberId),
                "mandatory" to FirestoreValue(booleanValue = mandatory),
                "dueDate" to FirestoreValue(integerValue = dueDate.toString()),
                "status" to FirestoreValue(stringValue = "assigned"),
                "assignedAt" to FirestoreValue(integerValue = now.toString())
            )

            val response: FirestoreDocumentResponse = client.post(
                "$baseUrl/households/$householdId/tasks/$taskId/assignments"
            ) {
                withAuth()
                contentType(ContentType.Application.Json)
                setBody(FirestoreDocument(fields))
            }.body()

            val id = extractDocId(response.name, "assignTask")
            results.add(TaskAssignmentResponse(
                id = id, taskId = taskId, memberId = memberId,
                mandatory = mandatory, dueDate = dueDate, status = "assigned",
                assignedAt = now
            ))

            // Create notification for the assigned member (salvo que se esté
            // auto-asignando la tarea a sí mismo — no tiene sentido notificarle
            // algo que acaba de hacer él mismo).
            if (memberId != assignedByMemberId) {
                try {
                    val lang = settingsStore.getLanguage()
                    val messageKey = if (taskTitle.isNotEmpty())
                        "notification_task_assigned_body_prefix"
                    else
                        "notification_task_assigned_body_generic"
                    notificationRepository.createNotification(
                        householdId = householdId,
                        memberId = memberId,
                        taskId = taskId,
                        // title/message: fallback en el idioma de quien asigna,
                        // para notificaciones leídas por una versión antigua de
                        // la app o si titleKey/messageKey no se pueden resolver.
                        title = AppStrings.get("notification_task_assigned_title", lang),
                        message = if (taskTitle.isNotEmpty())
                            AppStrings.get("notification_task_assigned_body_prefix", lang) + taskTitle
                        else
                            AppStrings.get("notification_task_assigned_body_generic", lang),
                        titleKey = "notification_task_assigned_title",
                        messageKey = messageKey,
                        messageParams = if (taskTitle.isNotEmpty()) mapOf("taskTitle" to taskTitle) else null
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("TaskRepository", "assignTask: createNotification failed for taskId=$taskId, memberId=$memberId", e)
                    // No crítico: la asignación ya se creó, la notificación es un efecto secundario.
                }
            }
        }

        taskCache.clearAssignments(householdId, taskId)
        return results
    }

    /**
     * Get all assignments for a specific task. Falls back to local cache si
     * offline (kanban "[Caché] getAssignments/getAllAssignments sin caché
     * offline"): antes un fallo de red aquí propagaba la excepción directa
     * (o, en los callers que ya la envolvían en try/catch, se trataba como
     * "sin asignaciones" en vez de la última foto conocida) — mismo patrón
     * cache-first que [getTasks].
     */
    suspend fun getAssignments(householdId: String, taskId: String): List<TaskAssignmentResponse> {
        return try {
            val documents = client.listAllDocuments(
                "$baseUrl/households/$householdId/tasks/$taskId/assignments"
            ) {
                withAuth()
            }
            val assignments = documents.map { toTaskAssignmentResponse(it, taskId) }
            taskCache.cacheAssignments(householdId, taskId, assignments)
            assignments
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirestoreException) {
            AppLog.w("TaskRepository", "getAssignments: fetch failed for taskId=$taskId, falling back to cache", e)
            if (e.statusCode == 404 || e.statusCode == 403) throw e
            taskCache.getCachedAssignments(householdId, taskId) ?: throw e
        } catch (e: Exception) {
            AppLog.e("TaskRepository", "getAssignments: fetch failed for taskId=$taskId, falling back to cache", e)
            taskCache.getCachedAssignments(householdId, taskId) ?: throw e
        }
    }

    /** Borra todas las asignaciones de una tarea (para reasignar al editar). */
    suspend fun deleteAssignments(householdId: String, taskId: String) {
        val assignments = try {
            getAssignments(householdId, taskId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("TaskRepository", "deleteAssignments: getAssignments failed for taskId=$taskId", e)
            emptyList()
        }
        deleteAssignmentDocs(householdId, taskId, assignments)
    }

    /**
     * Borra un conjunto concreto de documentos de asignación (no la colección
     * entera) — usado por [replaceAssignments] y, desde `FirestoreRepository`,
     * para purgar las asignaciones "assigned" de un miembro eliminado (ver
     * `RecurrenceRules.purgeMemberFromRotation`). Cada borrado es best-effort.
     */
    suspend fun deleteAssignmentDocs(
        householdId: String,
        taskId: String,
        assignments: List<TaskAssignmentResponse>
    ) {
        assignments.forEach { assignment ->
            try {
                client.delete("$baseUrl/households/$householdId/tasks/$taskId/assignments/${assignment.id}") {
                    withAuth()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("TaskRepository", "deleteAssignmentDocs: delete failed for taskId=$taskId, assignmentId=${assignment.id}", e)
                // No crítico: si ya no existe, seguimos.
            }
        }
        taskCache.clearAssignments(householdId, taskId)
    }

    /**
     * Sustituye las asignaciones de una tarea por unas nuevas (usado al editar
     * la tarea desde `EditTaskScreen`).
     *
     * Mitigación de atomicidad (ver `docs/atomicidad-commit-pendiente.md`,
     * sección `updateTask`): antes esta operación era `deleteAssignments` +
     * `assignTask` como dos pasos independientes en el caller — si la creación
     * de las nuevas asignaciones fallaba a mitad de camino (p. ej. tras crear
     * la asignación de 2 de 3 miembros), la tarea ya se había quedado sin
     * ninguna asignación previa, así que el resultado era "tarea con solo 2
     * asignaciones" en el mejor caso o "sin ninguna" si fallaba en el primer
     * miembro. Aquí se invierte el orden: se crean las asignaciones nuevas
     * PRIMERO y solo se borran las antiguas si esa creación no lanzó. Si el
     * paso de creación falla, la tarea conserva sus asignaciones previas
     * (estado recuperable) en vez de quedarse sin ninguna. Sigue sin ser
     * atómico de extremo a extremo (un fallo justo en el borrado de las
     * antiguas puede dejar antiguas + nuevas duplicadas, un estado peor que
     * "sin cambios" pero mejor que "sin asignaciones").
     */
    suspend fun replaceAssignments(
        householdId: String,
        taskId: String,
        memberIds: List<String>,
        mandatory: Boolean,
        dueDate: Long,
        taskTitle: String = ""
    ): List<TaskAssignmentResponse> {
        val previous = try {
            getAssignments(householdId, taskId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("TaskRepository", "replaceAssignments: getAssignments failed for taskId=$taskId", e)
            emptyList()
        }

        val created = if (memberIds.isNotEmpty()) {
            assignTask(householdId, taskId, memberIds, mandatory, dueDate, taskTitle)
        } else {
            emptyList()
        }

        deleteAssignmentDocs(householdId, taskId, previous)

        return created
    }

    /** Get all assignments across all tasks for a household (peticiones en paralelo). */
    suspend fun getAllAssignments(householdId: String): List<TaskAssignmentResponse> =
        getAllAssignments(householdId, getTasks(householdId))

    /**
     * Igual que [getAllAssignments], pero recibiendo la lista de tareas ya
     * cargada por el llamador — evita releer `getTasks(householdId)` cuando
     * el caller ya la tiene (antes duplicaba esa lectura en 3 rutas
     * calientes: `StatsScreenModel.loadStats`, `TaskScreenModel.loadTasks`,
     * `CalendarSyncManager.reconcile`; panel v7, #18).
     */
    suspend fun getAllAssignments(householdId: String, tasks: List<TaskResponse>): List<TaskAssignmentResponse> {
        return coroutineScope {
            tasks.map { task ->
                async {
                    getAllAssignmentsSemaphore.withPermit {
                        try {
                            getAssignments(householdId, task.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.e("TaskRepository", "getAllAssignments: getAssignments failed for taskId=${task.id}", e)
                            emptyList() // Task has no assignments yet — skip
                        }
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * Actualiza solo el campo `assignmentRotation` de una tarea, sin tocar el
     * resto de campos (a diferencia de [updateTask], que reescribe la tarea
     * entera) — usado para purgar las referencias a un miembro eliminado
     * (ver `RecurrenceRules.purgeMemberFromRotation`) sin recalcular
     * `nextDueAt` ni pisar ningún otro campo editado independientemente.
     */
    suspend fun updateAssignmentRotation(
        householdId: String,
        taskId: String,
        assignmentRotation: List<AssignmentSlot>
    ) {
        val fields = mapOf("assignmentRotation" to assignmentRotationField(assignmentRotation))
        // Panel v17 (hallazgo CRÍTICO de red/offline): try/finally — un
        // timeout no distingue "nunca llegó al servidor" de "se aplicó pero
        // se perdió la respuesta"; invalidar de más es barato, no invalidar
        // de menos deja la caché sirviendo datos obsoletos indefinidamente
        // (mismo motivo que [FirestoreRepository.completeTask]).
        try {
            client.patch("$baseUrl/households/$householdId/tasks/$taskId") {
                withAuth()
                updateMaskFieldPaths("assignmentRotation")
                contentType(ContentType.Application.Json)
                setBody(FirestoreDocument(fields))
            }
        } finally {
            taskCache.clearTasks(householdId)
        }
    }

    /**
     * Vincula/desvincula el evento de Google Calendar de una asignación.
     * `googleEventId = null` limpia el campo (p. ej. tras borrar el evento).
     */
    suspend fun updateAssignmentGoogleEventId(
        householdId: String,
        taskId: String,
        assignmentId: String,
        googleEventId: String?
    ) {
        val fields = mapOf(
            "googleEventId" to if (googleEventId != null) {
                FirestoreValue(stringValue = googleEventId)
            } else {
                FirestoreValue(nullValue = "NULL_VALUE")
            }
        )
        // Panel v17 (hallazgo CRÍTICO de red/offline): ver comentario en
        // [updateAssignmentRotation].
        try {
            client.patch(
                "$baseUrl/households/$householdId/tasks/$taskId/assignments/$assignmentId"
            ) {
                withAuth()
                updateMaskFieldPaths("googleEventId")
                contentType(ContentType.Application.Json)
                setBody(FirestoreDocument(fields))
            }
        } finally {
            taskCache.clearAssignments(householdId, taskId)
        }
    }

    // ────────────────────────────────────────────────────────
    //  Task helpers
    // ────────────────────────────────────────────────────────

    /**
     * Reescribe TODOS los campos editables de una tarea (a diferencia de
     * [updateSubtasks]/[updateAssignmentRotation], que solo tocan un campo).
     * Recalcula `nextDueAt` con [computeNextDueAt] anclado en
     * [lastCompletedDate] (o `now` si nunca se completó, igual que
     * [createTask]) para que editar un campo no relacionado con la
     * recurrencia (p.ej. el título) no adelante/atrase la fecha límite
     * pendiente. Los campos "borrables" (`recurrenceDay`, penalización,
     * `nextDueAt`) usan `NULL_VALUE` explícito vía [penaltyFieldsOrClear]/
     * [nextDueAtField] en vez de omitirse, para limpiar un valor previo si el
     * usuario lo quitó en este edit. Devuelve el `nextDueAt` recalculado (lo
     * usa el caller — ver [org.taskhub.ui.models.TaskScreenModel.updateTask] —
     * como `dueDate` de las asignaciones de una tarea recurrente sin fecha
     * límite manual).
     *
     * Concurrencia optimista (`currentDocument.updateTime` + reintento, mismo
     * patrón que [updateSubtasks]/[MemberRepository.addMemberPoints] — ver
     * `docs/atomicidad-commit-pendiente.md` para por qué NO se usa el
     * endpoint transaccional `:commit`): al reescribir el documento entero,
     * dos miembros editando la MISMA tarea casi a la vez (p.ej. uno cambia el
     * título mientras otro cambia los puntos) sufrían last-writer-wins puro —
     * el segundo PATCH en llegar pisaba el documento completo con su propia
     * foto, silenciosamente revirtiendo el cambio del primero. Ahora cada
     * intento relee el documento para obtener su `updateTime` y lo manda como
     * precondición; si otro escritor ganó la carrera entretanto, Firestore
     * rechaza el PATCH (`FAILED_PRECONDITION`/`ABORTED`) y se reintenta hasta
     * [FirestoreClient.OPTIMISTIC_WRITE_MAX_RETRIES] veces. A diferencia de
     * [updateSubtasks] (que recalcula su array sobre el documento fresco en
     * cada intento), aquí los campos a escribir ya son el estado final
     * decidido por quien edita (los parámetros de esta función) — no dependen
     * del documento leído, así que el reintento solo repite el mismo PATCH
     * con una precondición fresca. Si se agotan los reintentos, lanza
     * [TaskConflictException] en vez de la [FirestoreException] cruda, para
     * que la UI pueda mostrar un mensaje específico ("la tarea fue modificada
     * por otro miembro") en vez del genérico de fallo de red.
     */
    suspend fun updateTask(
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
        dueDate: Long = 0,
        /**
         * `lastCompletedDate` de la tarea ANTES de este edit — ancla el
         * recálculo de `nextDueAt` en la última ocurrencia real completada en
         * vez de en el momento del edit, para no adelantar/atrasar la fecha
         * límite pendiente solo por editar un campo no relacionado con la
         * recurrencia (p.ej. el título). null si nunca se completó (tarea
         * nueva o recién creada) — se ancla en `now`, igual que [createTask].
         */
        lastCompletedDate: Long? = null
    ): Long? {
        val now = Clock.System.now().toEpochMilliseconds()

        val fields = mutableMapOf<String, FirestoreValue>(
            "title" to FirestoreValue(stringValue = title),
            "description" to FirestoreValue(stringValue = description),
            "points" to FirestoreValue(integerValue = points.toString()),
            "frequency" to FirestoreValue(stringValue = frequency),
            "dueDate" to FirestoreValue(integerValue = dueDate.toString()),
            "updatedAt" to FirestoreValue(integerValue = now.toString())
        )

        // Tags as array
        fields["tags"] = tagsField(tags)

        // Recurrence days as array
        fields["recurrenceDays"] = recurrenceDaysField(recurrenceDays)

        // Recurrence day of month (solo "monthly"); null borra el valor previo.
        fields["recurrenceDay"] = if (recurrenceDay != null) {
            FirestoreValue(integerValue = recurrenceDay.toString())
        } else {
            FirestoreValue(nullValue = "NULL_VALUE")
        }

        // Penalty configuration
        fields.putAll(penaltyFieldsOrClear(penaltyMode, penaltyValue, penaltyInterval, penaltyMax))

        // Assignment rotation as array of maps
        fields["assignmentRotation"] = assignmentRotationField(assignmentRotation)

        // Subtasks as array of maps
        fields["subtasks"] = subtasksField(subtasks)

        // Fecha límite de la ocurrencia pendiente, recalculada por si cambió
        // la frecuencia/recurrenceDays/recurrenceDay; NULL_VALUE si ya no es
        // recurrente (limpia un nextDueAt previo).
        val nextDueAt = computeNextDueAt(frequency, recurrenceDay, recurrenceDays, lastCompletedDate ?: now)
        fields["nextDueAt"] = nextDueAtField(nextDueAt)

        // Concurrencia optimista — ver KDoc de la función. Cada intento relee
        // el documento (para su `updateTime` fresco) y repite el mismo PATCH;
        // el valor devuelto (`nextDueAt`, ver comentario más abajo) no cambia
        // entre intentos porque no depende del documento leído.
        val docUrl = "$baseUrl/households/$householdId/tasks/$taskId"
        repeat(FirestoreClient.OPTIMISTIC_WRITE_MAX_RETRIES) { attempt ->
            val current: FirestoreDocumentResponse = client.getWithRetry(docUrl) { withAuth() }.body()
            try {
                client.patch(docUrl) {
                    withAuth()
                    updateMaskFieldPaths(fields.keys)
                    current.updateTime?.let { parameter("currentDocument.updateTime", it) }
                    contentType(ContentType.Application.Json)
                    setBody(FirestoreDocument(fields))
                }
                taskCache.clearTasks(householdId)
                // Se devuelve para que el caller (FirestoreRepository.updateTask
                // → TaskScreenModel.updateTask) pueda usarlo como dueDate de las
                // asignaciones al editar una tarea recurrente sin fecha límite
                // manual — ver [assignmentRotationField]/KDoc de
                // TaskScreenModel.updateTask (panel v4, Experto 8 hallazgo #4
                // MEDIO).
                return nextDueAt
            } catch (e: FirestoreException) {
                AppLog.w("TaskRepository", "updateTask: optimistic write conflict for taskId=$taskId, attempt=$attempt", e)
                val isConflict = e.code == "FAILED_PRECONDITION" || e.code == "ABORTED"
                if (!isConflict) throw e
                if (attempt == FirestoreClient.OPTIMISTIC_WRITE_MAX_RETRIES - 1) {
                    // Se agotaron los reintentos: la caché se invalida igual
                    // (nuestra foto local ya no es de fiar) y se lanza la
                    // excepción tipada — ver KDoc de la función.
                    taskCache.clearTasks(householdId)
                    throw TaskConflictException(taskId)
                }
                // Otro escritor ganó la carrera: reintentar con el updateTime fresco.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("TaskRepository", "updateTask: patch failed for taskId=$taskId, attempt=$attempt", e)
                // Timeout/IOException (AMBIGUOUS): ver KDoc de [MemberRepository.addMemberPoints].
                if (e.errorCategory() == ErrorCategory.AMBIGUOUS) {
                    taskCache.clearTasks(householdId)
                }
                throw e
            }
        }
        error("updateTask: se agotó el bucle de reintentos sin devolver ni lanzar (inalcanzable)")
    }

    /**
     * Actualiza el array `subtasks` de una tarea aplicando [transform] sobre
     * el array FRESCO leído dentro del propio reintento — usado para marcar/
     * desmarcar una subtarea concreta sin pisar el cambio de otro dispositivo.
     *
     * Panel v17 (hallazgo CRÍTICO de red/offline): antes recibía la lista ya
     * calculada por el caller (leída ANTES de esta llamada) y hacía un PATCH
     * ciego del array completo, sin `currentDocument.updateTime`. Dos
     * dispositivos marcando subtareas DISTINTAS de la misma tarea casi a la
     * vez podían pisarse: el segundo PATCH sobreescribía el array entero
     * basado en una foto anterior a la escritura del primero, revirtiendo su
     * cambio en silencio. Ahora usa concurrencia optimista igual que
     * [MemberRepository.addMemberPoints]: en cada reintento relee el
     * documento fresco, aplica [transform] sobre SU array de subtareas (no
     * sobre uno capturado fuera del bucle) y manda `currentDocument.updateTime`
     * como precondición — si otro escritor ganó la carrera entretanto,
     * Firestore rechaza el PATCH y se reintenta con el valor ya actualizado.
     */
    suspend fun updateSubtasks(
        householdId: String,
        taskId: String,
        transform: (List<Subtask>) -> List<Subtask>
    ) {
        val docUrl = "$baseUrl/households/$householdId/tasks/$taskId"
        repeat(FirestoreClient.OPTIMISTIC_WRITE_MAX_RETRIES) { attempt ->
            val current: FirestoreDocumentResponse = client.getWithRetry(docUrl) { withAuth() }.body()
            val updatedSubtasks = transform(toTaskResponse(current, householdId).subtasks)
            val fields = mapOf("subtasks" to subtasksField(updatedSubtasks))
            try {
                client.patch(docUrl) {
                    withAuth()
                    updateMaskFieldPaths("subtasks")
                    current.updateTime?.let { parameter("currentDocument.updateTime", it) }
                    contentType(ContentType.Application.Json)
                    setBody(FirestoreDocument(fields))
                }
                taskCache.clearTasks(householdId)
                return
            } catch (e: FirestoreException) {
                AppLog.w("TaskRepository", "updateSubtasks: optimistic write conflict for taskId=$taskId, attempt=$attempt", e)
                val isConflict = e.code == "FAILED_PRECONDITION" || e.code == "ABORTED"
                if (!isConflict || attempt == FirestoreClient.OPTIMISTIC_WRITE_MAX_RETRIES - 1) throw e
                // Otro escritor ganó la carrera: reintentar con el valor fresco.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("TaskRepository", "updateSubtasks: patch failed for taskId=$taskId, attempt=$attempt", e)
                // Timeout/IOException (AMBIGUOUS): ver KDoc de [MemberRepository.addMemberPoints].
                if (e.errorCategory() == ErrorCategory.AMBIGUOUS) {
                    taskCache.clearTasks(householdId)
                }
                throw e
            }
        }
    }

    /**
     * Delete a task document.
     */
    suspend fun deleteTask(householdId: String, taskId: String) {
        // Panel v17 (hallazgo CRÍTICO de red/offline): ver comentario en
        // [updateAssignmentRotation].
        try {
            client.delete("$baseUrl/households/$householdId/tasks/$taskId") {
                withAuth()
            }
        } finally {
            taskCache.clearTasks(householdId)
        }
    }

    private fun toTaskResponse(doc: FirestoreDocumentResponse, householdId: String): TaskResponse =
        FirestoreParsers.toTaskResponse(doc, householdId)

    private fun toTaskAssignmentResponse(
        doc: FirestoreDocumentResponse,
        taskId: String
    ): TaskAssignmentResponse = FirestoreParsers.toTaskAssignmentResponse(doc, taskId)

    // ────────────────────────────────────────────────────────
    //  Comments (subcollection under households/{id}/tasks/{taskId})
    // ────────────────────────────────────────────────────────

    /** Add a comment to a task. [memberId] identifica al autor (ver [CommentResponse.memberId]). */
    suspend fun addComment(
        householdId: String,
        taskId: String,
        memberId: String,
        authorName: String,
        text: String
    ): CommentResponse {
        val now = Clock.System.now().toEpochMilliseconds()
        val fields = mapOf(
            "memberId" to FirestoreValue(stringValue = memberId),
            "authorName" to FirestoreValue(stringValue = authorName),
            "text" to FirestoreValue(stringValue = text),
            "createdAt" to FirestoreValue(integerValue = now.toString())
        )

        val response: FirestoreDocumentResponse = client.post(
            "$baseUrl/households/$householdId/tasks/$taskId/comments"
        ) {
            withAuth()
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }.body()

        val id = extractDocId(response.name, "addComment")
        return CommentResponse(id, authorName, text, now, memberId)
    }

    /** List comments for a task. */
    suspend fun getComments(
        householdId: String,
        taskId: String
    ): List<CommentResponse> {
        val documents = client.listAllDocuments(
            "$baseUrl/households/$householdId/tasks/$taskId/comments"
        ) {
            withAuth()
        }

        return documents.map { doc -> toCommentResponse(doc) }
    }

    private fun toCommentResponse(doc: FirestoreDocumentResponse): CommentResponse =
        FirestoreParsers.toCommentResponse(doc)

    /**
     * Anonimiza `authorName` de los comentarios de tarea de los miembros en
     * [memberIds] — mismo patrón que [HouseholdRepository.anonymizeMemberMessages],
     * aplicado a comentarios en vez de mensajes de chat (panel de revisión
     * 2026-09-03/04, Experto 2/10). Los comentarios son subcolección de CADA
     * tarea (no del hogar), así que hay que recorrer todas las tareas del
     * hogar; en paralelo por tarea (mismo motivo que [getAllAssignments]:
     * evita un barrido secuencial en hogares con muchas tareas).
     *
     * Best-effort por comentario y por tarea: un fallo puntual no aborta el
     * resto. Los comentarios sin `memberId` (creados antes de este campo, ver
     * KDoc de [CommentResponse.memberId]) no se pueden identificar como del
     * miembro anonimizado y quedan sin tocar — limitación conocida, no
     * retro-migrable.
     */
    /** @return `true` si TODOS los comentarios se anonimizaron correctamente (ver KDoc de [HouseholdRepository.anonymizeMemberMessages], panel v16 hallazgo I7). */
    suspend fun anonymizeMemberComments(householdId: String, memberIds: Set<String>, anonymizedName: String): Boolean {
        if (memberIds.isEmpty()) return true
        val tasks = try {
            getTasks(householdId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("TaskRepository", "anonymizeMemberComments: getTasks failed for householdId=$householdId", e)
            return false
        }
        return coroutineScope {
            tasks.map { task ->
                async {
                    val comments = try {
                        getComments(householdId, task.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.e("TaskRepository", "anonymizeMemberComments: getComments failed for taskId=${task.id}", e)
                        return@async false
                    }
                    var allOk = true
                    comments.filter { it.memberId in memberIds }.forEach { comment ->
                        try {
                            client.patch(
                                "$baseUrl/households/$householdId/tasks/${task.id}/comments/${comment.id}"
                            ) {
                                withAuth()
                                updateMaskFieldPaths("authorName")
                                contentType(ContentType.Application.Json)
                                setBody(FirestoreDocument(mapOf("authorName" to FirestoreValue(stringValue = anonymizedName))))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.e("TaskRepository", "anonymizeMemberComments: patch failed for taskId=${task.id}, commentId=${comment.id}", e)
                            // Se prioriza anonimizar el resto de comentarios
                            // (best-effort), pero el fallo se acumula — ver KDoc arriba.
                            allOk = false
                        }
                    }
                    allOk
                }
            }.awaitAll().all { it }
        }
    }

    /**
     * Reescribe `memberId` a [anonymizedMemberId] en los registros de
     * `taskHistory` de los miembros en [memberIds] — mismo patrón que
     * [anonymizeMemberComments]/[org.taskhub.network.HouseholdRepository.anonymizeMemberMessages],
     * pero sobre el UID en vez de un nombre mostrado: a diferencia de
     * mensajes/comentarios, `taskHistory`/`rewardRedemptions` no
     * desnormalizan un `authorName`, solo guardan el `memberId` (UID de
     * Google) crudo, que quedaba incrustado para siempre en el historial de
     * un hogar compartido tras abandonar/ser expulsado (panel v14
     * 2026-09-20, Experto 10, IMPORTANTE — `deleteHousehold` ya lo borraba
     * todo, pero `leaveHousehold`/`deleteMember` no tocaban estas dos
     * colecciones cuando el hogar sigue existiendo para el resto). La UI no
     * resuelve nombres de miembros que ya no están en [getMembers], así que
     * el sentinel no rompe ninguna pantalla existente. Best-effort por
     * registro, igual que el resto de anonimizaciones.
     */
    /** @return `true` si TODO el historial se anonimizó correctamente (ver KDoc de [HouseholdRepository.anonymizeMemberMessages], panel v16 hallazgo I7). */
    suspend fun anonymizeMemberTaskHistory(householdId: String, memberIds: Set<String>, anonymizedMemberId: String): Boolean {
        if (memberIds.isEmpty()) return true
        val history = try {
            getTaskHistory(householdId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("TaskRepository", "anonymizeMemberTaskHistory: getTaskHistory failed for householdId=$householdId", e)
            return false
        }
        var allOk = true
        history.filter { it.memberId in memberIds }.forEach { record ->
            try {
                client.patch("$baseUrl/households/$householdId/taskHistory/${record.id}") {
                    withAuth()
                    updateMaskFieldPaths("memberId")
                    contentType(ContentType.Application.Json)
                    setBody(FirestoreDocument(mapOf("memberId" to FirestoreValue(stringValue = anonymizedMemberId))))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("TaskRepository", "anonymizeMemberTaskHistory: patch failed for householdId=$householdId, recordId=${record.id}", e)
                // Se prioriza anonimizar el resto del historial (best-effort),
                // pero el fallo se acumula en el resultado — ver KDoc arriba.
                allOk = false
            }
        }
        taskCache.clearTaskHistory(householdId)
        return allOk
    }
}
