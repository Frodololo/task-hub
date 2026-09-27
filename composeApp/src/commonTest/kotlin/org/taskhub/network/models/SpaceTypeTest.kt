package org.taskhub.network.models

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SpaceType.firestoreValue] y [spaceTypeFromFirestoreValue] se mantienen a
 * mano en sincronía con el `@SerialName` del enum (ver KDoc de ambos en
 * `DTOs.kt`) — este test convierte un futuro olvido al añadir un 5º valor en
 * un fallo de CI inmediato en vez de una reclasificación silenciosa a HOME.
 */
class SpaceTypeTest {

    @Test
    fun firestoreValue_roundTripsThroughSpaceTypeFromFirestoreValue() {
        for (type in SpaceType.entries) {
            assertEquals(type, spaceTypeFromFirestoreValue(type.firestoreValue))
        }
    }

    @Test
    fun spaceTypeFromFirestoreValue_nullOrUnknown_fallsBackToHome() {
        assertEquals(SpaceType.HOME, spaceTypeFromFirestoreValue(null))
        assertEquals(SpaceType.HOME, spaceTypeFromFirestoreValue(""))
        assertEquals(SpaceType.HOME, spaceTypeFromFirestoreValue("unknown"))
    }
}
