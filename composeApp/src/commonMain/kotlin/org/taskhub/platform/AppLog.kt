/**
 * Logging estructurado multiplatform (Napier) — sustituye los `println`/
 * `Log.d`/`Log.w` sueltos repartidos por la app (Android, JVM desktop, web)
 * por llamadas con tag por módulo/pantalla, fáciles de filtrar.
 */
package org.taskhub.platform

import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException

/**
 * [d] solo emite en debug ([DebugFlags.isEnabled]) — en release queda
 * silenciado, igual que los `println(...)` que sustituye (ya condicionados a
 * `DebugFlags.isEnabled`). [w]/[e] emiten siempre, también en release: un
 * fallo real debe dejar rastro en producción, no solo en debug.
 */
object AppLog {
    @Volatile
    private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        initialized = true
        Napier.base(DebugAntilog())
    }

    fun d(tag: String, message: String) {
        if (!DebugFlags.isEnabled) return
        ensureInit()
        Napier.d(tag = tag, message = message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        ensureInit()
        Napier.w(tag = tag, message = message, throwable = throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        ensureInit()
        Napier.e(tag = tag, message = message, throwable = throwable)
    }
}

/**
 * Ejecuta [block] y devuelve [default] ante cualquier fallo NO fatal, pero
 * relanza [CancellationException] para no romper la cancelación cooperativa
 * de la corrutina. Sustituye los `try { } catch (e: CancellationException) {
 * throw e } catch (_: Exception) { }` repetidos a mano por `network/` y
 * `ui/models/`.
 */
suspend inline fun <T> bestEffort(default: T, tag: String = "bestEffort", block: () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(tag, "block failed, returning default", e)
        default
    }
}
