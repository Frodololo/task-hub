package org.taskhub.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [bestEffort] sustituye los `try { } catch (e: CancellationException) { throw
 * e } catch (_: Exception) { }` repetidos a mano por `network/` y
 * `ui/models/` — este test cubre su contrato: éxito pasa el resultado, un
 * fallo NO fatal devuelve [default] en silencio, pero [CancellationException]
 * (cancelación cooperativa de la corrutina) se relanza siempre, nunca se
 * traga.
 */
class AppLogTest {

    @Test
    fun bestEffort_exito_devuelveElResultadoDelBlock() = runTest {
        val result = bestEffort(default = -1) { 42 }

        assertEquals(42, result)
    }

    @Test
    fun bestEffort_fallaConExcepcionNoFatal_devuelveElDefaultEnSilencio() = runTest {
        val result = bestEffort(default = "fallback") {
            throw RuntimeException("boom")
        }

        assertEquals("fallback", result)
    }

    @Test
    fun bestEffort_cancellationException_seRelanzaEnVezDeDevolverElDefault() = runTest {
        assertFailsWith<CancellationException> {
            bestEffort(default = "no debería verse") {
                throw CancellationException("corrutina cancelada")
            }
        }
    }
}
