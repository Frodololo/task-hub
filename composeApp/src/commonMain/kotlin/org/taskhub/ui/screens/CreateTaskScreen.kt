/**
 * Formulario de creación de tarea: se navega aquí desde [HouseholdScreen]
 * (botón crear tarea directa para un miembro) o desde [TaskListScreen].
 * Concentra las reglas de negocio del modelo de tarea (recurrencia
 * diaria/semanal/mensual, fecha límite, penalización fija/porcentual,
 * checklist de subtareas, asignación a uno o varios miembros) que persiste
 * vía [org.taskhub.ui.models.TaskScreenModel]; también usa
 * [org.taskhub.ui.models.MemberScreenModel] para listar a quién asignar.
 */
package org.taskhub.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List as ListIcon
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import org.taskhub.network.models.AssignmentSlot
import org.taskhub.network.models.Subtask
import org.taskhub.ui.models.MemberScreenModel
import org.taskhub.ui.models.MemberUiState
import org.taskhub.ui.models.TaskActionState
import org.taskhub.ui.models.TaskScreenModel
import org.taskhub.ui.models.TaskTemplate
import org.taskhub.ui.models.TaskTemplates
import org.taskhub.ui.models.TemplateCategory
import org.taskhub.ui.models.HouseholdScreenModel
import org.taskhub.ui.components.ExpandableSectionHeader
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.RecurrenceNextPreview
import org.taskhub.ui.components.TaskHubTopBar
import org.taskhub.ui.components.filterChipCheckIcon
import org.taskhub.ui.components.rememberHouseholdName
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.theme.*

// ────────────────────────────────────────────────────────────
//  CreateTaskScreen
// ────────────────────────────────────────────────────────────

/**
 * Formulario largo en [LazyColumn] con secciones: info básica, checklist,
 * frecuencia/recurrencia, etiquetas, asignación de miembros, fecha límite y
 * penalización. El botón "Crear" (en la topbar) solo se habilita cuando
 * pasan todas las validaciones de cada sección (ver `enabled = ...` en el
 * `TextButton` de creación).
 */
