/**
 * Flujo de dos pasos para unirse a un hogar existente mediante código de
 * invitación: navegado desde [WelcomeScreen] ("Unirse a un hogar"). Paso 1
 * valida el código contra Firestore ([org.taskhub.ui.models.HouseholdScreenModel]);
 * paso 2 crea el perfil del nuevo miembro
 * ([org.taskhub.ui.models.MemberScreenModel]) y navega a [HouseholdScreen].
 */
package org.taskhub.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.GoogleAuthManager
import org.taskhub.ui.models.GoogleAuthState
import org.taskhub.ui.models.HouseholdScreenModel
import org.taskhub.ui.models.HouseholdUiState
import org.taskhub.ui.models.MemberScreenModel
import org.taskhub.ui.models.MemberUiState

/**
 * Screen con estado interno de "wizard": el paso mostrado se deriva de
 * [joinedHouseholdId] (null = paso 1, código; no-null = paso 2, nombre).
 */
class JoinHouseholdScreen : Screen {

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

        var inviteCode by remember { mutableStateOf("") }
        var codeTouched by remember { mutableStateOf(false) }
        var displayName by remember { mutableStateOf("") }
        var nameAutoFilled by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current

        // Track the joined household
        var joinedHouseholdId by remember { mutableStateOf<String?>(null) }

        // Pre-rellena el nombre del paso 2 con el de la cuenta de Google si el
        // usuario todavía no ha escrito nada — sigue siendo editable.
        LaunchedEffect(authState) {
            val signedIn = authState as? GoogleAuthState.SignedIn
            if (displayName.isEmpty() && !signedIn?.displayName.isNullOrBlank()) {
                displayName = signedIn.displayName
                nameAutoFilled = true
            }
        }

        LaunchedEffect(Unit) {
            householdModel.reset()
            memberModel.reset()
        }

        // When household join succeeds, capture the household id
        LaunchedEffect(householdState) {
            when (val state = householdState) {
                is HouseholdUiState.Success -> {
                    if (joinedHouseholdId == null) {
                        joinedHouseholdId = (state).household.id
                    }
                }
                is HouseholdUiState.AlreadyMember -> {
                    navigator.replaceAll(HouseholdScreen(state.household.id))
                }
                else -> {}
            }
        }

        // When member is created, navigate to household
        LaunchedEffect(memberState) {
            if (memberState is MemberUiState.Success && joinedHouseholdId != null) {
                navigator.replaceAll(HouseholdScreen(joinedHouseholdId!!, justCreated = true))
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TaskHubTopBar(
                    title = s("welcome_join"),
                    onBack = { navigator.pop() }
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(32.dp))

                    // Step indicator
                    val step = when {
                    joinedHouseholdId == null -> 1
                    householdState is HouseholdUiState.Success && memberState !is MemberUiState.Success -> 2
                    else -> 1
                }

                Text(
                    text = "🔑",
                    style = MaterialTheme.typography.displayMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = s("welcome_join"),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (step == 1) s("join_household_step1")
                    else s("join_household_step2"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Step 1: Enter invite code
                if (joinedHouseholdId == null) {
                    // Panel v17 (hallazgo MENOR de UX): antes el botón "Unirse"
                    // simplemente aparecía gris sin que el campo explicara por
                    // qué — mismo patrón isError/supportingText que el resto
                    // de formularios (CreateTaskScreen, CreateRewardScreen).
                    val codeInvalid = codeTouched && inviteCode.trim().length < 4
                    OutlinedTextField(
                        value = inviteCode,
                        // Códigos de invitación son alfanuméricos en mayúsculas, máx. 8 chars.
                        onValueChange = {
                            inviteCode = it.uppercase().take(8)
                            codeTouched = true
                        },
                        label = { Text(s("household_invite_code")) },
                        placeholder = { Text(s("join_household_code_placeholder")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        enabled = householdState !is HouseholdUiState.Loading,
                        isError = codeInvalid,
                        supportingText = { Text(s("join_household_code_hint")) }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            householdModel.joinHousehold(inviteCode.trim())
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = inviteCode.trim().length >= 4 && householdState !is HouseholdUiState.Loading,
                        shape = MaterialTheme.shapes.large
                    ) {
                        if (householdState is HouseholdUiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(s("join_household_submit"), style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    if (householdState is HouseholdUiState.Error) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = s("error_icon_content_desc"),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = (householdState as HouseholdUiState.Error).message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }
                }

                // "Te uniste a..." — entrada con rebote (delight fase 2, §2.2).
                // Se mantiene siempre en composición (no gateada por el `if` de
                // abajo) para que AnimatedVisibility pueda animar la transición
                // null→no-null: si se gatease dentro del `if`, Compose la
                // compondría ya en estado visible=true y se saltaría la
                // animación de entrada (comportamiento documentado de
                // AnimatedVisibility con parámetro booleano).
                val reduceMotion = shouldReduceMotion()
                val joinedHousehold = (householdState as? HouseholdUiState.Success)?.household
                AnimatedVisibility(
                    visible = joinedHouseholdId != null && joinedHousehold != null,
                    enter = if (reduceMotion) EnterTransition.None else
                        fadeIn(tween(250)) + scaleIn(
                            initialScale = 0.92f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                        )
                ) {
                    if (joinedHousehold != null) {
                        Column {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = s("join_household_joined_prefix"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = joinedHousehold.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }

                // Step 2: Create profile (after joining)
                if (joinedHouseholdId != null && householdState is HouseholdUiState.Success) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it; nameAutoFilled = false },
                        label = { Text(s("create_profile_name_label")) },
                        placeholder = { Text(s("create_profile_name_placeholder")) },
                        singleLine = true,
                        // liveRegion solo mientras el valor sigue siendo el auto-relleno
                        // sin tocar: se apaga en el primer onValueChange (edición real
                        // del usuario), ver CreateProfileScreen.kt (panel v22, corrige v21 #1).
                        modifier = if (nameAutoFilled)
                            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                        else
                            Modifier.fillMaxWidth(),
                        enabled = memberState !is MemberUiState.Loading
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            val userId = householdModel.getLocalId()
                            // Unirse por invitación siempre entra como "child"
                            // (display "Miembro") — nadie se autoproclama admin.
                            // Reforzado también en firestore.rules v4. Un
                            // admin/owner existente puede ascenderlo después.
                            memberModel.addMember(joinedHouseholdId!!, displayName.trim(), "child", userId = userId, inviteCode = inviteCode.trim())
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
                            Text(s("join_household_enter_space"), style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    if (memberState is MemberUiState.Error) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = s("error_icon_content_desc"),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = (memberState as MemberUiState.Error).message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }
                }
                }
            }
        }
    }
}
