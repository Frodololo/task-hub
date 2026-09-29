package org.taskhub.ui.components

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [todayStartEpoch] extraída de [org.taskhub.ui.screens.TaskListScreen]
 * (panel v21, pendiente técnico) — antes solo vivía dentro del `@Composable`,
 * sin forma de testearla.
 */
class TaskListRulesTest {

    @Test
    fun todayStartEpoch_isMidnightLocalTime() {
        val tz = TimeZone.currentSystemDefault()
        val result = Instant.fromEpochMilliseconds(todayStartEpoch()).toLocalDateTime(tz)

        assertEquals(0, result.hour)
        assertEquals(0, result.minute)
        assertEquals(0, result.second)
    }

    @Test
    fun todayStartEpoch_isSameCalendarDayAsNow() {
        val tz = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(tz).date
        val result = Instant.fromEpochMilliseconds(todayStartEpoch()).toLocalDateTime(tz)

        assertEquals(today, result.date)
    }

    @Test
    fun todayStartEpoch_isNotInTheFuture() {
        assertTrue(todayStartEpoch() <= Clock.System.now().toEpochMilliseconds())
    }
}
