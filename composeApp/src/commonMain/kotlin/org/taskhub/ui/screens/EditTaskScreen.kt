/**
 * Formulario de edición de una tarea existente: se navega aquí desde
 * [TaskDetailScreen] o [TaskListScreen] (acción "Editar"). Precarga todos
 * los campos desde el [TaskResponse] recibido y, además, desde las
 * asignaciones ya guardadas en Firestore (vía
 * [org.taskhub.ui.models.TaskScreenModel.getAssignments], recargable si
 * falla). Reutiliza las mismas validaciones y helpers de formato de fecha/hora
 * que [CreateTaskScreen] (definidos en ese archivo, mismo paquete). También
 * permite configurar la rotación de asignación por día de la semana.
 */
package org.taskhub.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import org.taskhub.network.models.TaskResponse
import org.taskhub.network.models.Subtask
import org.taskhub.ui.models.TaskActionState
import org.taskhub.ui.models.TaskScreenModel
import org.taskhub.ui.models.MemberScreenModel
import org.taskhub.ui.models.MemberUiState
import org.taskhub.ui.models.HouseholdScreenModel
import org.taskhub.ui.components.ExpandableSectionHeader
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.RecurrenceNextPreview
import org.taskhub.ui.components.TaskHubTopBar
import org.taskhub.ui.components.filterChipCheckIcon
import org.taskhub.ui.components.rememberHouseholdName
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.theme.*

// ────────────────────────────────────────────────────────────
//  EditTaskScreen
// ────────────────────────────────────────────────────────────

/**
 * Igual que [CreateTaskScreen] pero pre-rellenado y con `updateTask` en vez
 * de `createTask`. Añade además la rotación de asignación por día de la
 * semana ([rotationSlots]), no presente en la creación.
 */
