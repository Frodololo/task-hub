// Fachada de acceso a Firestore vía REST (ver `docs/refactor-arquitectura-2026-08-31.md`):
// delega la mayor parte del CRUD por dominio en los repos de
// `HouseholdRepository`/`MemberRepository`/`TaskRepository`/`NotificationRepository`/
// `RewardsRepository`, y conserva aquí solo la orquestación que toca más de un
// dominio a la vez (p.ej. completar una tarea otorga puntos a un miembro).
package org.taskhub.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
// NOTA: FormDataContent/Parameters (refresh de token) y errorParsingJson/HttpClient
// ahora viven en FirestoreClient (ver fase 1 del refactor, docs/refactor-arquitectura-2026-08-31.md).
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import org.taskhub.network.models.HouseholdResponse
import org.taskhub.network.models.MemberResponse
import org.taskhub.network.models.UserProfile
import org.taskhub.network.models.TaskHistoryResponse
import org.taskhub.network.models.TaskResponse
import org.taskhub.network.models.TaskAssignmentResponse
import org.taskhub.network.models.CompleteRecurringTaskRequest
import org.taskhub.network.models.CompleteAssignmentRequest
import org.taskhub.network.models.ReassignTaskCompletionRequest
import org.taskhub.network.models.ReassignTaskCompletionResult
import org.taskhub.network.models.UndoTaskCompletionRequest
import org.taskhub.network.models.UndoTaskCompletionResult
import org.taskhub.network.models.TaskCompletionFunctionResult
import org.taskhub.network.models.NotificationResponse
import org.taskhub.network.models.RewardResponse
import org.taskhub.network.models.RewardRedemption
import org.taskhub.network.models.Subtask
import org.taskhub.storage.HouseholdStore
import org.taskhub.storage.SavedHousehold
import org.taskhub.storage.SettingsStore
import org.taskhub.storage.TaskCache
import org.taskhub.platform.secureRandomInt
import org.taskhub.ui.i18n.AppStrings

/**
 * Límite de peticiones DELETE concurrentes al borrar en cascada colecciones
 * potencialmente grandes (`taskHistory`, asignaciones/comentarios por tarea)
 * — ver [FirestoreRepository.deleteAllDocuments]/[FirestoreRepository.deleteHousehold]
 * (panel v7, #20).
 */
private const val MAX_CONCURRENT_DELETES = 20

/**
 * TTL de retención compartido por las purgas best-effort de colecciones sin
 * techo natural de crecimiento (`taskHistory`, mensajes de chat,
 * notificaciones ya leídas) — ver [FirestoreRepository.purgeOldTaskHistory]/
 * [FirestoreRepository.purgeOldMessages]/[FirestoreRepository.purgeOldNotifications]
 * (ronda de deuda aplicable 2026-09-12, punto B9, mismo tope para las tres).
 */
const val RETENTION_90_DAYS_MILLIS = 90L * 24 * 60 * 60 * 1000

/**
 * Talks directly to Firestore REST API — no Ktor server needed.
 *
 * Firestore REST API docs:
 *   https://firebase.google.com/docs/firestore/reference/rest
 *
 * Uses Firebase Auth (Google Sign-In only, ver `docs/google-only-auth-2026-09-12.md`)
 * for write access. The API key alone only allows reads — writes require a
 * Bearer token.
 *
 * Construcción de rutas: todas las URLs son concatenaciones directas sobre
 * [baseUrl] (= `firestoreBaseUrl(projectId)`, el endpoint REST de la base de
 * datos) siguiendo la jerarquía real de Firestore, p.ej.
 * `$baseUrl/households/{id}` para un hogar y
 * `$baseUrl/households/{id}/tasks/{taskId}/assignments/{assignmentId}` para
 * una asignación — no hay una capa de "query builder" intermedia. Los repos
 * de dominio inyectados ([taskRepository], [householdRepository]...) reciben
 * ese mismo [baseUrl] ya resuelto y construyen sus propias rutas con el mismo
 * patrón.
 */
