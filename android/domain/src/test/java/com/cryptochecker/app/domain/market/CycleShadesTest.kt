package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V4-11: ab dem Halving 2028 (drei frühere Zyklen) hat jeder frühere Zyklus einen eigenen Grauton. iOS: CycleShadesTests. */
class CycleShadesTest {

    @Test
    fun heuteZweiFruehereZyklenWieBisher() {
        assertEquals(0.65f, CycleShades.olderAlpha(0, 2), 1e-6f)
        assertEquals(0.85f, CycleShades.olderAlpha(1, 2), 1e-6f)
    }

    @Test
    fun ab2028JederZyklusEigenerTon() {
        val alphas = (0 until 3).map { CycleShades.olderAlpha(it, 3) }
        assertEquals(listOf(0.65f, 0.75f, 0.85f).map { Math.round(it * 100) }, alphas.map { Math.round(it * 100) })
        assertEquals(3, alphas.toSet().size)
        // Nie heller als die geprüfte Kontrastgrenze
        (1..6).forEach { n -> (0 until n).forEach { assertTrue(CycleShades.olderAlpha(it, n) >= CycleShades.OLDEST) } }
    }

    @Test
    fun einFruehererZyklus() {
        assertEquals(CycleShades.NEWEST, CycleShades.olderAlpha(0, 1), 0f)
    }
}
