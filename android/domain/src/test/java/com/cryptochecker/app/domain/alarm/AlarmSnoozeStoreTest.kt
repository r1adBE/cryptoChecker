package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.util.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «1 h später»-Pausen nach dem Wiederherstellen: [AlarmSnoozeStore.clear] entfernt alle (Ids der Sicherung können sich
 * mit bisherigen decken), andere Einträge der Datei bleiben. Wie iOS `AlarmSnooze.clearAll` (`AlarmSnoozeClearTests`).
 */
class AlarmSnoozeStoreTest {

    private class MemoryStore : KeyValueStore {
        val values = HashMap<String, Any?>()
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun getString(key: String): String? = values[key] as? String
        override fun contains(key: String) = key in values
        override fun all(): Map<String, Any?> = HashMap(values)
        override fun edit(block: KeyValueStore.Editor.() -> Unit) {
            object : KeyValueStore.Editor {
                override fun putInt(key: String, value: Int) {
                    values[key] = value
                }
                override fun putLong(key: String, value: Long) {
                    values[key] = value
                }
                override fun putBoolean(key: String, value: Boolean) {
                    values[key] = value
                }
                override fun putString(key: String, value: String?) {
                    values[key] = value
                }
                override fun remove(key: String) {
                    values.remove(key)
                }
            }.block()
        }
    }

    @Test
    fun clearRemovesEverySnooze() {
        val memory = MemoryStore()
        val store = AlarmSnoozeStore { memory }
        val now = 1_800_000_000_000L
        store.snooze(1, now)
        store.snooze(7, now)
        memory.values["other"] = 3
        assertEquals(AlarmSnooze.snoozeUntil(now), store.snoozeUntil(1))
        store.clear()
        assertNull(store.snoozeUntil(1))
        assertNull(store.snoozeUntil(7))
        assertEquals(3, memory.values["other"])
    }
}
