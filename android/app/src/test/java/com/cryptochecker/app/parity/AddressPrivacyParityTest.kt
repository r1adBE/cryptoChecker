package com.cryptochecker.app.parity

import com.cryptochecker.app.data.portfolio.WatchedAddressSource
import com.cryptochecker.app.data.remote.HttpUserAgent
import com.cryptochecker.app.data.remote.UserAgentInterceptor
import com.cryptochecker.app.data.remote.callMarket
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Neutraler User-Agent der Adressabfragen (`testdata/parity/address_privacy.json`): gleicher Wert wie
 * iOS, ohne App-Name/Version; Host-Regel (Adress-Hosts immer neutral, auch Gebühren/Halving) und eigener
 * Verbindungs-Pool des Adress-Clients. Gegenstück: `Tests/AddressPrivacyParityTests.swift`.
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

    /** Host → User-Agent wie iOS. */
    @Test
    fun hostRegelWieGemeinsameFaelle() {
        val app = "cryptoChecker-Android/1.0"
        for (case in fixture["hostCases"].list()) {
            val c = case as Map<*, *>
            val host = c["host"] as String
            val expected = if (c["neutral"] as Boolean) HttpUserAgent.NEUTRAL else app
            assertEquals(host, expected, HttpUserAgent.forHost(host, app))
        }
    }

    /**
     * Der gemeinsame Client setzt für Adress-Hosts den neutralen User-Agent, auch über einen mitgebrachten.
     * MockWebServer als HTTP-Proxy, damit der Interceptor den echten Host-Namen sieht.
     */
    @Test
    fun gemeinsamerClientSendetAdressHostsNeutral() {
        val server = MockWebServer()
        server.start()
        try {
            val shared = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(server.hostName, server.port)))
                .addInterceptor(UserAgentInterceptor("cryptoChecker-Android/1.0"))
                .build()
            fun send(url: String, own: String? = null): String? {
                server.enqueue(MockResponse.Builder().code(200).body("{}").build())
                val request = Request.Builder().url(url).apply { own?.let { header(HttpUserAgent.HEADER, it) } }.build()
                shared.newCall(request).execute().close()
                return server.takeRequest().headers[HttpUserAgent.HEADER]
            }
            assertEquals(HttpUserAgent.NEUTRAL, send("http://mempool.space/api/v1/fees/recommended"))
            assertEquals(HttpUserAgent.NEUTRAL, send("http://ethereum-rpc.publicnode.com/", own = "eigener/1"))
            assertEquals("cryptoChecker-Android/1.0", send("http://cloudflare-eth.com/"))
            assertEquals("eigener/1", send("http://api.binance.com/x", own = "eigener/1"))
        } finally {
            server.close()
        }
    }

    /** Eigener Verbindungs-Pool und Dispatcher des Adress-Clients. */
    @Test
    fun adressClientTeiltKeineVerbindungen() {
        val shared = OkHttpClient.Builder().addInterceptor(UserAgentInterceptor("cryptoChecker-Android/1.0")).build()
        val source = WatchedAddressSource(shared)
        assertNotSame(shared.connectionPool, source.client.connectionPool)
        assertNotSame(shared.dispatcher, source.client.dispatcher)
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
