package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.mirror.MirrorManifest
import com.cryptochecker.app.parity.ParityJson
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Grössengrenzen von Manifest und Signatur über den echten Ladeweg ([mirrorLoader] → OkHttp → Server): zu gross
 * (angekündigt oder ohne Länge) zählt wie fehlend, genau an der Grenze wird gelesen. Wie iOS `MirrorLoadLimitTests.swift`.
 */
class MirrorLoadLimitTest {

    private lateinit var server: MockWebServer

    /** Adressen, wie die App sie angefragt hat (vor der Umleitung auf den Server). */
    private val requested: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val data = ParityJson.load("mirror_signature.json")
    private val testKey = data["publicKey"] as String
    private val fixtureNow = (data["now"] as Number).toLong()

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = (data[key] as List<Any?>).map { it as Map<String, Any?> }

    private val valid = list("verify").first { it["name"] == "valid" }
    private val manifest = valid["manifest"] as String
    private val signature = valid["signature"] as String
    private val okText = list("files").first { it["name"] == "listed and same bytes" }["text"] as String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** Leitet die GitHub-Adressen auf den Server um; Antworten in Reihenfolge der Warteschlange. */
    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(5_000, TimeUnit.MILLISECONDS)
        .addInterceptor(Interceptor { chain ->
            val original = chain.request()
            requested += original.url.toString()
            chain.proceed(original.newBuilder().url(server.url("/" + original.url.host + original.url.encodedPath)).build())
        })
        .build()

    private fun respond(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    private fun verify(trust: MirrorTrust): Boolean =
        runBlocking { trust.verified("fng.txt", okText, client().mirrorLoader(10_000)) }

    private fun trust() = MirrorTrust(testKey) { fixtureNow }

    @Test
    fun validManifestOverHttpIsAccepted() {
        respond(manifest)
        respond(signature)
        val trust = trust()
        assertTrue(verify(trust))
        assertEquals(listOf(MirrorManifest.URL, MirrorManifest.SIGNATURE_URL), requested)
    }

    @Test
    fun announcedManifestAboveTheLimitCountsAsMissing() {
        respond(manifest + " ".repeat(MirrorManifest.MAX_BYTES.toInt()))
        val trust = trust()
        assertFalse(verify(trust))
        assertEquals(MirrorManifest.Status.MISSING, trust.lastStatus)
        // Ohne Manifest keine Signatur-Anfrage
        assertEquals(listOf(MirrorManifest.URL), requested)
    }

    @Test
    fun chunkedManifestAboveTheLimitCountsAsMissing() {
        val body = manifest + " ".repeat(MirrorManifest.MAX_BYTES.toInt())
        server.enqueue(MockResponse.Builder().code(200).chunkedBody(body, 1_024).build())
        val trust = trust()
        assertFalse(verify(trust))
        assertEquals(MirrorManifest.Status.MISSING, trust.lastStatus)
    }

    @Test
    fun manifestExactlyAtTheLimitIsRead() {
        // Gelesen, aber durch die Auffüllung passt die Signatur nicht mehr: INVALID statt MISSING
        respond(manifest + " ".repeat(MirrorManifest.MAX_BYTES.toInt() - manifest.toByteArray(Charsets.UTF_8).size))
        respond(signature)
        val trust = trust()
        assertFalse(verify(trust))
        assertEquals(MirrorManifest.Status.INVALID, trust.lastStatus)
    }

    @Test
    fun signatureAboveTheLimitCountsAsMissing() {
        respond(manifest)
        respond(signature + "\n".repeat(MirrorManifest.SIGNATURE_MAX_BYTES.toInt() - signature.length + 1))
        val trust = trust()
        assertFalse(verify(trust))
        assertEquals(MirrorManifest.Status.MISSING, trust.lastStatus)
    }

    @Test
    fun signatureExactlyAtTheLimitIsAccepted() {
        respond(manifest)
        // Zeilenumbrüche am Ende fallen beim Prüfen weg
        respond(signature + "\n".repeat(MirrorManifest.SIGNATURE_MAX_BYTES.toInt() - signature.length))
        val trust = trust()
        assertTrue(verify(trust))
        assertEquals(MirrorManifest.Status.OK, trust.lastStatus)
    }
}
