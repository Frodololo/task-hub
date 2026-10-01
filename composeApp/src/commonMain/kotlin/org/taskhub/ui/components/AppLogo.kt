// Logo vectorial de la app dibujado a mano con Canvas (sin assets externos).
// Usado en la pantalla de splash/onboarding y en cualquier sitio que necesite
// el isotipo de Task Hub a un tamaño arbitrario.
package org.taskhub.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Isotipo de Task Hub: un checkmark (tarea completada) inscrito en un anillo
 * teal, con un punto coral que marca el "hub" — sin depender de assets/fuentes,
 * para poder escalarse a cualquier tamaño (splash, top bar, favicon futuro).
 */
/**
 * Dibuja el isotipo de Task Hub (anillo + checkmark + punto "hub") en un [Canvas].
 *
 * @param modifier modificador adicional del [Canvas].
 * @param size lado del cuadrado en el que se inscribe el logo.
 * @param ringColor color del anillo exterior.
 * @param checkColor color del checkmark central.
 * @param dotColor color del punto "hub" en la esquina superior derecha del anillo.
 */
@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    // Colores del tema (no literales Teal*/Coral*) para que el logo se adapte
    // a los 6 temas (DEFAULT, NATURALEZA, MINIMAL, OCEANO, ATARDECER, MEDIANOCHE)
    // en vez de quedar siempre teal/coral — mismo criterio ya aplicado en
    // EmptyStateIllustrations.kt.
    // checkColor usa onPrimaryContainer (variante oscura), no primaryContainer
    // directo: primaryContainer es un tono CLARO en los 6 temas (pensado para
    // fondos, no para trazos finos sobre el propio canvas del logo).
    ringColor: Color = MaterialTheme.colorScheme.primary,
    checkColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    dotColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = this.size.minDimension * 0.09f
        val radius = (this.size.minDimension - strokeWidth) / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)

        // Anillo exterior
        drawCircle(
            color = ringColor,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth)
        )

        // Checkmark central
        val checkStroke = this.size.minDimension * 0.12f
        val w = this.size.width
        val h = this.size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.30f, h * 0.52f)
            lineTo(w * 0.44f, h * 0.66f)
            lineTo(w * 0.72f, h * 0.36f)
        }
        drawPath(
            path = path,
            color = checkColor,
            style = Stroke(
                width = checkStroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )

        // Punto "hub" en la esquina superior derecha del anillo
        drawCircle(
            color = dotColor,
            radius = this.size.minDimension * 0.10f,
            center = Offset(center.x + radius * 0.75f, center.y - radius * 0.75f)
        )
    }
}
