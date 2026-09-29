package org.taskhub.ui.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.taskhub.network.FirestoreException
import org.taskhub.network.FirestoreRepository
import org.taskhub.network.models.MemberResponse
import org.taskhub.network.models.TaskResponse
import org.taskhub.platform.NoOpAdController
import org.taskhub.storage.SettingsStore
import org.taskhub.ui.i18n.AppStrings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Primer test de clase de [TaskScreenModel] (tarjeta kanban "TaskScreenModel
 * god object sin test de clase") — hasta ahora solo se testeaban funciones
 * de paquete extraídas de este archivo ([AchievementChecker], `computeStats`
 * en `StatsScreenModelTest`...), nunca el propio ScreenModel con sus
 * dependencias.
 *
 * [FirestoreRepository] es una `class` final que habla HTTP real, así que se
 * marcó `open` (igual que los métodos que usa este ScreenModel) solo para
 * permitir [FakeFirestoreRepository] — ver su KDoc. [CalendarSyncManager] se
 * dividió en interfaz + [CalendarSyncManagerImpl] por el mismo motivo, pero
 * además porque su única implementación real depende de [GoogleAuthManager],
 * que lanza una coroutine sobre `Dispatchers.Main` en su `init` — inviable de
 * instanciar en un test JVM sin acoplar el test a ese detalle interno.
 *
 * `screenModelScope` (Voyager) despacha en `Dispatchers.Main.immediate`; se
 * sustituye por [UnconfinedTestDispatcher] en cada test para que las
 * corrutinas lanzadas por el ScreenModel se resuelvan síncronamente antes de
 * las aserciones.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TaskScreenModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun member(id: String, streak: Int = 0) = MemberResponse(
        id = id,
        householdId = "h1",
        displayName = "Miembro $id",
        role = "child",
        totalPoints = 0,
        currentStreak = streak
    )

    private fun task(id: String, points: Int = 10) = TaskResponse(
        id = id,
        householdId = "h1",
        createdBy = "m1",
        title = "Tarea $id",
        points = points
    )

    private fun newModel(
        repo: FakeFirestoreRepository = FakeFirestoreRepository(),
        calendarSync: FakeCalendarSyncManager = FakeCalendarSyncManager(),
        notificationScheduler: FakeNotificationScheduler = FakeNotificationScheduler()
    ) = TaskScreenModel(
        repo = repo,
        notificationScheduler = notificationScheduler,
        calendarSync = calendarSync,
        adController = NoOpAdController(),
        settingsStore = SettingsStore(InMemorySettings())
    )

    // ── loadTasks ─────────────────────────────────────────────

    @Test
    fun loadTasks_exito_publicaListaYRecopilaTagsOrdenados() = runTest {
        val repo = FakeFirestoreRepository().apply {
            tasks = listOf(
                task("t1").copy(tags = listOf("cocina", "urgente")),
                task("t2").copy(tags = listOf("baño"))
            )
            members = listOf(member("m1"))
            online = true
        }
        val model = newModel(repo)

        model.loadTasks("h1")

        val state = model.listState.value
        assertIs<TaskListUiState.Success>(state)
        assertEquals(2, state.tasks.size)
        assertEquals(listOf("baño", "cocina", "urgente"), model.allTags.value)
        assertTrue(!model.isOffline.value)
    }

    @Test
    fun loadTasks_recursoBorradoOSinAcceso_marcaErrorSinTocarOffline() = runTest {
        val repo = FakeFirestoreRepository().apply {
            getTasksError = FirestoreException(statusCode = 404, message = "not found")
        }
        val model = newModel(repo)

        model.loadTasks("h1")

        val state = model.listState.value
        assertIs<TaskListUiState.Error>(state)
        assertEquals(AppStrings.get("error_gone_or_forbidden", "es"), state.message)
        assertTrue(!model.isOffline.value, "un 404/403 es 'sin acceso', no 'sin conexión'")
    }

    @Test
    fun loadTasks_fallosGenerico_marcaOfflineYError() = runTest {
        val repo = FakeFirestoreRepository().apply {
            getTasksError = RuntimeException("boom")
        }
        val model = newModel(repo)

        model.loadTasks("h1")

        assertIs<TaskListUiState.Error>(model.listState.value)
        assertTrue(model.isOffline.value)
    }

    // ── createTask ────────────────────────────────────────────

    @Test
    fun createTask_sinMiembrosSeleccionados_autoAsignaATodosLosMiembrosDelHogar() = runTest {
        val repo = FakeFirestoreRepository().apply {
            members = listOf(member("m1"), member("m2"), member("m3"))
        }
        val model = newModel(repo)

        model.createTask(
            householdId = "h1",
            createdBy = "m1",
            title = "Fregar platos",
            description = "",
            points = 10,
            frequency = "once",
            recurrenceDays = emptyList(),
            tags = emptyList(),
            penaltyMode = null,
            penaltyValue = 0,
            penaltyInterval = "day",
            penaltyMax = 0,
            memberIds = emptyList(),
            mandatory = false,
            dueDate = 0
        )

        assertEquals(TaskActionState.Success, model.actionState.value)
        assertEquals(listOf("Fregar platos"), repo.createTaskCalls)
        assertEquals(setOf("m1", "m2", "m3"), repo.assignTaskCalls.single().toSet())
    }

    // ── completeTask ──────────────────────────────────────────

    @Test
    fun completeTask_dobleTapMientrasHayUnaEnVuelo_ignoraLaSegundaLlamada() = runTest {
        val repo = FakeFirestoreRepository().apply {
            tasks = listOf(task("t1"))
            members = listOf(member("m1"))
            hangGetTask = true // simula la primera llamada aún en curso
        }
        val model = newModel(repo)

        model.completeTask("h1", "t1")
        assertEquals(TaskActionState.Loading, model.actionState.value)

        model.completeTask("h1", "t1") // debe no-opear: actionState ya está Loading

        assertEquals(0, repo.completeTaskCalls.size)
    }

    @Test
    fun completeTask_exito_otorgaPuntosYGuardaEstadoParaDeshacer() = runTest {
        val repo = FakeFirestoreRepository().apply {
            tasks = listOf(task("t1", points = 15))
            members = listOf(member("m1", streak = 2))
            completeTaskResult = FirestoreRepository.TaskCompletionResult(completedAt = 500L, pointsAwarded = 15, onTime = true)
        }
        val model = newModel(repo)
        model.setCurrentMemberId("m1")

        model.completeTask("h1", "t1")

        assertEquals(TaskActionState.Success, model.actionState.value)
        assertEquals(listOf(Triple("h1", "t1", "m1")), repo.completeTaskCalls)
        val undo = model.undoState.value
        assertEquals("t1", undo?.taskId)
        assertEquals(500L, undo?.completedAt)
        assertEquals(2, undo?.previousStreak, "debe guardar la racha ANTERIOR al premio, no la ya actualizada")
    }

    @Test
    fun completeTask_conflictoDeConcurrencia_muestraErrorTraducidoYRecargaLista() = runTest {
        val repo = FakeFirestoreRepository().apply {
            tasks = listOf(task("t1"))
            members = listOf(member("m1"))
            completeTaskError = FirestoreRepository.TaskCompletionConflictException("otro dispositivo ganó la carrera")
        }
        val model = newModel(repo)
        model.setCurrentMemberId("m1")

        model.completeTask("h1", "t1")

        val state = model.actionState.value
        assertIs<TaskActionState.Error>(state)
        assertEquals(AppStrings.get("task_completion_conflict_error", "es"), state.message)
        assertNull(model.undoState.value)
        assertIs<TaskListUiState.Success>(model.listState.value, "el conflicto debe recargar la lista, no dejarla en Idle")
    }

    // ── completeTask: contrato de idempotencyKey ───────────────
    // Ver KDoc de [TaskScreenModel.pendingIdempotencyKeys]: un reintento tras
    // un fallo AMBIGUO debe reutilizar la MISMA clave (para que el servidor
    // pueda deduplicar), pero una acción nueva tras un ÉXITO debe generar una
    // clave distinta (para no colisionar con una compleción real anterior).

    @Test
    fun completeTask_exito_propagaUnaIdempotencyKeyNoVaciaAlRepo() = runTest {
        val repo = FakeFirestoreRepository().apply {
            tasks = listOf(task("t1"))
            members = listOf(member("m1"))
        }
        val model = newModel(repo)
        model.setCurrentMemberId("m1")

        model.completeTask("h1", "t1")

        assertEquals(1, repo.completeTaskIdempotencyKeys.size)
        assertTrue(repo.completeTaskIdempotencyKeys.single().isNotBlank())
    }

    @Test
    fun completeTask_fallaAmbiguoYReintenta_reutilizaLaMismaIdempotencyKey() = runTest {
        val repo = FakeFirestoreRepository().apply {
            tasks = listOf(task("t1"))
            members = listOf(member("m1"))
            completeTaskError = RuntimeException("timeout: aplicado en servidor, respuesta perdida")
        }
        val model = newModel(repo)
        model.setCurrentMemberId("m1")

        model.completeTask("h1", "t1")
        assertIs<TaskActionState.Error>(model.actionState.value)
        val firstKey = repo.completeTaskIdempotencyKeys.single()

        // Reintento manual de la MISMA acción tras el fallo.
        repo.completeTaskError = null
        model.completeTask("h1", "t1")

        assertEquals(
            listOf(firstKey, firstKey),
            repo.completeTaskIdempotencyKeys,
            "el reintento tras un fallo ambiguo debe reutilizar la clave del intento anterior, no generar una nueva (evita duplicar puntos si el servidor ya lo aplicó)"
        )
    }

    @Test
    fun completeTask_exito_laSiguienteAccionGeneraClaveNueva() = runTest {
        val repo = FakeFirestoreRepository().apply {
            tasks = listOf(task("t1"))
            members = listOf(member("m1"))
        }
        val model = newModel(repo)
        model.setCurrentMemberId("m1")

        model.completeTask("h1", "t1")
        val firstKey = repo.completeTaskIdempotencyKeys.single()
        model.completeTask("h1", "t1") // segunda acción "real" (p.ej. tras deshacer)

        val secondKey = repo.completeTaskIdempotencyKeys[1]
        assertTrue(firstKey != secondKey, "tras un ÉXITO no debe quedar clave pendiente reutilizable — cada acción nueva necesita su propia clave")
    }

    // ── undoCompleteTask ──────────────────────────────────────

    @Test
    fun undoCompleteTask_revierteRachaYLlamaAlEndpointDeDeshacer() = runTest {
        val repo = FakeFirestoreRepository().apply {
            tasks = listOf(task("t1"))
            members = listOf(member("m1", streak = 3))
            completeTaskResult = FirestoreRepository.TaskCompletionResult(completedAt = 999L, pointsAwarded = 10, onTime = true)
        }
        val model = newModel(repo)
        model.setCurrentMemberId("m1")
        model.completeTask("h1", "t1")
        check(model.undoState.value != null) { "precondición: completeTask debió guardar undoState" }
        repo.updateMemberStreakCalls.clear() // completeTask() ya hizo su propia llamada (racha nueva); aislar la de undo

        model.undoCompleteTask()

        assertNull(model.undoState.value)
        assertEquals(listOf(999L), repo.undoTaskCompletionCalls)
        assertEquals(listOf(3), repo.updateMemberStreakCalls, "debe restaurar la racha PREVIA (3), no la actual")
    }

    /**
     * Panel v16 (2026-09-24), hallazgo CRÍTICO C4: [completeTask] publicaba
     * [TaskScreenModel.undoState] de forma optimista (con `completedAt = 0L`)
     * ANTES de que la llamada de red real (Cloud Function) resolviera. Si
     * [undoCompleteTask] se invocaba en ese margen, veía `completedAt == 0L`
     * y se SALTABA la llamada real a `repo.undoTaskCompletion` — la tarea
     * quedaba completada y los puntos otorgados en el servidor de forma
     * permanente, sin ningún error visible. Este test reproduce exactamente
     * esa secuencia con [FakeFirestoreRepository.hangCompleteTask] (simula el
     * round-trip de red aún en curso) y verifica que el undo ahora ESPERA el
     * resultado real antes de decidir, en vez de perderse en silencio.
     */
    @Test
    fun undoCompleteTask_disparadoMientrasCompleteTaskSigueEnVuelo_esperaYDeshaceConElCompletedAtReal() = runTest {
        val repo = FakeFirestoreRepository().apply {
            tasks = listOf(task("t1"))
            members = listOf(member("m1", streak = 3))
            completeTaskResult = FirestoreRepository.TaskCompletionResult(completedAt = 777L, pointsAwarded = 10, onTime = true)
            hangCompleteTask = true // simula que la respuesta del servidor aún no ha llegado
        }
        val model = newModel(repo)
        model.setCurrentMemberId("m1")

        model.completeTask("h1", "t1")
        // completeTask() sigue en vuelo (ver hangCompleteTask): el estado de
        // undo ya se publicó de forma OPTIMISTA, con completedAt = 0L —
        // exactamente la ventana de carrera del hallazgo C4.
        assertEquals(TaskActionState.Loading, model.actionState.value)
        check(model.undoState.value != null) { "precondición: completeTask debió publicar undoState optimista" }
        assertEquals(0L, model.undoState.value?.completedAt)

        // El usuario pulsa "Deshacer" DENTRO de esa ventana.
        model.undoCompleteTask()
        assertNull(model.undoState.value)
        // Antes del fix, esto se saltaba silenciosamente el undo real
        // (completedAt == 0L => no se llamaba a repo.undoTaskCompletion).
        // Ahora debe estar esperando, no haberse rendido ya.
        assertEquals(0, repo.undoTaskCompletionCalls.size, "el undo debe esperar la respuesta real, no perderse")

        // El servidor por fin responde a la compleción original.
        repo.releaseCompleteTask()

        assertEquals(TaskActionState.Success, model.actionState.value, "completeTask() debe seguir resolviendo en éxito")
        assertEquals(
            listOf(777L), repo.undoTaskCompletionCalls,
            "el undo diferido debe llamar al servidor con el completedAt REAL devuelto por completeTask, no perderse"
        )
        // La lista incluye también la propia llamada interna de
        // completeTask() (que registra la racha NUEVA al completar, antes de
        // que el undo diferido revierta a la previa) — lo relevante para
        // este hallazgo es que la ÚLTIMA llamada (la del undo diferido) use
        // la racha PREVIA (3), no que se haya perdido.
        assertEquals(3, repo.updateMemberStreakCalls.last(), "el undo diferido debe restaurar la racha PREVIA (3) una vez confirmado el undo real")
    }

    /**
     * Panel v16, hallazgo I10: el servidor puede hacer un no-op idempotente
     * (`reverted = false`, p.ej. undo ya aplicado desde otro dispositivo) —
     * la racha NO debe revertirse en ese caso, para no desincronizarla del
     * resto del estado (puntos/historial) que el servidor conservó intacto.
     */
    @Test
    fun undoCompleteTask_servidorDevuelveRevertedFalse_noRevierteLaRacha() = runTest {
        val repo = FakeFirestoreRepository().apply {
            tasks = listOf(task("t1"))
            members = listOf(member("m1", streak = 3))
            completeTaskResult = FirestoreRepository.TaskCompletionResult(completedAt = 999L, pointsAwarded = 10, onTime = true)
            undoTaskCompletionReverted = false // no-op idempotente del servidor
        }
        val model = newModel(repo)
        model.setCurrentMemberId("m1")
        model.completeTask("h1", "t1")
        check(model.undoState.value != null) { "precondición: completeTask debió guardar undoState" }
        repo.updateMemberStreakCalls.clear()

        model.undoCompleteTask()

        assertEquals(listOf(999L), repo.undoTaskCompletionCalls, "sí debe llamar al servidor")
        assertEquals(emptyList(), repo.updateMemberStreakCalls, "NO debe revertir la racha si el servidor no revirtió nada")
    }

    // ── deleteTask ────────────────────────────────────────────

    @Test
    fun deleteTask_exito_borraLaTareaYPublicaSuccess() = runTest {
        val repo = FakeFirestoreRepository()
        val model = newModel(repo)

        model.deleteTask("h1", "t1")

        assertEquals(TaskActionState.Success, model.actionState.value)
        assertEquals(listOf("t1"), repo.deleteTaskCalls)
    }

    // ── reset ─────────────────────────────────────────────────

    @Test
    fun reset_vuelveTodosLosStateFlowsASusValoresIniciales() = runTest {
        val repo = FakeFirestoreRepository().apply {
            tasks = listOf(task("t1"))
            members = listOf(member("m1"))
        }
        val model = newModel(repo)
        model.loadTasks("h1")
        model.setFilter(TaskFilter.COMPLETED)
        model.setSort(TaskSort.POINTS_DESC)
        model.setTagFilter("urgente")
        model.setSearchQuery("algo")
        model.setCurrentMemberId("m1")
        assertIs<TaskListUiState.Success>(model.listState.value) // precondición

        model.reset()

        assertEquals(TaskListUiState.Idle, model.listState.value)
        assertEquals(TaskFilter.MINE, model.filter.value)
        assertEquals(TaskSort.DEADLINE_ASC, model.sort.value)
        assertNull(model.selectedTagFilter.value)
        assertEquals("", model.searchQuery.value)
        assertNull(model.currentMemberId.value)
    }
}
