// Ilustraciones geométricas dibujadas con Canvas para estados vacíos
// (sin tareas / sin hogares), sin depender de imágenes/assets.
package org.taskhub.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.taskhub.ui.theme.semanticColors

/**
 * Ilustración geométrica para el estado vacío "sin tareas": un checkmark
 * grande con confeti alrededor, en los colores de marca + éxito.
 */
@Composable
fun EmptyTasksIllustration(modifier: Modifier = Modifier) {
    val successColor = MaterialTheme.semanticColors.success
    // Colores del tema (no literales Teal*/Coral*) para que la ilustración
    // se adapte a los 6 themes (DEFAULT, NATURALEZA, MINIMAL, OCEANO, ATARDECER,
    // MEDIANOCHE) en vez de
    // quedar siempre teal/coral pase lo que elija el usuario en Ajustes —
    // mismo criterio ya aplicado en PointsBadge.badgeToneColors.
    val bgCircleColor = MaterialTheme.colorScheme.primaryContainer
    val confettiPrimary = MaterialTheme.colorScheme.primary
    val confettiSecondary = MaterialTheme.colorScheme.secondary
    val confettiTertiary = MaterialTheme.colorScheme.tertiary
    val confettiTertiaryContainer = MaterialTheme.colorScheme.tertiaryContainer
    Canvas(modifier = modifier.size(120.dp)) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val radius = size.minDimension * 0.34f

        // Círculo de fondo
        drawCircle(color = bgCircleColor.copy(alpha = 0.4f), radius = radius * 1.35f, center = center)

        // Anillo de éxito
        drawCircle(
            color = successColor,
            radius = radius,
            center = center,
            style = Stroke(width = size.minDimension * 0.07f)
        )

        // Checkmark
        val check = Path().apply {
            moveTo(w * 0.34f, h * 0.52f)
            lineTo(w * 0.46f, h * 0.64f)
            lineTo(w * 0.68f, h * 0.38f)
        }
        drawPath(
            path = check,
            color = successColor,
            style = Stroke(
                width = size.minDimension * 0.09f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Confeti: pequeños cuadrados/círculos dispersos alrededor del anillo
        val confetti = listOf(
            Triple(Offset(w * 0.12f, h * 0.18f), confettiTertiary, false),
            Triple(Offset(w * 0.88f, h * 0.22f), confettiPrimary, true),
            Triple(Offset(w * 0.82f, h * 0.82f), confettiTertiaryContainer, false),
            Triple(Offset(w * 0.14f, h * 0.80f), confettiSecondary, true),
            Triple(Offset(w * 0.90f, h * 0.55f), confettiTertiary, true),
        )
        confetti.forEach { (offset, color, isCircle) ->
            if (isCircle) {
                drawCircle(color = color, radius = size.minDimension * 0.035f, center = offset)
            } else {
                val s = size.minDimension * 0.06f
                drawRect(
                    color = color,
                    topLeft = Offset(offset.x - s / 2f, offset.y - s / 2f),
                    size = androidx.compose.ui.geometry.Size(s, s)
                )
            }
        }
    }
}

/**
 * Ilustración geométrica para el estado vacío "sin hogares": una casita
 * sencilla en los colores de marca teal/coral.
 */
@Composable
fun EmptyHouseholdsIllustration(modifier: Modifier = Modifier) {
    // Colores del tema (no literales Teal*/Coral*) — mismo criterio que
    // [EmptyTasksIllustration], para que la casita se adapte a los 6 themes.
    val bgCircleColor = MaterialTheme.colorScheme.primaryContainer
    val roofColor = MaterialTheme.colorScheme.tertiary
    val bodyColor = MaterialTheme.colorScheme.secondary
    val doorColor = MaterialTheme.colorScheme.primaryContainer
    Canvas(modifier = modifier.size(120.dp)) {
        val w = size.width
        val h = size.height

        // Círculo de fondo
        drawCircle(
            color = bgCircleColor.copy(alpha = 0.4f),
            radius = size.minDimension * 0.46f,
            center = Offset(w / 2f, h / 2f)
        )

        // Tejado (triángulo)
        val roof = Path().apply {
            moveTo(w * 0.22f, h * 0.52f)
            lineTo(w * 0.50f, h * 0.26f)
            lineTo(w * 0.78f, h * 0.52f)
            close()
        }
        drawPath(path = roof, color = roofColor)

        // Cuerpo de la casa
        drawRect(
            color = bodyColor,
            topLeft = Offset(w * 0.30f, h * 0.52f),
            size = androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.28f)
        )

        // Puerta
        drawRect(
            color = doorColor,
            topLeft = Offset(w * 0.45f, h * 0.60f),
            size = androidx.compose.ui.geometry.Size(w * 0.10f, h * 0.20f)
        )
    }
}

/**
 * Ilustración geométrica para el estado vacío "sin recompensas": una caja de
 * regalo con lazo, en los colores de marca. Sustituye al emoji 🎁 suelto de
 * [org.taskhub.ui.screens.RewardsBody] (informe delight #5).
 */
