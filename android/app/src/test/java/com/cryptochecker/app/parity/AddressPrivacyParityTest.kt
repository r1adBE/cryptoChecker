package com.cryptochecker.app.parity

import com.cryptochecker.app.data.portfolio.WatchedAddressSource
import com.cryptochecker.app.data.remote.HttpUserAgent
import com.cryptochecker.app.data.remote.UserAgentInterceptor
import com.cryptochecker.app.data.remote.callMarket
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Neutraler User-Agent der Adressabfragen (`testdata/parity/address_privacy.json`, seit Build 103): gleicher Wert wie
 * iOS, ohne App-Name/Version. Gegenstück: `Tests/AddressPrivacyParityTests.swift`.
 */
class AddressPrivacyParityTest {

    private val fixture = ParityJson.load("address_privacy.json")

    @Test
    fun neutralerUserAgentWieGemeinsamerWert() {
        assertEquals(fixture["userAgent"], HttpUserAgent.NEUTRAL)
        for (part in fixture["forbiddenParts"].list()) {
            assertFalse("$part im User-Agent", HttpUserAgent.NEUTRAL.contains(part as String, ignoreCase = true))
        }
    }

    /** Der Client von [WatchedAddressSource] ersetzt den App-User-Agent des gemeinsamen Clients. */
    @Test
    fun adressClientSendetNeutralenUserAgent() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            val shared = OkHttpClient.Builder().addInterceptor(UserAgentInterceptor("cryptoChecker-Android/1.0")).build()
            val source = WatchedAddressSource(shared)
            runBlocking { source.client.callMarket(server.url("/api/address/x").toString(), null) }
            assertEquals(HttpUserAgent.NEUTRAL, server.takeRequest().headers[HttpUserAgent.HEADER])
        } finally {
            server.close()
        }
    }
}
