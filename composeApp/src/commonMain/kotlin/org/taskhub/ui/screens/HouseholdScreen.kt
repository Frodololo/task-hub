/**
 * Pantalla "hub" de un hogar: tarjeta de invitación (QR/código), accesos a
 * Tareas/Calendario/Explorar (estadísticas+ranking+recompensas), lista de
 * miembros con acciones de admin (agradecer, donar puntos, cambiar rol,
 * expulsar) y el chat del hogar. Se llega aquí tras crear/unirse a un hogar
 * o desde [ProfileScreen]/[HomeScreen]. Combina
 * [org.taskhub.ui.models.HouseholdScreenModel] (datos del hogar, chat,
 * borrar/salir), [org.taskhub.ui.models.MemberScreenModel] (miembros,
 * agradecer/donar) y [org.taskhub.ui.models.NotificationScreenModel]
 * (contador de no leídas).
 */
package org.taskhub.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.taskhub.network.models.MemberResponse
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import org.taskhub.ui.components.AppreciateDialog
import org.taskhub.ui.components.DeleteHouseholdConfirmDialog1
import org.taskhub.ui.components.DeleteHouseholdConfirmDialog2
import org.taskhub.ui.components.DonateDialog
import org.taskhub.ui.components.ErrorAwareSnackbarHost
import org.taskhub.ui.components.HouseholdChatSection
import org.taskhub.ui.components.HouseholdSettingsDialog
import org.taskhub.ui.components.LeaveHouseholdDialog
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.PointsBadge
import org.taskhub.ui.components.QrShareDialog
import org.taskhub.ui.components.ShimmerList
import org.taskhub.ui.components.StatChip
import org.taskhub.ui.components.TaskHubTopBar
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.components.showErrorSnackbar
import org.taskhub.ui.components.householdMemberList
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.AppreciateActionState
import org.taskhub.ui.models.CalendarSyncManager
import org.taskhub.ui.models.DonateActionState
import org.taskhub.ui.models.HouseholdScreenModel
import org.taskhub.ui.models.HouseholdUiState
import org.taskhub.ui.models.MemberActionState
import org.taskhub.ui.models.MemberScreenModel
import org.taskhub.ui.models.MemberUiState
import org.taskhub.ui.models.NotificationScreenModel
import org.taskhub.ui.theme.*

/**
 * Contenedor de todo el estado de un hogar en pantalla: identidad del
 * miembro actual, permisos de admin, diálogos modales (agradecer, donar,
 * borrar, salir, QR, ajustes) y la lista de miembros/chat en un único
 * [androidx.compose.foundation.lazy.LazyColumn].
 */
