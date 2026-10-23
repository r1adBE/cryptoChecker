package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.logos.CoinLogos
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freigabe von Logo-Adressen (`testdata/parity/logo_url_allow.json`, SEC3-02): die Prüfung entscheidet
 * wie der URL-Parser, der die Anfrage stellt. Gegenstück: `Tests/LogoURLAllowParityTests.swift`.
 */
class LogoUrlAllowParityTest {

    private val cases = ParityJson.load("logo_url_allow.json")["cases"].list().map { it.obj() }

    @Test
    fun freigabeWieGemeinsameFaelle() {
        for (c in cases) {
            assertEquals("allowed: $c", c["expected"] as Boolean, CoinLogos.isAllowedUrl(c["url"] as String?))
        }
    }

    /** Jede freigegebene Adresse geht beim Abruf (OkHttp) wirklich an einen freigegebenen Host. */
    @Test
    fun freigegebeneAdresseLaedtVomFreigegebenenHost() {
        for (c in cases.filter { it["expected"] as Boolean }) {
            val host = (c["url"] as String).toHttpUrlOrNull()?.host.orEmpty()
            val known = host == "coingecko.com" || host.endsWith(".coingecko.com") || host.endsWith(".bnbstatic.com")
            assertTrue("host: $c", known)
        }
    }

    /** Gegenprobe zum alten Fehler: bei «#»/«\» endet der Host für OkHttp vor «.coingecko.com». */
    @Test
    fun parserBeendetHostAnRaute() {
        assertEquals("evil.example", "https://evil.example#.coingecko.com/a.png".toHttpUrlOrNull()?.host)
        assertEquals("evil.example", "https://evil.example\\.coingecko.com/a.png".toHttpUrlOrNull()?.host)
    }
}
