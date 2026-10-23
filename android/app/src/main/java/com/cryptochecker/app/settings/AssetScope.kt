package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.TokenIdentity
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.market.BinanceAlpha

/**
 * «Token & TradFi» (Einstellungen › Erweitert): Was die Paar-Auswahl (Paar hinzufügen, Suche über
 * alle Börsen, «Alle …-Paare») zeigt.
 *
 *  - [TOKENS] (Standard): nur Krypto — Spot, Perpetuals und (mit QTLY) Laufzeit-Futures.
 *  - [TRADFI]: nur Futures auf Aktien, Rohstoffe, Devisen und Pre-IPO ([MarketPair.tradFi]).
 *  - [BOTH]: alles.
 *
 * Alpha-Tokens ([AssetKind.ALPHA]) gehören zu Token; der Schalter «inkl. Alpha-Tokens»
 * ([AppSettings.includeAlphaTokens], Standard aus) blendet sie in der Auswahl aus ([allows] mit
 * `hiddenAlpha`).
 *
 * Gemerkte Paare bleiben immer in der Merkliste, die Einstellung blendet dort nichts aus.
 * Gespeichert und gesichert unter [name] (Schlüssel «asset_scope» bzw. «assetScope», wie iOS).
 * Ersetzt den früheren Schalter «include_tradfi_futures» / «includeTradFiFutures»: an → [BOTH],
 * aus → [TOKENS] ([fromLegacy]).
 */
enum class AssetScope {
    TOKENS,
    TRADFI,
    BOTH;

    /** TradFi-Kontrakte erscheinen in der Auswahl. */
    val allowsTradFi: Boolean get() = this != TOKENS

    /** Krypto-Paare (Token) erscheinen in der Auswahl. */
    val allowsTokens: Boolean get() = this != TRADFI

    /**
     * Paar der Börse [marketKey] in der Auswahl zeigen? [hiddenAlpha]: Kürzel der Alpha-Tokens, die
     * ausgeblendet sind («inkl. Alpha-Tokens» aus; [hiddenAlphaSymbols]), sonst leer. Alpha-Paare von
     * Binance selbst ([BinanceAlpha]) nur mit «inkl. Alpha-Tokens». [pairNames] (Kürzel gross → Name aus
     * der Paarliste der Börse) und [alphaNames] wie bei [AssetKind.of]: klar anderer Name → kein Alpha.
     */
    fun allows(
        pair: MarketPair,
        marketKey: String = "",
        hiddenAlpha: Set<String> = emptySet(),
        pairNames: Map<String, String> = emptyMap(),
        alphaNames: Map<String, String> = emptyMap(),
    ): Boolean = when {
        pair.tradFi -> allowsTradFi
        !allowsTokens -> false
        BinanceAlpha.isAlphaPair(marketKey, pair.pairId) -> hiddenAlpha.isEmpty()
        hiddenAlpha.isEmpty() -> true
        else -> AssetKind.of(
            marketKey, pair.base, tradFi = false, alphaSymbols = hiddenAlpha,
            pairName = pairNames[TokenIdentity.key(pair.base)], alphaNames = alphaNames,
        ) != AssetKind.ALPHA
    }

    /**
     * Börse in der Auswahl zeigen? Bei «Nur TradFi» nur Börsen, deren Paarliste TradFi kennt
     * ([CoinLogos.TRADFI_MARKETS]) — die übrigen (auch DEX) hätten dort keine Paare.
     */
    fun allowsMarket(marketKey: String): Boolean = allowsTokens || marketKey in CoinLogos.TRADFI_MARKETS

    companion object {
        val DEFAULT = TOKENS

        /** Gespeicherter Name; unbekannt (neuere Version) oder leer → null. */
        fun fromNameOrNull(name: String?): AssetScope? =
            name?.trim()?.uppercase()?.let { n -> entries.firstOrNull { it.name == n } }

        /** Früherer Schalter «TradFi»: an → [BOTH], sonst [TOKENS]. */
        fun fromLegacy(includeTradFi: Boolean?): AssetScope = if (includeTradFi == true) BOTH else TOKENS

        /**
         * Gespeicherte Einstellung: der neue Wert, sonst (Update von einer älteren Version) der
         * frühere Schalter, sonst der Standard.
         */
        fun resolve(stored: String?, legacyTradFi: Boolean?): AssetScope =
            fromBackup(stored, legacyTradFi) ?: DEFAULT

        /**
         * Aus einer Sicherung: «assetScope», sonst «includeTradFiFutures» (ältere Sicherung);
         * fehlen beide, null = aktuelle Einstellung stehen lassen.
         */
        fun fromBackup(scope: String?, legacyTradFi: Boolean?): AssetScope? =
            fromNameOrNull(scope) ?: legacyTradFi?.let { fromLegacy(it) }

        /**
         * Platzhalter, wenn «inkl. Alpha-Tokens» aus ist, aber noch keine Alpha-Liste vorliegt: hält
         * [hiddenAlphaSymbols] nicht leer, damit die Alpha-Paare von Binance trotzdem ausgeblendet sind
         * (kein gültiges Kürzel, trifft nie ein Paar).
         */
        const val HIDE_ALPHA_MARK = "#alpha"

        /**
         * Auszublendende Alpha-Kürzel für [allows]: «inkl. Alpha-Tokens» an → keine, aus → alle
         * bekannten ([alphaSymbols]; ohne Liste [HIDE_ALPHA_MARK]).
         */
        fun hiddenAlphaSymbols(includeAlpha: Boolean, alphaSymbols: Set<String>): Set<String> = when {
            includeAlpha -> emptySet()
            alphaSymbols.isEmpty() -> setOf(HIDE_ALPHA_MARK)
            else -> alphaSymbols
        }
    }
}

/**
 * Nur die Paare der Börse [marketKey], die [scope] in der Auswahl zeigt (dieselbe Liste, wenn
 * nichts wegfällt); [hiddenAlpha] wie bei [AssetScope.allows].
 */
fun MarketPairsInfo.scoped(
    scope: AssetScope,
    marketKey: String = "",
    hiddenAlpha: Set<String> = emptySet(),
    pairNames: Map<String, String> = emptyMap(),
    alphaNames: Map<String, String> = emptyMap(),
): MarketPairsInfo {
    val keep: (MarketPair) -> Boolean = { scope.allows(it, marketKey, hiddenAlpha, pairNames, alphaNames) }
    return if (pairs.all(keep)) this else copy(pairs = pairs.filter(keep))
}
