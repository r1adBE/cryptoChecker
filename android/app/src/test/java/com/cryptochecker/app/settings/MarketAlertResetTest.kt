package com.cryptochecker.app.settings

import android.content.SharedPreferences
import com.cryptochecker.app.domain.gas.GasAlertChecker
import com.cryptochecker.app.domain.gas.GasAlertLogic
import com.cryptochecker.app.domain.market.IndicatorAlertKind
import com.cryptochecker.app.domain.market.IndicatorAlertRules
import com.cryptochecker.app.domain.market.MarketNoticeGate
import com.cryptochecker.app.work.ZoneCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markt-Meldung aus- und mit derselben Grenze wieder eingeschaltet, ohne dass dazwischen geprüft wurde (Arbeit
 * abgesagt): [MarketAlertReset] vergisst beim Ausschalten den gemerkten Stand, sodass die nächste Prüfung neu bewertet.
 * Wie iOS `MarketAlertResetTests`.
 */
class MarketAlertResetTest {

    @Test
    fun `fear and greed off then on with the same bound notifies again`() {
        val prefs = FakePrefs()
        // Gemeldet bei 20 ≤ 25, Stand gemerkt
        prefs.edit().putInt(ZoneCheckWorker.KEY_FNG, 20).putInt(ZoneCheckWorker.KEY_FNG_BELOW, 25)
            .putInt(ZoneCheckWorker.KEY_FNG_ABOVE, 0).apply()
        // Ohne Zurücksetzen: gleiche Grenze, alter Stand → stumm
        val before = MarketNoticeGate.fearGreed(18, 20, 25, 0, true, MarketNoticeGate.boundChanged(25, 25), false)
        assertEquals(MarketNoticeGate.Step.STORE, before.step)

        MarketAlertReset.fearGreedOff(prefs, below = true)
        assertEquals(0, prefs.getInt(ZoneCheckWorker.KEY_FNG_BELOW, -1))
        val changed = MarketNoticeGate.boundChanged(prefs.getInt(ZoneCheckWorker.KEY_FNG_BELOW, -1), 25)
        val after = MarketNoticeGate.fearGreed(18, prefs.getInt(ZoneCheckWorker.KEY_FNG, 50), 25, 0, true, changed, false)
        assertEquals(MarketNoticeGate.Step.NOTIFY, after.step)
        assertEquals(MarketNoticeGate.FearGreedKind.BELOW, after.kind)
        // Die andere Grenze bleibt unberührt
        assertEquals(0, prefs.getInt(ZoneCheckWorker.KEY_FNG_ABOVE, -1))
    }

    @Test
    fun `fear and greed without stored state writes nothing`() {
        val prefs = FakePrefs()
        MarketAlertReset.fearGreedOff(prefs, below = false)
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `gas off then on with the same bound is armed again`() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean(GasAlertChecker.KEY_ETH_ARMED, false)
            .putLong(GasAlertChecker.KEY_ETH_THRESHOLD, 5.0.toRawBits()).apply()
        MarketAlertReset.gasOff(prefs, eth = true)
        val stored = Double.fromBits(prefs.getLong(GasAlertChecker.KEY_ETH_THRESHOLD, -1))
        assertEquals(0.0, stored, 0.0)
        assertTrue(GasAlertLogic.armedFor(prefs.getBoolean(GasAlertChecker.KEY_ETH_ARMED, true), stored, 5.0))
        // Bitcoin unberührt
        assertFalse(prefs.contains(GasAlertChecker.KEY_BTC_THRESHOLD))
    }

    @Test
    fun `gas off waits for a running check instead of being overwritten by it`() = runBlocking {
        val prefs = FakePrefs()
        prefs.edit().putBoolean(GasAlertChecker.KEY_ETH_ARMED, false)
            .putLong(GasAlertChecker.KEY_ETH_THRESHOLD, 5.0.toRawBits()).apply()
        // Laufende Prüfung hält die Sperre …
        GasAlertChecker.lock.lock()
        val reset = launch(Dispatchers.Default) { MarketAlertReset.gasOffLocked(prefs, eth = true) }
        delay(50)
        assertFalse(reset.isCompleted)
        // … und schreibt am Ende die Grenze, mit der sie begann
        prefs.edit().putLong(GasAlertChecker.KEY_ETH_THRESHOLD, 5.0.toRawBits()).apply()
        GasAlertChecker.lock.unlock()
        reset.join()
        // Das «Aus» gilt danach: Wiedereinschalten mit derselben Grenze ist scharf
        val stored = Double.fromBits(prefs.getLong(GasAlertChecker.KEY_ETH_THRESHOLD, -1))
        assertEquals(0.0, stored, 0.0)
        assertTrue(GasAlertLogic.armedFor(prefs.getBoolean(GasAlertChecker.KEY_ETH_ARMED, true), stored, 5.0))
    }

    @Test
    fun `indicator off forgets crossed state and its bound`() {
        val prefs = FakePrefs()
        val kind = IndicatorAlertKind.MAYER_ABOVE
        prefs.edit().putBoolean(ZoneCheckWorker.crossedKey(kind), true).putInt(ZoneCheckWorker.thresholdKey(kind), 240).apply()
        MarketAlertReset.indicatorOff(prefs, kind)
        assertFalse(prefs.contains(ZoneCheckWorker.crossedKey(kind)))
        assertFalse(prefs.contains(ZoneCheckWorker.thresholdKey(kind)))
        val was = IndicatorAlertRules.wasCrossed(prefs.getBoolean(ZoneCheckWorker.crossedKey(kind), false), null, 240)
        assertTrue(IndicatorAlertRules.decide(kind, 2.5, 2.4, was).notify)
    }

    @Test
    fun `fake prefs remove works`() {
        val prefs = FakePrefs()
        prefs.edit().putInt("a", 1).apply()
        prefs.edit().remove("a").apply()
        assertNull(prefs.all["a"])
    }
}

/** SharedPreferences im Speicher (Einheitstests ohne Gerät). */
private class FakePrefs : SharedPreferences {
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
