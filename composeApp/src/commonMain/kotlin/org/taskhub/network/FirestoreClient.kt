/**
 * Cliente HTTP + autenticación de bajo nivel para la capa REST de
 * Firestore/Firebase Auth: construcción de URLs, ciclo de vida del token
 * (login exclusivamente con Google, refresco), validación de errores HTTP y
 * paginación de colecciones. Es la base sobre la que se construyen
 * [FirestoreRepository] y todos los repos de dominio (`MemberRepository`,
 * `TaskRepository`, etc.), inyectada por composición vía Koin.
 */
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import org.taskhub.platform.AppLog
import kotlin.concurrent.Volatile
import kotlin.random.Random

/** Project ID por defecto de Firestore — ver [firestoreBaseUrl]. */
const val DEFAULT_FIRESTORE_PROJECT_ID = "task-hub-62f98"

/**
 * URL base de la API REST de Firestore para un proyecto — construida en un
 * único sitio para que [FirestoreRepository] y los repos de dominio
 * (registrados como `single` de Koin, ver `AppModule.kt`) usen exactamente la
 * misma URL sin duplicar la plantilla (panel v7, #16).
 */
fun firestoreBaseUrl(projectId: String = DEFAULT_FIRESTORE_PROJECT_ID): String =
    "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"

/**
 * Cliente HTTP + autenticación Firebase de bajo nivel, extraído de
 * [FirestoreRepository] (ver docs/refactor-arquitectura-2026-08-31.md, punto 6,
 * fase 1). Sin lógica de dominio: solo transporte, gestión de tokens y parseo
 * de errores de la API REST de Firestore/Firebase Auth. Se inyecta por
 * composición en [FirestoreRepository] y en los repos de dominio.
 */
