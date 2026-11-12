package com.cryptochecker.app.domain.logos
import com.cryptochecker.app.domain.logos.CoinLogoQueue
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.CoinMeta
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.obj

/**
 * Sammel-Abruf der Logos ([CoinLogoQueue]): gemeinsame Testfälle `coin_logos.json` › `queue` (iOS:
 * `CoinLogoQueueTests.swift`) — für alle dieselbe Reihenfolge (nie nach der Merkliste), Grenzen insgesamt und je
 * Server — und der echte Ablauf ([CoinLogoQueue.run]):
 * nie mehr gleichzeitig als erlaubt, Start in der Reihenfolge der Liste, jeder Abruf genau einmal.
 */
class CoinLogoQueueParityTest {

    private val queue = ParityJson.load("coin_logos.json")["queue"].obj()

    @Test
    fun limitsAsFixture() {
        assertEquals((queue["maxParallel"] as Double).toInt(), CoinLogoQueue.MAX_PARALLEL)
        assertEquals((queue["perHostParallel"] as Double).toInt(), CoinLogoQueue.PER_HOST_PARALLEL)
    }

    /**
     * Für alle dieselbe Reihenfolge, nie nach Merkliste oder Portfolio: die bei Binance gelisteten Kürzel in der
     * Reihenfolge der Symbolliste, dann die Rangliste ([CoinMeta.merge] mit `coin_meta.json`; iOS über `Lists.order`).
     * Die Abrufe ([CoinLogos.downloadJobs]) behalten diese Reihenfolge.
     */
    @Test
    fun fixedOrderBinanceListThenRank() {
        val root = org.json.JSONObject(File(ParityJson.dir(), "coin_meta.json").readText(Charsets.UTF_8))
        val binance = CoinMeta.parseBinanceList(root.get("binanceList").toString())
        val gecko = CoinMeta.parseMarketsPage(root.get("geckoPage").toString())
        assertNotNull(binance)
        assertNotNull(gecko)
        if (binance == null || gecko == null) return
        val first = CoinMeta.binanceFirst(binance).keys.toList()
        val map = CoinMeta.merge(gecko, binance, null).map
        val keys = map.keys.toList()
        assertTrue(first.isNotEmpty())
        assertEquals(first, keys.take(first.size))
        val ranked = CoinLogos.pick(gecko.map { it.symbol to it.image }).keys.filter { it !in first }
        assertTrue(ranked.isNotEmpty())
        assertEquals(ranked, keys.drop(first.size).take(ranked.size))
        val files = CoinLogos.downloadJobs(map.toList()) { false }.map { it.first }
        assertEquals(keys.mapNotNull { CoinLogos.fileName(it) }.distinct(), files)
    }

    @Test
    fun nextStartable() {
        queue["nextStartable"].list().map { it.obj() }.forEach { c ->
            val hosts = c["hosts"].list().map { it as String }
            val running = c["running"].obj().mapValues { (it.value as Double).toInt() }
            val expected = (c["expected"] as Double?)?.toInt() ?: -1
            assertEquals("nextStartable $c", expected, CoinLogoQueue.nextStartable(hosts, running))
        }
    }

    @Test
    fun runNeverExceedsTheLimitsAndKeepsTheOrder() = runBlocking {
        // 15 Bilder bei CoinGecko, 5 bei Binance, gemischt
        val jobs = (0 until 20).map { i -> i to if (i % 4 == 3) "b" else "g" }
        val total = AtomicInteger()
        val perHost = mapOf("g" to AtomicInteger(), "b" to AtomicInteger())
        val maxTotal = AtomicInteger()
        val maxPerHost = mapOf("g" to AtomicInteger(), "b" to AtomicInteger())
        val started = java.util.Collections.synchronizedList(ArrayList<Int>())
        val done = ArrayList<Int>()
        CoinLogoQueue.run(jobs, hostOf = { it.second }, work = { (i, host) ->
            started += i
            maxTotal.accumulateAndGet(total.incrementAndGet()) { a, b -> maxOf(a, b) }
            maxPerHost.getValue(host).accumulateAndGet(perHost.getValue(host).incrementAndGet()) { a, b -> maxOf(a, b) }
            delay(5L + i % 3)
            perHost.getValue(host).decrementAndGet()
            total.decrementAndGet()
            i
        }) { _, result -> done += result }
        assertTrue("insgesamt ${maxTotal.get()}", maxTotal.get() <= CoinLogoQueue.MAX_PARALLEL)
        assertEquals(CoinLogoQueue.MAX_PARALLEL, maxTotal.get())
        assertTrue("CoinGecko ${maxPerHost.getValue("g").get()}", maxPerHost.getValue("g").get() <= CoinLogoQueue.PER_HOST_PARALLEL)
        assertTrue("Binance ${maxPerHost.getValue("b").get()}", maxPerHost.getValue("b").get() <= CoinLogoQueue.PER_HOST_PARALLEL)
        assertEquals((0 until 20).toList(), done.sorted())
        // Je Server in der Reihenfolge der Liste gestartet
        for (host in listOf("g", "b")) {
            val order = jobs.filter { it.second == host }.map { it.first }
            assertEquals(order, started.filter { jobs[it].second == host })
        }
    }

    @Test
    fun runWithOneServerStartsInListOrder() = runBlocking {
        val jobs = (0 until 10).toList()
        val started = java.util.Collections.synchronizedList(ArrayList<Int>())
        val running = AtomicInteger()
        val most = AtomicInteger()
        CoinLogoQueue.run(jobs, hostOf = { "g" }, work = { i ->
            started += i
            most.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            delay(3)
            running.decrementAndGet()
        }) { _, _ -> }
        assertEquals(jobs, started.toList())
        assertEquals(CoinLogoQueue.PER_HOST_PARALLEL, most.get())
        CoinLogoQueue.run(emptyList<Int>(), hostOf = { "g" }, work = { }) { _, _ -> error("kein Abruf") }
    }
}
