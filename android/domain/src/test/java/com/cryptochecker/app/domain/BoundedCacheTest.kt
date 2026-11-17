package com.cryptochecker.app.domain
import com.cryptochecker.app.domain.BoundedCache
import com.cryptochecker.app.domain.KeyedMutex
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Begrenzte Zwischenspeicher der Kerzen (Mini-Chart, 24-h-Bezug) und Sperren je Paar. */
class BoundedCacheTest {

    @Test
    fun `at most maxEntries entries, the least recently used drops out`() {
        val cache = BoundedCache<String, Int>(3)
        cache["A"] = 1
        cache["B"] = 2
        cache["C"] = 3
        // A benutzt: B ist jetzt der älteste
        assertEquals(1, cache["A"])
        cache["D"] = 4
        assertEquals(3, cache.size)
        assertNull(cache["B"])
        assertEquals(1, cache["A"])
        assertEquals(4, cache["D"])
    }

    @Test
    fun `many pairs stay bounded`() {
        val cache = BoundedCache<String, Int>(256)
        repeat(10_000) { cache["P$it"] = it }
        assertEquals(256, cache.size)
        assertEquals(9_999, cache["P9999"])
        assertNull(cache["P0"])
    }

    @Test
    fun `merge keeps the newer value as before`() {
        val cache = BoundedCache<String, Long>(4)
        cache.merge("A", 5L) { current, disk -> maxOf(current, disk) }
        cache.merge("A", 3L) { current, disk -> maxOf(current, disk) }
        assertEquals(5L, cache["A"])
        assertEquals(mapOf("A" to 5L), cache.snapshot())
    }

    @Test
    fun `lock per key is mutually exclusive and removed afterwards`() = runBlocking {
        val locks = KeyedMutex<String>()
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val jobs = (1..20).map {
            async {
                locks.withLock("BTC|USDT") {
                    val now = inside.incrementAndGet()
                    maxInside.updateAndGet { m -> maxOf(m, now) }
                    yield()
                    inside.decrementAndGet()
                }
            }
        }
        jobs.awaitAll()
        assertEquals(1, maxInside.get())
        // Niemand mehr dran: keine Sperre bleibt liegen
        assertEquals(0, locks.size)
    }

    @Test
    fun `waiter gets the same lock even when the first finishes`() = runBlocking {
        val locks = KeyedMutex<String>()
        val release = CompletableDeferred<Unit>()
        val first = async { locks.withLock("ETH|USDT") { release.await() } }
        yield()
        val second = async { locks.withLock("ETH|USDT") { "zweiter" } }
        yield()
        assertEquals(1, locks.size)
        release.complete(Unit)
        first.await()
        assertEquals("zweiter", second.await())
        assertEquals(0, locks.size)
    }

    @Test
    fun `cancel while waiting cleans up the lock`() = runBlocking {
        val locks = KeyedMutex<String>()
        val release = CompletableDeferred<Unit>()
        val first = async { locks.withLock("SOL|USDT") { release.await() } }
        yield()
        val waiting = async { locks.withLock("SOL|USDT") { } }
        yield()
        waiting.cancel()
        release.complete(Unit)
        first.await()
        waiting.join()
        assertEquals(0, locks.size)
    }
}
