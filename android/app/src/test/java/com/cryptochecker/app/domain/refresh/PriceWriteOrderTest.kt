package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.above
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.watch
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import com.cryptochecker.marketdata.model.SimpleTicker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reihenfolge der Kurs-Schreiber ([PriceWriteOrder], `price_write_order.json`, geteilt mit iOS): Ein älterer Kurs
 * überschreibt keinen neueren — weder Kurs noch Alarm. Wie `PriceWriteOrderTests.swift`.
 */
class PriceWriteOrderTest {

    private val data = ParityJson.load("price_write_order.json")

    @Test
    fun parityCases() {
        val now = data["now"].num()!!.toLong()
        assertEquals(data["futureToleranceMillis"].num()!!.toLong(), PriceWriteOrder.FUTURE_TOLERANCE_MILLIS)
        val cases = data["cases"].list()
        assertTrue(cases.size >= 5)
        cases.forEach { raw ->
            val c = raw.obj()
            val newTime = now - c["newAgo"].num()!!.toLong()
            val stored = c["storedAgo"].num()?.let { now - it.toLong() } ?: 0L
            assertEquals(c["name"] as String, c["accepted"], PriceWriteOrder.accepts(newTime, stored, now))
        }
    }

    private fun ticker(price: Double, time: Long) = SimpleTicker().apply {
        last = price
        timestamp = time
    }

    private fun RefreshFixture.process(fetched: Map<Long, Fetched>): RefreshResultProcessor.Processed = runBlocking {
        val watches: List<WatchEntity> = store.getWatches()
        lock.withLock { proof -> processor.process(proof, watches, fetched, settings.settings, dayStart = null, live = false) }
    }

    /** Zwei Ergebnisse desselben Paars in vertauschter Reihenfolge: der neuere Kurs bleibt, der ältere löst nichts aus. */
    @Test
    fun olderResultAfterNewerKeepsNewerPriceAndFiresNoAlarm() {
        val now = System.currentTimeMillis()
        val f = RefreshFixture(listOf(watch(price = 99.0)), listOf(above(100.0)))

        // Zuerst fertig: die vollständige Aktualisierung mit dem neueren Kurs (unter der Schwelle)
        f.process(mapOf(1L to Fetched(ticker(98.0, now - 1_000L), null)))
        assertEquals(98.0, f.store.watches.getValue(1).lastPrice!!, 0.0)
        assertEquals(now - 1_000L, f.store.watches.getValue(1).lastUpdate)

        // Danach fertig: die langsame Einzelabfrage mit dem älteren Kurs (über der Schwelle)
        val late = f.process(mapOf(1L to Fetched(ticker(101.0, now - 30_000L), null)))

        assertEquals(98.0, f.store.watches.getValue(1).lastPrice!!, 0.0)
        assertEquals(now - 1_000L, f.store.watches.getValue(1).lastUpdate)
        assertEquals(0, late.alarms)
        assertTrue(f.notifier.alarmPosts.isEmpty())
        assertTrue(f.store.alarm(7).enabled)
    }

    /** Die Bedingung der Datenbank entspricht [PriceWriteOrder.accepts] (beide Schreibwege). */
    @Test
    fun daoQueriesCarryTheOrderCondition() {
        val dao = sourceFile("data/local/WatchDao.kt").readText()
        val condition = "(lastUpdate <= :time OR lastUpdate > :plausibleUntil)"
        assertEquals(2, dao.split(condition).size - 1)
        val writer = sourceFile("data/WatchRepositoryRefresh.kt").readText()
        assertTrue(writer.contains("PriceWriteOrder.plausibleUntil(now)"))
    }

    private fun sourceFile(path: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/cryptochecker/app/$path")
            if (candidate.isFile) return candidate
            val inModule = File(dir, "src/main/java/com/cryptochecker/app/$path")
            if (inModule.isFile) return inModule
            // Datenschicht seit Build 142 im Modul :data (gleiche Pakete)
            val inData = File(dir, "data/src/main/java/com/cryptochecker/app/$path")
            if (inData.isFile) return inData
            dir = dir.parentFile
        }
        throw IllegalStateException("$path not found")
    }
}
