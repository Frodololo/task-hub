/**
 * Pantalla de detalle de una tarea: info, checklist de subtareas,
 * asignaciones pendientes/completadas, estado de sincronización con Google
 * Calendar y comentarios. Usa [org.taskhub.ui.models.TaskScreenModel] para
 * completar/reasignar/borrar y [org.taskhub.ui.models.TaskCommentsScreenModel]
 * para los comentarios. Se navega aquí desde las listas de tareas
 * ([TaskListScreen], [CalendarScreen], [HomeScreen]); desde aquí se navega a
 * [EditTaskScreen].
 */
package org.taskhub.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.taskhub.network.TaskListRules
import org.taskhub.network.models.TaskAssignmentResponse
import org.taskhub.network.models.MemberResponse
import org.taskhub.ui.models.*
import org.taskhub.ui.components.AchievementToast
import org.taskhub.ui.components.AnimatedCheckmark
import org.taskhub.ui.components.AnimatedCounter
import org.taskhub.ui.components.BadgeTone
import org.taskhub.ui.components.ConfettiOverlay
import org.taskhub.ui.components.DestructiveConfirmDialog
import org.taskhub.ui.components.EffectCategory
import org.taskhub.ui.components.ExpandableSectionHeader
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.StatChip
import org.taskhub.ui.components.StatusDot
import org.taskhub.ui.components.TaskHubTopBar
import org.taskhub.ui.components.effectsEnabled
import org.taskhub.ui.components.rememberHouseholdName
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.components.taskHubTextFieldColors
import org.taskhub.ui.components.UserAvatar
import org.taskhub.ui.components.defaultRoleEmoji
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.theme.*

// ────────────────────────────────────────────────────────────
//  TaskDetailScreen
// ────────────────────────────────────────────────────────────

/**
 * Pantalla de detalle de la tarea [taskId] dentro del hogar [householdId].
 * Determina localmente si el usuario actual es admin/owner (necesario para
 * habilitar reasignar quién completó la tarea, ya que eso mueve puntos entre
 * miembros — restricción reflejada también en `firestore.rules`) y navega
 * hacia atrás automáticamente al completar/borrar la tarea con éxito.
 */