class FirestoreClient(
    private val apiKey: String,
    private val settingsStore: org.taskhub.storage.SettingsStore
) {
    private val secureTokenUrl = "https://securetoken.googleapis.com/v1/token"

    // ── Estado de auth (en memoria, se regenera al reiniciar la app) ──
    @Volatile
    var bearerToken: String? = null
        private set
    @Volatile
    var tokenExpiry: Long = 0L  // epoch millis en que caduca el token (con margen de seguridad restado)
        private set
    @Volatile
    var cachedLocalId: String? = null  // UID del usuario — persiste entre sesiones vía settingsStore
        private set
    // Serializa ensureAuth(): sin esto, ráfagas de llamadas paralelas (varias
    // pantallas cargando datos a la vez tras un cold start) pasan todas el
    // check "bearerToken == null" antes de que la primera termine de escribirlo,
    // disparando N altas/refrescos de token concurrentes.
    private val authMutex = Mutex()

    /** Json tolerante usado solo para parsear el body de error de Firestore, no el de dominio. */
    private val errorParsingJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
            })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
        // Intercepta CUALQUIER respuesta de error (>=400) antes de que se parsee
        // como documento — si no, un body de error de Firestore se convierte en
        // un FirestoreDocumentResponse vacío (por ignoreUnknownKeys) y el fallo
        // real (p.ej. PERMISSION_DENIED) queda enmascarado tras "missing document name".
        HttpResponseValidator {
            validateResponse { response ->
                if (response.status.value >= 400) {
                    val bodyText = orDefault("") { response.bodyAsText() }
                    val errorBody = orDefault(null) {
                        errorParsingJson.decodeFromString<FirestoreErrorEnvelope>(bodyText)
                    }?.error
                    val message = errorBody?.message?.takeIf { it.isNotBlank() }
                        ?: bodyText.takeIf { it.isNotBlank() }
                        ?: "Firestore respondió ${response.status.value} sin más detalles"
                    throw FirestoreException(
                        statusCode = response.status.value,
                        code = errorBody?.status,
                        message = message
                    )
                }
            }
        }
    }

    /**
     * Devuelve el UID de Google del usuario actual, o `null` si no hay sesión
     * iniciada. Cae al UID persistido si aún no se ha autenticado en esta
     * sesión, para que esté disponible antes de la primera llamada de red
     * (p.ej. al crear el miembro "Yo" del Personal).
     *
     * Vive aquí (no en `FirestoreRepository`) porque solo depende de
     * [cachedLocalId]/[settingsStore], nada del resto de la fachada — moverlo
     * es lo que permite que los repos de dominio ([MemberRepository]/
     * [HouseholdRepository]) dependan de [FirestoreClient] directamente en vez
     * de recibir `getLocalId`/`currentUserIdentities` como lambdas para evitar
     * un ciclo hacia `FirestoreRepository` (panel v7, #16).
     */
    fun getLocalId(): String? =
        cachedLocalId ?: settingsStore.getGoogleUid()

    /**
     * Todas las identidades posibles del usuario actual, sin duplicados: el
     * UID de Google persistido y el UID activo en esta sesión (normalmente el
     * mismo). Sirve para resolver "¿este miembro soy yo?" con independencia
     * de si el miembro se creó antes o después del login. Ver [getLocalId].
     */
    fun currentUserIdentities(): List<String> =
        listOfNotNull(
            settingsStore.getGoogleUid(),
            cachedLocalId
        ).distinct()

    /**
     * Asegura que hay un token de auth válido, renovando la sesión de Google
     * persistida si hace falta. Se llama de forma perezosa en la primera
     * petición. El token se cachea en memoria y se refresca cuando está a
     * menos de 5 minutos de caducar.
     *
     * Devuelve `null` si no hay ninguna sesión de Google (sin sesión anónima
     * de respaldo: Task Hub exige login con Google — ver
     * `docs/google-only-auth-2026-09-12.md`). Los llamadores ya tratan un
     * `bearerToken`/`getLocalId()` nulo como "no autenticado" (ver
     * [HouseholdRepository.createHousehold]).
     *
     * Devuelve el [bearerToken] vigente — leído DENTRO de la sección protegida
     * por [authMutex], no por el caller después de que `withLock` ya haya
     * soltado el lock (panel de revisión 2026-09-03/04, Experto 6, NUEVO):
     * antes los 2 llamadores ([withAuth]/[deleteFirebaseAccount]) leían
     * `bearerToken` en su propio cuerpo, fuera de cualquier mutex, así que
     * una ráfaga de llamadas concurrentes podía
     * intercalarse entre "esta corrutina terminó `ensureAuth()`" y "esta
     * corrutina lee `bearerToken`" con OTRA corrutina que también estuviera
     * refrescando el token — `@Volatile` garantiza visibilidad de memoria,
     * pero no que el valor leído sea el que ESTA llamada acaba de asegurar.
     */
    suspend fun ensureAuth(): String? = authMutex.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        if (bearerToken != null && now < tokenExpiry) return@withLock bearerToken

        // Restaurar sesión de Google si existe (UID estable del login Google).
        val googleRefresh = settingsStore.getGoogleRefreshToken()
        if (settingsStore.getGoogleUid() != null && googleRefresh != null) {
            try {
                val refreshed = refreshFirebaseToken(googleRefresh)
                bearerToken = refreshed.idToken
                cachedLocalId = refreshed.userId
                tokenExpiry = refreshed.tokenExpiry
                settingsStore.setGoogleRefreshToken(refreshed.refreshToken ?: googleRefresh)
                return@withLock bearerToken
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("FirestoreClient", "ensureAuth: refreshFirebaseToken failed (transient=${e.isTransientReadFailure()})", e)
                // OJO: distinguir fallo TRANSITORIO (sin red, timeout, 5xx de
                // Identity Toolkit) de un refresh token realmente inválido/
                // revocado (4xx, p.ej. TOKEN_EXPIRED/INVALID_REFRESH_TOKEN).
                // Antes se borraba la sesión de Google persistida ante
                // CUALQUIER excepción — incluida abrir la app sin conexión,
                // el caso de uso explícitamente soportado por el bootstrap de
                // `App.kt` (best-effort, cae a datos cacheados). Sin esta
                // distinción, cada arranque en frío offline destruía el
                // `googleRefreshToken` guardado (aunque siguiera siendo
                // válido): la sesión en memoria de esa ejecución seguía viva,
                // pero el siguiente arranque en frío (siguiera offline o no)
                // ya no encontraba credenciales que restaurar y caía a
                // [GoogleAuthState.SignedOut] — un usuario sin conexión podía
                // quedar deslogueado permanentemente sin haber hecho nada.
                // Solo se limpia la sesión ante un fallo NO transitorio (panel
                // de expertos, red/offline/sync, CRÍTICO).
                if (!e.isTransientReadFailure()) {
                    settingsStore.clearGoogleAuth()
                }
            }
        }

        null
    }

    /**
     * Renueva un idToken de Firebase Auth usando su refresh token, sin crear una
     * identidad nueva. Devuelve el MISMO UID (user_id), de modo que el usuario
     * conserva sus datos entre reinicios y reinstalaciones.
     *
     * Endpoint: POST https://securetoken.googleapis.com/v1/token?key=API_KEY
     * Body (form-urlencoded): grant_type=refresh_token&refresh_token=...
     */
    private suspend fun refreshFirebaseToken(refreshToken: String): RefreshedAuth {
        val response: TokenRefreshResponse = try {
            client.post("$secureTokenUrl?key=$apiKey") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(Parameters.build {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                }))
            }.body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("FirestoreClient", "refreshFirebaseToken: POST securetoken failed", e)
            throw redactApiKey(e)
        }

        val idToken = response.id_token
        val userId = response.user_id
        val expiresIn = response.expires_in?.toLongOrNull()
        if (idToken.isNullOrBlank() || userId.isNullOrBlank() || expiresIn == null) {
            throw IllegalStateException("Renovación del token de Firebase falló (respuesta incompleta)")
        }

        val now = Clock.System.now().toEpochMilliseconds()
        return RefreshedAuth(
            idToken = idToken,
            userId = userId,
            tokenExpiry = now + (expiresIn * 1000) - 300_000,
            refreshToken = response.refresh_token
        )
    }

    /** Resultado de renovar un token vía [refreshFirebaseToken]. */
    private data class RefreshedAuth(
        val idToken: String,
        val userId: String,
        val tokenExpiry: Long,
        val refreshToken: String? = null
    )

    /**
     * Fija el estado de auth (token/uid/expiry) directamente, sin pasar por
     * [ensureAuth]. Lo usa [FirestoreRepository.signInWithGoogle] tras
     * intercambiar el idToken de Google por uno de Firebase — ese flujo no es
     * "asegurar" un token existente sino sustituirlo por uno nuevo de sesión.
     * Protegido por [authMutex] igual que el resto de escrituras de estos 3
     * campos: sin el lock, una corrutina en medio de [ensureAuth] podía leer
     * una combinación a medio escribir de token/uid/expiry (panel de revisión
     * 2026-09-10, Experto 6, NUEVO).
     */
    suspend fun setAuthState(idToken: String, localId: String, expiry: Long) = authMutex.withLock {
        bearerToken = idToken
        cachedLocalId = localId
        tokenExpiry = expiry
    }

    /**
     * Añade la cabecera Authorization a la petición si ya hay token. Llama
     * primero a [ensureAuth] para garantizar que el token esté vigente —
     * usado tanto en lecturas como en escrituras, ya que TODAS las
     * colecciones de `firestore.rules` exigen `signedIn()` (no hay ninguna
     * ruta de lectura pública sin usuario autenticado).
     *
     * Hasta la ronda de seguridad 2026-09-12 (encargo "Quitar fallback
     * tryAuthOrApiKey") existía un `tryAuthOrApiKey()` separado para
     * lecturas, que ante un fallo de `ensureAuth()` caía a pasar la API key
     * como parámetro de query. Ese fallback nunca podía tener éxito (mismo
     * motivo: todas las reglas exigen `signedIn()`), así que quitarlo no
     * cambia ningún comportamiento observable — solo deja de exponer la API
     * key en la query string de esas peticiones de lectura.
     */
    suspend fun HttpRequestBuilder.withAuth() {
        val token = ensureAuth()
        token?.let { header("Authorization", "Bearer $it") }
    }

    /**
     * Añade `updateMask.fieldPaths` como parámetros de query repetidos (uno por
     * campo), tal y como exige la API REST de Firestore. Un único string con
     * los campos unidos por comas ("a,b,c") es inválido y produce el error
     * "Invalid property path" — Firestore espera múltiples pares
     * `updateMask.fieldPaths=a&updateMask.fieldPaths=b&updateMask.fieldPaths=c`.
     */
    fun HttpRequestBuilder.updateMaskFieldPaths(vararg fields: String) {
        fields.forEach { parameter("updateMask.fieldPaths", it) }
    }

    /** Igual que la sobrecarga vararg, para cuando los campos ya vienen en una colección. */
    fun HttpRequestBuilder.updateMaskFieldPaths(fields: Collection<String>) {
        fields.forEach { parameter("updateMask.fieldPaths", it) }
    }

    /** Ver [FirestoreParsers.extractDocId] — extraído para ser testable sin I/O. */
    fun extractDocId(resourceName: String, operation: String): String =
        FirestoreParsers.extractDocId(resourceName, operation)

    /**
     * Borra la cuenta de Firebase Auth actual (Google) vía el REST
     * de Identity Toolkit. Paso final e irreversible del flujo "eliminar
     * cuenta" (ver [org.taskhub.ui.models.GoogleAuthManager.deleteAccount]):
     * debe llamarse SOLO después de borrar los datos del usuario en
     * Firestore, porque una vez borrada la cuenta el idToken deja de ser
     * válido para cualquier escritura posterior.
     *
     * Endpoint: POST https://identitytoolkit.googleapis.com/v1/accounts:delete?key=API_KEY
     * Body: {"idToken": "..."}
     */
    suspend fun deleteFirebaseAccount() {
        val token = ensureAuth()
            ?: throw IllegalStateException("No hay sesión activa para eliminar la cuenta")
        try {
            client.post("https://identitytoolkit.googleapis.com/v1/accounts:delete?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(DeleteAccountRequest(token))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("FirestoreClient", "deleteFirebaseAccount: POST accounts:delete failed", e)
            throw redactApiKey(e)
        }
    }

    /**
     * Ktor mete la URL completa (con `?key=$apiKey`) en el mensaje de las
     * excepciones de timeout/conexión (`HttpRequestTimeoutException`,
     * `ConnectTimeoutException`), lanzadas ANTES de llegar a
     * [HttpResponseValidator] — sin este saneado, esa clave acababa en el
     * Snackbar de error de la UI (patrón `e.message ?: fallback`, extendido
     * por casi todos los `ScreenModel`) y en Logcat de producción cada vez
     * que la alta/refresco de sesión o el borrado de cuenta tenían un fallo
     * de red (panel 2026-09-11, IMPORTANTE).
     *
     * `internal` (no `private`): [FirestoreRepository.requestSignInWithIdp]
     * (login con Google, `accounts:signInWithIdp?key=...`) reutiliza esta
     * misma función en vez de duplicar el saneado (panel de expertos,
     * seguridad, 2026-09-12 — ese endpoint comparte el patrón `?key=$apiKey`
     * pero se había quedado sin cubrir cuando se introdujo esta redacción
     * para [refreshFirebaseToken]/[deleteFirebaseAccount]).
     */
    internal fun redactApiKey(e: Exception): Exception {
        val msg = e.message ?: return e
        if (!msg.contains(apiKey)) return e
        return IllegalStateException(msg.replace(apiKey, "***"), e)
    }

    companion object {
        /** Reintentos ante conflicto de concurrencia optimista (ver `addMemberPoints`/`addMemberAchievement`). */
        const val OPTIMISTIC_WRITE_MAX_RETRIES = 3
    }
}