@Composable
fun EmptyRewardsIllustration(modifier: Modifier = Modifier) {
    val bgCircleColor = MaterialTheme.colorScheme.primaryContainer
    val boxColor = MaterialTheme.colorScheme.tertiary
    val ribbonColor = MaterialTheme.colorScheme.tertiaryContainer
    val bowColor = MaterialTheme.colorScheme.secondary
    Canvas(modifier = modifier.size(120.dp)) {
        val w = size.width
        val h = size.height

        drawCircle(
            color = bgCircleColor.copy(alpha = 0.4f),
            radius = size.minDimension * 0.46f,
            center = Offset(w / 2f, h / 2f)
        )

        // Cuerpo de la caja
        drawRect(
            color = boxColor,
            topLeft = Offset(w * 0.26f, h * 0.44f),
            size = androidx.compose.ui.geometry.Size(w * 0.48f, h * 0.32f)
        )

        // Tapa
        drawRect(
            color = boxColor,
            topLeft = Offset(w * 0.22f, h * 0.38f),
            size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.08f)
        )

        // Cinta vertical
        drawRect(
            color = ribbonColor,
            topLeft = Offset(w * 0.47f, h * 0.38f),
            size = androidx.compose.ui.geometry.Size(w * 0.06f, h * 0.38f)
        )

        // Lazo (dos triángulos)
        val bowLeft = Path().apply {
            moveTo(w * 0.50f, h * 0.38f)
            lineTo(w * 0.32f, h * 0.24f)
            lineTo(w * 0.42f, h * 0.38f)
            close()
        }
        val bowRight = Path().apply {
            moveTo(w * 0.50f, h * 0.38f)
            lineTo(w * 0.68f, h * 0.24f)
            lineTo(w * 0.58f, h * 0.38f)
            close()
        }
        drawPath(path = bowLeft, color = bowColor)
        drawPath(path = bowRight, color = bowColor)
    }
}

/**
 * Ilustración geométrica para el estado vacío "sin notificaciones": una
 * campana con líneas de "silencio" alrededor. Sustituye al emoji 🔕 suelto de
 * [org.taskhub.ui.screens.NotificationListScreen] (informe delight #5).
 */
@Composable
fun EmptyNotificationsIllustration(modifier: Modifier = Modifier) {
    val bgCircleColor = MaterialTheme.colorScheme.primaryContainer
    val bellColor = MaterialTheme.colorScheme.primary
    val clapperColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = modifier.size(120.dp)) {
        val w = size.width
        val h = size.height

        drawCircle(
            color = bgCircleColor.copy(alpha = 0.4f),
            radius = size.minDimension * 0.46f,
            center = Offset(w / 2f, h / 2f)
        )

        // Cuerpo de la campana
        val bell = Path().apply {
            moveTo(w * 0.50f, h * 0.24f)
            cubicTo(w * 0.32f, h * 0.24f, w * 0.30f, h * 0.42f, w * 0.30f, h * 0.56f)
            lineTo(w * 0.24f, h * 0.68f)
            lineTo(w * 0.76f, h * 0.68f)
            lineTo(w * 0.70f, h * 0.56f)
            cubicTo(w * 0.70f, h * 0.42f, w * 0.68f, h * 0.24f, w * 0.50f, h * 0.24f)
            close()
        }
        drawPath(path = bell, color = bellColor)

        // Badajo
        drawCircle(color = clapperColor, radius = size.minDimension * 0.045f, center = Offset(w * 0.50f, h * 0.74f))
    }
}

/**
 * Ilustración geométrica para el estado vacío "sin ranking": un podio de 3
 * escalones. Sustituye al emoji 🏆 suelto de [org.taskhub.ui.screens.RankingBody]
 * (informe delight #5).
 */
@Composable
fun EmptyRankingIllustration(modifier: Modifier = Modifier) {
    val bgCircleColor = MaterialTheme.colorScheme.primaryContainer
    val firstColor = MaterialTheme.colorScheme.tertiary
    val secondColor = MaterialTheme.colorScheme.primaryContainer
    val thirdColor = MaterialTheme.colorScheme.secondaryContainer
    Canvas(modifier = modifier.size(120.dp)) {
        val w = size.width
        val h = size.height

        drawCircle(
            color = bgCircleColor.copy(alpha = 0.4f),
            radius = size.minDimension * 0.46f,
            center = Offset(w / 2f, h / 2f)
        )

        // Escalón 2º puesto (izquierda)
        drawRect(
            color = secondColor,
            topLeft = Offset(w * 0.16f, h * 0.52f),
            size = androidx.compose.ui.geometry.Size(w * 0.22f, h * 0.24f)
        )
        // Escalón 1er puesto (centro, más alto)
        drawRect(
            color = firstColor,
            topLeft = Offset(w * 0.39f, h * 0.38f),
            size = androidx.compose.ui.geometry.Size(w * 0.22f, h * 0.38f)
        )
        // Escalón 3er puesto (derecha)
        drawRect(
            color = thirdColor,
            topLeft = Offset(w * 0.62f, h * 0.60f),
            size = androidx.compose.ui.geometry.Size(w * 0.22f, h * 0.16f)
        )
    }
}
