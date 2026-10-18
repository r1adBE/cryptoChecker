package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.remote.util.CallGuard
import com.cryptochecker.app.data.remote.util.CallStart
import com.cryptochecker.app.data.remote.util.CallStartInterceptor
import com.cryptochecker.app.data.remote.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

/**
 * Wache der Börsenanfragen ([CallGuard]): Die Frist läuft erst ab der Ausführung ([CallStart]); wer in der
 * Warteschlange des Dispatchers wartet, endet nicht als Schein-Zeitüberschreitung (die sonst die Börse pausierte).
 */
class CallGuardTest {

    @Test
    fun waitingInTheQueueDoesNotCount() = runBlocking {
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
    }

    @Test
    fun hangingCallEndsAfterTheGuard() = runBlocking {
        val start = CallStart().also { it.markStarted() }
        val begun = System.currentTimeMillis()
        val result = CallGuard.run(start, guardMillis = 200) { awaitCancellation() }
        assertNull(result)
        assertTrue(System.currentTimeMillis() - begun < 2_000)
    }

    @Test
    fun withoutStartSignalTheGuardBeginsAfterTheFallback() = runBlocking {
        val begun = System.currentTimeMillis()
        val result = CallGuard.run(CallStart(), guardMillis = 100, queueFallbackMillis = 200) { awaitCancellation() }
        assertNull(result)
        val took = System.currentTimeMillis() - begun
        assertTrue("etwa 300 ms, war $took", took in 250..2_000)
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
        // Ein Platz je Host: drei Anfragen à 300 ms brauchen zusammen ~900 ms, die Wache je Anfrage ist 600 ms
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 1 })
            .addInterceptor(CallStartInterceptor)
            .addInterceptor(Interceptor { chain ->
                Thread.sleep(300)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val results = (1..3).map { n ->
            async(Dispatchers.IO) {
                val start = CallStart()
                val request = Request.Builder().url("https://example.invalid/$n").tag(CallStart::class.java, start).build()
                CallGuard.run(start, guardMillis = 600) { client.newCall(request).await().use { it.body.string() } }
            }
        }.awaitAll()
        assertEquals(listOf("{}", "{}", "{}"), results)
    }
}
