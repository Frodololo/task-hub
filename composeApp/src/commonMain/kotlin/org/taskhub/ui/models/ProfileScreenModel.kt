// ScreenModel del perfil global de usuario (no confundir con el perfil de
// "miembro" dentro de un hogar, ver [MemberScreenModel]): nombre, avatar,
// bio y estado, que se guardan a nivel de cuenta y se ven igual en todos los
// hogares del usuario. Usado por las pantallas de perfil propio (editable) y
// perfil de otro usuario (solo lectura) en `ui/screens/`. Habla con
// [FirestoreRepository] (colección de perfiles de usuario).
package org.taskhub.ui.models

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.taskhub.network.FirestoreRepository
import org.taskhub.network.models.UserProfile
import org.taskhub.platform.AppLog
import org.taskhub.platform.setAnalyticsCollectionEnabled
import org.taskhub.platform.HapticKind
import org.taskhub.ui.components.hapticsEnabled
import org.taskhub.platform.vibrate
import org.taskhub.storage.SettingsStore
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.i18n.toUserMessage

/**
 * Estados de carga del perfil de usuario (propio o ajeno).
 */
sealed class ProfileUiState {
    /** Aún no se ha pedido cargar el perfil. */
    data object Idle : ProfileUiState()
    /** Carga en curso ([ProfileScreenModel.loadMyProfile] o [ProfileScreenModel.loadUserProfile]). */
    data object Loading : ProfileUiState()
    /** Perfil disponible (real, o uno por defecto si el usuario aún no tiene documento de perfil). */
    data class Success(val profile: UserProfile) : ProfileUiState()
    /** Fallo al cargar; [message] listo para mostrar. */
    data class Error(val message: String) : ProfileUiState()
}

/**
 * ScreenModel para cargar y actualizar el perfil global de usuario.
 * Maneja tanto el perfil propio (editable) como el de otros (solo lectura).
 */
class ProfileScreenModel(
    private val repo: FirestoreRepository,
    private val settingsStore: SettingsStore
) : ScreenModel {

    private fun buzz(kind: HapticKind) {
        if (hapticsEnabled(settingsStore)) vibrate(kind)
    }

    private fun s(key: String) = AppStrings.get(key, settingsStore.getLanguage())

    private val _myProfileState = MutableStateFlow<ProfileUiState>(ProfileUiState.Idle)
    /** Perfil del usuario autenticado en este dispositivo, para la pantalla de edición. */
    val myProfileState: StateFlow<ProfileUiState> = _myProfileState.asStateFlow()

    private val _otherProfileState = MutableStateFlow<ProfileUiState>(ProfileUiState.Idle)
    /** Perfil de OTRO usuario (vista pública, solo lectura), independiente del propio. */
    val otherProfileState: StateFlow<ProfileUiState> = _otherProfileState.asStateFlow()

    private val _saveState = MutableStateFlow<ProfileSaveState>(ProfileSaveState.Idle)
    /** Progreso del guardado del perfil propio (ver [ProfileSaveState]). */
    val saveState: StateFlow<ProfileSaveState> = _saveState.asStateFlow()

    /**
     * Carga el perfil del usuario actual (para editar). Si aún no tiene
     * documento de perfil en Firestore (usuario nuevo), expone un
     * [UserProfile] vacío con solo el [UserProfile.id] relleno en vez de un
     * error, para que la pantalla de edición arranque en blanco.
     */
    fun loadMyProfile() {
        val userId = repo.getLocalId() ?: run {
            _myProfileState.value = ProfileUiState.Error(s("profile_error_not_authenticated"))
            return
        }
        screenModelScope.launch {
            _myProfileState.value = ProfileUiState.Loading
            try {
                val profile = repo.getUserProfile(userId)
                if (profile != null) {
                    _myProfileState.value = ProfileUiState.Success(profile)
                } else {
                    // Perfil vacío por defecto — el usuario lo rellenará
                    _myProfileState.value = ProfileUiState.Success(
                        UserProfile(id = userId)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("ProfileScreenModel", "loadMyProfile failed", e)
                _myProfileState.value = ProfileUiState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "profile_error_loading_own")
                )
            }
        }
    }

    /** Carga el perfil de otro usuario (vista pública). */
    fun loadUserProfile(userId: String) {
        if (userId.isBlank()) return
        screenModelScope.launch {
            _otherProfileState.value = ProfileUiState.Loading
            try {
                val profile = repo.getUserProfile(userId)
                if (profile != null) {
                    _otherProfileState.value = ProfileUiState.Success(profile)
                } else {
                    _otherProfileState.value = ProfileUiState.Success(
                        UserProfile(id = userId, displayName = s("profile_default_name"))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("ProfileScreenModel", "loadUserProfile failed for user $userId", e)
                _otherProfileState.value = ProfileUiState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "profile_error_loading")
                )
            }
        }
    }

    /** Guarda los cambios del perfil propio. */
    fun saveProfile(
        displayName: String,
        bio: String,
        status: String,
        avatarEmoji: String
    ) {
        val userId = repo.getLocalId() ?: run {
            _saveState.value = ProfileSaveState.Error(s("profile_error_not_authenticated"))
            return
        }
        // Preserva la avatarUrl (foto de Google) ya cargada: upsertUserProfile
        // escribe null en avatarUrl si no se le pasa explícitamente.
        val currentAvatarUrl = (_myProfileState.value as? ProfileUiState.Success)?.profile?.avatarUrl
        screenModelScope.launch {
            _saveState.value = ProfileSaveState.Saving
            try {
                repo.upsertUserProfile(
                    userId = userId,
                    displayName = displayName,
                    avatarUrl = currentAvatarUrl,
                    avatarEmoji = avatarEmoji,
                    bio = bio,
                    status = status
                )
                _saveState.value = ProfileSaveState.Saved
                buzz(HapticKind.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("ProfileScreenModel", "saveProfile failed for user $userId", e)
                _saveState.value = ProfileSaveState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "profile_error_saving")
                )
                buzz(HapticKind.ERROR)
            }
        }
    }

    fun reset() {
        _myProfileState.value = ProfileUiState.Idle
        _otherProfileState.value = ProfileUiState.Idle
        _saveState.value = ProfileSaveState.Idle
    }

    fun clearSaveState() {
        _saveState.value = ProfileSaveState.Idle
    }

    /** Aplica el opt-out de analytics (RGPD): persiste la preferencia y lo propaga al SDK. */
    fun setAnalyticsOptOut(optOut: Boolean) {
        settingsStore.setAnalyticsOptOut(optOut)
        setAnalyticsCollectionEnabled(!optOut)
    }
}

/** Estado del guardado del perfil. */
sealed class ProfileSaveState {
    data object Idle : ProfileSaveState()
    data object Saving : ProfileSaveState()
    data object Saved : ProfileSaveState()
    data class Error(val message: String) : ProfileSaveState()
}