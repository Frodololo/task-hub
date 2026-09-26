// Diálogos específicos del dominio "hogar": compartir invitación (QR),
// confirmaciones de borrar/salir de un hogar, ajustes del hogar y las
// transferencias de puntos (Agradecer/Donar) entre miembros. Usados desde
// HouseholdScreen y pantallas relacionadas.
package org.taskhub.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.taskhub.network.models.MemberResponse
import org.taskhub.platform.QrCodeImage
import org.taskhub.platform.logAnalyticsEvent
import org.taskhub.platform.shareText
import org.taskhub.ui.models.AppreciateActionState
import org.taskhub.ui.models.DonateActionState

/**
 * Diálogo con el código QR / texto de invitación al hogar, con botón para compartir.
 *
 * @param inviteCode código de invitación del hogar (se codifica en el QR y se
 *   muestra también como texto).
 * @param s resolutor de claves i18n ya fijado al idioma actual (`AppStrings.get(key, lang)`).
 * @param onDismiss callback al cerrar el diálogo.
 */
@Composable
fun QrShareDialog(
    inviteCode: String,
    s: (String) -> String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                s("household_invite_title"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                QrCodeImage(
                    text = inviteCode,
                    contentDescription = s("household_qr_description").replace("%s", inviteCode),
                    modifier = Modifier.size(220.dp)
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = inviteCode,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = s("household_share_code"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    logAnalyticsEvent("invite_code_shared")
                    shareText(
                        s("household_share_message").replace("%s", inviteCode),
                        s("household_share_subject")
                    )
                },
            ) {
                Text(s("household_share"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s("household_close"))
            }
        }
    )
}

/**
 * Primer paso de la doble confirmación para eliminar un hogar.
 */
@Composable
fun DeleteHouseholdConfirmDialog1(
    householdName: String,
    s: (String) -> String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    DestructiveConfirmDialog(
        title = s("household_delete_title"),
        text = s("household_delete_confirm_1").replace("%s", householdName),
        s = s,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

/**
 * Segundo paso (definitivo) de la doble confirmación para eliminar un hogar.
 */
@Composable
fun DeleteHouseholdConfirmDialog2(
    householdName: String,
    s: (String) -> String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    DestructiveConfirmDialog(
        title = s("household_delete_confirm_2"),
        text = s("household_delete_confirm_2_desc").replace("%s", householdName),
        s = s,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmLabel = s("household_delete_yes")
    )
}

/**
 * Confirmación para desvincularse (salir) de un hogar.
 */
@Composable
fun LeaveHouseholdDialog(
    householdName: String,
    s: (String) -> String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    DestructiveConfirmDialog(
        title = s("household_leave_title"),
        text = s("household_leave_confirm").replace("%s", householdName),
        s = s,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmLabel = s("household_leave_btn")
    )
}

/**
 * Hoja de ajustes de la app, mostrada como diálogo a pantalla casi completa.
 *
 * Por defecto oculta la exportación CSV (`showExportCsv = false`): solo la
 * lista de tareas tiene los datos para exportar, y es la única pantalla que
 * pasa [onExportCsv] y `showExportCsv = true` explícitamente.
 *
 * @param onDismiss callback al cerrar el diálogo.
 * @param onEditProfile callback al pulsar "Editar perfil" (cierra este diálogo primero).
 * @param onExportCsv acción de exportar tareas a CSV (solo se invoca si [showExportCsv] es `true`).
 * @param showExportCsv si `true`, muestra el botón de exportar CSV.
 */
@Composable
fun HouseholdSettingsDialog(
    onDismiss: () -> Unit,
    onEditProfile: () -> Unit,
    onExportCsv: () -> Unit = { },
    showExportCsv: Boolean = false
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 480.dp)
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            SettingsSheet(
                callbacks = SettingsCallbacks(
                    onExportCsv = onExportCsv,
                    onDismiss = onDismiss,
                    onEditProfile = onEditProfile,
                    showExportCsv = showExportCsv
                )
            )
        }
    }
}

