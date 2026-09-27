/**
 * Manager (no ScreenModel) inyectado como singleton en Koin que centraliza
 * el login con Google, la obtención de tokens OAuth de Calendar y el borrado
 * de cuenta. Expone [GoogleAuthManager.state] ([GoogleAuthState]), observado
 * por [org.taskhub.App] (gate de login) y las pantallas de ajustes/perfil;
 * [CalendarSyncManager] depende de él para los tokens.
 */
package org.taskhub.ui.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.taskhub.network.FirestoreRepository
import org.taskhub.platform.AppLog
import org.taskhub.platform.GoogleSignInResult
import org.taskhub.platform.GoogleSignInResultHolder
import org.taskhub.platform.cancelGoogleSignIn
import org.taskhub.platform.consumeLastSignInFailureReason
import org.taskhub.platform.getGoogleCalendarAccessToken
import org.taskhub.platform.launchGoogleSignIn
import org.taskhub.platform.revokeGoogleCalendarAccess
import org.taskhub.storage.HouseholdStore
import org.taskhub.storage.SettingsStore
import org.taskhub.ui.i18n.AppStrings
import org.taskhub.ui.i18n.toUserMessage

/**
 * Estado del login del usuario.
 */
sealed class GoogleAuthState {
    data object SigningIn : GoogleAuthState()
    data class SignedIn(val email: String?) : GoogleAuthState()
    /** Sin sesión — Task Hub exige login con Google (no hay modo anónimo, ver `docs/google-only-auth-2026-09-12.md`). */
    data object SignedOut : GoogleAuthState()
    data class Error(val message: String) : GoogleAuthState()
}

/**
 * Señala que [GoogleAuthManager.deleteAccount] no pudo completar el
 * cascade-delete de TODOS los hogares del usuario (algún `leaveHousehold`/
 * `deleteHousehold` falló) — por eso la cuenta de Firebase Auth (paso
 * irreversible) NO se ha borrado, para que el usuario conserve la sesión con
 * la que reintentarlo (panel v4, Experto 12 hallazgo #2).
 */
class AccountDeletionCascadeException :
    Exception("No se pudieron borrar/abandonar todos los hogares del usuario; la cuenta no se ha eliminado.")

/**
 * Orquesta el login con Google — único mecanismo de auth de Task Hub, sin
 * modo anónimo (ver `docs/google-only-auth-2026-09-12.md`).
 *
 * - [signIn] lanza el flujo nativo de Google Sign-In y, al recibir el idToken,
 *   lo intercambia por credenciales de Firebase Auth vía [FirestoreRepository.signInWithGoogle].
 * - Guarda el UID estable de Google en [SettingsStore] para que los datos
 *   sobrevivan a una reinstalación.
 * - Restaura los hogares del usuario desde Firestore (users/{uid}).
 *
 * Se registra como singleton en Koin para que HomeScreen y Ajustes compartan estado.
 */
