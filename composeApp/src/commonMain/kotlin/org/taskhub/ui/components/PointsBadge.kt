// Chips reutilizables para mostrar puntos/estadísticas con color accesible
// según tema, usados en tareas, ranking, perfil y estadísticas.
package org.taskhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.taskhub.ui.theme.semanticColors

/** Tono visual de un [PointsBadge]/[StatChip] (fondo/texto accesibles tomados del tema). */
enum class BadgeTone { Coral, Teal, Neutral, Success, Warning, Info, Error }

/**
 * Resuelve el par (fondo, texto) accesible de un [BadgeTone]. Compartido por
 * [PointsBadge] y [StatChip] para no duplicar el mapeo tono → color del tema.
 */
@Composable
private fun badgeToneColors(tone: BadgeTone): Pair<Color, Color> = when (tone) {
    BadgeTone.Coral -> MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.onTertiary
    BadgeTone.Teal -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    BadgeTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    BadgeTone.Success -> MaterialTheme.semanticColors.successContainer to MaterialTheme.semanticColors.onSuccessContainer
    BadgeTone.Warning -> MaterialTheme.semanticColors.warningContainer to MaterialTheme.semanticColors.onWarningContainer
    BadgeTone.Info -> MaterialTheme.semanticColors.infoContainer to MaterialTheme.semanticColors.onInfoContainer
    BadgeTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
}

/**
 * Chip reutilizable para puntos, urgencia y costes.
 *
 * Reemplaza los antiguos `Surface(color = Coral500)` + texto blanco que fallaban contraste
 * WCAG (blanco sobre #FF5C3A = 3.07:1). Usa colores del tema ya accesibles:
 * - [BadgeTone.Coral]: `tertiary`/`onTertiary` (coral oscuro + blanco, 5.92:1).
 * - [BadgeTone.Teal]: `primaryContainer`/`onPrimaryContainer`.
 * - [BadgeTone.Neutral]: `surfaceVariant`/`onSurfaceVariant`.
 * - [BadgeTone.Success]/[BadgeTone.Warning]/[BadgeTone.Info]: paleta semántica
 *   (`MaterialTheme.semanticColors`), pensada para ser coherente en los 6 themes
 *   (aunque en Naturaleza/Océano coincide visualmente con [BadgeTone.Teal]/[BadgeTone.Info]
 *   por reutilizar el mismo swatch de marca — ver auditoría panel v23).
 *
 * @param text  Texto corto del badge (p. ej. "10 pts").
 * @param tone  Tono visual; [BadgeTone.Coral] por defecto (puntos/urgencia).
 * @param gradient degradado sutil en vez de fondo plano — reservado a los
 *   badges de "puntos ganados" (p. ej. el badge de puntos de una tarea ya
 *   completada en TaskListScreen.kt) y NO al resto de badges genéricos
 *   (costes, urgencia, contadores…), que se quedan planos (informe delight
 *   #10, aprobado). Ver [gradientBrush] sobre por qué es una variación de
 *   luminosidad del propio [container], no un segundo rol del colorScheme.
 */
@Composable
fun PointsBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.Coral,
    gradient: Boolean = false,
) {
    val (container, content) = badgeToneColors(tone)
    if (gradient) {
        val brush = remember(container, content) { gradientBrush(container, content) }
        Box(
            modifier = modifier
                .clip(MaterialTheme.shapes.small)
                .background(brush)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = content,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }
    Surface(shape = MaterialTheme.shapes.small, color = container, modifier = modifier) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Degradado sutil (top-left → bottom-right) para [PointsBadge] en modo
 * [PointsBadge.gradient]: dos variaciones de luminosidad de [base], NO
 * `base`→otro rol del colorScheme (p. ej. `tertiary`→`tertiaryContainer`) —
 * ese segundo rol suele tener MENOS contraste con el `content` (texto) ya
 * auditado en [badgeToneColors] para `base` en solitario (p. ej. blanco
 * sobre `tertiaryContainer`, un tono claro, falla WCAG en los 3 temas).
 *
 * Antes interpolaba también hacia [Color.White] (0.18f), lo que lavaba el
 * extremo claro y en temas claros dejaba el contraste con `content` por
 * debajo de 4.5:1 (panel v14 2026-09-20, hallazgo 1). Después (panel v15,
 * a622bcd) se interpoló siempre hacia [Color.Black], lo que arregló 5/6
 * combinaciones tema×modo pero rompió "Naturaleza oscuro" (`tertiary` =
 * `Green200`, `onTertiary` = `Green900`: texto OSCURO sobre fondo CLARO,
 * al revés que el resto — oscurecer further el degradado REDUCE el
 * contraste con el texto en vez de aumentarlo). Ahora la dirección del
 * lerp depende de la luminancia relativa de [content] frente a [base]:
 * si el texto es más claro que el fondo (caso común: texto claro sobre
 * fondo oscuro), oscurecer further el fondo; si el texto es más oscuro
 * (caso [NaturalezaDarkColorScheme]), aclarar further el fondo — en
 * ambos casos el degradado se aleja de la luminancia del texto, nunca se
 * acerca (panel v15, hallazgo de accesibilidad #3).
 */
private fun gradientBrush(base: Color, content: Color): Brush {
    val target = if (content.luminance() > base.luminance()) Color.Black else Color.White
    return Brush.linearGradient(colors = listOf(lerp(base, target, 0.14f), lerp(base, target, 0.04f)))
}

/**
 * Chip de estadística reutilizable: unifica los antiguos `InfoBadge`
 * (`TaskDetailScreen`, label+value con fondo tintado) y `StatItem`
 * (`StatsScreen`, emoji+value+label sin fondo) en un único componente
 * compartido, reutilizando [BadgeTone] (igual mapeo de color que [PointsBadge]).
 *
 * - [tone] = null: sin superficie/fondo (equivalente exacto al antiguo
 *   `StatItem` — mismo aspecto visual, solo cambia dónde vive el código).
 * - [tone] != null: chip con superficie tintada según [BadgeTone] (mismos
 *   pares container/onContainer ya auditados por WCAG que usa [PointsBadge]).
 *   Sustituye a `InfoBadge`, que usaba `color.copy(alpha = 0.15f)` sin
 *   auditar contraste — `BadgeTone.Teal` es el reemplazo visualmente más
 *   cercano a su único uso real (`color = colorScheme.primary`).
 *
 * @param value texto principal (obligatorio).
 * @param label texto secundario opcional, debajo de [value].
 * @param emoji emoji opcional, encima de [value] (uso de `StatItem`).
 */
@Composable
fun StatChip(
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    emoji: String? = null,
    tone: BadgeTone? = BadgeTone.Coral
) {
    if (tone == null) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            if (emoji != null) {
                Text(emoji, style = MaterialTheme.typography.titleLarge)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val (container, content) = badgeToneColors(tone)
    Surface(shape = MaterialTheme.shapes.small, color = container, modifier = modifier) {
        if (label == null) {
            Text(
                text = value,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = content,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (emoji != null) {
                    Text(emoji, style = MaterialTheme.typography.titleLarge, color = content)
                }
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = content)
                Text(label, style = MaterialTheme.typography.labelSmall, color = content)
            }
        }
    }
}