data class TaskDetailScreen(
    val householdId: String,
    val taskId: String
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = koinScreenModel<TaskScreenModel>()
        val commentsModel = koinScreenModel<TaskCommentsScreenModel>()
        // Solo para la sección "Puntuación" (recompensas disponibles) — carga
        // perezosa bajo demanda, ver LaunchedEffect(pointsExpanded) en
        // TaskDetailContent (diseño v2, fase 2, decisión 7).
        val memberModel = koinScreenModel<MemberScreenModel>()
        val authManager = koinInject<GoogleAuthManager>()
        val coroutineScope = rememberCoroutineScope()
        val appSettings = LocalAppSettings.current
        val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }
        val detailState by model.detailState.collectAsState()
        val actionState by model.actionState.collectAsState()
        val reassignState by model.reassignState.collectAsState()
        val currentMemberId by model.currentMemberId.collectAsState()
        val calendarActionState by model.calendarActionState.collectAsState()
        val myAssignment by model.myAssignment.collectAsState()
        val commentsState by commentsModel.commentsState.collectAsState()
        val newCommentText by commentsModel.newCommentText.collectAsState()
        val sendCommentError by commentsModel.sendCommentError.collectAsState()
        val rewardState by memberModel.rewardState.collectAsState()
        val newlyUnlockedAchievement by model.newlyUnlockedAchievement.collectAsState()

        val householdModel = koinScreenModel<HouseholdScreenModel>()
        val householdName = rememberHouseholdName(householdId, householdModel)

        var isLinkingCalendar by remember { mutableStateOf(false) }
        var showDeleteDialog by remember { mutableStateOf(false) }

        // El owner del hogar (quien lo creó) es siempre "de confianza" para
        // reasignar completados, igual que isTrusted(hid) en firestore.rules,
        // independientemente de qué rol se auto-asignara al crear su propio
        // perfil — ver el mismo fix en HouseholdScreen.kt.
        var isOwner by remember { mutableStateOf(false) }
        LaunchedEffect(householdId) {
            // best-effort: si falla, isOwner se queda en false (solo se pierde
            // el atajo de "owner", el rol "admin" normal sigue igual) — ver
            // FirestoreRepository.isHouseholdOwner.
            isOwner = model.isHouseholdOwner(householdId)
        }

        LaunchedEffect(taskId) {
            model.resetActionState()
            model.loadTaskDetail(householdId, taskId)
            commentsModel.loadComments(householdId, taskId)
        }

        // Delete confirmation dialog
        if (showDeleteDialog) {
            DestructiveConfirmDialog(
                title = s("task_detail_delete_title"),
                text = s("task_detail_delete_confirm"),
                s = s,
                onDismiss = { showDeleteDialog = false },
                onConfirm = {
                    showDeleteDialog = false
                    model.deleteTask(householdId, taskId)
                }
            )
        }

        // Watch for complete/delete success and navigate back. El pop() se
        // retrasa 260ms (0ms si reduce-motion) para dar tiempo a ver el check
        // + confeti del botón "Hecho" antes de salir de la pantalla — sin
        // este retraso la animación nunca llegaría a verse (delight fase 2,
        // §2.5).
        val reduceMotionNav = shouldReduceMotion()
        LaunchedEffect(actionState) {
            if (actionState is TaskActionState.Success && !showDeleteDialog) {
                // Refresh list state before going back so the
                // completed instance disappears from the pending list
                model.loadTasks(householdId)
                if (!reduceMotionNav) delay(260)
                navigator.pop()
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                TaskHubTopBar(
                    title = s("task_detail_title"),
                    subtitle = householdName,
                    onBack = { navigator.pop() },
                    actions = {
                        // Panel v16 (2026-09-24, hallazgo I12): sin `enabled`,
                        // ambos iconos seguían pulsables mientras se borraba
                        // la tarea de forma asíncrona — tocar "Editar" abría
                        // una tarea a punto de desaparecer, y un segundo toque
                        // en "Borrar" reintentaba sobre un documento ya
                        // borrado, en ambos casos con un error de red confuso
                        // en vez de bloquear la interacción de origen.
                        IconButton(
                            enabled = actionState !is TaskActionState.Loading,
                            onClick = {
                                val state = detailState
                                if (state is TaskDetailUiState.Success) {
                                    navigator.push(EditTaskScreen(householdId, state.task))
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = s("task_detail_edit_content_desc"))
                        }
                        IconButton(
                            enabled = actionState !is TaskActionState.Loading,
                            onClick = { showDeleteDialog = true }
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = s("common_delete"))
                        }
                    }
                )

                when (val state = detailState) {
                    is TaskDetailUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    is TaskDetailUiState.Success -> {
                        val memberMap = state.members.associateBy { it.id }
                        val isGoogleLinked = model.hasGoogleLinked()
                        val isCalendarSyncEnabled = model.isCalendarSyncEnabled()
                        // Reasignar quién hizo una tarea mueve puntos entre miembros:
                        // gateado a admin/owner tanto aquí como en firestore.rules
                        // (ver el `update` de households/{hid}/tasks/{tid}).
                        val isAdmin = memberMap[currentMemberId]?.role == "admin" || isOwner
                        TaskDetailContent(
                            task = state.task,
                            assignments = state.assignments,
                            memberMap = memberMap,
                            currentMemberId = currentMemberId,
                            actionState = actionState,
                            reassignState = reassignState,
                            isAdmin = isAdmin,
                            commentsState = commentsState,
                            newCommentText = newCommentText,
                            sendCommentError = sendCommentError,
                            onDismissSendCommentError = { commentsModel.clearSendCommentError() },
                            s = s,
                            myAssignment = myAssignment,
                            rewardState = rewardState,
                            onExpandPoints = { memberModel.loadRewards(householdId) },
                            onViewAllRewards = {
                                navigator.push(ExploreScreen(householdId, currentMemberId ?: "", initialTab = 2))
                            },
                            isGoogleLinked = isGoogleLinked,
                            isCalendarSyncEnabled = isCalendarSyncEnabled,
                            isLinkingCalendar = isLinkingCalendar,
                            calendarActionState = calendarActionState,
                            onCommentTextChange = { commentsModel.setNewCommentText(it) },
                            onAddComment = {
                                commentsModel.addComment(householdId, taskId, currentMemberId)
                            },
                            onCompleteTask = {
                                model.completeTask(
                                    householdId = householdId,
                                    taskId = taskId
                                )
                            },
                            onComplete = { assignmentId, assignment ->
                                model.completeAssignment(
                                    householdId = householdId,
                                    taskId = taskId,
                                    task = state.task,
                                    assignmentId = assignmentId,
                                    assignment = assignment
                                )
                            },
                            onChangeCompletedBy = { memberId ->
                                model.reassignTaskCompletion(
                                    householdId = householdId,
                                    taskId = taskId,
                                    taskPoints = state.task.points,
                                    newMemberId = memberId
                                )
                            },
                            onToggleSubtask = { subtaskId ->
                                model.toggleSubtask(householdId, taskId, subtaskId)
                            },
                            onSyncCalendarNow = {
                                model.syncTaskToCalendarNow(householdId, state.task)
                            },
                            onLinkCalendar = {
                                isLinkingCalendar = true
                                coroutineScope.launch {
                                    val linked = authManager.linkCalendar()
                                    isLinkingCalendar = false
                                    if (linked) {
                                        model.setCalendarSyncEnabled(true)
                                    } else {
                                        // Antes fallaba en silencio: solo se apagaba el
                                        // spinner y volvía a "No vinculado" sin ninguna
                                        // pista de si el usuario canceló o hubo un fallo
                                        // real — reutiliza la misma tarjeta de error que
                                        // ya usa "Sincronizar ahora".
                                        model.setCalendarLinkError(s("calendar_link_error"))
                                    }
                                    model.loadTaskDetail(householdId, taskId)
                                }
                            },
                            onEnableCalendarSync = {
                                model.setCalendarSyncEnabled(true)
                                model.loadTaskDetail(householdId, taskId)
                            }
                        )
                    }

                    is TaskDetailUiState.Error -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = s("error_icon_content_desc"),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = state.message,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Panel v17 (hallazgo MENOR de UX): si el
                                    // error persiste (p.ej. tarea borrada por
                                    // otro miembro, o deep link a un ID
                                    // inexistente), "Reintentar" vuelve a
                                    // fallar indefinidamente — se añade una
                                    // salida explícita en el propio cuerpo del
                                    // error, no solo la flecha del topbar.
                                    OutlinedButton(onClick = { navigator.pop() }) {
                                        Text(s("tasks_back"))
                                    }
                                    Button(onClick = { model.loadTaskDetail(householdId, taskId) }) {
                                        Text(s("tasks_retry"))
                                    }
                                }
                            }
                        }
                    }

                    is TaskDetailUiState.Idle -> {}
                }
            }

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
//  TaskDetailContent (simplified — no instances)
// ────────────────────────────────────────────────────────────

