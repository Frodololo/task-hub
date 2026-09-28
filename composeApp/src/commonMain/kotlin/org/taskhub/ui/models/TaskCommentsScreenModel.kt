// ScreenModel del chat de comentarios de una tarea, usado por
// `ui/screens/TaskDetailScreen.kt`. Habla con [FirestoreRepository] para
// leer/crear comentarios (subcolección de la tarea en Firestore) y resolver
// el nombre del autor a partir de la lista de miembros del hogar.
package org.taskhub.ui.models

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.taskhub.network.FirestoreRepository
import org.taskhub.network.models.CommentResponse
import org.taskhub.platform.AppLog
import org.taskhub.platform.bestEffort
import org.taskhub.storage.SettingsStore
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.i18n.toUserMessage

// ── Comments State ────────────────────────────────────────

/** Estado del listado de comentarios de una tarea. */
sealed class CommentsUiState {
    /** Aún no se ha pedido cargar comentarios. */
    data object Idle : CommentsUiState()
    /** Carga en curso, tanto al abrir la pantalla como al recargar tras publicar uno nuevo. */
    data object Loading : CommentsUiState()
    /** Comentarios cargados con éxito, en el orden que devuelve el repositorio. */
    data class Success(val comments: List<CommentResponse>) : CommentsUiState()
    /** Fallo al cargar o al publicar un comentario; [message] listo para mostrar. */
    data class Error(val message: String) : CommentsUiState()
}

/**
 * Comentarios de una tarea (chat por tarea), extraído de [TaskScreenModel]
 * (el "mini god ScreenModel" — 1258+ líneas, panel de revisión
 * 2026-09-03/04, Experto 7, reabierto desde v3). Comentarios es el
 * subsistema más autocontenido dentro de `TaskDetailScreen`: tiene su propio
 * estado (`CommentsUiState`/`newCommentText`) y no participa en ningún flujo
 * de puntos/compleción, así que se extrae primero (panel v7, #17).
 *
 * [currentMemberId] se recibe como parámetro en vez de mantenerse aquí
 * duplicado: [TaskScreenModel] ya es la fuente de verdad de "quién es el
 * miembro activo" para el resto de `TaskDetailScreen`.
 */
class TaskCommentsScreenModel(
    private val repo: FirestoreRepository,
    private val settingsStore: SettingsStore
) : ScreenModel {

    private fun s(key: String) = AppStrings.get(key, settingsStore.getLanguage())

    private val _commentsState = MutableStateFlow<CommentsUiState>(CommentsUiState.Idle)
    /** Estado de la lista de comentarios de la tarea abierta (ver [CommentsUiState]). */
    val commentsState: StateFlow<CommentsUiState> = _commentsState.asStateFlow()

    private val _newCommentText = MutableStateFlow("")
    /** Texto del campo de "nuevo comentario", controlado por la UI (`TextField`). */
    val newCommentText: StateFlow<String> = _newCommentText.asStateFlow()

    private val _sendCommentError = MutableStateFlow<String?>(null)
    /**
     * Error al PUBLICAR un comentario (distinto de [commentsState], que es el
     * estado de la LISTA ya cargada) — antes un fallo al enviar sustituía
     * [commentsState] por `Error`, ocultando el historial de comentarios ya
     * mostrado; ahora el error se muestra como banner independiente sin
     * tocar la lista (ronda de deuda aplicable 2026-09-12, punto A3). La UI
     * debe llamar [clearSendCommentError] al descartar el banner.
     */
    val sendCommentError: StateFlow<String?> = _sendCommentError.asStateFlow()

    /** Descarta el banner de error de envío (ver [sendCommentError]). */
    fun clearSendCommentError() {
        _sendCommentError.value = null
    }

    /** Actualiza el borrador de comentario, descartando cualquier exceso por encima de 200 caracteres. */
    fun setNewCommentText(text: String) {
        if (text.length <= 200) {
            _newCommentText.value = text
        }
    }

    /** Carga (o recarga) los comentarios de [taskId] dentro de [householdId]. */
    fun loadComments(householdId: String, taskId: String) {
        screenModelScope.launch {
            _commentsState.value = CommentsUiState.Loading
            try {
                val comments = repo.getComments(householdId, taskId)
                _commentsState.value = CommentsUiState.Success(comments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("TaskCommentsScreenModel", "loadComments failed for task $taskId", e)
                _commentsState.value = CommentsUiState.Error(
                    e.toUserMessage(settingsStore.getLanguage(), "task_comment_error_loading")
                )
            }
        }
    }

    /**
     * Publica el contenido actual de [newCommentText] (recortado; no hace
     * nada si queda vacío) como comentario de [taskId], resolviendo primero
     * el nombre visible del autor. Si [currentMemberId] es null, pide al
     * repositorio que resuelva el miembro activo.
     * Tras publicar con éxito, recarga la lista completa vía [loadComments]
     * en vez de insertar el comentario en memoria: es más simple y evita
     * duplicar la lógica de orden/formato que ya aplica el repositorio.
     *
     * Si falla, el borrador se RESTAURA (en vez de perderse) y el error se
     * expone vía [sendCommentError] sin tocar [commentsState] — antes un
     * fallo aquí perdía el texto escrito Y sustituía la lista ya cargada por
     * el estado de error (ronda de deuda aplicable 2026-09-12, punto A3).
     */
    fun addComment(householdId: String, taskId: String, currentMemberId: String?) {
        val text = _newCommentText.value.trim()
        if (text.isEmpty()) return
        // Limpiar el campo de forma optimista, ANTES de la llamada de red: si no,
        // un doble tap en "Enviar" antes de que la primera petición complete lee
        // el mismo texto dos veces y envía el comentario duplicado. Se restaura
        // en el catch si la publicación falla.
        _newCommentText.value = ""
        screenModelScope.launch {
            try {
                val memberId = currentMemberId ?: repo.resolveCurrentMember(householdId)
                val authorName = resolveCurrentMemberName(householdId, memberId)
                repo.addComment(householdId, taskId, memberId, authorName, text)
                _sendCommentError.value = null
                // Reload comments
                loadComments(householdId, taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("TaskCommentsScreenModel", "addComment failed for task $taskId", e)
                _newCommentText.value = text
                _sendCommentError.value = e.toUserMessage(settingsStore.getLanguage(), "task_comment_error_adding")
            }
        }
    }

    /**
     * Resolves the display name of the current member, for use as comment author.
     * Nota: el nombre de fallback difiere según el caso — si el miembro existe
     * pero no tiene `displayName` (o está en blanco) se usa
     * `task_comment_default_author`, mientras que si falla la propia llamada
     * de red a [FirestoreRepository.getMembers] se usa `profile_default_name`.
     * Son claves de i18n distintas ya en el código original; se documenta tal
     * cual, sin unificarlas (fuera de alcance de esta pasada de comentarios).
     */
    private suspend fun resolveCurrentMemberName(householdId: String, memberId: String): String {
        return bestEffort(s("profile_default_name"), "TaskCommentsScreenModel.resolveCurrentMemberName") {
            val member = repo.getMembers(householdId).find { it.id == memberId }
            member?.displayName?.takeIf { it.isNotBlank() } ?: s("task_comment_default_author")
        }
    }
}