/**
 * Recorre una colección de Firestore paginando con `pageToken` hasta
 * agotarla, en vez de una única petición sin `pageSize` — el REST de
 * Firestore no garantiza devolver la colección completa en una sola
 * respuesta, así que sin este bucle una colección que creciera por encima
 * del tamaño de página del servidor se leería truncada, en silencio, sin
 * ningún error visible (ver docs/review-panel-expertos-v3-2026-09-01.md,
 * hallazgo de Escalabilidad "sin paginación en ninguna colección").
 * `pageSize` por defecto (300) es generoso para los hogares reales de hoy —
 * en la práctica el bucle da una sola vuelta — pero deja de truncar si un
 * hogar crece. `configureAuth` recibe la misma lambda que ya usan los
 * call-sites (`withAuth()`), definida en el repo llamante
 * porque son extension functions con receptor [FirestoreClient].
 *
 * [limit]/[orderBy] acotan colecciones que crecen sin cota natural (chat,
 * notificaciones) y a las que se sondea periódicamente — sin esto, cada
 * ciclo de sondeo releía la colección COMPLETA aunque solo hicieran falta
 * los documentos más recientes (tarjeta kanban "Paginación
 * getMessages/getNotifications", 2026-09-13). `orderBy` (p.ej. `"createdAt
 * desc"`) es responsabilidad del caller: sin él, [limit] recortaría un
 * subconjunto en el orden arbitrario que devuelva el servidor, no
 * necesariamente el más reciente. `pageSize` se acota también a [limit]
 * cuando se indica, para no pedir más documentos de los que hacen falta.
 */
