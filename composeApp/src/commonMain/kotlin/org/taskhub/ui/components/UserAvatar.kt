// Avatar de usuario reutilizable con fallback en cascada (foto > emoji >
// inicial > icono), usado en Ranking, HouseholdScreen, TaskDetailScreen,
// EditProfileScreen, ProfileScreen y PublicProfileScreen.
package org.taskhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Avatar reutilizable con orden de prioridad: foto ([avatarUrl]) > emoji
 * ([fallbackEmoji]) > inicial de [displayName] > icono [Icons.Filled.Person].
 *
 * Centraliza el render de avatar usado en Ranking, HouseholdScreen,
 * TaskDetailScreen, EditProfileScreen, ProfileScreen y PublicProfileScreen,
 * de modo que añadir foto de perfil solo requirió tocar este componente.
 */
@Composable
fun UserAvatar(
    avatarUrl: String?,
    fallbackEmoji: String,
    displayName: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    // Antes Teal100 fijo: el único caller que no pasa backgroundColor
    // explícito (EditProfileScreen) quedaba con un fondo fijo que ignora los
    // temas Naturaleza/Minimal. primaryContainer sigue el tema activo.
    backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
    // Anillo opcional (p.ej. admin vs. resto en Ranking/lista de miembros,
    // ver HouseholdMemberList.kt/RankingScreen.kt) — `null` = sin anillo,
    // idéntico al aspecto anterior para el resto de callers.
    ringColor: Color? = null,
) {
    val avatarContentDescription = contentDescription
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .then(if (ringColor != null) Modifier.border(2.dp, ringColor, CircleShape) else Modifier)
            // Fija la descripción accesible en el contenedor y anula la de los hijos:
            // sin esto, la rama de emoji (la más común, sin ningún Modifier.semantics
            // propio) dejaba que TalkBack/VoiceOver leyera el glifo unicode crudo del
            // emoji en vez del contentDescription recibido por el componente.
            .clearAndSetSemantics { this.contentDescription = avatarContentDescription },
        contentAlignment = Alignment.Center
    ) {
        when {
            !avatarUrl.isNullOrBlank() -> AsyncImage(
                model = avatarUrl,
                contentDescription = contentDescription,
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            fallbackEmoji.isNotBlank() -> Text(
                text = fallbackEmoji,
                style = MaterialTheme.typography.titleMedium
            )

            displayName.isNotBlank() -> Text(
                text = displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current
            )

            else -> Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = contentDescription
            )
        }
    }
}

/**
 * Emoji de fallback por defecto para [UserAvatar.fallbackEmoji] según el rol
 * de un miembro. Panel v16 (2026-09-24), hallazgo UI/Material3: la expresión
 * `if (role == "admin") "👑" else "👤"` estaba duplicada literalmente en 4
 * pantallas (`HouseholdMemberList`, `RankingScreen`, `TaskDetailScreen`,
 * `PublicProfileScreen`) — se extrae aquí, junto al propio [UserAvatar], para
 * que un cambio futuro del emoji de rol (o de la condición) solo requiera
 * tocar un sitio.
 */
fun defaultRoleEmoji(role: String): String = if (role == "admin") "👑" else "👤"
