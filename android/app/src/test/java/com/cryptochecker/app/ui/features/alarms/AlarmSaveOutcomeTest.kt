package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.domain.model.AlarmCondition
import com.cryptochecker.app.domain.model.AlarmEntity
import com.cryptochecker.app.domain.watch.AlarmSaveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ergebnis des Alarm-Speicherns im Editor ([savedOr]): «Paar voll» meldet nur [AlarmSaveResult.Full]; «Abbrechen» in der
 * Rückfrage bzw. gesperrt ([AlarmSaveResult.Refused]) speichert nichts und meldet still nichts.
 */
class AlarmSaveOutcomeTest {

    private val alarm = AlarmEntity(id = 7, watchId = 1, condition = AlarmCondition.PRICE_ABOVE, threshold = 70_000.0)

    @Test
    fun savedGivesTheAlarmWithoutTheLimitHint() {
        var limit = 0
        assertEquals(alarm, AlarmSaveResult.Saved(alarm).savedOr { limit++ })
        assertEquals(0, limit)
    }

    @Test
    fun onlyFullReportsTheLimit() {
        var limit = 0
        assertNull(AlarmSaveResult.Full.savedOr { limit++ })
        assertEquals(1, limit)
    }

    @Test
    fun refusedOrCancelledStaysSilent() {
        var limit = 0
        assertNull(AlarmSaveResult.Refused.savedOr { limit++ })
        assertEquals(0, limit)
    }
}