internal suspend fun HttpClient.listAllDocuments(
    url: String,
    pageSize: Int = 300,
    limit: Int? = null,
    orderBy: String? = null,
    configureAuth: suspend HttpRequestBuilder.() -> Unit
): List<FirestoreDocumentResponse> {
    val documents = mutableListOf<FirestoreDocumentResponse>()
    var pageToken: String? = null
    var page = 0
    val effectivePageSize = if (limit != null) minOf(pageSize, limit) else pageSize
    do {
        val response: FirestoreListResponse = retryTransientReadFailure {
            get(url) {
                configureAuth()
                parameter("pageSize", effectivePageSize)
                pageToken?.let { parameter("pageToken", it) }
                orderBy?.let { parameter("orderBy", it) }
            }.body()
        }
        documents += response.documents
        pageToken = response.nextPageToken
        page++
        // Tope de seguridad ante un backend/proxy que devolviera un
        // nextPageToken no-null indefinidamente (mismo criterio que el
        // `safety` de RecurrenceRules.nextOccurrence) — sin esto, ese
        // escenario dejaría la corrutina reintentando peticiones HTTP sin
        // fin. 200 páginas × 300 = 60.000 documentos, muy por encima de
        // cualquier hogar real.
        check(page < 200) { "listAllDocuments: demasiadas páginas para $url (posible bucle de paginación)" }
    } while (shouldFetchNextPage(pageToken, documents.size, limit))
    return if (limit != null) documents.take(limit) else documents
}

