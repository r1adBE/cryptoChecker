package com.cryptochecker.marketdata.model.market

/**
 * Normalisiert Kraken-Asset-Codes aus /0/public/AssetPairs.
 *
 * Kraken führt ältere Assets mit einem 4-stelligen Präfix-Code (X + Krypto, Z + Fiat,
 * z. B. XXBT, XETH, ZUSD). Nur diese bekannten Legacy-Codes werden gekürzt; alle
 * übrigen Codes (XTZ, ZRX, ZETA, XCN, ZK, ZEC …) bleiben unverändert. Danach werden
 * Krakens Sonderkürzel XBT/XDG/XVN auf BTC/DOGE/VEN abgebildet.
 */
object KrakenAssetCodes {

    /** Krypto-Codes, die Kraken mit vorangestelltem X führt (XXBT, XETH, …). */
    private val LEGACY_X = setOf(
        "XBT", "ETH", "LTC", "XRP", "XLM", "XMR", "ZEC", "REP", "ETC", "MLN", "XDG", "NMC", "XVN", "ICN",
    )

    /** Fiat-Codes, die Kraken mit vorangestelltem Z führt (ZUSD, ZEUR, …). */
    private val LEGACY_Z = setOf(
        "USD", "EUR", "GBP", "CAD", "JPY", "CHF", "AUD", "KRW",
    )

    /** Krakens Sonderkürzel → gebräuchlicher Code. */
    private val ALIASES = mapOf(
        "XBT" to "BTC",
        "XDG" to "DOGE",
        "XVN" to "VEN",
    )

    fun normalize(code: String): String {
        val stripped = if (code.length == 4) {
            val rest = code.substring(1)
            when (code[0]) {
                'X' -> if (rest in LEGACY_X) rest else code
                'Z' -> if (rest in LEGACY_Z) rest else code
                else -> code
            }
        } else {
            code
        }
        return ALIASES[stripped] ?: stripped
    }
}
