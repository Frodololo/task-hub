/**
 * Pantalla raíz de la app tras el login (landing page principal). Usa
 * [org.taskhub.ui.models.HomeScreenModel] para cargar todos los hogares del
 * usuario y una previsualización de tareas de cada uno. Navega a
 * [ProfileScreen], [PersonalSpaceScreen], [HouseholdScreen],
 * [CreateHouseholdScreen] y [JoinHouseholdScreen].
 */
package org.taskhub.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.taskhub.ui.components.AppLogo
import org.taskhub.ui.components.EmptyHouseholdsIllustration
import org.taskhub.ui.components.EmptyState
import org.taskhub.ui.components.HouseholdSettingsDialog
import org.taskhub.ui.components.HouseholdTaskSection
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.ShimmerList
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.HomeScreenModel
import org.taskhub.platform.AdBannerSlot

/**
 * Pantalla principal de la app — dashboard unificado que muestra las tareas
 * de todos los hogares del usuario (incluyendo el espacio Personal).
 *
 * Reemplaza a [WelcomeScreen] como landing page.
 */
class HomeScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val appSettings = LocalAppSettings.current
        val s = { key: String -> AppStrings.get(key, appSettings.currentLanguage) }
        val model = koinScreenModel<HomeScreenModel>()
        val uiState by model.uiState.collectAsState()
        val previewTasks by model.previewTasks.collectAsState()
        val reduceMotion = shouldReduceMotion()

        // Semilla con la lista guardada localmente (sync, sin red) en vez de
        // vacía: `households` es un `remember` LOCAL de este composable, así
        // que se reinicia cada vez que se vuelve a esta pantalla (p.ej. al
        // hacer pop() desde un hogar) — antes se quedaba en `emptyList()`
        // hasta que `reconcileHouseholds()` (red) resolvía, y offline eso
        // puede tardar (timeout) o nunca resolver rápido, dejando la rama de
        // "no tienes hogares, crea uno" visible por error aunque el usuario
        // SÍ tenga hogares guardados — bug reportado 2026-09-12 ("pantalla
        // principal en blanco al volver sin cobertura"). `reconcileHouseholds()`
        // en el LaunchedEffect de abajo sigue corriendo para podar hogares
        // fantasma cuando SÍ hay red.
        var households by remember { mutableStateOf(model.getSavedHouseholds()) }
        var showFabMenu by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

        // Cargar hogares + tareas de todos al entrar. Reconcilia primero contra
        // Firestore para podar hogares "fantasma" (borrados o sin acceso).
        LaunchedEffect(Unit) {
            households = model.reconcileHouseholds()
            model.loadAllTasks()
        }

        // Settings dialog
        if (showSettings) {
            HouseholdSettingsDialog(
                onDismiss = {
                    showSettings = false
                },
                onEditProfile = {
                    showSettings = false
                    navigator.push(EditProfileScreen())
                }
            )
        }

        Scaffold(
            modifier = Modifier.nestedScroll(topBarScrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppLogo(size = 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Task Hub", fontWeight = FontWeight.Bold)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            navigator.push(ProfileScreen(households))
                        }) {
                            Icon(Icons.Filled.Person, s("profile_title"))
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, s("profile_settings_label"))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    scrollBehavior = topBarScrollBehavior
                )
            },
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedVisibility(
                        visible = showFabMenu,
                        enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandVertically(),
                        exit = if (reduceMotion) ExitTransition.None else fadeOut() + shrinkVertically()
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            ExtendedFloatingActionButton(
                                onClick = {
                                    navigator.push(CreateHouseholdScreen())
                                    showFabMenu = false
                                },
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary,
                                icon = { Icon(Icons.Filled.Add, contentDescription = s("welcome_create"), modifier = Modifier.size(20.dp)) },
                                text = { Text(s("welcome_create"), fontWeight = FontWeight.SemiBold) }
                            )
                            Spacer(Modifier.height(12.dp))
                            ExtendedFloatingActionButton(
                                onClick = {
                                    navigator.push(JoinHouseholdScreen())
                                    showFabMenu = false
                                },
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                                icon = { Icon(Icons.Filled.Home, contentDescription = s("home_fab_join_space"), modifier = Modifier.size(20.dp)) },
                                text = { Text(s("home_fab_join_space"), fontWeight = FontWeight.SemiBold) }
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    FloatingActionButton(
                        onClick = { showFabMenu = !showFabMenu },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        if (showFabMenu) {
                            Icon(Icons.Filled.Close, s("settings_close"))
                        } else {
                            Icon(Icons.Filled.Add, s("home_fab_add_space"))
                        }
                    }
                }
            }
        ) { padding ->
            if (uiState.isLoading && households.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp)
                ) {
                    ShimmerList(count = 4, itemHeight = 96.dp)
                }
            } else if (households.isEmpty()) {
                EmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    illustration = { EmptyHouseholdsIllustration() },
                    title = s("home_empty_title"),
                    titleStyle = MaterialTheme.typography.titleLarge,
                    message = s("home_empty_subtitle"),
                    action = {
                        Button(onClick = { navigator.push(CreateHouseholdScreen()) }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s("welcome_create"), fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            } else {
                // Panel v17 (hallazgo MENOR de rendimiento): hoisted fuera del
                // bloque de contenido de LazyColumn — ese bloque es
                // `LazyListScope.() -> Unit`, NO un contexto @Composable
                // (solo lo que va dentro de `item {}`/`items {}` lo es), así
                // que `remember` no se puede llamar ahí dentro.
                val shared = remember(households) { households.filter { !it.isPersonal } }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(
                                s("home_pending_count_summary")
                                    .replace("%1", uiState.pendingCount.toString())
                                    .replace("%2", households.size.toString()),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    val personal = households.find { it.isPersonal }
                    if (personal != null) {
                        item(key = "personal") {
                            LaunchedEffect(personal.id) { model.loadHouseholdPreview(personal.id) }
                            HouseholdTaskSection(
                                household = personal,
                                previewState = previewTasks[personal.id],
                                onViewAll = { hid -> navigator.push(PersonalSpaceScreen(hid)) }
                            )
                        }
                    }

                    if (shared.isNotEmpty()) {
                        item(key = "shared_header") {
                            Text(
                                s("home_my_spaces"),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(shared.size, key = { shared[it].id }) { index ->
                            val h = shared[index]
                            LaunchedEffect(h.id) { model.loadHouseholdPreview(h.id) }
                            HouseholdTaskSection(
                                household = h,
                                previewState = previewTasks[h.id],
                                onViewAll = { hid -> navigator.push(HouseholdScreen(hid)) },
                                modifier = if (reduceMotion) Modifier else Modifier.animateItem()
                            )
                        }
                    }

                    // Mensaje de error si lo hay
                    if (uiState.error != null) {
                        item {
                            Text(
                                uiState.error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    item { Spacer(Modifier.height(80.dp)) }

                    // Banner de anuncios (preparado; deshabilitado por defecto).
                    // Se coloca al final del contenido principal, encima de
                    // cualquier barra inferior.
                    item(key = "ad_banner") {
                        AdBannerSlot()
                    }
                }
            }
        }
    }
}