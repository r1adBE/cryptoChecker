package com.cryptochecker.app.util

import com.cryptochecker.app.util.A11ySpeech.Age
import com.cryptochecker.app.util.A11ySpeech.Freshness
import com.cryptochecker.app.util.A11ySpeech.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Screenreader-Sätze ohne Android — wie `A11ySpeechTests.swift`. */
class A11ySpeechTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val now = 1_700_000_000_000L

    /** Deutsche Satzteile wie in values-de (Pluralform von Hand). */
    private val german = object : A11ySpeech.Texts {
        override val disabled = "Deaktiviert"
        override val notTraded = "Wird an der Börse nicht mehr gehandelt"
        override val outdated = "Kurs veraltet"
        override fun updated(age: Age): String = when (age) {
            Age.JustNow -> "zuletzt aktualisiert gerade eben"
            is Age.Minutes -> "zuletzt aktualisiert vor ${age.count} " + if (age.count == 1) "Minute" else "Minuten"
            is Age.Hours -> "zuletzt aktualisiert vor ${age.count} " + if (age.count == 1) "Stunde" else "Stunden"
            is Age.Days -> "zuletzt aktualisiert vor ${age.count} " + if (age.count == 1) "Tag" else "Tagen"
        }
        override fun unreachable(market: String) = "$market nicht erreichbar"
    }

    @Test
    fun `Alter in ganzen Einheiten`() {
        assertEquals(Age.JustNow, A11ySpeech.age(now - 59_999, now))
        assertEquals(Age.JustNow, A11ySpeech.age(now + 5 * minute, now))
        assertEquals(Age.Minutes(1), A11ySpeech.age(now - minute, now))
        assertEquals(Age.Minutes(45), A11ySpeech.age(now - 45 * minute - 59_000, now))
        assertEquals(Age.Hours(1), A11ySpeech.age(now - hour, now))
        assertEquals(Age.Hours(23), A11ySpeech.age(now - day + 1, now))
        assertEquals(Age.Days(2), A11ySpeech.age(now - 2 * day - hour, now))
    }

    @Test
    fun `Frische nach der Regel veraltet`() {
        val after = 2 * minute
        fun f(disabled: Boolean = false, notTraded: Boolean = false, failed: Boolean = false, age: Long) =
            A11ySpeech.freshness(disabled, notTraded, failed, now - age, now, after)
        assertEquals(Freshness.FRESH, f(age = after))
        assertEquals(Freshness.OUTDATED, f(age = after + 1))
        assertEquals(Freshness.UNREACHABLE, f(failed = true, age = minute))
        assertEquals(Freshness.UNREACHABLE_OUTDATED, f(failed = true, age = 45 * minute))
        // Deaktiviert und «nicht mehr gehandelt» sind Zustände, nie «veraltet»
        assertEquals(Freshness.DISABLED, f(disabled = true, age = day))
        assertEquals(Freshness.NOT_TRADED, f(notTraded = true, age = day))
        // Noch nie aktualisiert bzw. Grenze 0: nichts sagen
        assertEquals(Freshness.FRESH, A11ySpeech.freshness(false, false, false, 0, now, after))
        assertEquals(Freshness.FRESH, A11ySpeech.freshness(false, false, false, now - day, now, 0))
    }

    @Test
    fun `Satzteil zur Frische`() {
        val t = now - 45 * minute
        assertEquals(
            "Kurs veraltet, zuletzt aktualisiert vor 45 Minuten",
            A11ySpeech.freshnessText(Freshness.OUTDATED, t, now, "Binance", german)
        )
        assertEquals(
            "zuletzt aktualisiert vor 1 Minute, Binance nicht erreichbar",
            A11ySpeech.freshnessText(Freshness.UNREACHABLE, now - minute, now, "Binance", german)
        )
        assertEquals(
            "Kurs veraltet, zuletzt aktualisiert vor 3 Stunden, Kraken nicht erreichbar",
            A11ySpeech.freshnessText(Freshness.UNREACHABLE_OUTDATED, now - 3 * hour, now, "Kraken", german)
        )
        assertEquals("Deaktiviert", A11ySpeech.freshnessText(Freshness.DISABLED, t, now, "Binance", german))
        assertEquals(german.notTraded, A11ySpeech.freshnessText(Freshness.NOT_TRADED, t, now, "Binance", german))
        assertNull(A11ySpeech.freshnessText(Freshness.FRESH, t, now, "Binance", german))
    }

    @Test
    fun `Alarm eben ausgeloest`() {
        assertTrue(A11ySpeech.firedRecently(now - minute, now))
        assertTrue(A11ySpeech.firedRecently(now - A11ySpeech.FIRED_MILLIS, now))
        assertFalse(A11ySpeech.firedRecently(now - A11ySpeech.FIRED_MILLIS - 1, now))
        assertFalse(A11ySpeech.firedRecently(0, now))
        assertFalse(A11ySpeech.firedRecently(now + minute, now))
    }

    @Test
    fun `Status nur beim Wechsel ansagen`() {
        assertEquals(StatusKind.FRESH, A11ySpeech.statusKind(none = false, offline = false, warn = false, newest = now))
        assertEquals(StatusKind.STALE, A11ySpeech.statusKind(none = false, offline = false, warn = true, newest = now))
        assertEquals(StatusKind.OFFLINE, A11ySpeech.statusKind(none = false, offline = true, warn = true, newest = now))
        assertNull(A11ySpeech.statusKind(none = true, offline = true, warn = true, newest = now))
        assertNull(A11ySpeech.statusKind(none = false, offline = false, warn = false, newest = 0))

        assertFalse(A11ySpeech.shouldAnnounce(null, StatusKind.STALE))
        assertFalse(A11ySpeech.shouldAnnounce(StatusKind.FRESH, StatusKind.FRESH))
        assertFalse(A11ySpeech.shouldAnnounce(StatusKind.FRESH, null))
        assertTrue(A11ySpeech.shouldAnnounce(StatusKind.FRESH, StatusKind.STALE))
        assertTrue(A11ySpeech.shouldAnnounce(StatusKind.STALE, StatusKind.OFFLINE))
        assertTrue(A11ySpeech.shouldAnnounce(StatusKind.OFFLINE, StatusKind.FRESH))
    }

    @Test
    fun `Trenner werden Pausen`() {
        assertEquals("Alles aktuell, gerade eben", A11ySpeech.spokenDots("Alles aktuell · gerade eben"))
        assertEquals("1 von 3 veraltet", A11ySpeech.spokenDots("1 von 3 veraltet"))
    }

    @Test
    fun `Fiat-Waehrung als Wort, Krypto bleibt`() {
        assertEquals("US Dollar", A11ySpeech.currencyName("USD", Locale.ENGLISH))
        assertEquals("Euro", A11ySpeech.currencyName("EUR", Locale.GERMAN))
        assertNull(A11ySpeech.currencyName("USDT", Locale.ENGLISH))
        assertNull(A11ySpeech.currencyName("BTC", Locale.ENGLISH))
        assertNull(A11ySpeech.currencyName("XAU", Locale.ENGLISH))
        assertNull(A11ySpeech.currencyName("usd", Locale.ENGLISH))
        assertEquals("61,234.00 US Dollar", A11ySpeech.spokenPrice("61,234.00 USD", "USD", Locale.ENGLISH))
        assertEquals("61,234.00 USDT", A11ySpeech.spokenPrice("61,234.00 USDT", "USDT", Locale.ENGLISH))
        assertEquals("—", A11ySpeech.spokenPrice("—", "USD", Locale.ENGLISH))
    }

    @Test
    fun `Reihenfolge Paar Kurs Veraenderung Frische Details Alarme Rest`() {
        val sentence = A11ySpeech.row(
            pair = "BTC/USDT an Binance",
            price = "Kurs 61’234.00 USDT",
            change = "gestiegen um 2.14% in 24 Stunden",
            state = listOf("Kurs veraltet, zuletzt aktualisiert vor 45 Minuten"),
            details = listOf(null, "Notiz: Langfristig", " "),
            alarms = listOf("Alarm ausgelöst", "Alarme: 2"),
            rest = listOf("Mitteilung"),
        )
        assertEquals(
            "BTC/USDT an Binance, Kurs 61’234.00 USDT, gestiegen um 2.14% in 24 Stunden, " +
                "Kurs veraltet, zuletzt aktualisiert vor 45 Minuten, Notiz: Langfristig, " +
                "Alarm ausgelöst, Alarme: 2, Mitteilung",
            sentence
        )
    }

    @Test
    fun `Kursmitteilung wie die Glocke, vor den Alarmen`() {
        assertTrue(A11ySpeech.priceNotificationOn(pairOn = true, masterOn = true))
        // Paar aus oder Hauptschalter aus: durchgestrichene Glocke → «aus»
        assertFalse(A11ySpeech.priceNotificationOn(pairOn = false, masterOn = true))
        assertFalse(A11ySpeech.priceNotificationOn(pairOn = true, masterOn = false))
        val sentence = A11ySpeech.row(
            pair = "BTC/USDT an Binance",
            price = "Kurs 61’234.00 USDT",
            change = "gestiegen um 2.14% in 24 Stunden",
            alarms = listOf("Kursmitteilung an", null, "Alarme: 2"),
        )
        assertEquals(
            "BTC/USDT an Binance, Kurs 61’234.00 USDT, gestiegen um 2.14% in 24 Stunden, Kursmitteilung an, Alarme: 2",
            sentence
        )
    }
}
