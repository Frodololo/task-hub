/**
 * Puente commonMain para recibir el resultado del flujo de Google Sign-In
 * disparado por [launchGoogleSignIn] (expect/actual): cada plataforma
 * publica el resultado aquí en vez de devolverlo directamente, porque el
 * inicio de sesión nativo es asíncrono y basado en callbacks/Activity result.
 */
package org.taskhub.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Resultado del flujo de Google Sign-In publicado en [GoogleSignInResultHolder].
 * Sustituye al contrato previo basado en `String?` (`null` = en curso, `""` =
 * cancelado/sin token, cualquier otro valor = idToken) por un tipo explícito.
 */
sealed class GoogleSignInResult {
    /** En curso/no iniciado — estado inicial y tras [GoogleSignInResultHolder.reset]. */
    data object Loading : GoogleSignInResult()

    /** Login correcto: [token] es el idToken de Google. */
    data class Success(val token: String) : GoogleSignInResult()

    /** Cancelado por el usuario o sin token (no-op/error genérico). */
    data object Cancelled : GoogleSignInResult()
}

/**
 * Contenedor multiplataforma del resultado de Google Sign-In.
 *
 * Tras completarse [launchGoogleSignIn] (Android) o no-opear (otras
 * plataformas), el resultado se entrega aquí para que el código de
 * commonMain (p.ej. GoogleAuthManager) pueda observarlo vía [result].
 */
object GoogleSignInResultHolder {
    private val _result = MutableStateFlow<GoogleSignInResult>(GoogleSignInResult.Loading)
    val result: StateFlow<GoogleSignInResult> = _result.asStateFlow()

    /**
     * Publica el resultado del intento de sign-in. Mantiene el contrato previo
     * basado en `String?` para no tocar los 8 call-sites de plataforma (`null`
     * = en curso, `""` = cancelado/sin token, cualquier otro valor = idToken)
     * — solo cambia la representación interna que observa [result].
     */
    fun setResult(token: String?) {
        _result.value = when {
            token == null -> GoogleSignInResult.Loading
            token.isEmpty() -> GoogleSignInResult.Cancelled
            else -> GoogleSignInResult.Success(token)
        }
    }

    /** Vuelve al estado "en curso/no iniciado", para lanzar un nuevo intento desde cero. */
    fun reset() {
        _result.value = GoogleSignInResult.Loading
    }
}
