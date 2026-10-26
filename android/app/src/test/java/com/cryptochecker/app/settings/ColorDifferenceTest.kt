package com.cryptochecker.app.settings

import com.cryptochecker.app.parity.ParityJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CIEDE2000 gegen die Testpaare von Sharma, Wu, Dalal (2005) und sRGB-Paare — gemeinsame Testfälle
 * `price_colors.json` (iOS: `ColorDifferenceTests.swift`).
 */
class ColorDifferenceTest {

    private val data = ParityJson.load("price_colors.json")

    @Suppress("UNCHECKED_CAST")
    private fun lab(value: Any?): DoubleArray = (value as List<Double>).toDoubleArray()

    @Test
    fun `Sharma-Testpaare`() {
        @Suppress("UNCHECKED_CAST")
        val pairs = data["deltaE2000"] as List<Map<String, Any?>>
        assertEquals(34, pairs.size)
        for ((i, p) in pairs.withIndex()) {
            val expected = p["expected"] as Double
            val a = lab(p["lab1"])
            val b = lab(p["lab2"])
            assertEquals("Paar ${i + 1}", expected, ColorDifference.deltaE2000(a, b), 1e-4)
            // Symmetrisch
            assertEquals("Paar ${i + 1} getauscht", expected, ColorDifference.deltaE2000(b, a), 1e-4)
        }
    }

    @Test
    fun `sRGB-Paare`() {
        @Suppress("UNCHECKED_CAST")
        val srgb = data["srgb"] as List<Map<String, Any?>>
        for (p in srgb) {
            val a = (p["a"] as String).toInt(16)
            val b = (p["b"] as String).toInt(16)
            assertEquals("${p["a"]}/${p["b"]}", p["expected"] as Double, ColorDifference.deltaE2000(a, b), 1e-3)
        }
    }

    @Test
    fun `Lab von Weiss, Schwarz und Grau`() {
        assertArrayEquals(doubleArrayOf(100.0, 0.0, 0.0), ColorDifference.lab(0xFFFFFF), 0.02)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), ColorDifference.lab(0x000000), 1e-9)
        val grey = ColorDifference.lab(0x777777)
        assertEquals(0.0, grey[1], 0.01)
        assertEquals(0.0, grey[2], 0.01)
        // Alpha zählt nicht
        assertEquals(0.0, ColorDifference.deltaE2000(0xFFA64827.toInt(), 0x00A64827), 1e-12)
        assertTrue(ColorDifference.deltaE2000(0x000000, 0xFFFFFF) > 99.0)
    }
}
