package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rate-Limit ohne HTTP 429/418 (Kraken, Bybit, OKX, BingX, bitbank). Fixture `rate_limit_signal.json`, wie `RateLimitSignalTests.swift`. */
class RateLimitSignalTest {

    @Test
    fun `fixture - status passed on and classified error`() {
        val data = ParityJson.load("rate_limit_signal.json")
        data["httpCode"].list().forEach { raw ->
            val c = raw.obj()
            val status = c["status"].num()!!.toInt()
            val body = c["body"] as String?
            assertEquals("httpCode: $status $body", c["code"].num()?.toInt(), RateLimitSignal.httpCode(status, body))
        }
        data["classify"].list().forEach { raw ->
            val c = raw.obj()
            val error = c["error"] as String
            assertEquals("classify: $error", c["failure"], RefreshReportLogic.classify(error).name)
        }
    }

    @Test
    fun `long ok bodies are not scanned, error bodies only their start`() {
        val long = "x".repeat(RateLimitSignal.MAX_OK_BODY) + "Too many requests"
        assertNull(RateLimitSignal.httpCode(200, long))
        assertNull(RateLimitSignal.httpCode(403, long))
        assertEquals(429, RateLimitSignal.httpCode(403, "Too many requests" + long))
        assertTrue(RateLimitSignal.inOkBody("{\"code\":\"50011\",\"msg\":\"\"}"))
        assertFalse(RateLimitSignal.inOkBody("{\"code\":\"0\",\"data\":[]}"))
    }

    @Test
    fun `readable ok bodies count only in error fields`() {
        // Kursliste bzw. Kurs, dessen Name «rate limit» enthält: keine Bremse
        assertFalse(RateLimitSignal.okBodyRateLimited("""[{"symbol":"X","name":"Rate limit exceeded token"}]"""))
        assertFalse(RateLimitSignal.okBodyRateLimited("""{"result":{"desc":"too many requests coin"},"error":[]}"""))
        // Fehlerfelder und Codes
        assertTrue(RateLimitSignal.okBodyRateLimited("""{"error":["EAPI:Rate limit exceeded"]}"""))
        assertTrue(RateLimitSignal.okBodyRateLimited("""{"msg":"Too many requests"}"""))
        assertTrue(RateLimitSignal.okBodyRateLimited("""{"retCode":"10006"}"""))
        // Unlesbar: der ganze Text
        assertTrue(RateLimitSignal.okBodyRateLimited("Too many requests"))
        assertTrue(RateLimitSignal.okBodyRateLimited("""[{"msg":"Too many requests"""))
    }

    @Test
    fun `rate limit text stops further singles`() {
        assertTrue(SingleRateGate.isRateLimit("EGeneral:Too many requests"))
        assertFalse(SingleRateGate.isRateLimit("EQuery:Unknown asset pair"))
    }
}
