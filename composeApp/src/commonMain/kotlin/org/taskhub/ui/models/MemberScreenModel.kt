/**
 * ScreenModel de miembros de un hogar: alta/baja/rol, recompensas (crear,
 * borrar, canjear) y transferencias de puntos entre miembros (agradecer con
 * límite diario, donar con validación de saldo). Lo usan
 * [org.taskhub.ui.screens.HouseholdScreen], [org.taskhub.ui.screens.RankingScreen]
 * y las pantallas de recompensas ([org.taskhub.ui.screens.RewardListScreen],
 * [org.taskhub.ui.screens.CreateRewardScreen], [org.taskhub.ui.screens.MemberRewardScreen]).
 */
@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package org.taskhub.ui.models

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.taskhub.network.FirestoreRepository
import org.taskhub.network.InsufficientBalanceException
import org.taskhub.network.MemberRepository
import org.taskhub.network.models.MemberResponse
import org.taskhub.network.models.RewardResponse
import org.taskhub.network.models.RewardRedemption
import org.taskhub.network.toUserMessageKey
import org.taskhub.platform.AppLog
import org.taskhub.platform.HapticKind
import org.taskhub.ui.components.hapticsEnabled
import org.taskhub.platform.vibrate
import org.taskhub.storage.SettingsStore
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.i18n.toUserMessage
import kotlin.uuid.Uuid

/** Estados de carga de la lista de miembros de un hogar. */
sealed class MemberUiState {
    data object Idle : MemberUiState()
    data object Loading : MemberUiState()
    data class Success(val members: List<MemberResponse>) : MemberUiState()
    data class Error(val message: String) : MemberUiState()
}

/** Estados de carga de la lista de recompensas de un hogar. */
sealed class RewardUiState {
    data object Idle : RewardUiState()
    data object Loading : RewardUiState()
    data class Success(val rewards: List<RewardResponse>) : RewardUiState()
    data class Error(val message: String) : RewardUiState()
}

/** Estado de una acción sobre recompensas (crear, borrar o canjear). */
sealed class RewardActionState {
    data object Idle : RewardActionState()
    data object Loading : RewardActionState()
    data class Success(val redemption: RewardRedemption? = null) : RewardActionState()
    data class Error(val message: String) : RewardActionState()
}

/**
 * Resultado de mutaciones sobre un miembro (cambiar rol, eliminar) — separado
 * de [MemberUiState] igual que [RewardActionState] lo está de [RewardUiState].
 * Antes, un fallo en `updateMemberRole`/`removeMember` escribía directamente
 * en `_uiState` (que contenía la lista de miembros en `Success`), así que el
 * error sustituía la lista visible entera en vez de solo mostrarse como aviso
 * puntual — un fallo de red al cambiar un rol borraba de la pantalla a todos
 * los demás miembros.
 */
sealed class MemberActionState {
    data object Idle : MemberActionState()
    data object Loading : MemberActionState()
    data object Success : MemberActionState()
    data class Error(val message: String) : MemberActionState()
}

/** Estado de la acción de "agradecer" puntos a otro miembro (con límite diario). */
sealed class AppreciateActionState {
    data object Idle : AppreciateActionState()
    data object Loading : AppreciateActionState()
    data class Success(val remaining: Int) : AppreciateActionState()
    /** [messageKey] es una clave de [org.taskhub.ui.i18n.AppStrings], no un mensaje ya traducido. */
    data class Error(val messageKey: String) : AppreciateActionState()
}

/** Estado de la acción de donar puntos propios a otro miembro. */
sealed class DonateActionState {
    data object Idle : DonateActionState()
    data object Loading : DonateActionState()
    data class Success(val donorNewTotal: Int) : DonateActionState()
    /** [messageKey] es una clave de [org.taskhub.ui.i18n.AppStrings], no un mensaje ya traducido. */
    data class Error(val messageKey: String) : DonateActionState()
}

/**
 * ScreenModel de miembros/recompensas/transferencias de un hogar. Expone
 * [uiState] (lista de miembros), [rewardState]/[rewardActionState] y
 * [appreciateActionState]/[donateActionState] para las transferencias de
 * puntos entre miembros.
 */
