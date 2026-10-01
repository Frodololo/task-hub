package org.taskhub.ui.models

import org.taskhub.network.MemberRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Test de cobertura para [appreciateErrorKey]/[donateErrorKey] (funciones de
 * paquete en `MemberScreenModel.kt`, no métodos del ScreenModel) — mismo
 * patrón que [StatsScreenModelTest] para [computeStats]: verifica que cada
 * valor del enum de motivo de fallo mapea a la clave de i18n correcta, en
 * particular que ninguno de los dos "cae" en un motivo equivocado (p.ej.
 * `AMOUNT_EXCEEDS_LIMIT`, añadido en la ronda 09-13, no debe traducirse como
 * `TRANSFER_FAILED`).
 */
class MemberScreenModelTest {

    @Test
    fun `appreciateErrorKey traduce cada motivo a su clave de i18n`() {
        assertEquals("transfer_error_self", appreciateErrorKey(MemberRepository.AppreciateErrorReason.SELF))
        assertEquals("transfer_error_invalid_amount", appreciateErrorKey(MemberRepository.AppreciateErrorReason.INVALID_AMOUNT))
        assertEquals("appreciate_error_limit", appreciateErrorKey(MemberRepository.AppreciateErrorReason.LIMIT_EXCEEDED))
        assertEquals("transfer_error_member_not_found", appreciateErrorKey(MemberRepository.AppreciateErrorReason.MEMBER_NOT_FOUND))
        assertEquals("transfer_error_failed", appreciateErrorKey(MemberRepository.AppreciateErrorReason.TRANSFER_FAILED))
        assertEquals("transfer_error_uncertain", appreciateErrorKey(MemberRepository.AppreciateErrorReason.UNCERTAIN))
    }

    @Test
    fun `donateErrorKey traduce cada motivo a su clave de i18n`() {
        assertEquals("transfer_error_self", donateErrorKey(MemberRepository.DonateErrorReason.SELF))
        assertEquals("transfer_error_invalid_amount", donateErrorKey(MemberRepository.DonateErrorReason.INVALID_AMOUNT))
        assertEquals("donate_error_insufficient_balance", donateErrorKey(MemberRepository.DonateErrorReason.INSUFFICIENT_BALANCE))
        assertEquals("transfer_error_member_not_found", donateErrorKey(MemberRepository.DonateErrorReason.MEMBER_NOT_FOUND))
        assertEquals("transfer_error_failed", donateErrorKey(MemberRepository.DonateErrorReason.TRANSFER_FAILED))
        assertEquals("donate_error_exceeds_limit", donateErrorKey(MemberRepository.DonateErrorReason.AMOUNT_EXCEEDS_LIMIT))
        assertEquals("transfer_error_uncertain", donateErrorKey(MemberRepository.DonateErrorReason.UNCERTAIN))
    }

    @Test
    fun `donateErrorKey distingue AMOUNT_EXCEEDS_LIMIT de TRANSFER_FAILED`() {
        val exceedsLimitKey = donateErrorKey(MemberRepository.DonateErrorReason.AMOUNT_EXCEEDS_LIMIT)
        val transferFailedKey = donateErrorKey(MemberRepository.DonateErrorReason.TRANSFER_FAILED)

        assertEquals(true, exceedsLimitKey != transferFailedKey)
    }
}
