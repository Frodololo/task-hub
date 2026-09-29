/**
 * Listado principal de tareas de un hogar: agrupa por Vencidas/Hoy/Completadas
 * hoy, con filtros (estado, etiqueta, búsqueda), orden y deshacer al
 * completar. Se navega aquí desde [HouseholdScreen] ("Ver tareas"). Usa
 * [org.taskhub.ui.models.TaskScreenModel] como única fuente de estado
 * (lista, filtros, acciones); no hay "instancias" de tareas recurrentes en
 * Firestore — el estado due/completado se recalcula en cliente a partir de
 * `frequency` + `lastCompletedDate` (ver [isTaskDueToday]).
 */
package org.taskhub.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import org.taskhub.network.RecurrenceRules
import org.taskhub.network.models.TaskResponse
import org.taskhub.network.models.TaskAssignmentResponse
import org.taskhub.network.models.MemberResponse
import org.taskhub.ui.models.*
import org.taskhub.ui.components.AchievementToast
import org.taskhub.ui.components.AnimatedCheckmark
import org.taskhub.ui.components.ConfettiOverlay
import org.taskhub.ui.components.EffectCategory
import org.taskhub.ui.components.effectsEnabled
import org.taskhub.ui.components.EmptyState
import org.taskhub.ui.components.EmptyTasksIllustration
import org.taskhub.ui.components.formatFriendlyDate
import org.taskhub.ui.components.ErrorAwareSnackbarHost
import org.taskhub.ui.components.ExpandableSectionHeader
import org.taskhub.ui.components.HouseholdSettingsDialog
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.filterChipCheckIcon
import org.taskhub.ui.components.PointsBadge
import org.taskhub.ui.components.rememberHouseholdName
import org.taskhub.ui.components.StatusDot
import org.taskhub.ui.components.TaskHubTopBar
import org.taskhub.ui.components.ShimmerList
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.components.showErrorSnackbar
import org.taskhub.ui.components.taskHubTextFieldColors
import org.taskhub.ui.components.TaskWithStatus
import org.taskhub.ui.components.TaskGroup
import org.taskhub.ui.components.groupTasksByStatus
import org.taskhub.ui.components.isTaskDueToday
import org.taskhub.ui.components.isTaskCompletedToday
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.theme.*
import org.taskhub.platform.shareText

// ────────────────────────────────────────────────────────────
//  TaskListScreen
// ────────────────────────────────────────────────────────────