class GoogleAuthManager(
    private val repo: FirestoreRepository,
    private val settingsStore: SettingsStore,
    private val householdStore: HouseholdStore
) {
    private companion object {
        /**
         * Sentinel contra bucle de reintento: tras este número de fallos
         * consecutivos de [signIn] (sin ningún éxito entre medias), se deja de
         * relanzar el flujo nativo y se muestra un error fijo hasta el próximo
         * inicio de sesión correcto — sin esto, un fallo persistente del
         * proveedor Google (p.ej. backend caído) dejaría al usuario reintentando
         * el mismo flujo sin límite.
         */
        const val MAX_CONSECUTIVE_SIGN_IN_FAILURES = 3

        /**
         * Si el flujo nativo de Google Sign-In no resuelve (ni éxito ni
         * cancelación) en este plazo, se da por fallido — sin esto, un flujo
         * colgado (p.ej. el selector de cuenta nunca se cierra) dejaría
         * [GoogleAuthState.SigningIn] para siempre, con el botón de login
         * deshabilitado sin salida.
         *
         * Antes 60s: ese valor era MENOR que el propio timeout interno de los
         * flujos largos (`GoogleDesktopSignInHelper.CALLBACK_TIMEOUT_MILLIS` =
         * 5 min en JVM, `GIS_TIMEOUT_MS` = 2 min en wasmJs), así que este
         * timeout genérico disparaba SIEMPRE primero: un login legítimo pero
         * lento (2FA, escribir la contraseña, elegir cuenta en el navegador
         * de escritorio) mostraba "tiempo de espera agotado" mientras el
         * flujo nativo seguía en curso de fondo — y si el usuario terminaba
         * de loguearse justo después, la pantalla pasaba de golpe de "Error"
         * a "Conectado" sin que el usuario entendiera por qué (justo la
         * confusión fallo-de-red-vs-cancelación de la tarjeta del kanban).
         * Ahora se alinea con el peor caso real (el flujo de escritorio) para
         * que nunca dispare antes de que el propio flujo nativo tenga ocasión
         * de resolver por sí mismo. El botón "Cancelar" de `AuthGateScreen`
         * (ver [cancelSignIn]) es la salida temprana que antes faltaba.
         */
        const val SIGN_IN_TIMEOUT_MS = 5 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Fallos consecutivos de [signIn] sin éxito entre medias — ver [MAX_CONSECUTIVE_SIGN_IN_FAILURES]. */
    private var consecutiveSignInFailures = 0

    /** Vigila que el flujo lanzado por [signIn] resuelva antes de [SIGN_IN_TIMEOUT_MS]; se cancela al llegar cualquier resultado. */
    private var signInTimeoutJob: Job? = null

    private val _state = MutableStateFlow<GoogleAuthState>(
        if (settingsStore.isGoogleLoggedIn()) {
            GoogleAuthState.SignedIn(settingsStore.getGoogleEmail())
        } else {
            GoogleAuthState.SignedOut
        }
    )
    val state: StateFlow<GoogleAuthState> = _state.asStateFlow()

    init {
        // Observa el resultado del flujo nativo de Google Sign-In
        scope.launch {
            GoogleSignInResultHolder.result.collect { result ->
                when (result) {
                    // En curso (aún no resuelto); no hacer nada.
                    is GoogleSignInResult.Loading -> Unit
                    // Cancelado / sin token → volver a SignedOut para no
                    // quedarse colgado en "Conectando con Google...", SALVO
                    // que la plataforma reporte un motivo específico de fallo
                    // (hoy solo wasmJs — ver `Platform.wasmJs.kt`/`index.html`):
                    // el error actual en web era silencioso (D4/item 4).
                    is GoogleSignInResult.Cancelled -> {
                        signInTimeoutJob?.cancel()
                        GoogleSignInResultHolder.reset()
                        _state.value = when (consumeLastSignInFailureReason()) {
                            "gis_not_loaded" -> {
                                consecutiveSignInFailures++
                                GoogleAuthState.Error(
                                    AppStrings.get("google_auth_error_gis_not_loaded", settingsStore.getLanguage())
                                )
                            }
                            "origin_not_authorized" -> {
                                consecutiveSignInFailures++
                                GoogleAuthState.Error(
                                    AppStrings.get("google_auth_error_origin_not_authorized", settingsStore.getLanguage())
                                )
                            }
                            else -> GoogleAuthState.SignedOut
                        }
                    }
                    is GoogleSignInResult.Success -> {
                        signInTimeoutJob?.cancel()
                        handleGoogleToken(result.token)
                        GoogleSignInResultHolder.reset()
                    }
                }
            }
        }
    }

    /**
     * Lanza el flujo de Google Sign-In (abre el selector de cuenta).
     *
     * Guarda de reentrancia: si ya hay un flujo en curso (doble-tap en
     * "Vincular con Google", o [linkCalendar] invocado dos veces casi a la vez
     * desde Ajustes y desde el detalle de una tarea), una segunda llamada NO
     * relanza el flujo nativo ni resetea [GoogleSignInResultHolder] — eso
     * dejaría el resultado del primer flujo (que puede llegar después del
     * reset) sin nadie que lo recoja, colgando ese `state.first { ... }` en
     * [linkCalendar] para siempre.
     *
     * Sentinel contra bucle de reintento: si ya hubo
     * [MAX_CONSECUTIVE_SIGN_IN_FAILURES] fallos consecutivos sin un login
     * correcto entre medias, no relanza el flujo nativo — deja un error fijo
     * hasta que [consecutiveSignInFailures] se resetee (login correcto). Y si
     * el flujo lanzado no resuelve en [SIGN_IN_TIMEOUT_MS], lo da por fallido.
     */
    fun signIn() {
        if (_state.value is GoogleAuthState.SigningIn) return
        if (consecutiveSignInFailures >= MAX_CONSECUTIVE_SIGN_IN_FAILURES) {
            _state.value = GoogleAuthState.Error(
                AppStrings.get("google_auth_error_locked_out", settingsStore.getLanguage())
            )
            return
        }
        _state.value = GoogleAuthState.SigningIn
        GoogleSignInResultHolder.reset()
        launchGoogleSignIn()
        signInTimeoutJob?.cancel()
        signInTimeoutJob = scope.launch {
            delay(SIGN_IN_TIMEOUT_MS)
            GoogleSignInResultHolder.reset()
            consecutiveSignInFailures++
            _state.value = GoogleAuthState.Error(
                AppStrings.get("google_auth_error_timeout", settingsStore.getLanguage())
            )
        }
    }

    /**
     * Aborta un [signIn] en curso — botón "Cancelar" de `AuthGateScreen`
     * durante [GoogleAuthState.SigningIn]. Antes de esto, la única salida de
     * esa pantalla era esperar a [SIGN_IN_TIMEOUT_MS] (ver su KDoc, ahora 5
     * min): sin botón, un usuario que cambia de idea o cuyo navegador/
     * selector de cuenta se queda colgado no tenía forma de volver atrás.
     *
     * Además de volver a [GoogleAuthState.SignedOut], llama a
     * [cancelGoogleSignIn] (no-op en Android/iOS, cancela el trabajo de fondo
     * en JVM/wasmJs) para que un resultado tardío del intento cancelado no
     * reabra sesión (o muestre un error) más tarde en una pantalla en la que
     * el usuario ya había vuelto a "sin sesión" a propósito. No-op si no hay
     * ningún sign-in en curso.
     */
    fun cancelSignIn() {
        if (_state.value !is GoogleAuthState.SigningIn) return
        signInTimeoutJob?.cancel()
        cancelGoogleSignIn()
        GoogleSignInResultHolder.reset()
        _state.value = GoogleAuthState.SignedOut
    }

    /**
     * Cierra sesión: sin modo anónimo de respaldo, la app queda sin sesión
     * ([GoogleAuthState.SignedOut]) y exige volver a iniciar sesión con
     * Google (ver el gate de login en `App.kt`).
     *
     * Limpia también el `fcmToken` del perfil global de la cuenta que cierra
     * sesión (best-effort, en segundo plano) — sin esto, en un dispositivo
     * familiar compartido el token de push quedaba asociado indefinidamente
     * a la cuenta anterior tras cerrar sesión localmente (panel de revisión
     * 2026-09-03/04, Experto 10, NUEVO).
     *
     * También invalida la caché de "miembro actual" de [MemberRepository]
     * (todos los hogares) — sin esto, en un dispositivo familiar compartido,
     * tras cerrar sesión de un perfil e iniciar con otro, el `memberId`
     * cacheado del perfil anterior podía seguir resolviéndose para el mismo
     * hogar, atribuyendo compleciones/puntos al miembro equivocado (panel de
     * revisión 2026-09-04, Experto 9/10, NUEVO).
     *
     * Limpia también [HouseholdStore] (lista de hogares guardados + espacio
     * Personal) — sin esto, en un dispositivo familiar compartido, cerrar
     * sesión de la cuenta A e iniciar sesión con la cuenta B dejaba los
     * hogares de A en el `HouseholdStore` local; como
     * [restoreFromCloudOnStartup]/[handleGoogleToken] son ADITIVOS (unión,
     * nunca podan lo que no reconocen) y [reconcileHouseholds] solo comprueba
     * que el documento `households/{hid}` siga existiendo (su regla es
     * `allow get: if signedIn()`, sin exigir membresía — ver
     * `firestore.rules`), esos hogares de A nunca se podaban: quedaban
     * visibles (nombre + código de invitación) en la sesión de B y, peor,
     * [syncHouseholdsToCloud] los subía a `users/{uidB}.householdIds`,
     * filtrando la fuga a TODOS los dispositivos de la cuenta B (panel
     * arquitectura 2026-09-12, CRÍTICO — no cubierto por la limpieza de
     * `currentMemberCache`/`fcmToken`/sondeo de notificaciones de arriba,
     * que ya contemplaba el mismo escenario de dispositivo compartido pero
     * no tocaba `HouseholdStore`).
     */
    fun signOut() {
        val uidBeingSignedOut = settingsStore.getGoogleUid()
        settingsStore.clearGoogleAuth()
        // Sin esto, en un dispositivo familiar compartido el marcador de
        // sondeo de notificaciones (NotificationPollWorker) de la cuenta
        // saliente podría ocultar/filtrar datos frente a la cuenta entrante
        // (panel de notificaciones 2026-09-05, QA — mismo riesgo ya cubierto
        // para currentMemberCache y fcmToken en esta misma función).
        settingsStore.clearNotificationPollState()
        // Ver KDoc de esta función: sin esto, los hogares de la cuenta
        // saliente se filtraban a la cuenta entrante en un dispositivo
        // compartido.
        householdStore.clearAll()
        _state.value = GoogleAuthState.SignedOut
        scope.launch {
            try {
                repo.invalidateAllCurrentMembers()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("GoogleAuthManager", "signOut: invalidateAllCurrentMembers failed", e)
                // No crítico: la caché se autocorrige en la siguiente resolución.
            }
        }
        if (uidBeingSignedOut != null) {
            scope.launch {
                try {
                    repo.clearFcmToken(uidBeingSignedOut)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("GoogleAuthManager", "signOut: clearFcmToken failed", e)
                    // No crítico: el token se sobrescribirá en el próximo login de esa cuenta.
                }
            }
        }
    }

    /**
     * Elimina la cuenta de Google del usuario actual y TODOS sus datos — ver
     * hallazgo de privacidad "sin flujo de eliminar cuenta"
     * (docs/review-panel-expertos-v3-2026-09-01.md, Experto 10 #3).
     *
     * Orden:
     *  1. Su espacio Personal (isPersonal=true) → borrado completo en cascada
     *     ([FirestoreRepository.deleteHousehold]), es solo suyo.
     *  2. Cualquier otro hogar donde sea miembro → se le da de baja igual que
     *     si lo abandonara ([FirestoreRepository.leaveHousehold]): borra SU
     *     propio miembro (y el hogar entero si quedaba vacío), pero conserva
     *     el contenido compartido de otros miembros — borrar por completo un
     *     hogar familiar entero porque el dueño elimina su cuenta destruiría
     *     datos que no son (solo) suyos.
     *
     *     La lista de hogares se lee de FIRESTORE ([FirestoreRepository.loadUserHouseholds]
     *     + el ID determinista del espacio Personal), NO de [householdStore]
     *     (caché local): si la sync de `syncHouseholdsToCloud` nunca llegó a
     *     completarse en este dispositivo, la caché local podía faltar hogares
     *     donde el usuario seguía siendo miembro en Firestore, dejando su UID
     *     huérfano en `taskHistory`/`chat` tras borrar la cuenta. Requiere red:
     *     si el dispositivo está offline, se trata como fallo de cascada (ver
     *     más abajo) en vez de asumir "sin hogares".
     *
     *     Si CUALQUIER hogar de este bucle falla (offline, cascade parcial —
     *     ver [org.taskhub.network.HouseholdCascadeIncompleteException] —
     *     etc.), el fallo se ACUMULA y el resto de pasos (perfil global,
     *     borrado de la cuenta Auth) NO se ejecutan: se devuelve
     *     [AccountDeletionCascadeException] para que el usuario conserve la
     *     sesión con la que reintentarlo, en vez de perder la cuenta Auth
     *     (paso irreversible) con hogares a medio borrar sin ninguna forma
     *     de completarlo (panel v4, Experto 12 hallazgo #2).
     *  3. Su perfil global (`users/{uid}`) — best-effort, no bloquea el resto.
     *  4. Revoca el consentimiento OAuth de Google Calendar (best-effort, ver
     *     [revokeGoogleCalendarAccess]) — panel v4, Experto 10 hallazgo #5.
     *  5. La cuenta de Firebase Auth en sí — el paso irreversible final; si
     *     este falla SÍ se reporta como error (la cuenta sigue activa).
     * Termina limpiando todo el estado local (tokens, hogares guardados) y
     * dejando la app en [GoogleAuthState.SignedOut]: sin modo anónimo de
     * respaldo, el usuario debe volver a iniciar sesión con Google (gate de
     * login en `App.kt`) para crear una cuenta nueva.
     */
    suspend fun deleteAccount(): Result<Unit> {
        val myId = currentUserId()
        if (myId != null && !repo.isOnline()) {
            // Sin red no se puede leer la lista autoritativa de hogares desde
            // Firestore — tratarlo como "sin hogares" borraría la cuenta Auth
            // dejando el UID huérfano en hogares reales que no se pudieron
            // consultar. Igual que un fallo de cascada: el usuario conserva la
            // sesión para reintentarlo con conexión.
            return Result.failure(AccountDeletionCascadeException())
        }
        val households = myId?.let { uid ->
            val ids = (repo.loadUserHouseholds(uid) + repo.personalHouseholdId(uid)).distinct()
            repo.getHouseholds(ids)
        } ?: emptyList()
        var hadCascadeFailure = false
        for (h in households) {
            try {
                if (h.isPersonal) {
                    repo.deleteHousehold(h.id)
                } else {
                    repo.leaveHousehold(h.id, myId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("GoogleAuthManager", "deleteAccount: household cascade delete/leave failed for ${h.id}", e)
                hadCascadeFailure = true
            }
        }
        if (hadCascadeFailure) {
            return Result.failure(AccountDeletionCascadeException())
        }
        if (myId != null) {
            try {
                repo.deleteUserProfile(myId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("GoogleAuthManager", "deleteAccount: deleteUserProfile failed", e)
                // No crítico: el perfil global huérfano no es un dato con
                // identidad reclamable sin la cuenta que acabamos de borrar.
            }
        }
        return try {
            repo.deleteFirebaseAccount()
            if (settingsStore.hasGoogleLinked()) {
                try {
                    revokeGoogleCalendarAccess()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("GoogleAuthManager", "deleteAccount: revokeGoogleCalendarAccess failed", e)
                    // No crítico: el access token en sí caduca solo en ~1h.
                }
            }
            householdStore.clearAll()
            settingsStore.unlinkGoogleCalendar()
            settingsStore.clearGoogleAuth()
            _state.value = GoogleAuthState.SignedOut
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("GoogleAuthManager", "deleteAccount: final deleteFirebaseAccount step failed", e)
            Result.failure(e)
        }
    }

    /**
     * Fuerza una reautenticación reciente con Google justo antes de eliminar
     * la cuenta (acción irreversible) — sin esto, una sesión de Firebase ya
     * iniciada (posiblemente horas o días atrás) bastaba para borrar la
     * cuenta desde un dispositivo desatendido/robado (panel v4, Experto 9).
     * Task Hub es Google-only, así que si no hay sesión iniciada (estado
     * distinto de [GoogleAuthState.SignedIn]) no hay nada que reautenticar —
     * devuelve true directamente (no debería poder llegar a Ajustes sin
     * sesión, ver el gate de login en `App.kt`).
     *
     * Reutiliza [signIn] (mismo patrón que [linkCalendar]) para no duplicar
     * el manejo de [GoogleSignInResultHolder]/reentrancia: si el usuario
     * cancela el selector de cuenta, el estado vuelve a [GoogleAuthState.SignedOut]
     * (comportamiento ya existente y aceptado en [linkCalendar] ante una
     * cancelación) y esta función devuelve false.
     */
    suspend fun reauthenticateForDeletion(): Boolean {
        if (_state.value !is GoogleAuthState.SignedIn) return true
        signIn()
        val result = state.first {
            it is GoogleAuthState.SignedIn || it is GoogleAuthState.SignedOut || it is GoogleAuthState.Error
        }
        return result is GoogleAuthState.SignedIn
    }

    /**
     * Obtiene bajo demanda un access token OAuth con scope de Calendar (efímero,
     * ~1h) y actualiza el flag "vinculado" de [SettingsStore] según el resultado:
     * éxito → vinculado; fallo (sin cuenta, consentimiento denegado, revocado) →
     * desvinculado. No se persiste como si fuera de larga duración — se vuelve a
     * pedir cada vez que hace falta, y la capa de plataforma cachea/refresca la
     * cuenta subyacente de forma transparente.
     */
    suspend fun ensureCalendarAccessToken(): String? {
        val token = getGoogleCalendarAccessToken()
        settingsStore.setGoogleAccessToken(token)
        return token
    }

    /**
     * Flujo completo de "vincular cuenta de Google Calendar" desde ajustes o
     * desde el detalle de una tarea: si no hay sesión de Google, la inicia
     * primero (abre el selector de cuenta y espera el resultado) y, una vez
     * autenticado, pide el token de Calendar. Devuelve true si quedó vinculado.
     */
    suspend fun linkCalendar(): Boolean {
        if (_state.value !is GoogleAuthState.SignedIn) {
            signIn()
            val result = state.first {
                it is GoogleAuthState.SignedIn || it is GoogleAuthState.SignedOut || it is GoogleAuthState.Error
            }
            if (result !is GoogleAuthState.SignedIn) return false
        }
        return ensureCalendarAccessToken() != null
    }

    /**
     * ID estable del usuario actual (UID de Google), o `null` si no hay
     * sesión iniciada. Sirve para identificar los miembros creados por este
     * usuario en un hogar (member.userId).
     */
    fun currentUserId(): String? = settingsStore.getGoogleUid() ?: repo.getLocalId()

    /** Intercambia el idToken de Google por credenciales de Firebase y restaura datos. */
    private suspend fun handleGoogleToken(googleIdToken: String) {
        try {
            val result = repo.signInWithGoogle(googleIdToken)
            settingsStore.setGoogleAuth(result.uid, result.email)
            restoreHouseholds(result.uid)
            repointPersonalHousehold()
            syncHouseholdsToCloud()
            syncGoogleAvatar(result)
            // Login correcto: resetea el sentinel de reintentos (ver [signIn]).
            consecutiveSignInFailures = 0
            _state.value = GoogleAuthState.SignedIn(result.email)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("GoogleAuthManager", "handleGoogleToken: sign-in failed", e)
            consecutiveSignInFailures++
            // Nunca `e.message` crudo (panel de expertos v10, UX):
            // `repo.signInWithGoogle` puede lanzar un `FirestoreException` con
            // el `error.message` crudo de Identity Toolkit (p.ej.
            // "INVALID_IDP_RESPONSE : ...", "TOO_MANY_ATTEMPTS_TRY_LATER") o una
            // excepción de transporte ya saneada de la apiKey pero con texto de
            // Ktor sin traducir — `AuthGateScreen` es el ÚNICO punto de entrada
            // de la app (Google-only, ver `docs/google-only-auth-2026-09-12.md`)
            // y muestra `authState.message` tal cual, sin pasar por ninguna
            // otra capa de traducción de errores (a diferencia del patrón
            // `appreciateErrorKey`/`donateErrorKey` de `MemberScreenModel.kt`).
            // Sí se clasifica por categoría (sin conexión / servidor / resto)
            // vía [toUserMessage] — antes mostraba siempre el mismo mensaje
            // genérico sin distinguir "no hay internet" de un fallo real de
            // Identity Toolkit (`docs/mensajes-error-usuario-2026-09-16.md`).
            _state.value = GoogleAuthState.Error(
                e.toUserMessage(settingsStore.getLanguage(), "google_auth_error_sign_in")
            )
        }
    }

    /**
     * Guarda la foto de perfil de Google como avatarUrl del perfil global, solo
     * si el usuario todavía no tiene una foto propia (para no pisar una subida
     * a mano en EditProfileScreen con la foto de Google en cada login).
     */
    private suspend fun syncGoogleAvatar(result: FirestoreRepository.GoogleSignInResult) {
        val photoUrl = result.photoUrl ?: return
        try {
            val existing = repo.getUserProfile(result.uid)
            if (!existing?.avatarUrl.isNullOrBlank()) return
            repo.upsertUserProfile(
                userId = result.uid,
                displayName = existing?.displayName?.ifBlank { result.displayName ?: "" }
                    ?: (result.displayName ?: ""),
                avatarUrl = photoUrl,
                avatarEmoji = existing?.avatarEmoji ?: "",
                bio = existing?.bio ?: "",
                status = existing?.status ?: ""
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("GoogleAuthManager", "syncGoogleAvatar failed", e)
            // No crítico: se reintenta en el próximo login.
        }
    }

    /**
     * Restaura los hogares vinculados a la cuenta de Google desde Firestore.
     *
     * Usa [FirestoreRepository.getHouseholds] (batch en paralelo con
     * `async`/`awaitAll`, ya existente en [org.taskhub.network.HouseholdRepository]
     * para el mismo propósito en `reconcileHouseholds`) en vez de un `for` con
     * un `getHousehold(id)` secuencial por hogar — con una cuenta con varios
     * hogares compartidos, la versión secuencial encadenaba N round-trips de
     * red uno detrás de otro en el arranque en frío; en paralelo son
     * concurrentes (panel de expertos v10, rendimiento, IMPORTANTE). Hogares
     * obsoletos/eliminados ya se descartan dentro de `getHouseholds` (ignora
     * el fallo y omite ese ID), mismo comportamiento que el `catch` que
     * sustituye.
     */
    private suspend fun restoreHouseholds(uid: String) {
        val ids = repo.loadUserHouseholds(uid)
        val households = repo.getHouseholds(ids)
        for (household in households) {
            householdStore.saveHousehold(
                householdId = household.id,
                householdName = household.name,
                inviteCode = household.inviteCode,
                isPersonal = household.isPersonal,
                spaceType = household.spaceType
            )
        }
    }

    /**
     * Restaura los hogares compartidos desde la nube (users/{uid}). Debe
     * llamarse al arrancar la app si hay sesión de Google persistida, para
     * que los hogares creados/unidos en OTRO dispositivo con la misma cuenta
     * aparezcan sin re-loguearse (antes `restoreHouseholds` solo se ejecutaba
     * en el login explícito).
     *
     * A propósito NO re-apunta aquí el espacio Personal (a diferencia de
     * [handleGoogleToken], que sí llama a [repointPersonalHousehold] tras un
     * login explícito): el único llamante de esta función, el bootstrap de
     * `App.kt`, ya resuelve/crea el espacio Personal por su cuenta justo
     * ANTES de invocar [restoreFromCloudOnStartup] (con manejo adicional de
     * fallback offline que [repointPersonalHousehold] no tiene). Repetirlo
     * aquí era un segundo `GET households/{personal_uid}` a Firestore
     * idéntico y descartado en cada arranque en frío — panel de expertos v10,
     * rendimiento, CRÍTICO: doblaba la latencia de red de esa parte del
     * arranque sin ningún efecto observable.
     *
     * Es aditivo (unión) y tolerante a fallos: no lanza excepciones. Es `suspend`
     * para que App.kt pueda esperarla ANTES de mostrar HomeScreen (que lee la
     * lista de hogares una sola vez y no reacciona a cambios).
     */
    suspend fun restoreFromCloudOnStartup() {
        val uid = settingsStore.getGoogleUid() ?: return
        try {
            restoreHouseholds(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("GoogleAuthManager", "restoreFromCloudOnStartup failed", e)
            // No crítico: se reintenta en el próximo arranque/login.
        }
    }

    /**
     * Re-apunta el espacio Personal a la identidad de Google para que sea
     * interdispositivo: con el UID estable de Google, todos los dispositivos
     * resuelven el mismo documento `personal_{uid}` (vía
     * [FirestoreRepository.getOrCreatePersonalHousehold]).
     */
    private suspend fun repointPersonalHousehold() {
        try {
            val personal = repo.getOrCreatePersonalHousehold()
            householdStore.replacePersonalHousehold(personal.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("GoogleAuthManager", "repointPersonalHousehold failed", e)
            // Offline/transitorio: App.kt lo reintenta en el próximo arranque.
        }
    }

    // Job en vuelo de syncHouseholdsToCloud() — ver su KDoc (punto B12).
    private var syncHouseholdsJob: Job? = null

    /**
     * Sincroniza los hogares compartidos del usuario con Firestore (users/{uid}).
     * Solo se sincroniza si hay sesión de Google iniciada. Ignora el espacio
     * Personal: no se guarda aquí, se resuelve de forma determinista
     * (personal_{uid}) desde [FirestoreRepository.getOrCreatePersonalHousehold].
     *
     * Cancela la sincronización anterior en curso antes de lanzar esta (ronda
     * de deuda aplicable 2026-09-12, punto B12; mismo patrón que
     * `TaskScreenModel.loadTasksJob`) — se llama desde 4 sitios de
     * `HouseholdScreenModel` (crear/eliminar/abandonar hogar, cambiar rol de
     * miembro) que pueden dispararse casi seguidos; sin serializar, dos
     * llamadas fire-and-forget podían resolverse fuera de orden y la
     * respuesta más antigua (con una lista de IDs ya obsoleta) sobrescribir
     * en `users/{uid}` el resultado de la más reciente.
     */
    fun syncHouseholdsToCloud() {
        val uid = settingsStore.getGoogleUid() ?: return
        val ids = householdStore.getSavedHouseholds()
            .filter { !it.isPersonal }
            .map { it.id }
        syncHouseholdsJob?.cancel()
        syncHouseholdsJob = scope.launch {
            try {
                repo.saveUserHouseholds(uid, ids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("GoogleAuthManager", "syncHouseholdsToCloud failed", e)
                // No crítico — se reintenta en el próximo cambio
            }
        }
    }

    /**
     * Cancela el `CoroutineScope` interno (observador de
     * [GoogleSignInResultHolder] + corrutinas de [syncHouseholdsToCloud] en
     * vuelo). No implementa `java.io.Closeable` porque no existe en
     * `commonMain` de Kotlin Multiplatform (es JVM-only) — introducirlo
     * forzaría una dependencia no disponible en iOS. En su lugar, Koin
     * invoca este método vía `onClose` al cerrar el contenedor de DI (ver
     * `AppModule.kt`). Impacto práctico hoy: nulo — la app nunca llama a
     * `koin.close()` (este manager vive tanto como el proceso), pero deja
     * el manager correctamente cerrable si eso cambia en el futuro (p.ej.
     * tests instrumentados que recrean el contenedor de Koin entre casos).
     */
    fun close() {
        scope.cancel()
    }
}
