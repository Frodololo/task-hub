/**
 * Pantalla de calendario de tareas de un hogar, en vista semana/mes. Usa el
 * [org.taskhub.ui.models.TaskScreenModel] compartido (mismo que
 * [TaskListScreen]/[HomeScreen]) para cargar las tareas y calcula en cliente,
 * por celda de día, si cada una está pendiente/completada/vencida. Se navega
 * aquí desde [PersonalSpaceScreen]/[HomeScreen]; al tocar una tarea del popup
 * de día se abre [TaskDetailScreen].
 */
package org.taskhub.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.datetime.*
import org.taskhub.network.RecurrenceRules
import org.taskhub.network.models.TaskResponse
import org.taskhub.ui.components.BadgeTone
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.localizedMonthAbbr as monthAbbr
import org.taskhub.ui.components.PointsBadge
import org.taskhub.ui.components.ShimmerList
import org.taskhub.ui.components.StatusDot
import org.taskhub.ui.components.TaskHubTopBar
import org.taskhub.ui.components.rememberHouseholdName
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.*
import org.taskhub.ui.theme.*

// ── Calendar mode ──────────────────────────────────────────

internal enum class CalendarMode { WEEK, MONTH }

// ── Calendar entry (task + status for a specific day) ─────

internal data class DayTaskEntry(
    val task: TaskResponse,
    val isOverdue: Boolean,
    val isDueToday: Boolean,
    val isCompleted: Boolean
)

// ── Color helpers ──────────────────────────────────────────
// Antes: colores fijos (0xFFFFCDD2/Teal500/0xFFA5D6A7) iguales en los 3 temas
// y en claro/oscuro. En el tema Minimal (monocromo por diseño) rompían la
// identidad "escala de grises"; combinados con texto que sí sigue el tema
// (ver TaskChip), fallaban WCAG AA en varias combinaciones — mismo patrón ya
// corregido en el popup de esta misma pantalla (ver TaskPopupItem/statusLabel
// más abajo, que ya usa PointsBadge con pares container/onContainer
// auditados). Sustituidos por la paleta semántica (éxito/error/info), común
// a los 3 temas y ya auditada ≥4.5:1 en las 6 combinaciones.

/** Agrupa los 3 colores derivados del estado de [DayTaskEntry] — ver [DayTaskEntry.taskColors]. */
private data class DayTaskEntryColors(
    val dot: Color,
    val container: Color,
    val onContainer: Color
)

@Composable
private fun DayTaskEntry.taskColors(): DayTaskEntryColors = when {
    isCompleted -> DayTaskEntryColors(
        dot = MaterialTheme.semanticColors.success,
        container = MaterialTheme.semanticColors.successContainer,
        onContainer = MaterialTheme.semanticColors.onSuccessContainer
    )
    isOverdue -> DayTaskEntryColors(
        dot = MaterialTheme.colorScheme.error,
        container = MaterialTheme.colorScheme.errorContainer,
        onContainer = MaterialTheme.colorScheme.onErrorContainer
    )
    isDueToday -> DayTaskEntryColors(
        dot = MaterialTheme.semanticColors.info,
        container = MaterialTheme.semanticColors.infoContainer,
        onContainer = MaterialTheme.semanticColors.onInfoContainer
    )
    else -> DayTaskEntryColors(
        dot = MaterialTheme.colorScheme.primary,
        container = MaterialTheme.colorScheme.primaryContainer,
        onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    )
}

@Composable
private fun DayTaskEntry.dotColor(): Color = taskColors().dot

@Composable
private fun DayTaskEntry.containerColor(): Color = taskColors().container

@Composable
private fun DayTaskEntry.onContainerColor(): Color = taskColors().onContainer

// ────────────────────────────────────────────────────────────
//  CalendarScreen
// ────────────────────────────────────────────────────────────

/**
 * Pantalla de calendario. Muestra las tareas del hogar [householdId] en una
 * cuadrícula semanal o mensual, con un punto/chip de color por estado
 * (pendiente/vencida/completada). Al tocar un día se abre un popup
 * ([DayTasksPopup]) con el detalle de sus tareas; si se pasa [memberId], el
 * listado de tareas se restringe a ese miembro (usado desde el espacio
 * personal de un miembro concreto).
 */
