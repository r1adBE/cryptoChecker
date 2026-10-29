package com.cryptochecker.app.parity

import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.data.portfolio.WatchedAddressSource
import com.cryptochecker.app.data.remote.HttpUserAgent
import com.cryptochecker.app.data.remote.UserAgentInterceptor
import com.cryptochecker.app.data.remote.callExchange
import com.cryptochecker.app.domain.portfolio.AddressRedirect
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * Neutraler User-Agent der Adressabfragen (`testdata/parity/address_privacy.json`): gleicher Wert wie
 * iOS, ohne App-Name/Version; Host-Regel (Adress-Hosts immer neutral, auch Gebühren/Halving) und eigener
 * Verbindungs-Pool des Adress-Clients. Gegenstück: `Tests/AddressPrivacyParityTests`.
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

    /** Weder App-Name noch App-Version (Beispiele und Version dieser App), wie iOS. */
    @Test
    fun neutralerUserAgentOhneAppNameUndVersion() {
        val versions = fixture["appVersionSamples"].list().map { it as String } + BuildConfig.VERSION_NAME
        for (version in versions) {
            val appAgent = HttpUserAgent.android(version)
            assertFalse(appAgent, HttpUserAgent.NEUTRAL.contains(appAgent, ignoreCase = true))
            assertFalse("Version $version im User-Agent", HttpUserAgent.NEUTRAL.contains(version.trim()))
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

    /** Weiterleitungen nur auf denselben Host, nie von HTTPS auf HTTP — wie iOS. */
    @Test
    fun weiterleitungNurAufDenselbenHost() {
        for (case in fixture["redirectCases"].list()) {
            val c = case as Map<*, *>
            val from = URI(c["from"] as String)
            val to = URI(c["to"] as String)
            assertEquals("${c["from"]} → ${c["to"]}", c["follow"], AddressRedirect.allowed(from.scheme, from.host, to.scheme, to.host))
        }
    }

    /** Der Adress-Client folgt einer Weiterleitung auf denselben Host, aber nie auf einen anderen. */
    @Test
    fun adressClientFolgtKeinerWeiterleitungZuEinemAnderenHost() {
        val server = MockWebServer()
        server.start()
        try {
            val source = WatchedAddressSource(OkHttpClient())
            // Gleicher Host: gefolgt
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/api/address/y").build())
            server.enqueue(MockResponse.Builder().code(200).body("{}").build())
            assertEquals("{}", runBlocking { source.client.callExchange(server.url("/api/address/x").toString(), null) })
            assertEquals(2, server.requestCount)
            // Anderer Host (127.0.0.1 statt des Namens): nicht gefolgt, die Weiterleitung ist die Antwort
            val other = "http://127.0.0.1:${server.port}/api/address/z"
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other).build())
            val url = server.url("/api/address/x").newBuilder().host("localhost").build().toString()
            val failure = runCatching { runBlocking { source.client.callExchange(url, null) } }.exceptionOrNull()
            assertTrue(failure is com.cryptochecker.app.domain.exceptions.HttpExchangeError)
            assertEquals(3, server.requestCount)
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
            runBlocking { source.client.callExchange(server.url("/api/address/x").toString(), null) }
            assertEquals(HttpUserAgent.NEUTRAL, server.takeRequest().headers[HttpUserAgent.HEADER])
        } finally {
            server.close()
        }
    }
}
