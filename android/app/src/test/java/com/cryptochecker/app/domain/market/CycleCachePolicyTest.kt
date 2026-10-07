package com.cryptochecker.app.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CycleCachePolicyTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun ttlsPerSource() {
        assertEquals(12 * hour, CycleSource.HISTORY.ttlMillis)
        assertEquals(12 * hour, CycleSource.ON_CHAIN.ttlMillis)
        assertEquals(hour, CycleSource.MARKET.ttlMillis)
        assertEquals(30 * minute, CycleSource.FEAR_GREED.ttlMillis)
        assertEquals(15 * minute, CycleSource.GLOBAL.ttlMillis)
        assertEquals(hour, CycleSource.ALT_SEASON.ttlMillis)
        assertEquals(5 * minute, CycleSource.PULSE.ttlMillis)
        assertEquals(10 * minute, CycleSource.UNUSUAL.ttlMillis)
        assertEquals(minute, CycleSource.GAS.ttlMillis)
        assertEquals(15 * minute, CycleSource.COIN.ttlMillis)
    }

    @Test
    fun timeoutsAreBounded() {
        CycleSource.entries.forEach { assertTrue(it.name, it.timeoutMillis in 8_000L..20_000L) }
        assertEquals(CycleSource.entries.size, CycleSource.entries.map { it.key }.toSet().size)
    }

    @Test
    fun freshOnlyWithinTtl() {
        val ttl = CycleSource.FEAR_GREED.ttlMillis
        assertTrue(CycleCachePolicy.isFresh(now, now, ttl))
        assertTrue(CycleCachePolicy.isFresh(now - ttl + 1, now, ttl))
        assertFalse(CycleCachePolicy.isFresh(now - ttl, now, ttl))
        assertFalse(CycleCachePolicy.isFresh(now - 3 * ttl, now, ttl))
    }

    @Test
    fun missingInvalidOrFutureTimesAreNeverFresh() {
        val ttl = CycleSource.HISTORY.ttlMillis
        assertFalse(CycleCachePolicy.isFresh(null, now, ttl))
        assertFalse(CycleCachePolicy.isFresh(0L, now, ttl))
        assertFalse(CycleCachePolicy.isFresh(-5L, now, ttl))
        // Uhr zurückgestellt: gespeichert «in der Zukunft» → neu laden
        assertFalse(CycleCachePolicy.isFresh(now + minute, now, ttl))
    }

    @Test
    fun refreshWhenStaleMissingOrForced() {
        val ttl = CycleSource.GAS.ttlMillis
        assertFalse(CycleCachePolicy.needsRefresh(now - 30_000L, now, ttl, force = false))
        assertTrue(CycleCachePolicy.needsRefresh(now - 30_000L, now, ttl, force = true))
        assertTrue(CycleCachePolicy.needsRefresh(now - 61_000L, now, ttl, force = false))
        assertTrue(CycleCachePolicy.needsRefresh(null, now, ttl, force = false))
    }

    @Test
    fun sameAgeDifferentDecisionsBySource() {
        val savedAt = now - 20 * minute
        assertFalse(CycleCachePolicy.needsRefresh(savedAt, now, CycleSource.HISTORY.ttlMillis, false))
        assertFalse(CycleCachePolicy.needsRefresh(savedAt, now, CycleSource.MARKET.ttlMillis, false))
        assertFalse(CycleCachePolicy.needsRefresh(savedAt, now, CycleSource.FEAR_GREED.ttlMillis, false))
        assertTrue(CycleCachePolicy.needsRefresh(savedAt, now, CycleSource.GLOBAL.ttlMillis, false))
        assertTrue(CycleCachePolicy.needsRefresh(savedAt, now, CycleSource.COIN.ttlMillis, false))
        assertTrue(CycleCachePolicy.needsRefresh(savedAt, now, CycleSource.PULSE.ttlMillis, false))
        assertTrue(CycleCachePolicy.needsRefresh(savedAt, now, CycleSource.GAS.ttlMillis, false))
    }

    @Test
    fun onlyCurrentFormatIsRead() {
        assertTrue(CycleCachePolicy.isCurrentFormat(CycleCachePolicy.FORMAT_VERSION))
        assertFalse(CycleCachePolicy.isCurrentFormat(CycleCachePolicy.FORMAT_VERSION - 1))
        assertFalse(CycleCachePolicy.isCurrentFormat(CycleCachePolicy.FORMAT_VERSION + 1))
        assertFalse(CycleCachePolicy.isCurrentFormat(null))
    }

    @Test
    fun versionedSafeFileNames() {
        val v = CycleCachePolicy.FORMAT_VERSION
        assertEquals("fear_greed_v$v.json", CycleCachePolicy.fileName(CycleSource.FEAR_GREED.key))
        assertEquals("coin_ETH", CycleCachePolicy.coinName("eth"))
        assertEquals("coin_1INCH", CycleCachePolicy.coinName("1inch"))
        // Kein Weg aus dem Ordner hinaus
        assertEquals("coin_ETH_v$v.json", CycleCachePolicy.fileName("../coin_ETH"))
        assertEquals("entry_v$v.json", CycleCachePolicy.fileName("/.."))
    }

    @Test
    fun dataAsOfIsOldestShownAmongRunning() {
        val shown = mapOf(
            CycleSource.HISTORY to now - 10 * hour,
            CycleSource.GAS to now - 2 * minute,
            CycleSource.PULSE to now - 7 * minute,
        )
        // Nichts läuft: keine Zeile
        assertNull(CycleCachePolicy.dataAsOf(shown, emptySet()))
        // Nur Gas lädt neu: dessen Zeit, nicht die (frische, ältere) Zyklus-Historie
        assertEquals(now - 2 * minute, CycleCachePolicy.dataAsOf(shown, setOf(CycleSource.GAS)))
        assertEquals(
            now - 7 * minute,
            CycleCachePolicy.dataAsOf(shown, setOf(CycleSource.GAS, CycleSource.PULSE))
        )
        // Läuft nur ein Bereich ohne angezeigte Daten (erstes Laden): keine Zeile
        assertNull(CycleCachePolicy.dataAsOf(shown, setOf(CycleSource.COIN)))
        assertNull(CycleCachePolicy.dataAsOf(mapOf(CycleSource.GAS to 0L), setOf(CycleSource.GAS)))
    }

    @Test
    fun onChainHasAny() {
        assertFalse(OnChainValues(null, null, null, null).hasAny)
        assertTrue(OnChainValues(null, 1.2, null, null).hasAny)
    }
}
