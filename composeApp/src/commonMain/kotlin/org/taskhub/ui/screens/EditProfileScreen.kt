/**
 * Pantalla de edición del perfil global (no por hogar) del usuario. Se
 * navega desde el panel de Ajustes ([org.taskhub.ui.components.SettingsSheet],
 * botón "Editar perfil") en varias pantallas (Home, Welcome, TaskList,
 * Profile, Household). Usa [org.taskhub.ui.models.ProfileScreenModel] tanto
 * para cargar el perfil actual como para persistir los cambios.
 */
package org.taskhub.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.TaskHubTopBar
import org.taskhub.ui.components.EmojiPicker
import org.taskhub.ui.components.EMOJI_OPTIONS
import org.taskhub.ui.components.UserAvatar
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.ProfileScreenModel
import org.taskhub.ui.models.ProfileUiState
import org.taskhub.ui.models.ProfileSaveState

/**
 * Pantalla para editar el perfil GLOBAL del usuario actual.
 * Accesible desde Ajustes → "Editar perfil".
 *
 * Campos editables:
 * - Nombre público (displayName)
 * - Avatar emoji (selector rápido de emojis)
 * - Bio (frase corta)
 * - Estado (mensaje efímero)
 */
class EditProfileScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = koinScreenModel<ProfileScreenModel>()
        val profileState by model.myProfileState.collectAsState()
        val saveState by model.saveState.collectAsState()
        val focusManager = LocalFocusManager.current
        val appSettings = LocalAppSettings.current
        val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

        // Form fields — filled from loaded profile
        var displayName by remember { mutableStateOf("") }
        var avatarEmoji by remember { mutableStateOf("") }
        var bio by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }

        // Cargar perfil al entrar
        LaunchedEffect(Unit) {
            model.loadMyProfile()
        }

        // Rellenar campos cuando el perfil cargue
        LaunchedEffect(profileState) {
            if (profileState is ProfileUiState.Success) {
                val p = (profileState as ProfileUiState.Success).profile
                if (displayName.isEmpty()) displayName = p.displayName
                if (avatarEmoji.isEmpty()) avatarEmoji = p.avatarEmoji
                if (bio.isEmpty()) bio = p.bio
                if (status.isEmpty()) status = p.status
            }
        }

        // Navegar atrás al guardar
        LaunchedEffect(saveState) {
            if (saveState is ProfileSaveState.Saved) {
                model.clearSaveState()
                navigator.pop()
            }
        }

        Scaffold(
            // TaskHubTopBar (no TopAppBar manual): consistencia con las otras 19
            // pantallas — la barra manual dejaba el título alineado a la izquierda
            // en vez de centrado.
            topBar = {
                TaskHubTopBar(
                    title = s("edit_profile_title"),
                    onBack = { navigator.pop() }
                )
            }
        ) { padding ->
            when (val state = profileState) {
                is ProfileUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                is ProfileUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
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
                                    state.message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyLarge,
                                    // Mismo patrón que AuthGateScreen/JoinHouseholdScreen: sin
                                    // esto, TalkBack/VoiceOver no anuncia el error al aparecer.
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { model.loadMyProfile() }) {
                                Text(s("tasks_retry"))
                            }
                        }
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // ── Avatar preview: foto > emoji > inicial ──
                        val currentAvatarUrl = (state as? ProfileUiState.Success)?.profile?.avatarUrl
                        UserAvatar(
                            avatarUrl = currentAvatarUrl,
                            fallbackEmoji = avatarEmoji,
                            displayName = displayName,
                            contentDescription = s("profile_avatar_content_desc"),
                            size = 96.dp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )

                        Text(
                            text = s("edit_profile_choose_avatar"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Emoji grid
                        EmojiPicker(
                            currentEmoji = avatarEmoji,
                            onEmojiSelected = { avatarEmoji = it },
                            buttonLabel = if (avatarEmoji.isNotEmpty())
                                s("edit_profile_avatar_selected").replace("%s", avatarEmoji)
                            else
                                s("edit_profile_select_emoji"),
                            emojiContentDesc = s("edit_profile_emoji_content_desc"),
                        )

                        HorizontalDivider()

                        // ── Nombre ──
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text(s("edit_profile_name_label")) },
                            placeholder = { Text(s("edit_profile_name_placeholder")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )

                        // ── Bio ──
                        OutlinedTextField(
                            value = bio,
                            onValueChange = { if (it.length <= 100) bio = it },
                            label = { Text(s("edit_profile_bio_label")) },
                            placeholder = { Text(s("edit_profile_bio_placeholder")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            supportingText = { Text("${bio.length}/100") }
                        )

                        // ── Estado ──
                        OutlinedTextField(
                            value = status,
                            onValueChange = { if (it.length <= 80) status = it },
                            label = { Text(s("edit_profile_status_label")) },
                            placeholder = { Text(s("edit_profile_status_placeholder")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() }
                            ),
                            supportingText = { Text("${status.length}/80") }
                        )

                        Spacer(Modifier.height(8.dp))

                        // ── Save button ──
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                model.saveProfile(
                                    displayName = displayName.trim(),
                                    bio = bio.trim(),
                                    status = status.trim(),
                                    avatarEmoji = avatarEmoji
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = displayName.isNotBlank() &&
                                     saveState !is ProfileSaveState.Saving,
                            shape = MaterialTheme.shapes.large
                        ) {
                            if (saveState is ProfileSaveState.Saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(s("edit_profile_save"), style = MaterialTheme.typography.titleMedium)
                            }
                        }

                        // Error al guardar
                        if (saveState is ProfileSaveState.Error) {
                            Row(
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = s("error_icon_content_desc"),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    (saveState as ProfileSaveState.Error).message,
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
}