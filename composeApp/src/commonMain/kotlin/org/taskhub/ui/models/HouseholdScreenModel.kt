/**
 * ScreenModel de gestión de hogares: crear/unirse/cargar/abandonar/borrar un
 * hogar y el chat de mensajes del hogar. Lo usan [org.taskhub.ui.screens.HouseholdScreen],
 * [org.taskhub.ui.screens.CreateHouseholdScreen] y [org.taskhub.ui.screens.JoinHouseholdScreen].
 * Tras cualquier cambio en la membresía persiste el hogar en
 * [HouseholdStore] (caché local) y sincroniza la lista en la nube vía
 * [GoogleAuthManager.syncHouseholdsToCloud].
 */
package org.taskhub.ui.models

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.taskhub.network.ErrorCategory
import org.taskhub.network.errorCategory
import org.taskhub.network.FirestoreRepository
import org.taskhub.network.HouseholdRepository
import org.taskhub.network.models.HouseholdResponse
import org.taskhub.network.models.MemberResponse
import org.taskhub.network.models.MessageResponse
import org.taskhub.storage.HouseholdStore
import org.taskhub.storage.SettingsStore
import org.taskhub.platform.AppLog
import org.taskhub.platform.HapticKind
import org.taskhub.ui.components.hapticsEnabled
import org.taskhub.platform.logAnalyticsEvent
import org.taskhub.platform.vibrate
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.i18n.toUserMessage

/** Estados de carga/creación/unión a un hogar. */
sealed class HouseholdUiState {
    data object Idle : HouseholdUiState()
    data object Loading : HouseholdUiState()
    data class Success(val household: HouseholdResponse) : HouseholdUiState()
    data class AlreadyMember(val household: HouseholdResponse) : HouseholdUiState()
    /**
     * [removable] es true cuando Firestore confirmó (404/403) que el hogar ya
     * no existe o no es accesible — la UI puede ofrecer "quitar de mis espacios"
     * en vez de solo "reintentar", que nunca funcionaría en ese caso.
     */
    data class Error(val message: String, val removable: Boolean = false) : HouseholdUiState()
}

/**
 * Tope de mensajes que trae [HouseholdScreenModel.loadMessages] (chat en
 * pantalla, sondeado cada 60s desde [org.taskhub.ui.screens.HouseholdScreen])
 * — antes releía la subcolección `messages` COMPLETA en cada ciclo, con
 * coste de red creciente sin cota conforme el chat envejecía (tarjeta kanban
 * "Paginación getMessages/getNotifications", 2026-09-13). No afecta a
 * [HouseholdRepository.anonymizeMemberMessages], que necesita ver TODO el
 * historial para poder anonimizar mensajes antiguos.
 */
private const val MAX_POLLED_MESSAGES = 300

/** Estados de carga del chat de mensajes del hogar. */
sealed class MessagesUiState {
    data object Idle : MessagesUiState()
    data object Loading : MessagesUiState()
    data class Success(val messages: List<MessageResponse>) : MessagesUiState()
    data class Error(val message: String) : MessagesUiState()
}

/**
 * ScreenModel de un hogar concreto: expone [uiState] ([HouseholdUiState])
 * para crear/unirse/cargar/abandonar/borrar y [messagesUiState] para el
 * chat del hogar.
 */
