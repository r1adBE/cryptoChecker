package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.marketdata.model.market.BinanceAlpha
import com.cryptochecker.app.domain.logos.alphaSymbols

/**
 * Art eines Paars — kleines Symbol vor der Glocke in der Merkliste, Chip «Alpha» und «inkl.
 * Alpha-Tokens» in der Paar-Auswahl. Wie `AssetKind` (iOS, `WatchFilter.swift`).
 *
 *  - [TRADFI]: Futures auf Aktien, Rohstoffe, Devisen, Pre-IPO (Kennzeichen der Paarliste).
 *  - [ALPHA]: Token aus der Binance-Alpha-Liste, die Binance nicht regulär am Spot führt — frühe,
 *    meist kleine Projekte. Gilt nur als Alpha, obwohl es auch ein Token ist.
 *  - [TOKEN]: alles übrige (Krypto: Spot, Perpetuals, Laufzeit-Futures).
 */
enum class AssetKind {
    TOKEN,
    TRADFI,
    ALPHA;

    companion object {
        /** Spot-Börse von Binance: was dort gehandelt wird, ist regulär gelistet — nie Alpha. */
        const val BINANCE_SPOT_MARKET = "Binance"

        /**
         * Art eines Paars. [alphaSymbols]: Kürzel der Alpha-Liste ohne die regulär an Binance Spot
         * gelisteten (wie [CoinLogos.normalize]; aus der Alpha-Liste, [CoinLogos.alphaSymbols]).
         * Leer (Liste noch nie geladen, Logos aus) → nie Alpha. DEX-Pools nie: Dort darf ein
         * fremder Token gleich heissen. Paare von Binance Spot nie: dort regulär gelistet.
         * Hebel-Kürzel zählen wie das Kürzel («1000AIA» → «AIA»).
         *
         * Alpha-Paare von Binance selbst (Kennung «ALPHA_<id>USDT», [BinanceAlpha.isAlphaPair]) sind
         * immer Alpha. Weniger Fehltreffer: Kennen beide Seiten einen Namen ([pairName] aus der Paarliste
         * der Börse, [alphaNames] je Kürzel aus der Alpha-Liste) und widersprechen sie sich klar
         * ([TokenIdentity.compatible]), ist es ein anderer Token gleichen Kürzels — kein Alpha.
         */
        fun of(
            marketKey: String,
            base: String,
            tradFi: Boolean,
            alphaSymbols: Set<String>,
            pairId: String? = null,
            pairName: String? = null,
            alphaNames: Map<String, String> = emptyMap(),
        ): AssetKind {
            if (tradFi) return TRADFI
            if (BinanceAlpha.isAlphaPair(marketKey, pairId)) return ALPHA
            if (alphaSymbols.isEmpty()) return TOKEN
            if (marketKey == CoinLogos.DEX_MARKET_KEY || marketKey == BINANCE_SPOT_MARKET) return TOKEN
            val symbol = CoinLogos.normalize(base)
            if (symbol !in alphaSymbols) return TOKEN
            return if (TokenIdentity.compatible(symbol, pairName, alphaNames[symbol])) ALPHA else TOKEN
        }
    }
}
