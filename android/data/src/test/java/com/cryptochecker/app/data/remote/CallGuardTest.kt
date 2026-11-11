package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.remote.util.CallGuard
import com.cryptochecker.app.data.remote.util.CallStart
import com.cryptochecker.app.data.remote.util.CallStartInterceptor
import com.cryptochecker.app.data.remote.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wache der Börsenanfragen ([CallGuard]): Die Frist läuft erst ab der Ausführung ([CallStart]); wer in der
 * Warteschlange des Dispatchers wartet, endet nicht als Schein-Zeitüberschreitung (die sonst die Börse pausierte).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallGuardTest {

    // Virtuelle Zeit (runTest): Die Abstände zwischen Wache und Arbeit sind exakt statt vom Takt der CI-Maschine abhängig

    @Test
    fun waitingInTheQueueDoesNotCount() = runTest {
        val start = CallStart()
        launch {
            // 400 ms in der Warteschlange, dann 100 ms Arbeit — Wache 300 ms
            delay(400)
            start.markStarted()
        }
        val result = CallGuard.run(start, guardMillis = 300) {
            delay(500)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(500L, testScheduler.currentTime)
    }

    @Test
    fun hangingCallEndsAfterTheGuard() = runTest {
        val start = CallStart().also { it.markStarted() }
        val result = CallGuard.run(start, guardMillis = 200) { awaitCancellation() }
        assertNull(result)
        assertEquals(200L, testScheduler.currentTime)
    }

    @Test
    fun withoutStartSignalTheGuardBeginsAfterTheFallback() = runTest {
        val result = CallGuard.run(CallStart(), guardMillis = 100, queueFallbackMillis = 200) { awaitCancellation() }
        assertNull(result)
        // Rückfallfrist 200 ms, dann Wache 100 ms
        assertEquals(300L, testScheduler.currentTime)
    }

    @Test
    fun errorsOfTheCallPassThrough() = runBlocking {
        val start = CallStart().also { it.markStarted() }
        try {
            CallGuard.run(start, guardMillis = 1_000) { throw IllegalStateException("boom") }
            fail("Fehler erwartet")
        } catch (expected: IllegalStateException) {
            assertEquals("boom", expected.message)
        }
    }

    @Test
    fun queuedOkHttpCallsToOneHostAllSucceed() = runBlocking {
        // Ohne feste Arbeitszeiten: Ein Platz je Host; die erste Anfrage hält ihn, bis der
        // Test sie freigibt (Latch). Die übrigen drei warten derweil nachweislich in der Warteschlange des Dispatchers —
        // länger als ihre Wache. Zählte die Wartezeit, endeten sie als Schein-Zeitüberschreitung (null). Nach der Freigabe
        // antworten alle sofort; die Wache ab dem Start ist grosszügig (keine Abhängigkeit vom Takt der CI-Maschine).
        val firstRunning = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val dispatcher = Dispatcher().apply { maxRequestsPerHost = 1 }
        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor(CallStartInterceptor)
            .addInterceptor(Interceptor { chain ->
                if (chain.request().url.encodedPath == "/1") {
                    firstRunning.countDown()
                    releaseFirst.await(LATCH_SECONDS, TimeUnit.SECONDS)
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        fun call(n: Int, guardMillis: Long) = async(Dispatchers.IO) {
            val start = CallStart()
            val request = Request.Builder().url("https://example.invalid/$n").tag(CallStart::class.java, start).build()
            CallGuard.run(start, guardMillis = guardMillis) { client.newCall(request).await().use { it.body.string() } }
        }

        // Die erste hält den Platz (ihre Wache ist so lang wie das Warten auf den Latch)
        val first = call(1, guardMillis = LATCH_SECONDS * 1_000)
        assertTrue("erste Anfrage gestartet", firstRunning.await(LATCH_SECONDS, TimeUnit.SECONDS))
        val queued = (2..4).map { call(it, guardMillis = QUEUED_GUARD_MILLIS) }
        // Auf den Zustand warten statt auf eine feste Zeit: alle drei stehen in der Warteschlange
        val allQueued = withTimeoutOrNull(LATCH_SECONDS * 1_000) {
            while (dispatcher.queuedCallsCount() < 3) delay(10)
            true
        }
        assertEquals(true, allQueued)
        // Länger in der Schlange als ihre Wache: Mehr Zeit macht den Test nur strenger, nie wackliger
        delay(QUEUED_GUARD_MILLIS + 500)
        releaseFirst.countDown()

        assertEquals(List(4) { "{}" }, (listOf(first) + queued).awaitAll())
    }

    private companion object {
        /** Obergrenze fürs Warten auf Latch und Warteschlange (nur bei einem Fehler erreicht). */
        const val LATCH_SECONDS = 30L

        /** Wache der wartenden Anfragen ab ihrem Start; sie antworten danach sofort. */
        const val QUEUED_GUARD_MILLIS = 2_000L
    }
}
