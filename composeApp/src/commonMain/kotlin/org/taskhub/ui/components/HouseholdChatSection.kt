// Sección de chat del hogar (lista de mensajes + campo de envío), usada
// dentro de HouseholdScreen.
package org.taskhub.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.taskhub.network.models.MessageResponse
import org.taskhub.ui.models.MessagesUiState

/**
 * Chat de mensajes del hogar: lista de mensajes + campo de envío.
 *
 * Puramente presentacional: recibe el estado ya resuelto en [messagesState]
 * y delega en callbacks cualquier acción (escribir, enviar, refrescar); no
 * conoce el repositorio ni el ScreenModel del host.
 *
 * @param s resolutor de claves i18n ya fijado al idioma actual.
 * @param messagesState estado de carga de los mensajes del hogar.
 * @param newMessageText texto actual del campo de envío (controlado por el caller).
 * @param onTextChange callback al escribir en el campo de envío.
 * @param onSend callback al pulsar enviar (con [newMessageText] ya validado como no vacío).
 * @param onRefresh callback al pulsar el icono de refrescar (también sirve de "Reintentar").
 * @param sendMessageError error al ENVIAR un mensaje (independiente de [messagesState], que
 *   sigue mostrando la lista ya cargada — ver KDoc de `HouseholdScreenModel.sendMessageError`).
 * @param onDismissSendMessageError callback al descartar el banner de [sendMessageError].
 */
@Composable
fun HouseholdChatSection(
    s: (String) -> String,
    messagesState: MessagesUiState,
    newMessageText: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    sendMessageError: String? = null,
    onDismissSendMessageError: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = s("messages_title"),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = s("messages_refresh"))
                }
            }

            Spacer(Modifier.height(8.dp))

            when (messagesState) {
                is MessagesUiState.Loading,
                MessagesUiState.Idle -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                is MessagesUiState.Error -> {
                    Column(modifier = Modifier.padding(vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = s("error_icon_content_desc"),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = messagesState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        // Botón de reintentar — antes esta pantalla se quedaba sin
                        // forma de recuperar el chat tras un error de carga, a
                        // diferencia de NotificationListScreen (ronda de deuda
                        // aplicable 2026-09-12, punto A6: unificar el patrón de
                        // error+retry entre pantallas).
                        Button(onClick = onRefresh) {
                            Text(s("common_retry"))
                        }
                    }
                }

                is MessagesUiState.Success -> {
                    val messages = messagesState.messages
                    if (messages.isEmpty()) {
                        Text(
                            text = s("messages_empty"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                        )
                    } else {
                        val listState = rememberLazyListState()
                        val reduceMotion = shouldReduceMotion()
                        LaunchedEffect(messages.size) {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(messages, key = { it.id }) { message ->
                                MessageBubble(
                                    message = message,
                                    modifier = if (reduceMotion) Modifier else Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (sendMessageError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "⚠️ $sendMessageError",
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        IconButton(onClick = onDismissSendMessageError) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = s("common_dismiss"),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newMessageText,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    label = { Text(s("messages_label")) },
                    placeholder = { Text(s("messages_hint")) },
                    singleLine = true
                )
                IconButton(
                    onClick = onSend,
                    enabled = newMessageText.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = s("messages_send"))
                }
            }
        }
    }
}

/** Una entrada de la lista de chat: autor + hora, y el texto del mensaje. */
@Composable
private fun MessageBubble(message: MessageResponse, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message.authorName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = formatMessageTime(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** "dd/mm hh:mm" en la zona horaria local; cadena vacía si no hay timestamp (0). */
private fun formatMessageTime(epochMillis: Long): String {
    if (epochMillis == 0L) return ""
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val day = local.dayOfMonth.toString().padStart(2, '0')
    val month = local.monthNumber.toString().padStart(2, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val min = local.minute.toString().padStart(2, '0')
    return "$day/$month $hour:$min"
}
