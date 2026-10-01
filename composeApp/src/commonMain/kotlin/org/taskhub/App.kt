/**
 * Raíz de Compose de Task Hub, común a Android/iOS/JVM. Instala Koin
 * ([org.taskhub.di.appModule]), resuelve tema/idioma persistidos y aloja el
 * único [Navigator] de Voyager de la app.
 */
package org.taskhub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.FadeTransition
import cafe.adriel.voyager.transitions.SlideTransition
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.taskhub.di.appModule
import org.taskhub.network.FirestoreRepository
import org.taskhub.platform.NotificationScheduler
import org.taskhub.platform.bestEffort
import org.taskhub.storage.HouseholdStore
import org.taskhub.storage.SettingsStore
import org.taskhub.ui.components.AppSettingsState
import org.taskhub.ui.components.LocalAppSettings
import org.taskhub.ui.components.shouldReduceMotion
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.models.GoogleAuthManager
import org.taskhub.ui.models.GoogleAuthState
import org.taskhub.ui.screens.AuthGateScreen
import org.taskhub.ui.screens.HomeScreen
import org.taskhub.ui.screens.HouseholdScreen
import org.taskhub.ui.screens.SplashScreen
import org.taskhub.ui.screens.TaskDetailScreen
import org.taskhub.ui.theme.TaskHubTheme
import org.taskhub.ui.theme.TaskHubThemeType

/** Límite del bootstrap post-login (espacio Personal + miembro "Yo" + restaurar hogares) — ver LaunchedEffect en [App]. */
private const val BOOTSTRAP_TIMEOUT_MS = 20_000L

/** Tema persistido en [settingsStore], o [TaskHubThemeType.DEFAULT] si el valor guardado no es válido (p.ej. tras quitar un tema). */
private fun resolveTheme(settingsStore: SettingsStore): TaskHubThemeType =
    try {
        TaskHubThemeType.valueOf(settingsStore.getTheme())
    } catch (_: IllegalArgumentException) {
        TaskHubThemeType.DEFAULT
    }

/**
 * Composable raíz de la app.
 *
 * Orden de arranque: 1) [KoinApplication] instala [org.taskhub.di.appModule]
 * y se inyectan de inmediato [SettingsStore], [HouseholdStore],
 * [FirestoreRepository], [GoogleAuthManager] y [NotificationScheduler] —
 * ninguna de esas construcciones hace red (el estado de sesión de
 * [GoogleAuthManager.state] se lee síncronamente de disco); 2) el
 * `LaunchedEffect(authState)` de bootstrap (ver más abajo) arranca ya en este
 * punto, EN PARALELO con la animación de 1.5s de [SplashScreen] — antes el
 * bootstrap no empezaba hasta que el splash terminaba (1.5s muertos seguidos
 * de la carga de red en serie); solapar ambos aprovecha esos 1.5s para
 * adelantar trabajo real (rendimiento, 2026-09-25); 3) mientras dura la
 * animación se muestra [SplashScreen] (que lee el idioma de [SettingsStore]);
 * al terminar (`showSplash = false`) se resuelven tema/idioma reactivos y se
 * envuelve el árbol en [TaskHubTheme] + `LocalAppSettings`; 4) sin sesión de
 * Google ([GoogleAuthManager.state] distinto de `SignedIn`), se muestra
 * [org.taskhub.ui.screens.AuthGateScreen] y el resto de este composable no se
 * ejecuta — Task Hub es Google-only (ver
 * `docs/google-only-auth-2026-09-12.md`), no hay modo anónimo al que caer;
 * 5) ya con sesión, el bootstrap (que puede llevar ya un rato corriendo desde
 * el paso 2) hace la inicialización de arranque en frío — resolver/crear el
 * hogar "Personal" (determinista por UID para que sea el mismo en todos los
 * dispositivos con la misma cuenta), asegurar el miembro "Yo" en él y
 * restaurar hogares compartidos desde la nube
 * ([GoogleAuthManager.restoreFromCloudOnStartup]), en secuencia (cada paso
 * depende del anterior o comparte `HouseholdStore`); subir el token FCM
 * pendiente corre en paralelo (`launch`, no en la cadena anterior: escribe en
 * un documento distinto, sin nada que serializar) — todo best-effort (nunca
 * bloquea si está offline); 6) se crea
 * el único [Navigator] de Voyager de la app con la pila inicial
 * `[HomeScreen(), destino?]` (el destino del deep link, si lo hay, ya
 * incluido para evitar un salto visual doble).
 *
 * [deepLinkHouseholdId]/[deepLinkTaskId] llegan de tocar una notificación
 * local del sistema (Android: [org.taskhub.NotificationHelper.showUpdateNotification]
 * o el recordatorio de tarea) — `null` en el arranque normal e ignorados en
 * iOS/JVM (no producen notificaciones del sistema hoy, ver
 * docs/review-panel-expertos-notificaciones-2026-09-05.md, gap B).
 * [deepLinkTaskId] vacío (no null) es el centinela de "es un mensaje de chat,
 * no una tarea" — abre [HouseholdScreen] en vez de [TaskDetailScreen].
 * [deepLinkNotificationId], si llega, se marca como leída en Firestore al
 * consumir el deep link — sin esto, tocar la notificación del sistema no
 * tenía ningún efecto sobre su estado "no leída" en la lista in-app,
 * inconsistente con tocar la card desde `NotificationListScreen` (panel de
 * notificaciones 2026-09-05, UX).
 */