class HouseholdScreenModel(
    private val repo: FirestoreRepository,
    private val householdStore: HouseholdStore,
    private val authManager: GoogleAuthManager,
    private val settingsStore: SettingsStore
) : ScreenModel {

    private fun buzz(kind: HapticKind) {
        if (hapticsEnabled(settingsStore)) vibrate(kind)
    }

    private fun s(key: String) = AppStrings.get(key, settingsStore.getLanguage())

    private val _uiState = MutableStateFlow<HouseholdUiState>(HouseholdUiState.Idle)
    val uiState: StateFlow<HouseholdUiState> = _uiState.asStateFlow()

    /** Ver [FirestoreRepository.resolveCurrentMember]. */
    suspend fun resolveCurrentMember(householdId: String): String = repo.resolveCurrentMember(householdId)

    /** Ver [FirestoreRepository.appreciationRemaining]. */
    fun appreciationRemaining(member: MemberResponse): Int = repo.appreciationRemaining(member)

    /** Crea un hogar nuevo con [name] y lo guarda como hogar actual del usuario. */
    fun createHousehold(name: String) {
        // Panel v16 (2026-09-24), hallazgo QA: `createHousehold` NO es
        // idempotente (cada llamada crea un documento Firestore nuevo con ID
        // autogenerado) — era la única mutación del código sin esta guarda
        // ya usada de forma consistente en el resto de acciones
        // (createTask/completeTask/addMember/redeemReward...). Sin ella, un
        // doble-tap muy rápido podía crear dos hogares distintos, uno de
        // ellos huérfano en Firestore.
        if (_uiState.value == HouseholdUiState.Loading) return
        screenModelScope.launch {
            _uiState.value = HouseholdUiState.Loading
            try {
                val household = repo.createHousehold(name)
                householdStore.saveHousehold(household.id, household.name, household.inviteCode)
                authManager.syncHouseholdsToCloud()
                logAnalyticsEvent("household_created")
                _uiState.value = HouseholdUiState.Success(household)
                buzz(HapticKind.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("HouseholdScreenModel", "createHousehold failed", e)
                _uiState.value = HouseholdUiState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "household_error_creating")
                )
                buzz(HapticKind.ERROR)
            }
        }
    }

    /**
     * Se une a un hogar existente mediante [inviteCode]. Si el usuario ya era
     * miembro no vuelve a crear perfil, solo emite [HouseholdUiState.AlreadyMember]
     * para que la UI navegue directo al hogar sin duplicar el alta.
     */
    fun joinHousehold(inviteCode: String) {
        // Panel v16, hallazgo QA: ver mismo motivo en createHousehold().
        if (_uiState.value == HouseholdUiState.Loading) return
        screenModelScope.launch {
            _uiState.value = HouseholdUiState.Loading
            try {
                val household = repo.joinHousehold(inviteCode)

                householdStore.saveHousehold(household.id, household.name, household.inviteCode)

                // Si ya somos miembros (con cualquiera de nuestras identidades),
                // no volvemos a crear perfil: navegamos directo al hogar.
                if (repo.isCurrentUserMember(household.id)) {
                    _uiState.value = HouseholdUiState.AlreadyMember(household)
                } else {
                    logAnalyticsEvent("household_joined")
                    _uiState.value = HouseholdUiState.Success(household)
                }
                authManager.syncHouseholdsToCloud()
                buzz(HapticKind.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HouseholdRepository.InvalidInviteCodeException) {
                // Por tipo, no por e.message (fijo en español desde el repo,
                // nunca null) — panel de revisión 2026-09-10, Experto 2,
                // IMPORTANTE.
                AppLog.w("HouseholdScreenModel", "joinHousehold: invalid invite code", e)
                _uiState.value = HouseholdUiState.Error(s("household_error_invalid_invite_code"))
                buzz(HapticKind.ERROR)
            } catch (e: Exception) {
                // Fallback genérico: antes reutilizaba "código de invitación
                // inválido" incluso para un fallo de RED (sin conexión/5xx),
                // mostrando un mensaje de validación equivocado ante un
                // problema que nada tiene que ver con el código en sí.
                AppLog.e("HouseholdScreenModel", "joinHousehold failed", e)
                _uiState.value = HouseholdUiState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "household_error_joining")
                )
                buzz(HapticKind.ERROR)
            }
        }
    }

    /**
     * Carga los datos de un hogar por [id]. Si Firestore responde 404/403
     * (hogar borrado o sin acceso), marca el error como `removable` para que
     * la UI ofrezca quitarlo de la lista local en vez de solo "reintentar".
     */
    fun loadHousehold(id: String) {
        screenModelScope.launch {
            _uiState.value = HouseholdUiState.Loading
            try {
                val household = repo.getHousehold(id)
                _uiState.value = HouseholdUiState.Success(household)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("HouseholdScreenModel", "loadHousehold failed", e)
                _uiState.value = HouseholdUiState.Error(
                    message = e.toUserMessage(settingsStore.getLanguage(), "household_error_loading"),
                    removable = e.errorCategory() == ErrorCategory.GONE_OR_FORBIDDEN
                )
            }
        }
    }

    /** Quita un hogar inaccesible de la caché local (ver [HouseholdUiState.Error.removable]). */
    fun removeGhostHousehold(householdId: String) {
        householdStore.removeHousehold(householdId)
    }

    /** Vuelve [uiState] a [HouseholdUiState.Idle]. */
    fun reset() {
        _uiState.value = HouseholdUiState.Idle
    }

    /** Borra el hogar [householdId] en cascada (solo el owner puede hacerlo, ver `firestore.rules`). */
    fun deleteHousehold(
        householdId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        screenModelScope.launch {
            try {
                repo.deleteHousehold(householdId)
                householdStore.removeHousehold(householdId)
                authManager.syncHouseholdsToCloud()
                buzz(HapticKind.WARNING)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: org.taskhub.network.HouseholdCascadeIncompleteException) {
                // Por tipo, no por e.message (fijo en español desde el repo e
                // incluye el ID interno del hogar) — mismo motivo que
                // AccountDeletionCascadeException en DeleteAccountSection.kt.
                AppLog.w("HouseholdScreenModel", "deleteHousehold: cascade incomplete", e)
                buzz(HapticKind.ERROR)
                onError(s("household_error_deleting_cascade"))
            } catch (e: Exception) {
                AppLog.e("HouseholdScreenModel", "deleteHousehold failed", e)
                buzz(HapticKind.ERROR)
                onError(e.toUserMessage(settingsStore.getLanguage(), "household_error_deleting"))
            }
        }
    }

    /** El usuario actual abandona el hogar [householdId] (borra su propio miembro, no el hogar). */
    fun leaveHousehold(
        householdId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        screenModelScope.launch {
            try {
                repo.leaveHousehold(householdId, authManager.currentUserId())
                householdStore.removeHousehold(householdId)
                authManager.syncHouseholdsToCloud()
                buzz(HapticKind.WARNING)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("HouseholdScreenModel", "leaveHousehold failed", e)
                buzz(HapticKind.ERROR)
                onError(e.toUserMessage(settingsStore.getLanguage(), "household_error_leaving"))
            }
        }
    }

    /** UID de Google del usuario actual, o `null` si no hay sesión iniciada. */
    fun getLocalId(): String? = repo.getLocalId()

    // ── Chat de mensajes ──

    private val _messagesUiState = MutableStateFlow<MessagesUiState>(MessagesUiState.Idle)
    val messagesUiState: StateFlow<MessagesUiState> = _messagesUiState.asStateFlow()

    private val _newMessageText = MutableStateFlow("")
    val newMessageText: StateFlow<String> = _newMessageText.asStateFlow()

    private val _sendMessageError = MutableStateFlow<String?>(null)
    /**
     * Error al ENVIAR un mensaje (distinto de [messagesUiState], que es el
     * estado de la LISTA ya cargada) — antes un fallo al enviar sustituía
     * [messagesUiState] por `Error`, ocultando el historial de chat ya
     * mostrado; ahora se expone como banner independiente sin tocar la lista
     * (ronda de deuda aplicable 2026-09-12, punto A3). La UI debe llamar
     * [clearSendMessageError] al descartar el banner.
     */
    val sendMessageError: StateFlow<String?> = _sendMessageError.asStateFlow()

    /** Descarta el banner de error de envío (ver [sendMessageError]). */
    fun clearSendMessageError() {
        _sendMessageError.value = null
    }

    /** Actualiza el texto del campo de nuevo mensaje (estado del input, aún sin enviar). */
    fun updateNewMessageText(text: String) {
        _newMessageText.value = text
    }

    /** Carga los mensajes del chat del hogar [householdId]. */
    fun loadMessages(householdId: String) {
        screenModelScope.launch {
            if (_messagesUiState.value !is MessagesUiState.Success) {
                _messagesUiState.value = MessagesUiState.Loading
            }
            try {
                val messages = repo.getMessages(householdId, limit = MAX_POLLED_MESSAGES)
                _messagesUiState.value = MessagesUiState.Success(messages)

                // Purga TTL de 90 días del chat (ver KDoc de
                // HouseholdRepository.purgeOldMessages) — best-effort, DESPUÉS
                // de publicar la lista: se reutilizan los mensajes ya
                // cargados aquí para no hacer un segundo fetch (ronda de
                // deuda aplicable 2026-09-12, punto B9). Desde que [messages]
                // está acotado a [MAX_POLLED_MESSAGES], un mensaje caducado
                // MÁS ANTIGUO que ese tope no se purgará hasta que el chat
                // encoja por debajo del tope de forma natural — trade-off
                // aceptado: es limpieza best-effort, no una garantía de
                // borrado (tarjeta kanban "Paginación
                // getMessages/getNotifications", 2026-09-13).
                try {
                    repo.purgeOldMessages(householdId, messages)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("HouseholdScreenModel", "loadMessages: purgeOldMessages failed", e)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("HouseholdScreenModel", "loadMessages failed", e)
                _messagesUiState.value = MessagesUiState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "messages_error_loading")
                )
            }
        }
    }

    /**
     * Envía el mensaje pendiente en [newMessageText] al chat del hogar,
     * firmado por [memberId]. Limpia el campo de texto de forma optimista
     * ANTES de la llamada de red para que un doble tap en "Enviar" no lea el
     * mismo texto dos veces y lo duplique; si falla, se RESTAURA (en vez de
     * perderse) y el error se expone vía [sendMessageError] sin pisar
     * [messagesUiState] (que sigue mostrando el historial ya cargado — ronda
     * de deuda aplicable 2026-09-12, punto A3).
     */
    fun sendMessage(householdId: String, memberId: String) {
        val text = _newMessageText.value.trim()
        if (text.isEmpty() || memberId.isEmpty()) return
        // Limpiar de forma optimista ANTES de la llamada de red: evita que un
        // doble tap en "Enviar" lea el mismo texto dos veces y lo duplique.
        // Se restaura en el catch si el envío falla.
        _newMessageText.value = ""
        screenModelScope.launch {
            try {
                val authorName = repo.getMembers(householdId)
                    .firstOrNull { it.id == memberId }
                    ?.displayName ?: ""
                repo.sendMessage(householdId, memberId, authorName, text)
                _sendMessageError.value = null
                loadMessages(householdId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Panel v18 (funcionalidad): solo restaurar si el usuario no ha
                // empezado ya a escribir un mensaje NUEVO mientras este envío
                // seguía en vuelo — si no, este catch pisaba en silencio lo que
                // ya había tecleado con el texto del intento fallido.
                AppLog.e("HouseholdScreenModel", "sendMessage failed", e)
                if (_newMessageText.value.isEmpty()) {
                    _newMessageText.value = text
                }
                _sendMessageError.value = e.toUserMessage(settingsStore.getLanguage(), "messages_error_sending")
            }
        }
    }
}
