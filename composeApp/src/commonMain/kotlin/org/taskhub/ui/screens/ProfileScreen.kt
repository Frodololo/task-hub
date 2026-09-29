/**
 * Pantalla de listado de hogares del usuario (espacio Personal + hogares
 * compartidos), accesible desde la barra inferior/navegación principal.
 * No tiene ScreenModel propio: recibe la lista de [SavedHousehold] ya
 * cargada por el llamador (leída de [org.taskhub.storage.HouseholdStore]);
 * navega a [HouseholdScreen], [CreateHouseholdScreen], [JoinHouseholdScreen]
 * o [EditProfileScreen] (vía el diálogo de ajustes).
 */
package org.taskhub.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.taskhub.storage.SavedHousehold
import org.taskhub.ui.components.HouseholdSettingsDialog
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.TaskHubTopBar
import org.taskhub.ui.i18n.AppStrings

import androidx.compose.material3.ExperimentalMaterial3Api

/**
 * Pantalla de perfil del usuario. Muestra:
 * - Sus hogares (Personal + compartidos)
 * - Acceso a crear/unirse a hogares
 * - Acceso a ajustes
 */
class ProfileScreen(private val households: List<SavedHousehold>) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val appSettings = LocalAppSettings.current
        val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

        var showSettings by remember { mutableStateOf(false) }
        val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
        // Panel v18 (rendimiento): mismo hallazgo y mismo fix que HomeScreen.kt
        // (panel v17) — antes se recalculaba en cada recomposición dentro del
        // contenido de LazyColumn.
        val personalHousehold = remember(households) { households.find { it.isPersonal } }
        val sharedHouseholds = remember(households) { households.filter { !it.isPersonal } }

        if (showSettings) {
            HouseholdSettingsDialog(
                onDismiss = { showSettings = false },
                onEditProfile = {
                    showSettings = false
                    navigator.push(EditProfileScreen())
                }
            )
        }

        Scaffold(
            modifier = Modifier.nestedScroll(topBarScrollBehavior.nestedScrollConnection),
            // TaskHubTopBar (no TopAppBar manual): consistencia con las otras
            // pantallas — la barra manual dejaba el título alineado a la izquierda.
            topBar = {
                TaskHubTopBar(
                    title = s("profile_title"),
                    onBack = { navigator.pop() },
                    scrollBehavior = topBarScrollBehavior
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Cabecera
                item {
                    Text(
                        s("household_list_my"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Espacio Personal
                if (personalHousehold != null) {
                    item {
                        HouseholdProfileCard(
                            household = personalHousehold,
                            icon = Icons.Default.Person,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Hogares compartidos
                items(sharedHouseholds, key = { it.id }) { household ->
                    HouseholdProfileCard(
                        household = household,
                        icon = Icons.Default.Edit,
                        // Coral500 fijo teñía el icono con 2.60-2.81:1 en los 3 temas
                        // claros (falla el umbral 3:1 de icono significativo, WCAG
                        // 1.4.11) — colorScheme.tertiary ya está auditado en los 6
                        // temas/modo.
                        color = MaterialTheme.colorScheme.tertiary,
                        onNavigate = { navigator.push(HouseholdScreen(household.id)) }
                    )
                }

                // Acciones
                item { Spacer(Modifier.height(8.dp)) }

                item {
                    Button(
                        onClick = { navigator.push(CreateHouseholdScreen()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = s("create_household_title"))
                        Spacer(Modifier.width(8.dp))
                        Text(s("create_household_title"))
                    }
                }

                item {
                    OutlinedButton(
                        onClick = { navigator.push(JoinHouseholdScreen()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Home, contentDescription = s("welcome_join"))
                        Spacer(Modifier.width(8.dp))
                        Text(s("welcome_join"))
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }

                // Ajustes
                item {
                    OutlinedButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = s("profile_settings_label"))
                        Spacer(Modifier.width(8.dp))
                        Text(s("profile_settings_label"))
                    }
                }
            }
        }
    }
}

/**
 * Tarjeta de hogar en el listado de perfil. Si [onNavigate] es `null` la
 * tarjeta no es interactiva (sin flecha ni `clickable`); el llamador solo lo
 * pasa para hogares compartidos, no para el espacio Personal.
 */
@Composable
private fun HouseholdProfileCard(
    household: SavedHousehold,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    onNavigate: (() -> Unit)? = null
) {
    val appSettings = LocalAppSettings.current
    val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

    Card(
        modifier = Modifier.fillMaxWidth().then(
            // role = Button: sin esto, TalkBack leía la tarjeta como texto
            // estático sin anunciar que es pulsable (panel 2026-09-11, MENOR).
            if (onNavigate != null) Modifier.clickable(role = Role.Button) { onNavigate() } else Modifier
        ),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = household.name, tint = color)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (household.isPersonal) household.name
                    else "${if (!household.emoji.isNullOrBlank()) household.emoji else household.spaceType.emoji} ${household.name}",
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (household.isPersonal) {
                    Text(
                        s("profile_card_private_space"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        s("profile_card_code_prefix").replace("%s", household.inviteCode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onNavigate != null) {
                // ArrowForward, no ArrowBack: este icono significa "ir a/entrar en
                // este hogar", no "volver" — una flecha de retroceso en esa posición
                // confundía la dirección de la acción.
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = s("profile_card_go_to_space"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                )
            }
        }
    }
}