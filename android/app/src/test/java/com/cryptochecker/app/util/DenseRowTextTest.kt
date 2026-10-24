package com.cryptochecker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wie `DenseRowTextTests.swift`. */
class DenseRowTextTest {

    @Test
    fun `Standardgroessen einzeilig nebeneinander`() {
        for (scale in listOf(0.85f, 1f, 1.15f)) {
            assertEquals(1, DenseRowText.maxLines(scale))
            assertFalse(DenseRowText.stacksTrailing(scale))
        }
    }

    @Test
    fun `Grosse Schrift bricht um`() {
        // Android 1.3, iOS xxLarge (21/17) und xxxLarge (23/17): zwei Zeilen, Kurs noch daneben
        for (scale in listOf(1.3f, 21f / 17f, 23f / 17f)) {
            assertEquals(2, DenseRowText.maxLines(scale))
            assertFalse(DenseRowText.stacksTrailing(scale))
        }
    }

    @Test
    fun `Mini-Chart in schmalen Zeilen 48 sonst 56`() {
        assertEquals(48f, DenseRowText.sparklineWidth(320f), 0f)
        assertEquals(48f, DenseRowText.sparklineWidth(359.5f), 0f)
        assertEquals(56f, DenseRowText.sparklineWidth(360f), 0f)
        assertEquals(56f, DenseRowText.sparklineWidth(412f), 0f)
    }

    @Test
    fun `Groesste Schrift stellt die rechte Spalte darunter`() {
        // Android 1.5 / 1.8 / 2.0, iOS accessibility1 (28/17) und grösser
        for (scale in listOf(1.5f, 1.8f, 2f, 28f / 17f, 53f / 17f)) {
            assertEquals(2, DenseRowText.maxLines(scale))
            assertTrue(DenseRowText.stacksTrailing(scale))
        }
    }

    @Test
    fun `Kurs eine Stufe groesser nur mit Platz`() {
        // Normale Schrift, normale Zeile: gross; schmale Zeile oder Umbruch-Schrift daneben: alte Stufe
        assertTrue(DenseRowText.largePrice(1f, 360f))
        assertTrue(DenseRowText.largePrice(1.15f, 412f))
        assertFalse(DenseRowText.largePrice(1f, 320f))
        assertFalse(DenseRowText.largePrice(1.3f, 412f))
        // Gestapelt (Kurs unter dem Paar, volle Breite): immer gross
        assertTrue(DenseRowText.largePrice(1.5f, 320f))
        assertTrue(DenseRowText.largePrice(2f, 412f))
    }

    @Test
    fun `Umrechnung hoechstens halbe Zeile`() {
        // Lange ≈ Zeile (Vermerk) darf Paar, Name und Börse nicht verdrängen
        assertEquals(205.5f, DenseRowText.convertedMaxWidth(411f), 0.001f)
        assertEquals(160f, DenseRowText.convertedMaxWidth(320f), 0.001f)
    }

    @Test
    fun `Umrechnung bricht neben dem Paar auf zwei Zeilen um, gestapelt frei`() {
        // «≈ 66’120.50 CHF · Kurs 08:15 · Umrechnung unsicher»: der Vermerk am Ende darf nicht im «…» verschwinden
        assertEquals(2, DenseRowText.convertedMaxLines(stacked = false))
        assertEquals(Int.MAX_VALUE, DenseRowText.convertedMaxLines(stacked = true))
    }
}
