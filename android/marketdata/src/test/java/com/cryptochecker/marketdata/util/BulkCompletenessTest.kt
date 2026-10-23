package com.cryptochecker.marketdata.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vollständigkeit mehrteiliger Sammelabfragen (BulkCompleteness), ohne Netz. */
class BulkCompletenessTest {

    private val groupOf: (String) -> Int = { if (it.startsWith("alpha:")) 1 else 0 }

    @Test
    fun nurPaareDerGescheitertenGruppe() {
        val ids = listOf("BTCUSDT", "alpha:X", "ETHUSDT", "alpha:Y")
        assertEquals(setOf("alpha:X", "alpha:Y"), BulkCompleteness.incomplete(ids, setOf(1), groupOf))
        assertEquals(emptySet<String>(), BulkCompleteness.incomplete(ids, emptySet(), groupOf))
    }

    @Test
    fun auskunftSolangeNichtAllePaareUnvollstaendig() {
        assertTrue(BulkCompleteness.anyComplete(listOf("a", "b"), emptySet(), emptySet()))
        assertTrue(BulkCompleteness.anyComplete(listOf("a", "a", "b"), setOf(1), setOf("b")))
        assertFalse(BulkCompleteness.anyComplete(listOf("b", "b"), setOf(1), setOf("b")))
        // Ohne Paare: keine Auskunft
        assertFalse(BulkCompleteness.anyComplete(emptyList(), setOf(1), emptySet()))
    }

    @Test
    fun unbekanntePaareNurMitNichtLeererListe() {
        assertNull(BulkCompleteness.unknownPairs(listOf("a", "b"), emptySet()))
        assertNull(BulkCompleteness.unknownPairs(listOf("a"), setOf("a", "c")))
        assertEquals(setOf("b"), BulkCompleteness.unknownPairs(listOf("a", "b", "b"), setOf("a")))
    }
}
