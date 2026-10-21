package com.cryptochecker.app.ui.features.alarms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AlarmUserChange]: Ausschalten, Bearbeiten und Löschen entfernen die gemeldete Mitteilung — ihr «1 h später» schaltete den
 * Alarm sonst wieder ein, obwohl der Nutzer ihn eben ausgeschaltet hat.
 */
class AlarmUserChangeTest {

    @Test
    fun disablingAndEditingRemoveTheNotification() {
        assertTrue(AlarmUserChange.cancelsNotification(enabled = false, storedKept = false))
        // Bearbeitet (Bedingung, Ton …)
        assertTrue(AlarmUserChange.cancelsNotification(enabled = null, storedKept = false))
        // Einschalten lässt eine Mitteilung stehen
        assertFalse(AlarmUserChange.cancelsNotification(enabled = true, storedKept = false))
        // Merkliste nur leer im Arbeitsspeicher: Die Id kann die eines gespeicherten Alarms sein
        assertFalse(AlarmUserChange.cancelsNotification(enabled = false, storedKept = true))
    }

    @Test
    fun deletingRemovesNotificationAndLogUnlessOnlyInMemory() {
        assertEquals(AlarmUserChange.Cleanup(cancelNotification = true, removeLog = true), AlarmUserChange.afterDelete(false, false))
        assertEquals(AlarmUserChange.Cleanup(cancelNotification = false, removeLog = false), AlarmUserChange.afterDelete(true, false))
        // Kopie einer alten unverschlüsselten Datei: Alarm kommt nach dem Neustart zurück, sein Protokoll bleibt
        assertEquals(AlarmUserChange.Cleanup(cancelNotification = true, removeLog = false), AlarmUserChange.afterDelete(false, true))
    }
}
