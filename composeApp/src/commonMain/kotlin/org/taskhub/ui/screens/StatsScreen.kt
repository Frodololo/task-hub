/**
 * Contenido de la pestaña de estadísticas de un miembro (rachas, gráficas de
 * tareas/puntos por día, distribución por etiqueta y logros). No es un
 * `Screen` propio: se embebe en la pantalla combinada del hogar, comparte el
 * [org.taskhub.ui.models.StatsScreenModel] creado por el contenedor y dibuja
 * las gráficas a mano con `Canvas` (sin librería de gráficos externa).
 */
package org.taskhub.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.*
import org.taskhub.ui.components.EffectCategory
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.StatusDot
import org.taskhub.ui.components.ShimmerList
import org.taskhub.ui.components.StatChip
import org.taskhub.ui.components.effectsEnabled
import org.taskhub.ui.components.streakFireFontSize
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.Achievement
import org.taskhub.ui.models.DayCount
import org.taskhub.ui.models.DayPoints
import org.taskhub.ui.models.StatsScreenModel
import org.taskhub.ui.models.StatsUiState
import org.taskhub.ui.models.TagCount
import org.taskhub.ui.theme.*

/**
 * Contenido reutilizable de estadísticas (sin barra superior), para la
 * pantalla combinada. [statsModel] se crea en [ExploreScreen] (que sí es un
 * `Screen` y puede usar `koinScreenModel`) y se pasa aquí como parámetro,
 * igual que ya hacían [RewardsBody]/[RankingBody] con su `MemberScreenModel`.
 */
@Composable
internal fun StatsBody(householdId: String, memberId: String, statsModel: StatsScreenModel) {
    val appSettings = LocalAppSettings.current
    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

    val uiState by statsModel.uiState.collectAsState()

    LaunchedEffect(householdId, memberId) {
        statsModel.loadStats(householdId, memberId, appSettings.currentLanguage)
    }

    when {
                    uiState is StatsUiState.Loading || uiState is StatsUiState.Idle -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                        ) {
                            ShimmerList(count = 4, itemHeight = 88.dp)
                        }
                    }
                    uiState is StatsUiState.Error -> {
                        val errorMessage = (uiState as StatsUiState.Error).message
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
                                        errorMessage,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { statsModel.loadStats(householdId, memberId, appSettings.currentLanguage) }) { Text(s("tasks_retry")) }
                            }
                        }
                    }
                    uiState is StatsUiState.Success -> {
                        val successState = uiState as StatsUiState.Success
                        val data = successState.data
                        val achievements = successState.achievements
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Streak card
                            item {
                                StreakCard(
                                    currentStreak = data.currentStreak,
                                    bestStreak = data.bestStreak
                                )
                            }

                            // Bar chart: tasks per day
                            item {
                                BarChartCard(
                                    title = s("stats_chart_tasks_title"),
                                    data = data.tasksPerDay
                                )
                            }

                            // Points chart
                            item {
                                PointsChartCard(
                                    title = s("stats_chart_points_title"),
                                    dailyPoints = data.dailyPoints
                                )
                            }

                            // Pie chart: distribution by category/tag
                            if (data.tasksByTag.isNotEmpty()) {
                                item {
                                    PieChartCard(
                                        title = s("stats_chart_category_title"),
                                        data = data.tasksByTag
                                    )
                                }
                            }

                            // Summary stats
                            item {
                                SummaryStatsCard(
                                    totalTasks = data.totalTasksCompleted,
                                    totalPoints = data.totalPoints,
                                    onTimeRate = data.onTimeRate,
                                    overdueCount = data.overdueCount
                                )
                            }

                            // Achievements
                            if (achievements.isNotEmpty()) {
                                item {
                                    Text(
                                                            text = s("stats_achievements_title"),
                                                            style = MaterialTheme.typography.titleLarge,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier
                                                                .padding(top = 8.dp)
                                                                .semantics { heading() }
                                                        )
                                }

                                items(achievements, key = { it.id }) { achievement ->
                                    AchievementCard(achievement)
                                }
                            }

                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }
}