data class CreateTaskScreen(
    val householdId: String,
    val createdBy: String,
    /** Si se indica, la tarea sale preasignada a este miembro (crear tarea directa desde un miembro). */
    val preselectedMemberId: String? = null
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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

        // [createdBy] puede llegar vacío si esta pantalla se empujó antes de
        // que la pantalla de origen resolviera `currentMemberId` (arranca en
        // "" y se resuelve de forma asíncrona) — sin este fallback, crear la
        // tarea con `createdBy=""` deja un creador vacío y dispara una
        // auto-notificación falsa al "asignarse" la tarea a sí mismo (panel
        // de notificaciones 2026-09-05, QA, MENOR). Se resuelve aquí en vez
        // de bloquear para siempre: `createdBy` es un valor fijo capturado al
        // construir este `Screen`, no se actualiza solo aunque la pantalla de
        // origen termine de resolverlo más tarde.
        var effectiveCreatedBy by remember(createdBy) { mutableStateOf(createdBy) }
        // true si resolveCurrentMemberId falló — antes esta rama no tenía
        // try/catch y una excepción de red podía propagarse sin control
        // (ronda de deuda aplicable 2026-09-12, punto A5); distingue "aún
        // resolviendo" (spinner) de "falló, hay que reintentar" (banner +
        // botón) en el aviso de más abajo.
        var creatorResolveError by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        suspend fun resolveCreator() {
            creatorResolveError = false
            try {
                effectiveCreatedBy = taskModel.resolveCurrentMemberId(householdId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                creatorResolveError = true
            }
        }

        LaunchedEffect(householdId) {
            taskModel.resetActionState()
            memberModel.loadMembers(householdId)
            if (createdBy.isBlank()) {
                resolveCreator()
            }
        }

        // Form state
        var title by remember { mutableStateOf("") }
        var titleTouched by remember { mutableStateOf(false) }
        var description by remember { mutableStateOf("") }
        var pointsText by remember { mutableStateOf("10") }
        var frequency by remember { mutableStateOf("once") }
        var recurrenceDays by remember { mutableStateOf(setOf<Int>()) }
        var recurrenceDay by remember { mutableStateOf<Int?>(null) }
        var tagsText by remember { mutableStateOf("") }
        var tags by remember { mutableStateOf(listOf<String>()) }
        var selectedMembers by remember {
            mutableStateOf(preselectedMemberId?.let { setOf(it) } ?: emptySet())
        }
        var mandatory by remember { mutableStateOf(false) }
        var hasDeadline by remember { mutableStateOf(false) }
        var deadlineDay by remember { mutableStateOf("") }
        var deadlineTime by remember { mutableStateOf("12:00") }
        var hasPenalty by remember { mutableStateOf(false) }
        var showDatePicker by remember { mutableStateOf(false) }

        // ── DatePicker para elegir la fecha límite ────────────
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
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let { millis ->
                                val date = Instant.fromEpochMilliseconds(millis)
                                    .toLocalDateTime(TimeZone.UTC).date
                                deadlineDay = "${date.year}-${date.monthNumber.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"
                            }
                            showDatePicker = false
                        }
                    ) { Text(s("task_date_accept")) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text(s("common_cancel")) }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }
        var penaltyMode by remember { mutableStateOf("fixed") }
        var penaltyValue by remember { mutableStateOf("") }
        var penaltyInterval by remember { mutableStateOf("day") }
        var penaltyMax by remember { mutableStateOf("") }
        var templatesExpanded by remember { mutableStateOf(false) }
        // Subtasks state
        var subtaskText by remember { mutableStateOf("") }
        var subtasks by remember { mutableStateOf(listOf<Subtask>()) }
        // Rotación de asignación por día de la semana (igual que EditTaskScreen,
        // aquí no hay tarea previa de la que precargar slots).
        var hasRotation by remember { mutableStateOf(false) }
        var rotationSlots by remember {
            mutableStateOf((1..7).associateWith { "" }.toMutableMap())
        }
        // Estado de plegado de las secciones desplegables (diseño v2, fase 1).
        var checklistExpanded by remember { mutableStateOf(false) }
        var showCustomTagField by remember { mutableStateOf(false) }
        var tagsExpanded by remember { mutableStateOf(false) }
        var assignmentExpanded by remember { mutableStateOf(preselectedMemberId != null) }
        var otrosExpanded by remember { mutableStateOf(false) }
        var puntuacionExpanded by remember { mutableStateOf(true) }

        // Panel v16 (2026-09-24, hallazgo I11): antes, crear una tarea era
        // indistinguible de "se canceló sin guardar" — la pantalla
        // simplemente desaparecía. Snackbar de confirmación, mismo patrón ya
        // usado en MemberRewardScreen para canjear recompensas.
        val snackbarHostState = remember { SnackbarHostState() }
        val successCoroutineScope = rememberCoroutineScope()

        // Handle success
        LaunchedEffect(actionState) {
            if (actionState is TaskActionState.Success) {
                taskModel.loadTasks(householdId)
                successCoroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = s("create_task_success"),
                        duration = SnackbarDuration.Short
                    )
                }
                navigator.pop()
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
                    title = s("create_task_title"),
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
                                    val rotation = resolvedRotation(hasRotation, frequency, rotationSlots)

                                    taskModel.createTask(
                                        householdId = householdId,
                                        createdBy = effectiveCreatedBy,
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
                                        dueDate = dueDate
                                    )
                                },
                                enabled = actionState !is TaskActionState.Loading &&
                                    effectiveCreatedBy.isNotBlank() &&
                                    title.isNotBlank() &&
                                    (pointsText.toIntOrNull() ?: -1) > 0 &&
                                    (!hasDeadline || deadlineTime.isValidTimeFormat()) &&
                                    (!hasPenalty || (penaltyValue.toIntOrNull() ?: -1) > 0) &&
                                    (!hasPenalty || penaltyMax.isBlank() || (penaltyMax.toIntOrNull() ?: -1) >= 0),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(s("create_task_submit"), fontWeight = FontWeight.Bold)
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
                    // createdBy aún sin resolver (miembro actual no cargado todavía):
                    // bloquea la creación en vez de guardar una tarea con createdBy=""
                    // (creador vacío + auto-notificación falsa al asignarse a sí mismo).
                    if (effectiveCreatedBy.isBlank()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (creatorResolveError) {
                                        MaterialTheme.colorScheme.errorContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (creatorResolveError) {
                                        // Reintento manual — antes este banner se quedaba
                                        // atascado en el spinner para siempre si
                                        // resolveCurrentMemberId fallaba, sin forma de
                                        // recuperarse sin salir de la pantalla (ronda de
                                        // deuda aplicable 2026-09-12, punto A5).
                                        Text(
                                            text = s("create_task_creator_resolve_error"),
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        TextButton(onClick = { coroutineScope.launch { resolveCreator() } }) {
                                            Text(s("common_retry"))
                                        }
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            text = s("create_task_creator_not_resolved"),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

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
                                        imageVector = Icons.Default.Close,
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

                    // ── Quick templates ──
                    item {
                        QuickTemplatesSection(
                            expanded = templatesExpanded,
                            onToggle = { templatesExpanded = !templatesExpanded },
                            onTemplateSelected = { template ->
                                title = template.title
                                description = template.description
                                tags = template.tags
                                frequency = template.frequency
                                pointsText = template.points.toString()
                            }
                        )
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
                            onValueChange = { title = it; titleTouched = true },
                            label = { Text(s("create_task_title_field")) },
                            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) titleTouched = true },
                            singleLine = true,
                            isError = titleTouched && title.isBlank(),
                            supportingText = {
                                if (titleTouched && title.isBlank()) {
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
                                    // Panel v16 (2026-09-24), hallazgo UX: antes
                                    // el botón se veía activo con el campo vacío
                                    // pero pulsar no hacía nada (affordance
                                    // engañosa, la comprobación de blank solo
                                    // vivía dentro del onClick).
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
                                    Icon(Icons.Default.Add, contentDescription = s("create_task_add_item"))
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
                                        Icon(Icons.Default.Close, contentDescription = s("common_delete"))
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
                                            // Igual que al elegir "Semanal" por primera vez: no
                                            // dejar llegar a 0 días marcados, que
                                            // RecurrenceRules trata silenciosamente como "todos
                                            // los días" — desmarcar el último día vuelve a
                                            // marcar los 7 en vez de dejar ese estado ambiguo
                                            // (panel v4, UX hallazgo ALTA #4).
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
                                        // Panel v16, hallazgo UX: ver el mismo motivo en el botón de añadir subtarea.
                                        enabled = tagsText.isNotBlank(),
                                        onClick = {
                                            val tag = tagsText.trim()
                                            if (tag.isNotBlank() && tag !in tags) {
                                                tags = tags + tag
                                                tagsText = ""
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = s("create_task_add_tag"))
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
                                                    Icons.Default.Close,
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
                                Text(
                                    text = s("create_task_assignment_hint"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
                                                    // (línea 763): con onCheckedChange no-nulo aquí,
                                                    // TalkBack expone dos nodos interactivos superpuestos
                                                    // sobre el mismo control (panel de expertos
                                                    // 2026-09-11 v9 reintento, accesibilidad).
                                                    onCheckedChange = null,
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = MaterialTheme.colorScheme.primary
                                                    )
                                                )
                                                Text(
                                                    // s("member_role_*_short") ya incluye el emoji + texto de rol
                                                    // ("👑 Admin"/"👤 Miembro"): un emoji sin texto de apoyo es el
                                                    // único diferenciador de rol para TalkBack/VoiceOver, que solo
                                                    // anuncia el nombre unicode del glifo ("corona"/"busto").
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
                                                Spacer(Modifier.weight(1f))
                                                Text(
                                                    text = "⭐ ${member.totalPoints}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
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

                        // Mandatory toggle
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
                        // ── Deadline ──
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
                                            deadlineDay = "${local.year}-${local.monthNumber.toString().padStart(2,'0')}-${local.dayOfMonth.toString().padStart(2,'0')}"
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
                                        Icon(Icons.Default.DateRange, contentDescription = s("create_task_pick_date"))
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
                                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
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
                                    // Panel v16 (2026-09-24), hallazgo UX: sin
                                    // límite de longitud, tecleando ~10+ dígitos
                                    // `toIntOrNull()` da null por overflow de Int
                                    // y el error mostrado era "Debe ser un
                                    // número" pese a que el usuario sí tecleó
                                    // solo dígitos — mismo límite ya usado en
                                    // CreateRewardScreen para `costText`.
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
                                        // A diferencia de "Puntos", este campo no validaba nada: con
                                        // "Aplicar penalización" activado y el valor vacío/0,
                                        // pValue caía en un fallback silencioso a 0 (createTask()
                                        // más abajo) — se guardaba "con penalización" que en
                                        // realidad nunca descontaba nada.
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
                                        // este no validaba nada: cualquier texto no numérico caía en
                                        // un fallback silencioso a 0 (pMax más arriba). 0/vacío SÍ es
                                        // un valor válido aquí (significa "sin tope"), solo un negativo
                                        // o texto no numérico es error.
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

// ────────────────────────────────────────────────────────────
//  Quick Templates Section
// ────────────────────────────────────────────────────────────

/**
 * Sección plegable de plantillas rápidas: al elegir una plantilla,
 * sobrescribe título/descripción/etiquetas/frecuencia/puntos del formulario
 * (no añade, reemplaza los valores actuales).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickTemplatesSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    onTemplateSelected: (TaskTemplate) -> Unit
) {
    val appSettings = LocalAppSettings.current
    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }
    val reduceMotion = shouldReduceMotion()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            ExpandableSectionHeader(
                expanded = expanded,
                onToggle = onToggle,
                modifier = Modifier.padding(vertical = 4.dp),
                chevronTint = MaterialTheme.colorScheme.primary
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ListIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = s("create_task_templates_title"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Content
            AnimatedVisibility(
                visible = expanded,
                enter = if (reduceMotion) EnterTransition.None else expandVertically(),
                exit = if (reduceMotion) ExitTransition.None else shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = s("create_task_templates_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TemplateCategory.entries.forEach { category ->
                        val templates = TaskTemplates.byCategory[category] ?: return@forEach
                        Column {
                            Text(
                                text = "${category.emoji} ${category.label}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                templates.forEach { template ->
                                    SuggestionChip(
                                        onClick = { onTemplateSelected(template) },
                                        label = {
                                            Text(
                                                text = template.title,
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        },
                                        icon = {
                                            Text(
                                                text = "\u2B50${template.points}",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
//  Helpers
// ────────────────────────────────────────────────────────────

// Regex a nivel de archivo (no recompiladas en cada llamada): isValidDateFormat/
// isValidTimeFormat se invocan desde el cuerpo del composable principal
// (condición `enabled` del botón Crear/Guardar, `isError` del campo de hora),
// así que sin esto se recompilaban en CADA recomposición del formulario, con
// cualquier tecleo en CUALQUIER campo (panel 2026-09-11, MENOR).
private val DATE_FORMAT_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
private val TIME_FORMAT_REGEX = Regex("""(\d{2}):(\d{2})""")

/** Comprueba el formato literal aaaa-mm-dd (no valida que la fecha exista). */
internal fun String.isValidDateFormat(): Boolean =
    DATE_FORMAT_REGEX.matches(this)

/**
 * D2 (2026-09-24): `true` si [dateStr] (aaaa-mm-dd, se asume ya válida vía
 * [isValidDateFormat]) es anterior al día de hoy en la zona horaria local.
 * Solo se usa para mostrar un aviso — las fechas pasadas SÍ se permiten
 * guardar (p.ej. registrar una tarea hecha fuera de plazo).
 */
internal fun isPastDate(dateStr: String): Boolean {
    val parts = dateStr.split("-")
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return false
    val month = parts.getOrNull(1)?.toIntOrNull() ?: return false
    val day = parts.getOrNull(2)?.toIntOrNull() ?: return false
    val selected = LocalDate(year, month, day)
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    return selected < today
}

/** Valida formato HH:mm y rango real de hora/minuto (evita crash de LocalDateTime, ver comentario abajo). */
internal fun String.isValidTimeFormat(): Boolean {
    // No basta con el formato (dos dígitos:dos dígitos): "99:99" también lo
    // cumple pero LocalDateTime(...) en parseDeadline() lanza
    // IllegalArgumentException con una hora/minuto fuera de rango — sin esta
    // validación, el botón Crear/Guardar quedaba habilitado y pulsar crasheaba.
    val match = TIME_FORMAT_REGEX.matchEntire(this) ?: return false
    val (hourStr, minuteStr) = match.destructured
    val hour = hourStr.toIntOrNull() ?: return false
    val minute = minuteStr.toIntOrNull() ?: return false
    return hour in 0..23 && minute in 0..59
}

/** Combina fecha (aaaa-mm-dd) + hora (HH:mm) en un epoch-millis en la zona horaria local. */
internal fun parseDeadline(dateStr: String, timeStr: String): Long {
    val parts = dateStr.split("-")
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return 0L
    val month = parts.getOrNull(1)?.toIntOrNull() ?: return 0L
    val day = parts.getOrNull(2)?.toIntOrNull() ?: return 0L

    val timeParts = timeStr.split(":")
    val hour = timeParts.getOrNull(0)?.toIntOrNull() ?: 12
    val minute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0

    val tz = TimeZone.currentSystemDefault()
    val localDateTime = LocalDateTime(year, month, day, hour, minute)
    return localDateTime.toInstant(tz).toEpochMilliseconds()
}

/**
 * Invariante de guardado (Crear y Editar tarea): la penalización por
 * retraso no tiene sentido sin fecha límite, así que se descarta al
 * guardar sin importar cómo haya quedado el estado local del formulario
 * (p.ej. si `hasPenalty` quedó en `true` por un flujo que no pasó por el
 * reseteo del switch de fecha límite).
 */
internal fun resolvedPenaltyMode(hasPenalty: Boolean, hasDeadline: Boolean, penaltyMode: String): String? =
    if (hasPenalty && hasDeadline) penaltyMode else null

/**
 * Invariante de guardado (Crear y Editar tarea): la rotación de
 * asignación por día de la semana solo aplica con frecuencia semanal.
 */
internal fun resolvedRotation(
    hasRotation: Boolean,
    frequency: String,
    rotationSlots: Map<Int, String>
): List<AssignmentSlot> =
    if (hasRotation && frequency == "weekly") {
        rotationSlots.entries
            .filter { it.value.isNotBlank() }
            .map { (day, memberId) -> AssignmentSlot(dayOfWeek = day, memberId = memberId) }
    } else {
        emptyList()
    }
