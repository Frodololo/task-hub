/**
 * Contenido del ranking de puntos de un hogar (pestaña "Ranking" de
 * [ExploreScreen]; este archivo NO define un [cafe.adriel.voyager.core.screen.Screen]
 * propio, solo los composables que renderiza esa pestaña). Se apoya en
 * [org.taskhub.ui.models.MemberScreenModel] para la lista de miembros.
 */
package org.taskhub.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.taskhub.network.models.MemberResponse
import org.taskhub.ui.components.AnimatedCounter
import org.taskhub.ui.components.EffectCategory
import org.taskhub.ui.components.EmptyRankingIllustration
import org.taskhub.ui.components.EmptyState
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.ShimmerList
import org.taskhub.ui.components.UserAvatar
import org.taskhub.ui.components.defaultRoleEmoji
import org.taskhub.ui.components.effectsEnabled
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.components.streakFireFontSize
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.MemberScreenModel
import org.taskhub.ui.models.MemberUiState
import org.taskhub.ui.theme.*

/**
 * Contenido reutilizable del ranking (sin barra superior), para la pantalla
 * combinada. Reutiliza el [MemberScreenModel] que ya crea [ExploreScreen] en
 * vez de inyectar `FirestoreRepository` directamente — antes esta pantalla
 * duplicaba su propia carga de miembros al margen de cualquier ScreenModel.
 */
