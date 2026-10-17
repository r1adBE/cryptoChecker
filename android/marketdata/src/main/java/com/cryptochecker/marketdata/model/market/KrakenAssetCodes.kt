package com.cryptochecker.marketdata.model.market

/**
 * Kraken-Kürzel ↔ gebräuchliche Kürzel.
 *
 * Basis und Gegenwährung kommen aus Krakens eigenem Feld `wsname` («XBT/USD») der AssetPairs: Es trennt die beiden
 * Kürzel schon und trägt keine alten «X»/«Z»-Präfixe (`base` «XXBT», `quote` «ZUSD»), darum braucht es keine
 * eigene Liste der Präfix-Assets. Übrig bleiben die zwei Eigennamen, die Kraken heute noch führt (XBT, XDG).
 * Gegenstück auf iOS: `KrakenAssetCodes` in `Kraken.swift`.
 */
object KrakenAssetCodes {

    /** Krakens Eigennamen → übliche Kürzel. */
    private val commonByKraken: Map<String, String> = mapOf("XBT" to "BTC", "XDG" to "DOGE")

    /** Umkehrung für Paar-Kennungen, die aus Kürzeln zusammengesetzt werden. */
    private val krakenByCommon: Map<String, String> = commonByKraken.entries.associate { (kraken, common) -> common to kraken }

    /**
     * «XBT/USD» → BTC und USD. null, wenn `wsname` nicht genau zwei nicht leere Kürzel enthält — das Paar fällt dann
     * weg, statt mit einem rohen Kürzel wie «XXBT» als eigene Währung aufzutauchen.
     */
    fun fromWsName(wsName: String): Pair<String, String>? {
        val parts = wsName.split('/')
        if (parts.size != 2 || parts.any { it.isEmpty() }) return null
        return common(parts[0]) to common(parts[1])
    }

    /** Kraken-Kürzel → übliches Kürzel (XBT → BTC, XDG → DOGE); sonst unverändert. */
    fun common(code: String): String = commonByKraken[code] ?: code

    /** Übliches Kürzel → Kraken-Kürzel für Paar-Kennungen (BTC → XBT, DOGE → XDG); sonst unverändert. */
    fun krakenCode(code: String): String = krakenByCommon[code] ?: code
}