/**
 * Decide si [listAllDocuments] debe pedir una página más: solo si el
 * servidor aún ofrece [pageToken] Y (sin [limit], o con [documentsSoFar]
 * todavía por debajo de él). Extraída como función pura para poder
 * testearla sin I/O (mismo criterio que [isTransientReadFailure] en este
 * archivo).
 */
internal fun shouldFetchNextPage(pageToken: String?, documentsSoFar: Int, limit: Int?): Boolean {
    if (pageToken == null) return false
    if (limit != null && documentsSoFar >= limit) return false
    return true
}

/**
 * Reintenta [block] con backoff exponencial acotado (300ms, 600ms, ...) ante
 * fallos TRANSITORIOS — de transporte (timeout, DNS, conexión) o 5xx del
 * servidor — en operaciones de LECTURA (GET). Nunca debe envolver una
 * escritura (POST/PATCH/DELETE): sin `:commit`/transacciones, reintentar una
 * escritura que sí llegó al servidor pero cuya respuesta se perdió podría
 * duplicar la operación (tarjeta kanban "Retry/backoff idempotente", alcance
 * explícitamente limitado a lecturas). Un 4xx (permiso, documento
 * inexistente, argumento inválido) no es transitorio y se relanza sin
 * reintentar — reintentarlo no cambiaría el resultado.
 *
 * El backoff incluye jitter (±25% del delay base): sin él, varios
 * dispositivos del mismo hogar reconectando a la vez tras un corte de red
 * doméstico reintentarían todos en ráfaga sincronizada (panel v13/v14,
 * Red/offline, MENOR).
 */
