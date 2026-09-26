/**
 * ScreenModel de [org.taskhub.ui.screens.HomeScreen]: agrega las tareas
 * pendientes de todos los hogares del usuario (dashboard unificado), calcula
 * la previsualización por hogar y mantiene el widget de Android sincronizado
 * con la lista combinada de pendientes.
 */
package org.taskhub.ui.models

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.taskhub.network.FirestoreRepository
import org.taskhub.network.RecurrenceRules
import org.taskhub.network.models.TaskResponse
import org.taskhub.platform.AppLog
import org.taskhub.platform.updateWidgetPendingTasks
import org.taskhub.storage.HouseholdStore
import org.taskhub.storage.SavedHousehold
import org.taskhub.storage.SettingsStore
import org.taskhub.ui.i18n.toUserMessage

/**
 * ViewModel compartido para [HomeScreen].
 *
 * Carga las tareas pendientes de TODOS los hogares del usuario
 * (incluyendo el espacio Personal) y actualiza el widget Android
 * con la lista agregada.
 */
class HomeScreenModel(
    private val repo: FirestoreRepository,
    private val householdStore: HouseholdStore,
    private val settingsStore: SettingsStore
) : ScreenModel {

    private val _uiState = MutableStateFlow(HomeScreenUiState())
    val uiState: StateFlow<HomeScreenUiState> = _uiState.asStateFlow()

    /**
     * Reconcilia los hogares guardados localmente contra Firestore antes de
     * mostrarlos — poda los que ya no existen o son inaccesibles (404/403),
     * conserva los demás ante cualquier fallo de red/servidor.
     */
    suspend fun reconcileHouseholds(): List<SavedHousehold> = repo.reconcileHouseholds(householdStore)

    /** Hogares guardados localmente, sin reconciliar contra red. Ver [reconcileHouseholds]. */
    fun getSavedHouseholds(): List<SavedHousehold> = householdStore.getSavedHouseholds()

    /**
     * Carga las tareas pendientes de todos los hogares y
     * actualiza el widget con la lista combinada.
     */
    private var loadAllTasksJob: Job? = null

    /**
     * Última lista CRUDA (sin filtrar por "pendiente") de tareas por hogar
     * que trajo [loadAllTasks] — [loadHouseholdPreview] la reutiliza en vez
     * de repetir su propio `getTasks(householdId)`, que con N hogares
     * duplicaba a 2N las lecturas de la colección `tasks` al entrar en
     * `HomeScreen` (panel v7, Exp. 11, CRÍTICO de rendimiento).
     */
    private var rawTasksByHousehold: Map<String, List<TaskResponse>> = emptyMap()

    fun loadAllTasks() {
        // Cancela la carga anterior: sin esto, dos loadAllTasks() solapadas
        // podrían resolverse fuera de orden y la más antigua sobrescribiría
        // el estado con datos obsoletos después de la más reciente.
        loadAllTasksJob?.cancel()
        loadAllTasksJob = screenModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val households = householdStore.getSavedHouseholds()
                val failedHouseholdIds = mutableSetOf<String>()

                val perHousehold = coroutineScope {
                    households.map { h ->
                        async {
                            h.id to try {
                                repo.getTasks(h.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // No se distingue "hogar borrado" de un fallo real
                                // (token caducado, 500, blip de red) — se expone en
                                // failedHouseholdIds para que la UI pueda avisar en
                                // vez de silenciarlo como "0 tareas".
                                AppLog.e("HomeScreenModel", "loadAllTasks: getTasks failed for household ${h.id}", e)
                                failedHouseholdIds += h.id
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }
                rawTasksByHousehold = perHousehold.toMap()
                val allTasks = perHousehold.flatMap { (hid, tasks) ->
                    tasks.filter { isPending(it) }.map { hid to it }
                }

                // Sort: overdue first, then by due date, then no-due-date last
                val now = Clock.System.now().toEpochMilliseconds()
                val sorted = allTasks.sortedBy { (_, task) ->
                    if (task.dueDate > 0 && task.dueDate < now) 0 // overdue
                    else if (task.dueDate > 0) task.dueDate
                    else Long.MAX_VALUE // no due date
                }

                // Update widget
                val widgetText = buildWidgetText(sorted, households)
                updateWidgetPendingTasks(widgetText)

                _uiState.value = HomeScreenUiState(
                    isLoading = false,
                    householdTasks = sorted.groupBy({ it.first }, { it.second }),
                    pendingCount = sorted.size
                )
            } catch (e: CancellationException) {
                // Relanzar: si no, una loadAllTasks() más reciente que ya canceló
                // este Job ve su propia cancelación como un error normal aquí y
                // puede sobrescribir el resultado correcto de la carga nueva.
                throw e
            } catch (e: Exception) {
                AppLog.e("HomeScreenModel", "loadAllTasks failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage(settingsStore.getLanguage(), "task_error_loading")
                )
            }
        }
    }

    // ── Previsualización por hogar (HouseholdTaskSection) ─────

    private val _previewTasks = MutableStateFlow<Map<String, HouseholdPreviewState>>(emptyMap())
    val previewTasks: StateFlow<Map<String, HouseholdPreviewState>> = _previewTasks.asStateFlow()

    /**
     * Carga las primeras tareas sin completar de UN hogar, para la
     * previsualización de [org.taskhub.ui.components.HouseholdTaskSection].
     * Antes esa sección inyectaba [FirestoreRepository] directamente y hacía
     * su propio fetch en un `LaunchedEffect`, sin pasar por ningún ScreenModel
     * — el único sitio del árbol con ese patrón (panel v7, #15).
     *
     * Usa [isPending] (misma regla que [loadAllTasks]) en vez de mirar
     * solo `lastCompletedDate == null` — antes del fix (panel 2026-09-11) una
     * tarea recurrente ya completada alguna vez (p. ej. diaria, completada
     * ayer) nunca volvía a aparecer aquí aunque hoy tocara de nuevo, aun
     * cuando sí aparecía correctamente en el dashboard agregado de
     * [loadAllTasks] — dos definiciones de "pendiente" que divergían. Test de
     * regresión: [previewFilterTasks] es la misma función pura usada aquí,
     * ver `HomeScreenModelTest.kt`.
     */
    private fun previewFilter(tasks: List<TaskResponse>): List<TaskResponse> =
        previewFilterTasks(tasks)

    fun loadHouseholdPreview(householdId: String) {
        screenModelScope.launch {
            // Espera a que un loadAllTasks() en curso termine de traer los
            // datos crudos (mismo Job que rellena [rawTasksByHousehold]) para
            // reutilizarlos en vez de duplicar el fetch — ver KDoc de
            // [rawTasksByHousehold]. `join()` es no-op si ya terminó o si
            // nunca se llamó (job null). Se captura la referencia ANTES del
            // `join()`: si ese Job fue CANCELADO (otro loadAllTasks() lo
            // reemplazó), `join()` no lanza, así que sin esta comprobación
            // se serviría `rawTasksByHousehold` obsoleto sin ningún aviso.
            val job = loadAllTasksJob
            job?.join()
            val cached = if (job?.isCancelled == true) null else rawTasksByHousehold[householdId]
            if (cached != null) {
                _previewTasks.value = _previewTasks.value + (householdId to HouseholdPreviewState.Success(previewFilter(cached)))
                return@launch
            }

            // Fallback con fetch propio: no hay datos cacheados para este
            // hogar (p. ej. loadAllTasks() aún no se ha llamado, o el hogar
            // no estaba en la lista guardada al cargarlos) — mismo
            // comportamiento que antes de este fix.
            _previewTasks.value = _previewTasks.value + (householdId to HouseholdPreviewState.Loading)
            try {
                val tasks = previewFilter(repo.getTasks(householdId))
                _previewTasks.value = _previewTasks.value + (householdId to HouseholdPreviewState.Success(tasks))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("HomeScreenModel", "loadHouseholdPreview failed for household $householdId", e)
                _previewTasks.value = _previewTasks.value + (
                    householdId to HouseholdPreviewState.Error(
                        e.toUserMessage(settingsStore.getLanguage(), "task_error_loading")
                    )
                )
            }
        }
    }

    /**
     * Construye el texto para el widget agrupando por hogar.
     */
    private fun buildWidgetText(
        sorted: List<Pair<String, TaskResponse>>,
        households: List<SavedHousehold>
    ): String {
        if (sorted.isEmpty()) return "🎉 ¡No hay tareas pendientes!"

        val now = Clock.System.now().toEpochMilliseconds()
        val householdsById = households.associateBy { it.id }

        return sorted.joinToString("\n") { (hid, task) ->
            val freqIcon = when (task.frequency) {
                "daily" -> "🔄"
                "weekly" -> "📅"
                "monthly" -> "📆"
                else -> "•"
            }
            val overdue = task.dueDate > 0 && task.dueDate < now
            val marker = if (overdue) "⚠️" else ""
            val household = householdsById[hid]
            // isPersonal (no comparar por nombre): un hogar COMPARTIDO al que
            // alguien le puso literalmente "Personal" de nombre se confundía
            // con el espacio Personal real y perdía el prefijo (panel 2026-09-11).
            val prefix = if (household != null && !household.isPersonal) "[${household.name}] " else ""
            "$marker$freqIcon $prefix${task.title}"
        }
    }

    data class HomeScreenUiState(
        val isLoading: Boolean = true,
        val householdTasks: Map<String, List<TaskResponse>> = emptyMap(),
        val pendingCount: Int = 0,
        val error: String? = null
    )
}

/**
 * Determina si una tarea está pendiente (no completada hoy). Función pura de
 * nivel de archivo (no método de [HomeScreenModel]) para poder testearla sin
 * mockear [FirestoreRepository]/[HouseholdStore]/[SettingsStore] — usada por
 * [HomeScreenModel.loadAllTasks] y, vía [previewFilterTasks], por
 * [HomeScreenModel.loadHouseholdPreview]. Ver `HomeScreenModelTest.kt` para
 * el test de regresión de la divergencia corregida en el panel 2026-09-11.
 */
internal fun isPending(task: TaskResponse, now: Instant = Clock.System.now()): Boolean {
    val tz = TimeZone.currentSystemDefault()
    val today = now.toLocalDateTime(tz).date
    val todayStartEpoch = today.atStartOfDayIn(tz).toEpochMilliseconds()

    val due = RecurrenceRules.isDueToday(
        frequency = task.frequency,
        recurrenceDays = task.recurrenceDays,
        recurrenceDay = task.recurrenceDay,
        lastCompletedDate = task.lastCompletedDate,
        nowEpochMs = now.toEpochMilliseconds(),
        tz = tz,
        createdAt = task.createdAt,
        dueDate = task.dueDate
    )

    val done = task.lastCompletedDate != null && task.lastCompletedDate >= todayStartEpoch
    return due && !done
}

/** Misma regla de "pendiente" que [isPending], recortada a las primeras 5 — ver [HomeScreenModel.previewFilter]. */
internal fun previewFilterTasks(tasks: List<TaskResponse>, now: Instant = Clock.System.now()): List<TaskResponse> =
    tasks.filter { isPending(it, now) }.take(5)

/** Estado de previsualización de tareas de UN hogar. Ver [HomeScreenModel.loadHouseholdPreview]. */
sealed class HouseholdPreviewState {
    data object Loading : HouseholdPreviewState()
    data class Success(val tasks: List<TaskResponse>) : HouseholdPreviewState()
    data class Error(val message: String) : HouseholdPreviewState()
}