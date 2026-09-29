/**
 * Manager (no ScreenModel) inyectado como singleton en Koin que sincroniza
 * tareas con fecha hacia Google Calendar. Lo usan [org.taskhub.ui.screens.TaskDetailScreen]
 * (sync bajo demanda) y [org.taskhub.ui.screens.PersonalSpaceScreen]/
 * [org.taskhub.ui.screens.HouseholdScreen] (reconciliación al entrar),
 * apoyándose en [GoogleAuthManager] para obtener el access token de Calendar.
 */
package org.taskhub.ui.models

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import org.taskhub.network.FirestoreRepository
import org.taskhub.network.GoogleCalendarRepository
import org.taskhub.network.models.TaskAssignmentResponse
import org.taskhub.platform.bestEffort
import org.taskhub.storage.SettingsStore

/**
 * Contrato de [CalendarSyncManagerImpl] — extraído para poder inyectar un
 * doble de prueba en tests de clase (p. ej. [org.taskhub.ui.models.TaskScreenModel])
 * sin construir la cadena real [GoogleAuthManager]/[GoogleCalendarRepository]
 * (la primera lanza una coroutine sobre `Dispatchers.Main` en su `init`, poco
 * práctico de instanciar en un test JVM).
 */
interface CalendarSyncManager {
    suspend fun onTaskAssigned(
        householdId: String,
        householdName: String,
        isPersonal: Boolean,
        assignments: List<TaskAssignmentResponse>
    )

    suspend fun onTaskUnassigned(householdId: String, assignment: TaskAssignmentResponse)

    suspend fun onTaskCompleted(householdId: String, assignment: TaskAssignmentResponse)

    suspend fun onDueDateChanged(
        householdId: String,
        householdName: String,
        isPersonal: Boolean,
        assignment: TaskAssignmentResponse,
        newDueDate: Long,
        taskTitle: String,
        taskDescription: String = ""
    )

    suspend fun reconcile(householdId: String, householdName: String, isPersonal: Boolean)

    suspend fun syncNow(
        householdId: String,
        householdName: String,
        isPersonal: Boolean,
        assignment: TaskAssignmentResponse,
        task: org.taskhub.network.models.TaskResponse
    ): Boolean
}

/**
 * Orquesta la sincronización automática de tareas con fecha → eventos en un
 * calendario de Google Calendar dedicado, uno por (usuario, espacio):
 * "Tareas personal" para el espacio Personal, "Tareas <hogar>" para hogares
 * compartidos.
 *
 * Solo sincroniza las asignaciones del usuario actual — cada persona ve sus
 * propias tareas en su propio calendario (ver [FirestoreRepository.resolveCurrentMember]).
 * Un `googleEventId` en la asignación (Firestore) marca que ya está sincronizada.
 *
 * Toda operación es best-effort: nunca lanza excepciones. Si no hay cuenta
 * vinculada o el token falla (consentimiento pendiente, offline, revocado),
 * se salta en silencio — [reconcile] se encarga de recuperar el estado más
 * tarde, sin bloquear la UI ni el flujo de tareas.
 */
