/**
 * Flujo OAuth 2.0 authorization-code + PKCE para Google Sign-In en iOS, sin
 * SDK nativo de Google (mismo motivo que desktop, ver
 * [GoogleDesktopSignInHelper]): el flujo implícito (`response_type=id_token`)
 * que se usaba antes fue rechazado por Google con `Error 400:
 * unsupported_response_type` — Google ya no permite el implicit flow para
 * clientes OAuth de tipo iOS, hay que canjear un `code` por el token.
 *
 * A diferencia de desktop (que puede levantar un servidor loopback local y
 * esperar bloqueando en el mismo `suspend fun`), en iOS el callback llega en
 * una invocación de proceso completamente distinta: Safari se abre
 * ([launchGoogleSignIn] en `Platform.ios.kt`, que solo lanza la URL y
 * retorna), la app pasa a segundo plano, y el `code` llega más tarde vía el
 * URL scheme ([org.taskhub.platform.GOOGLE_IOS_REVERSED_CLIENT_ID]) que
 * `ContentView.swift` (`onOpenURL`) reenvía a [handleCallback]. Por eso el
 * `code_verifier` se guarda aquí como estado pendiente entre ambas llamadas,
 * en vez de vivir en la pila de una sola función como en desktop.
 */
package org.taskhub.platform

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import platform.CoreCrypto.CC_SHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

object GoogleIosSignInHelper {
    private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

    /** URL scheme de callback: reverse-DNS del client ID iOS, registrado en Info.plist. */
    const val REDIRECT_URI = "$GOOGLE_IOS_REVERSED_CLIENT_ID:/oauth2redirect"

    // Sin motor explícito: igual que FirestoreClient.kt, Ktor lo autodetecta
    // de la única dependencia de motor en el classpath de la plataforma
    // (ktor-client-darwin en iosMain). Especificarlo a mano (HttpClient(Darwin) {...})
    // causaba un ThrowIrLinkageError en tiempo de ejecución dentro de
    // io.ktor.client.engine.darwin — la vía implícita es la que ya funciona
    // en iOS para todas las llamadas de Firestore.
    private val client by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
        }
    }

    /**
     * `code_verifier` del intento en curso — un único flujo a la vez, misma
     * garantía que la guarda de reentrancia de
     * [org.taskhub.ui.models.GoogleAuthManager.signIn].
     */
    @Volatile
    private var pendingCodeVerifier: String? = null

    /** Construye la URL de autorización (authorization-code + PKCE) y guarda el `code_verifier` para el canje posterior en [handleCallback]. */
    @OptIn(ExperimentalEncodingApi::class)
    fun buildAuthorizationUrl(): String {
        val codeVerifier = randomUrlSafeString(32)
        pendingCodeVerifier = codeVerifier
        val codeChallenge = codeChallengeFor(codeVerifier)
        return "$AUTH_ENDPOINT?client_id=$GOOGLE_IOS_CLIENT_ID" +
            "&redirect_uri=$REDIRECT_URI" +
            "&response_type=code" +
            "&scope=openid%20email%20profile" +
            "&code_challenge=$codeChallenge" +
            "&code_challenge_method=S256"
    }

    /** Descarta el `code_verifier` pendiente sin canjear nada (p.ej. Safari no pudo abrirse). */
    fun clearPending() {
        pendingCodeVerifier = null
    }

    /** Scope dedicado al canje código-por-token, fuera de la pila de `onOpenURL`. */
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Punto de entrada llamado directamente desde `ContentView.swift`
     * (`onOpenURL`) con la URL completa de callback — `object` de Kotlin, así
     * que en Swift se invoca como `GoogleIosSignInHelper.shared.processCallback(callbackUrl:)`
     * (mismo patrón ya usado para [GoogleSignInResultHolder]). No-suspend a
     * propósito: lanza el canje en segundo plano y publica el resultado en
     * [GoogleSignInResultHolder] — `""` tanto si el usuario canceló como si
     * el canje falla, para no dejar el flujo colgado en SigningIn.
     */
    fun processCallback(callbackUrl: String) {
        callbackScope.launch {
            GoogleSignInResultHolder.setResult(handleCallback(callbackUrl) ?: "")
        }
    }

    /**
     * Extrae `code` de la query string de [callbackUrl] y lo canjea por un
     * id_token. Devuelve `null` si no hay un `code_verifier` pendiente
     * (callback inesperado), Google no incluyó `code` (usuario canceló:
     * `error=access_denied`), o falla el canje (red, respuesta inválida) —
     * nunca lanza, mismo contrato que [GoogleDesktopSignInHelper.signIn].
     */
    private suspend fun handleCallback(callbackUrl: String): String? {
        val codeVerifier = pendingCodeVerifier ?: return null
        pendingCodeVerifier = null
        val code = extractQueryParam(callbackUrl, "code") ?: return null
        return try {
            exchangeCodeForIdToken(code, codeVerifier)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** POST a [TOKEN_ENDPOINT] — canjea el `code` por tokens usando PKCE. Sin `client_secret`: los clientes OAuth de tipo iOS son "públicos" (RFC 8252), Google no emite secreto para ellos. */
    private suspend fun exchangeCodeForIdToken(code: String, codeVerifier: String): String? {
        val response: TokenResponse = client.submitForm(
            url = TOKEN_ENDPOINT,
            formParameters = Parameters.build {
                append("code", code)
                append("client_id", GOOGLE_IOS_CLIENT_ID)
                append("redirect_uri", REDIRECT_URI)
                append("grant_type", "authorization_code")
                append("code_verifier", codeVerifier)
            }
        ).body()
        return response.id_token?.takeIf { it.isNotBlank() }
    }

    @Serializable
    private data class TokenResponse(
        val id_token: String? = null,
        val error: String? = null,
        val error_description: String? = null
    )

    /** Extrae el valor de [name] de la query string de [url] (una sola invocación por nombre, sin decodificar `+`: Google no lo usa en sus parámetros de callback). */
    private fun extractQueryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        for (pair in query.substringBefore('#').split("&")) {
            val idx = pair.indexOf('=')
            if (idx < 0) continue
            if (pair.substring(0, idx) == name) {
                return decodeUrlComponent(pair.substring(idx + 1))
            }
        }
        return null
    }

    private fun decodeUrlComponent(value: String): String {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                bytes.add(hex.toInt(16).toByte())
                i += 3
            } else {
                bytes.add(c.code.toByte())
                i += 1
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    @OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
    private fun randomUrlSafeString(byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        val status = SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), bytes.refTo(0))
        check(status == 0) { "SecRandomCopyBytes falló con status $status" }
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
    }

    /** PKCE `code_challenge` S256: base64url(SHA-256(code_verifier)) — RFC 7636, mismo cálculo que [GoogleDesktopSignInHelper.codeChallengeFor]. */
    @OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class, ExperimentalUnsignedTypes::class)
    private fun codeChallengeFor(codeVerifier: String): String {
        val input = codeVerifier.encodeToByteArray().toUByteArray()
        val digest = UByteArray(32) // CC_SHA256_DIGEST_LENGTH
        CC_SHA256(input.refTo(0), input.size.toUInt(), digest.refTo(0))
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(digest.toByteArray())
    }
}
