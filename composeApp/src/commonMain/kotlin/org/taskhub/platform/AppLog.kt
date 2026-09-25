/**
 * Logging estructurado multiplatform (Napier) — sustituye los `println`/
 * `Log.d`/`Log.w` sueltos repartidos por la app (Android, JVM desktop, web)
 * por llamadas con tag por módulo/pantalla, fáciles de filtrar.
 */
package org.taskhub.platform

import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import kotlin.concurrent.Volatile

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