/**
 * Diálogo para agradecer (transferir puntos del presupuesto semanal) a un miembro.
 *
 * @param target miembro destinatario de la transferencia.
 * @param remaining presupuesto semanal restante disponible para agradecer (tope del importe).
 * @param state estado de la acción de agradecer (idle/loading/error), controla el spinner y el mensaje de error.
 * @param onConfirm callback con el importe introducido al confirmar.
 */
@Composable
fun AppreciateDialog(
    target: MemberResponse,
    s: (String) -> String,
    remaining: Int,
    state: AppreciateActionState,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    TransferAmountDialog(
        title = "${s("appreciate_dialog_title")} ${target.displayName}",
        budgetLabel = s("appreciate_dialog_remaining_label"),
        budget = remaining,
        pointsSuffix = s("transfer_points_suffix"),
        amountLabel = s("transfer_amount_label"),
        confirmLabel = s("transfer_confirm"),
        cancelLabel = s("transfer_cancel"),
        errorText = (state as? AppreciateActionState.Error)?.let { s(it.messageKey) },
        isLoading = state is AppreciateActionState.Loading,
        emptyBudgetText = s("appreciate_no_budget"),
        amountRangeHint = s("transfer_amount_range_hint").replace("%d", remaining.toString()),
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/**
 * Diálogo para donar puntos del saldo propio a un miembro.
 *
 * @param target miembro destinatario de la transferencia.
 * @param balance saldo propio disponible para donar (tope del importe).
 * @param state estado de la acción de donar (idle/loading/error), controla el spinner y el mensaje de error.
 * @param onConfirm callback con el importe introducido al confirmar.
 */
@Composable
fun DonateDialog(
    target: MemberResponse,
    s: (String) -> String,
    balance: Int,
    state: DonateActionState,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    TransferAmountDialog(
        title = "${s("donate_dialog_title")} ${target.displayName}",
        budgetLabel = s("donate_dialog_balance_label"),
        budget = balance,
        pointsSuffix = s("transfer_points_suffix"),
        amountLabel = s("transfer_amount_label"),
        confirmLabel = s("transfer_confirm"),
        cancelLabel = s("transfer_cancel"),
        errorText = (state as? DonateActionState.Error)?.let { s(it.messageKey) },
        isLoading = state is DonateActionState.Loading,
        emptyBudgetText = s("donate_no_balance"),
        amountRangeHint = s("transfer_amount_range_hint").replace("%d", balance.toString()),
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/**
 * Diálogo de importe reutilizado por "Agradecer" y "Donar": ambos piden una
 * cantidad de puntos con un tope visible ([budget], presupuesto semanal o
 * saldo según el caso) y muestran el error de la última acción, si lo hay.
 */
@Composable
private fun TransferAmountDialog(
    title: String,
    budgetLabel: String,
    budget: Int,
    pointsSuffix: String,
    amountLabel: String,
    confirmLabel: String,
    cancelLabel: String,
    errorText: String?,
    isLoading: Boolean,
    emptyBudgetText: String? = null,
    amountRangeHint: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    val amount = amountText.toIntOrNull() ?: 0
    val isValid = amount in 1..budget
    val showRangeError = amountText.isNotBlank() && !isValid

    AlertDialog(
        onDismissRequest = onDismiss,
        // Panel v18 (UX): con la transferencia en vuelo, tocar fuera cerraba el
        // diálogo sin que el usuario supiera que la petición seguía en curso.
        properties = DialogProperties(dismissOnClickOutside = !isLoading),
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "$budgetLabel: $budget $pointsSuffix",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (budget <= 0 && emptyBudgetText != null) {
                    Text(
                        text = emptyBudgetText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter(Char::isDigit) },
                        label = { Text(amountLabel) },
                        singleLine = true,
                        isError = showRangeError,
                        // Antes el botón "Confirmar" solo se deshabilitaba sin
                        // ninguna pista de por qué (importe fuera de rango).
                        supportingText = { Text(amountRangeHint) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(amount) },
                enabled = isValid && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        }
    )
}