internal suspend fun <T> retryTransientReadFailure(
    maxAttempts: Int = 3,
    initialDelayMillis: Long = 300,
    block: suspend () -> T
): T {
    var attempt = 0
    var delayMillis = initialDelayMillis
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("FirestoreClient", "retryTransientReadFailure: attempt ${attempt + 1}/$maxAttempts failed", e)
            attempt++
            if (attempt >= maxAttempts || !e.isTransientReadFailure()) throw e
            val jitterRange = (delayMillis / 4).coerceAtLeast(1)
            delay(delayMillis + Random.nextLong(-jitterRange, jitterRange + 1))
            delayMillis *= 2
        }
    }
}

/**
 * Ver [retryTransientReadFailure]. `HttpRequestTimeoutException` y
 * `ConnectTimeoutException` (ktor) heredan de [IOException] en todas las
 * plataformas objetivo, así que capturar [IOException] ya cubre timeouts,
 * DNS y conexión rechazada sin necesidad de listarlas una a una.
 *
 * Comprueba también [cause] (no solo el tipo de `this`): `redactApiKey`
 * (ver más arriba, `refreshFirebaseToken`/`deleteFirebaseAccount`) reenvuelve
 * un timeout/error de conexión como `IllegalStateException` para poder
 * censurar la API key embebida en el mensaje de esas excepciones de ktor —
 * eso perdía el tipo `IOException` original y hacía que
 * [ensureAuth] tratara un simple timeout de red como un refresh token
 * inválido (borrando la sesión de Google persistida). `redactApiKey`
 * conserva la excepción original como `cause`, así que basta con mirar un
 * nivel más para no perder esa transitoriedad.
 */
internal fun Exception.isTransientReadFailure(): Boolean = when (this) {
    is FirestoreException -> statusCode >= 500
    is IOException -> true
    else -> (cause as? Exception)?.isTransientReadFailure() ?: false
}

/**
 * Igual que `client.get(url) { ... }` pero con [retryTransientReadFailure] por
 * delante — para GET de documento único (las listas paginadas usan
 * [listAllDocuments], que ya reintenta cada página). Solo para lecturas, ver
 * [retryTransientReadFailure].
 */
internal suspend fun HttpClient.getWithRetry(
    url: String,
    block: suspend HttpRequestBuilder.() -> Unit = {}
): HttpResponse = retryTransientReadFailure { get(url) { block() } }

/**
 * Ejecuta [block] y devuelve [default] ante cualquier fallo NO fatal, pero
 * relanza [CancellationException] para no romper la cancelación cooperativa
 * de la corrutina. Compartida por los repos de dominio de `network/` (antes
 * duplicada 4 veces, una copia idéntica por archivo).
 */
internal suspend inline fun <T> orDefault(default: T, block: () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w("FirestoreClient", "orDefault: block failed, returning default value", e)
        default
    }
}
