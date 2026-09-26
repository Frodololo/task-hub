/**
 * Composable commonMain que dibuja un QR con Canvas de Compose a partir de
 * [QrEncoder], evitando depender de una librería de QR nativa por plataforma.
 */
package org.taskhub.platform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dibuja un código QR para el [text] dado usando Canvas de Compose.
 *
 * Usa un codificador de QR puro-Kotlin ([QrEncoder]) que funciona en todos
 * los targets de KMP. El QR se pinta como módulos negros sobre fondo blanco,
 * con un margen ("quiet zone") alrededor.
 *
 * [contentDescription] es obligatorio (sin default) para forzar a cada
 * call-site a decidir un texto accesible con el código en claro — un lector
 * de pantalla no puede "leer" un QR pintado a mano en un Canvas.
 */
@Composable
fun QrCodeImage(
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    onError: @Composable () -> Unit = {}
) {
    val matrix = remember(text) {
        try {
            // QrEncoder.encode() puede lanzar si `text` no cabe en la
            // versión 1-M (>16 caracteres) o contiene algún carácter fuera
            // del alfabeto alfanumérico soportado; se captura para poder
            // mostrar [onError] en vez de tumbar la pantalla.
            QrEncoder.encode(text)
        } catch (e: Exception) {
            AppLog.w("QrCodeImage", "encode failed for text of length ${text.length}", e)
            null
        }
    }

    if (matrix == null) {
        onError()
        return
    }

    val moduleCount = matrix.size
    val quietZone = 4  // modules of quiet zone on each side
    val totalModules = moduleCount + 2 * quietZone

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription }
    ) {
        val moduleSize = size.toPx() / totalModules

        // White background
        drawRect(
            color = Color.White,
            topLeft = Offset.Zero,
            size = Size(size.toPx(), size.toPx())
        )

        // Draw modules
        for (r in matrix.indices) {
            for (c in matrix[r].indices) {
                if (matrix[r][c]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(
                            (quietZone + c) * moduleSize,
                            (quietZone + r) * moduleSize
                        ),
                        size = Size(moduleSize, moduleSize)
                    )
                }
            }
        }
    }
}