class CalendarSyncManagerImpl(
    private val repo: FirestoreRepository,
    private val calendarRepo: GoogleCalendarRepository,
    private val settingsStore: SettingsStore,
    private val authManager: GoogleAuthManager
) : CalendarSyncManager {

    /** Nombre del calendario dedicado para este espacio. */
    private fun calendarName(householdName: String, isPersonal: Boolean): String =
        if (isPersonal) "Tareas personal" else "Tareas $householdName"

    /**
     * Serializa la creación del calendario: [GoogleCalendarRepository.ensureCalendar]
     * hace "buscar por nombre, si no existe crear" pero esas dos llamadas HTTP
     * no son atómicas en la API de Google — dos coroutines de este MISMO
     * dispositivo llamando a [ensureCalendarId] casi a la vez (p. ej.
     * [onTaskAssigned] y [reconcile] disparados juntos al abrir la app) podían
     * buscar antes de que ninguna hubiera creado todavía, y las dos acababan
     * creando un calendario duplicado (panel v7, Exp. 12, MENOR). No cubre
     * carreras entre DISTINTOS dispositivos con la misma cuenta (fuera de
     * alcance de un `Mutex` en memoria de proceso).
     */
    private val ensureCalendarMutex = Mutex()

    /**
     * Limita a 4 las creaciones de evento simultáneas contra la API de Google
     * Calendar: antes se creaban en serie (N+1), lo que era lento con muchas
     * asignaciones pendientes; paralelizar sin límite arriesga rate-limiting
     * de la API (condición de aprobación de la tarjeta de rendimiento).
     */
    private val createEventSemaphore = Semaphore(4)

    /**
     * Cuenta los fallos consecutivos de [GoogleAuthManager.ensureCalendarAccessToken].
     * Al llegar a [MAX_CONSECUTIVE_TOKEN_FAILURES], se desvincula la cuenta
     * ([SettingsStore.unlinkGoogleCalendar]) para que [SettingsStore.hasGoogleLinked]
     * refleje que el enlace ya no es válido (p. ej. consentimiento revocado en
     * Google) en vez de quedarse indefinidamente en "vinculado" pese a que todo
     * intento de sincronizar falle en silencio (panel v21). Se resetea a 0 en
     * cualquier sincronización que obtenga el token con éxito.
     */
    private var consecutiveTokenFailures = 0

    private companion object {
        /** Ver KDoc de [reconcile] (panel v17, hallazgo de rendimiento). */
        const val RECONCILE_THROTTLE_MS = 15 * 60 * 1000L

        /** Ver KDoc de [consecutiveTokenFailures]. */
        const val MAX_CONSECUTIVE_TOKEN_FAILURES = 3
    }

    /**
     * Envuelve [GoogleAuthManager.ensureCalendarAccessToken] para llevar la
     * cuenta de fallos consecutivos de [consecutiveTokenFailures] — ver su KDoc.
     */
    private suspend fun ensureCalendarAccessToken(): String? {
        val token = authManager.ensureCalendarAccessToken()
        if (token != null) {
            consecutiveTokenFailures = 0
        } else {
            consecutiveTokenFailures++
            if (consecutiveTokenFailures >= MAX_CONSECUTIVE_TOKEN_FAILURES) {
                settingsStore.unlinkGoogleCalendar()
            }
        }
        return token
    }

    /** Devuelve el calendarId cacheado localmente, o lo crea/busca y lo cachea. */
    private suspend fun ensureCalendarId(
        householdId: String,
        householdName: String,
        isPersonal: Boolean,
        accessToken: String
    ): String? {
        settingsStore.getCalendarId(householdId)?.let { return it }
        return ensureCalendarMutex.withLock {
            // Re-comprobar tras adquirir el lock (double-checked locking):
            // otra coroutine pudo haber terminado de crear y cachear el
            // calendario mientras esta esperaba, ver KDoc de [ensureCalendarMutex].
            settingsStore.getCalendarId(householdId)?.let { return@withLock it }
            bestEffort(null, "CalendarSyncManager.ensureCalendarId.createOrFind") {
                val id = calendarRepo.ensureCalendar(accessToken, calendarName(householdName, isPersonal))
                settingsStore.setCalendarId(householdId, id)
                id
            }
        }
    }

    /**
     * Al asignar una tarea (crear o reasignar): crea un evento en Calendar para
     * cada asignación mía con fecha, y persiste el `googleEventId`. Las
     * asignaciones sin fecha (`dueDate == 0`) o de otros miembros se ignoran.
     */
    override suspend fun onTaskAssigned(
        householdId: String,
        householdName: String,
        isPersonal: Boolean,
        assignments: List<TaskAssignmentResponse>
    ) {
        if (!settingsStore.isCalendarSyncEnabled()) return
        bestEffort(Unit, "CalendarSyncManager.onTaskAssigned.sync") {
            val myMemberId = repo.resolveCurrentMember(householdId)
            val mine = assignments.filter { it.memberId == myMemberId && it.dueDate > 0 }
            if (mine.isEmpty()) return@bestEffort

            val token = ensureCalendarAccessToken() ?: return@bestEffort
            val calendarId = ensureCalendarId(householdId, householdName, isPersonal, token) ?: return@bestEffort
            val tasks = bestEffort(emptyList(), "CalendarSyncManager.onTaskAssigned.getTasks") {
                repo.getTasks(householdId)
            }

            coroutineScope {
                mine.map { assignment ->
                    async {
                        createEventSemaphore.withPermit {
                            createEventForAssignment(householdId, calendarId, token, assignment, tasks)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /** Al desasignar/borrar una tarea: borra el evento vinculado (si lo hay) y limpia el campo. */
    override suspend fun onTaskUnassigned(householdId: String, assignment: TaskAssignmentResponse) {
        if (!settingsStore.isCalendarSyncEnabled()) return
        deleteEventForAssignment(householdId, assignment)
    }

    /** Al completar una tarea: el evento ya no tiene sentido, se borra igual que al desasignar. */
    override suspend fun onTaskCompleted(householdId: String, assignment: TaskAssignmentResponse) {
        if (!settingsStore.isCalendarSyncEnabled()) return
        deleteEventForAssignment(householdId, assignment)
    }

    /** Si cambia la fecha límite de una asignación ya sincronizada, actualiza el evento existente. */
    override suspend fun onDueDateChanged(
        householdId: String,
        householdName: String,
        isPersonal: Boolean,
        assignment: TaskAssignmentResponse,
        newDueDate: Long,
        taskTitle: String,
        taskDescription: String
    ) {
        if (!settingsStore.isCalendarSyncEnabled()) return
        bestEffort(Unit, "CalendarSyncManager.onDueDateChanged.sync") {
            val myMemberId = repo.resolveCurrentMember(householdId)
            if (assignment.memberId != myMemberId) return@bestEffort

            val token = ensureCalendarAccessToken() ?: return@bestEffort
            val eventId = assignment.googleEventId
            if (eventId == null) {
                if (newDueDate <= 0) return@bestEffort
                val calendarId = ensureCalendarId(householdId, householdName, isPersonal, token) ?: return@bestEffort
                val event = calendarRepo.createEvent(
                    accessToken = token,
                    calendarId = calendarId,
                    summary = taskTitle,
                    description = taskDescription,
                    dueDateEpochMs = newDueDate
                )
                repo.updateAssignmentGoogleEventId(householdId, assignment.taskId, assignment.id, event.id)
                return@bestEffort
            }

            val calendarId = settingsStore.getCalendarId(householdId) ?: return@bestEffort
            if (newDueDate <= 0) {
                calendarRepo.deleteEvent(token, calendarId, eventId)
                repo.updateAssignmentGoogleEventId(householdId, assignment.taskId, assignment.id, null)
            } else {
                calendarRepo.updateEvent(
                    accessToken = token,
                    calendarId = calendarId,
                    eventId = eventId,
                    summary = taskTitle,
                    description = taskDescription,
                    dueDateEpochMs = newDueDate
                )
            }
        }
    }

    /**
     * Al abrir un espacio (hogar/Personal): backfillea las asignaciones mías con
     * fecha que aún no tienen `googleEventId` (p. ej. porque el token no estaba
     * vinculado cuando se crearon). Idempotente — no toca nada que ya esté bien.
     */
    override suspend fun reconcile(householdId: String, householdName: String, isPersonal: Boolean) {
        if (!settingsStore.isCalendarSyncEnabled()) return
        // Panel v17 (hallazgo IMPORTANTE de rendimiento): throttle — sin
        // esto, cada apertura de HouseholdScreen/PersonalSpaceScreen repetía
        // el fetch completo (getTasks + getAllAssignments) aunque no hubiera
        // nada pendiente el 99% de las veces (ver KDoc de
        // [SettingsStore.getLastCalendarReconcileAt]).
        val now = Clock.System.now().toEpochMilliseconds()
        val lastReconcileAt = settingsStore.getLastCalendarReconcileAt(householdId)
        if (now - lastReconcileAt < RECONCILE_THROTTLE_MS) return
        bestEffort(Unit, "CalendarSyncManager.reconcile.sync") {
            val myMemberId = repo.resolveCurrentMember(householdId)
            // Tareas cargadas UNA vez y reutilizadas tanto para
            // getAllAssignments(tasks) como para construir los eventos más
            // abajo — antes se pedían dos veces (una dentro de
            // getAllAssignments(), otra explícita) cuando había pendientes
            // (panel v7, #18).
            val tasks = bestEffort(emptyList(), "CalendarSyncManager.reconcile.getTasks") {
                repo.getTasks(householdId)
            }
            val assignments = repo.getAllAssignments(householdId, tasks)
            // Se marca "reconciliado" en cuanto se completa el fetch caro
            // (independientemente de si había algo pendiente) — es lo que el
            // throttle de arriba quiere evitar repetir.
            settingsStore.setLastCalendarReconcileAt(householdId, now)
            val pending = assignments.filter {
                it.memberId == myMemberId &&
                    it.dueDate > 0 &&
                    it.googleEventId == null &&
                    it.status != "completed"
            }
            if (pending.isEmpty()) return@bestEffort

            val token = ensureCalendarAccessToken() ?: return@bestEffort
            val calendarId = ensureCalendarId(householdId, householdName, isPersonal, token) ?: return@bestEffort

            coroutineScope {
                pending.map { assignment ->
                    async {
                        createEventSemaphore.withPermit {
                            createEventForAssignment(householdId, calendarId, token, assignment, tasks)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * Sincroniza una asignación concreta bajo demanda (botón "Sincronizar ahora"
     * en el detalle de tarea). A diferencia del resto de métodos, ignora el
     * interruptor de sincronización automática — es una acción explícita del
     * usuario. Devuelve true si el evento quedó creado y enlazado.
     */
    override suspend fun syncNow(
        householdId: String,
        householdName: String,
        isPersonal: Boolean,
        assignment: TaskAssignmentResponse,
        task: org.taskhub.network.models.TaskResponse
    ): Boolean {
        if (assignment.dueDate <= 0) return false
        val token = ensureCalendarAccessToken() ?: return false
        val calendarId = ensureCalendarId(householdId, householdName, isPersonal, token) ?: return false
        return bestEffort(false, "CalendarSyncManager.syncNow.createEvent") {
            val event = calendarRepo.createEvent(
                accessToken = token,
                calendarId = calendarId,
                summary = task.title,
                description = task.description,
                dueDateEpochMs = assignment.dueDate
            )
            repo.updateAssignmentGoogleEventId(householdId, assignment.taskId, assignment.id, event.id)
            true
        }
    }

    private suspend fun createEventForAssignment(
        householdId: String,
        calendarId: String,
        token: String,
        assignment: TaskAssignmentResponse,
        tasks: List<org.taskhub.network.models.TaskResponse>
    ) {
        bestEffort(Unit, "CalendarSyncManager.createEventForAssignment.createEvent") {
            val task = tasks.find { it.id == assignment.taskId }
            val event = calendarRepo.createEvent(
                accessToken = token,
                calendarId = calendarId,
                summary = task?.title ?: "Tarea",
                description = task?.description.orEmpty(),
                dueDateEpochMs = assignment.dueDate
            )
            repo.updateAssignmentGoogleEventId(householdId, assignment.taskId, assignment.id, event.id)
        }
    }

    private suspend fun deleteEventForAssignment(householdId: String, assignment: TaskAssignmentResponse) {
        val eventId = assignment.googleEventId ?: return
        bestEffort(Unit, "CalendarSyncManager.deleteEventForAssignment.cleanup") {
            val calendarId = settingsStore.getCalendarId(householdId) ?: return@bestEffort
            val token = ensureCalendarAccessToken() ?: return@bestEffort
            // Puede que ya no exista (borrado a mano) — igualmente limpiamos el campo.
            bestEffort(Unit, "CalendarSyncManager.deleteEventForAssignment.deleteEvent") {
                calendarRepo.deleteEvent(token, calendarId, eventId)
            }
            repo.updateAssignmentGoogleEventId(householdId, assignment.taskId, assignment.id, null)
        }
    }
}