data class TaskListScreen(
    val householdId: String,
    val memberId: String? = null
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = koinScreenModel<TaskScreenModel>()
        val householdModel = koinScreenModel<HouseholdScreenModel>()
        val householdName = rememberHouseholdName(householdId, householdModel)
        val listState by model.listState.collectAsState()
        val actionState by model.actionState.collectAsState()
        val filter by model.filter.collectAsState()
        val sort by model.sort.collectAsState()
        val tagFilter by model.selectedTagFilter.collectAsState()
        val allTags by model.allTags.collectAsState()
        val currentMemberId by model.currentMemberId.collectAsState()
        val searchQuery by model.searchQuery.collectAsState()
        val undoState by model.undoState.collectAsState()
        val undoError by model.undoError.collectAsState()
        val isOffline by model.isOffline.collectAsState()
        val newlyUnlockedAchievement by model.newlyUnlockedAchievement.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }
        val appSettings = LocalAppSettings.current
        val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }
        val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

        // ── Undo snackbar ────────────────────────────────────
        LaunchedEffect(undoState) {
            if (undoState != null) {
                val result = snackbarHostState.showSnackbar(
                    message = s("task_list_undo_snackbar_msg"),
                    actionLabel = s("task_list_undo_action"),
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    model.undoCompleteTask()
                    model.loadTasks(householdId)
                } else {
                    model.clearUndoState()
                }
            }
        }

        // Aviso de error de deshacer — ver KDoc de TaskScreenModel.undoError
        // (ronda de deuda aplicable 2026-09-12, punto A4): antes un fallo de
        // undoCompleteTask() era completamente silencioso.
        LaunchedEffect(undoError) {
            if (undoError != null) {
                snackbarHostState.showSnackbar(message = undoError!!, duration = SnackbarDuration.Long)
                model.clearUndoError()
            }
        }

        LaunchedEffect(householdId) {
            // `memberId` puede llegar null si el caller (p. ej. HouseholdScreen)
            // navega aquí antes de que su propio `currentMemberId` termine de
            // resolverse de forma asíncrona. Con TaskFilter.MINE como pestaña
            // por defecto, un `currentMemberId` null deja la lista "Mías"
            // vacía sin ninguna vía de recuperación — se resuelve aquí como
            // fallback, mismo patrón que CreateTaskScreen.resolveCreator().
            model.setCurrentMemberId(
                memberId ?: try {
                    model.resolveCurrentMemberId(householdId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            )
            model.loadTasks(householdId)
        }

        // Reload when action completes
        LaunchedEffect(actionState) {
            val state = actionState
            if (state is TaskActionState.Success) {
                model.loadTasks(householdId)
                model.resetActionState()
            } else if (state is TaskActionState.Error) {
                // Sin esto, un fallo al completar una tarea (p.ej. red) no mostraba
                // ningún mensaje y la card de TaskCard se quedaba invisible/deshabilitada
                // para siempre (isCompleting/loadingTaskIds nunca se resetean por sí
                // solos — ver el TaskActionState.Error pasado a TaskListContent abajo).
                snackbarHostState.showErrorSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Short
                )
                model.resetActionState()
            }
        }

        // Settings dialog state
        var showSettings by remember { mutableStateOf(false) }

        val exportCsvScope = rememberCoroutineScope()

        // Pre-compute CSV export callback — needs access to list state
        val exportCsv: () -> Unit = {
            val state = listState
            if (state is TaskListUiState.Success) {
                val csv = model.generateCsv(state.tasks)
                // shareText devuelve true solo si copió en silencio al
                // portapapeles (desktop, sin hoja nativa) — ahí, sin este
                // snackbar, el usuario no tenía ninguna señal de que la
                // exportación funcionó (a diferencia de Android/iOS, donde el
                // chooser/share sheet nativo ya es la confirmación visual).
                if (shareText(csv, s("tasks_export_csv_title"))) {
                    exportCsvScope.launch {
                        snackbarHostState.showSnackbar(s("tasks_export_csv_copied"))
                    }
                }
            }
        }

        // Settings dialog
        if (showSettings) {
            HouseholdSettingsDialog(
                onDismiss = { showSettings = false },
                onEditProfile = {
                    showSettings = false
                    navigator.push(EditProfileScreen())
                },
                onExportCsv = exportCsv,
                showExportCsv = true
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(topBarScrollBehavior.nestedScrollConnection)
                ) {
                // Top bar
                TaskHubTopBar(
                    title = s("task_list_title"),
                    subtitle = householdName,
                    onBack = { navigator.pop() },
                    scrollBehavior = topBarScrollBehavior,
                    actions = {
                        IconButton(onClick = { model.loadTasks(householdId) }) {
                            Icon(Icons.Default.Refresh, contentDescription = s("task_list_refresh_content_desc"))
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = s("profile_settings_label"))
                        }
                        TextButton(
                            onClick = { navigator.push(CreateTaskScreen(householdId, currentMemberId ?: "")) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(s("tasks_new"), fontWeight = FontWeight.Bold)
                        }
                    }
                )

                // Content
                // ── Offline banner ──────────────────────────────
                if (isOffline) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.semanticColors.warningContainer
                    ) {
                        Text(
                            text = s("task_list_offline_banner"),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.onWarningContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                when (val state = listState) {
                    is TaskListUiState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            ShimmerList(count = 5, itemHeight = 88.dp)
                        }
                    }

                    is TaskListUiState.Success -> {
                        TaskListContent(
                            state = state,
                            actionErrorSignal = actionState as? TaskActionState.Error,
                            filter = filter,
                            sort = sort,
                            tagFilter = tagFilter,
                            allTags = allTags,
                            searchQuery = searchQuery,
                            currentMemberId = currentMemberId,
                            s = s,
                            onFilterChange = { model.setFilter(it) },
                            onSortChange = { model.setSort(it) },
                            onTagFilterChange = { model.setTagFilter(it) },
                            onSearchQueryChange = { model.setSearchQuery(it) },
                            onTaskClick = { task ->
                                navigator.push(TaskDetailScreen(householdId, task.id))
                            },
                            onCompleteTask = { task ->
                                model.completeTask(householdId, task.id)
                            },
                            onRefresh = { model.loadTasks(householdId) },
                            onCreateFirstTask = {
                                navigator.push(CreateTaskScreen(householdId, currentMemberId ?: ""))
                            }
                        )
                    }

                    is TaskListUiState.Error -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = s("error_icon_content_desc"),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = state.message,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { model.loadTasks(householdId) }) {
                                    Text(s("tasks_retry"))
                                }
                            }
                        }
                    }

                    is TaskListUiState.Idle -> {}
                }
            }
                // ── Snackbar for undo ──────────────────────────
                ErrorAwareSnackbarHost(
                    hostState = snackbarHostState,
                    errorIconContentDescription = s("error_icon_content_desc"),
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                AchievementToast(
                    achievement = newlyUnlockedAchievement,
                    onDismissed = { model.clearNewlyUnlockedAchievement() },
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
//  TaskListContent (task-based — no instances)
// ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskListContent(
    state: TaskListUiState.Success,
    /** No-null cada vez que completar una tarea falla: limpia los spinners "en curso". */
    actionErrorSignal: TaskActionState.Error?,
    filter: TaskFilter,
    sort: TaskSort,
    tagFilter: String?,
    allTags: List<String>,
    searchQuery: String,
    currentMemberId: String?,
    s: (String) -> String,
    onFilterChange: (TaskFilter) -> Unit,
    onSortChange: (TaskSort) -> Unit,
    onTagFilterChange: (String?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onTaskClick: (TaskResponse) -> Unit,
    onCompleteTask: (TaskResponse) -> Unit,
    onRefresh: () -> Unit,
    onCreateFirstTask: () -> Unit
) {
    // `memberMap`/`assignmentsByTask` se pasan tal cual a cada `TaskCard` de
    // la lista — remember(...) evita reconstruir el Map/groupBy (y por tanto
    // la identidad del objeto) en cada recomposición que no cambie los datos
    // reales (p.ej. colapsar/expandir un grupo, o el spinner de "completando"
    // de OTRA tarea), en vez de asignar uno nuevo cada vez.
    val memberMap = remember(state.members) { state.members.associateBy { it.id } }
    val assignmentsByTask = remember(state.assignments) { state.assignments.groupBy { it.taskId } }
    val reduceMotion = shouldReduceMotion()
    val lang = LocalAppSettings.current.currentLanguage

    // Compute today start for overdue detection and due-today calculation
    val now = Clock.System.now()
    val tz = TimeZone.currentSystemDefault()
    val todayStartEpoch = now.toLocalDateTime(tz).date
        .atStartOfDayIn(tz).toEpochMilliseconds()

    // Estado por tarea (isDueToday/isCompletedToday/isOverdue vía
    // RecurrenceRules) calculado UNA vez por tarea — independiente de
    // searchQuery/filter/tagFilter. Antes vivía en el mismo remember que el
    // filtro de texto: cada pulsación en el buscador (searchQuery en la key)
    // recalculaba el estado de TODAS las tareas del hogar, no solo relanzaba
    // el filtrado (panel v4, Experto 11 #2).
    val statusByTaskId = remember(state.tasks, todayStartEpoch) {
        state.tasks.associate { task ->
            val due = isTaskDueToday(task, todayStartEpoch)
            val done = isTaskCompletedToday(task, todayStartEpoch)
            // Recurrentes sin dueDate propio (daily/weekly/monthly): "vencida" es una
            // ocurrencia programada de un día ya pasado que sigue pendiente
            // (completado tardío) — ver RecurrenceRules.isOverdueOccurrence.
            val isOverdue = if (task.dueDate > 0) {
                task.dueDate < todayStartEpoch && task.lastCompletedDate == null
            } else {
                due && RecurrenceRules.isOverdueOccurrence(
                    frequency = task.frequency,
                    recurrenceDays = task.recurrenceDays,
                    recurrenceDay = task.recurrenceDay,
                    nowEpochMs = todayStartEpoch
                )
            }
            task.id to TaskWithStatus(
                task = task,
                isDueToday = due,
                isCompletedToday = done,
                isOverdue = isOverdue,
                isCompleted = !due && task.lastCompletedDate != null
            )
        }
    }

    // Filtrado por estado/tag/miembro + búsqueda de texto: sí depende de
    // searchQuery, pero reutiliza statusByTaskId en vez de recalcular
    // isDueToday/isCompletedToday/isOverdue por tarea.
    val tasksWithStatus = remember(statusByTaskId, assignmentsByTask, filter, tagFilter, searchQuery, currentMemberId) {
        // Filter tasks based on filter + tag
        val filteredTasks = state.tasks.filter { task ->
            // Tag filter
            if (tagFilter != null && tagFilter !in task.tags) return@filter false

            // Status filter
            when (filter) {
                TaskFilter.ALL -> true
                TaskFilter.PENDING -> {
                    // Pending = due today AND not completed today
                    val status = statusByTaskId.getValue(task.id)
                    status.isDueToday && !status.isCompletedToday
                }
                TaskFilter.COMPLETED -> statusByTaskId.getValue(task.id).isCompleted
                TaskFilter.MINE -> {
                    val mid = currentMemberId ?: return@filter false
                    val taskAssignments = assignmentsByTask[task.id] ?: emptyList()
                    taskAssignments.any { it.memberId == mid && it.status == "assigned" }
                }
            }
        }

        // Text search filter (client-side, after status filter, before groupByStatus)
        val searchFilteredTasks = if (searchQuery.isBlank()) {
            filteredTasks
        } else {
            val q = searchQuery.trim().lowercase()
            filteredTasks.filter { task ->
                task.title.lowercase().contains(q) ||
                task.description.lowercase().contains(q)
            }
        }

        searchFilteredTasks.map { task -> statusByTaskId.getValue(task.id) }
    }

    // Group by status
    val groups = remember(tasksWithStatus, filter, sort, tagFilter, lang) {
        groupTasksByStatus(tasksWithStatus, sort, lang)
    }

    // Collapse state per group
    val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }

    // Búsqueda colapsada a icono expandible (L4): el campo de texto solo
    // ocupa espacio cuando el usuario lo pide, en vez de siempre a tamaño
    // completo (diseño v2, fase 1, ítem 10).
    var searchExpanded by remember { mutableStateOf(searchQuery.isNotBlank()) }

    // Track which tasks are being completed (loading state)
    val loadingTaskIds = remember { mutableStateMapOf<String, Boolean>() }

    // Si completar una tarea falla, la card correspondiente quedaba con su
    // spinner/animación de salida bloqueados para siempre (nada los reseteaba
    // ante un error, solo ante éxito) — ver `isCompleting`/`onComplete` en
    // TaskCard más abajo, que observan `actionErrorSignal` para recuperarse.
    LaunchedEffect(actionErrorSignal) {
        if (actionErrorSignal != null) loadingTaskIds.clear()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        // Filter chips
        item {
            FilterChipsRow(
                currentFilter = filter,
                currentSort = sort,
                tagFilter = tagFilter,
                allTags = allTags,
                s = s,
                searchExpanded = searchExpanded,
                hasActiveSearch = searchQuery.isNotBlank(),
                onFilterChange = onFilterChange,
                onSortChange = onSortChange,
                onTagFilterChange = onTagFilterChange,
                onToggleSearch = { searchExpanded = !searchExpanded }
            )
        }

        // Search bar — solo ocupa espacio cuando el usuario expande el icono.
        if (searchExpanded) {
            item {
                SearchBar(
                    query = searchQuery,
                    s = s,
                    onQueryChange = onSearchQueryChange
                )
            }
        }

        // ── DEBUG: always show task count (only in debug builds) ──
        if (org.taskhub.platform.DebugFlags.isEnabled) {
            item {
                Text(
                    text = "DEBUG: ${state.tasks.size} tasks, filter=$filter, groups=${groups.size}, tagFilter=$tagFilter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }

        if (state.tasks.isEmpty()) {
            item {
                Spacer(Modifier.height(24.dp))
                EmptyState(
                    modifier = Modifier.fillMaxWidth(),
                    illustration = { EmptyTasksIllustration() },
                    title = s("task_list_empty_title"),
                    titleStyle = MaterialTheme.typography.titleLarge,
                    message = s("task_list_empty_subtitle"),
                    action = {
                        Button(onClick = onCreateFirstTask) {
                            Text(s("task_list_create_first"), fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }
        } else if (tasksWithStatus.isEmpty() || groups.isEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = when (filter) {
                            TaskFilter.PENDING -> s("task_list_filter_empty_pending").replace("%d", state.tasks.size.toString())
                            TaskFilter.COMPLETED -> s("tasks_empty_completed")
                            TaskFilter.MINE -> s("tasks_empty_mine")
                            TaskFilter.ALL -> s("tasks_empty_all")
                        },
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            groups.forEach { group ->
                val isCollapsed = collapsedGroups[group.dateKey]
                    ?: (group.dateKey == "completed_today" || group.dateKey == "completed_other")

                stickyHeader(key = "header_${group.dateKey}") {
                    GroupHeader(
                        label = group.label,
                        count = group.items.size,
                        isOverdue = group.isOverdue,
                        isDueSoon = group.isDueSoon,
                        isNoDate = group.isNoDate,
                        isCollapsed = isCollapsed,
                        onToggle = { collapsedGroups[group.dateKey] = !isCollapsed }
                    )
                }

                item(key = "spacer_${group.dateKey}") {
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (!isCollapsed) {
                    items(
                        items = group.items,
                        // Sin sufijo de grupo: misma key al completar una tarea permite que
                        // Compose la reconozca como el mismo ítem moviéndose de grupo (en vez
                        // de destruirla y recrearla), habilitando animateItem() para el traslado.
                        key = { "task_${it.task.id}" }
                    ) { item ->
                        // `onClick`/`onComplete` memoizados: sin esto, cada TaskCard visible
                        // recibía una lambda con identidad nueva en CADA recomposición de este
                        // bloque (p.ej. al expandir/colapsar OTRO grupo, o al fijar
                        // loadingTaskIds de OTRA tarea), anulando el skip de recomposición que
                        // el resto de la memoización de esta pantalla ya habilita — panel v4,
                        // Rendimiento hallazgo MEDIO-ALTO.
                        val onClick = remember(item.task.id) { { onTaskClick(item.task) } }
                        val canComplete = item.isDueToday && !item.isCompletedToday
                        val onComplete = remember(item.task.id, canComplete) {
                            if (canComplete) {
                                {
                                    loadingTaskIds[item.task.id] = true
                                    onCompleteTask(item.task)
                                }
                            } else null
                        }
                        TaskCard(
                            item = item,
                            assignments = assignmentsByTask[item.task.id] ?: emptyList(),
                            memberMap = memberMap,
                            isLoading = loadingTaskIds[item.task.id] == true,
                            hasError = actionErrorSignal != null,
                            onClick = onClick,
                            onComplete = onComplete,
                            modifier = if (reduceMotion) Modifier else Modifier.animateItem()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        // Spacer at bottom
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ────────────────────────────────────────────────────────────
//  TaskCard – shows a task with quick-complete
// ────────────────────────────────────────────────────────────

@Composable
private fun TaskCard(
    item: TaskWithStatus,
    assignments: List<TaskAssignmentResponse>,
    memberMap: Map<String, MemberResponse>,
    isLoading: Boolean,
    hasError: Boolean,
    onClick: () -> Unit,
    onComplete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val task = item.task
    val pendingCount = assignments.count { it.status == "assigned" }
    val completedCount = assignments.count { it.status == "completed" }
    val totalAssigned = assignments.size
    // isCompleted (no isCompletedToday): esta card también se usa para tareas
    // completadas en días anteriores (grupo "Completadas", ver
    // [TaskWithStatus.isCompleted]) — deben verse igual de "hechas"
    // (tachado, sin botón "Hecho") que las completadas hoy.
    val isDone = item.isCompleted
    val reduceMotion = shouldReduceMotion()
    // El bounce del check y el confeti son "efectos visuales" (categoría
    // fx_effects) a efectos del modo simple — la salida de la card
    // (scaleX/Y + alpha, más abajo) es una transición básica de lista y no
    // se gatea aquí (fuera del alcance de "gating mínimo" de la fase 0).
    val effectsOn = effectsEnabled(EffectCategory.EFFECTS)
    val appSettings = LocalAppSettings.current
    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

    // Al pulsar "Hecho" primero se anima la card (fade out + scale down) y solo
    // entonces se dispara la finalización real, para que la tarea no desaparezca
    // bruscamente al pasar al grupo "Completadas hoy".
    var isCompleting by remember(task.id) { mutableStateOf(false) }
    LaunchedEffect(isDone) {
        // La card puede reutilizarse (misma key) al moverse de grupo — una vez el
        // backend confirma el completado, se libera la animación de salida.
        if (isDone) isCompleting = false
    }
    LaunchedEffect(hasError) {
        // Sin esto, un fallo de red al completar dejaba la card en animación de
        // salida permanente (cardAlpha=0f, clickable deshabilitado) y ningún
        // camino la recuperaba, porque solo `isDone` (éxito) reseteaba isCompleting.
        if (hasError) isCompleting = false
    }
    LaunchedEffect(isCompleting) {
        if (isCompleting) {
            if (!reduceMotion) delay(260)
            onComplete?.invoke()
        }
    }
    val cardScale by animateFloatAsState(
        targetValue = if (isCompleting) 0.92f else 1f,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 260),
        label = "taskCardScale"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (isCompleting) 0f else 1f,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 260),
        label = "taskCardAlpha"
    )

    Box(modifier = modifier) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
                alpha = cardAlpha
            }
            // role = Button: semántica estructurada para TalkBack/VoiceOver
            // (panel v7 2026-09-10, Exp. 3, IMPORTANTE).
            // customActions: expone "Hecho" como acción accesible del propio
            // card en vez de un botón anidado dentro del área clicable (panel
            // kanban 2026-09-10, [Accesibilidad] botón anidado); el Button
            // interno se oculta del árbol de semántica con clearAndSetSemantics.
            .then(
                if (!isDone && onComplete != null && !isLoading && !isCompleting) {
                    Modifier.semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(s("task_detail_mark_done")) {
                                isCompleting = true
                                true
                            }
                        )
                    }
                } else Modifier
            )
            .clickable(enabled = !isCompleting, role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isDone) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDone) 0.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Title + Complete button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                )

                if (!isDone && onComplete != null) {
                    if (isCompleting) {
                        AnimatedCheckmark(reduceMotion = reduceMotion || !effectsOn)
                    } else {
                        Button(
                            onClick = { isCompleting = true },
                            enabled = !isLoading,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            // Oculto para TalkBack/VoiceOver: la acción ya está expuesta
                            // como customAction del Card (evita botón anidado dentro
                            // del área clicable). Sigue funcionando al tacto.
                            modifier = Modifier.clearAndSetSemantics {}
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(s("task_detail_mark_done"), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                } else if (isDone) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "✅",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // Due date badge below title — friendly format (Hoy/Mañana/day/date)
            if (task.dueDate > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "📅 ${formatFriendlyDate(task.dueDate, appSettings.currentLanguage)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.isOverdue) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Description
            if (task.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Metadata row: puntos + progreso de subtareas + frecuencia/etiquetas.
            // Frecuencia/etiquetas con menor contraste que la fila de vencimiento
            // (996-1063), que es la relevante para decidir qué hacer hoy (revisión
            // UX 2026-09-17, L1).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Points badge — degradado sutil solo si la tarea ya está
                // completada (puntos YA ganados, no solo el valor que vale
                // la tarea): informe delight #10, aprobado.
                // Panel v16 (2026-09-24), hallazgo estética: "⭐ N" es el
                // formato usado en el resto de la app (perfil, ranking,
                // recompensas, calendario, detalle de tarea); esta era la
                // única pantalla con "N pts" en texto plano.
                PointsBadge(text = "⭐ ${task.points}", gradient = isDone)

                // Progreso de subtareas — antes invisible fuera del detalle de la
                // tarea (revisión UX 2026-09-17, L2/H1).
                if (task.subtasks.isNotEmpty()) {
                    val completedSubtasks = task.subtasks.count { it.completed }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = s("task_list_subtask_badge")
                                .replace("%1", completedSubtasks.toString())
                                .replace("%2", task.subtasks.size.toString()),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }

                // Frecuencia + etiquetas, agrupadas en texto plano de menor contraste
                val freqLabel = when (task.frequency) {
                    "daily" -> s("task_list_freq_daily")
                    "weekly" -> s("task_list_freq_weekly")
                    "monthly" -> if (task.recurrenceDay != null) s("task_list_freq_monthly_day").replace("%d", task.recurrenceDay.toString()) else s("task_list_freq_monthly")
                    else -> s("task_list_freq_once")
                }
                val metaLabel = (listOf(freqLabel) + task.tags.take(2)).joinToString(" · ")
                Text(
                    text = metaLabel,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Due date / completion status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Due date or completion info
                if (isDone && task.lastCompletedDate != null) {
                    Text(
                        text = "✅ ${formatDeadline(task.lastCompletedDate)}",
                        style = MaterialTheme.typography.bodySmall,
                        // onSuccessContainer (no success): este texto va sobre la card
                        // surfaceVariant de una tarea completada — en Naturaleza claro,
                        // success cae por debajo de 4.5:1 contra ese fondo concreto.
                        color = MaterialTheme.semanticColors.onSuccessContainer
                    )
                } else if (task.dueDate > 0) {
                    val deadlineText = formatDeadline(task.dueDate)
                    Text(
                        text = "⏰ $deadlineText",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isOverdue) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = s("task_list_no_deadline"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Assignment status
                if (totalAssigned > 0) {
                    val allDone = completedCount == totalAssigned
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (allDone) MaterialTheme.semanticColors.successContainer else MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "✅ $completedCount/$totalAssigned",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (allDone) MaterialTheme.semanticColors.onSuccessContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    // Show rotation assignee for today
                    val rotationMemberId = getTodayAssigneeMemberId(task)
                    if (rotationMemberId != null) {
                        val rotationMember = memberMap[rotationMemberId]
                        if (rotationMember != null) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = "🧑 ${rotationMember.displayName}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }

        // Confeti mínimo mientras la card se anima de salida al completar.
        if (isCompleting && !reduceMotion && effectsOn) {
            ConfettiOverlay(modifier = Modifier.matchParentSize())
        }
    }
}

// ────────────────────────────────────────────────────────────
//  GroupHeader – sticky header for each day group
// ────────────────────────────────────────────────────────────

@Composable
private fun GroupHeader(
    label: String,
    count: Int,
    isOverdue: Boolean,
    isDueSoon: Boolean = false,
    isNoDate: Boolean,
    isCollapsed: Boolean,
    onToggle: () -> Unit
) {
    val semantic = MaterialTheme.semanticColors
    // isOverdue es un estado (vencida) → semanticColors.error, no la marca coral;
    // el resto ("toca hoy/próximo") usa la marca teal (primaryContainer).
    val backgroundColor = when {
        isOverdue -> MaterialTheme.colorScheme.errorContainer
        isDueSoon -> semantic.warningContainer
        isNoDate -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when {
        isOverdue -> MaterialTheme.colorScheme.onErrorContainer
        isDueSoon -> semantic.onWarningContainer
        isNoDate -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val dotColor = when {
        isOverdue -> MaterialTheme.colorScheme.error
        isDueSoon -> semantic.warning
        isNoDate -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = backgroundColor,
        shadowElevation = 1.dp
    ) {
        ExpandableSectionHeader(
            expanded = !isCollapsed,
            onToggle = onToggle,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            chevronTint = contentColor,
            chevronSize = 20.dp
        ) {
            // Colored dot indicator
            StatusDot(color = dotColor, size = 8.dp)

            Spacer(modifier = Modifier.width(10.dp))

            // Label + count
            Text(
                text = "$label ($count)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )

            // Overdue badge
            if (isOverdue) {
                PointsBadge(text = "!")
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
//  SearchBar
// ────────────────────────────────────────────────────────────

@Composable
private fun SearchBar(
    query: String,
    s: (String) -> String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        label = { Text(s("task_list_search_label")) },
        placeholder = { Text(s("task_list_search_placeholder")) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = s("task_list_search_content_desc"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = s("task_list_clear_search_content_desc"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        singleLine = true,
        colors = taskHubTextFieldColors(),
        shape = MaterialTheme.shapes.medium
    )
}

// ────────────────────────────────────────────────────────────
//  FilterChipsRow
// ────────────────────────────────────────────────────────────

@Composable
private fun FilterChipsRow(
    currentFilter: TaskFilter,
    currentSort: TaskSort,
    tagFilter: String?,
    allTags: List<String>,
    s: (String) -> String,
    searchExpanded: Boolean,
    hasActiveSearch: Boolean,
    onFilterChange: (TaskFilter) -> Unit,
    onSortChange: (TaskSort) -> Unit,
    onTagFilterChange: (String?) -> Unit,
    onToggleSearch: () -> Unit
) {
    var expandedSort by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Status filter chips. Los 4 chips eran bloques idénticos salvo el
        // filtro/etiqueta — unificados en un bucle sobre una lista, con los
        // colores tomados del tema (antes Teal100/Teal900 fijos, iguales en
        // los 3 temas) en vez de repetidos por chip.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val statusFilterColors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
            val statusFilters = listOf(
                TaskFilter.PENDING to s("task_list_filter_pending"),
                TaskFilter.COMPLETED to s("task_list_filter_completed"),
                TaskFilter.MINE to s("task_list_filter_mine"),
                TaskFilter.ALL to s("task_list_filter_all")
            )
            statusFilters.forEach { (filter, label) ->
                FilterChip(
                    selected = currentFilter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(label) },
                    colors = statusFilterColors,
                    leadingIcon = filterChipCheckIcon(currentFilter == filter)
                )
            }
        }

        // Tag filter + Sort
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Búsqueda colapsada a icono (L4): antes la SearchBar se pintaba
            // siempre a tamaño completo aunque se usara ocasionalmente.
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = s(if (searchExpanded) "common_collapse" else "task_list_search_content_desc"),
                    tint = if (searchExpanded || hasActiveSearch) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (allTags.isNotEmpty()) {
                // Tag filter dropdown
                var tagExpanded by remember { mutableStateOf(false) }
                Box {
                    FilterChip(
                        selected = tagFilter != null,
                        onClick = { tagExpanded = true },
                        label = { Text(tagFilter ?: s("create_task_section_tags")) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ),
                        leadingIcon = filterChipCheckIcon(tagFilter != null)
                    )
                    DropdownMenu(
                        expanded = tagExpanded,
                        onDismissRequest = { tagExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(s("task_list_filter_all")) },
                            onClick = {
                                onTagFilterChange(null)
                                tagExpanded = false
                            }
                        )
                        allTags.forEach { tag ->
                            DropdownMenuItem(
                                text = { Text(tag) },
                                onClick = {
                                    onTagFilterChange(tag)
                                    tagExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Sort button. El icono es solo emoji (sin texto visible por espacio en
            // la fila) — contentDescription expone el criterio activo en claro para
            // TalkBack/VoiceOver, que si no leerían el nombre unicode del glifo.
            val currentSortLabel = when (currentSort) {
                TaskSort.DEADLINE_ASC -> s("task_list_sort_deadline_asc")
                TaskSort.DEADLINE_DESC -> s("task_list_sort_deadline_desc")
                TaskSort.POINTS_DESC -> s("task_list_sort_points")
                TaskSort.CREATED_DESC -> s("task_list_sort_recent")
            }
            Box {
                TextButton(
                    onClick = { expandedSort = true },
                    modifier = Modifier.semantics {
                        contentDescription = "${s("task_list_sort_label")}: $currentSortLabel"
                    }
                ) {
                    Text(
                        text = when (currentSort) {
                            TaskSort.DEADLINE_ASC -> "📅 ↑"
                            TaskSort.DEADLINE_DESC -> "📅 ↓"
                            TaskSort.POINTS_DESC -> "⭐"
                            TaskSort.CREATED_DESC -> "🕐"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = currentSortLabel,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                DropdownMenu(
                    expanded = expandedSort,
                    onDismissRequest = { expandedSort = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(s("task_list_sort_deadline_asc")) },
                        onClick = {
                            onSortChange(TaskSort.DEADLINE_ASC)
                            expandedSort = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(s("task_list_sort_deadline_desc")) },
                        onClick = {
                            onSortChange(TaskSort.DEADLINE_DESC)
                            expandedSort = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(s("task_list_sort_points")) },
                        onClick = {
                            onSortChange(TaskSort.POINTS_DESC)
                            expandedSort = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(s("task_list_sort_recent")) },
                        onClick = {
                            onSortChange(TaskSort.CREATED_DESC)
                            expandedSort = false
                        }
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
//  Helpers
// ────────────────────────────────────────────────────────────

private fun formatDeadline(epochMillis: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val month = local.monthNumber.toString().padStart(2, '0')
    val day = local.dayOfMonth.toString().padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val min = local.minute.toString().padStart(2, '0')
    return "$day/$month ${hour}:${min}"
}

/**
 * Returns the member ID responsible for this task today based on assignmentRotation.
 * Returns null if no rotation is defined.
 */
private fun getTodayAssigneeMemberId(task: TaskResponse): String? {
    if (task.assignmentRotation.isEmpty()) return null

    val now = Clock.System.now()
    val tz = TimeZone.currentSystemDefault()
    val today = now.toLocalDateTime(tz).date
    val todayDow = today.dayOfWeek.ordinal + 1 // 1=Monday..7=Sunday

    val slot = task.assignmentRotation.find { it.dayOfWeek == todayDow }
    return slot?.memberId
}
