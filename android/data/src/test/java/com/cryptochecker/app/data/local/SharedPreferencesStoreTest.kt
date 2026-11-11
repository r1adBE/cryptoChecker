package com.cryptochecker.app.data.local

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schlüssel-Wert-Ablage über SharedPreferences ([SharedPreferencesStore]): Lesen mit Standardwerten, Schreiben und
 * Entfernen in einem Zug, [SharedPreferencesStore.all] als Kopie.
 */
class SharedPreferencesStoreTest {

    private val prefs = MemoryPrefs()
    private val store = SharedPreferencesStore(prefs)

    @Test
    fun missingValuesGiveTheDefaults() {
        assertEquals(7, store.getInt("i", 7))
        assertEquals(8L, store.getLong("l", 8L))
        assertTrue(store.getBoolean("b", true))
        assertNull(store.getString("s"))
        assertFalse(store.contains("i"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun editWritesAllValuesTogether() {
        store.edit {
            putInt("i", 1)
            putLong("l", 2L)
            putBoolean("b", true)
            putString("s", "Text")
        }
        assertEquals(1, store.getInt("i", 0))
        assertEquals(2L, store.getLong("l", 0L))
        assertTrue(store.getBoolean("b", false))
        assertEquals("Text", store.getString("s"))
        assertTrue(store.contains("s"))
        assertEquals(mapOf("i" to 1, "l" to 2L, "b" to true, "s" to "Text"), store.all())
        assertEquals(1, prefs.applies)
    }

    @Test
    fun removeAndNullStringDeleteValues() {
        store.edit {
            putInt("a", 1)
            putString("s", "x")
        }
        store.edit {
            remove("a")
            putString("s", null)
        }
        assertFalse(store.contains("a"))
        assertNull(store.getString("s"))
        assertEquals(5, store.getInt("a", 5))
    }

    @Test
    fun allIsACopy() {
        store.edit { putInt("a", 1) }
        val snapshot = store.all()
        store.edit { putInt("b", 2) }
        assertEquals(setOf("a"), snapshot.keys)
        assertEquals(setOf("a", "b"), store.all().keys)
    }

    /** SharedPreferences im Speicher; null-Wert entfernt den Schlüssel wie auf dem Gerät. */
    private class MemoryPrefs : SharedPreferences {
        private val values = HashMap<String, Any?>()
        var applies = 0

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            private var clear = false
            override fun putString(key: String, value: String?): SharedPreferences.Editor { changes[key] = value; return this }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { changes[key] = values; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { changes[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { changes[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { changes[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { changes[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { removed += key; return this }
            override fun clear(): SharedPreferences.Editor { clear = true; return this }
            override fun commit(): Boolean {
                if (clear) values.clear()
                removed.forEach { values.remove(it) }
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
            override fun apply() {
                applies++
                commit()
            }
        }
    }
}
