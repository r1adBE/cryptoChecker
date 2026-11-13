package com.cryptochecker.app.data

import com.cryptochecker.app.domain.logos.CoinLogoRetry
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Einordnung einer Bild-Antwort ([LogoImageDownload.png]): Pause des Servers, Fehler mit Code (kurze Störung oder
 * «gibt es nicht», [CoinLogoRetry.isTransient]) und keine Antwort (IOException = kurze Störung, keine Tagessperre).
 */
class LogoImageDownloadTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun fetch(): ByteArray? = runBlocking { LogoImageDownload.png(client, server.url("/logo.png").toString(), "test") }

    private fun httpCode(response: MockResponse): Int {
        server.enqueue(response)
        try {
            fetch()
        } catch (e: LogoImageDownload.HttpError) {
            return e.code
        }
        fail("expected HttpError")
        return 0
    }

    @Test
    fun serverErrorsAreTransientMissingFilesAreNot() {
        // 408 nicht hier: OkHttp wiederholt die Anfrage dabei selbst einmal (Regel in CoinLogoRetry, Parität)
        for (code in listOf(500, 502, 504)) {
            val got = httpCode(MockResponse.Builder().code(code).build())
            assertEquals(code, got)
            assertTrue("$code", CoinLogoRetry.isTransient(got))
        }
        for (code in listOf(404, 403, 410)) {
            val got = httpCode(MockResponse.Builder().code(code).build())
            assertFalse("$code", CoinLogoRetry.isTransient(got))
        }
    }

    @Test
    fun rateLimitPausesTheServer() {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "30").build())
        try {
            fetch()
            fail("expected Pause")
        } catch (e: LogoImageDownload.Pause) {
            assertEquals(30L, e.retryAfterSeconds)
        }
    }

    @Test
    fun noAnswerIsAnIoException() {
        // Antwort kommt nicht rechtzeitig (Lesezeit 1 s): kurze Störung
        server.enqueue(MockResponse.Builder().code(200).body("x").headersDelay(3, TimeUnit.SECONDS).build())
        try {
            fetch()
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(CoinLogoRetry.isTransient(null))
        }
    }
}
