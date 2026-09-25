// Lista de miembros del hogar (extensión de LazyListScope), usada dentro del
// LazyColumn principal de HouseholdScreen.
package org.taskhub.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.taskhub.network.models.MemberResponse
import org.taskhub.ui.models.MemberUiState

/**
 * Sección desplegable de miembros del hogar: cabecera con contador + lista
 * de tarjetas de miembro. Se añade directamente como items de un `LazyColumn`
 * anfitrión (extensión de [LazyListScope]) para conservar el scroll compartido
 * con el resto de la pantalla, en vez de anidar otro `LazyColumn` propio.
 *
 * @param membersExpanded si la lista de miembros está visible bajo la cabecera.
 * @param onToggleExpanded callback al pulsar la cabecera desplegable.
 * @param memberState estado de carga de los miembros del hogar.
 * @param isMemberActionPending si hay una acción de miembro en curso (deshabilita botones para evitar dobles envíos).
 * @param isAdmin si el usuario actual es admin del hogar (habilita cambiar rol / eliminar miembro).
 * @param ownerUserId el `userId` del owner actual del hogar, para no ofrecer degradar su rol vía
 * el desplegable (ver [MemberCard]: cambiar solo `role` a "child" no transfiere `ownerId`, dejaría
 * el badge inconsistente y sería auto-uncorregible). Expulsarlo SÍ está permitido desde `f92aa0b`
 * (`firestore.rules` añadió `isValidOwnerSuccession(hid)`, que permite a un admin no-owner
 * transferir `ownerId` al expulsar al owner — panel de expertos 2026-09-11 v9 reintento, corregido
 * con reglas + deploy en vez de mantener este bloqueo de UI, ver cabecera de `firestore.rules` v10).
 * @param myMember el miembro correspondiente al usuario actual, o `null` si aún no se resolvió.
 * @param s resolutor de claves i18n ya fijado al idioma actual.
 * @param onAppreciateClick callback al pulsar "Agradecer" sobre un miembro.
 * @param onDonateClick callback al pulsar "Donar" sobre un miembro.
 * @param onRoleChange callback tras confirmar un cambio de rol (miembro, nuevo rol).
 * @param onRemoveMember callback tras confirmar la eliminación de un miembro.
 * @param onCreateTask callback al pulsar "crear tarea" preasignada a un miembro.
 * @param onMemberClick callback al pulsar el nombre/avatar de un miembro (ver perfil público).
 * @param onInviteClick callback del CTA de invitar cuando la lista está vacía.
 */
