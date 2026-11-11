package com.cryptochecker.app.domain.logos

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogoUrlPolicy] kommt ohne Parser-Bibliothek aus (:domain kennt OkHttp nicht). Hier die Gegenprobe gegen den
 * Parser, der die Anfrage stellt: dieselbe Entscheidung wie mit OkHttp (gleicher Host, Port 443, kein
 * Benutzer/Passwort, kein Fragment), auch bei schwierigen Hosts, Ports und Längen.
 */
class LogoUrlPolicyTest {

    private fun known(host: String) =
        host == "coingecko.com" || host.endsWith(".coingecko.com") || host.endsWith(".bnbstatic.com")

    /** Was OkHttp aus einer freigegebenen Adresse liest: freigegebener Host über https und Port 443, sonst nichts. */
    private fun parserAgrees(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" && known(parsed.host) && parsed.port == 443 &&
            parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null
    }

    private val hosts = listOf(
        "coingecko.com", "assets.coingecko.com", "bin.bnbstatic.com", "a.b.c.coingecko.com", "1.coingecko.com",
        "a--b.coingecko.com", "COINGECKO.com", "Assets.CoinGecko.Com", "1.2.3.4.coingecko.com", "0.bnbstatic.com",
        "bnbstatic.com", "evil.example", "coingecko.com.evil.example", "evilcoingecko.com", "127.0.0.1", "localhost",
        "xn--bcher-kva.coingecko.com", "bücher.coingecko.com", "a_b.coingecko.com", "-a.coingecko.com",
        "a-.coingecko.com", "coingecko.com.", ".coingecko.com", "a..coingecko.com", "[::1]", "",
    )
    private val ports = listOf("", ":443", ":80", ":8443", ":0443", ":", ":443:443", ":4430", ":+443", ":443x")
    private val tails = listOf(
        "", "/", "/a.png", "?x=1", "/a?b=c", "/a/%41", "/%zz", "/a%4", "/a%", "//x", "/a@b", "?a@b", "/a:b", "?",
        "/..", "/a;b=c", "/~x", "/(x)", "/a+b", "/%2F", "/%2f%5C", "?%23", "#x", "/a#x", "\\x", "/a b", "/a\tb",
    )
    private val wrappers: List<(String) -> String> = listOf(
        { it }, { "user@$it" }, { "user:pw@$it" }, { "$it@evil.example" }, { "evil.example#@$it" },
        { "evil.example\\@$it" }, { "evil.example%2e$it" }, { "%61.$it" }, { "$it%23" }, { "/$it" },
    )

    @Test
    fun entscheidetWieDerParserDesAbrufs() {
        var allowed = 0
        for (host in hosts) for (port in ports) for (tail in tails) for (wrap in wrappers) {
            val url = "https://" + wrap(host) + port + tail
            val expected = isAllowedWithParser(url)
            assertEquals(url, expected, LogoUrlPolicy.isAllowed(url))
            if (expected) {
                allowed++
                assertTrue(url, parserAgrees(url))
            }
        }
        assertTrue("freigegebene Fälle: $allowed", allowed > 200)
    }

    /** Die frühere Prüfung mit OkHttp als Parser, hier als Vergleich. */
    private fun isAllowedWithParser(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val urlCharacters = "-._~:/?@!$&'()*+,;=%"
        if (!url.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in urlCharacters }) return false
        val hex = "0123456789abcdefABCDEF"
        if (!url.indices.filter { url[it] == '%' }.all { it + 2 < url.length && url[it + 1] in hex && url[it + 2] in hex }) {
            return false
        }
        val authority = url.substring("https://".length).takeWhile { it != '/' && it != '?' }
        if (authority.isEmpty() || authority.any { it == '@' || it == '%' }) return false
        val host = (if (':' in authority) authority.removeSuffix(":443") else authority).lowercase()
        val label = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")
        if (':' in host || !host.split('.').all { label.matches(it) && !it.startsWith("xn--") }) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https" || parsed.host != host || parsed.port != 443) return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null) return false
        return known(host) && !url.contains("/missing_")
    }

    /** Label über 63 und Hosts über 253 Zeichen lehnt OkHttp ab — die Prüfung ebenso, die Grenzen selbst gelten. */
    @Test
    fun langeLabelsUndHostsWieDerParser() {
        for (length in listOf(1, 62, 63, 64, 65, 200)) {
            for (url in listOf(
                "https://${"a".repeat(length)}.coingecko.com/x.png",
                "https://x.${"a".repeat(length)}.bnbstatic.com:443/x.png",
            )) {
                assertEquals("Label $length", length <= 63, LogoUrlPolicy.isAllowed(url))
                assertEquals("Label $length", isAllowedWithParser(url), LogoUrlPolicy.isAllowed(url))
            }
        }
        val label = "b".repeat(58) + "."
        for (first in 1..10) {
            // Anfang + «.» + 4 Labels zu 58 mit Punkten (235) + «.coingecko.com» (14): Hostlänge 250 + first
            val host = "c".repeat(first) + "." + label.repeat(4).dropLast(1) + ".coingecko.com"
            val url = "https://$host/x.png"
            assertEquals("Host ${host.length}", host.length <= 253, LogoUrlPolicy.isAllowed(url))
            assertEquals("Host ${host.length}", isAllowedWithParser(url), LogoUrlPolicy.isAllowed(url))
        }
    }

    @Test
    fun schemaNurKleinUndHttps() {
        for (url in listOf(
            "HTTPS://coingecko.com/x.png", "Https://coingecko.com/x.png", "http://coingecko.com/x.png",
            "https:/coingecko.com/x.png", "https:///coingecko.com/x.png", "//coingecko.com/x.png",
            " https://coingecko.com/x.png", "https://coingecko.com/x.png ", "",
        )) {
            assertFalse(url, LogoUrlPolicy.isAllowed(url))
        }
        assertFalse(LogoUrlPolicy.isAllowed(null))
        assertTrue(LogoUrlPolicy.isAllowed("https://coingecko.com/x.png"))
        assertFalse(LogoUrlPolicy.isAllowed("https://coingecko.com/missing_large.png"))
    }
}
