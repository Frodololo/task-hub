// ScreenModel de la campanita de notificaciones in-app (lista y contador de
// no leídas), usado por `ui/screens/NotificationListScreen.kt` y por el
// badge de `ui/screens/HouseholdScreen.kt`. Lee y marca como leídas las
// notificaciones vía [FirestoreRepository] (subcolección
// `households/{id}/notifications`).
//
// Importante: el estado "leída" que gestiona esta clase es el booleano
// `read` de cada documento de notificación en Firestore (para pintar la
// lista in-app). Es un mecanismo DISTINTO del marcador de sondeo de
// notificaciones push (`SettingsStore.getNotifiedNotificationIds`, usado por
// el worker de Android), que guarda un CONJUNTO DE IDs ya notificados (no un
// timestamp) precisamente para no depender del reloj del dispositivo que
// creó la notificación — ver el comentario en `SettingsStore` para el
// detalle. La auto-exclusión de notificaciones generadas por el propio
// usuario (p.ej. no notificarte una tarea que tú mismo te asignaste) tampoco
// ocurre aquí: se decide al crear la notificación, en
// `TaskRepository.assignTask` / `HouseholdRepository` (comparando el autor
// con el destinatario antes de llamar a `createNotification`).
package org.taskhub.ui.models

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.taskhub.network.FirestoreRepository
import org.taskhub.network.MAX_POLLED_NOTIFICATIONS
import org.taskhub.network.models.NotificationResponse
import org.taskhub.platform.AppLog
import org.taskhub.platform.bestEffort
import org.taskhub.storage.SettingsStore
import org.taskhub.ui.i18n.toUserMessage

/** Estado de la pantalla de lista de notificaciones. */
sealed class NotificationUiState {
    /** Aún no se ha pedido cargar notificaciones. */
    data object Idle : NotificationUiState()
    /** Carga en curso ([loadNotifications]). */
    data object Loading : NotificationUiState()
    /**
     * Notificaciones del miembro actual ya cargadas, ordenadas de más
     * reciente a más antigua.
     *
     * @param notifications lista completa (leídas y no leídas) para pintar.
     * @param unreadCount cuántas de [notifications] tienen `read == false`;
     *   se expone también por separado en [NotificationScreenModel.unreadCount].
     */
    data class Success(
        val notifications: List<NotificationResponse>,
        val unreadCount: Int
    ) : NotificationUiState()
    /** Fallo al cargar; [message] ya viene traducido/listo para mostrar. */
    data class Error(val message: String) : NotificationUiState()
}

/**
 * ScreenModel de notificaciones in-app. Carga las notificaciones de un
 * miembro dentro de un hogar, permite marcarlas como leídas y mantiene un
 * contador de no leídas independiente para el badge de la pantalla principal
 * (que puede refrescarse sin recargar la lista completa, ver
 * [refreshUnreadCount]).
 */
class NotificationScreenModel(
    private val repo: FirestoreRepository,
    private val settingsStore: SettingsStore
) : ScreenModel {

    private val _uiState = MutableStateFlow<NotificationUiState>(NotificationUiState.Idle)
    /** Estado de la pantalla de lista de notificaciones (ver [NotificationUiState]). */
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    /**
     * Contador de notificaciones no leídas del miembro actual, para el badge
     * de la campanita. Se mantiene deliberadamente separado de [uiState]
     * para poder refrescarlo por sondeo periódico (ver [refreshUnreadCount])
     * sin necesidad de tener la lista completa cargada ni de pasar por el
     * estado `Loading`.
     */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    /**
     * Carga todas las notificaciones del hogar y filtra las dirigidas a
     * [memberId], ordenándolas de más reciente a más antigua. Actualiza tanto
     * [uiState] como [unreadCount].
     */
    fun loadNotifications(householdId: String, memberId: String) {
        screenModelScope.launch {
            _uiState.value = NotificationUiState.Loading
            try {
                val all = repo.getNotifications(householdId)
                // Filter notifications for this member
                val memberNotifications = all.filter { it.memberId == memberId }
                    .sortedByDescending { it.createdAt }
                val unread = memberNotifications.count { !it.read }
                _unreadCount.value = unread
                _uiState.value = NotificationUiState.Success(memberNotifications, unread)

                // Purga TTL de 90 días de las notificaciones ya leídas (ver
                // KDoc de NotificationRepository.purgeOldRead) — best-effort,
                // DESPUÉS de publicar la lista: ya se tiene [all] (la
                // colección completa del HOGAR, no solo las de este miembro)
                // cargada aquí, así que no hace falta un segundo fetch (ronda
                // de deuda aplicable 2026-09-12, punto B9).
                bestEffort(Unit, "NotificationScreenModel") {
                    repo.purgeOldNotifications(householdId, all)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("NotificationScreenModel", "loadNotifications failed", e)
                _uiState.value = NotificationUiState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "notification_error_loading")
                )
            }
        }
    }

    /**
     * Marca una notificación como leída en Firestore y, si la llamada tiene
     * éxito, refleja el cambio en memoria (evita tener que recargar toda la
     * lista solo para actualizar un icono). Si [uiState] no está en
     * [NotificationUiState.Success] en ese momento (p.ej. todavía cargando o
     * en error), la actualización local se omite silenciosamente: el
     * documento en Firestore sí queda marcado como leído, pero el contador
     * en memoria no baja hasta la siguiente [loadNotifications] o
     * [refreshUnreadCount].
     *
     * Los errores de red se ignoran a propósito (no es una operación
     * crítica): en el peor caso la notificación se sigue mostrando como no
     * leída hasta reintentarlo.
     */
    fun markAsRead(householdId: String, notificationId: String) {
        screenModelScope.launch {
            // Non-critical, ignore failures.
            bestEffort(Unit, "NotificationScreenModel") {
                repo.markNotificationRead(householdId, notificationId)
                // Update local state
                val current = _uiState.value
                if (current is NotificationUiState.Success) {
                    val updated = current.notifications.map {
                        if (it.id == notificationId) it.copy(read = true) else it
                    }
                    val unread = updated.count { !it.read }
                    _unreadCount.value = unread
                    _uiState.value = NotificationUiState.Success(updated, unread)
                }
            }
        }
    }

    /**
     * Recalcula solo [unreadCount] (sin tocar [uiState] ni pedir la lista
     * completa a la UI) para el sondeo periódico del badge de notificaciones
     * en [org.taskhub.ui.screens.HouseholdScreen]. Los errores se ignoran:
     * es un refresco en segundo plano, no vale la pena interrumpir al
     * usuario por un fallo puntual de red.
     */
    fun refreshUnreadCount(householdId: String, memberId: String) {
        screenModelScope.launch {
            // Ignore polling errors: background refresh, not worth interrupting the user.
            bestEffort(Unit, "NotificationScreenModel") {
                val all = repo.getNotifications(householdId, limit = MAX_POLLED_NOTIFICATIONS)
                val unread = all.count { it.memberId == memberId && !it.read }
                _unreadCount.value = unread
            }
        }
    }
}