data class CalendarScreen(
    val householdId: String,
    val memberId: String? = null
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = koinScreenModel<TaskScreenModel>()
        val listState by model.listState.collectAsState()
        val actionState by model.actionState.collectAsState()
        val appSettings = LocalAppSettings.current
        // remember(currentLanguage): sin esto se recreaba en cada recomposición
        // (p.ej. cualquier cambio de actionState/diálogo), impidiendo que
        // WeekView/MonthView/PendingWithoutDueDateSection aplicaran skip de
        // recomposición pese a que sus datos no hubieran cambiado (mismo
        // patrón ya corregido en HouseholdScreen).
        val s = remember(appSettings.currentLanguage) { { key: String -> AppStrings.get(key, appSettings.currentLanguage) } }
        val lang = appSettings.currentLanguage

        val householdModel = koinScreenModel<HouseholdScreenModel>()
        val householdName = rememberHouseholdName(householdId, householdModel)

        LaunchedEffect(householdId) {
            model.setCurrentMemberId(memberId)
            model.loadTasks(householdId)
        }

        // Completar una tarea de la sección "Pendientes" (sin fecha límite, ver
        // más abajo) reutiliza el mismo TaskActionState que el resto de la app
        // (TaskListScreen/TaskDetailScreen) — mismo patrón: al llegar a Success
        // se recarga la lista y se resetea el estado para no dejar el spinner
        // colgado en un segundo intento.
        LaunchedEffect(actionState) {
            if (actionState is TaskActionState.Success) {
                model.loadTasks(householdId)
                model.resetActionState()
            }
        }

        var mode by remember { mutableStateOf(CalendarMode.WEEK) }
        val reduceMotion = shouldReduceMotion()

        val tz = remember { TimeZone.currentSystemDefault() }
        // "Hoy", recalculado cada minuto: si la pantalla se deja abierta cruzando
        // la medianoche, el resaltado de "hoy" en semana/mes no se quedaba
        // clavado en la fecha con la que se abrió la pantalla.
        var today by remember { mutableStateOf(Clock.System.now().toLocalDateTime(tz).date) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                today = Clock.System.now().toLocalDateTime(tz).date
            }
        }

        // Anchor date: the "focus" date we use to compute week/month ranges
        var anchorDate by remember {
            mutableStateOf(today)
        }

        // ── Computed week / month ranges ────────────────────
        val weekRange = remember(anchorDate) {
            computeWeekRange(anchorDate)
        }
        val monthRange = remember(anchorDate) {
            computeMonthGrid(anchorDate)
        }

        // ── Tasks grouped by date ───────────────────────────
        val tasksByDate = remember(listState, anchorDate, mode) {
            val tasks = (listState as? TaskListUiState.Success)?.tasks ?: emptyList()
            groupTasksByDate(tasks, tz, mode, weekRange, monthRange)
        }

        // ── Tareas pendientes SIN fecha Y SIN recurrencia ────────────
        // Antes se mostraban (incorrectamente) en TODAS las celdas del
        // calendario, ver KDoc de [isTaskDueOnDay]. Ahora quedan fuera de la
        // cuadrícula y se listan aparte, debajo (revisión final 2026-09-17).
        val pendingWithoutDueDate = remember(listState) {
            val tasks = (listState as? TaskListUiState.Success)?.tasks ?: emptyList()
            tasks.filter { isTaskPendingWithoutDueDate(it) }
        }

        // ── Tareas caducadas: fecha ya pasada y sin completar ────────
        // Clave `today` incluida a propósito: sin ella, una tarea que vence
        // justo al cruzar la medianoche con la pantalla abierta no se
        // reclasificaba como caducada hasta el siguiente `loadTasks`.
        val overdueTasks = remember(listState, today) {
            val tasks = (listState as? TaskListUiState.Success)?.tasks ?: emptyList()
            tasks.filter { isTaskOverdueOverall(it, today, tz) }
        }

        // ── Selected day popup ──────────────────────────────
        var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
        val selectedTasks = remember(selectedDay, tasksByDate) {
            val day = selectedDay ?: return@remember emptyList<DayTaskEntry>()
            tasksByDate[day] ?: emptyList()
        }

        // ── Day popup dialog ────────────────────────────────
        if (selectedDay != null) {
            DayTasksPopup(
                date = selectedDay!!,
                tasks = selectedTasks,
                onDismiss = { selectedDay = null },
                onTaskClick = { taskId ->
                    selectedDay = null
                    navigator.push(TaskDetailScreen(householdId, taskId))
                }
            )
        }

        // ── UI ──────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Top bar ─────────────────────────────────
                TaskHubTopBar(
                    title = s("personal_space_calendar"),
                    subtitle = householdName,
                    onBack = { navigator.pop() },
                    actions = {
                        TextButton(
                            onClick = {
                                mode = if (mode == CalendarMode.WEEK) CalendarMode.MONTH else CalendarMode.WEEK
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                if (mode == CalendarMode.WEEK) s("calendar_toggle_month") else s("calendar_toggle_week"),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                )
                // Navigation row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            anchorDate = when (mode) {
                                CalendarMode.WEEK -> anchorDate.minus(7, DateTimeUnit.DAY)
                                CalendarMode.MONTH -> {
                                    val ym = anchorDate.minus(1, DateTimeUnit.MONTH)
                                    LocalDate(ym.year, ym.month, 1)
                                }
                            }
                        },
                        modifier = Modifier.semantics {
                            contentDescription = if (mode == CalendarMode.WEEK) s("calendar_prev_week") else s("calendar_prev_month")
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                    }
                    Text(
                        text = when (mode) {
                            CalendarMode.WEEK -> {
                                val monday = getMondayOfWeek(anchorDate)
                                val sunday = monday.plus(6, DateTimeUnit.DAY)
                                s("calendar_week_of")
                                    .replace("%1", monday.dayOfMonth.toString())
                                    .replace("%2", monthAbbr(monday.month, lang))
                            }
                            CalendarMode.MONTH ->
                                "${monthFull(anchorDate.month, lang)} ${anchorDate.year}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    TextButton(
                        onClick = {
                            anchorDate = when (mode) {
                                CalendarMode.WEEK -> anchorDate.plus(7, DateTimeUnit.DAY)
                                CalendarMode.MONTH -> {
                                    val ym = anchorDate.plus(1, DateTimeUnit.MONTH)
                                    LocalDate(ym.year, ym.month, 1)
                                }
                            }
                        },
                        modifier = Modifier.semantics {
                            contentDescription = if (mode == CalendarMode.WEEK) s("calendar_next_week") else s("calendar_next_month")
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }

                // ── Loading / Error states ──────────────────
                when (val state = listState) {
                    is TaskListUiState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                        ) {
                            ShimmerList(count = 5, itemHeight = 88.dp)
                        }
                    }

                    is TaskListUiState.Error -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = state.message,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                // Botón de reintentar — antes esta pantalla se quedaba
                                // sin forma de recuperarse tras un error de carga, a
                                // diferencia de NotificationListScreen (ronda de deuda
                                // aplicable 2026-09-12, punto A6).
                                Button(onClick = { model.loadTasks(householdId) }) {
                                    Text(s("common_retry"))
                                }
                            }
                        }
                    }

                    else -> {
                        // Una sola Column desplazable para la cuadrícula (semana/mes)
                        // + la sección "Pendientes" de abajo: WeekView/MonthView ya no
                        // hacen scroll cada una por su cuenta (antes MonthView ocupaba
                        // fillMaxSize() y dejaba sin espacio a cualquier contenido
                        // posterior en la misma Column).
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (tasksByDate.values.all { it.isEmpty() }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = s("calendar_no_tasks_range"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            val onDayClick = remember { { day: LocalDate -> selectedDay = day } }
                            // Crossfade en vez del `when` directo (informe delight §2.11):
                            // la cuadrícula cambiaba de golpe al navegar de mes/semana. El
                            // rango de fechas y el agrupado de tareas se recalculan DENTRO
                            // del contenido a partir del `targetState` recibido (en vez de
                            // leer `weekRange`/`monthRange`/`tasksByDate` del scope exterior)
                            // para que el slot saliente conserve su propio mes/semana
                            // durante el fundido — si leyera las variables exteriores, ambos
                            // slots (saliente y entrante) mostrarían el mismo mes nuevo y el
                            // cruce no se vería (gotcha de Crossfade con estado mutable
                            // externo). Misma lógica de fechas ya existente
                            // (computeWeekRange/computeMonthGrid/groupTasksByDate), sin
                            // cambios de comportamiento.
                            Crossfade(
                                targetState = mode to anchorDate,
                                animationSpec = tween(durationMillis = if (reduceMotion) 0 else 200)
                            ) { (crossfadeMode, crossfadeAnchor) ->
                                val crossfadeWeekRange = remember(crossfadeAnchor) { computeWeekRange(crossfadeAnchor) }
                                val crossfadeMonthRange = remember(crossfadeAnchor) { computeMonthGrid(crossfadeAnchor) }
                                val crossfadeTasksByDate = remember(listState, crossfadeAnchor, crossfadeMode) {
                                    val tasks = (listState as? TaskListUiState.Success)?.tasks ?: emptyList()
                                    groupTasksByDate(tasks, tz, crossfadeMode, crossfadeWeekRange, crossfadeMonthRange)
                                }
                                when (crossfadeMode) {
                                    CalendarMode.WEEK -> WeekView(
                                        weekRange = crossfadeWeekRange,
                                        tasksByDate = crossfadeTasksByDate,
                                        today = today,
                                        onDayClick = onDayClick
                                    )
                                    CalendarMode.MONTH -> MonthView(
                                        monthGrid = crossfadeMonthRange,
                                        tasksByDate = crossfadeTasksByDate,
                                        today = today,
                                        onDayClick = onDayClick
                                    )
                                }
                            }

                            if (actionState is TaskActionState.Error) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    )
                                ) {
                                    Text(
                                        text = "⚠️ ${(actionState as TaskActionState.Error).message}",
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }

                            PendingWithoutDueDateSection(
                                tasks = pendingWithoutDueDate,
                                isCompleting = actionState is TaskActionState.Loading,
                                s = s,
                                onComplete = remember { { taskId: String -> model.completeTask(householdId, taskId) } },
                                onTaskClick = remember { { taskId: String -> navigator.push(TaskDetailScreen(householdId, taskId)) } }
                            )

                            OverdueSection(
                                tasks = overdueTasks,
                                isCompleting = actionState is TaskActionState.Loading,
                                s = s,
                                onComplete = remember { { taskId: String -> model.completeTask(householdId, taskId) } },
                                onTaskClick = remember { { taskId: String -> navigator.push(TaskDetailScreen(householdId, taskId)) } }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
//  Week view (7 columns: Mon–Sun)
// ────────────────────────────────────────────────────────────

@Composable
private fun WeekView(
    weekRange: List<LocalDate>,
    tasksByDate: Map<LocalDate, List<DayTaskEntry>>,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit
) {
    val lang = LocalAppSettings.current.currentLanguage
    Column(modifier = Modifier.fillMaxSize()) {
        // Day-of-week headers
        Row(modifier = Modifier.fillMaxWidth()) {
            val headers = dayAbbrHeaders(lang)
            headers.forEach { header ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 7 day columns.
        // Modifier.height(IntrinsicSize.Min): sin esto, DayColumn.fillMaxHeight()
        // (dentro) sería un no-op y las 7 columnas no compartirían altura — el
        // resaltado de "hoy" quedaría recortado al contenido en vez de cubrir
        // toda la fila. El scroll ahora lo aporta la Column exterior que envuelve
        // WeekView + la sección "Pendientes" (revisión final 2026-09-17): un
        // verticalScroll propio aquí ya no es necesario y, anidado dentro de otro
        // vertical scroll, es redundante.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            weekRange.forEach { date ->
                val entries = tasksByDate[date] ?: emptyList()
                val isToday = date == today
                DayColumn(
                    modifier = Modifier.weight(1f),
                    date = date,
                    entries = entries,
                    isToday = isToday,
                    onClick = { onDayClick(date) }
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
//  Month view (grid: 6 rows × 7 columns)
// ────────────────────────────────────────────────────────────

@Composable
private fun MonthView(
    monthGrid: List<List<LocalDate?>>,
    tasksByDate: Map<LocalDate, List<DayTaskEntry>>,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit
) {
    val lang = LocalAppSettings.current.currentLanguage
    Column(modifier = Modifier.fillMaxSize()) {
        // Day-of-week headers
        Row(modifier = Modifier.fillMaxWidth()) {
            val headers = dayAbbrHeaders(lang)
            headers.forEach { header ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Week rows. Altura ya acotada (cada fila mide 100.dp fijo, máx. 6 filas):
        // no necesita fillMaxSize()/scroll propio — la Column exterior que
        // envuelve MonthView + la sección "Pendientes" ya aporta el scroll de
        // toda la pantalla (revisión final 2026-09-17); fillMaxSize() aquí, bajo
        // un padre con altura no acotada, rompería el layout.
        Column(modifier = Modifier.fillMaxWidth()) {
            monthGrid.forEach { week ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                ) {
                    week.forEach { date ->
                        if (date != null) {
                            val entries = tasksByDate[date] ?: emptyList()
                            val isToday = date == today
                            MonthDayCell(
                                modifier = Modifier.weight(1f),
                                date = date,
                                entries = entries,
                                isToday = isToday,
                                onClick = { onDayClick(date) }
                            )
                        } else {
                            // Empty cell (padding days from adjacent months)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
//  Day column (week view)
// ────────────────────────────────────────────────────────────

@Composable
private fun DayColumn(
    modifier: Modifier = Modifier,
    date: LocalDate,
    entries: List<DayTaskEntry>,
    isToday: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            // role = Button: semántica estructurada para TalkBack/VoiceOver
            // (panel v7 2026-09-10, Exp. 3, IMPORTANTE).
            .clickable(role = Role.Button, onClick = onClick)
            .then(
                if (isToday) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                else Modifier
            )
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Day number
        DayNumberBadge(day = date.dayOfMonth, isToday = isToday, size = 32.dp, textStyle = MaterialTheme.typography.labelLarge)

        Spacer(Modifier.height(4.dp))

        // Task chips
        entries.take(4).forEach { entry ->
            TaskChip(entry = entry)
            Spacer(Modifier.height(2.dp))
        }

        if (entries.size > 4) {
            Text(
                text = "+${entries.size - 4}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Insignia circular del número de día, compartida por [DayColumn] (vista
 * semana) y [MonthDayCell] (vista mes) — antes duplicada casi literalmente
 * entre ambas, solo variando tamaño y estilo de texto.
 */
@Composable
private fun DayNumberBadge(day: Int, isToday: Boolean, size: Dp, textStyle: TextStyle) {
    Surface(
        shape = CircleShape,
        color = if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "$day",
                style = textStyle,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ────────────────────────────────────────────────────────────
//  Month day cell
// ────────────────────────────────────────────────────────────

@Composable
private fun MonthDayCell(
    modifier: Modifier = Modifier,
    date: LocalDate,
    entries: List<DayTaskEntry>,
    isToday: Boolean,
    onClick: () -> Unit
) {
    val appSettings = LocalAppSettings.current
    // remember(currentLanguage): MonthDayCell se invoca ~35-42 veces por vista
    // mes (una por celda del grid), mismo antipatrón que v13 corrigió en
    // Content() — sin memoizar, cada recomposición del grid recrea la lambda
    // en cada celda.
    val s = remember(appSettings.currentLanguage) { { key: String -> AppStrings.get(key, appSettings.currentLanguage) } }
    val completedCount = entries.count { it.isCompleted }
    val overdueCount = entries.count { it.isOverdue }
    // Los indicadores de estado (puntos de color) son mudos para TalkBack y
    // el número de día por sí solo no comunica cuántas tareas hay ni su
    // estado — se resume aquí en una sola descripción (panel v7 2026-09-10,
    // Exp. 3, IMPORTANTE, NUEVO).
    val cellDescription = buildString {
        append(date.dayOfMonth)
        if (isToday) append(", ${s("calendar_today")}")
        if (entries.isNotEmpty()) {
            append(", ${entries.size}")
            if (completedCount > 0) append(", $completedCount ${s("calendar_task_status_completed")}")
            if (overdueCount > 0) append(", $overdueCount ${s("calendar_task_status_overdue")}")
        } else {
            append(", ${s("calendar_no_tasks_for_day")}")
        }
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            // role = Button + contentDescription resumida: sin esto, TalkBack
            // solo leía el número de día, mudo respecto a las tareas del día
            // (panel v7 2026-09-10, Exp. 3, IMPORTANTE, NUEVO).
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = cellDescription }
            .then(
                if (isToday) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                else Modifier
            )
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Day number
        DayNumberBadge(day = date.dayOfMonth, isToday = isToday, size = 24.dp, textStyle = MaterialTheme.typography.labelSmall)

        // Compact task indicators (dots)
        if (entries.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Show colored dots — max 6
                entries.take(6).forEach { entry ->
                    StatusDot(color = entry.dotColor(), size = 6.dp)
                    Spacer(Modifier.width(1.dp))
                }
                if (entries.size > 6) {
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
//  Task chip (small pill inside day cells)
// ────────────────────────────────────────────────────────────

@Composable
private fun TaskChip(entry: DayTaskEntry) {
    val bgColor = entry.containerColor()
    val textColor = entry.onContainerColor()

    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = entry.task.title,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium
        )
    }
}

// ────────────────────────────────────────────────────────────
//  Day tasks popup dialog
// ────────────────────────────────────────────────────────────

@Composable
private fun DayTasksPopup(
    date: LocalDate,
    tasks: List<DayTaskEntry>,
    onDismiss: () -> Unit,
    onTaskClick: (String) -> Unit
) {
    val appSettings = LocalAppSettings.current
    val s = remember(appSettings.currentLanguage) { { key: String -> AppStrings.get(key, appSettings.currentLanguage) } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 480.dp)
                .fillMaxHeight(0.7f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${dayFull(date.dayOfWeek, appSettings.currentLanguage)} ${date.dayOfMonth}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(s("settings_close"))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (tasks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = s("calendar_no_tasks_for_day"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(tasks, key = { it.task.id }) { entry ->
                            TaskPopupItem(
                                entry = entry,
                                onClick = { onTaskClick(entry.task.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskPopupItem(
    entry: DayTaskEntry,
    onClick: () -> Unit
) {
    val appSettings = LocalAppSettings.current
    val s = remember(appSettings.currentLanguage) { { key: String -> AppStrings.get(key, appSettings.currentLanguage) } }
    val statusColor = entry.dotColor()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // role = Button: semántica estructurada para TalkBack/VoiceOver
            // (panel v7 2026-09-10, Exp. 3, IMPORTANTE).
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator dot
            StatusDot(color = statusColor, size = 12.dp)

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (entry.task.description.isNotBlank()) {
                    Text(
                        text = entry.task.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Status label — colores fijos (0xFF2E7D32/0xFFC62828/Teal600) sobre el
                // fondo de esta card (colorScheme.surfaceVariant, que SÍ cambia con el
                // tema/modo) fallaban WCAG AA en varias combinaciones (hasta 2.47:1 en
                // oscuro). PointsBadge ya usa pares container/onContainer auditados
                // (>=4.5:1 en los 3 temas x claro/oscuro), independientes del fondo.
                val statusLabel = when {
                    entry.isCompleted -> s("calendar_task_status_completed")
                    entry.isOverdue -> s("calendar_task_status_overdue")
                    entry.isDueToday -> s("calendar_task_status_pending")
                    else -> ""
                }
                if (statusLabel.isNotEmpty()) {
                    PointsBadge(
                        text = statusLabel,
                        tone = when {
                            entry.isCompleted -> BadgeTone.Success
                            entry.isOverdue -> BadgeTone.Error
                            else -> BadgeTone.Info
                        }
                    )
                }
            }

            // Points badge
            PointsBadge(
                text = "⭐ ${entry.task.points}",
                tone = BadgeTone.Teal
            )
        }
    }
}

// ────────────────────────────────────────────────────────────
//  "Pendientes" — tareas sin fecha límite y sin recurrencia
// ────────────────────────────────────────────────────────────

/**
 * Lista, debajo de la cuadrícula semana/mes, las tareas pendientes que
 * [isTaskDueOnDay] excluye de TODAS las celdas del calendario por no tener
 * `dueDate` ni recurrencia (ver su KDoc) — para que sigan siendo accesibles
 * en vez de desaparecer.
 */
@Composable
private fun PendingWithoutDueDateSection(
    tasks: List<TaskResponse>,
    isCompleting: Boolean,
    s: (String) -> String,
    onComplete: (String) -> Unit,
    onTaskClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${s("calendar_pending_section")} (${tasks.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() }
        )
        Text(
            text = s("calendar_pending_section_subtitle"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        if (tasks.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = s("calendar_pending_empty"),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tasks.forEach { task ->
                    PendingTaskRow(
                        task = task,
                        isLoading = isCompleting,
                        s = s,
                        onComplete = { onComplete(task.id) },
                        onClick = { onTaskClick(task.id) }
                    )
                }
            }
        }
    }
}

/**
 * Fila de una tarea sin fecha límite: título + puntos + botón "Hecho" (mismo
 * patrón de accesibilidad que `TaskCard` en `TaskListScreen` — customAction
 * "Hecho" en la Card + botón interno oculto con `clearAndSetSemantics`, para
 * que TalkBack/VoiceOver no exponga dos controles interactivos superpuestos
 * en la misma fila clicable). Tocar la fila navega al detalle de la tarea.
 */
@Composable
private fun PendingTaskRow(
    task: TaskResponse,
    isLoading: Boolean,
    s: (String) -> String,
    onComplete: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(s("task_detail_mark_done")) {
                        onComplete()
                        true
                    }
                )
            }
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                PointsBadge(text = "⭐ ${task.points}", tone = BadgeTone.Teal)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onComplete,
                enabled = !isLoading,
                // Oculto para TalkBack/VoiceOver: la acción ya está expuesta como
                // customAction de la Card (evita botón anidado dentro del área
                // clicable). Sigue funcionando al tacto.
                modifier = Modifier.clearAndSetSemantics {}
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
    }
}

// ────────────────────────────────────────────────────────────
//  "Caducadas" — tareas con fecha pasada sin completar
// ────────────────────────────────────────────────────────────

/**
 * Lista, debajo de la sección "Pendientes", las tareas que ya pasaron
 * su fecha (isTaskOverdueOverall) y aún no se han completado. Mismo
 * patrón visual que [PendingWithoutDueDateSection] pero con colores
 * de error (rojo).
 */
@Composable
private fun OverdueSection(
    tasks: List<TaskResponse>,
    isCompleting: Boolean,
    s: (String) -> String,
    onComplete: (String) -> Unit,
    onTaskClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${s("calendar_overdue_section")} (${tasks.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))

        if (tasks.isEmpty()) {
            // Panel v16 (2026-09-24), hallazgo estética: "¡No hay tareas
            // caducadas!" es un mensaje POSITIVO — usar errorContainer (rojo,
            // reservado en el resto de la app para alarma/vencido) contradecía
            // su propia lectura semántica. successContainer, igual que el
            // resto de estados "completado"/positivo de esta pantalla.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.semanticColors.successContainer
                )
            ) {
                Text(
                    text = s("calendar_overdue_empty"),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.semanticColors.onSuccessContainer,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tasks.forEach { task ->
                    OverdueTaskRow(
                        task = task,
                        isLoading = isCompleting,
                        s = s,
                        onComplete = { onComplete(task.id) },
                        onClick = { onTaskClick(task.id) }
                    )
                }
            }
        }
    }
}

/**
 * Fila de una tarea caducada: título + badge "⏰ Caducada" con
 * BadgeTone.Error + puntos. Mismo patrón que [PendingTaskRow] pero
 * con colores de error.
 */
@Composable
private fun OverdueTaskRow(
    task: TaskResponse,
    isLoading: Boolean,
    s: (String) -> String,
    onComplete: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(s("task_detail_mark_done")) {
                        onComplete()
                        true
                    }
                )
            }
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PointsBadge(text = "⭐ ${task.points}", tone = BadgeTone.Teal)
                    Spacer(Modifier.width(6.dp))
                    PointsBadge(text = s("calendar_task_status_overdue"), tone = BadgeTone.Error)
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onComplete,
                enabled = !isLoading,
                modifier = Modifier.clearAndSetSemantics {}
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(s("task_detail_mark_done"))
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
//  Date calculations
// ────────────────────────────────────────────────────────────

/** Get Monday of the week containing [date]. */
private fun getMondayOfWeek(date: LocalDate): LocalDate {
    val dayOfWeek = date.dayOfWeek.ordinal // 0=Monday
    return date.minus(dayOfWeek, DateTimeUnit.DAY)
}

/** Compute 7 days (Mon–Sun) for the week containing [anchorDate]. */
private fun computeWeekRange(anchorDate: LocalDate): List<LocalDate> {
    val monday = getMondayOfWeek(anchorDate)
    return (0..6).map { monday.plus(it, DateTimeUnit.DAY) }
}

/** Compute a 6×7 grid for the month of [anchorDate]. Null = padding cell. */
private fun computeMonthGrid(anchorDate: LocalDate): List<List<LocalDate?>> {
    val firstOfMonth = LocalDate(anchorDate.year, anchorDate.month, 1)
    val daysInMonth = when (anchorDate.month) {
        Month.JANUARY, Month.MARCH, Month.MAY, Month.JULY,
        Month.AUGUST, Month.OCTOBER, Month.DECEMBER -> 31
        Month.APRIL, Month.JUNE, Month.SEPTEMBER, Month.NOVEMBER -> 30
        Month.FEBRUARY -> if (anchorDate.year % 4 == 0 && (anchorDate.year % 100 != 0 || anchorDate.year % 400 == 0)) 29 else 28
    }

    // Offset: Monday=0 ... Sunday=6
    val startOffset = firstOfMonth.dayOfWeek.ordinal // 0=Monday
    val allDays = (0 until daysInMonth).map { firstOfMonth.plus(it, DateTimeUnit.DAY) }

    val grid = mutableListOf<List<LocalDate?>>()
    var dayIndex = 0
    var totalCells = 0

    // Row 1: padding + first days
    val row1 = mutableListOf<LocalDate?>()
    repeat(startOffset) { row1.add(null); totalCells++ }
    while (dayIndex < allDays.size && row1.size < 7) {
        row1.add(allDays[dayIndex])
        dayIndex++
        totalCells++
    }
    grid.add(row1)

    // Subsequent rows
    while (dayIndex < allDays.size) {
        val row = mutableListOf<LocalDate?>()
        repeat(7) {
            if (dayIndex < allDays.size) {
                row.add(allDays[dayIndex])
                dayIndex++
                totalCells++
            } else {
                row.add(null)
                totalCells++
            }
        }
        grid.add(row)
    }

    return grid
}

// ────────────────────────────────────────────────────────────
//  isTaskDueOnDay — calendar variant of isTaskDueToday
// ────────────────────────────────────────────────────────────

/**
 * Determines if a task is due on a specific [date].
 *
 * daily/weekly/monthly delegan en [RecurrenceRules.isDueOn] — antes esta
 * función reimplementaba esa misma lógica de recurrencia (con riesgo real de
 * divergencia respecto a la regla usada en `TaskListScreen`/`HomeScreenModel`).
 * "once" se queda fuera de [RecurrenceRules]: a diferencia de
 * `isDueToday`/`isDueOn` (que solo miran si está pendiente, ignorando
 * `dueDate`), el calendario necesita saber en qué celda de día concreto cae
 * la tarea, así que sí usa `dueDate` — un propósito distinto, no una
 * duplicación de la misma regla.
 *
 * Una tarea "once" pendiente SIN `dueDate` no tiene ningún día concreto que
 * mostrar: antes se marcaba como "due" en TODAS las celdas (bug reportado
 * 2026-09-17), inundando el calendario. Ahora se excluye de la cuadrícula por
 * completo — [isTaskPendingWithoutDueDate] la recoge para listarla aparte, en
 * la sección "Pendientes" bajo el calendario (ver `PendingWithoutDueDateSection`
 * en `CalendarScreen.Content`). Las tareas recurrentes (daily/weekly/monthly)
 * tampoco tienen `dueDate`, pero esas NO pasan por esta rama — siguen su regla
 * de recurrencia normal vía [RecurrenceRules.isDueOn], sin cambios.
 */
internal fun isTaskDueOnDay(task: TaskResponse, date: LocalDate, tz: TimeZone): Boolean {
    if (task.frequency == "once") {
        // Due if not completed yet
        if (task.lastCompletedDate != null) return false
        if (task.dueDate > 0) {
            val dueDate = Instant.fromEpochMilliseconds(task.dueDate).toLocalDateTime(tz).date
            return date == dueDate
        }
        // Sin dueDate y sin completar: fuera del calendario, ver KDoc de arriba.
        return false
    }
    return RecurrenceRules.isDueOn(
        date = date,
        frequency = task.frequency,
        recurrenceDays = task.recurrenceDays,
        recurrenceDay = task.recurrenceDay,
        lastCompletedDate = task.lastCompletedDate,
        tz = tz,
        createdAt = task.createdAt
)
}

/**
 * Tareas pendientes sin ningún día propio en el calendario: "once", sin
 * `dueDate` y aún no completadas — el mismo conjunto que [isTaskDueOnDay]
 * excluye de todas las celdas. Se listan en la sección "Pendientes" debajo
 * del calendario en vez de perderse.
 */
internal fun isTaskPendingWithoutDueDate(task: TaskResponse): Boolean =
    task.frequency == "once" && task.dueDate <= 0 && task.lastCompletedDate == null

/**
 * Tareas con una ocurrencia pendiente ya vencida: se listan en la sección
 * "Caducadas" debajo del calendario. Cubre "once" con [TaskResponse.dueDate]
 * pasado y sin completar, y también recurrentes (daily/weekly/monthly, R18,
 * 2026-09-24) con [TaskResponse.nextDueAt] pasado — ese campo siempre
 * representa la ocurrencia pendiente actual (se recalcula a la SIGUIENTE
 * cada vez que se completa, ver su KDoc en DTOs.kt), así que basta con que
 * su fecha sea anterior a [today]; no hace falta mirar [TaskResponse.lastCompletedDate]
 * aparte. `null` (recurrente creada antes de que existiera este campo) no se
 * considera vencida — mismo fallback que ya usa el resto del cálculo de
 * penalización en el cliente.
 */
internal fun isTaskOverdueOverall(task: TaskResponse, today: LocalDate, tz: TimeZone): Boolean = when (task.frequency) {
    "once" -> task.dueDate > 0 &&
        Instant.fromEpochMilliseconds(task.dueDate).toLocalDateTime(tz).date < today &&
        task.lastCompletedDate == null
    "daily", "weekly", "monthly" -> task.nextDueAt?.let {
        Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).date < today
    } ?: false
    else -> false
}

/**
 * Check if a task was completed on a specific date.
 */
private fun isTaskCompletedOnDay(task: TaskResponse, date: LocalDate, tz: TimeZone): Boolean {
    val lcd = task.lastCompletedDate ?: return false
    val lcdDate = Instant.fromEpochMilliseconds(lcd).toLocalDateTime(tz).date
    return lcdDate == date
}

/**
 * Check if a task is overdue relative to a specific date.
 * Overdue = task.dueDate has passed and task didn't get completed on/before the due date.
 * Only applies to "once" tasks with an explicit dueDate — recurring tasks
 * (daily/weekly/monthly) have no dueDate and are shown as due (not overdue)
 * on each day cell where they're pending, see isTaskDueOnDay.
 */
private fun isTaskOverdueOnDay(task: TaskResponse, date: LocalDate, tz: TimeZone): Boolean {
    // For "once" tasks with a dueDate
    if (task.frequency == "once" && task.dueDate > 0) {
        val dueDate = Instant.fromEpochMilliseconds(task.dueDate).toLocalDateTime(tz).date
        if (date > dueDate && task.lastCompletedDate == null) return true
        // If completed, check if it was completed after the due date
        val lcd = task.lastCompletedDate
        if (lcd != null) {
            val lcdDate = Instant.fromEpochMilliseconds(lcd).toLocalDateTime(tz).date
            if (lcdDate > dueDate) return true
        }
    }
    return false
}

// ────────────────────────────────────────────────────────────
//  Group tasks by date for the calendar
// ────────────────────────────────────────────────────────────

internal fun groupTasksByDate(
    tasks: List<TaskResponse>,
    tz: TimeZone,
    mode: CalendarMode,
    weekRange: List<LocalDate>,
    monthGrid: List<List<LocalDate?>>
): Map<LocalDate, List<DayTaskEntry>> {
    val result = mutableMapOf<LocalDate, MutableList<DayTaskEntry>>()

    val datesToCheck = when (mode) {
        CalendarMode.WEEK -> weekRange
        CalendarMode.MONTH -> monthGrid.flatten().filterNotNull()
    }

    for (date in datesToCheck) {
        for (task in tasks) {
            val isDue = isTaskDueOnDay(task, date, tz)
            val isCompleted = isTaskCompletedOnDay(task, date, tz)
            val isOverdue = isTaskOverdueOnDay(task, date, tz)

            if (isDue || isCompleted) {
                result.getOrPut(date) { mutableListOf() }.add(
                    DayTaskEntry(
                        task = task,
                        isOverdue = isOverdue && !isCompleted,
                        isDueToday = isDue && !isCompleted,
                        isCompleted = isCompleted
                    )
                )
            }
        }
    }

    return result
}

// ────────────────────────────────────────────────────────────
//  Localized date name helpers
// ────────────────────────────────────────────────────────────

private fun dayAbbrHeaders(lang: String): List<String> = listOf(
    AppStrings.get("day_abbr_monday", lang),
    AppStrings.get("day_abbr_tuesday", lang),
    AppStrings.get("day_abbr_wednesday", lang),
    AppStrings.get("day_abbr_thursday", lang),
    AppStrings.get("day_abbr_friday", lang),
    AppStrings.get("day_abbr_saturday", lang),
    AppStrings.get("day_abbr_sunday", lang)
)

private fun dayFull(dayOfWeek: DayOfWeek, lang: String): String = when (dayOfWeek) {
    DayOfWeek.MONDAY -> AppStrings.get("recurrence_day_monday", lang)
    DayOfWeek.TUESDAY -> AppStrings.get("recurrence_day_tuesday", lang)
    DayOfWeek.WEDNESDAY -> AppStrings.get("recurrence_day_wednesday", lang)
    DayOfWeek.THURSDAY -> AppStrings.get("recurrence_day_thursday", lang)
    DayOfWeek.FRIDAY -> AppStrings.get("recurrence_day_friday", lang)
    DayOfWeek.SATURDAY -> AppStrings.get("recurrence_day_saturday", lang)
    DayOfWeek.SUNDAY -> AppStrings.get("recurrence_day_sunday", lang)
    else -> ""
}

private fun monthFull(month: Month, lang: String): String = when (month) {
    Month.JANUARY -> AppStrings.get("month_full_january", lang)
    Month.FEBRUARY -> AppStrings.get("month_full_february", lang)
    Month.MARCH -> AppStrings.get("month_full_march", lang)
    Month.APRIL -> AppStrings.get("month_full_april", lang)
    Month.MAY -> AppStrings.get("month_full_may", lang)
    Month.JUNE -> AppStrings.get("month_full_june", lang)
    Month.JULY -> AppStrings.get("month_full_july", lang)
    Month.AUGUST -> AppStrings.get("month_full_august", lang)
    Month.SEPTEMBER -> AppStrings.get("month_full_september", lang)
    Month.OCTOBER -> AppStrings.get("month_full_october", lang)
    Month.NOVEMBER -> AppStrings.get("month_full_november", lang)
    Month.DECEMBER -> AppStrings.get("month_full_december", lang)
    else -> ""
}