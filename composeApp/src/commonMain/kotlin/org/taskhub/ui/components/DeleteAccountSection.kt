// Flujo de eliminación de cuenta (RGPD), usado dentro de SettingsSheet.
package org.taskhub.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.launch
import org.taskhub.ui.models.AccountDeletionCascadeException
import org.taskhub.ui.models.GoogleAuthManager
import org.taskhub.ui.screens.HomeScreen

/**
 * Flujo completo de "eliminar cuenta" (RGPD): descripción, botón, error con
 * región viva para TalkBack, y la doble confirmación (2 pasos) con
 * reautenticación reciente antes del borrado irreversible. Extraído de
 * `SettingsSheet` (que acumulaba demasiadas responsabilidades — panel v4,
 * UI/Componentes #5) para que el flujo de borrado de cuenta tenga su propio
 * componente con su propio estado, en vez de vivir mezclado con el resto de
 * ajustes.
 */
/**
 * Flujo completo de "eliminar cuenta" (RGPD); ver KDoc de cabecera.
 *
 * @param s resolutor de claves i18n ya fijado al idioma actual.
 * @param authManager gestiona la reautenticación reciente y el borrado real de la cuenta.
 * @param navigator usado para volver a [HomeScreen] tras un borrado exitoso.
 * @param onDismiss cierra la hoja de ajustes que aloja esta sección (se invoca tras borrar con éxito).
 */
@Composable
fun DeleteAccountSection(
    s: (String) -> String,
    authManager: GoogleAuthManager,
    navigator: Navigator,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showDeleteAccountConfirm by remember { mutableStateOf(false) }
    var showDeleteAccountConfirm2 by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }
    // Distingue el paso de reautenticación (puede relanzar el selector de
    // cuenta de Google) del borrado en sí — antes ambos mostraban el mismo
    // texto "Eliminando cuenta…", confundiendo al usuario cuando de repente
    // aparecía un selector de cuentas sin explicación (panel de revisión
    // 2026-09-03, Experto 5, IMPORTANTE #1).
    var isReauthenticating by remember { mutableStateOf(false) }
    var deleteAccountError by remember { mutableStateOf<String?>(null) }

    Text(
        text = s("settings_delete_account_desc"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    if (deleteAccountError != null) {
        Text(
            text = deleteAccountError.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            // Sin esto, TalkBack solo se entera del fallo del borrado de
            // cuenta (acción irreversible) si el usuario explora manualmente
            // hasta aquí — panel v4, Accesibilidad #1.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Spacer(Modifier.height(8.dp))
    }
    OutlinedButton(
        onClick = {
            deleteAccountError = null
            showDeleteAccountConfirm = true
        },
        enabled = !isDeletingAccount,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        if (isDeletingAccount) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = MaterialTheme.colorScheme.error,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(s(if (isReauthenticating) "settings_delete_account_reauthenticating" else "settings_delete_account_deleting"))
        } else {
            // Icono real en vez del emoji que llevaba el texto de AppStrings
            // — coherente con el patrón ya usado para borrar en
            // HouseholdMemberList/HouseholdScreen (panel v4, Estética #2).
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(s("settings_delete_account_button"))
        }
    }

    if (showDeleteAccountConfirm) {
        DestructiveConfirmDialog(
            title = s("settings_delete_account_confirm_title"),
            text = s("settings_delete_account_confirm_text"),
            s = s,
            onDismiss = { showDeleteAccountConfirm = false },
            onConfirm = {
                showDeleteAccountConfirm = false
                showDeleteAccountConfirm2 = true
            },
            confirmLabel = s("settings_delete_account_confirm_button")
        )
    }

    // Segundo paso (definitivo) de la doble confirmación — mismo patrón que
    // eliminar hogar (DeleteHouseholdConfirmDialog1/2) — panel v4, Experto 3/5.
    if (showDeleteAccountConfirm2) {
        DestructiveConfirmDialog(
            title = s("settings_delete_account_confirm2_title"),
            text = s("settings_delete_account_confirm2_text"),
            s = s,
            onDismiss = { showDeleteAccountConfirm2 = false },
            onConfirm = {
                showDeleteAccountConfirm2 = false
                isDeletingAccount = true
                deleteAccountError = null
                scope.launch {
                    // Reautenticación reciente ANTES del borrado irreversible
                    // — panel v4, Experto 9. No-op (true) si por algún motivo
                    // se llegara aquí sin sesión, ver KDoc de
                    // [GoogleAuthManager.reauthenticateForDeletion].
                    isReauthenticating = true
                    val reauthOk = authManager.reauthenticateForDeletion()
                    isReauthenticating = false
                    if (!reauthOk) {
                        isDeletingAccount = false
                        deleteAccountError = s("settings_delete_account_reauth_error")
                        return@launch
                    }
                    val result = authManager.deleteAccount()
                    isDeletingAccount = false
                    result.onSuccess {
                        onDismiss()
                        navigator.replaceAll(HomeScreen())
                    }.onFailure { e ->
                        deleteAccountError = if (e is AccountDeletionCascadeException) {
                            s("settings_delete_account_cascade_error")
                        } else {
                            s("settings_delete_account_error")
                        }
                    }
                }
            },
            confirmLabel = s("settings_delete_account_confirm2_button")
        )
    }
}
