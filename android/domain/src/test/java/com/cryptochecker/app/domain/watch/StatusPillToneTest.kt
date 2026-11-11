package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Test

/** Farbe der Statuszeile: «veraltet» in Bernstein, Fehler in Fehlerfarbe, offline neutral. Wie iOS. */
class StatusPillToneTest {
    private fun tone(none: Boolean = false, deviceOffline: Boolean = false, offline: Boolean = false, stale: Int = 0, failed: Int = 0) =
        StatusPillTone.of(none, deviceOffline, offline, stale, failed)

    @Test
    fun staleIsWarningNotError() {
        assertEquals(StatusPillTone.WARNING, tone(stale = 2, failed = 1))
        assertEquals(StatusPillTone.ERROR, tone(failed = 1))
        assertEquals(StatusPillTone.ERROR, tone(offline = true, stale = 3, failed = 3))
        assertEquals(StatusPillTone.NEUTRAL, tone(deviceOffline = true, offline = true, stale = 3))
        assertEquals(StatusPillTone.NEUTRAL, tone(none = true))
        assertEquals(StatusPillTone.NORMAL, tone())
    }
}