open class FirestoreRepository(
    private val projectId: String = DEFAULT_FIRESTORE_PROJECT_ID,
    private val apiKey: String = DEFAULT_API_KEY,
    private val taskCache: TaskCache,
    private val settingsStore: SettingsStore,
    private val firestoreClient: FirestoreClient = FirestoreClient(apiKey, settingsStore),
    // Ver `docs/recurrencia-backend-cloud-functions-diseno-2026-09-11.md` —
    // completar/deshacer/reasignar delegan en las Cloud Functions de
    // `functions/` en vez de orquestar HTTP secuencial contra Firestore.
    private val cloudFunctionsClient: CloudFunctionsClient = CloudFunctionsClient(firestoreClient.client, firestoreClient),
    // ── Repos de dominio — recibidos por inyección (registrados como
    // `single` de Koin, ver AppModule.kt) en vez de construidos a mano aquí
    // dentro. Antes eran campos privados armados en el cuerpo de la clase, lo
    // que forzaba a que TODA orquestación cross-dominio (completeTask,
    // deleteHousehold, redeemReward...) viviera en esta fachada porque no
    // había forma de que un repo de dominio dependiera de otro sin pasar por
    // aquí — causa raíz diagnosticada en el panel de revisión 2026-09-03/04,
    // Experto 7 (panel v7, #16). Los valores por defecto solo cubren el caso
    // de construir esta clase fuera de Koin (tests, previews).
    private val notificationRepository: NotificationRepository = NotificationRepository(firestoreBaseUrl(projectId), firestoreClient, taskCache),
    private val rewardsRepository: RewardsRepository = RewardsRepository(firestoreBaseUrl(projectId), firestoreClient, taskCache),
    private val taskRepository: TaskRepository = TaskRepository(firestoreBaseUrl(projectId), firestoreClient, taskCache, notificationRepository, settingsStore),
    // MemberRepository propio (no el parámetro de abajo): un default solo se
    // puede referenciar a sí mismo o a parámetros ANTERIORES en la lista, y
    // `memberRepository` se declara después — esta ruta solo se usa fuera de
    // Koin (tests/previews), así que una segunda instancia aquí es inocua.
    private val householdRepository: HouseholdRepository = HouseholdRepository(
        firestoreBaseUrl(projectId), firestoreClient, taskCache,
        MemberRepository(firestoreBaseUrl(projectId), firestoreClient, taskCache),
        notificationRepository, settingsStore
    ),
    private val memberRepository: MemberRepository = MemberRepository(firestoreBaseUrl(projectId), firestoreClient, taskCache)
) {
    private val baseUrl = firestoreBaseUrl(projectId)

    // ── Cliente HTTP + auth de bajo nivel (ver FirestoreClient, fase 1 del
    // refactor). `client` se mantiene como alias local para no tocar los ~60
    // call-sites internos (`client.get/post/patch/delete`) de este archivo.
    private val client = firestoreClient.client

    // ────────────────────────────────────────────────────────
    //  Auth
    // ────────────────────────────────────────────────────────

    /**
     * Ver [FirestoreClient.ensureAuth] — delegado tal cual (wrapper para no
     * tocar los call-sites internos `ensureAuth()` de este archivo).
     */
    private suspend fun ensureAuth() = firestoreClient.ensureAuth()

    /** Ver [FirestoreClient.getLocalId]. */
    fun getLocalId(): String? = firestoreClient.getLocalId()

    /**
     * True si el usuario actual es el owner del hogar (comparación de
     * [getLocalId] con `household.ownerId`) — igual que `isTrusted(hid)` en
     * `firestore.rules`. Se usaba duplicado en 3 sitios (`HouseholdScreen`,
     * `TaskDetailScreen`, `RewardListScreen`), cada uno inyectando
     * `FirestoreRepository` directamente para repetir la misma comparación.
     * false ante cualquier fallo de red (best-effort, igual que el código que
     * sustituye).
     */
    open suspend fun isHouseholdOwner(householdId: String): Boolean {
        val localId = getLocalId() ?: return false
        val household = try {
            getHousehold(householdId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        return localId == household.ownerId
    }

    /** Ver [FirestoreClient.currentUserIdentities]. */
    fun currentUserIdentities(): List<String> = firestoreClient.currentUserIdentities()

    /**
     * Inicia sesión con Google: intercambia un Google idToken por un token de
     * Firebase Auth vía accounts:signInWithIdp. Devuelve el UID estable de Google
     * (localId) + email/displayName, que persisten entre reinstalaciones.
     *
     * Único mecanismo de autenticación de Task Hub (Google-only, ver
     * `docs/google-only-auth-2026-09-12.md`): no hay sesión anónima previa que
     * vincular, así que siempre es un login normal.
     */
    suspend fun signInWithGoogle(googleIdToken: String): GoogleSignInResult {
        val response: FirebaseAuthResponse = requestSignInWithIdp(googleIdToken)

        val idToken = response.idToken
        val localId = response.localId
        val expiresIn = response.expiresIn?.toLongOrNull()
        if (idToken.isNullOrBlank() || localId.isNullOrBlank() || expiresIn == null) {
            throw IllegalStateException(
                "Google sign-in falló: respuesta de Firebase Auth incompleta. " +
                "Verifica que el proveedor Google esté habilitado en Firebase Auth."
            )
        }

        val now = Clock.System.now().toEpochMilliseconds()
        firestoreClient.setAuthState(idToken, localId, now + (expiresIn * 1000) - 300_000)

        // Persistir la sesión de Google para restaurarla en próximos arranques
        // (el refresh token permite renovar el idToken sin re-login).
        settingsStore.setGoogleRefreshToken(response.refreshToken)

        return GoogleSignInResult(
            uid = localId,
            email = response.email,
            displayName = response.displayName,
            photoUrl = response.photoUrl
        )
    }

    /**
     * POST a `accounts:signInWithIdp`. Ver [signInWithGoogle].
     *
     * Envuelve el fallo con [FirestoreClient.redactApiKey] igual que
     * [FirestoreClient.refreshFirebaseToken]/[FirestoreClient.deleteFirebaseAccount]:
     * Ktor mete la URL completa (con `?key=$apiKey`) en el mensaje de las
     * excepciones de transporte (timeout/sin conexión), y ese mensaje llega
     * sin filtrar a [GoogleAuthState.Error] → `AuthGateScreen` (el ÚNICO
     * punto de entrada de la app, ver `docs/google-only-auth-2026-09-12.md`)
     * — este endpoint compartía el mismo patrón que los otros dos pero se
     * había quedado sin cubrir por la redacción introducida en el panel de
     * seguridad 2026-09-11 (panel de expertos, seguridad, 2026-09-12).
     */
    private suspend fun requestSignInWithIdp(googleIdToken: String): FirebaseAuthResponse =
        try {
            client.post("https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(
                    SignInWithIdpRequest(
                        postBody = "id_token=$googleIdToken&providerId=google.com",
                        requestUri = "http://localhost",
                        returnSecureToken = true
                    )
                )
            }.body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw firestoreClient.redactApiKey(e)
        }

    /** Resultado del login con Google. */
    data class GoogleSignInResult(
        val uid: String,
        val email: String? = null,
        val displayName: String? = null,
        /** Foto de perfil de la cuenta de Google, si Firebase Auth la expone. */
        val photoUrl: String? = null
    )

    /**
     * Persiste en Firestore la lista de IDs de hogares a los que pertenece el
     * usuario (documento users/{uid}). Permite restaurar los hogares tras una
     * reinstalación cuando el usuario vuelve a iniciar sesión con Google.
     */
    suspend fun saveUserHouseholds(uid: String, householdIds: List<String>) {
        val fields = mapOf(
            "householdIds" to FirestoreValue(
                arrayValue = FirestoreArrayValue(
                    values = householdIds.map { FirestoreValue(stringValue = it) }
                )
            ),
            "updatedAt" to FirestoreValue(integerValue = Clock.System.now().toEpochMilliseconds().toString())
        )
        client.patch("$baseUrl/users/$uid") {
            withAuth()
            updateMaskFieldPaths("householdIds", "updatedAt")
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }
    }

    /**
     * Sube el token de notificaciones push (FCM) del dispositivo actual al
     * perfil global del usuario (users/{uid}.fcmToken), para que un backend/
     * Cloud Function pueda dirigirle un push (p.ej. "tarea asignada"). Se
     * limita a los campos fcmToken/fcmTokenUpdatedAt vía updateMask para no
     * pisar otros campos del perfil (displayName, avatar, etc.).
     */
    suspend fun saveFcmToken(uid: String, token: String) {
        val fields = mapOf(
            "fcmToken" to FirestoreValue(stringValue = token),
            "fcmTokenUpdatedAt" to FirestoreValue(integerValue = Clock.System.now().toEpochMilliseconds().toString())
        )
        client.patch("$baseUrl/users/$uid") {
            withAuth()
            updateMaskFieldPaths("fcmToken", "fcmTokenUpdatedAt")
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }
    }

    /**
     * Limpia el token de notificaciones push (FCM) del perfil global de un
     * usuario — llamado al cerrar sesión localmente ([GoogleAuthManager.signOut]).
     * Sin esto, en un dispositivo familiar compartido el token de push queda
     * asociado indefinidamente a la cuenta anterior tras cerrar sesión (panel
     * de revisión 2026-09-03/04, Experto 10, NUEVO — sin impacto real hoy: no
     * existe todavía infraestructura de Cloud Functions que lo consuma).
     */
    suspend fun clearFcmToken(uid: String) {
        val fields = mapOf(
            "fcmToken" to FirestoreValue(nullValue = "NULL_VALUE"),
            "fcmTokenUpdatedAt" to FirestoreValue(integerValue = Clock.System.now().toEpochMilliseconds().toString())
        )
        client.patch("$baseUrl/users/$uid") {
            withAuth()
            updateMaskFieldPaths("fcmToken", "fcmTokenUpdatedAt")
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }
    }

    /**
     * Recupera de Firestore los IDs de hogares guardados para el usuario.
     * Devuelve lista vacía si no existe el documento users/{uid}.
     *
     * SIN caché de respaldo propia (punto B11, evaluado y descartado): el
     * único caller es [org.taskhub.ui.models.GoogleAuthManager.syncHouseholdsToCloud]
     * — un mecanismo de DESCUBRIMIENTO de hogares añadidos desde OTRO
     * dispositivo, no la lista local del dispositivo actual (esa ya vive,
     * persistida y con su propia resiliencia offline, en
     * [org.taskhub.storage.HouseholdStore]). Un fallo aquí solo significa
     * "no se descubrieron hogares nuevos de otro dispositivo en ESTE
     * arranque" — autocorregible en el siguiente sync exitoso; cachear esta
     * lista duplicaría la responsabilidad de `HouseholdStore` sin un
     * escenario real que lo justifique.
     */
    suspend fun loadUserHouseholds(uid: String): List<String> = orDefault(emptyList()) {
        val response: FirestoreDocumentResponse = client.getWithRetry("$baseUrl/users/$uid") {
            withAuth()
        }.body()
        response.fields["householdIds"]?.arrayValue?.values
            ?.mapNotNull { it.stringValue }
            ?: emptyList()
    }

    /**
     * Sonda de alcanzabilidad: pide un documento que se sabe que NO existe
     * (`households/__ping__`) solo para comprobar si el dispositivo llega a
     * Firestore, sin depender de tener un hogar real ni de estar
     * autenticado (usa la API key, no auth).
     *
     * El resultado esperado de esa petición es un 404 ("documento no
     * encontrado") — o un 403 si las reglas de seguridad lo bloquean antes de
     * comprobar existencia — y ambos cuentan como "hay red": Firestore tuvo
     * que responder con un status HTTP real, lo que solo pasa si la conexión
     * llegó al servidor. Por eso [FirestoreException] (cualquier respuesta
     * HTTP de Firestore, sea cual sea el código) se trata como éxito aquí, y
     * solo un fallo de TRANSPORTE (timeout, DNS, sin conexión — una excepción
     * que ni siquiera llega a convertirse en [FirestoreException]) se
     * interpreta como offline.
     *
     * A propósito SIN [retryTransientReadFailure] (a diferencia del resto de
     * lecturas, tarjeta kanban "Retry/backoff idempotente"): el fallo de
     * transporte que dispara este catch ES la señal que la sonda busca
     * detectar, no un contratiempo que enmascarar — reintentar con backoff
     * aquí solo retrasaría varios segundos el aviso de "sin conexión" en la
     * UI ([org.taskhub.ui.models.TaskScreenModel] lo usa para `_isOffline`).
     */
    open suspend fun isOnline(): Boolean {
        return try {
            client.get("$baseUrl/households/__ping__") {
                parameter("key", apiKey)
            }
            true
        } catch (e: FirestoreException) {
            // Firestore respondió con un status HTTP (aunque sea 404/403): hay red.
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fallo de transporte (sin red): no se llegó a Firestore.
            false
        }
    }

    /**
     * Ver [FirestoreClient.withAuth]/[FirestoreClient.updateMaskFieldPaths] —
     * delegados tal cual (wrappers para no tocar los ~60 call-sites internos
     * de este archivo que ya invocan `withAuth()`/`updateMaskFieldPaths(...)`
     * a secas dentro de un bloque `client.get/post/patch/delete { ... }`).
     */
    private suspend fun HttpRequestBuilder.withAuth() = with(firestoreClient) { withAuth() }

    private fun HttpRequestBuilder.updateMaskFieldPaths(vararg fields: String) =
        with(firestoreClient) { updateMaskFieldPaths(*fields) }

    private fun HttpRequestBuilder.updateMaskFieldPaths(fields: Collection<String>) =
        with(firestoreClient) { updateMaskFieldPaths(fields) }

    // ────────────────────────────────────────────────────────
    //  Households
    // ────────────────────────────────────────────────────────

    /** Create a household (auto-generated doc ID). Requires auth (write). */
    suspend fun createHousehold(name: String, isPersonal: Boolean = false): HouseholdResponse =
        householdRepository.createHousehold(name, isPersonal)

    /**
     * Obtiene (o crea) el espacio Personal del usuario actual con un ID DETERMINISTA
     * derivado de su identidad estable: `personal_{uid}`, donde `uid` es el UID de
     * Google de la sesión iniciada.
     *
     * Esto hace el espacio Personal interdispositivo: con la misma cuenta de Google,
     * todos los dispositivos resuelven el MISMO documento `households/personal_{uid}`,
     * así que tareas/miembros/puntos se comparten automáticamente.
     */
    suspend fun getOrCreatePersonalHousehold(): HouseholdResponse = householdRepository.getOrCreatePersonalHousehold()

    /** ID determinista del espacio Personal para una identidad (UID) dada. */
    fun personalHouseholdId(uid: String): String = householdRepository.personalHouseholdId(uid)

    /**
     * Get a household by id. Falls back to local cache on network/5xx failures.
     *
     * A 404/403 from Firestore is a DEFINITIVE signal — the household was deleted
     * or we lost access to it — so it must NOT fall back to the stale cache (that
     * would keep showing a "ghost" household forever). Callers that need to prune
     * local state should catch [FirestoreException] and check [FirestoreException.statusCode].
     */
    open suspend fun getHousehold(id: String): HouseholdResponse = householdRepository.getHousehold(id)

    /**
     * Reconcilia los hogares guardados localmente en [store] contra Firestore
     * (fuente de verdad). Ver [HouseholdRepository.reconcileHouseholds] para el
     * detalle de la política de poda (movida tal cual, sin cambios).
     */
    suspend fun reconcileHouseholds(store: HouseholdStore): List<SavedHousehold> =
        householdRepository.reconcileHouseholds(store)

    /** Batch-fetch multiple households by their document IDs (en paralelo). */
    suspend fun getHouseholds(ids: List<String>): List<HouseholdResponse> = householdRepository.getHouseholds(ids)

    /**
     * Borra un hogar y TODOS sus datos asociados (borrado real, no soft-delete
     * — ver hallazgo de privacidad "el borrado no borra datos reales",
     * docs/review-panel-expertos-v3-2026-09-01.md Experto 10 #4). Recorre y
     * borra, hojas primero, las subcolecciones: tasks/{tid}/assignments,
     * tasks/{tid}/comments, tasks; members/{mid}/achievements, members;
     * taskHistory; notifications; rewardRedemptions; rewards; messages;
     * invites/{código} (colección de nivel superior, ver
     * [HouseholdRepository.createHousehold]) — y finalmente el propio
     * documento households/{householdId}.
     *
     * Best-effort por documento: un fallo puntual (404 ya borrado, timeout
     * transitorio) no aborta el resto — se prioriza dejar el hogar lo más
     * limpio posible en vez de abortar a medias. Pero si CUALQUIER documento
     * no se pudo borrar, el documento `households/{id}` en sí NO se borra —
     * lanza [HouseholdCascadeIncompleteException] en vez de dejar el hogar
     * "borrado" con datos huérfanos sin ninguna forma de completar el
     * cascade-delete después (panel v4, Experto 8 hallazgo #2). Requiere
     * auth (write).
     *
     * Las subcolecciones se borran en PARALELO (`coroutineScope`/`async`/
     * `awaitAll`), no una a una — mismo patrón que
     * [HouseholdRepository.reconcileHouseholds]. Para un hogar con
     * `taskHistory` real, el borrado secuencial eran miles de round-trips en
     * serie con la UI bloqueada (panel v4, Experto 7 #2 + Experto 11 #4).
     */
    suspend fun deleteHousehold(householdId: String) {
        val householdUrl = "$baseUrl/households/$householdId"
        var hadFailures = false

        // Borra el código de invitación ANTES que el propio hogar (ver abajo):
        // invites/{código} es una colección de nivel superior sin relación
        // padre-hijo con households/{id}, así que Firestore no la borra sola.
        // Sin este paso, un hogar borrado seguía siendo "unible" por código
        // (apuntando a un householdId ya inexistente) — hallazgo de privacidad
        // panel v4, Experto 10 #3 ALTO. Best-effort: si el hogar no tiene
        // inviteCode (dato ya corrupto) o falla la lectura, se continúa igual
        // con el resto del cascade-delete.
        try {
            val household = getHousehold(householdId)
            client.delete("$baseUrl/invites/${household.inviteCode}") { withAuth() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No crítico: ver KDoc de deleteHousehold (best-effort por documento).
        }

        val taskIds = listDocumentIds("$householdUrl/tasks")
        val memberIds = listDocumentIds("$householdUrl/members")

        // Un único Semaphore compartido por TODO el cascade-delete (no uno por
        // llamada a deleteAllDocuments) — antes cada llamada creaba el suyo
        // propio, así que el límite de MAX_CONCURRENT_DELETES no se respetaba
        // de verdad: con hasta 20 taskJobs en vuelo a la vez, cada uno con su
        // propio semáforo interno de 20, el pico real de DELETE concurrentes
        // podía llegar a ~400 en vez de 20 (panel de revisión 2026-09-04,
        // Experto 2). No se envuelve el `async` de cada tarea/miembro en su
        // propio permiso (como antes) porque compartir el mismo Semaphore en
        // dos niveles anidados (uno por tarea + uno por documento dentro de
        // esa tarea) puede autobloquearse: si las 20 tareas en vuelo agotan
        // los permisos del nivel exterior, sus llamadas anidadas a
        // deleteAllDocuments se quedan esperando permisos que nunca se
        // liberan porque los tenedores están, a su vez, esperando permisos
        // del mismo semáforo. Compartir el límite solo al nivel de la
        // petición HTTP individual (dentro de deleteAllDocuments) evita el
        // interbloqueo y sigue acotando el pico real de conexiones.
        val limiter = Semaphore(MAX_CONCURRENT_DELETES)
        val subcollectionResults = coroutineScope {
            val taskJobs = taskIds.map { tid ->
                async {
                    val okAssignments = deleteAllDocuments("$householdUrl/tasks/$tid/assignments", limiter = limiter)
                    val okComments = deleteAllDocuments("$householdUrl/tasks/$tid/comments", limiter = limiter)
                    okAssignments && okComments
                }
            }
            val memberJobs = memberIds.map { mid ->
                async { deleteAllDocuments("$householdUrl/members/$mid/achievements", limiter = limiter) }
            }
            val flatJobs = listOf(
                async { deleteAllDocuments("$householdUrl/taskHistory", limiter = limiter) },
                async { deleteAllDocuments("$householdUrl/notifications", limiter = limiter) },
                async { deleteAllDocuments("$householdUrl/rewardRedemptions", limiter = limiter) },
                async { deleteAllDocuments("$householdUrl/rewards", limiter = limiter) },
                async { deleteAllDocuments("$householdUrl/messages", limiter = limiter) }
            )
            (taskJobs + memberJobs + flatJobs).awaitAll()
        }
        if (subcollectionResults.any { !it }) hadFailures = true

        val parentCollectionResults = coroutineScope {
            listOf(
                async { deleteAllDocuments("$householdUrl/tasks", knownIds = taskIds, limiter = limiter) },
                async { deleteAllDocuments("$householdUrl/members", knownIds = memberIds, limiter = limiter) }
            ).awaitAll()
        }
        if (parentCollectionResults.any { !it }) hadFailures = true

        if (hadFailures) {
            throw HouseholdCascadeIncompleteException(householdId)
        }

        client.delete(householdUrl) {
            withAuth()
        }
        taskCache.clearHousehold(householdId)
        memberRepository.invalidateCurrentMember(householdId)
    }

    /** Invalida toda la caché de miembro actual (todos los hogares) — ver [MemberRepository.invalidateAllCurrentMembers]. */
    suspend fun invalidateAllCurrentMembers() = memberRepository.invalidateAllCurrentMembers()

    /**
     * Lista los IDs de todos los documentos de una colección — ver
     * [listAllDocuments] (compartido con `getTasks`/`getAssignments`/
     * `getMessages`) para el motivo de paginar con `pageToken` en vez de una
     * sola petición.
     */
    private suspend fun listDocumentIds(collectionUrl: String): List<String> =
        client.listAllDocuments(collectionUrl) { withAuth() }
            .map { extractDocId(it.name, "deleteHousehold") }

    /**
     * Borra todos los documentos de una colección PLANA (sin subcolecciones
     * propias sin borrar ya), en PARALELO (`coroutineScope`/`async`/
     * `awaitAll`) en vez de uno a uno. Cada borrado individual es
     * best-effort: un fallo (404, timeout) no aborta el resto de la
     * subcolección — pero SÍ se reporta en el valor de retorno para que
     * [deleteHousehold] sepa que el cascade quedó incompleto. Devuelve true
     * solo si TODOS los documentos se borraron.
     *
     * El fan-out se limita a [MAX_CONCURRENT_DELETES] peticiones simultáneas
     * (`Semaphore`) — antes lanzaba un `async` por documento sin límite, y
     * una colección `taskHistory` real de miles de documentos podía disparar
     * miles de conexiones HTTP concurrentes de golpe (panel v7, #20).
     *
     * [limiter] es compartido entre todas las llamadas de un mismo
     * `deleteHousehold` (ver esa función) para que el límite sea real a
     * nivel de cascade-delete completo, no por colección — el valor por
     * defecto (uno nuevo por llamada) solo aplica si se invoca de forma
     * aislada fuera de ese flujo.
     */
    private suspend fun deleteAllDocuments(
        collectionUrl: String,
        knownIds: List<String>? = null,
        limiter: Semaphore = Semaphore(MAX_CONCURRENT_DELETES)
    ): Boolean {
        val ids = knownIds ?: listDocumentIds(collectionUrl)
        if (ids.isEmpty()) return true
        return coroutineScope {
            ids.map { id ->
                async {
                    limiter.withPermit {
                        try {
                            client.delete("$collectionUrl/$id") { withAuth() }
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
            }.awaitAll().all { it }
        }
    }

    /**
     * Desvincula al usuario actual de un hogar: borra (DELETE real) los miembros
     * cuyo [currentUserId] coincide y, si no queda ningún miembro, elimina el
     * hogar completo de la base de datos. Si quien se va es el owner del hogar
     * y quedan otros miembros, transfiere el rol owner al miembro con cuenta
     * vinculada más antiguo restante (ver [HouseholdRules.resolveOwnerSuccessor])
     * para que el hogar no quede huérfano de owner (panel v4, Experto 2
     * hallazgo #6 ALTO). Usada tanto por "abandonar hogar" como por
     * `GoogleAuthManager.deleteAccount` (un hogar compartido por cada uno).
     *
     * Devuelve true si el hogar se eliminó por completo (no quedaban miembros).
     * Si [currentUserId] es null (sin auth), no borra miembros pero sí
     * comprueba si el hogar queda vacío.
     */
    suspend fun leaveHousehold(householdId: String, currentUserId: String?): Boolean {
        // Resolver TODAS las identidades del usuario, para que el miembro se
        // borre aunque se haya creado antes del login actual (ver
        // FirestoreClient.currentUserIdentities).
        val identities = (currentUserIdentities() + listOfNotNull(currentUserId)).distinct()

        val members = try {
            getMembers(householdId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val toDelete = members.filter { it.userId != null && it.userId in identities }

        // Si TODOS los miembros restantes del hogar son nuestros (vamos a
        // quedarnos con el hogar vacío), borramos el hogar ENTERO ya —
        // mientras todavía tenemos isMember(hid)/isOwner(hid) — en vez de
        // borrar primero nuestro propio documento de miembro y comprobar
        // después si quedó vacío. Con el orden anterior, en cuanto se borraba
        // el último documento de miembro perdíamos isMember() y el siguiente
        // intento de deleteHousehold fallaba con 403 a mitad del cascade,
        // dejando el hogar huérfano e imborrable para siempre (ver panel v4,
        // Experto 10 hallazgo #2 ALTO — deleteHousehold ya borra también
        // nuestro(s) propio(s) documento(s) de miembro dentro de su cascade,
        // así que no hace falta borrarlos aparte en este camino).
        if (members.isNotEmpty() && toDelete.size == members.size) {
            try {
                deleteHousehold(householdId)
            } catch (e: FirestoreException) {
                // Dos miembros abandonando casi a la vez pueden intentar borrar
                // el mismo hogar; si ya no existe (404), el objetivo -que el
                // hogar no exista- ya se cumplió, así que no es un fallo real.
                if (e.statusCode != 404) throw e
            }
            return true
        }

        // El hogar seguirá teniendo otros miembros: si quien se va es el
        // owner, transferir el rol al miembro con cuenta vinculada más
        // antiguo restante ANTES de borrar nuestro propio documento — en
        // cuanto el UID del owner deje de poder autenticarse (p.ej. tras
        // borrar la cuenta, ver GoogleAuthManager.deleteAccount, que reutiliza
        // esta función), nadie más vuelve a pasar `isOwner(hid)` en
        // `firestore.rules` nunca — el hogar queda sin nadie que pueda
        // gestionar roles/contenido sensible (panel v4, Experto 2 hallazgo
        // #6 ALTO). Debe ocurrir mientras seguimos siendo owner (antes de
        // borrar nuestro documento): `firestore.rules` exige `isOwner(hid)`
        // (comparado contra el `ownerId` vigente) tanto para reasignar el rol
        // de otro miembro como para el propio PATCH de `ownerId`.
        val household = try {
            getHousehold(householdId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (household != null && household.ownerId in identities) {
            val remaining = members.filterNot { it in toDelete }
            val successor = HouseholdRules.resolveOwnerSuccessor(remaining)
            if (successor?.userId != null) {
                try {
                    if (successor.role != "admin") {
                        memberRepository.updateMemberRole(householdId, successor.id, "admin")
                    }
                    householdRepository.updateHouseholdOwner(householdId, successor.userId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // No crítico: mejor completar el abandono/borrado de
                    // cuenta del usuario actual que bloquearlo por un fallo
                    // al transferir la propiedad (best-effort, igual que el
                    // resto de este flujo).
                }
            }
            // Si ningún miembro restante tiene cuenta vinculada (solo quedan
            // perfiles "hijo/a" sin cuenta), no hay a quién transferir — el
            // hogar queda sin owner operable hasta que alguno de ellos se
            // vincule a una cuenta; limitación conocida, ver KDoc de
            // [HouseholdRules.resolveOwnerSuccessor].
        }

        // El hogar seguirá teniendo otros miembros: borrado real solo de los
        // documentos que nos pertenecen.
        toDelete.forEach { member ->
            try {
                client.delete("$baseUrl/households/$householdId/members/${member.id}") {
                    withAuth()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No crítico: si el doc ya no existe, seguimos.
            }
            // Borra también los logros del miembro y lo purga de
            // assignmentRotation/asignaciones pendientes — mismo tratamiento
            // que [deleteMember] (expulsión por admin); sin esto, quien
            // abandona (o cuya cuenta se borra vía GoogleAuthManager.deleteAccount,
            // que reutiliza esta función) dejaba `members/{uid}/achievements`
            // huérfano e indexado por su propio UID (Art. 17 RGPD, panel de
            // expertos 2026-09-11 v9 reintento, privacidad).
            try {
                deleteAllDocuments("$baseUrl/households/$householdId/members/${member.id}/achievements")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No crítico: ver comentario de arriba (best-effort).
            }
            try {
                purgeMemberFromTasks(householdId, member.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No crítico: ver comentario de arriba (best-effort).
            }
        }
        if (toDelete.isNotEmpty()) {
            // Reescribe el authorName de los mensajes de chat de quien se va
            // — a diferencia de deleteHousehold (borra TODO el contenido),
            // aquí el hogar sigue existiendo para el resto de miembros, así
            // que el nombre real de quien se fue quedaba visible para
            // siempre en el historial de chat (panel v4, Experto 10 #4).
            // Best-effort: ver KDoc de [HouseholdRepository.anonymizeMemberMessages].
            householdRepository.anonymizeMemberMessages(
                householdId,
                toDelete.map { it.id }.toSet(),
                AppStrings.get("member_deleted_name", settingsStore.getLanguage())
            )
            // Ver KDoc de [deleteMember]: mismo tratamiento para los
            // comentarios de tarea de quien abandona.
            taskRepository.anonymizeMemberComments(
                householdId,
                toDelete.map { it.id }.toSet(),
                AppStrings.get("member_deleted_name", settingsStore.getLanguage())
            )
            taskCache.clearMembers(householdId)
            memberRepository.invalidateCurrentMember(householdId)
        }
        return false
    }

    /** Find a household by invite code. Uses the invites/{code} map (no list). */
    suspend fun joinHousehold(inviteCode: String): HouseholdResponse = householdRepository.joinHousehold(inviteCode)

    // ────────────────────────────────────────────────────────
    //  Household membership, Members, User profile, Points — delegado en
    //  MemberRepository (ver docs/review-panel-expertos-v3-2026-09-01.md,
    //  Experto 7 #1). Facade temporal: firma pública idéntica, sin lógica
    //  propia salvo `deleteFirebaseAccount` (Auth, no Member).
    // ────────────────────────────────────────────────────────

    suspend fun isMember(householdId: String, userIds: List<String>): Boolean =
        memberRepository.isMember(householdId, userIds)

    suspend fun isCurrentUserMember(householdId: String): Boolean =
        memberRepository.isCurrentUserMember(householdId)

    open suspend fun getMembers(householdId: String): List<MemberResponse> = memberRepository.getMembers(householdId)

    suspend fun createMember(
        householdId: String,
        displayName: String,
        role: String = "child",
        avatarUrl: String? = null,
        userId: String? = null,
        inviteCode: String? = null
    ): MemberResponse = memberRepository.createMember(householdId, displayName, role, avatarUrl, userId, inviteCode)

    /** Ver [MemberRepository.resolveCurrentMember]. */
    open suspend fun resolveCurrentMember(householdId: String): String = memberRepository.resolveCurrentMember(householdId)

    suspend fun ensurePersonalMember(householdId: String): String = memberRepository.ensurePersonalMember(householdId)

    /**
     * Da de baja a un miembro (ver [MemberRepository.deleteMember] para el
     * soft-delete en sí) y purga sus referencias en las tareas del hogar:
     * quita sus slots de `assignmentRotation` y borra sus asignaciones
     * "assigned" pendientes, para que la siguiente regeneración de la
     * Cloud Function `completeRecurringTask`/`completeAssignment` (ver
     * `functions/src/completeRecurringTask.ts`) no le cree una asignación
     * real a alguien ya invisible en [getMembers] (panel v4, Experto 2
     * hallazgo #2 ALTO).
     * La purga es best-effort: un fallo aquí no debe deshacer el soft-delete
     * ya confirmado, que es el efecto principal e irreversible de esta acción.
     *
     * Si [memberId] es el propio `ownerId` del hogar (la UI de
     * `HouseholdMemberList` permite a cualquier admin expulsar a cualquier
     * otro miembro, incluido el owner, sin gate específico — panel de
     * expertos 2026-09-13), transfiere la propiedad al mismo sucesor que
     * [leaveHousehold] (miembro con cuenta vinculada más antiguo, admin
     * primero — ver [HouseholdRules.resolveOwnerSuccessor]) ANTES del
     * soft-delete. Sin esto, `households/{hid}.ownerId` seguía apuntando al
     * UID del miembro ya expulsado: `firestore.rules` `isOwner(hid)` solo
     * compara ese campo contra `request.auth.uid`, así que el expulsado
     * conservaba permisos de owner (borrar el hogar, gestionar roles) pese a
     * ya no aparecer como miembro — y nadie más podía sucederle nunca por
     * esta vía.
     */
    suspend fun deleteMember(householdId: String, memberId: String): Boolean {
        val household = try {
            getHousehold(householdId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val targetMember = try {
            getMembers(householdId).find { it.id == memberId }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (household != null && targetMember != null && targetMember.userId == household.ownerId) {
            val remaining = try {
                getMembers(householdId).filterNot { it.id == memberId }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val plan = HouseholdRules.planOwnerSuccession(household.ownerId, targetMember.userId, remaining)
            if (plan != null) {
                try {
                    if (plan.promoteToAdmin) {
                        memberRepository.updateMemberRole(householdId, plan.successorMemberId, "admin")
                    }
                    householdRepository.updateHouseholdOwner(householdId, plan.successorUserId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // No crítico: mejor completar la expulsión que bloquearla
                    // por un fallo al transferir la propiedad (best-effort,
                    // igual que en leaveHousehold).
                }
            }
            // Si nadie más tiene cuenta vinculada, el hogar queda sin owner
            // operable — misma limitación conocida que en leaveHousehold.
        }
        val result = memberRepository.deleteMember(householdId, memberId)
        try {
            purgeMemberFromTasks(householdId, memberId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No crítico: ver KDoc de deleteMember.
        }
        // Anonimiza los mensajes de chat del miembro expulsado — mismo patrón
        // ya usado en leaveHousehold (panel v6, Experto 10 #2): a diferencia
        // de abandonar voluntariamente, la expulsión por admin se había
        // quedado sin esta llamada, dejando el nombre real del expulsado
        // visible para siempre en el chat. Best-effort, igual que en
        // leaveHousehold.
        try {
            householdRepository.anonymizeMemberMessages(
                householdId,
                setOf(memberId),
                AppStrings.get("member_deleted_name", settingsStore.getLanguage())
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No crítico: ver KDoc de deleteMember.
        }
        // Anonimiza también los comentarios de tarea del miembro expulsado —
        // mismo motivo que los mensajes de chat arriba (panel 2026-09-03/04,
        // Experto 2/10): sin esto, su nombre real queda visible para siempre
        // en los comentarios de cualquier tarea que haya comentado.
        try {
            taskRepository.anonymizeMemberComments(
                householdId,
                setOf(memberId),
                AppStrings.get("member_deleted_name", settingsStore.getLanguage())
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No crítico: ver KDoc de deleteMember.
        }
        return result
    }

    /**
     * Recorre todas las tareas del hogar purgando [memberId] de
     * `assignmentRotation` (ver [RecurrenceRules.purgeMemberFromRotation]) y
     * borrando sus asignaciones "assigned" pendientes — ver KDoc de
     * [deleteMember]. Best-effort por tarea: un fallo puntual no aborta el
     * resto.
     */
    private suspend fun purgeMemberFromTasks(householdId: String, memberId: String) {
        val tasks = try {
            getTasks(householdId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        for (task in tasks) {
            if (task.assignmentRotation.any { it.memberId == memberId }) {
                try {
                    taskRepository.updateAssignmentRotation(
                        householdId, task.id,
                        RecurrenceRules.purgeMemberFromRotation(task.assignmentRotation, memberId)
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }
            }
            val assignments = try {
                getAssignments(householdId, task.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val toDelete = assignments.filter { it.memberId == memberId && it.status == "assigned" }
            if (toDelete.isNotEmpty()) {
                taskRepository.deleteAssignmentDocs(householdId, task.id, toDelete)
            }
        }
    }

    suspend fun updateMemberRole(householdId: String, memberId: String, role: String) =
        memberRepository.updateMemberRole(householdId, memberId, role)

    suspend fun getUserProfile(userId: String): UserProfile? = memberRepository.getUserProfile(userId)

    suspend fun upsertUserProfile(
        userId: String,
        displayName: String,
        avatarUrl: String? = null,
        avatarEmoji: String = "",
        bio: String = "",
        status: String = ""
    ) = memberRepository.upsertUserProfile(userId, displayName, avatarUrl, avatarEmoji, bio, status)

    suspend fun deleteUserProfile(userId: String) = memberRepository.deleteUserProfile(userId)

    /**
     * Borra la cuenta de Firebase Auth actual (Google). Ver
     * [FirestoreClient.deleteFirebaseAccount] — debe ser SIEMPRE el último
     * paso del flujo "eliminar cuenta": una vez borrada, el idToken deja de
     * servir para más escrituras.
     */
    suspend fun deleteFirebaseAccount() = firestoreClient.deleteFirebaseAccount()

    open suspend fun updateMemberStreak(
        householdId: String,
        memberId: String,
        currentStreak: Int,
        bestStreak: Int,
        lastStreakDate: Long
    ) = memberRepository.updateMemberStreak(householdId, memberId, currentStreak, bestStreak, lastStreakDate)

    suspend fun addMemberPoints(householdId: String, memberId: String, delta: Int, floor: Int? = null) =
        memberRepository.addMemberPoints(householdId, memberId, delta, floor)

    // NOTA: `AppreciateResult`/`AppreciateErrorReason`/`DonateResult`/
    // `DonateErrorReason` ya NO son clases anidadas de FirestoreRepository —
    // viven en MemberRepository (ver su KDoc). Kotlin no permite `typealias`
    // anidado dentro de una clase, así que los call-sites externos
    // (MemberScreenModel) referencian `MemberRepository.AppreciateResult`
    // directamente en vez de `FirestoreRepository.AppreciateResult`.

    fun appreciationRemaining(member: MemberResponse, now: Long = Clock.System.now().toEpochMilliseconds()): Int =
        memberRepository.appreciationRemaining(member, now)

    suspend fun appreciateMember(
        householdId: String,
        fromMemberId: String,
        toMemberId: String,
        amount: Int
    ): MemberRepository.AppreciateResult = memberRepository.appreciateMember(householdId, fromMemberId, toMemberId, amount)

    suspend fun donatePoints(
        householdId: String,
        fromMemberId: String,
        toMemberId: String,
        amount: Int
    ): MemberRepository.DonateResult = memberRepository.donatePoints(householdId, fromMemberId, toMemberId, amount)

    open suspend fun getMemberAchievements(householdId: String, memberId: String): Set<String> =
        memberRepository.getMemberAchievements(householdId, memberId)

    open suspend fun addMemberAchievement(householdId: String, memberId: String, achievementId: String) =
        memberRepository.addMemberAchievement(householdId, memberId, achievementId)

    // ────────────────────────────────────────────────────────
    //  Tasks (subcollection under households/{id})
    // ────────────────────────────────────────────────────────

    // Tasks CRUD + history + assignments — delegado en TaskRepository (fase
    // 2.3 del refactor), salvo completeTask/completeAssignment/
    // reassignTaskCompletion: desde
    // `docs/recurrencia-backend-cloud-functions-diseno-2026-09-11.md` (decisión
    // aprobada por Liberto) ya NO orquestan Task+Member en el cliente —
    // delegan la transacción completa en Cloud Functions
    // (`cloudFunctionsClient.call(...)`) y la fachada solo mapea errores de
    // conflicto y limpia caché. Se quedan aquí (no en TaskRepository) porque
    // esa es la capa que conoce `cloudFunctionsClient`; no hay lógica de
    // negocio residual que mover — vive en `functions/src/`.

    open suspend fun createTask(
        householdId: String,
        createdBy: String,
        title: String,
        description: String,
        points: Int,
        frequency: String,
        recurrenceDays: List<Int>,
        recurrenceDay: Int? = null,
        tags: List<String>,
        subtasks: List<Subtask> = emptyList(),
        penaltyMode: String?,
        penaltyValue: Int,
        penaltyInterval: String,
        penaltyMax: Int,
        dueDate: Long = 0,
        assignmentRotation: List<org.taskhub.network.models.AssignmentSlot> = emptyList()
    ): TaskResponse = taskRepository.createTask(
        householdId, createdBy, title, description, points, frequency, recurrenceDays,
        recurrenceDay, tags, subtasks, penaltyMode, penaltyValue, penaltyInterval, penaltyMax,
        dueDate, assignmentRotation
    )

    open suspend fun getTasks(householdId: String): List<TaskResponse> = taskRepository.getTasks(householdId)

    /** Get a single task by id. Used where only one task is needed (avoids an N+1 full-list fetch). */
    open suspend fun getTask(householdId: String, taskId: String): TaskResponse = taskRepository.getTask(householdId, taskId)

    /** Resultado de [completeTask]: puntos realmente otorgados (tras penalización) y puntualidad. */
    data class TaskCompletionResult(val completedAt: Long, val pointsAwarded: Int, val onTime: Boolean)

    /**
     * Otro dispositivo modificó el documento de la tarea (típicamente
     * completándola también) entre que [completeTask] leyó su estado y trató
     * de marcarla como completada. A diferencia de [addMemberPoints] (donde
     * reintentar con el valor fresco es seguro porque sumar es conmutativo),
     * completar una tarea NO es idempotente: reintentar automáticamente
     * otorgaría los puntos dos veces. Política elegida: el perdedor de la
     * carrera ve este error (mapeado a `TaskActionState.Error` en
     * `TaskScreenModel`, mismo tratamiento que cualquier otro fallo de red) y
     * debe recargar/reintentar a mano — nunca se le otorgan puntos.
     */
    class TaskCompletionConflictException(message: String) : Exception(message)

    /**
     * Otro dispositivo modificó la asignación entre que [completeAssignment]
     * la leyó y trató de marcarla como completada. Mismo motivo y política
     * que [TaskCompletionConflictException] (no reintentar automáticamente:
     * completar no es idempotente) — antes `completeAssignment` no tenía
     * ninguna protección aquí, así que dos dispositivos completando la misma
     * asignación casi a la vez duplicaban puntos/historial y podían crear DOS
     * asignaciones distintas para la siguiente ocurrencia (cadena bifurcada).
     */
    class AssignmentCompletionConflictException(message: String) : Exception(message)

    /**
     * Completa una tarea recurrente delegando en la Cloud Function
     * `completeRecurringTask` (ver
     * `docs/recurrencia-backend-cloud-functions-diseno-2026-09-11.md`,
     * secciones 2.1 y 4): UNA sola llamada de red, resuelta en una
     * `runTransaction` del Admin SDK en vez de la secuencia de N escrituras
     * HTTP directas a Firestore que había antes (evaluada y descartada la
     * alternativa de construir el `:commit` a mano en cliente — ver
     * `docs/atomicidad-commit-pendiente.md`). El servidor lee tarea +
     * miembro + asignaciones del ciclo, calcula puntos/puntualidad con las
     * MISMAS reglas (portadas a TypeScript, ver `functions/src/penalty.ts`/
     * `rules.ts`) y escribe todo-o-nada: `lastCompletedDate`/`completedBy`/
     * `nextDueAt` de la tarea, el registro de `taskHistory`
     * (`pointsApplied: true` directo, sin el patrón "false → true" de antes),
     * `totalPoints` del miembro, las asignaciones del ciclo → `completed` y
     * la del siguiente ciclo si aplica.
     *
     * Concurrencia optimista: [task].`lastCompletedDate` viaja como
     * `expectedLastCompletedDate`; si no coincide con el valor fresco que lee
     * la transacción, la función responde `ABORTED`/`FAILED_PRECONDITION` →
     * se mapea a [TaskCompletionConflictException] (mismo tratamiento en
     * `TaskScreenModel` que antes, cero cambio en ese catch).
     */
    open suspend fun completeTask(
        householdId: String,
        taskId: String,
        memberId: String,
        task: TaskResponse
    ): TaskCompletionResult {
        // Invalidación de caché movida a `finally`: un timeout de red no
        // distingue "la llamada nunca llegó al servidor" de "sí se aplicó
        // pero la respuesta se perdió" — antes solo se invalidaba en el
        // camino feliz, así que ese segundo caso (ambiguo) dejaba
        // `taskCache` sirviendo la foto PRE-compleción indefinidamente si el
        // dispositivo quedaba offline justo después (panel v12, Red/offline).
        // Invalidar de más es barato (una lectura de red extra); no
        // invalidar de menos puede mostrar datos incorrectos.
        try {
            val result = try {
                cloudFunctionsClient.call<CompleteRecurringTaskRequest, TaskCompletionFunctionResult>(
                    "completeRecurringTask",
                    CompleteRecurringTaskRequest(
                        householdId = householdId,
                        taskId = taskId,
                        memberId = memberId,
                        expectedLastCompletedDate = task.lastCompletedDate
                    )
                )
            } catch (e: CloudFunctionException) {
                throw mapToTaskCompletionConflict(e)
            }
            return TaskCompletionResult(result.completedAt, result.pointsAwarded, result.onTime)
        } finally {
            taskCache.clearTasks(householdId)
            taskCache.clearTaskHistory(householdId)
            taskCache.clearMembers(householdId)
            taskCache.clearAssignments(householdId, taskId)
        }
    }

    /** `true` si [status] (de [CloudFunctionException]) señala un conflicto de concurrencia (perdedor de una carrera). */
    private fun isConflictStatus(status: String) = status == "ABORTED" || status == "FAILED_PRECONDITION"

    private fun mapToTaskCompletionConflict(e: CloudFunctionException): Exception =
        if (isConflictStatus(e.status)) {
            TaskCompletionConflictException(
                "La tarea se modificó en otro dispositivo justo antes de completarla. Vuelve a intentarlo."
            )
        } else e

    private fun mapToAssignmentCompletionConflict(e: CloudFunctionException): Exception =
        if (isConflictStatus(e.status)) {
            AssignmentCompletionConflictException(
                "Esta asignación se completó en otro dispositivo justo antes. Vuelve a intentarlo."
            )
        } else e

    /**
     * Deshace una compleción — delega en la Cloud Function `undoTaskCompletion`
     * (ver diseño, sección 2.4, opción (ii) decidida por Liberto): el
     * SERVIDOR deriva el estado previo (lastCompletedDate/completedBy/
     * nextDueAt/puntos/asignaciones) leyendo el registro de `taskHistory`
     * anterior a [completedAt], en vez de depender de un `UndoState` volátil
     * en memoria del cliente — deshacer sobrevive a recargar la pantalla o
     * cerrar la app entre completar y deshacer. Idempotente: si el registro
     * ya no existe (undo repetido desde dos dispositivos), la función
     * devuelve `reverted: false` en vez de fallar.
     *
     * Colapsa en UNA llamada lo que antes eran dos ([revertTaskCompletion] +
     * [undoTaskCompletionAssignments], ya retiradas) más el
     * `deleteTaskHistoryRecord`/`addMemberPoints` que hacía `TaskScreenModel`
     * a mano. Streak/racha del miembro sigue siendo responsabilidad del
     * cliente (la función no la toca) — ver `TaskScreenModel.undoCompleteTask`.
     */
    open suspend fun undoTaskCompletion(householdId: String, taskId: String, completedAt: Long) {
        // Invalidación en `finally`: mismo motivo que [completeTask].
        try {
            cloudFunctionsClient.call<UndoTaskCompletionRequest, UndoTaskCompletionResult>(
                "undoTaskCompletion",
                UndoTaskCompletionRequest(householdId = householdId, taskId = taskId, completedAt = completedAt)
            )
        } finally {
            taskCache.clearTasks(householdId)
            taskCache.clearTaskHistory(householdId)
            taskCache.clearMembers(householdId)
            taskCache.clearAssignments(householdId, taskId)
        }
    }

    /** Get all task history records for a household. */
    open suspend fun getTaskHistory(householdId: String): List<TaskHistoryResponse> = taskRepository.getTaskHistory(householdId)

    /** Ver [TaskRepository.purgeOldTaskHistory]. */
    suspend fun purgeOldTaskHistory(householdId: String, all: List<TaskHistoryResponse>, maxAgeMillis: Long = RETENTION_90_DAYS_MILLIS) =
        taskRepository.purgeOldTaskHistory(householdId, all, maxAgeMillis)

    /**
     * Reasigna quién ha hecho una tarea ya completada (corrección de
     * errores) — delega en la Cloud Function `reassignTaskCompletion` (ver
     * diseño, sección 2.3): UNA transacción que lee `completedBy` + el
     * registro de `taskHistory` de esa compleción (fallback a `task.points`
     * si no hay historial, igual que antes), transfiere puntos del miembro
     * anterior al nuevo y reasigna `completedBy` + el registro de historial +
     * la asignación de esa misma compleción — reemplaza las 4 escrituras
     * HTTP secuenciales de antes. Requiere llamador `isTrusted` (owner o
     * admin), verificado por la función leyendo el rol del caller.
     *
     * [taskPoints] se mantiene en la firma solo por compatibilidad con
     * `TaskScreenModel` (no cambia de firma vista desde el ScreenModel) — ya
     * no se reenvía a la función, que deriva el fallback de `task.points`
     * ella misma si no hay registro de historial.
     */
    open suspend fun reassignTaskCompletion(
        householdId: String,
        taskId: String,
        taskPoints: Int,
        newMemberId: String
    ) {
        cloudFunctionsClient.call<ReassignTaskCompletionRequest, ReassignTaskCompletionResult>(
            "reassignTaskCompletion",
            ReassignTaskCompletionRequest(householdId = householdId, taskId = taskId, newMemberId = newMemberId)
        )
        taskCache.clearTasks(householdId)
        taskCache.clearTaskHistory(householdId)
        taskCache.clearMembers(householdId)
        taskCache.clearAssignments(householdId, taskId)
    }

    /** Assign a task to one or more members with a due date. */
    open suspend fun assignTask(
        householdId: String,
        taskId: String,
        memberIds: List<String>,
        mandatory: Boolean,
        dueDate: Long,
        taskTitle: String = "",
        assignedByMemberId: String? = null
    ): List<TaskAssignmentResponse> =
        taskRepository.assignTask(householdId, taskId, memberIds, mandatory, dueDate, taskTitle, assignedByMemberId)

    /** Get all assignments for a specific task. */
    open suspend fun getAssignments(householdId: String, taskId: String): List<TaskAssignmentResponse> =
        taskRepository.getAssignments(householdId, taskId)

    /** Borra todas las asignaciones de una tarea (para reasignar al editar). */
    suspend fun deleteAssignments(householdId: String, taskId: String) =
        taskRepository.deleteAssignments(householdId, taskId)

    /**
     * Sustituye las asignaciones de una tarea por unas nuevas (usado al editar
     * la tarea desde [EditTaskScreen]).
     *
     * Mitigación de atomicidad (ver `docs/atomicidad-commit-pendiente.md`,
     * sección `updateTask`): antes esta operación era `deleteAssignments` +
     * `assignTask` como dos pasos independientes en el caller — si la creación
     * de las nuevas asignaciones fallaba a mitad de camino (p. ej. tras crear
     * la asignación de 2 de 3 miembros), la tarea ya se había quedado sin
     * ninguna asignación previa, así que el resultado era "tarea con solo 2
     * asignaciones" en el mejor caso o "sin ninguna" si fallaba en el primer
     * miembro. Aquí se invierte el orden: se crean las asignaciones nuevas
     * PRIMERO y solo se borran las antiguas si esa creación no lanzó. Si el
     * paso de creación falla, la tarea conserva sus asignaciones previas
     * (estado recuperable) en vez de quedarse sin ninguna. Sigue sin ser
     * atómico de extremo a extremo (un fallo justo en el borrado de las
     * antiguas puede dejar antiguas + nuevas duplicadas, un estado peor que
     * "sin cambios" pero mejor que "sin asignaciones").
     */
    open suspend fun replaceAssignments(
        householdId: String,
        taskId: String,
        memberIds: List<String>,
        mandatory: Boolean,
        dueDate: Long,
        taskTitle: String = ""
    ): List<TaskAssignmentResponse> =
        taskRepository.replaceAssignments(householdId, taskId, memberIds, mandatory, dueDate, taskTitle)

    /** Get all assignments across all tasks for a household (peticiones en paralelo). */
    open suspend fun getAllAssignments(householdId: String): List<TaskAssignmentResponse> =
        taskRepository.getAllAssignments(householdId)

    /** Ver [TaskRepository.getAllAssignments] (overload que reutiliza tareas ya cargadas). */
    open suspend fun getAllAssignments(householdId: String, tasks: List<TaskResponse>): List<TaskAssignmentResponse> =
        taskRepository.getAllAssignments(householdId, tasks)

    /**
     * Completa una asignación concreta delegando en la Cloud Function
     * `completeAssignment` (ver diseño, sección 2.2): transacción análoga a
     * [completeTask] pero ancla la lectura en el documento de la ASIGNACIÓN
     * (no en `task.lastCompletedDate`) — replica exactamente lo que antes
     * hacían `completeAssignment` + `regenerateNextAssignment` en una
     * secuencia de escrituras HTTP.
     *
     * Concurrencia optimista: la función valida `assignment.status ==
     * "assigned"` dentro de su propia transacción; si otro dispositivo ya la
     * completó, responde `ABORTED` → se mapea a
     * [AssignmentCompletionConflictException] (mismo tratamiento en
     * `TaskScreenModel` que antes).
     */
    open suspend fun completeAssignment(
        householdId: String,
        taskId: String,
        task: TaskResponse,
        assignmentId: String,
        assignment: TaskAssignmentResponse
    ): TaskAssignmentResponse {
        val result = try {
            cloudFunctionsClient.call<CompleteAssignmentRequest, TaskCompletionFunctionResult>(
                "completeAssignment",
                CompleteAssignmentRequest(householdId = householdId, taskId = taskId, assignmentId = assignmentId)
            )
        } catch (e: CloudFunctionException) {
            throw mapToAssignmentCompletionConflict(e)
        }
        taskCache.clearTasks(householdId)
        taskCache.clearTaskHistory(householdId)
        taskCache.clearMembers(householdId)
        taskCache.clearAssignments(householdId, taskId)
        return assignment.copy(
            status = "completed",
            completedAt = result.completedAt,
            pointsAwarded = result.pointsAwarded,
            onTime = result.onTime
        )
    }

    /**
     * Vincula/desvincula el evento de Google Calendar de una asignación.
     * `googleEventId = null` limpia el campo (p. ej. tras borrar el evento).
     */
    open suspend fun updateAssignmentGoogleEventId(
        householdId: String,
        taskId: String,
        assignmentId: String,
        googleEventId: String?
    ) = taskRepository.updateAssignmentGoogleEventId(householdId, taskId, assignmentId, googleEventId)

    // ────────────────────────────────────────────────────────
    //  Task helpers
    // ────────────────────────────────────────────────────────

    open suspend fun updateTask(
        householdId: String,
        taskId: String,
        title: String,
        description: String,
        points: Int,
        frequency: String,
        recurrenceDays: List<Int>,
        recurrenceDay: Int? = null,
        tags: List<String>,
        subtasks: List<Subtask> = emptyList(),
        penaltyMode: String?,
        penaltyValue: Int,
        penaltyInterval: String,
        penaltyMax: Int,
        assignmentRotation: List<org.taskhub.network.models.AssignmentSlot> = emptyList(),
        dueDate: Long = 0,
        lastCompletedDate: Long? = null
    ): Long? = taskRepository.updateTask(
        householdId, taskId, title, description, points, frequency, recurrenceDays, recurrenceDay,
        tags, subtasks, penaltyMode, penaltyValue, penaltyInterval, penaltyMax, assignmentRotation, dueDate,
        lastCompletedDate
    )

    /**
     * Update only the subtasks array on a task document.
     * Used for quick toggling of individual subtask checkboxes.
     */
    open suspend fun updateSubtasks(
        householdId: String,
        taskId: String,
        subtasks: List<Subtask>
    ) = taskRepository.updateSubtasks(householdId, taskId, subtasks)

    /**
     * Delete a task document.
     */
    open suspend fun deleteTask(householdId: String, taskId: String) = taskRepository.deleteTask(householdId, taskId)

    // ────────────────────────────────────────────────────────
    //  Comments (subcollection under households/{id}/tasks/{taskId})
    // ────────────────────────────────────────────────────────

    /** Add a comment to a task. [memberId] identifica al autor (ver [org.taskhub.network.models.CommentResponse.memberId]). */
    suspend fun addComment(
        householdId: String,
        taskId: String,
        memberId: String,
        authorName: String,
        text: String
    ): org.taskhub.network.models.CommentResponse = taskRepository.addComment(householdId, taskId, memberId, authorName, text)

    /** List comments for a task. */
    suspend fun getComments(
        householdId: String,
        taskId: String
    ): List<org.taskhub.network.models.CommentResponse> = taskRepository.getComments(householdId, taskId)

    // ────────────────────────────────────────────────────────
    //  Messages (subcollection under households/{id})
    // ────────────────────────────────────────────────────────

    /** Send a chat message to a household. */
    suspend fun sendMessage(
        householdId: String,
        memberId: String,
        authorName: String,
        text: String
    ): org.taskhub.network.models.MessageResponse = householdRepository.sendMessage(householdId, memberId, authorName, text)

    /** List chat messages for a household, oldest first. Ver [HouseholdRepository.getMessages]. */
    suspend fun getMessages(householdId: String, limit: Int? = null): List<org.taskhub.network.models.MessageResponse> =
        householdRepository.getMessages(householdId, limit)

    /** Ver [HouseholdRepository.purgeOldMessages]. */
    suspend fun purgeOldMessages(
        householdId: String,
        all: List<org.taskhub.network.models.MessageResponse>,
        maxAgeMillis: Long = RETENTION_90_DAYS_MILLIS
    ) = householdRepository.purgeOldMessages(householdId, all, maxAgeMillis)

    // ────────────────────────────────────────────────────────
    //  Notifications — delegado en NotificationRepository (fase 2.1 del
    //  refactor). Facade temporal: firma pública idéntica, sin lógica propia.
    // ────────────────────────────────────────────────────────

    suspend fun createNotification(
        householdId: String,
        memberId: String,
        taskId: String,
        title: String,
        message: String
    ): NotificationResponse = notificationRepository.createNotification(householdId, memberId, taskId, title, message)

    /** Ver [NotificationRepository.getNotifications]. */
    suspend fun getNotifications(householdId: String, limit: Int? = null): List<NotificationResponse> =
        notificationRepository.getNotifications(householdId, limit)

    suspend fun markNotificationRead(householdId: String, notificationId: String) =
        notificationRepository.markNotificationRead(householdId, notificationId)

    /**
     * Ver [NotificationRepository.purgeOldRead]. Delegado aquí por primera vez
     * (ronda de deuda aplicable 2026-09-12, punto B9): existía en
     * `NotificationRepository` desde el panel de notificaciones 2026-09-05
     * pero ningún caller llegó a invocarlo a través de la fachada —
     * `NotificationScreenModel.loadNotifications` ya lo dispara ahora.
     */
    suspend fun purgeOldNotifications(householdId: String, all: List<NotificationResponse>, maxAgeMillis: Long = RETENTION_90_DAYS_MILLIS) =
        notificationRepository.purgeOldRead(householdId, all, maxAgeMillis)

    // ────────────────────────────────────────────────────────
    //  Rewards — delegado en RewardsRepository (fase 2.2 del refactor), salvo
    //  redeemReward: orquesta Reward+Member (descuenta puntos vía
    //  MemberRepository.addMemberPoints a la vez que registra el canje), así
    //  que se queda en la fachada — mismo motivo que completeTask.
    // ────────────────────────────────────────────────────────

    suspend fun getRewards(householdId: String): List<RewardResponse> = rewardsRepository.getRewards(householdId)

    suspend fun createReward(
        householdId: String,
        title: String,
        description: String,
        cost: Int,
        icon: String,
        createdBy: String
    ): RewardResponse = rewardsRepository.createReward(householdId, title, description, cost, icon, createdBy)

    suspend fun deleteReward(householdId: String, rewardId: String) =
        rewardsRepository.deleteReward(householdId, rewardId)

    /** Redeem a reward: subtract points from member, record redemption. Requires auth (write). */
    suspend fun redeemReward(
        householdId: String,
        rewardId: String,
        memberId: String,
        pointsSpent: Int
    ): RewardRedemption {
        val now = Clock.System.now().toEpochMilliseconds()

        // Validar saldo contra una lectura fresca del miembro — a diferencia de
        // donatePoints (que sí valida vía PointsRules), esta función descontaba
        // puntos sin comprobar el saldo en ningún punto del repositorio,
        // confiando solo en el `canAfford` (potencialmente obsoleto) de la UI.
        // No elimina la carrera entre dos canjes concurrentes (ver
        // docs/atomicidad-commit-pendiente.md), pero evita el caso más común:
        // un único canje con saldo insuficiente por datos ya desincronizados.
        val member = getMembers(householdId).find { it.id == memberId }
            ?: throw IllegalStateException("Miembro no encontrado")
        if (member.totalPoints < pointsSpent) {
            throw InsufficientBalanceException("Saldo insuficiente para canjear esta recompensa")
        }

        // 1. Guardar primero el registro de canje: si el paso 2 (descontar
        //    puntos) falla a mitad de camino, queda un registro auditable en
        //    vez de puntos perdidos sin ningún rastro de en qué se gastaron.
        val redemption = rewardsRepository.createRedemption(householdId, rewardId, memberId, pointsSpent, now)

        // 2. Descontar los puntos del miembro. Si esto falla, el registro de
        //    canje del paso 1 queda huérfano (recompensa "canjeada" sin
        //    descuento real) y un reintento del usuario duplicaría el
        //    registro con un solo descuento — se compensa borrándolo aquí
        //    antes de relanzar, en vez de dejarlo para un segundo intento
        //    (garantía que cambia: ya no queda rastro auditable de un intento
        //    fallido, pero tampoco puede haber doble registro con un único
        //    descuento).
        try {
            // floor = 0: revalida el saldo contra el valor fresco en cada
            // reintento de addMemberPoints, no solo contra la lectura de
            // arriba (que puede estar obsoleta si otro dispositivo canjeó/
            // donó entre medias) — cierra la carrera de dos canjes
            // concurrentes que podía dejar totalPoints negativo (panel v12,
            // Red/offline). Al lanzar InsufficientBalanceException, este
            // mismo catch borra el redemption huérfano y la relanza tal cual,
            // igual que cualquier otro fallo de esta escritura.
            addMemberPoints(householdId, memberId, -pointsSpent, floor = 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                rewardsRepository.deleteRedemption(householdId, redemption.id)
            } catch (cleanupError: CancellationException) {
                throw cleanupError
            } catch (_: Exception) {
                // Best-effort: si el borrado también falla, se prioriza relanzar
                // el error original en vez de ocultarlo tras un fallo de limpieza.
            }
            throw e
        }

        return redemption
    }

    /** Get all reward redemptions for a household. */
    suspend fun getRewardRedemptions(householdId: String): List<RewardRedemption> =
        rewardsRepository.getRewardRedemptions(householdId)

    // ────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────

    /** Ver [FirestoreClient.extractDocId] — delegado tal cual, mismo motivo que [withAuth]. */
    private fun extractDocId(resourceName: String, operation: String): String =
        firestoreClient.extractDocId(resourceName, operation)

    companion object {
        /** Firebase Web API Key for task-hub-62f98 (Firebase Console → Project Settings → General). */
        const val DEFAULT_API_KEY = "AIzaSyD5Xo11SqvysWRgEFv_91rBjYuFIq93lV8"
    }
}
