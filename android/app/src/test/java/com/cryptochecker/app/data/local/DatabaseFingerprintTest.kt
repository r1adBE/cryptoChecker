package com.cryptochecker.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** [DatabaseFingerprint]: Prüfsummen für den Vergleich vor und nach einem `sqlcipher_export`. */
class DatabaseFingerprintTest {

    private val content = DatabaseFingerprint(
        userVersion = 15,
        tables = mapOf("watches" to DatabaseFingerprint.Table(2, 42), "alarms" to DatabaseFingerprint.Table(1, 7)),
    )

    @Test
    fun rowChecksumSeparatesTypesAndValues() {
        val sums = listOf(
            listOf<Any?>(1L), listOf<Any?>(1.0), listOf<Any?>("1"), listOf<Any?>(byteArrayOf(1)), listOf<Any?>(null),
            listOf<Any?>(1L, null), listOf<Any?>(null, 1L), listOf<Any?>("ab", "c"), listOf<Any?>("a", "bc"),
        ).map(DatabaseFingerprint::rowChecksum)
        assertEquals(sums.size, sums.toSet().size)
        assertEquals(DatabaseFingerprint.rowChecksum(listOf(5, "x")), DatabaseFingerprint.rowChecksum(listOf(5L, "x")))
    }

    @Test
    fun tableChecksumIgnoresRowOrder() {
        val rows = listOf(listOf<Any?>(1L, "BTC"), listOf<Any?>(2L, "ETH"), listOf<Any?>(3L, null))
        val empty = DatabaseFingerprint.Table(0, 0)
        val forward = rows.fold(empty, DatabaseFingerprint::addRow)
        val backward = rows.reversed().fold(empty, DatabaseFingerprint::addRow)
        assertEquals(forward, backward)
        assertEquals(3, forward.rows)
        assertNotEquals(forward, rows.take(2).fold(empty, DatabaseFingerprint::addRow))
    }

    @Test
    fun differenceNamesTablesNotContents() {
        val after = content.copy(
            userVersion = 14,
            tables = mapOf("watches" to DatabaseFingerprint.Table(1, 42), "extra" to DatabaseFingerprint.Table(0, 0)),
        )
        assertEquals(
            "user_version 15 → 14, alarms fehlt in der Kopie, extra nur in der Kopie, watches: 2 → 1 Zeilen",
            content.difference(after),
        )
    }

    @Test
    fun differentChecksumOrVersionOrMissingTableIsNotEqual() {
        val mutations = listOf<(DatabaseFingerprint) -> DatabaseFingerprint>(
            { it.copy(tables = it.tables + ("alarms" to DatabaseFingerprint.Table(1, 8))) },
            { it.copy(userVersion = 0) },
            { it.copy(tables = it.tables - "alarms") },
        )
        for (mutation in mutations) assertNotEquals(content, mutation(content))
    }
}
