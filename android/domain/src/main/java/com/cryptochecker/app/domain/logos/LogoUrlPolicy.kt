package com.cryptochecker.app.domain.logos

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Freigabe einer Logo-Adresse ([CoinLogos.isAllowedUrl]) mit echtem URL-Parser statt
 * ausgeschnittenem Host: Ein «#» oder «\» in der Authority (der Parser beendet den Host dort) kann die Liste nicht mehr
 * umgehen. Gleich in iOS `LogoURLPolicy` (gemeinsame Fälle `testdata/parity/logo_url_allow.json`).
 *
 * - nur `https://`, nur URL-Zeichen in ASCII (kein Leerzeichen, kein «\», kein «#» = kein Fragment, «%» nur mit zwei
 *   Hex-Ziffern); Authority ohne «@» (Benutzer/Passwort) und ohne «%»
 * - Host nur aus DNS-Labels (a–z 0–9 «-», keine leeren Labels, kein «xn--»), Port fehlt oder genau 443
 * - der Parser (OkHttp) muss denselben Host, Port 443, kein Benutzer/Passwort und kein Fragment ergeben
 * - Host `coingecko.com`, `*.coingecko.com` oder `*.bnbstatic.com`; kein Platzhalter `/missing_…`
 */
object LogoUrlPolicy {
    private const val PREFIX = "https://"
    private const val URL_PUNCTUATION = "-._~:/?@!$&'()*+,;=%"
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")

    fun isAllowed(url: String?): Boolean {
        if (url == null || !url.startsWith(PREFIX)) return false
        if (!url.all { it.isAsciiLetterOrDigit() || it in URL_PUNCTUATION } || !validPercents(url)) return false
        val authority = url.substring(PREFIX.length).takeWhile { it != '/' && it != '?' }
        if (authority.isEmpty() || authority.any { it == '@' || it == '%' }) return false
        val host = (if (':' in authority) authority.removeSuffix(":443") else authority).lowercase()
        if (':' in host || !host.split('.').all { LABEL.matches(it) && !it.startsWith("xn--") }) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https" || parsed.host != host || parsed.port != 443) return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null) return false
        val known = host == "coingecko.com" || host.endsWith(".coingecko.com") || host.endsWith(".bnbstatic.com")
        // Platzhalter für Coins ohne Logo («missing_large.png»): lieber Initialen
        return known && !url.contains("/missing_")
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    // «%» nur als Kodierung mit zwei Hex-Ziffern (sonst lesen OkHttp und Foundation die Adresse verschieden)
    private fun validPercents(url: String): Boolean = url.indices.filter { url[it] == '%' }.all { i ->
        i + 2 < url.length && url[i + 1].isHex() && url[i + 2].isHex()
    }

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