// ── UI Components ──────────────────────────────────────────

/** Tarjeta con racha actual y mejor racha (en días consecutivos). */
@Composable
private fun StreakCard(currentStreak: Int, bestStreak: Int) {
    val appSettings = LocalAppSettings.current
    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Escalado estático por tramo de racha (informe delight #8,
                // aprobado con toggle) — sin animación, solo if/else.
                Text("🔥", fontSize = streakFireFontSize(currentStreak))
                Text(
                    s("stats_current_streak_label"),
                    style = MaterialTheme.typography.bodySmall,
                    // onTertiaryContainer sólido (par accesible auditado, sigue el
                    // tema activo) en vez del antiguo Coral800/Coral700/Teal800
                    // fijos. Sin alpha: con .copy(alpha=0.8f) caía por debajo de
                    // 4.5:1 en 3/6 combinaciones tema/modo (Default oscuro 3.43:1,
                    // Naturaleza claro 3.22:1, Naturaleza oscuro 3.56:1).
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    s("stats_days_suffix").replace("%d", currentStreak.toString()),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🏆", style = MaterialTheme.typography.displaySmall)
                Text(
                    s("stats_best_streak_label"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    s("stats_days_suffix").replace("%d", bestStreak.toString()),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

/**
 * Progreso de entrada (0→1) compartido por las 3 gráficas `Canvas` de esta
 * pantalla: una sola pasada (`Animatable(0f)` → `1f`, `tween(600)`) disparada
 * en un `LaunchedEffect(Unit)` la primera vez que el composable que la llama
 * entra en composición — no se repite en recomposiciones por refresco de
 * datos (sin claves en `remember`/`LaunchedEffect`). Con animaciones
 * desactivadas ([effectsEnabled] ya cubre modo simple, interruptor de
 * categoría y `shouldReduceMotion`) devuelve `1f` directo.
 */
@Composable
private fun rememberChartEntranceProgress(): Float {
    val animationsOn = effectsEnabled(EffectCategory.ANIMATIONS)
    val progress = remember { Animatable(if (animationsOn) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animationsOn) {
            progress.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
        }
    }
    return progress.value
}

/** Wrapper común de las tarjetas de gráfica: `Card` + título en negrita + separador. */
@Composable
private fun ChartCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Gráfica de barras (tareas completadas por día) dibujada con [Canvas]. */
@Composable
private fun BarChartCard(title: String, data: List<DayCount>) {
    ChartCard(title) {
        val entranceProgress = rememberChartEntranceProgress()
        val maxCount = (data.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
        val barColor = MaterialTheme.colorScheme.primary
        val textMeasurer = rememberTextMeasurer()
        val labelTextStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Los Canvas de esta pantalla no tienen ningún texto alternativo:
        // para un lector de pantalla, las tres tarjetas de estadísticas son
        // invisibles/mudas sin esto.
        val chartDescription = remember(data) {
            data.joinToString(", ") { "${it.dayLabel}: ${it.count}" }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = "$title. $chartDescription" }
        ) {
            val chartWidth = size.width
            val chartHeight = size.height - 30f
            val barCount = data.size
            if (barCount == 0) return@Canvas

            val barWidth = (chartWidth / barCount) * 0.6f
            val gap = (chartWidth / barCount) * 0.4f

            data.forEachIndexed { index, dayCount ->
                val barHeight = (if (maxCount > 0) (dayCount.count.toFloat() / maxCount) * chartHeight else 0f) * entranceProgress
                val x = index * (barWidth + gap) + gap / 2

                // Bar
                drawRect(
                    color = barColor,
                    topLeft = Offset(x, chartHeight - barHeight),
                    size = Size(barWidth, barHeight.coerceAtLeast(2f))
                )

                // Count on top
                if (dayCount.count > 0) {
                    val textLayout = textMeasurer.measure(
                        "${dayCount.count}",
                        labelTextStyle
                    )
                    drawText(
                        textLayout,
                        topLeft = Offset(
                            x + barWidth / 2 - textLayout.size.width / 2,
                            chartHeight - barHeight - textLayout.size.height - 4f
                        )
                    )
                }

                // Label
                val labelLayout = textMeasurer.measure(
                    dayCount.dayLabel,
                    labelTextStyle
                )
                drawText(
                    labelLayout,
                    topLeft = Offset(
                        x + barWidth / 2 - labelLayout.size.width / 2,
                        chartHeight + 5f
                    )
                )
            }
        }
    }
}

/** Gráfica de línea (puntos ganados por día) dibujada con [Canvas]. */
@Composable
private fun PointsChartCard(title: String, dailyPoints: List<DayPoints>) {
    ChartCard(title) {
        val entranceProgress = rememberChartEntranceProgress()
        val maxPoints = (dailyPoints.maxOfOrNull { it.points } ?: 10).coerceAtLeast(1)
        val textMeasurer = rememberTextMeasurer()
        val lineColor = MaterialTheme.colorScheme.tertiary
        val pointColor = MaterialTheme.colorScheme.tertiary
        // "Agujero" del punto: antes Color.White fijo, se veía como un
        // punto blanco incrustado sobre la card oscura en modo oscuro
        // (panel v7 2026-09-10, Exp. 1/4, MENOR, SIGUE ABIERTO).
        val surfaceColor = MaterialTheme.colorScheme.surface
        val labelTextStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        val chartDescription = remember(dailyPoints) {
            dailyPoints.joinToString(", ") { "${it.dayLabel}: ${it.points}" }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = "$title. $chartDescription" }
        ) {
            val chartWidth = size.width
            val chartHeight = size.height - 30f
            val padding = 20f
            val usableWidth = chartWidth - padding * 2
            val usableHeight = chartHeight

            if (dailyPoints.isEmpty()) return@Canvas

            val points = dailyPoints.mapIndexed { index, dp ->
                val x = padding + (index.toFloat() / (dailyPoints.size - 1).coerceAtLeast(1)) * usableWidth
                val y = usableHeight - (dp.points.toFloat() / maxPoints) * usableHeight
                Offset(x, y)
            }

            // Entrada: revela la polyline de izquierda a derecha en una sola
            // pasada. `fullyVisible` puntos ya están dibujados del todo;
            // `partial` interpola el segmento hacia el siguiente punto aún
            // oculto para que el trazo avance de forma continua en vez de a
            // saltos discretos entre puntos.
            val exactVisible = points.size * entranceProgress
            val fullyVisible = exactVisible.toInt().coerceIn(0, points.size)
            val partial = exactVisible - fullyVisible

            // Draw line (segmentos completos)
            for (i in 0 until (fullyVisible - 1).coerceAtLeast(0)) {
                drawLine(
                    color = lineColor,
                    start = points[i],
                    end = points[i + 1],
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
            // Segmento parcial hacia el siguiente punto oculto
            if (fullyVisible in 1 until points.size) {
                val start = points[fullyVisible - 1]
                val end = points[fullyVisible]
                val interpolated = Offset(
                    start.x + (end.x - start.x) * partial,
                    start.y + (end.y - start.y) * partial
                )
                drawLine(
                    color = lineColor,
                    start = start,
                    end = interpolated,
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }

            // Draw points (solo los ya totalmente revelados)
            points.take(fullyVisible).forEach { point ->
                drawCircle(color = pointColor, radius = 5f, center = point)
                drawCircle(color = surfaceColor, radius = 3f, center = point)
            }

            // Labels
            dailyPoints.forEachIndexed { index, dp ->
                val labelLayout = textMeasurer.measure(
                    dp.dayLabel,
                    labelTextStyle
                )
                val x = padding + (index.toFloat() / (dailyPoints.size - 1).coerceAtLeast(1)) * usableWidth
                drawText(
                    labelLayout,
                    topLeft = Offset(
                        x - labelLayout.size.width / 2,
                        chartHeight + 5f
                    )
                )
            }
        }
    }
}

/** Gráfica circular (distribución de tareas por etiqueta) con leyenda. */
@Composable
private fun PieChartCard(title: String, data: List<TagCount>) {
    ChartCard(title) {
        val entranceProgress = rememberChartEntranceProgress()
        // Paleta categórica de 6 tonos, uno por rol de MaterialTheme.colorScheme:
        // antes 6 hex fijos (Teal/Coral), iguales en los 3 temas — ahora sigue
        // el tema activo (Naturaleza/Minimal) manteniendo 6 tonos distinguibles.
        val colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.secondaryContainer
        )
        val total = data.sumOf { it.count }.toFloat().coerceAtLeast(1f)
        val chartDescription = remember(data) {
            data.joinToString(", ") { "${it.tag}: ${it.count}" }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pie chart
            Canvas(
                modifier = Modifier
                    .size(140.dp)
                    .semantics { contentDescription = "$title. $chartDescription" }
            ) {
                var startAngle = -90f
                data.forEachIndexed { index, tagCount ->
                    val sweep = (tagCount.count.toFloat() / total) * 360f
                    drawArc(
                        color = colors[index % colors.size],
                        startAngle = startAngle,
                        sweepAngle = sweep * entranceProgress,
                        useCenter = true,
                        size = Size(size.width, size.height)
                    )
                    startAngle += sweep
                }
            }

            Spacer(Modifier.width(16.dp))

            // Legend — Modifier.weight(1f) para que la columna se ajuste al
            // ancho disponible en vez de poder desbordar la card con etiquetas
            // largas (frecuente en español); TextOverflow.Ellipsis para que el
            // texto se corte con "…" en vez de a mitad de carácter.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                data.forEachIndexed { index, tagCount ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color = colors[index % colors.size], size = 12.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${tagCount.tag} (${tagCount.count})",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Tarjeta resumen con totales: tareas, puntos, % a tiempo y vencidas. */
@Composable
private fun SummaryStatsCard(
    totalTasks: Int,
    totalPoints: Int,
    onTimeRate: Float,
    overdueCount: Int
) {
    val appSettings = LocalAppSettings.current
    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                s("stats_summary_title"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatChip(label = s("stats_summary_tasks"), value = "$totalTasks", emoji = "✅", tone = null)
                StatChip(label = s("stats_summary_points"), value = "$totalPoints", emoji = "⭐", tone = null)
                StatChip(label = s("stats_summary_on_time"), value = "${(onTimeRate * 100).toInt()}%", emoji = "⏱️", tone = null)
                StatChip(label = s("stats_summary_overdue"), value = "$overdueCount", emoji = "⚠️", tone = null)
            }
        }
    }
}

/**
 * Tarjeta de un logro; atenuada y con candado si aún no está desbloqueado.
 * Entrada única (fade+scale) la primera vez que [achievement] se observa
 * desbloqueado en esta composición (§2.8 del informe de delight) — no se
 * repite en recomposiciones posteriores mientras siga desbloqueado.
 */
@Composable
private fun AchievementCard(achievement: Achievement) {
    val lang = LocalAppSettings.current.currentLanguage
    val animationsOn = effectsEnabled(EffectCategory.ANIMATIONS)
    val entrance = remember(achievement.id, achievement.isUnlocked) {
        Animatable(if (achievement.isUnlocked) 0f else 1f)
    }
    LaunchedEffect(achievement.id, achievement.isUnlocked) {
        if (achievement.isUnlocked) {
            if (animationsOn) {
                entrance.snapTo(0f)
                entrance.animateTo(1f, tween(300))
            } else {
                entrance.snapTo(1f)
            }
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val scale = 0.92f + 0.08f * entrance.value
                scaleX = scale
                scaleY = scale
                alpha = entrance.value
            },
        colors = CardDefaults.cardColors(
            containerColor = if (achievement.isUnlocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (achievement.isUnlocked) achievement.emoji else "🔒",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = achievement.title(lang),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (achievement.isUnlocked) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = achievement.description(lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}