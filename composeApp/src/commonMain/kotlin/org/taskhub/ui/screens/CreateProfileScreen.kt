/**
 * Formulario para dar de alta el primer miembro de un hogar recién creado.
 * Navegado únicamente desde [CreateHouseholdScreen] tras crear el hogar; si
 * el usuario ya es miembro, redirige directamente a [HouseholdScreen]. Usa
 * [org.taskhub.ui.models.HouseholdScreenModel] (para cargar el hogar) y
 * [org.taskhub.ui.models.MemberScreenModel] (para crear el miembro).
 */
package org.taskhub.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.koin.compose.koinInject
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.TaskHubTopBar
import org.taskhub.ui.components.filterChipCheckIcon
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.GoogleAuthManager
import org.taskhub.ui.models.GoogleAuthState
import org.taskhub.ui.models.HouseholdScreenModel
import org.taskhub.ui.models.HouseholdUiState
import org.taskhub.ui.models.MemberScreenModel
import org.taskhub.ui.models.MemberUiState

/**
 * Crea el PRIMER perfil de miembro justo tras crear un hogar nuevo
 * (navegado únicamente desde [CreateHouseholdScreen]). A diferencia de
 * [JoinHouseholdScreen] (alta por invitación, rol forzado a "child"), aquí
 * SÍ se deja elegir rol libremente porque quien llega a esta pantalla es,
 * por construcción, el `ownerId` del hogar recién creado (nadie más puede
 * alcanzarla) — coincide con la rama `isOwner(hid)` de
 * `firestore.rules` (households/{hid}/members/{mid}.create), que también
 * permite elegir rol libremente para esta cuenta.
 */
data class CreateProfileScreen(val householdId: String) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val householdModel = koinScreenModel<HouseholdScreenModel>()
        val memberModel = koinScreenModel<MemberScreenModel>()
        val authManager = koinInject<GoogleAuthManager>()
        val householdState by householdModel.uiState.collectAsState()
        val memberState by memberModel.uiState.collectAsState()
        val authState by authManager.state.collectAsState()
        val appSettings = LocalAppSettings.current
        val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

        var displayName by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("admin") }
        var nameAutoFilled by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current

        // Pre-rellena el nombre con el de la cuenta de Google si el usuario
        // todavía no ha escrito nada — sigue siendo editable.
        LaunchedEffect(authState) {
            val signedIn = authState as? GoogleAuthState.SignedIn
            if (displayName.isEmpty() && !signedIn?.displayName.isNullOrBlank()) {
                displayName = signedIn.displayName
                nameAutoFilled = true
            }
        }

        LaunchedEffect(Unit) {
            memberModel.reset()
        }

        // Load household info if not already loaded
        LaunchedEffect(householdId) {
            if (householdState is HouseholdUiState.Idle) {
                householdModel.loadHousehold(householdId)
            }
        }

        // When member is created, navigate to household
        LaunchedEffect(memberState) {
            if (memberState is MemberUiState.Success) {
                navigator.replaceAll(HouseholdScreen(householdId, justCreated = true))
            }
        }

        // Check if current user is already a member — skip this step
        LaunchedEffect(householdState) {
            if (householdState is HouseholdUiState.AlreadyMember) {
                navigator.replaceAll(HouseholdScreen(householdId))
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TaskHubTopBar(
                    title = s("create_profile_title"),
                    // Se llega aquí SIEMPRE vía replaceAll (desde CreateHouseholdScreen),
                    // así que esta es la única pantalla de la pila: navigator.pop() sería
                    // un no-op y la flecha de atrás no haría nada (bug reportado
                    // 2026-09-11). canPop cubre el caso general por si esto cambia.
                    onBack = {
                        if (navigator.canPop) navigator.pop() else navigator.replaceAll(HomeScreen())
                    }
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "👤",
                    style = MaterialTheme.typography.displayMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = s("create_profile_title"),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Show household name if available
                val householdName = when (val state = householdState) {
                    is HouseholdUiState.Success -> state.household.name
                    else -> null
                }

                Text(
                    text = if (householdName != null) {
                        s("create_profile_subtitle_named").replace("%s", householdName)
                    } else {
                        s("create_profile_subtitle_generic")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(32.dp))

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; nameAutoFilled = false },
                    label = { Text(s("create_profile_name_label")) },
                    placeholder = { Text(s("create_profile_name_placeholder")) },
                    singleLine = true,
                    // liveRegion solo mientras el valor sigue siendo el auto-relleno
                    // sin tocar: se apaga en el primer onValueChange (edición real del
                    // usuario) para no seguir anunciando cada tecleo como si fuera un
                    // cambio ajeno al usuario (panel v22, corrige v21 #1).

                    modifier = if (nameAutoFilled)
                        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                    else
                        Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    enabled = memberState !is MemberUiState.Loading
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = s("create_profile_role_label"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilterChip(
                        selected = role == "admin",
                        onClick = { role = "admin" },
                        label = { Text(s("member_role_admin_short")) },
                        modifier = Modifier.weight(1f),
                        leadingIcon = filterChipCheckIcon(role == "admin")
                    )
                    FilterChip(
                        selected = role == "child",
                        onClick = { role = "child" },
                        label = { Text(s("member_role_child_short")) },
                        modifier = Modifier.weight(1f),
                        leadingIcon = filterChipCheckIcon(role == "child")
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val userId = householdModel.getLocalId()
                        memberModel.addMember(householdId, displayName.trim(), role, userId = userId)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = displayName.isNotBlank() && memberState !is MemberUiState.Loading,
                    shape = MaterialTheme.shapes.large
                ) {
                    if (memberState is MemberUiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(s("create_profile_submit"), style = MaterialTheme.typography.titleMedium)
                    }
                }

                // Show errors
                if (memberState is MemberUiState.Error) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = s("error_icon_content_desc"),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = (memberState as MemberUiState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            // Mismo patrón que AuthGateScreen/JoinHouseholdScreen: sin
                            // esto, TalkBack/VoiceOver no anuncia el error al aparecer.
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
                }
            }
        }
    }
}
