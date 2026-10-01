/**
 * Formulario para crear un hogar nuevo: navegado desde [WelcomeScreen]
 * ("Crear un hogar"). Al crearse con éxito, sustituye la pila de navegación
 * por [CreateProfileScreen] para dar de alta al primer miembro (el creador).
 * Usa [org.taskhub.ui.models.HouseholdScreenModel] para la creación.
 */
package org.taskhub.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.taskhub.ui.components.shouldReduceMotion
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.taskhub.network.models.SpaceType
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.TaskHubTopBar
import org.taskhub.ui.components.EmojiPicker
import org.taskhub.ui.components.filterChipCheckIcon
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.HouseholdScreenModel
import org.taskhub.ui.models.HouseholdUiState

/** Clave i18n del label y del placeholder contextual de cada [SpaceType], en el orden mostrado en el selector. */
private val SPACE_TYPE_OPTIONS = listOf(
    SpaceType.HOME to ("space_type_home" to "space_type_placeholder_home"),
    SpaceType.FLAT_SHARE to ("space_type_flat" to "space_type_placeholder_flat"),
    SpaceType.STUDY_GROUP to ("space_type_study" to "space_type_placeholder_study"),
    SpaceType.CLUB to ("space_type_club" to "space_type_placeholder_club")
)

/** Formulario mínimo: un único campo (nombre del hogar) obligatorio. */
class CreateHouseholdScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = koinScreenModel<HouseholdScreenModel>()
        val uiState by model.uiState.collectAsState()
        val appSettings = LocalAppSettings.current
        val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

        var householdName by remember { mutableStateOf("") }
        var selectedType by remember { mutableStateOf(SpaceType.HOME) }
        // `null` = sin elección manual, se usa el emoji del SpaceType seleccionado.
        var customEmoji by remember { mutableStateOf<String?>(null) }
        val focusManager = LocalFocusManager.current

        val effectiveEmoji = customEmoji ?: selectedType.emoji

        LaunchedEffect(Unit) {
            model.reset()
        }

        // Navegar a la pantalla de crear perfil cuando se crea exitosamente
        LaunchedEffect(uiState) {
            if (uiState is HouseholdUiState.Success) {
                navigator.replaceAll(CreateProfileScreen((uiState as HouseholdUiState.Success).household.id))
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TaskHubTopBar(
                    title = s("create_household_title"),
                    onBack = { navigator.pop() }
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "👥",
                    style = MaterialTheme.typography.displayMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = s("create_household_title"),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = s("create_household_subtitle"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = s("space_type_selector_title"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SPACE_TYPE_OPTIONS.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEach { (type, labelKeys) ->
                                val (labelKey, _) = labelKeys
                                FilterChip(
                                    selected = selectedType == type,
                                    onClick = { selectedType = type },
                                    label = {
                                        Text(
                                            text = s(labelKey),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            // Oculta el emoji para TalkBack/VoiceOver (p.ej. "balón de
                                            // fútbol" antes de "Grupo o club" suena desconectado del
                                            // texto) dejando solo el nombre del tipo como nombre accesible.
                                            modifier = Modifier.clearAndSetSemantics {
                                                contentDescription = s(labelKey).removePrefix(type.emoji).trim()
                                            }
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    leadingIcon = filterChipCheckIcon(selectedType == type)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = s("create_household_emoji_label"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                                EmojiPicker(
                                    currentEmoji = effectiveEmoji,
                                    onEmojiSelected = { customEmoji = it },
                                    buttonLabel = s("create_household_emoji_selected").replace("%s", effectiveEmoji),
                                    emojiContentDesc = s("edit_profile_emoji_content_desc"),
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = householdName,
                    onValueChange = { householdName = it },
                    label = { Text(s("create_household_name_label")) },
                    placeholder = { Text(s(SPACE_TYPE_OPTIONS.first { it.first == selectedType }.second.second)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    enabled = uiState !is HouseholdUiState.Loading
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        model.createHousehold(householdName.trim(), selectedType, customEmoji)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = householdName.isNotBlank() && uiState !is HouseholdUiState.Loading,
                    shape = MaterialTheme.shapes.large
                ) {
                    if (uiState is HouseholdUiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(s("welcome_create"), style = MaterialTheme.typography.titleMedium)
                    }
                }

                // Show errors
                when (val state = uiState) {
                    is HouseholdUiState.Error -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = s("error_icon_content_desc"),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                // Mismo patrón que AuthGateScreen/JoinHouseholdScreen: sin
                                // esto, TalkBack/VoiceOver no anuncia el error al aparecer.
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }

                    else -> {}
                }
                }
            }
        }
    }
}