class MemberScreenModel(
    private val repo: FirestoreRepository,
    private val settingsStore: SettingsStore
) : ScreenModel {

    private fun buzz(kind: HapticKind) {
        if (hapticsEnabled(settingsStore)) vibrate(kind)
    }

    private fun s(key: String) = AppStrings.get(key, settingsStore.getLanguage())

    /**
     * idempotencyKey pendiente por clave lógica de acción (`"donate:hid:toId"`,
     * `"redeem:rewardId"`), para reutilizar entre reintentos de la MISMA
     * acción — ver el mismo mecanismo y motivo en
     * [TaskScreenModel.pendingIdempotencyKeys].
     */
    private val pendingIdempotencyKeys = mutableMapOf<String, String>()

    private val _uiState = MutableStateFlow<MemberUiState>(MemberUiState.Idle)
    val uiState: StateFlow<MemberUiState> = _uiState.asStateFlow()

    private val _lastCreatedMember = MutableStateFlow<MemberResponse?>(null)
    val lastCreatedMember: StateFlow<MemberResponse?> = _lastCreatedMember.asStateFlow()

    private val _memberActionState = MutableStateFlow<MemberActionState>(MemberActionState.Idle)
    val memberActionState: StateFlow<MemberActionState> = _memberActionState.asStateFlow()

    private val _rewardState = MutableStateFlow<RewardUiState>(RewardUiState.Idle)
    val rewardState: StateFlow<RewardUiState> = _rewardState.asStateFlow()

    private val _rewardActionState = MutableStateFlow<RewardActionState>(RewardActionState.Idle)
    val rewardActionState: StateFlow<RewardActionState> = _rewardActionState.asStateFlow()

    /** Ver [FirestoreRepository.isHouseholdOwner]. */
    suspend fun isHouseholdOwner(householdId: String): Boolean = repo.isHouseholdOwner(householdId)

    /** UID del usuario actual. Ver [FirestoreRepository.getLocalId]. */
    val localId: String? get() = repo.getLocalId()

    /** Carga los miembros del hogar [householdId]. */
    fun loadMembers(householdId: String) {
        screenModelScope.launch {
            _uiState.value = MemberUiState.Loading
            try {
                val members = repo.getMembers(householdId)
                _uiState.value = MemberUiState.Success(members)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("MemberScreenModel", "loadMembers failed for household $householdId", e)
                _uiState.value = MemberUiState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "member_error_loading")
                )
            }
        }
    }

    /** Crea un miembro nuevo (p.ej. perfil infantil) en el hogar y recarga la lista. */
    fun addMember(householdId: String, displayName: String, role: String, userId: String? = null, inviteCode: String? = null) {
        if (_uiState.value == MemberUiState.Loading) return // evita doble-tap
        screenModelScope.launch {
            _uiState.value = MemberUiState.Loading
            try {
                val member = repo.createMember(householdId, displayName, role, userId = userId, inviteCode = inviteCode)
                _lastCreatedMember.value = member
                // Reload the full member list
                val members = repo.getMembers(householdId)
                _uiState.value = MemberUiState.Success(members)
                buzz(HapticKind.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("MemberScreenModel", "addMember failed for household $householdId", e)
                _uiState.value = MemberUiState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "member_error_adding")
                )
                buzz(HapticKind.ERROR)
            }
        }
    }

    /** Elimina a [memberId] del hogar. Ignora llamadas mientras ya hay una acción en curso (evita doble-tap). */
    fun removeMember(householdId: String, memberId: String) {
        if (_memberActionState.value == MemberActionState.Loading) return // evita doble-tap
        screenModelScope.launch {
            _memberActionState.value = MemberActionState.Loading
            try {
                repo.deleteMember(householdId, memberId)
                val members = repo.getMembers(householdId)
                _uiState.value = MemberUiState.Success(members)
                _memberActionState.value = MemberActionState.Success
                buzz(HapticKind.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("MemberScreenModel", "removeMember failed for member $memberId", e)
                _memberActionState.value = MemberActionState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "member_error_removing")
                )
                buzz(HapticKind.ERROR)
            }
        }
    }

    /**
     * Edita el rol de un miembro ("admin" | "child") sin recrearlo.
     * Recarga la lista al terminar.
     */
    fun updateMemberRole(householdId: String, memberId: String, role: String) {
        if (_memberActionState.value == MemberActionState.Loading) return // evita doble-tap
        screenModelScope.launch {
            _memberActionState.value = MemberActionState.Loading
            try {
                repo.updateMemberRole(householdId, memberId, role)
                val members = repo.getMembers(householdId)
                _uiState.value = MemberUiState.Success(members)
                _memberActionState.value = MemberActionState.Success
                buzz(HapticKind.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("MemberScreenModel", "updateMemberRole failed for member $memberId", e)
                _memberActionState.value = MemberActionState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "member_error_role")
                )
                buzz(HapticKind.ERROR)
            }
        }
    }

    /** Vuelve [memberActionState] a [MemberActionState.Idle]. */
    fun clearMemberAction() {
        _memberActionState.value = MemberActionState.Idle
    }

    /** Resetea el estado de miembros (uiState, último creado y acción) a Idle/null. */
    fun reset() {
        _uiState.value = MemberUiState.Idle
        _lastCreatedMember.value = null
        _memberActionState.value = MemberActionState.Idle
        // Al cambiar de hogar/pantalla no debe reintentarse una donación/canje
        // de otro contexto con la clave pendiente del anterior.
        pendingIdempotencyKeys.clear()
    }

    /** Limpia [lastCreatedMember] tras consumirlo (p.ej. tras navegar con el nuevo miembro). */
    fun clearLastCreated() {
        _lastCreatedMember.value = null
    }

    // ── Rewards ──────────────────────────────────────────

    /** Carga las recompensas disponibles del hogar [householdId]. */
    fun loadRewards(householdId: String) {
        screenModelScope.launch {
            _rewardState.value = RewardUiState.Loading
            try {
                val rewards = repo.getRewards(householdId)
                _rewardState.value = RewardUiState.Success(rewards)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("MemberScreenModel", "loadRewards failed for household $householdId", e)
                _rewardState.value = RewardUiState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "reward_error_loading")
                )
            }
        }
    }

    /** Crea una recompensa canjeable en el hogar y recarga la lista al terminar. */
    fun createReward(
        householdId: String,
        title: String,
        description: String,
        cost: Int,
        icon: String
    ) {
        if (_rewardActionState.value == RewardActionState.Loading) return // evita doble-tap
        screenModelScope.launch {
            _rewardActionState.value = RewardActionState.Loading
            try {
                val createdBy = repo.getLocalId() ?: ""
                repo.createReward(householdId, title, description, cost, icon, createdBy)
                _rewardActionState.value = RewardActionState.Success()
                buzz(HapticKind.SUCCESS)
                // Reload
                loadRewards(householdId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("MemberScreenModel", "createReward failed for household $householdId", e)
                _rewardActionState.value = RewardActionState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "reward_error_creating")
                )
                buzz(HapticKind.ERROR)
            }
        }
    }

    /** Borra la recompensa [rewardId] y recarga la lista. */
    fun deleteReward(householdId: String, rewardId: String) {
        screenModelScope.launch {
            try {
                repo.deleteReward(householdId, rewardId)
                buzz(HapticKind.WARNING)
                loadRewards(householdId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("MemberScreenModel", "deleteReward failed for reward $rewardId", e)
                _rewardActionState.value = RewardActionState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "reward_error_deleting")
                )
                buzz(HapticKind.ERROR)
            }
        }
    }

    /**
     * Canjea la recompensa [rewardId] para [memberId], descontando
     * [pointsSpent] puntos. Ignora llamadas mientras ya hay un canje en
     * curso (evita doble-tap / doble descuento).
     */
    fun redeemReward(
        householdId: String,
        rewardId: String,
        memberId: String,
        pointsSpent: Int
    ) {
        if (_rewardActionState.value == RewardActionState.Loading) return // evita doble-tap / doble descuento
        val actionKey = "redeem:$rewardId"
        screenModelScope.launch {
            _rewardActionState.value = RewardActionState.Loading
            // Reutiliza la clave del intento anterior si el último fallo para
            // este canje fue ambiguo — ver [pendingIdempotencyKeys].
            val idempotencyKey = pendingIdempotencyKeys.remove(actionKey) ?: Uuid.random().toString()
            try {
                val redemption = repo.redeemReward(householdId, rewardId, memberId, pointsSpent, idempotencyKey)
                _rewardActionState.value = RewardActionState.Success(redemption)
                buzz(HapticKind.SUCCESS)
                // Reload members to refresh points
                loadMembers(householdId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InsufficientBalanceException) {
                // Por tipo, no por e.message (que viene fijo en español desde
                // el repo y nunca es null, así que el fallback de i18n de
                // abajo nunca se disparaba) — panel de revisión 2026-09-10,
                // Experto 2, IMPORTANTE.
                AppLog.w("MemberScreenModel", "redeemReward: insufficient balance for member $memberId", e)
                _rewardActionState.value = RewardActionState.Error(s("member_reward_insufficient"))
                buzz(HapticKind.ERROR)
            } catch (e: Exception) {
                AppLog.e("MemberScreenModel", "redeemReward failed for reward $rewardId", e)
                // Reutilizar la clave en el próximo reintento de ESTE canje — ver
                // el mismo motivo en TaskScreenModel.completeTask.
                pendingIdempotencyKeys[actionKey] = idempotencyKey
                _rewardActionState.value = RewardActionState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "reward_error_redeeming")
                )
                buzz(HapticKind.ERROR)
                // Recargar el saldo tras CUALQUIER error (no solo éxito): un error
                // ambiguo (timeout) puede haber descontado puntos igualmente en el
                // servidor — sin esto, el saldo mostrado queda "congelado" al valor
                // previo y refuerza la falsa sensación de que la operación no tuvo
                // efecto (panel v15, oleada 3, Red/offline).
                loadMembers(householdId)
            }
        }
    }

    /** Vuelve [rewardActionState] a [RewardActionState.Idle]. */
    fun clearRewardAction() {
        _rewardActionState.value = RewardActionState.Idle
    }

    // ── Agradecer / Donar ──────────────────────────────────

    private val _appreciateActionState = MutableStateFlow<AppreciateActionState>(AppreciateActionState.Idle)
    val appreciateActionState: StateFlow<AppreciateActionState> = _appreciateActionState.asStateFlow()

    private val _donateActionState = MutableStateFlow<DonateActionState>(DonateActionState.Idle)
    val donateActionState: StateFlow<DonateActionState> = _donateActionState.asStateFlow()

    /**
     * "Agradece" [amount] puntos de [fromMemberId] a [toMemberId] (no resta
     * saldo al emisor, sujeto a un límite diario validado en
     * [MemberRepository.appreciateMember]). Ignora llamadas mientras ya hay
     * una en curso.
     */
    fun appreciateMember(householdId: String, fromMemberId: String, toMemberId: String, amount: Int) {
        if (_appreciateActionState.value == AppreciateActionState.Loading) return
        screenModelScope.launch {
            _appreciateActionState.value = AppreciateActionState.Loading
            try {
                when (val result = repo.appreciateMember(householdId, fromMemberId, toMemberId, amount)) {
                    is MemberRepository.AppreciateResult.Ok -> {
                        loadMembers(householdId)
                        _appreciateActionState.value = AppreciateActionState.Success(result.remaining)
                        buzz(HapticKind.SUCCESS)
                    }
                    is MemberRepository.AppreciateResult.Error -> {
                        _appreciateActionState.value = AppreciateActionState.Error(appreciateErrorKey(result.reason))
                        buzz(HapticKind.ERROR)
                        // Panel v16, hallazgo I19: UNCERTAIN significa que el
                        // acreditado al receptor pudo haber llegado a aplicarse
                        // en el servidor pese al error — recargar, igual que ya
                        // hace redeemReward, para no dejar el saldo mostrado
                        // "congelado" justo en el caso en que el propio mensaje
                        // le pide al usuario comprobarlo.
                        if (result.reason == MemberRepository.AppreciateErrorReason.UNCERTAIN) {
                            loadMembers(householdId)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("MemberScreenModel", "appreciateMember failed from $fromMemberId to $toMemberId", e)
                _appreciateActionState.value = AppreciateActionState.Error(e.toUserMessageKey("transfer_error_failed"))
                buzz(HapticKind.ERROR)
            }
        }
    }

    /**
     * Dona [amount] puntos propios de [fromMemberId] a [toMemberId]
     * (sí resta saldo al emisor; requiere saldo suficiente, validado en
     * [MemberRepository.donatePoints]). Ignora llamadas mientras ya hay una
     * en curso.
     */
    fun donatePoints(householdId: String, fromMemberId: String, toMemberId: String, amount: Int) {
        if (_donateActionState.value == DonateActionState.Loading) return
        val actionKey = "donate:$householdId:$toMemberId"
        screenModelScope.launch {
            _donateActionState.value = DonateActionState.Loading
            // Reutiliza la clave del intento anterior si la última donación a
            // este miembro falló de forma ambigua — ver [pendingIdempotencyKeys].
            val idempotencyKey = pendingIdempotencyKeys.remove(actionKey) ?: Uuid.random().toString()
            try {
                when (val result = repo.donatePoints(householdId, fromMemberId, toMemberId, amount, idempotencyKey)) {
                    is MemberRepository.DonateResult.Ok -> {
                        loadMembers(householdId)
                        _donateActionState.value = DonateActionState.Success(result.donorNewTotal)
                        buzz(HapticKind.SUCCESS)
                    }
                    is MemberRepository.DonateResult.Error -> {
                        // Reutilizar la clave en el próximo reintento de ESTA donación
                        // — ver el mismo motivo en TaskScreenModel.completeTask. No
                        // duele reutilizarla también en errores de validación (sin
                        // llamada de red, p.ej. saldo insuficiente): el servidor la
                        // trataría igualmente como una operación nueva.
                        pendingIdempotencyKeys[actionKey] = idempotencyKey
                        _donateActionState.value = DonateActionState.Error(donateErrorKey(result.reason))
                        buzz(HapticKind.ERROR)
                        // Panel v16, hallazgo I19: ver el mismo motivo en appreciateMember().
                        if (result.reason == MemberRepository.DonateErrorReason.UNCERTAIN) {
                            loadMembers(householdId)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("MemberScreenModel", "donatePoints failed from $fromMemberId to $toMemberId", e)
                pendingIdempotencyKeys[actionKey] = idempotencyKey
                _donateActionState.value = DonateActionState.Error(e.toUserMessageKey("transfer_error_failed"))
                buzz(HapticKind.ERROR)
            }
        }
    }

    /** Vuelve [appreciateActionState] a [AppreciateActionState.Idle]. */
    fun clearAppreciateAction() {
        _appreciateActionState.value = AppreciateActionState.Idle
    }

    /** Vuelve [donateActionState] a [DonateActionState.Idle]. */
    fun clearDonateAction() {
        _donateActionState.value = DonateActionState.Idle
    }
}

/**
 * Traduce el motivo de fallo de "agradecer" a una clave de i18n. Función de
 * paquete (no método de [MemberScreenModel]) para poder testearla sin
 * construir el ScreenModel completo — mismo patrón que [computeStats] en
 * `StatsScreenModel.kt`, que estuvo con un bug de doble conteo en producción
 * varios días por ser `private` y no poder escribirle un test de regresión
 * (panel 2026-09-11). Ver [donateErrorKey].
 */
internal fun appreciateErrorKey(reason: MemberRepository.AppreciateErrorReason): String = when (reason) {
    MemberRepository.AppreciateErrorReason.SELF -> "transfer_error_self"
    MemberRepository.AppreciateErrorReason.INVALID_AMOUNT -> "transfer_error_invalid_amount"
    MemberRepository.AppreciateErrorReason.LIMIT_EXCEEDED -> "appreciate_error_limit"
    MemberRepository.AppreciateErrorReason.MEMBER_NOT_FOUND -> "transfer_error_member_not_found"
    MemberRepository.AppreciateErrorReason.TRANSFER_FAILED -> "transfer_error_failed"
    MemberRepository.AppreciateErrorReason.UNCERTAIN -> "transfer_error_uncertain"
}

/** Traduce el motivo de fallo de "donar" a una clave de i18n. Ver [appreciateErrorKey]. */
internal fun donateErrorKey(reason: MemberRepository.DonateErrorReason): String = when (reason) {
    MemberRepository.DonateErrorReason.SELF -> "transfer_error_self"
    MemberRepository.DonateErrorReason.INVALID_AMOUNT -> "transfer_error_invalid_amount"
    MemberRepository.DonateErrorReason.INSUFFICIENT_BALANCE -> "donate_error_insufficient_balance"
    MemberRepository.DonateErrorReason.MEMBER_NOT_FOUND -> "transfer_error_member_not_found"
    MemberRepository.DonateErrorReason.TRANSFER_FAILED -> "transfer_error_failed"
    MemberRepository.DonateErrorReason.AMOUNT_EXCEEDS_LIMIT -> "donate_error_exceeds_limit"
    MemberRepository.DonateErrorReason.UNCERTAIN -> "transfer_error_uncertain"
}
