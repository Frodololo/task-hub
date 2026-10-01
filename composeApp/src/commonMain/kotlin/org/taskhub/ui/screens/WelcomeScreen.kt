/**
 * Pantalla de bienvenida (landing) que se muestra cuando el usuario no tiene
 * ningún hogar guardado localmente, o vuelve aquí manualmente. Ofrece crear
 * un hogar nuevo ([CreateHouseholdScreen]), unirse a uno existente por código
 * de invitación ([JoinHouseholdScreen]) o, si ya hay hogares guardados en
 * [org.taskhub.storage.HouseholdStore], saltar directo a [HomeScreen].
 * Usa [org.taskhub.ui.models.HomeScreenModel] para leer/reconciliar esos
 * hogares guardados contra Firestore.
 */
package org.taskhub.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.taskhub.storage.SavedHousehold
import org.taskhub.ui.components.HouseholdSettingsDialog
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.TaskHubTopBar
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.HomeScreenModel
import org.taskhub.ui.theme.semanticColors

/**
 * Landing screen sin ScreenModel propio de navegación: gestiona el diálogo
 * de ajustes y los tres accesos de entrada al hogar (crear / unirse / ir a
 * mis hogares).
 */
class WelcomeScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val homeModel = koinScreenModel<HomeScreenModel>()
        var savedHouseholds by remember { mutableStateOf<List<SavedHousehold>>(homeModel.getSavedHouseholds()) }
        val appSettings = LocalAppSettings.current
        val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }

        var showSettings by remember { mutableStateOf(false) }

        // Fade-in del bloque de contenido (informe delight §2.1): un solo fade de
        // conjunto, sin stagger por botón (evita sobrecargar la pantalla).
        val reduceMotion = shouldReduceMotion()
        var contentVisible by remember { mutableStateOf(false) }
        val contentAlpha by animateFloatAsState(
            targetValue = if (contentVisible) 1f else 0f,
            animationSpec = tween(durationMillis = if (reduceMotion) 0 else 300)
        )
        LaunchedEffect(Unit) { contentVisible = true }

        // Reconcilia contra Firestore para podar hogares "fantasma" (borrados o sin acceso).
        LaunchedEffect(Unit) {
            savedHouseholds = homeModel.reconcileHouseholds()
        }

        // Settings dialog
        if (showSettings) {
            HouseholdSettingsDialog(
                onDismiss = { showSettings = false },
                onEditProfile = {
                    showSettings = false
                    navigator.push(EditProfileScreen())
                }
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                TaskHubTopBar(
                    title = "Task Hub",
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = s("profile_settings_label"))
                        }
                    }
                )

                // Main content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                        .alpha(contentAlpha),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "👥",
                        style = MaterialTheme.typography.displayLarge
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = s("welcome_title"),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = s("welcome_subtitle"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(48.dp))

                    Button(
                        onClick = { navigator.push(CreateHouseholdScreen()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(
                            text = s("welcome_create"),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { navigator.push(JoinHouseholdScreen()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            // Panel v18 (accesibilidad): `colorScheme.tertiary` crudo sobre el
                            // fondo de la pantalla fallaba WCAG AA (4.19:1 en Naturaleza claro,
                            // bajo el umbral 4.5:1) — mismo fix que TaskDetailScreen.kt.
                            contentColor = MaterialTheme.semanticColors.info
                        ),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(
                            text = s("welcome_join"),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    if (savedHouseholds.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { navigator.replaceAll(HomeScreen()) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Text(
                                text = s("welcome_my_households"),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Text(
                        // Sin acceso multiplataforma trivial al versionName de Gradle desde
                        // commonMain: recuerda actualizar este literal en cada
                        // "chore: bump versión X.Y.Z" (ver CLAUDE.md).
                        text = "v0.7.46",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}