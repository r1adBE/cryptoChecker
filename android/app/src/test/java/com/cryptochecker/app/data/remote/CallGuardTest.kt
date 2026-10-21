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
import org.junit.Assert.fail
import org.junit.Test

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
        // Ein Platz je Host: vier Anfragen à 500 ms brauchen zusammen ~2 s, die Wache je Anfrage ist 1,5 s — die letzte
        // wartet 1,5 s in der Warteschlange und endete ohne CallStart als Schein-Zeitüberschreitung. Je Anfrage bleibt
        // 1 s Luft für eine langsame CI-Maschine (echte Threads, keine virtuelle Zeit)
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 1 })
            .addInterceptor(CallStartInterceptor)
            .addInterceptor(Interceptor { chain ->
                Thread.sleep(500)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val results = (1..4).map { n ->
            async(Dispatchers.IO) {
                val start = CallStart()
                val request = Request.Builder().url("https://example.invalid/$n").tag(CallStart::class.java, start).build()
                CallGuard.run(start, guardMillis = 1_500) { client.newCall(request).await().use { it.body.string() } }
            }
        }.awaitAll()
        assertEquals(List(4) { "{}" }, results)
    }
}
