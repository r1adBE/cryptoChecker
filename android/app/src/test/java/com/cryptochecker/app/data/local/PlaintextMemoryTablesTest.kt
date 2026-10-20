package com.cryptochecker.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kopiert werden nur die eigenen Tabellen, sortiert; Verwaltung legt Room im Speicher selbst an. */
class PlaintextMemoryTablesTest {

    @Test
    fun skipsBookkeepingTables() {
        assertEquals(
            listOf("alarms", "watch"),
            PlaintextMemoryTables.copyable(listOf("watch", "android_metadata", "sqlite_sequence", "room_master_table", "alarms")),
        )
    }
}
