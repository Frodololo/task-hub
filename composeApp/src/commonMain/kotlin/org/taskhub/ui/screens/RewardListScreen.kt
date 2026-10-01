/**
 * Contenido de la pestaña de recompensas de un hogar (rejilla de canjeables
 * + gestión admin). No es un [cafe.adriel.voyager.core.screen.Screen] propio:
 * se embebe en la pantalla combinada del hogar (p.ej. [HouseholdScreen]) vía
 * [RewardsBody], compartiendo el [org.taskhub.ui.models.MemberScreenModel]
 * ya creado por el contenedor. Navega a [CreateRewardScreen] y
 * [MemberRewardScreen].
 */
package org.taskhub.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.taskhub.network.models.RewardResponse
import org.taskhub.ui.components.BadgeTone
import org.taskhub.ui.components.DestructiveConfirmDialog
import org.taskhub.ui.components.EmptyRewardsIllustration
import org.taskhub.ui.components.EmptyState
import org.taskhub.ui.components.ErrorAwareSnackbarHost
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.PointsBadge
import org.taskhub.ui.components.ShimmerList
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.components.showErrorSnackbar
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.MemberScreenModel
import org.taskhub.ui.models.MemberUiState
import org.taskhub.ui.models.RewardUiState
import org.taskhub.ui.theme.*

/**
 * Contenido reutilizable de recompensas (sin barra superior), para la
 * pantalla combinada. Determina permisos de administración localmente
 * (owner del hogar o rol "admin" entre los miembros) para mostrar el botón
 * de crear y el de borrar por recompensa; los errores de acciones
 * (borrar/canjear) se muestran vía snackbar en vez de bloquear la lista.
 */
@Composable
internal fun RewardsBody(householdId: String, memberModel: MemberScreenModel) {
    val navigator = LocalNavigator.currentOrThrow
    val rewardState by memberModel.rewardState.collectAsState()
    val memberState by memberModel.uiState.collectAsState()
    val rewardActionState by memberModel.rewardActionState.collectAsState()
    val appSettings = LocalAppSettings.current
    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }
    val snackbarHostState = remember { SnackbarHostState() }
    val reduceMotion = shouldReduceMotion()

    // Sin esto, un fallo al borrar una recompensa (p.ej. red) no mostraba nada:
    // rewardActionState pasaba a Error pero ningún composable estaba suscrito,
    // así que la tarjeta simplemente seguía ahí sin explicación.
    LaunchedEffect(rewardActionState) {
        val state = rewardActionState
        if (state is org.taskhub.ui.models.RewardActionState.Error) {
            snackbarHostState.showErrorSnackbar(message = state.message, duration = SnackbarDuration.Short)
            memberModel.clearRewardAction()
        }
    }

    // Determine if current user is admin
    var isAdmin by remember { mutableStateOf(false) }
    var currentMemberId by remember { mutableStateOf("") }

    LaunchedEffect(householdId) {
        memberModel.loadRewards(householdId)
        memberModel.loadMembers(householdId)
    }

    // El owner del hogar es siempre "de confianza" para gestionar recompensas,
    // igual que isTrusted(hid) en firestore.rules — ver mismo fix en
    // HouseholdScreen.kt/TaskDetailScreen.kt.
    var isOwner by remember { mutableStateOf(false) }
    LaunchedEffect(householdId) {
        isOwner = memberModel.isHouseholdOwner(householdId)
    }

    var currentMemberPoints by remember { mutableStateOf(0) }
    LaunchedEffect(memberState) {
        if (memberState is MemberUiState.Success) {
            val members = (memberState as MemberUiState.Success).members
            val localId = memberModel.localId
            val myMember = members.find { it.userId == localId }
            isAdmin = myMember?.role == "admin" || isOwner
            currentMemberId = myMember?.id ?: ""
            currentMemberPoints = myMember?.totalPoints ?: 0
        }
    }
    LaunchedEffect(isOwner) {
        if (isOwner) isAdmin = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Acción de crear (solo admins)
        if (isAdmin) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { navigator.push(CreateRewardScreen(householdId)) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        s("tasks_new"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = s("reward_list_explainer_banner"),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when (val rState = rewardState) {
            is RewardUiState.Loading -> {
                // ShimmerList en vez de CircularProgressIndicator genérico —
                // mismo patrón ya usado en TaskListScreen/HouseholdScreen/
                // HomeScreen/RankingScreen (ronda de deuda aplicable
                // 2026-09-12, punto C15).
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    ShimmerList(count = 4, itemHeight = 88.dp)
                }
            }

            is RewardUiState.Success -> {
                if (rState.rewards.isEmpty()) {
                    EmptyState(
                        modifier = Modifier.fillMaxSize(),
                        illustration = { EmptyRewardsIllustration() },
                        title = s("reward_list_empty_title"),
                        message = if (isAdmin) s("reward_list_empty_admin") else s("reward_list_empty_member")
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(rState.rewards, key = { it.id }) { reward ->
                            RewardCard(
                                reward = reward,
                                isAdmin = isAdmin,
                                memberPoints = currentMemberPoints,
                                modifier = if (reduceMotion) Modifier else Modifier.animateItem(),
                                onDelete = {
                                    memberModel.deleteReward(householdId, reward.id)
                                },
                                onRedeem = {
                                    if (currentMemberId.isNotEmpty()) {
                                        navigator.push(
                                            MemberRewardScreen(
                                                householdId = householdId,
                                                memberId = currentMemberId,
                                                reward = reward
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            is RewardUiState.Error -> {
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
                                text = rState.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { memberModel.loadRewards(householdId) }) {
                            Text(s("tasks_retry"))
                        }
                    }
                }
            }

            is RewardUiState.Idle -> {}
        }
    }

        ErrorAwareSnackbarHost(
            hostState = snackbarHostState,
            errorIconContentDescription = s("error_icon_content_desc"),
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/**
 * Tarjeta individual de recompensa en la rejilla. Muestra icono, título,
 * descripción y coste; el botón de borrar solo aparece si [isAdmin] y pide
 * confirmación antes de invocar [onDelete].
 */
@Composable
private fun RewardCard(
    reward: RewardResponse,
    isAdmin: Boolean,
    memberPoints: Int,
    onDelete: () -> Unit,
    onRedeem: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val appSettings = LocalAppSettings.current
    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }
    // No bloquea el canje (eso lo decide el servidor en MemberRewardScreen):
    // solo atenúa la tarjeta y avisa de cuántos puntos faltan, para que no
    // parezca alcanzable sin serlo (informe delight #4, aprobado).
    val canAfford = reward.cost <= memberPoints

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (!canAfford) Modifier.alpha(0.6f) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Emoji icon
            Text(
                text = reward.icon,
                style = MaterialTheme.typography.displaySmall
            )

            Spacer(Modifier.height(8.dp))

            // Title
            Text(
                text = reward.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Description if present
            if (reward.description.isNotEmpty()) {
                Text(
                    text = reward.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(12.dp))

            // Cost badge
            PointsBadge(
                text = "⭐ ${reward.cost}",
                tone = BadgeTone.Coral
            )

            Spacer(Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (canAfford) {
                    Button(
                        onClick = onRedeem,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            s("member_reward_title"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onRedeem,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            s("reward_missing_points").replace("%d", (reward.cost - memberPoints).toString()),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isAdmin) {
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = s("reward_delete_action_named").replace("%s", reward.title),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        DestructiveConfirmDialog(
            title = s("reward_delete_title"),
            text = s("reward_delete_confirm").replace("%s", reward.title),
            s = s,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            }
        )
    }
}
