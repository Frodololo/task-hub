// Selector de emoji tipo grid (6 columnas), reutilizado por CreateHouseholdScreen
// y EditProfileScreen. Antes era el mismo código duplicado (~50 líneas c/u).
package org.taskhub.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Los 24 emojis comunes que ofrece el selector. */
val EMOJI_OPTIONS = listOf(
    "🧑", "👩", "👨", "👦", "👧", "🧒", "🐱", "🐶", "🐼", "🦊", "🐸", "🐵",
    "🌟", "🔥", "💎", "🎮", "📚", "🎨", "⚽", "🍕", "☕", "🦸", "🧙", "🤖"
)

/**
 * Botón + grid de emoji seleccionable (6 columnas), extraído de la duplicación
 * entre CreateHouseholdScreen y EditProfileScreen.
 *
 * @param currentEmoji emoji actualmente seleccionado ("" si ninguno).
 * @param onEmojiSelected callback con el emoji elegido.
 * @param buttonLabel texto del botón desplegable (p.ej. con el emoji actual).
 * @param emojiContentDesc i18n para el contentDescription de cada celda ("%s" se reemplaza por el emoji).
 */
@Composable
fun EmojiPicker(
    currentEmoji: String,
    onEmojiSelected: (String) -> Unit,
    buttonLabel: String,
    emojiContentDesc: String,
    modifier: Modifier = Modifier
) {
    val emojiOptions = EMOJI_OPTIONS
    var showEmojiGrid by remember { mutableStateOf(false) }
    val reduceMotion = shouldReduceMotion()

    OutlinedButton(
        onClick = { showEmojiGrid = !showEmojiGrid },
        modifier = modifier.fillMaxWidth()
    ) {
        Text(buttonLabel)
    }

    AnimatedVisibility(
        visible = showEmojiGrid,
        enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandVertically(),
        exit = if (reduceMotion) ExitTransition.None else fadeOut() + shrinkVertically()
    ) {
        val rows = emojiOptions.chunked(6)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { emoji ->
                        Surface(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = emojiContentDesc.replace("%s", emoji)
                                    selected = currentEmoji == emoji
                                }
                                .clickable(role = Role.Button) {
                                    onEmojiSelected(emoji)
                                    showEmojiGrid = false
                                },
                            shape = MaterialTheme.shapes.medium,
                            color = if (currentEmoji == emoji)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            border = if (currentEmoji == emoji)
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else null
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(emoji, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}