@Composable
internal fun RankingBody(householdId: String, memberModel: MemberScreenModel) {
    val appSettings = LocalAppSettings.current
    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

    val uiState by memberModel.uiState.collectAsState()

    LaunchedEffect(householdId) {
        memberModel.loadMembers(householdId)
    }

    // Sort by totalPoints descending (getMembers ya filtra miembros que abandonaron)
    val members = remember(uiState) {
        (uiState as? MemberUiState.Success)?.members
            ?.sortedByDescending { it.totalPoints }
            ?: emptyList()
    }
    val isLoading = uiState is MemberUiState.Loading || uiState is MemberUiState.Idle
    val errorMessage = (uiState as? MemberUiState.Error)?.message

    when {
        isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                ShimmerList(count = 5, itemHeight = 64.dp)
            }
        }
        errorMessage != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Close,
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
                    Button(onClick = { memberModel.loadMembers(householdId) }) { Text(s("tasks_retry")) }
                }
            }
        }
        members.isEmpty() -> {
            EmptyState(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                illustration = { EmptyRankingIllustration() },
                title = s("ranking_empty_title"),
                message = s("ranking_empty_subtitle")
            )
        }
        else -> {
            val reduceMotion = shouldReduceMotion()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(members, key = { _, member -> member.id }) { index, member ->
                    RankingRow(
                        position = index + 1,
                        member = member,
                        modifier = if (reduceMotion) Modifier else Modifier.animateItem()
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/** Fila individual del ranking: medalla/posición, avatar, nombre/rol y puntos+racha. */
@Composable
private fun RankingRow(
    position: Int,
    member: MemberResponse,
    modifier: Modifier = Modifier
) {
    val appSettings = LocalAppSettings.current
    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }
    val animationsOn = effectsEnabled(EffectCategory.ANIMATIONS)
    val navigator = LocalNavigator.currentOrThrow

    // Rebote de entrada solo para la medalla de oro (posición #1, §2.9 del
    // informe de delight) — remember(member.id) evita repetirlo si el mismo
    // miembro sigue en 1er puesto en la siguiente carga.
    val medalScale = if (position == 1) {
        val scale = remember(member.id) { Animatable(if (animationsOn) 0.7f else 1f) }
        LaunchedEffect(member.id) {
            if (animationsOn) {
                scale.animateTo(
                    1f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
                )
            }
        }
        scale.value
    } else 1f

    // Medal colours for top 3
    val medalEmoji = when (position) {
        1 -> "🥇"
        2 -> "🥈"
        3 -> "🥉"
        else -> "  $position"
    }

    val bgColor = when (position) {
        1 -> MaterialTheme.colorScheme.tertiaryContainer
        2 -> MaterialTheme.colorScheme.primaryContainer
        3 -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    // tertiaryContainer/onTertiaryContainer y primaryContainer/onPrimaryContainer
    // (antes Coral100/Teal50 fijos + Coral800/Teal800 fijos): siguen el tema
    // activo (Naturaleza/Minimal) y ya son pares auditados ≥4.5:1 en las 6
    // combinaciones tema/modo, mismo criterio que otras 6+ cards de la app.
    val secondaryTextColor = when (position) {
        1 -> MaterialTheme.colorScheme.onTertiaryContainer
        2, 3 -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        // Panel v18 (UX): la fila era inerte pese a ser un listado de miembros
        // comparándose entre sí — mismo destino (PublicProfileScreen) ya usado
        // en HouseholdMemberList.kt para el mismo dato. Sin onClick si el
        // miembro no tiene cuenta vinculada (userId == null: perfil infantil
        // sin acceso propio, no hay perfil público al que navegar).
        modifier = modifier.fillMaxWidth(),
        onClick = { member.userId?.let { uid -> navigator.push(PublicProfileScreen(uid, member)) } },
        enabled = member.userId != null,
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (position <= 3) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Position with text descriptor
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(40.dp)
            ) {
                Text(
                    text = medalEmoji,
                    // El ordinal "1º"/"2º"/"3º" de al lado ya transmite la misma
                    // información en texto plano — se oculta el emoji de la
                    // medalla al lector de pantalla para evitar leer dos veces
                    // la misma posición (y depender de que el TTS conozca el
                    // nombre Unicode del emoji). Para position > 3 este Text SÍ
                    // es la única fuente del número, así que se deja accesible.
                    modifier = (if (position in 1..3) Modifier.clearAndSetSemantics {} else Modifier)
                        .graphicsLayer {
                            scaleX = medalScale
                            scaleY = medalScale
                        },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = when (position) {
                        1 -> "1º"
                        2 -> "2º"
                        3 -> "3º"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.width(12.dp))

            // Avatar
            // Anillo admin/resto (decisión de diseño, informe delight #3):
            // tertiary (dorado/distintivo) para admin, primaryContainer
            // (sutil) para el resto — no compite visualmente con el propio
            // fondo tertiaryContainer de la medalla de oro en 1er puesto.
            UserAvatar(
                avatarUrl = member.avatarUrl,
                fallbackEmoji = defaultRoleEmoji(member.role),
                displayName = member.displayName,
                contentDescription = member.displayName,
                // Panel v16 (2026-09-24), hallazgo estética: mismo tamaño que
                // HouseholdMemberList (48dp) para el mismo patrón "fila de
                // miembro" — antes usaba el default de 40dp, salto perceptible
                // al navegar entre Ranking y la lista de miembros del hogar.
                size = 48.dp,
                backgroundColor = if (member.role == "admin") MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                ringColor = if (member.role == "admin") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primaryContainer
            )

            Spacer(Modifier.width(12.dp))

            // Name + role
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (member.role == "admin") s("ranking_role_admin") else s("member_role_child_full"),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                    // Mismo indicador que HouseholdMemberList.kt para perfiles
                    // "hijo/a" sin cuenta de Google vinculada (member.userId ==
                    // null): antes solo se veía en la lista de miembros, no aquí.
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
                }
            }

            // Points
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Panel v17 (hallazgo CRÍTICO de accesibilidad): antes
                    // `colorScheme.tertiary` fijo, sin relación con `bgColor`
                    // real de esta fila — fallaba WCAG AA (hasta 2.18:1) en
                    // varias combinaciones tema/modo, sobre todo oscuro.
                    // `secondaryTextColor` ya es el par on*Container/
                    // onSurfaceVariant correcto para el `bgColor` de esta
                    // posición (ver arriba).
                    Text(
                        text = "⭐ ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = secondaryTextColor
                    )
                    AnimatedCounter(
                        value = member.totalPoints,
                        style = MaterialTheme.typography.titleMedium,
                        color = secondaryTextColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Escalado estático por tramo de racha (informe delight
                    // #8, aprobado con toggle — sin animación: solo if/else,
                    // no cuenta como "efecto" a efectos de Modo simple).
                    Text(text = "🔥", fontSize = streakFireFontSize(member.currentStreak))
                    Text(
                        text = " ${member.currentStreak}",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                }
            }
        }
    }
}