@Composable
fun App(
    deepLinkHouseholdId: String? = null,
    deepLinkTaskId: String? = null,
    deepLinkNotificationId: String? = null
) {
    // ── Fase 1: Splash screen (1.5 segundos) ─────────────────
    var showSplash by remember { mutableStateOf(true) }

    // KoinApplication envuelve también el splash para poder leer el idioma
    // guardado (SettingsStore) y mostrar el subtítulo en el idioma correcto.
    KoinApplication(application = {
        modules(appModule)
    }) {
        val settingsStore = koinInject<SettingsStore>()

        // Inyecciones y bootstrap de red: se resuelven YA (no dentro del `if
        // (showSplash)` de abajo) para que el `LaunchedEffect` de bootstrap
        // arranque en paralelo con la animación del splash en vez de esperar
        // a que termine — ver KDoc de [App] (rendimiento, 2026-09-25).
        // Ninguna de estas construcciones hace red por sí misma.
        val householdStore = koinInject<HouseholdStore>()
        val repo = koinInject<FirestoreRepository>()
        val authManager = koinInject<GoogleAuthManager>()
        val notificationScheduler = koinInject<NotificationScheduler>()
        val authState by authManager.state.collectAsState()

        var initialScreens by remember { mutableStateOf<List<Screen>?>(null) }
        // Deep link ya incorporado a la pila con la que se crea el
        // Navigator (arranque en frío) — evita que el LaunchedEffect
        // de más abajo lo "empuje" una segunda vez y deje una
        // pantalla duplicada en la pila (panel de notificaciones
        // 2026-09-05, UX, doble salto visual Home→destino).
        var initialDeepLinkConsumedKey by remember { mutableStateOf<Pair<String?, String?>?>(null) }
        // true si el bootstrap de abajo excedió BOOTSTRAP_TIMEOUT_MS
        // sin terminar — el resto de pasos ya son best-effort/
        // offline-first (nunca lanzan), así que la única forma
        // realista de quedarse colgado es una llamada de red sin
        // resolver nunca. Splash/D6: antes no había ningún límite ni
        // pantalla de error, solo el spinner de "Still loading" de
        // más abajo indefinidamente.
        var bootstrapFailed by remember { mutableStateOf(false) }
        // Cambiarlo relanza el LaunchedEffect (ver su `key`) para que
        // el botón "Reintentar" de la pantalla de error repita el
        // bootstrap sin tener que cerrar/abrir sesión.
        var bootstrapRetryKey by remember { mutableStateOf(0) }

        // Task Hub es Google-only (ver docs/google-only-auth-2026-09-12.md):
        // sin sesión de Google, [AuthGateScreen] bloquea el resto de la app
        // más abajo — este bootstrap NO debe correr (todo lo que hace
        // requiere Firestore autenticado) hasta que `authState` sea
        // SignedIn. Si el usuario cierra sesión (o se elimina la cuenta)
        // mientras ya estaba dentro, `initialScreens` se resetea a null
        // para reconstruir el Navigator desde cero en el próximo login.
        LaunchedEffect(authState, bootstrapRetryKey) {
            if (authState !is GoogleAuthState.SignedIn) {
                initialScreens = null
                bootstrapFailed = false
                return@LaunchedEffect
            }
            // ── Subir el token FCM del dispositivo (si hay uno persistido) ──
            // Lanzado en paralelo (`launch`, no `await`ado aquí) en vez de
            // al final de este bloque secuencial: escribe en
            // `users/{uid}.fcmToken`, un documento que ningún otro paso de
            // este bootstrap toca, así que no hay ninguna escritura
            // concurrente que serializar (a diferencia de repointear el
            // hogar Personal y restaurar hogares compartidos, que si se
            // paralelizaran entre sí sí podrían pisarse al escribir la
            // MISMA lista en `HouseholdStore` sin un lock — ver PROPUESTA
            // en el informe de rendimiento del panel v10). No bloquea la
            // construcción de `initialScreens`: es best-effort, igual que
            // antes (panel de expertos v10, rendimiento, IMPORTANTE — antes
            // añadía un round-trip de red entero, en serie, al final del
            // arranque en frío sin ninguna razón para no solaparlo).
            launch {
                // Offline/transitorio: se reintenta en el próximo arranque.
                bestEffort(Unit, "App.saveFcmToken") {
                    val uid = repo.getLocalId()
                    val fcmToken = notificationScheduler.getFcmToken()
                    if (uid != null && fcmToken != null) {
                        repo.saveFcmToken(uid, fcmToken)
                    }
                }
            }
            // Los tres pasos de abajo (resolver espacio Personal, asegurar
            // el miembro "Yo", restaurar hogares compartidos) son todos
            // best-effort/offline-first (nunca lanzan por sí mismos) —
            // la única forma realista de quedarse colgado aquí es una
            // llamada de red que nunca resuelve. Este timeout es la red
            // de seguridad: si se excede, se corta y se muestra la
            // pantalla de error con "Reintentar" en vez de dejar el
            // spinner de "Still loading" girando para siempre (D6).
            val bootstrapCompleted = withTimeoutOrNull(BOOTSTRAP_TIMEOUT_MS) {
                // ── Resolver/crear el espacio Personal (interdispositivo) ──
                // El ID es determinista (personal_{uid}), de modo que con la
                // misma cuenta de Google todos los dispositivos apuntan al
                // MISMO hogar.
                var personalId: String? = null
                try {
                    val personal = repo.getOrCreatePersonalHousehold()
                    householdStore.replacePersonalHousehold(personal.id)
                    personalId = personal.id
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Sin conexión: recurrir al guardado local o a un placeholder.
                    personalId = householdStore.getPersonalHouseholdId()
                        ?: householdStore.getSavedHouseholds()
                            .firstOrNull { it.isPersonal }?.id
                        ?: "personal-offline".also {
                            householdStore.savePersonalHousehold(it)
                            householdStore.saveHousehold(
                                householdId = it,
                                householdName = "Personal",
                                inviteCode = "",
                                isPersonal = true
                            )
                        }
                }

                // ── Asegurar que el espacio Personal tenga un miembro "Yo" ──
                // Para que completar tareas sepa quién las hace (cubre migración).
                if (!personalId.isNullOrBlank() && personalId != "personal-offline") {
                    // No crítico: si falla (offline), se reintenta al reabrir
                    bestEffort(Unit, "App.ensurePersonalMember") {
                        repo.ensurePersonalMember(personalId)
                    }
                }

                // ── Restaurar hogares compartidos desde la nube ──
                // Cubre hogares creados/unidos en OTRO dispositivo con la
                // misma cuenta de Google (antes solo se restauraban al re-loguearse).
                authManager.restoreFromCloudOnStartup()
            }
            if (bootstrapCompleted == null) {
                bootstrapFailed = true
                return@LaunchedEffect
            }

            // ── Ir siempre a HomeScreen, con el destino del deep link
            // (si lo hay) ya incluido en la pila inicial ───────────
            // En vez de crear el Navigator solo con HomeScreen y hacer
            // `push` al destino en un LaunchedEffect posterior (lo que
            // pintaba HomeScreen un frame antes de la transición), la
            // API `Navigator(screens: List<Screen>, ...)` de Voyager
            // 1.1.0-beta03 permite construir la pila `[HomeScreen(),
            // destino]` directamente, mostrando ya el destino en el
            // primer frame (con "atrás" volviendo a Home).
            val screens = mutableListOf<Screen>(HomeScreen())
            if (!deepLinkHouseholdId.isNullOrEmpty()) {
                screens += if (deepLinkTaskId.isNullOrEmpty()) {
                    HouseholdScreen(deepLinkHouseholdId)
                } else {
                    TaskDetailScreen(deepLinkHouseholdId, deepLinkTaskId)
                }
                initialDeepLinkConsumedKey = deepLinkHouseholdId to deepLinkTaskId
                if (!deepLinkNotificationId.isNullOrEmpty()) {
                    // No crítico: solo afecta al estado "leída" in-app.
                    bestEffort(Unit, "App.markNotificationRead") {
                        repo.markNotificationRead(deepLinkHouseholdId, deepLinkNotificationId)
                    }
                }
            }
            initialScreens = screens
        }

        if (showSplash) {
            // D9 (2026-09-24): el splash debe respetar el tema guardado (Default/
            // Naturaleza/Minimal), no una paleta Teal/Coral fija — se resuelve aquí
            // igual que `themeType` más abajo porque `LocalAppSettings` aún no
            // está disponible en esta fase.
            val splashThemeType = resolveTheme(settingsStore)
            SplashScreen(
                lang = settingsStore.getLanguage(),
                themeType = splashThemeType,
                onFinished = { showSplash = false }
            )
            return@KoinApplication
        }

        // Reactive theme from settings
        var themeType by remember {
            mutableStateOf(resolveTheme(settingsStore))
        }

        // Reactive language from settings
        var currentLanguage by remember {
            mutableStateOf(settingsStore.getLanguage())
        }

        val appSettings = remember(themeType, currentLanguage) {
            AppSettingsState(
                currentLanguage = currentLanguage,
                currentTheme = themeType,
                onThemeChanged = { newTheme ->
                    themeType = newTheme
                    settingsStore.setTheme(newTheme.name)
                },
                onLanguageChanged = { newLang ->
                    currentLanguage = newLang
                    settingsStore.setLanguage(newLang)
                }
            )
        }

        TaskHubTheme(themeType = themeType) {
            CompositionLocalProvider(LocalAppSettings provides appSettings) {
                if (authState !is GoogleAuthState.SignedIn) {
                    // Google-only: sin sesión iniciada, bloquea el resto de la
                    // app con el gate de login en vez de mostrar HomeScreen.
                    AuthGateScreen(
                        authManager = authManager,
                        authState = authState,
                        lang = appSettings.currentLanguage
                    )
                    return@CompositionLocalProvider
                }

                // Surface paints the background behind system bars (edge-to-edge)
                // Inner Box applies system bar padding so content doesn't overlap
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        when (val screens = initialScreens) {
                            null -> {
                                if (bootstrapFailed) {
                                    // El bootstrap excedió BOOTSTRAP_TIMEOUT_MS —
                                    // ver LaunchedEffect de arriba (D6).
                                    Box(
                                        modifier = Modifier.fillMaxSize().padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = AppStrings.get("splash_bootstrap_error_title", appSettings.currentLanguage),
                                                style = MaterialTheme.typography.titleMedium,
                                                textAlign = TextAlign.Center,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Spacer(Modifier.height(16.dp))
                                            Button(onClick = {
                                                bootstrapFailed = false
                                                bootstrapRetryKey++
                                            }) {
                                                Text(AppStrings.get("splash_bootstrap_error_retry", appSettings.currentLanguage))
                                            }
                                        }
                                    }
                                } else {
                                    // Still loading
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .semantics { contentDescription = "Cargando" },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            else -> {
                                val reduceMotion = shouldReduceMotion()
                                Navigator(screens = screens) { navigator ->
                                    // Se ejecuta una vez por (navigator, deep
                                    // link). El deep link con el que se creó la
                                    // pila inicial (arranque en frío) ya quedó
                                    // resuelto arriba — este efecto solo debe
                                    // actuar cuando llega uno DISTINTO mientras
                                    // la Activity ya estaba viva (onNewIntent),
                                    // que sí dispara un `push` con transición
                                    // normal (aquí no hay "doble salto" porque
                                    // la app ya se estaba mostrando).
                                    LaunchedEffect(navigator, deepLinkHouseholdId, deepLinkTaskId) {
                                        val hid = deepLinkHouseholdId ?: return@LaunchedEffect
                                        if ((hid to deepLinkTaskId) == initialDeepLinkConsumedKey) return@LaunchedEffect
                                        if (deepLinkTaskId.isNullOrEmpty()) {
                                            navigator.push(HouseholdScreen(hid))
                                        } else {
                                            navigator.push(TaskDetailScreen(hid, deepLinkTaskId))
                                        }
                                        if (!deepLinkNotificationId.isNullOrEmpty()) {
                                            // No crítico: solo afecta al estado "leída" in-app.
                                            bestEffort(Unit, "App.markNotificationRead") {
                                                repo.markNotificationRead(hid, deepLinkNotificationId)
                                            }
                                        }
                                    }
                                    if (reduceMotion) {
                                        FadeTransition(navigator)
                                    } else {
                                        SlideTransition(navigator)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}