data class EditTaskScreen(
    val householdId: String,
    val task: TaskResponse
) : Screen {

    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val focusManager = LocalFocusManager.current
        val taskModel = koinScreenModel<TaskScreenModel>()
        val memberModel = koinScreenModel<MemberScreenModel>()
        val actionState by taskModel.actionState.collectAsState()
        val memberState by memberModel.uiState.collectAsState()
        val appSettings = LocalAppSettings.current
        val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

        val householdModel = koinScreenModel<HouseholdScreenModel>()
        val householdName = rememberHouseholdName(householdId, householdModel)

        LaunchedEffect(Unit) {
            taskModel.resetActionState()
        }

        LaunchedEffect(householdId) {
            memberModel.loadMembers(householdId)
        }

        // Form state — pre-populated from existing task
        var title by remember { mutableStateOf(task.title) }
        var description by remember { mutableStateOf(task.description) }
        var pointsText by remember { mutableStateOf(task.points.toString()) }
        var frequency by remember { mutableStateOf(task.frequency) }
        // Tareas semanales legado con recurrenceDays vacío (creadas antes del
        // premarcado de 7 días por defecto) inicializan con los 7 días
        // marcados en vez de reproducir el estado ambiguo "sin ningún día
        // marcado" al abrir para editar — mismo criterio que el chip
        // "Semanal" aplica al elegir la frecuencia por primera vez (panel v4,
        // UX hallazgo MEDIA #6).
        var recurrenceDays by remember {
            mutableStateOf(
                if (task.frequency == "weekly" && task.recurrenceDays.isEmpty()) {
                    (1..7).toSet()
                } else {
                    task.recurrenceDays.toSet()
                }
            )
        }
        var recurrenceDay by remember { mutableStateOf(task.recurrenceDay) }
        var tags by remember { mutableStateOf(task.tags) }
        var tagsText by remember { mutableStateOf("") }
        // Checklist (subtareas) — editable desde "Editar tarea"
        var subtasks by remember { mutableStateOf(task.subtasks) }
        var subtaskText by remember { mutableStateOf("") }
        var hasPenalty by remember { mutableStateOf(task.penaltyMode != null) }
        var penaltyMode by remember { mutableStateOf(task.penaltyMode ?: "fixed") }
        var penaltyValue by remember { mutableStateOf(if (task.penaltyValue > 0) task.penaltyValue.toString() else "") }
        var penaltyInterval by remember { mutableStateOf(task.penaltyInterval) }
        var penaltyMax by remember { mutableStateOf(if (task.penaltyMax > 0) task.penaltyMax.toString() else "") }

        // Obligatoria + fecha límite (se precargan de la tarea y sus asignaciones)
        var mandatory by remember { mutableStateOf(false) }
        var hasDeadline by remember { mutableStateOf(task.dueDate > 0) }
        var deadlineDay by remember { mutableStateOf("") }
        var deadlineTime by remember { mutableStateOf("12:00") }
        var showDatePicker by remember { mutableStateOf(false) }

        // Assignment state
        var selectedMembers by remember { mutableStateOf(setOf<String>()) }
        var hasRotation by remember { mutableStateOf(task.assignmentRotation.isNotEmpty()) }
        // rotationSlots: dayOfWeek (1..7) → memberId (empty string = not set)
        var rotationSlots by remember {
            mutableStateOf(
                (1..7).associateWith { day ->
                    task.assignmentRotation.find { it.dayOfWeek == day }?.memberId ?: ""
                }.toMutableMap()
            )
        }

        var assignmentsLoadFailed by remember { mutableStateOf(false) }
        var assignmentsReloadTrigger by remember { mutableStateOf(0) }

        // Estado de plegado de las secciones desplegables (diseño v2, fase 1).
        var checklistExpanded by remember { mutableStateOf(task.subtasks.isNotEmpty()) }
        var showCustomTagField by remember { mutableStateOf(false) }
        var tagsExpanded by remember { mutableStateOf(task.tags.isNotEmpty()) }
        var assignmentExpanded by remember { mutableStateOf(false) }
        // La penalización y la rotación ahora viven en apartados distintos
        // ("Puntuación" y este condicionado a frecuencia semanal): "Otros"
        // solo necesita abrirse por defecto si hay fecha límite, o rotación
        // ya guardada en una tarea que además sigue siendo semanal (una
        // tarea legado con rotación pero frecuencia ya cambiada no debería
        // forzar la apertura de un apartado donde esa rotación ni se ve).
        var otrosExpanded by remember { mutableStateOf(hasDeadline || (task.assignmentRotation.isNotEmpty() && task.frequency == "weekly")) }
        var puntuacionExpanded by remember { mutableStateOf(true) }

        // Precargar fecha límite (una sola vez).
        LaunchedEffect(Unit) {
            if (task.dueDate > 0) {
                val dt = Instant.fromEpochMilliseconds(task.dueDate)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                deadlineDay = "${dt.year}-${dt.monthNumber.toString().padStart(2, '0')}-${dt.dayOfMonth.toString().padStart(2, '0')}"
                deadlineTime = "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
            }
        }

        // Precargar asignaciones existentes (para poder editarlas); reintentable
        // tras un fallo de red sin tener que salir y volver a entrar en la pantalla.
        LaunchedEffect(assignmentsReloadTrigger) {
            try {
                assignmentsLoadFailed = false
                val assignments = taskModel.getAssignments(householdId, task.id)
                if (assignments.isNotEmpty()) {
                    selectedMembers = assignments.map { it.memberId }.toSet()
                    mandatory = assignments.firstOrNull()?.mandatory ?: false
                    assignmentExpanded = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Una subcolección vacía NO lanza excepción en Firestore (devuelve
                // documents: []); un fallo aquí es un error real de red, no "sin
                // asignaciones", así que avisamos en vez de dejar el formulario en
                // silencio con selectedMembers vacío como si la tarea no tuviera
                // asignaciones cuando en realidad podrían existir pero no cargaron.
                assignmentsLoadFailed = true
            }
        }

        // Panel v16 (2026-09-24, hallazgo I11): ver el mismo motivo en
        // CreateTaskScreen — guardar una edición era indistinguible de
        // "se canceló sin guardar".
        val snackbarHostState = remember { SnackbarHostState() }
        val successCoroutineScope = rememberCoroutineScope()

        // Handle success — navigate back and refresh detail
        LaunchedEffect(actionState) {
            if (actionState is TaskActionState.Success) {
                successCoroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = s("edit_task_success"),
                        duration = SnackbarDuration.Short
                    )
                }
                navigator.pop()
            }
        }

        // ── DatePicker para la fecha límite ──
        if (showDatePicker) {
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val initialMillis = if (deadlineDay.isValidDateFormat()) {
                val parts = deadlineDay.split("-")
                LocalDate(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                    .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
            } else {
                today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
            }
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.fromEpochMilliseconds(millis)
                                .toLocalDateTime(TimeZone.UTC).date
                            deadlineDay = "${date.year}-${date.monthNumber.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"
                        }
                        showDatePicker = false
                    }) { Text(s("task_date_accept")) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text(s("common_cancel")) }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { _ ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                TaskHubTopBar(
                    title = s("edit_task_title"),
                    subtitle = householdName,
                    onBack = { navigator.pop() },
                    actions = {
                        if (actionState !is TaskActionState.Loading) {
                            TextButton(
                                onClick = {
                                    val points = pointsText.toIntOrNull() ?: 10
                                    val pValue = penaltyValue.toIntOrNull() ?: 0
                                    val pMax = penaltyMax.toIntOrNull() ?: 0
                                    val dueDate = if (hasDeadline && deadlineDay.isNotBlank()) {
                                        parseDeadline(deadlineDay, deadlineTime)
                                    } else 0L

                                    // Build assignment rotation from rotation slots
                                    val rotation = resolvedRotation(hasRotation, frequency, rotationSlots)

                                    taskModel.updateTask(
                                        householdId = householdId,
                                        taskId = task.id,
                                        title = title,
                                        description = description,
                                        points = points,
                                        frequency = frequency,
                                        recurrenceDays = recurrenceDays.toList().sorted(),
                                        recurrenceDay = if (frequency == "monthly") recurrenceDay else null,
                                        tags = tags,
                                        subtasks = subtasks,
                                        penaltyMode = resolvedPenaltyMode(hasPenalty, hasDeadline, penaltyMode),
                                        penaltyValue = pValue,
                                        penaltyInterval = penaltyInterval,
                                        penaltyMax = pMax,
                                        assignmentRotation = rotation,
                                        memberIds = selectedMembers.toList(),
                                        mandatory = mandatory,
                                        dueDate = dueDate,
                                        lastCompletedDate = task.lastCompletedDate
                                    )
                                },
                                enabled = actionState !is TaskActionState.Loading &&
                                    !assignmentsLoadFailed &&
                                    title.isNotBlank() &&
                                    (pointsText.toIntOrNull() ?: -1) > 0 &&
                                    (!hasDeadline || deadlineTime.isValidTimeFormat()) &&
                                    (!hasPenalty || (penaltyValue.toIntOrNull() ?: -1) > 0) &&
                                    (!hasPenalty || penaltyMax.isBlank() || (penaltyMax.toIntOrNull() ?: -1) >= 0),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(s("edit_task_submit"), fontWeight = FontWeight.Bold)
                            }
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                )

                // Form content
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Error state
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
                                        text = (actionState as TaskActionState.Error).message,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        // Mismo patrón que AuthGateScreen/JoinHouseholdScreen:
                                        // sin esto, TalkBack/VoiceOver no anuncia el error al
                                        // aparecer, y el usuario no se entera de que "Guardar"
                                        // falló si no navega manualmente hasta este texto.
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                    )
                                }
                            }
                        }
                    }

                    // ── Basic info ──
                    item {
                        Text(
                            text = s("create_task_section_basic_info"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text(s("create_task_title_field")) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = title.isBlank(),
                            supportingText = {
                                if (title.isBlank()) {
                                    Text(s("create_task_title_required"))
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text(s("create_task_description_label")) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )
                    }

                    // ── Lista (antes "Checklist") ──
                    item {
                        ExpandableSectionHeader(
                            expanded = checklistExpanded,
                            onToggle = { checklistExpanded = !checklistExpanded },
                            chevronTint = MaterialTheme.colorScheme.primary
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = s("create_task_section_checklist"),
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
                        }
                    }

                    if (checklistExpanded) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = subtaskText,
                                    onValueChange = { subtaskText = it },
                                    label = { Text(s("create_task_add_item")) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                                )
                                Button(
                                    // Panel v16 (2026-09-24), hallazgo UX: ver mismo motivo en CreateTaskScreen.
                                    enabled = subtaskText.isNotBlank(),
                                    onClick = {
                                        val text = subtaskText.trim()
                                        if (text.isNotBlank()) {
                                            val id = kotlin.random.Random.nextLong().toString(36)
                                            subtasks = subtasks + Subtask(id = id, text = text, completed = false)
                                            subtaskText = ""
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = s("create_task_add_item"))
                                }
                            }
                        }

                        if (subtasks.isNotEmpty()) {
                            items(subtasks, key = { it.id }) { st ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = st.completed,
                                        onCheckedChange = { checked ->
                                            subtasks = subtasks.map {
                                                if (it.id == st.id) it.copy(completed = checked) else it
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                    )
                                    Text(
                                        text = st.text,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 4.dp),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    TextButton(
                                        onClick = { subtasks = subtasks.filter { it.id != st.id } },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = s("common_delete"))
                                    }
                                }
                            }
                        }
                    }

                    // ── Frequency ──
                    item {
                        Text(
                            text = s("create_task_section_frequency"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    item {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val freqs = listOf(
                                "once" to s("recurrence_once"),
                                "daily" to s("recurrence_daily"),
                                "weekly" to s("recurrence_weekly"),
                                "monthly" to s("recurrence_monthly")
                            )
                            freqs.forEach { (key, label) ->
                                FilterChip(
                                    selected = frequency == key,
                                    onClick = {
                                        frequency = key
                                        // "Semanal sin ningún día marcado" equivalía
                                        // silenciosamente a "todos los días"
                                        // (RecurrenceRules.isDueToday) sin ningún aviso.
                                        // Se premarcan los 7 días para que ese estado
                                        // ambiguo no sea el que queda por defecto.
                                        if (key == "weekly" && recurrenceDays.isEmpty()) {
                                            recurrenceDays = (1..7).toSet()
                                        }
                                        // Mismo problema que "semanal" sin días: "mensual"
                                        // sin recurrenceDay caía silenciosamente en el
                                        // camino de RecurrenceRules para "sin día fijado"
                                        // (se comporta como diaria). Prerellenar con el
                                        // día de hoy evita ese estado ambiguo por defecto.
                                        if (key == "monthly" && recurrenceDay == null) {
                                            recurrenceDay = Clock.System.now()
                                                .toLocalDateTime(TimeZone.currentSystemDefault()).date.dayOfMonth
                                        }
                                        // La rotación por día de la semana solo tiene sentido
                                        // con frecuencia semanal: al cambiar a otra frecuencia
                                        // se descarta para no dejar un estado residual sin
                                        // apartado visible donde revisarlo.
                                        if (key != "weekly") {
                                            hasRotation = false
                                            rotationSlots = (1..7).associateWith { "" }.toMutableMap()
                                        }
                                    },
                                    label = { Text(label) },
                                    leadingIcon = filterChipCheckIcon(frequency == key),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }

                    // Recurrence days (only for weekly)
                    if (frequency == "weekly") {
                        item {
                            Text(
                                text = s("recurrence_days_label"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        item {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val days = listOf(
                                    1 to (s("day_letter_monday") to s("recurrence_day_monday")),
                                    2 to (s("day_letter_tuesday") to s("recurrence_day_tuesday")),
                                    3 to (s("day_letter_wednesday") to s("recurrence_day_wednesday")),
                                    4 to (s("day_letter_thursday") to s("recurrence_day_thursday")),
                                    5 to (s("day_letter_friday") to s("recurrence_day_friday")),
                                    6 to (s("day_letter_saturday") to s("recurrence_day_saturday")),
                                    7 to (s("day_letter_sunday") to s("recurrence_day_sunday"))
                                )
                                days.forEach { (day, labels) ->
                                    val (letter, fullName) = labels
                                    FilterChip(
                                        selected = day in recurrenceDays,
                                        onClick = {
                                            val updated = if (day in recurrenceDays) {
                                                recurrenceDays - day
                                            } else {
                                                recurrenceDays + day
                                            }
                                            // Mismo criterio que CreateTaskScreen: no dejar
                                            // llegar a 0 días marcados (panel v4, UX hallazgo
                                            // ALTA #4).
                                            recurrenceDays = updated.ifEmpty { (1..7).toSet() }
                                        },
                                        label = { Text(letter) },
                                        leadingIcon = filterChipCheckIcon(day in recurrenceDays),
                                        modifier = Modifier.semantics { contentDescription = fullName },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Recurrence day of month (only for monthly)
                    if (frequency == "monthly") {
                        item {
                            Text(
                                text = s("recurrence_day_of_month_label"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = recurrenceDay?.toString() ?: "",
                                onValueChange = { text ->
                                    val n = text.toIntOrNull()
                                    recurrenceDay = when {
                                        text.isBlank() -> null
                                        n != null -> n.coerceIn(1, 31)
                                        else -> recurrenceDay
                                    }
                                },
                                label = { Text(s("create_task_day_of_month_field")) },
                                supportingText = { Text(s("recurrence_day_of_month_hint")) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                modifier = Modifier.fillMaxWidth(0.4f)
                            )
                        }
                    }

                    // Preview "próxima vez: X" (no aplica a "once", que usa fecha límite)
                    if (frequency != "once") {
                        item {
                            RecurrenceNextPreview(
                                frequency = frequency,
                                recurrenceDays = recurrenceDays.toList(),
                                recurrenceDay = recurrenceDay,
                                lang = appSettings.currentLanguage
                            )
                        }
                    }

                    // ── Etiquetas ──
                    item {
                        ExpandableSectionHeader(
                            expanded = tagsExpanded,
                            onToggle = { tagsExpanded = !tagsExpanded },
                            chevronTint = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = s("create_task_section_tags"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (tagsExpanded) {
                        // Predefined tags (FlowRow: los chips hacen wrap en vez de comprimirse),
                        // seguidas del chip "+ Otra" que revela el campo de texto libre (C3).
                        item {
                            val predefinedTags = listOf(
                                "limpieza", "cocina", "compras", "mascotas",
                                "mantenimiento", "niños", "exterior", "administración", "otro"
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                predefinedTags.forEach { tag ->
                                    FilterChip(
                                        selected = tag in tags,
                                        onClick = {
                                            tags = if (tag in tags) tags - tag else tags + tag
                                        },
                                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                        leadingIcon = filterChipCheckIcon(tag in tags)
                                    )
                                }
                                FilterChip(
                                    selected = showCustomTagField,
                                    onClick = { showCustomTagField = !showCustomTagField },
                                    label = { Text(s("create_task_tag_other_chip"), style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }

                        if (showCustomTagField) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = tagsText,
                                        onValueChange = { tagsText = it },
                                        label = { Text(s("create_task_add_tag")) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                                    )
                                    Button(
                                        // Panel v16, hallazgo UX: ver mismo motivo en CreateTaskScreen.
                                        enabled = tagsText.isNotBlank(),
                                        onClick = {
                                            val tag = tagsText.trim()
                                            if (tag.isNotBlank() && tag !in tags) {
                                                tags = tags + tag
                                                tagsText = ""
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = s("create_task_add_tag"))
                                    }
                                }
                            }
                        }

                        if (tags.isNotEmpty()) {
                            item {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    tags.forEach { tag ->
                                        InputChip(
                                            selected = false,
                                            onClick = { tags = tags - tag },
                                            label = { Text(tag) },
                                            trailingIcon = {
                                                Icon(
                                                    Icons.Filled.Close,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                                )
                                            },
                                            // El chip solo anunciaba el texto de la etiqueta,
                                            // sin indicar que pulsarlo la elimina (el icono va
                                            // con contentDescription = null a propósito, para
                                            // no duplicar el anuncio).
                                            modifier = Modifier.semantics {
                                                contentDescription = s("create_task_remove_tag_named").replace("%s", tag)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Assignment ──
                    item {
                        ExpandableSectionHeader(
                            expanded = assignmentExpanded,
                            onToggle = { assignmentExpanded = !assignmentExpanded },
                            chevronTint = MaterialTheme.colorScheme.primary
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = s("create_task_section_assignment"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (assignmentsLoadFailed) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = s("edit_task_assignment_load_error"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { assignmentsReloadTrigger++ }) {
                                            Text(s("common_retry"))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (assignmentExpanded) {
                        // Members list
                        when (val mState = memberState) {
                            is MemberUiState.Success -> {
                                if (mState.members.isNotEmpty()) {
                                    item {
                                        mState.members.forEach { member ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                                    .clickable(role = Role.Checkbox) {
                                                        selectedMembers = if (member.id in selectedMembers) {
                                                            selectedMembers - member.id
                                                        } else {
                                                            selectedMembers + member.id
                                                        }
                                                    }
                                                    .padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = member.id in selectedMembers,
                                                    // La Row exterior ya es clicable con role=Checkbox
                                                    // (línea 726): con onCheckedChange no-nulo aquí,
                                                    // TalkBack expone dos nodos interactivos superpuestos
                                                    // sobre el mismo control (panel de expertos
                                                    // 2026-09-11 v9 reintento, accesibilidad).
                                                    onCheckedChange = null,
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = MaterialTheme.colorScheme.primary
                                                    )
                                                )
                                                Text(
                                                    text = "${s(if (member.role == "admin") "member_role_admin_short" else "member_role_child_short")} ${member.displayName}",
                                                    style = MaterialTheme.typography.bodyLarge
                                                )
                                                // Mismo indicador que HouseholdMemberList.kt
                                                // para perfiles "hijo/a" sin cuenta de Google
                                                // vinculada (member.userId == null).
                                                if (member.userId == null) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Surface(
                                                        shape = MaterialTheme.shapes.extraSmall,
                                                        color = MaterialTheme.colorScheme.surfaceVariant
                                                    ) {
                                                        Text(
                                                            text = s("member_no_account_badge"),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            is MemberUiState.Loading -> {
                                item {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            else -> {}
                        }

                        // Obligatoria
                        if (selectedMembers.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = s("create_task_mandatory_label"),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Switch(
                                        checked = mandatory,
                                        onCheckedChange = { mandatory = it },
                                        colors = SwitchDefaults.colors(
                                            checkedTrackColor = MaterialTheme.colorScheme.tertiary
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // ── "Otros" (Fecha límite + Rotación semanal) ──
                    item {
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
                        }
                    }

                    if (otrosExpanded) {
                        // ── Fecha límite ──
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = s("create_task_deadline_section"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Switch(
                                    checked = hasDeadline,
                                    onCheckedChange = {
                                        hasDeadline = it
                                        if (it && deadlineDay.isBlank()) {
                                            val now = Clock.System.now()
                                            val local = now.toLocalDateTime(TimeZone.currentSystemDefault())
                                            deadlineDay = "${local.year}-${local.monthNumber.toString().padStart(2, '0')}-${local.dayOfMonth.toString().padStart(2, '0')}"
                                        }
                                        // La penalización por retraso no tiene sentido sin
                                        // fecha límite: al desactivarla se descarta también
                                        // el estado de penalización en vez de dejarlo oculto
                                        // pero listo para colarse en el guardado.
                                        if (!it) {
                                            hasPenalty = false
                                            penaltyMode = "fixed"
                                            penaltyValue = ""
                                            penaltyInterval = "day"
                                            penaltyMax = ""
                                        }
                                    }
                                )
                            }
                        }

                        if (hasDeadline) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { showDatePicker = true },
                                        modifier = Modifier.weight(1f).height(56.dp)
                                    ) {
                                        Icon(Icons.Filled.DateRange, contentDescription = s("create_task_pick_date"))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = if (deadlineDay.isBlank()) s("create_task_pick_date") else deadlineDay,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    OutlinedTextField(
                                        value = deadlineTime,
                                        onValueChange = { deadlineTime = it },
                                        label = { Text(s("create_task_time_label")) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        isError = !deadlineTime.isValidTimeFormat(),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                                    )
                                }
                            }
                            // D2 (2026-09-24): fechas pasadas se permiten (no se bloquean,
                            // p.ej. para registrar tareas hechas fuera de plazo), pero se
                            // avisa para que no pase desapercibido.
                            if (deadlineDay.isValidDateFormat() && isPastDate(deadlineDay)) {
                                item {
                                    Text(
                                        text = s("task_past_due_warning"),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        // ── Rotación de asignación (solo tiene sentido con frecuencia
                        // semanal: sin ella no hay "días de la semana" que asignar) ──
                        if (frequency == "weekly") {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = s("edit_task_rotation_toggle"),
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = hasRotation,
                                        onCheckedChange = { hasRotation = it },
                                        colors = SwitchDefaults.colors(
                                            checkedTrackColor = MaterialTheme.colorScheme.tertiary
                                        )
                                    )
                                }
                            }

                            // Day-of-week rotation selectors
                            if (hasRotation) {
                                when (val mState = memberState) {
                                    is MemberUiState.Success -> {
                                        val members = mState.members
                                        val days = listOf(
                                            1 to s("recurrence_day_monday"), 2 to s("recurrence_day_tuesday"), 3 to s("recurrence_day_wednesday"),
                                            4 to s("recurrence_day_thursday"), 5 to s("recurrence_day_friday"), 6 to s("recurrence_day_saturday"), 7 to s("recurrence_day_sunday")
                                        )
                                        days.forEach { (day, label) ->
                                            item {
                                                var expanded by remember { mutableStateOf(false) }
                                                val selectedMember = members.find { it.id == rotationSlots[day] }
                                                val displayText = selectedMember?.displayName ?: s("edit_task_unassigned")

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        text = label,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier.width(80.dp),
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )

                                                    Box(modifier = Modifier.weight(1f)) {
                                                        OutlinedButton(
                                                            onClick = { expanded = true },
                                                            modifier = Modifier.fillMaxWidth()
                                                        ) {
                                                            Text(
                                                                text = displayText,
                                                                modifier = Modifier.weight(1f),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                                                        }

                                                        DropdownMenu(
                                                            expanded = expanded,
                                                            onDismissRequest = { expanded = false }
                                                        ) {
                                                            DropdownMenuItem(
                                                                text = { Text(s("edit_task_unassigned")) },
                                                                onClick = {
                                                                    rotationSlots = rotationSlots.toMutableMap().apply { put(day, "") }
                                                                    expanded = false
                                                                }
                                                            )
                                                            members.forEach { member ->
                                                                DropdownMenuItem(
                                                                    text = {
                                                                        Text("${s(if (member.role == "admin") "member_role_admin_short" else "member_role_child_short")} ${member.displayName}")
                                                                    },
                                                                    onClick = {
                                                                        rotationSlots = rotationSlots.toMutableMap().apply { put(day, member.id) }
                                                                        expanded = false
                                                                    },
                                                                    trailingIcon = if (member.userId == null) {
                                                                        {
                                                                            Surface(
                                                                                shape = MaterialTheme.shapes.extraSmall,
                                                                                color = MaterialTheme.colorScheme.surfaceVariant
                                                                            ) {
                                                                                Text(
                                                                                    text = s("member_no_account_badge"),
                                                                                    style = MaterialTheme.typography.labelSmall,
                                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                                )
                                                                            }
                                                                        }
                                                                    } else null
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    is MemberUiState.Loading -> {
                                        item {
                                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                        }
                                    }

                                    else -> {}
                                }
                            }
                        }
                    }

                    // ── Puntuación (puntos + penalización por retraso) ──
                    item {
                        ExpandableSectionHeader(
                            expanded = puntuacionExpanded,
                            onToggle = { puntuacionExpanded = !puntuacionExpanded },
                            chevronTint = MaterialTheme.colorScheme.primary
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = s("task_detail_points_section"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = s("create_task_section_points_hint"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (puntuacionExpanded) {
                        item {
                            OutlinedTextField(
                                value = pointsText,
                                onValueChange = { newValue ->
                                    // Panel v16, hallazgo UX: ver mismo motivo en CreateTaskScreen.
                                    if (newValue.all { it.isDigit() } && newValue.length <= 5) {
                                        pointsText = newValue
                                    }
                                },
                                label = { Text(s("public_profile_stat_points")) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                isError = (pointsText.toIntOrNull() ?: -1) <= 0,
                                supportingText = {
                                    if (pointsText.toIntOrNull() == null) {
                                        Text(s("create_task_points_error_nan"))
                                    } else if ((pointsText.toIntOrNull() ?: -1) <= 0) {
                                        Text(s("create_task_points_error_positive"))
                                    }
                                }
                            )
                        }

                        // La penalización por retraso solo tiene sentido si la tarea
                        // tiene fecha límite (activable en "Otros"): sin eso no hay
                        // "retraso" que penalizar.
                        if (hasDeadline) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = s("create_task_penalty_section"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Switch(
                                        checked = hasPenalty,
                                        onCheckedChange = { hasPenalty = it }
                                    )
                                }
                            }

                            if (hasPenalty) {
                                // Penalty mode
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilterChip(
                                            selected = penaltyMode == "fixed",
                                            onClick = { penaltyMode = "fixed" },
                                            label = { Text(s("create_task_penalty_fixed")) },
                                            leadingIcon = filterChipCheckIcon(penaltyMode == "fixed"),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        )
                                        FilterChip(
                                            selected = penaltyMode == "percentage",
                                            onClick = { penaltyMode = "percentage" },
                                            label = { Text(s("create_task_penalty_percentage")) },
                                            leadingIcon = filterChipCheckIcon(penaltyMode == "percentage"),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        )
                                    }
                                }

                                item {
                                    OutlinedTextField(
                                        value = penaltyValue,
                                        onValueChange = { penaltyValue = it },
                                        label = {
                                            Text(if (penaltyMode == "fixed") s("create_task_penalty_points_label") else s("create_task_penalty_percent_label"))
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                        isError = (penaltyValue.toIntOrNull() ?: -1) <= 0,
                                        supportingText = {
                                            Text(if (penaltyMode == "fixed")
                                                s("create_task_penalty_fixed_hint")
                                            else s("create_task_penalty_percent_hint"))
                                        }
                                    )
                                }

                                item {
                                    Text(
                                        text = s("create_task_penalty_interval_label"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilterChip(
                                            selected = penaltyInterval == "day",
                                            onClick = { penaltyInterval = "day" },
                                            label = { Text(s("create_task_interval_daily")) },
                                            leadingIcon = filterChipCheckIcon(penaltyInterval == "day"),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        )
                                        FilterChip(
                                            selected = penaltyInterval == "week",
                                            onClick = { penaltyInterval = "week" },
                                            label = { Text(s("recurrence_weekly")) },
                                            leadingIcon = filterChipCheckIcon(penaltyInterval == "week"),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        )
                                        FilterChip(
                                            selected = penaltyInterval == "month",
                                            onClick = { penaltyInterval = "month" },
                                            label = { Text(s("recurrence_monthly")) },
                                            leadingIcon = filterChipCheckIcon(penaltyInterval == "month"),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        )
                                    }
                                }

                                item {
                                    OutlinedTextField(
                                        value = penaltyMax,
                                        onValueChange = { penaltyMax = it },
                                        label = { Text(s("create_task_penalty_max_label")) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                        // A diferencia de sus campos hermanos ("Puntos", penaltyValue),
                                        // este no validaba nada. 0/vacío SÍ es válido (sin tope), solo
                                        // un negativo o texto no numérico es error.
                                        isError = penaltyMax.isNotBlank() && (penaltyMax.toIntOrNull() ?: -1) < 0,
                                        supportingText = {
                                            Text(s("create_task_penalty_max_hint"))
                                        }
                                    )
                                }
                            }
                        } else {
                            item {
                                Text(
                                    text = s("create_task_penalty_requires_deadline_hint"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Bottom spacer
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
        }
    }
}