fun LazyListScope.householdMemberList(
    membersExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    memberState: MemberUiState,
    isMemberActionPending: Boolean = false,
    isAdmin: Boolean,
    ownerUserId: String?,
    myMember: MemberResponse?,
    s: (String) -> String,
    onAppreciateClick: (MemberResponse) -> Unit,
    onDonateClick: (MemberResponse) -> Unit,
    onRoleChange: (MemberResponse, String) -> Unit,
    onRemoveMember: (MemberResponse) -> Unit,
    onCreateTask: (MemberResponse) -> Unit,
    onMemberClick: (MemberResponse) -> Unit,
    onInviteClick: () -> Unit
) {
    // Members header (desplegable)
    item {
        ExpandableSectionHeader(
            expanded = membersExpanded,
            onToggle = onToggleExpanded,
            chevronTint = MaterialTheme.colorScheme.primary
        ) {
            Text(
                text = s("household_members"),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.weight(1f))

            when (memberState) {
                is MemberUiState.Success -> {
                    Text(
                        text = "${memberState.members.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {}
            }
        }
    }

    // Members list (desplegable)
    if (!membersExpanded) return

    when (memberState) {
        is MemberUiState.Loading -> {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        is MemberUiState.Success -> {
            if (memberState.members.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("👥", style = MaterialTheme.typography.displayMedium)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                s("household_member_list_empty_title"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                s("household_member_list_empty_desc"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = onInviteClick,
                            ) {
                                Text(s("household_member_list_invite_cta"), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            } else {
                items(memberState.members, key = { it.id }) { member ->
                    // HouseholdScreen recompone esta lista en cada tick del polling
                    // de notificaciones (30s) y chat (60s) aunque memberState no haya
                    // cambiado; sin memoizar, las lambdas de abajo (que cierran sobre
                    // `member`) se recreaban en cada paso, y su nueva identidad forzaba
                    // recomponer TODAS las MemberCard aunque sus datos siguieran
                    // iguales. remember(member) las estabiliza mientras el miembro no
                    // cambie (MemberResponse es data class → equals estructural), y
                    // rememberUpdatedState evita que queden cerradas sobre callbacks
                    // obsoletos (p.ej. si currentMemberId aún no se había resuelto).
                    val latestOnAppreciateClick by rememberUpdatedState(onAppreciateClick)
                    val latestOnDonateClick by rememberUpdatedState(onDonateClick)
                    val latestOnRoleChange by rememberUpdatedState(onRoleChange)
                    val latestOnRemoveMember by rememberUpdatedState(onRemoveMember)
                    val latestOnCreateTask by rememberUpdatedState(onCreateTask)
                    val latestOnMemberClick by rememberUpdatedState(onMemberClick)

                    val stableOnAppreciateClick = remember(member) { { latestOnAppreciateClick(member) } }
                    val stableOnDonateClick = remember(member) { { latestOnDonateClick(member) } }
                    val stableOnRoleChange = remember(member) { { newRole: String -> latestOnRoleChange(member, newRole) } }
                    val stableOnRemoveClick = remember(member) { { latestOnRemoveMember(member) } }
                    val stableOnCreateTask = remember(member) { { latestOnCreateTask(member) } }
                    val stableOnClick = remember(member) { { latestOnMemberClick(member) } }

                    MemberCard(
                        member = member,
                        isAdmin = isAdmin,
                        isSelf = myMember?.id == member.id,
                        isOwner = ownerUserId != null && member.userId == ownerUserId,
                        canTransfer = myMember != null,
                        actionPending = isMemberActionPending,
                        s = s,
                        onAppreciateClick = stableOnAppreciateClick,
                        onDonateClick = stableOnDonateClick,
                        onRoleChange = stableOnRoleChange,
                        onRemoveClick = stableOnRemoveClick,
                        onCreateTask = stableOnCreateTask,
                        onClick = stableOnClick
                    )
                }
            }
        }

        is MemberUiState.Error -> {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = memberState.message,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        is MemberUiState.Idle -> {}
    }
}

/** Tarjeta de un miembro: avatar, nombre/rol, puntos, y acciones (rol/eliminar/crear tarea/agradecer/donar). */
@Composable
private fun MemberCard(
    member: MemberResponse,
    isAdmin: Boolean,
    isSelf: Boolean,
    isOwner: Boolean,
    canTransfer: Boolean,
    actionPending: Boolean = false,
    s: (String) -> String,
    onRoleChange: (String) -> Unit,
    onRemoveClick: () -> Unit,
    onCreateTask: () -> Unit,
    onClick: () -> Unit,
    onAppreciateClick: () -> Unit,
    onDonateClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar — mismo anillo admin/resto que RankingRow (ver
                // RankingScreen.kt, informe delight #3): tertiary para admin,
                // primaryContainer (sutil) para el resto.
                UserAvatar(
                    avatarUrl = member.avatarUrl,
                    fallbackEmoji = defaultRoleEmoji(member.role),
                    displayName = member.displayName,
                    contentDescription = member.displayName,
                    size = 48.dp,
                    backgroundColor = if (member.role == "admin") MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    ringColor = if (member.role == "admin") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primaryContainer
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Name + role — clickable to view public profile
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        // role = Button: semántica estructurada para TalkBack/VoiceOver
                        // (panel v7, Exp. 3, MENOR).
                        .clickable(enabled = member.userId != null, role = Role.Button) { onClick() }
                ) {
                    Text(
                        text = member.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (member.role == "admin") s("member_role_admin_full") else s("member_role_child_full"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Indicador visual de perfil "hijo/a" sin cuenta de
                        // Google vinculada (member.userId == null) — antes no
                        // había forma de distinguirlo de un miembro con cuenta
                        // solo mirando la lista.
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

                // Points badge — mismo aspecto visual que PointsBadge(BadgeTone.Coral),
                // pero con AnimatedCounter para el número (§2.10 del informe de
                // delight): PointsBadge solo acepta texto plano, y no se anima
                // dentro del componente genérico (también usado para costes/urgencia).
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiary) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AnimatedCounter(
                            value = member.totalPoints,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = " ${s("transfer_points_suffix")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Acciones: editar rol (solo admins) + crear tarea
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Nunca sobre uno mismo: un admin que se auto-degrada a "Miembro"
                // (y era el único admin) deja el hogar sin nadie que pueda volver
                // a abrir este menú para revertirlo — bloqueo permanente evitable.
                // Nunca sobre el owner: cambiar solo su `role` a "child" no
                // transfiere `ownerId` (son campos independientes), así que
                // dejaría su badge incorrecto y solo él podría revertirlo —
                // el menú está oculto sobre uno mismo, y expulsarlo (abajo) es
                // la vía correcta si se quiere que deje de ser owner por la
                // fuerza (esa sí transfiere `ownerId` de verdad).
                if (isAdmin && !isSelf && !isOwner) {
                    var roleMenuExpanded by remember { mutableStateOf(false) }
                    var pendingRole by remember { mutableStateOf<String?>(null) }
                    Box {
                        OutlinedButton(
                            onClick = { roleMenuExpanded = true },
                            enabled = !actionPending
                        ) {
                            Text(if (member.role == "admin") s("member_role_admin_short") else s("member_role_child_short"))
                        }
                        DropdownMenu(
                            expanded = roleMenuExpanded,
                            onDismissRequest = { roleMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(s("member_role_admin_short")) },
                                onClick = {
                                    roleMenuExpanded = false
                                    if (member.role != "admin") pendingRole = "admin"
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(s("member_role_child_short")) },
                                onClick = {
                                    roleMenuExpanded = false
                                    if (member.role != "child") pendingRole = "child"
                                }
                            )
                        }
                    }
                    // Cambiar el rol de otro miembro es una acción de alto impacto
                    // (da/quita control total del hogar) — pide confirmación, igual
                    // que borrar/salir del hogar (DestructiveConfirmDialog).
                    val newRole = pendingRole
                    if (newRole != null) {
                        val newRoleLabel = if (newRole == "admin") s("member_role_admin_short") else s("member_role_child_short")
                        DestructiveConfirmDialog(
                            title = s("member_role_change_confirm_title"),
                            text = s("member_role_change_confirm_text")
                                .replace("%1", member.displayName)
                                .replace("%2", newRoleLabel),
                            s = s,
                            onDismiss = { pendingRole = null },
                            onConfirm = {
                                pendingRole = null
                                onRoleChange(newRole)
                            },
                            confirmLabel = s("member_role_change_confirm_btn"),
                            destructive = false
                        )
                    }
                }

                // Expulsar SÍ está permitido sobre el owner (a diferencia del
                // cambio de rol de arriba): `deleteMember` transfiere `ownerId`
                // al sucesor ANTES del soft-delete
                // (`HouseholdRules.planOwnerSuccession`), y desde `f92aa0b`
                // `firestore.rules` permite ese PATCH a un admin no-owner
                // (`isValidOwnerSuccession`) — ya no queda el hogar con
                // `ownerId` apuntando a un miembro ya expulsado.
                if (isAdmin && !isSelf) {
                    // Eliminar un miembro es tan destructivo como borrar/salir del
                    // hogar — mismo componente de confirmación (DestructiveConfirmDialog).
                    var showRemoveConfirm by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { showRemoveConfirm = true },
                        enabled = !actionPending
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = s("member_remove_action_named").replace("%s", member.displayName),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    if (showRemoveConfirm) {
                        // Si el objetivo es el owner actual, el copy genérico de
                        // "eliminar miembro" no basta: expulsarlo dispara una
                        // transferencia AUTOMÁTICA de `ownerId` a otro admin con
                        // cuenta vinculada (ver HouseholdRules.resolveOwnerSuccessor)
                        // que el admin que confirma no puede adivinar por su cuenta
                        // (panel de expertos v10, UX) — se avisa explícitamente antes
                        // de confirmar, en vez de que se entere después del hecho.
                        DestructiveConfirmDialog(
                            title = if (isOwner) s("member_remove_confirm_title_owner") else s("member_remove_confirm_title"),
                            text = if (isOwner) {
                                s("member_remove_confirm_text_owner").replace("%s", member.displayName)
                            } else {
                                s("member_remove_confirm_text").replace("%s", member.displayName)
                            },
                            s = s,
                            onDismiss = { showRemoveConfirm = false },
                            onConfirm = {
                                showRemoveConfirm = false
                                onRemoveClick()
                            },
                            confirmLabel = s("member_remove_confirm_btn")
                        )
                    }
                }

                OutlinedButton(
                    onClick = onCreateTask,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(s("member_create_task_short"))
                }
            }

            // Agradecer / Donar — ocultos sobre uno mismo
            if (!isSelf && canTransfer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onAppreciateClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(s("appreciate_action"))
                    }
                    OutlinedButton(
                        onClick = onDonateClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(s("donate_action"))
                    }
                }
            }
        }
    }
}
