package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.market.MarketPlainSummary
import com.cryptochecker.app.domain.market.PlainDirectionKind
import com.cryptochecker.app.domain.market.PlainMoodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Kurz gesagt» im Widget «Was gerade auffällt» ([PulseWidgetPlain]); Gegenstück `PulseWidgetPlainTests.swift`. */
class PulseWidgetPlainTest {

    private val now = 1_750_000_000_000L
    private val minute = 60_000L

    @Test
    fun `Groesse - schmal oder unbekannt klein, breit mit Platz gross, sonst mittel`() {
        assertEquals(PulseWidgetSize.SMALL, PulseWidgetPlain.size(0, blockFits = true))
        assertEquals(PulseWidgetSize.SMALL, PulseWidgetPlain.size(249, blockFits = true))
        assertEquals(PulseWidgetSize.MEDIUM, PulseWidgetPlain.size(250, blockFits = false))
        assertEquals(PulseWidgetSize.LARGE, PulseWidgetPlain.size(250, blockFits = true))
    }

    @Test
    fun `Hoehe unter dem bisherigen Inhalt - Nebenzeilen und zweizeilige Schlagzeile zaehlen`() {
        // 150 Grundhöhe + 4 Sicherheit
        assertEquals(146f, PulseWidgetPlain.blockAvailableDp(300, 1, 0), 0.001f)
        // Eine Nebenzeile 18, zwei 36, zweizeilige Schlagzeile 19
        assertEquals(128f, PulseWidgetPlain.blockAvailableDp(300, 1, 1), 0.001f)
        assertEquals(91f, PulseWidgetPlain.blockAvailableDp(300, 2, 2), 0.001f)
        // Zu niedrig oder unbekannt: kein Platz (nie ein abgeschnittener Satz)
        assertEquals(0f, PulseWidgetPlain.blockAvailableDp(140, 1, 0), 0.001f)
        assertEquals(0f, PulseWidgetPlain.blockAvailableDp(0, 1, 0), 0.001f)
    }

    @Test
    fun `Saetze - nur ganze, von hinten weggelassen`() {
        // Rahmen: 8 oben + Titel 16 + 4 + Hinweis 30 = 58; je Satz 4 Abstand + Höhe
        val heights = listOf(36f, 36f, 54f)
        assertEquals(3, PulseWidgetPlain.sentenceCount(196f, 16f, 30f, heights))
        // Ein dp weniger: der dritte Satz passt nicht ganz → weg, nicht abgeschnitten
        assertEquals(2, PulseWidgetPlain.sentenceCount(195f, 16f, 30f, heights))
        assertEquals(1, PulseWidgetPlain.sentenceCount(98f, 16f, 30f, heights))
        // Nicht einmal ein Satz: der Block entfällt
        assertEquals(0, PulseWidgetPlain.sentenceCount(97f, 16f, 30f, heights))
        assertEquals(0, PulseWidgetPlain.sentenceCount(500f, 16f, 30f, emptyList()))
        assertEquals(0, PulseWidgetPlain.sentenceCount(0f, 16f, 30f, heights))
    }

    @Test
    fun `Saetze - ein langer zweiter Satz sperrt den kuerzeren dritten nicht frei`() {
        // Reihenfolge bleibt: passt Satz 2 nicht, entfällt auch Satz 3 (keine Lücke)
        assertEquals(1, PulseWidgetPlain.sentenceCount(140f, 16f, 30f, listOf(20f, 90f, 10f)))
    }

    @Test
    fun `Mittel - erster Satz nur, wenn er ganz in die Zeilen des Leitsatzes passt`() {
        assertTrue(PulseWidgetPlain.sentenceAsLead(2, 2))
        assertTrue(PulseWidgetPlain.sentenceAsLead(1, 2))
        assertFalse(PulseWidgetPlain.sentenceAsLead(3, 2))
        assertFalse(PulseWidgetPlain.sentenceAsLead(2, 1))
        assertFalse(PulseWidgetPlain.sentenceAsLead(null, 2))
        assertFalse(PulseWidgetPlain.sentenceAsLead(0, 2))
    }

    @Test
    fun `Eingaben - Puls und Fear and Greed mit der Frische-Regel der App`() {
        val fresh = MarketPlainSummary.evaluate(
            PulseWidgetPlain.input(now, 2.4, now - 2 * minute, 72, now - 30 * minute)
        )
        assertEquals(PlainDirectionKind.UP_SLIGHT, fresh.direction?.kind)
        assertEquals(PlainMoodKind.GREED, fresh.mood?.kind)
        // Widget hat «Heute auffällig» nicht (wie iOS): nie ein dritter Satz
        assertNull(fresh.standout)

        // Puls veraltet nach 3 × 5 Min., Fear & Greed nach 3 × 1 h — dann kein Satz (keine Vermutung)
        val stale = MarketPlainSummary.evaluate(
            PulseWidgetPlain.input(now, 2.4, now - 16 * minute, 72, now - 181 * minute)
        )
        assertTrue(stale.isEmpty)
        // Ohne Stand: kein Satz
        assertTrue(MarketPlainSummary.evaluate(PulseWidgetPlain.input(now, 2.4, null, 72, null)).isEmpty)
    }

    @Test
    fun `Ablauf - fruehester Moment, an dem ein gezeigter Satz veralten wuerde`() {
        val input = PulseWidgetPlain.input(now, -4.0, now - 2 * minute, 20, now - 30 * minute)
        val summary = MarketPlainSummary.evaluate(input)
        // Puls: gespeichert vor 2 Min., veraltet nach 15 Min. → in 13 Min. (+1 ms, «älter als»)
        assertEquals(now + 13 * minute + 1, PulseWidgetPlain.expiresAt(input, summary))

        // Nur Fear & Greed: gespeichert vor 30 Min., veraltet nach 3 h
        val moodOnly = PulseWidgetPlain.input(now, null, null, 20, now - 30 * minute)
        assertEquals(now + 150 * minute + 1, PulseWidgetPlain.expiresAt(moodOnly, MarketPlainSummary.evaluate(moodOnly)))

        // Kein Satz: kein Wecker
        val none = PulseWidgetPlain.input(now, null, null, null, null)
        assertNull(PulseWidgetPlain.expiresAt(none, MarketPlainSummary.evaluate(none)))
    }

    @Test
    fun `Quellen - nur die der gezeigten Saetze, in ihrer Reihenfolge`() {
        val both = MarketPlainSummary.evaluate(PulseWidgetPlain.input(now, 2.4, now - 2 * minute, 72, now - 30 * minute))
        assertEquals("CoinGecko, alternative.me", PulseWidgetPlain.sources(both, 2))
        // Nur der erste Satz passt: nur seine Quelle
        assertEquals("CoinGecko", PulseWidgetPlain.sources(both, 1))
        assertNull(PulseWidgetPlain.sources(both, 0))
        // Nur Stimmung
        val moodOnly = MarketPlainSummary.evaluate(PulseWidgetPlain.input(now, null, null, 72, now - 30 * minute))
        assertEquals("alternative.me", PulseWidgetPlain.sources(moodOnly, 3))
    }
}
