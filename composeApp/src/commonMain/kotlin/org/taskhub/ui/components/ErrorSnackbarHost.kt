// SnackbarHost con soporte de estilo de error (icono + colores de
// errorContainer) sin depender de emojis embebidos en el texto del mensaje.
package org.taskhub.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * [SnackbarVisuals] con un flag [isError] para que [ErrorAwareSnackbarHost]
 * pinte el icono/color de error en vez de depender de un emoji embebido en
 * el mensaje (antes: `showSnackbar(message = "❌ $msg")`).
 */
private class ErrorSnackbarVisuals(
    override val message: String,
    val isError: Boolean,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short
) : SnackbarVisuals

/** Encola un snackbar de error con icono Material (ver [ErrorAwareSnackbarHost]). */
suspend fun SnackbarHostState.showErrorSnackbar(
    message: String,
    duration: SnackbarDuration = SnackbarDuration.Short
) {
    showSnackbar(ErrorSnackbarVisuals(message = message, isError = true, duration = duration))
}

/**
 * [SnackbarHost] que renderiza con icono/color de error los snackbars
 * encolados vía [showErrorSnackbar], y con el estilo por defecto el resto
 * (p.ej. el snackbar de "deshacer" de [TaskListScreen]).
 */
@Composable
fun ErrorAwareSnackbarHost(
    hostState: SnackbarHostState,
    errorIconContentDescription: String,
    modifier: Modifier = Modifier
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val visuals = data.visuals
        if (visuals is ErrorSnackbarVisuals && visuals.isError) {
            Snackbar(
                action = visuals.actionLabel?.let { label ->
                    { TextButton(onClick = { data.performAction() }) { Text(label) } }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = errorIconContentDescription,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(visuals.message)
                }
            }
        } else {
            Snackbar(snackbarData = data)
        }
    }
}
