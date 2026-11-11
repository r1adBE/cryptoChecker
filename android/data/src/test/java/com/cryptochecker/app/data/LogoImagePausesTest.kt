package com.cryptochecker.app.data

import android.content.SharedPreferences
import com.cryptochecker.app.domain.logos.CoinLogoRetry
import com.cryptochecker.app.domain.logos.CoinLogos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pausen der Bild-Server und fehlgeschlagene Coin-Logos ([LogoImagePauses]) mit Einstellungen im Speicher: gespeicherte
 * Pausen gelten nach einem Neustart, laufende Pausen werden nicht verlängert, alte Fehlschläge verfallen.
 */
class LogoImagePausesTest {

    private val prefs = MemoryPrefs()
    private val pauses = LogoImagePauses { prefs }

    @Test
    fun savedPausesSurviveARestart() {
        val now = System.currentTimeMillis()
        val saved = mapOf(
            "assets.coingecko.com" to now + 10 * 60_000L,
            "alt.example" to now - 1_000L,
            "weit.example" to now + CoinLogoRetry.IMAGE_PAUSE_MAX_MILLIS + 60_000L,
        )
        prefs.edit().putString(LogoImagePauses.KEY_IMAGE_PAUSES, CoinLogoRetry.encodePauses(saved)).apply()
        assertTrue(pauses.isPaused("assets.coingecko.com"))
        // Abgelaufen bzw. unplausibel weit voraus: keine Pause
        assertFalse(pauses.isPaused("alt.example"))
        assertFalse(pauses.isPaused("weit.example"))
        assertFalse(pauses.isPaused("unbekannt.example"))
    }

    @Test
    fun pauseHostIsStoredAndNotExtended() {
        pauses.pauseHost("img.example", 120L)
        assertTrue(pauses.isPaused("img.example"))
        val stored = prefs.getString(LogoImagePauses.KEY_IMAGE_PAUSES, null)
        val until = CoinLogoRetry.decodePauses(stored, System.currentTimeMillis())["img.example"]
        assertTrue("gespeichert: $stored", until != null)
        // Parallele Abrufe melden dasselbe: die laufende Pause bleibt, wie sie war
        pauses.pauseHost("img.example", 3_600L)
        assertEquals(stored, prefs.getString(LogoImagePauses.KEY_IMAGE_PAUSES, null))
    }

    @Test
    fun emptyHostIsIgnored() {
        pauses.pauseHost("", 60L)
        assertFalse(prefs.contains(LogoImagePauses.KEY_IMAGE_PAUSES))
        assertFalse(pauses.isPaused(""))
    }

    @Test
    fun shortestPauseOfTheJobHosts() {
        val jobs = listOf("BTC" to "https://a.example/btc.png", "ETH" to "https://b.example/eth.png", "X" to "http://a.example/x")
        assertNull(pauses.shortestPauseMillis(jobs))
        pauses.pauseHost("a.example", 120L)
        pauses.pauseHost("b.example", null)
        val shortest = pauses.shortestPauseMillis(jobs)!!
        // «Retry-After» 120 s gegen die Standardpause (15 min): die kürzere zählt
        assertTrue("kürzeste: $shortest", shortest in 1L..120_000L)
        assertNull(pauses.shortestPauseMillis(listOf("Y" to "https://c.example/y.png")))
        assertNull(pauses.shortestPauseMillis(emptyList()))
    }

    @Test
    fun failuresRoundTripAndExpire() {
        val now = System.currentTimeMillis()
        val failures = mapOf("bitcoin" to now - 1_000L, "ethereum" to now - 2_000L)
        pauses.saveFailures(failures)
        assertEquals(failures, pauses.loadFailures(now))
        // Älter als die Frist: nicht mehr als fehlgeschlagen
        assertEquals(mapOf("bitcoin" to now - 1_000L), pauses.loadFailures(now + CoinLogos.FAILURE_TTL_MILLIS - 1_500L))
        assertTrue(pauses.loadFailures(now + CoinLogos.FAILURE_TTL_MILLIS + 10_000L).isEmpty())
    }

    @Test
    fun brokenFailureEntriesAreSkipped() {
        val now = System.currentTimeMillis()
        prefs.edit().putString(LogoImagePauses.KEY_FAILED, "ok:${now - 5};:${now - 5};ohneZeit;kaputt:abc;;").apply()
        assertEquals(mapOf("ok" to now - 5), pauses.loadFailures(now))
        assertTrue(LogoImagePauses { MemoryPrefs() }.loadFailures(now).isEmpty())
    }

    /** SharedPreferences im Speicher (Einheitstests ohne Gerät). */
    private class MemoryPrefs : SharedPreferences {
        private val values = HashMap<String, Any?>()

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
                values.putAll(changes)
                return true
            }
            override fun apply() { commit() }
        }
    }
}