/**
 * Cuerpo de la pantalla de detalle (contenido puro, sin acceder a
 * ScreenModels): tarjeta de info de la tarea, estado de completado de hoy,
 * sincronización con Calendar, checklist de subtareas, listas de
 * asignaciones pendientes/completadas y sección de comentarios. Todas las
 * acciones se exponen como callbacks para que [TaskDetailScreen] las conecte
 * con los ScreenModels correspondientes.
 */
@Composable
private fun TaskDetailContent(
    task: org.taskhub.network.models.TaskResponse,
    assignments: List<TaskAssignmentResponse>,
    memberMap: Map<String, MemberResponse>,
    currentMemberId: String? = null,
    actionState: TaskActionState,
    reassignState: TaskActionState = TaskActionState.Idle,
    isAdmin: Boolean = false,
    commentsState: CommentsUiState,
    newCommentText: String,
    sendCommentError: String? = null,
    onDismissSendCommentError: () -> Unit = {},
    s: (String) -> String = { it },
    myAssignment: TaskAssignmentResponse? = null,
    rewardState: RewardUiState = RewardUiState.Idle,
    onExpandPoints: () -> Unit = {},
    onViewAllRewards: () -> Unit = {},
    isGoogleLinked: Boolean = false,
    isCalendarSyncEnabled: Boolean = false,
    isLinkingCalendar: Boolean = false,
    calendarActionState: TaskScreenModel.CalendarActionState = TaskScreenModel.CalendarActionState.Idle,
    onCommentTextChange: (String) -> Unit,
    onAddComment: () -> Unit,
    onCompleteTask: () -> Unit,
    onComplete: (String, TaskAssignmentResponse) -> Unit,
    onChangeCompletedBy: (String) -> Unit,
    onToggleSubtask: (String) -> Unit,
    onSyncCalendarNow: () -> Unit = {},
    onLinkCalendar: () -> Unit = {},
    onEnableCalendarSync: () -> Unit = {}
) {
    val now = Clock.System.now().toEpochMilliseconds()
    val pendingAssignments = assignments.filter { it.status == "assigned" }
    val completedAssignments = assignments.filter { it.status == "completed" }

    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(tz).date
    val isCompletedToday = task.lastCompletedDate != null && run {
        val lcdDate = kotlinx.datetime.Instant.fromEpochMilliseconds(task.lastCompletedDate!!).toLocalDateTime(tz).date
        lcdDate == today
    }

    // Celebración del botón "Hecho" a nivel de tarea (delight fase 2, §2.5):
    // mismo patrón isCompleting que TaskListScreen.TaskCard — al pulsar se
    // muestra AnimatedCheckmark + ConfettiOverlay y la llamada real a
    // onCompleteTask() se retrasa 260ms (0ms si reduce-motion) para que la
    // animación llegue a verse.
    val reduceMotion = shouldReduceMotion()
    val effectsOn = effectsEnabled(EffectCategory.EFFECTS)
    var isCompletingTask by remember(task.id) { mutableStateOf(false) }
    LaunchedEffect(actionState) {
        if (actionState is TaskActionState.Error) isCompletingTask = false
    }
    LaunchedEffect(isCompletingTask) {
        if (isCompletingTask) {
            if (!reduceMotion) delay(260)
            onCompleteTask()
        }
    }

    // Estado para el diálogo "¿quién ha hecho la tarea?" (editar quién la completó).
    var showChangeWhoDialog by remember { mutableStateOf(false) }
    var selectedCompleterId by remember { mutableStateOf(task.completedBy) }
    // "Otros" agrupa Calendar + Asignaciones completadas + Comentarios: contenido
    // secundario/infrecuente, plegado por defecto (diseño v2, fase 1).
    var otrosExpanded by remember { mutableStateOf(false) }

    // "Puntuación": plegada por defecto, con teaser en la cabecera. Al
    // expandirla por primera vez se dispara la carga de recompensas (coste de
    // red aceptado solo para quien realmente abre la sección — diseño v2,
    // fase 2, decisión 7).
    var pointsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(pointsExpanded) {
        if (pointsExpanded) onExpandPoints()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Action state feedback
        if (actionState is TaskActionState.Error) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = s("error_icon_content_desc"),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = actionState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // ── Task info card ──
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Title
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    // Description
                    if (task.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = task.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Info rows
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatChip(label = s("public_profile_stat_points"), value = "${task.points} ⭐", tone = BadgeTone.Teal)
                        StatChip(
                            label = s("task_detail_frequency_label"),
                            value = when (task.frequency) {
                                "daily" -> s("recurrence_daily")
                                "weekly" -> s("recurrence_weekly")
                                "monthly" -> s("recurrence_monthly")
                                else -> s("recurrence_once")
                            },
                            tone = BadgeTone.Teal
                        )
                    }

                    // Recurrencia (días / día del mes) — mismo tratamiento de
                    // sub-bloque con label que "Penalización", en vez de texto
                    // suelto con emoji 🔄 (revisión UX 2026-09-17, D1).
                    if (task.recurrenceDays.isNotEmpty() || (task.frequency == "monthly" && task.recurrenceDay != null)) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = s("task_detail_recurrence_section"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (task.recurrenceDays.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val daysStr = task.recurrenceDays.joinToString(", ") { day ->
                                when (day) {
                                    1 -> s("recurrence_day_monday")
                                    2 -> s("recurrence_day_tuesday")
                                    3 -> s("recurrence_day_wednesday")
                                    4 -> s("recurrence_day_thursday")
                                    5 -> s("recurrence_day_friday")
                                    6 -> s("recurrence_day_saturday")
                                    7 -> s("recurrence_day_sunday")
                                    else -> "?"
                                }
                            }
                            Text(
                                text = daysStr,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        if (task.frequency == "monthly" && task.recurrenceDay != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = s("task_detail_monthly_recurrence").replace("%d", task.recurrenceDay.toString()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // Tags
                    if (task.tags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            task.tags.forEach { tag ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(
                                        text = tag,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Subtasks checklist ──
        // Movido justo después de la tarjeta de info (antes iba tras el estado
        // de sincronización de calendario): es contenido accionable propio de
        // la tarea, no debe quedar bajo un estado auxiliar de integración
        // externa (revisión UX 2026-09-17, D3).
        if (task.subtasks.isNotEmpty()) {
            item {
                val completedCount = task.subtasks.count { it.completed }
                Text(
                    text = s("task_detail_checklist_header")
                        .replace("%1", completedCount.toString())
                        .replace("%2", task.subtasks.size.toString()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = s("create_task_section_checklist_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(task.subtasks, key = { it.id }) { st ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = st.completed,
                        onCheckedChange = { onToggleSubtask(st.id) },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                    )
                    Text(
                        text = st.text,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (st.completed) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (st.completed) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // ── Puntuación (NUEVA, diseño v2 fase 2) ──
        // Plegada con teaser en la cabecera (visible aunque esté plegada);
        // al expandir explica cómo se ganan los puntos, traslada aquí la
        // penalización (antes en la tarjeta de info) y muestra el saldo del
        // usuario actual (sin petición nueva) + hasta 3 recompensas
        // canjeables (carga perezosa, ver LaunchedEffect(pointsExpanded)).
        item {
            ExpandableSectionHeader(
                expanded = pointsExpanded,
                onToggle = { pointsExpanded = !pointsExpanded },
                chevronTint = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = "${s("task_detail_points_section")} · ${task.points} ${s("transfer_points_suffix")}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (pointsExpanded) {
            item {
                Text(
                    text = s("task_detail_points_how_it_works"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = s("task_detail_points_earn_desc").replace("%d", task.points.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Penalización por retraso — trasladada aquí desde la tarjeta
                // de info (mismas claves i18n, ninguna nueva).
                if (task.penaltyMode != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = s("create_task_penalty_section"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    val penaltyDesc = when (task.penaltyMode) {
                        "fixed" -> s("task_detail_penalty_fixed_desc")
                            .replace("%1", task.penaltyValue.toString())
                            .replace("%2", intervalLabel(task.penaltyInterval, s))
                        "percentage" -> s("task_detail_penalty_percent_desc")
                            .replace("%1", task.penaltyValue.toString())
                            .replace("%2", intervalLabel(task.penaltyInterval, s))
                        else -> ""
                    }
                    Text(
                        text = penaltyDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (task.penaltyMax > 0) {
                        Text(
                            text = s("task_detail_penalty_max_desc").replace("%d", task.penaltyMax.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Tu saldo (memberMap ya cargado — sin petición nueva) ──
            item {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = s("task_detail_points_your_balance"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                val memberPoints = memberMap[currentMemberId]?.totalPoints ?: 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⭐ ",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AnimatedCounter(
                        value = memberPoints,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " ${s("transfer_points_suffix")}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // ── Recompensas disponibles (carga perezosa vía onExpandPoints) ──
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = s("task_detail_points_rewards_header"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            when (val rState = rewardState) {
                is RewardUiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                is RewardUiState.Success -> {
                    val memberPoints = memberMap[currentMemberId]?.totalPoints ?: 0
                    if (rState.rewards.isEmpty()) {
                        item {
                            Text(
                                text = s("task_detail_points_rewards_empty"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val topRewards = rState.rewards.sortedBy { it.cost }.take(3)
                        items(topRewards, key = { it.id }) { reward ->
                            val canAfford = memberPoints >= reward.cost
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .alpha(if (canAfford) 1f else 0.5f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = reward.icon, style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = reward.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "⭐ ${reward.cost}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                is RewardUiState.Error -> {
                    item {
                        Text(
                            text = "⚠️ ${rState.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                is RewardUiState.Idle -> {}
            }

            item {
                TextButton(onClick = onViewAllRewards) {
                    Text(s("task_detail_points_view_all_rewards"))
                }
            }
        }

        // ── Completion status ──
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isCompletedToday) s("task_detail_completed_today") else s("task_detail_status_pending"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    // Panel v18 (accesibilidad): mismo fallo de contraste ya corregido en el
                    // badge "obligatoria" de esta pantalla (línea ~1514) — `colorScheme.tertiary`
                    // crudo fallaba WCAG AA (4.19:1 en Naturaleza claro).
                    color = if (isCompletedToday) MaterialTheme.colorScheme.primary else MaterialTheme.semanticColors.warning
                )
                // D4: con asignaciones, completar pasa a ser siempre "completar mi
                // asignación" vía AssignmentCard (sección Pendientes) — el botón
                // "Hecho" de nivel-tarea desaparece de aquí para no ofrecer dos
                // caminos de completar sin conexión visual entre sí.
                if (!isCompletedToday && assignments.isEmpty()) {
                    Box {
                        if (isCompletingTask) {
                            AnimatedCheckmark(reduceMotion = reduceMotion || !effectsOn)
                        } else {
                            Button(
                                onClick = { isCompletingTask = true },
                                enabled = actionState !is TaskActionState.Loading,
                            ) {
                                if (actionState is TaskActionState.Loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(s("task_detail_mark_done"))
                                }
                            }
                        }
                        if (isCompletingTask && !reduceMotion && effectsOn) {
                            ConfettiOverlay(modifier = Modifier.matchParentSize())
                        }
                    }
                }
            }
        }

        if (isCompletedToday) {
            item {
                val completer = task.completedBy?.let { memberMap[it] }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = s("task_detail_done_by").replace("%s", completer?.displayName ?: s("task_detail_someone_in_space")),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = s("task_detail_completed_at").replace("%s", formatDateTime(task.lastCompletedDate!!)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (reassignState is TaskActionState.Error) {
                            Text(
                                text = "⚠️ ${reassignState.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    // Reasignar quién completó la tarea mueve puntos entre
                    // miembros: solo admin/owner (ver firestore.rules v4).
                    if (isAdmin) {
                        TextButton(onClick = {
                            selectedCompleterId = task.completedBy
                            showChangeWhoDialog = true
                        }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = s("task_detail_change_completer_desc"),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(s("task_detail_change_btn"))
                        }
                    }
                }
            }
        }

        // ── Pending assignments ──
        // Sin cabecera de conteo cuando no hay pendientes: la tarjeta de estado
        // vacío de abajo ya comunica lo mismo, evita el mensaje duplicado
        // "Pendientes (0)" + "Sin asignaciones" (revisión UX 2026-09-17, D5).
        if (pendingAssignments.isNotEmpty()) {
            item {
                Text(
                    text = s("task_detail_pending_header").replace("%d", pendingAssignments.size.toString()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (pendingAssignments.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = if (completedAssignments.isNotEmpty()) s("task_detail_all_completed") else s("task_detail_no_assignments"),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(pendingAssignments, key = { it.id }) { assignment ->
                val member = memberMap[assignment.memberId]
                AssignmentCard(
                    assignment = assignment,
                    member = member,
                    now = now,
                    // Solo el miembro asignado o un admin/owner pueden
                    // completarla (ver TaskListRules.canComplete, replica en
                    // cliente de la autorización server-side de
                    // `completeAssignment` — panel de reglas de negocio,
                    // gap: antes se ofrecía "Hecho" para la asignación de
                    // CUALQUIER miembro).
                    showComplete = TaskListRules.canComplete(
                        assignment = assignment,
                        currentMemberId = currentMemberId,
                        isAdmin = isAdmin
                    ),
                    isLoading = actionState is TaskActionState.Loading,
                    isError = actionState is TaskActionState.Error,
                    onComplete = { onComplete(assignment.id, assignment) }
                )
            }
        }

        // ── "Otros": sincronización Calendar + asignaciones completadas +
        // comentarios — contenido secundario/infrecuente, plegado por defecto
        // (diseño v2, fase 1, ítem 4).
        item {
            val otrosHasError = calendarActionState is TaskScreenModel.CalendarActionState.Error ||
                sendCommentError != null
            ExpandableSectionHeader(
                expanded = otrosExpanded,
                onToggle = { otrosExpanded = !otrosExpanded },
                chevronTint = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = s("task_detail_other_section"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (!otrosExpanded && otrosHasError) {
                    StatusDot(color = MaterialTheme.colorScheme.error, size = 8.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }

        if (otrosExpanded) {
            // ── Google Calendar sync status ──
            item {
                val calendarStatus = when {
                    myAssignment == null || myAssignment.dueDate <= 0 -> CalendarSyncStatus.NoDueDate
                    myAssignment.googleEventId != null -> CalendarSyncStatus.Synced
                    !isGoogleLinked -> CalendarSyncStatus.NotLinked
                    !isCalendarSyncEnabled -> CalendarSyncStatus.SyncDisabled
                    else -> CalendarSyncStatus.Pending
                }
                CalendarSyncStatusCard(
                    status = calendarStatus,
                    calendarActionState = calendarActionState,
                    isLinkingCalendar = isLinkingCalendar,
                    s = s,
                    onSyncNow = onSyncCalendarNow,
                    onLinkAccount = onLinkCalendar,
                    onEnableSync = onEnableCalendarSync
                )
            }

            // ── Completed assignments ──
            if (completedAssignments.isNotEmpty()) {
                item {
                    Text(
                        text = s("task_detail_completed_header").replace("%d", completedAssignments.size.toString()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(completedAssignments, key = { it.id }) { assignment ->
                    val member = memberMap[assignment.memberId]
                    AssignmentCard(
                        assignment = assignment,
                        member = member,
                        now = now,
                        showComplete = false
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }

            // ── Comments section ──
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = s("task_detail_comments_title"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Banner de error al ENVIAR un comentario — independiente de
            // commentsState (que sigue mostrando la lista ya cargada, ver KDoc
            // de TaskCommentsScreenModel.sendCommentError).
            if (sendCommentError != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "⚠️ $sendCommentError",
                                // liveRegion: sin esto, TalkBack/VoiceOver no anuncia el
                                // fallo de envío mientras el foco sigue en el campo de texto.
                                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            IconButton(onClick = onDismissSendCommentError) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = s("common_dismiss"),
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            // Comment input
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newCommentText,
                        onValueChange = onCommentTextChange,
                        modifier = Modifier.weight(1f),
                        label = { Text(s("task_detail_comment_label")) },
                        placeholder = { Text(s("task_detail_comment_placeholder")) },
                        maxLines = 2,
                        singleLine = false,
                        supportingText = {
                            Text("${newCommentText.length}/200")
                        },
                        colors = taskHubTextFieldColors()
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = onAddComment,
                        enabled = newCommentText.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = s("messages_send"))
                    }
                }
            }

            // Comments list
            when (commentsState) {
                is CommentsUiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }

                is CommentsUiState.Success -> {
                    if (commentsState.comments.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = s("task_detail_no_comments"),
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        items(commentsState.comments, key = { it.id }) { comment ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                // surfaceVariant (no Teal50 con alpha 0.5f): el alpha compuesto sobre
                                // el fondo oscuro daba un contenedor de contraste insuficiente para
                                // onSurfaceVariant/onSurface (2.1-2.8:1) — surfaceVariant ya está
                                // pensado para combinarse con esos colores "on*" en los 3 temas/modos.
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = comment.authorName,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (comment.createdAt > 0) {
                                            Text(
                                                text = formatDateTime(comment.createdAt),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = comment.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                is CommentsUiState.Error -> {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = "⚠️ ${commentsState.message}",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                is CommentsUiState.Idle -> {}
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    // ── Diálogo: cambiar quién ha hecho la tarea ──
    if (showChangeWhoDialog) {
        val members = memberMap.values.toList()
        AlertDialog(
            onDismissRequest = { showChangeWhoDialog = false },
            title = { Text(s("task_detail_who_did_title")) },
            text = {
                Column {
                    Text(
                        text = s("task_detail_who_did_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.selectableGroup()) {
                        members.forEach { member ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.RadioButton) { selectedCompleterId = member.id }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedCompleterId == member.id,
                                    // La Row exterior ya es clicable con role=RadioButton
                                    // (línea 915): con onClick no-nulo aquí, TalkBack expone
                                    // dos nodos interactivos superpuestos sobre el mismo
                                    // control (panel de expertos 2026-09-11 v9 reintento,
                                    // accesibilidad).
                                    onClick = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = member.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showChangeWhoDialog = false
                        selectedCompleterId?.let { onChangeCompletedBy(it) }
                    },
                    enabled = selectedCompleterId != null && selectedCompleterId != task.completedBy
                ) {
                    Text(s("edit_task_submit"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangeWhoDialog = false }) {
                    Text(s("common_cancel"))
                }
            }
        )
    }
}

// ────────────────────────────────────────────────────────────
//  CalendarSyncStatusCard
// ────────────────────────────────────────────────────────────

private enum class CalendarSyncStatus { NoDueDate, Synced, Pending, NotLinked, SyncDisabled }

/**
 * Indicador del estado de sincronización con Google Calendar de la
 * asignación del usuario actual para esta tarea. Sustituye al antiguo botón
 * manual "Enviar a Google Calendar" — ahora la sincronización es automática
 * (ver [org.taskhub.ui.models.CalendarSyncManager]) y este componente solo
 * informa y ofrece un backfill puntual ("Sincronizar ahora") cuando aplica.
 */
@Composable
private fun CalendarSyncStatusCard(
    status: CalendarSyncStatus,
    calendarActionState: TaskScreenModel.CalendarActionState,
    isLinkingCalendar: Boolean,
    s: (String) -> String,
    onSyncNow: () -> Unit,
    onLinkAccount: () -> Unit,
    onEnableSync: () -> Unit
) {
    if (status == CalendarSyncStatus.NoDueDate) {
        Text(
            text = s("calendar_status_no_due_date"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = when (status) {
                        CalendarSyncStatus.Synced -> s("calendar_status_synced")
                        CalendarSyncStatus.Pending -> s("calendar_status_pending")
                        CalendarSyncStatus.NotLinked -> s("calendar_status_not_linked")
                        CalendarSyncStatus.SyncDisabled -> s("calendar_status_sync_disabled")
                        CalendarSyncStatus.NoDueDate -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    // onSuccessContainer (no success) porque este texto va sobre
                    // colorScheme.surfaceVariant: en el tema Naturaleza claro, success
                    // (0xFF2E7D32) cae por debajo de 4.5:1 contra ese fondo concreto —
                    // onSuccessContainer sí pasa AA en las 6 combinaciones tema×modo.
                    color = if (status == CalendarSyncStatus.Synced) MaterialTheme.semanticColors.onSuccessContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (status) {
                    CalendarSyncStatus.Pending -> {
                        TextButton(
                            onClick = onSyncNow,
                            enabled = calendarActionState !is TaskScreenModel.CalendarActionState.Sending
                        ) {
                            if (calendarActionState is TaskScreenModel.CalendarActionState.Sending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(s("calendar_status_syncing"))
                            } else {
                                Text(s("calendar_status_sync_now"))
                            }
                        }
                    }
                    CalendarSyncStatus.NotLinked -> {
                        TextButton(onClick = onLinkAccount, enabled = !isLinkingCalendar) {
                            if (isLinkingCalendar) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(s("calendar_status_link_cta"))
                            }
                        }
                    }
                    CalendarSyncStatus.SyncDisabled -> {
                        TextButton(onClick = onEnableSync) {
                            Text(s("calendar_status_enable_cta"))
                        }
                    }
                    else -> {}
                }
            }

            if (calendarActionState is TaskScreenModel.CalendarActionState.Error) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "⚠️ ${calendarActionState.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
//  AssignmentCard
// ────────────────────────────────────────────────────────────

/**
 * Tarjeta de una asignación de tarea a un miembro concreto: avatar/rol,
 * fecha límite (resaltada en rojo si está vencida y aún pendiente), insignia
 * de obligatoria y, si ya está completada, puntos otorgados (con distinto
 * color si fue a tiempo o con retraso/penalización).
 */
@Composable
private fun AssignmentCard(
    assignment: TaskAssignmentResponse,
    member: MemberResponse?,
    now: Long,
    showComplete: Boolean,
    isLoading: Boolean = false,
    isError: Boolean = false,
    onComplete: (() -> Unit)? = null
) {
    val appSettings = LocalAppSettings.current
    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

    // Celebración del botón "Hecho" de la asignación (delight fase 2, §2.5):
    // mismo patrón que el botón de nivel-tarea. AssignmentCard no navega
    // atrás, así que aquí sí se ve completo el ciclo check+confeti sin
    // retraso adicional de navegación.
    val reduceMotion = shouldReduceMotion()
    val effectsOn = effectsEnabled(EffectCategory.EFFECTS)
    var isCompleting by remember(assignment.id) { mutableStateOf(false) }
    LaunchedEffect(isError) {
        if (isError) isCompleting = false
    }
    LaunchedEffect(isCompleting) {
        if (isCompleting) {
            if (!reduceMotion) delay(260)
            onComplete?.invoke()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Member avatar/info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(
                        avatarUrl = member?.avatarUrl,
                        fallbackEmoji = defaultRoleEmoji(member?.role ?: ""),
                        displayName = member?.displayName ?: "",
                        contentDescription = member?.displayName ?: "",
                        size = 32.dp,
                        backgroundColor = if (member?.role == "admin") MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (member?.role == "admin") s("ranking_role_admin") else s("member_role_child_full"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = member?.displayName ?: assignment.memberId.take(8),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Due date
                if (assignment.dueDate > 0) {
                    val isOverdue = now > assignment.dueDate
                    Text(
                        text = s("task_detail_due_at").replace("%s", formatDateTime(assignment.dueDate)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOverdue && showComplete)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Mandatory badge
                // Panel v17 (hallazgo IMPORTANTE de accesibilidad):
                // `colorScheme.tertiary` sobre el `surface` de esta Card
                // fallaba WCAG AA (4.19:1 en Naturaleza claro, bajo el
                // umbral 4.5:1 para texto normal) — `semanticColors.warning`
                // está auditado explícitamente para texto sobre fondos
                // neutros en los 3 temas.
                if (assignment.mandatory) {
                    Text(
                        text = s("task_detail_mandatory_badge"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.semanticColors.warning
                    )
                }

                // Completion info
                if (assignment.status == "completed") {
                    val pts = assignment.pointsAwarded ?: 0
                    val onTime = assignment.onTime ?: true
                    Text(
                        text = if (onTime) s("task_detail_on_time_pts").replace("%d", pts.toString())
                        else s("task_detail_late_pts").replace("%d", pts.toString()),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (onTime) MaterialTheme.colorScheme.primary else MaterialTheme.semanticColors.warning
                    )
                    if (assignment.completedAt != null) {
                        Text(
                            text = s("task_detail_completed_at").replace("%s", formatDateTime(assignment.completedAt!!)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Complete button
            if (showComplete && onComplete != null) {
                Box {
                    if (isCompleting) {
                        AnimatedCheckmark(reduceMotion = reduceMotion || !effectsOn)
                    } else {
                        Button(
                            onClick = { isCompleting = true },
                            enabled = !isLoading
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(s("task_detail_mark_done"))
                            }
                        }
                    }
                    if (isCompleting && !reduceMotion && effectsOn) {
                        ConfettiOverlay(modifier = Modifier.matchParentSize())
                    }
                }
            } else if (assignment.status == "completed") {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "✅",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
//  Helpers

private fun formatDateTime(epochMillis: Long): String {
    val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMillis)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val month = local.monthNumber.toString().padStart(2, '0')
    val day = local.dayOfMonth.toString().padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val min = local.minute.toString().padStart(2, '0')
    return "$day/$month ${hour}:${min}"
}

private fun intervalLabel(interval: String, s: (String) -> String): String = when (interval) {
    "week" -> s("task_detail_interval_week")
    "month" -> s("task_detail_interval_month")
    else -> s("task_detail_interval_day")
}
