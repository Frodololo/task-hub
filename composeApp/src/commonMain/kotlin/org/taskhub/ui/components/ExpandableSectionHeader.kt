// Componente compartido de cabecera para secciones colapsables (contenido +
// chevron). Usado por HouseholdTaskSection, HouseholdMemberList y por
// pantallas de creación/edición de tareas y lista de tareas para no repetir
// el mismo patrón de Row + Icon + semántica de expandido/colapsado.
package org.taskhub.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import org.taskhub.ui.i18n.AppStrings

/**
 * Cabecera clicable de una sección colapsable: contenido a la izquierda +
 * chevron arriba/abajo a la derecha. Unifica el patrón que antes se repetía
 * de forma manual en 4 sitios (`HouseholdTaskSection`, `HouseholdMemberList`,
 * `CreateTaskScreen#QuickTemplatesSection`, `TaskListScreen#GroupHeader`),
 * 3 de los cuales reutilizaban las claves i18n `household_task_section_*`
 * fuera de su dominio original (panel v7, #25).
 */
/**
 * Cabecera clicable y accesible para una sección colapsable.
 *
 * Uso típico: pasar como [content] el texto/etiqueta de la sección (y
 * cualquier badge auxiliar); el propio composable añade el chevron
 * arriba/abajo a la derecha y gestiona la semántica de "botón" +
 * expandido/colapsado para TalkBack/VoiceOver.
 *
 * @param expanded estado actual (expandido/colapsado); lo gestiona el caller.
 * @param onToggle callback invocado al pulsar toda la fila (no solo el icono).
 * @param modifier modificador adicional aplicado a la fila raíz.
 * @param chevronTint color del icono de chevron.
 * @param chevronSize tamaño del icono de chevron.
 * @param content contenido a la izquierda del chevron (título, contador, etc.).
 */
@Composable
fun ExpandableSectionHeader(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    chevronTint: Color = LocalContentColor.current,
    chevronSize: Dp = 24.dp,
    content: @Composable RowScope.() -> Unit
) {
    val lang = LocalAppSettings.current.currentLanguage
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            // Semántica estructurada de "botón" + estado expandido/colapsado
            // (antes solo el contentDescription del icono chevron daba alguna
            // pista, y únicamente a quien explorara ese hijo por separado) —
            // panel v7, Exp. 3, MENOR.
            .semantics {
                stateDescription = AppStrings.get(if (expanded) "state_expanded" else "state_collapsed", lang)
            }
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
        val angle by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = if (shouldReduceMotion()) tween(0) else tween(200, easing = FastOutSlowInEasing),
            label = "chevronRotation"
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = AppStrings.get(if (expanded) "common_collapse" else "common_expand", lang),
            tint = chevronTint,
            modifier = Modifier
                .size(chevronSize)
                .graphicsLayer { rotationZ = angle }
        )
    }
}