data class HouseholdScreen(
    val householdId: String,
    // true cuando se llega aquí justo tras crear el hogar o unirse por
    // invitación (CreateProfileScreen/JoinHouseholdScreen, replaceAll) —
    // dispara la entrada con rebote de la tarjeta de invitación (delight
    // fase 2, §2.2). false en cualquier otra visita.
    val justCreated: Boolean = false
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val coroutineScope = rememberCoroutineScope()
        val householdModel = koinScreenModel<HouseholdScreenModel>()
        val memberModel = koinScreenModel<MemberScreenModel>()
        val notificationModel = koinScreenModel<NotificationScreenModel>()
        val calendarSync = koinInject<CalendarSyncManager>()
        val householdState by householdModel.uiState.collectAsState()
        val memberState by memberModel.uiState.collectAsState()
        val memberActionState by memberModel.memberActionState.collectAsState()
        val notificationUnreadCount by notificationModel.unreadCount.collectAsState()

        // Double-confirmation dialog state
        var showConfirmDialog1 by remember { mutableStateOf(false) }
        var showConfirmDialog2 by remember { mutableStateOf(false) }
        var isDeleting by remember { mutableStateOf(false) }

        // Leave (desvincularse) dialog state
        var showLeaveDialog by remember { mutableStateOf(false) }
        var isLeaving by remember { mutableStateOf(false) }

        // Error de borrar/salir del hogar: antes se descartaba en silencio
        // (onError = { _ -> isDeleting/isLeaving = false }), dejando al usuario
        // sin ninguna pista de que la acción (destructiva) había fallado.
        var householdErrorMessage by remember { mutableStateOf<String?>(null) }
        val householdSnackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(householdErrorMessage) {
            val msg = householdErrorMessage
            if (msg != null) {
                householdSnackbarHostState.showErrorSnackbar(message = msg, duration = SnackbarDuration.Long)
                householdErrorMessage = null
            }
        }

        // Fallo al cambiar rol/eliminar miembro: se muestra como aviso puntual
        // (memberActionState, separado de la lista) en vez de sustituir la
        // lista de miembros por un error — ver MemberActionState.
        LaunchedEffect(memberActionState) {
            val state = memberActionState
            if (state is MemberActionState.Error) {
                householdSnackbarHostState.showErrorSnackbar(message = state.message, duration = SnackbarDuration.Short)
                memberModel.clearMemberAction()
            }
        }

        // QR / Share dialog state
        var showQrDialog by remember { mutableStateOf(false) }

        // Settings dialog state
        var showSettings by remember { mutableStateOf(false) }
        val appSettings = LocalAppSettings.current

        // Miembros desplegable
        var membersExpanded by remember { mutableStateOf(false) }

        // Agradecer / Donar puntos entre miembros
        var appreciateTarget by remember { mutableStateOf<MemberResponse?>(null) }
        var donateTarget by remember { mutableStateOf<MemberResponse?>(null) }
        val appreciateActionState by memberModel.appreciateActionState.collectAsState()
        val donateActionState by memberModel.donateActionState.collectAsState()

        LaunchedEffect(householdId) {
            householdModel.loadHousehold(householdId)
            memberModel.loadMembers(householdId)
        }

        // Backfillea eventos de Calendar pendientes (best-effort, nunca bloquea la UI).
        // Clave = solo el id (no el objeto household completo): así no se relanza
        // cada vez que cambia otro campo del hogar (p.ej. al renombrarlo), solo al
        // entrar en Success por primera vez para este id.
        val successHouseholdId = (householdState as? HouseholdUiState.Success)?.household?.id
        LaunchedEffect(successHouseholdId) {
            val hState = householdState
            if (hState is HouseholdUiState.Success) {
                calendarSync.reconcile(householdId, hState.household.name, hState.household.isPersonal)
            }
        }

        // ── Identidad del usuario actual en este hogar ──
        // ÚNICA fuente de verdad para "qué miembro soy yo": antes, la navegación
        // a Tareas/Calendario/Explorar y el createdBy al crear tareas usaban
        // members.firstOrNull()?.id (el PRIMER miembro de la lista, no el mío),
        // así que en un hogar compartido cualquiera que no fuera el primer
        // miembro veía datos ajenos. resolveCurrentMember() sí resuelve al
        // usuario autenticado. isAdmin también se deriva de aquí (antes tenía
        // su propio LaunchedEffect con el mismo patrón buggy de "primer
        // miembro de la lista" como fallback, pudiendo mostrar/ocultar
        // controles de admin para la persona equivocada).
        var currentMemberId by remember { mutableStateOf("") }
        LaunchedEffect(householdId) {
            currentMemberId = householdModel.resolveCurrentMember(householdId)
        }
        val myMember = (memberState as? MemberUiState.Success)?.members?.firstOrNull { it.id == currentMemberId }
        // El owner del hogar (quien lo creó) es siempre "de confianza" para
        // gestionar roles/recompensas, igual que isTrusted(hid) en
        // firestore.rules, independientemente de qué rol se auto-asignara al
        // crear su propio perfil en CreateProfileScreen. Sin esto, un creador
        // que eligiera "Miembro" para sí mismo dejaba el hogar sin nadie con
        // controles de admin visibles en la UI (bloqueo permanente).
        val currentUserId = householdModel.getLocalId()
        val ownerHousehold = (householdState as? HouseholdUiState.Success)?.household
        val isAdmin = myMember?.role == "admin" ||
            (currentUserId != null && ownerHousehold != null && currentUserId == ownerHousehold.ownerId)
        // Borrar el hogar es la acción más destructiva: reservada al owner
        // (quien lo creó), igual que en firestore.rules — un admin promovido
        // que no sea el owner no debe verla ni poder ejecutarla.
        val isOwner = currentUserId != null && ownerHousehold != null && currentUserId == ownerHousehold.ownerId

        // ── Chat de mensajes ──
        // remember(currentLanguage): sin esto se recreaba en cada recomposición
        // (p.ej. cada tick del polling de notificaciones/chat), y al propagarse
        // sin memoizar a la lista de miembros anulaba el remember(member) de
        // householdMemberList (ver HouseholdMemberList.kt) forzando recomponer
        // toda la lista igualmente.
        val s = remember(appSettings.currentLanguage) { { key: String -> AppStrings.get(key, appSettings.currentLanguage) } }

        // Poll for notification unread count every 30 seconds
        LaunchedEffect(householdId, currentMemberId) {
            if (currentMemberId.isNotEmpty()) {
                notificationModel.refreshUnreadCount(householdId, currentMemberId)
                while (true) {
                    kotlinx.coroutines.delay(30_000L)
                    notificationModel.refreshUnreadCount(householdId, currentMemberId)
                }
            }
        }

        // Cierra los diálogos y confirma el envío con un snackbar al completarse
        // con éxito — antes agradecer/donar cerraba el diálogo en silencio, sin
        // ninguna señal de que los puntos habían llegado (encargo kanban
        // "Snackbars de éxito en canjear/donar/agradecer puntos", 2026-09-12).
        LaunchedEffect(appreciateActionState) {
            if (appreciateActionState is AppreciateActionState.Success) {
                appreciateTarget = null
                householdSnackbarHostState.showSnackbar(message = s("appreciate_success"), duration = SnackbarDuration.Short)
            }
        }
        LaunchedEffect(donateActionState) {
            if (donateActionState is DonateActionState.Success) {
                donateTarget = null
                householdSnackbarHostState.showSnackbar(message = s("donate_success"), duration = SnackbarDuration.Short)
            }
        }
        val messagesState by householdModel.messagesUiState.collectAsState()
        val newMessageText by householdModel.newMessageText.collectAsState()
        val sendMessageError by householdModel.sendMessageError.collectAsState()
        LaunchedEffect(householdId) {
            householdModel.loadMessages(householdId)
            while (true) {
                // 20s → 60s: getMessages traía la subcolección `messages`
                // COMPLETA en cada tick (sin cursor/limit) — a 20s, un hogar
                // con chat activo/antiguo disparaba varias recargas
                // completas por minuto mientras la pantalla estuviera
                // abierta (panel de revisión 2026-09-10, Experto 11,
                // IMPORTANTE). `loadMessages` ahora además acota cada tick a
                // los MAX_POLLED_MESSAGES más recientes vía `orderBy`+`limit`
                // en el servidor (tarjeta kanban "Paginación
                // getMessages/getNotifications", 2026-09-13) — el intervalo
                // de 60s sigue siendo la mitigación complementaria (menos
                // frecuencia además de menos volumen por tick).
                kotlinx.coroutines.delay(60_000L)
                householdModel.loadMessages(householdId)
            }
        }

        val householdName = when (val hState = householdState) {
            is HouseholdUiState.Success -> hState.household.name
            else -> ""
        }

        val inviteCode = when (val hState = householdState) {
            is HouseholdUiState.Success -> hState.household.inviteCode
            else -> ""
        }

        // ── Dialogs ──
        if (showQrDialog && inviteCode.isNotEmpty()) {
            QrShareDialog(inviteCode = inviteCode, s = s, onDismiss = { showQrDialog = false })
        }

        if (showConfirmDialog1) {
            DeleteHouseholdConfirmDialog1(
                householdName = householdName,
                s = s,
                onDismiss = { showConfirmDialog1 = false },
                onConfirm = {
                    showConfirmDialog1 = false
                    showConfirmDialog2 = true
                }
            )
        }

        if (showConfirmDialog2) {
            DeleteHouseholdConfirmDialog2(
                householdName = householdName,
                s = s,
                onDismiss = { showConfirmDialog2 = false },
                onConfirm = {
                    showConfirmDialog2 = false
                    isDeleting = true

                    householdModel.deleteHousehold(
                        householdId = householdId,
                        onSuccess = {
                            // Confirma la acción destructiva antes de navegar: si el
                            // replaceAll se disparara fuera de este launch, se ejecutaría
                            // sincrónicamente y desmontaría esta pantalla (y su
                            // SnackbarHost) antes de que el snackbar llegase a pintarse.
                            coroutineScope.launch {
                                householdSnackbarHostState.showSnackbar(
                                    message = s("household_deleted_success"),
                                    duration = SnackbarDuration.Short
                                )
                                navigator.replaceAll(HomeScreen())
                            }
                        },
                        onError = { msg ->
                            isDeleting = false
                            householdErrorMessage = msg
                        }
                    )
                }
            )
        }

        if (showLeaveDialog) {
            LeaveHouseholdDialog(
                householdName = householdName,
                s = s,
                onDismiss = { showLeaveDialog = false },
                onConfirm = {
                    showLeaveDialog = false
                    isLeaving = true

                    householdModel.leaveHousehold(
                        householdId = householdId,
                        onSuccess = {
                            coroutineScope.launch {
                                householdSnackbarHostState.showSnackbar(
                                    message = s("household_left_success"),
                                    duration = SnackbarDuration.Short
                                )
                                navigator.replaceAll(HomeScreen())
                            }
                        },
                        onError = { msg ->
                            isLeaving = false
                            householdErrorMessage = msg
                        }
                    )
                }
            )
        }

        appreciateTarget?.let { target ->
            val remaining = myMember?.let { householdModel.appreciationRemaining(it) } ?: 0
            AppreciateDialog(
                target = target,
                s = s,
                remaining = remaining,
                state = appreciateActionState,
                onConfirm = { amount ->
                    val fromId = myMember?.id
                    if (fromId != null) {
                        memberModel.appreciateMember(householdId, fromId, target.id, amount)
                    }
                },
                onDismiss = {
                    appreciateTarget = null
                    memberModel.clearAppreciateAction()
                }
            )
        }

        donateTarget?.let { target ->
            val balance = myMember?.totalPoints ?: 0
            DonateDialog(
                target = target,
                s = s,
                balance = balance,
                state = donateActionState,
                onConfirm = { amount ->
                    val fromId = myMember?.id
                    if (fromId != null) {
                        memberModel.donatePoints(householdId, fromId, target.id, amount)
                    }
                },
                onDismiss = {
                    donateTarget = null
                    memberModel.clearDonateAction()
                }
            )
        }

        if (showSettings) {
            HouseholdSettingsDialog(
                onDismiss = { showSettings = false },
                onEditProfile = {
                    showSettings = false
                    navigator.push(EditProfileScreen())
                }
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                TaskHubTopBar(
                    // Nombre del hogar activo en vez del genérico "Task Hub":
                    // con varios hogares, la topbar era el único punto de la
                    // pantalla que no dejaba claro en cuál se estaba (ver
                    // panel de expertos v2, Estética #4).
                    title = householdName.ifBlank { "Task Hub" },
                    // pop() en vez de replaceAll(HomeScreen()) SIEMPRE: unifica con el
                    // atrás del sistema y con la convención del resto de pantallas de la
                    // app. Pero si esta pantalla se alcanzó vía replaceAll (tras crear
                    // perfil o unirse a un hogar — ver CreateProfileScreen/
                    // JoinHouseholdScreen — o el redirect AlreadyMember de más abajo),
                    // esta es la ÚNICA pantalla de la pila: pop() sería un no-op y la
                    // flecha se quedaría sin efecto visible, dejando al usuario atrapado
                    // (bug reportado 2026-09-11). canPop cubre ambos casos con el mismo
                    // destino al que ya iría el usuario en el resto de flujos: Home.
                    onBack = {
                        if (navigator.canPop) navigator.pop() else navigator.replaceAll(HomeScreen())
                    },
                    actions = {
                        // Notification bell with badge
                        Box {
                            IconButton(
                                onClick = {
                                    val mid = currentMemberId
                                    if (mid.isNotEmpty()) {
                                        navigator.push(NotificationListScreen(householdId, mid))
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Notifications, contentDescription = s("notifications_title"))
                            }
                            if (notificationUnreadCount > 0) {
                                PointsBadge(
                                    text = if (notificationUnreadCount > 99) "99+" else notificationUnreadCount.toString(),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = (-4).dp)
                                )
                            }
                        }
                        // Settings
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = s("profile_settings_label"))
                        }
                        // Delete: solo el owner del hogar la ve/ejecuta (ver isOwner arriba).
                        if (isDeleting || isLeaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                strokeWidth = 2.dp
                            )
                        } else if (isOwner) {
                            IconButton(onClick = { showConfirmDialog1 = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = s("household_delete_title"))
                            }
                        }
                    }
                )

                if (isDeleting || isLeaving) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(16.dp))
                            // Sin este texto, un borrado/salida que tarda (cascade-delete
                            // de tareas/asignaciones/historial) se ve como una pantalla
                            // colgada — panel de revisión 2026-09-03, Experto 5, IMPORTANTE #2.
                            Text(
                                text = s(if (isDeleting) "household_deleting_progress" else "household_leaving_progress"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Content
                    when (val hState = householdState) {
                        is HouseholdUiState.Loading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            ) {
                                ShimmerList(count = 4, itemHeight = 96.dp)
                            }
                        }

                        is HouseholdUiState.Success -> {
                            val household = hState.household

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Household info card
                                item {
                                    // Entrada con rebote UNA vez si se llega tras crear/unirse
                                    // a este hogar (delight fase 2, §2.2) — sin confeti aquí:
                                    // el confeti es la firma visual de "completar tarea"
                                    // (decisión de diseño del informe de delight, §6).
                                    val reduceMotion = shouldReduceMotion()
                                    var heroVisible by remember { mutableStateOf(!justCreated) }
                                    LaunchedEffect(Unit) {
                                        if (justCreated) heroVisible = true
                                    }
                                    val heroScale by animateFloatAsState(
                                        targetValue = if (heroVisible) 1f else 0.9f,
                                        animationSpec = if (reduceMotion) tween(0) else spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessLow
                                        ),
                                        label = "householdHeroScale"
                                    )
                                    val heroAlpha by animateFloatAsState(
                                        targetValue = if (heroVisible) 1f else 0f,
                                        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 300),
                                        label = "householdHeroAlpha"
                                    )
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                scaleX = heroScale
                                                scaleY = heroScale
                                                alpha = heroAlpha
                                            }
                                            // role = Button + contentDescription: la tarjeta era muda
                                            // para TalkBack (solo se leía el texto suelto, sin indicar
                                            // que es pulsable ni qué hace) — panel 2026-09-11, IMPORTANTE.
                                            .clickable(role = Role.Button) { showQrDialog = true }
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = s("household_invite_card_description")
                                                    .replace("%1\$s", household.name)
                                                    .replace("%2\$s", household.inviteCode)
                                            },
                                        // containerColor transparente: el degradado real vive en el
                                        // Modifier.background del Column de abajo (Card no acepta un
                                        // Brush en `colors`) — clip de Card ya recorta ese fondo a las
                                        // esquinas redondeadas de la tarjeta.
                                        colors = CardDefaults.cardColors(
                                            containerColor = Color.Transparent
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                // Degradado entre dos luminosidades de primaryContainer
                                                // en vez de primaryContainer→secondaryContainer (panel v14
                                                // 2026-09-20, hallazgo 1 midió ~3.9:1 con onPrimaryContainer en
                                                // DEFAULT oscuro) — ambos extremos son del mismo color, así que el
                                                // contraste auditado de onPrimaryContainer contra primaryContainer
                                                // se mantiene en todo el degradado (≥4.5:1 en los 3 temas).
                                                .background(
                                                    MaterialTheme.colorScheme.primaryContainer.let { primaryContainer ->
                                                        remember(primaryContainer) {
                                                            Brush.linearGradient(
                                                                colors = listOf(
                                                                    primaryContainer,
                                                                    lerp(primaryContainer, Color.Black, 0.08f)
                                                                )
                                                            )
                                                        }
                                                    }
                                                )
                                                .padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = "👥",
                                                style = MaterialTheme.typography.displaySmall
                                            )

                                            Spacer(modifier = Modifier.height(8.dp))

                                            Text(
                                                text = household.name,
                                                style = MaterialTheme.typography.headlineSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )

                                            Spacer(modifier = Modifier.height(16.dp))

                                            Text(
                                                text = s("household_invite_code"),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )

                                            Text(
                                                text = household.inviteCode,
                                                style = MaterialTheme.typography.headlineMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )

                                            Spacer(modifier = Modifier.height(4.dp))

                                            Text(
                                                text = s("household_share_code"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }

                                // Saludo + puntos/racha del miembro actual EN ESTE hogar
                                // (informe delight #9, redirigido aquí desde HomeScreen tras
                                // el rollback: con varios hogares, "Hola, {nombre}" con
                                // puntos/racha de UN solo hogar no tenía sentido en el
                                // dashboard agregado — aquí sí, porque son los del hogar
                                // que se está viendo). myMember ya resuelto arriba vía
                                // resolveCurrentMember(householdId).
                                myMember?.let { member ->
                                    item(key = "greeting") {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = s("household_greeting_hello").replace("%s", member.displayName),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    StatChip(
                                                        value = "${member.totalPoints}",
                                                        label = s("stats_summary_points"),
                                                        emoji = "⭐"
                                                    )
                                                    StatChip(
                                                        value = "${member.currentStreak}",
                                                        label = s("stats_current_streak_label"),
                                                        emoji = "🔥"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Navigation: Ver Tareas + Calendario
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                navigator.push(TaskListScreen(householdId, currentMemberId.ifEmpty { null }))
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                                            shape = MaterialTheme.shapes.large
                                        ) {
                                            Text(
                                                text = s("household_view_tasks_plain"),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                navigator.push(CalendarScreen(householdId, currentMemberId.ifEmpty { null }))
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = MaterialTheme.shapes.large
                                        ) {
                                            Text(
                                                text = s("personal_space_calendar"),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                // Estadísticas + ranking + recompensas (pantalla combinada)
                                item {
                                    Button(
                                        onClick = {
                                            navigator.push(ExploreScreen(householdId, currentMemberId))
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.large
                                    ) {
                                        Text(
                                            text = s("household_explore_button"),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                householdMemberList(
                                    membersExpanded = membersExpanded,
                                    onToggleExpanded = { membersExpanded = !membersExpanded },
                                    memberState = memberState,
                                    isMemberActionPending = memberActionState is MemberActionState.Loading,
                                    isAdmin = isAdmin,
                                    ownerUserId = ownerHousehold?.ownerId,
                                    myMember = myMember,
                                    s = s,
                                    onAppreciateClick = { member -> appreciateTarget = member },
                                    onDonateClick = { member -> donateTarget = member },
                                    onRoleChange = { member, newRole ->
                                        memberModel.updateMemberRole(householdId, member.id, newRole)
                                    },
                                    onRemoveMember = { member ->
                                        memberModel.removeMember(householdId, member.id)
                                    },
                                    onCreateTask = { member ->
                                        navigator.push(
                                            CreateTaskScreen(
                                                householdId = householdId,
                                                createdBy = currentMemberId,
                                                preselectedMemberId = member.id
                                            )
                                        )
                                    },
                                    onMemberClick = { member ->
                                        member.userId?.let { uid ->
                                            navigator.push(PublicProfileScreen(uid, member))
                                        }
                                    },
                                    onInviteClick = { showQrDialog = true }
                                )

                                // Chat de mensajes
                                item {
                                    HouseholdChatSection(
                                        s = s,
                                        messagesState = messagesState,
                                        newMessageText = newMessageText,
                                        onTextChange = householdModel::updateNewMessageText,
                                        onSend = { householdModel.sendMessage(householdId, currentMemberId) },
                                        onRefresh = { householdModel.loadMessages(householdId) },
                                        sendMessageError = sendMessageError,
                                        onDismissSendMessageError = { householdModel.clearSendMessageError() }
                                    )
                                }

                                // Salir (desvincularse) del hogar
                                item {
                                    OutlinedButton(
                                        onClick = { showLeaveDialog = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.large
                                    ) {
                                        Text(
                                            text = s("household_leave_button_full"),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                // Bottom spacer
                                item { Spacer(modifier = Modifier.height(16.dp)) }
                            }
                        }

                        is HouseholdUiState.Error -> {
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
                                            text = hState.message,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.error,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    if (hState.removable) {
                                        Button(
                                            onClick = {
                                                householdModel.removeGhostHousehold(householdId)
                                                navigator.replaceAll(HomeScreen())
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text(s("household_remove_ghost"))
                                        }
                                    } else {
                                        Button(onClick = { householdModel.loadHousehold(householdId) }) {
                                            Text(s("tasks_retry"))
                                        }
                                    }
                                }
                            }
                        }

                        is HouseholdUiState.AlreadyMember -> {
                            // Already a member — this shouldn't normally be shown on this screen
                            // Navigate directly
                            LaunchedEffect(Unit) {
                                navigator.replaceAll(HouseholdScreen(hState.household.id))
                            }
                        }

                        is HouseholdUiState.Idle -> {}
                    }
                }
            }

            ErrorAwareSnackbarHost(
                hostState = householdSnackbarHostState,
                errorIconContentDescription = s("error_icon_content_desc"),
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            }
        }
    }
}
