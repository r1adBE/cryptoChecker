package com.cryptochecker.app.settings

import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.TokenIdentity
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.exchange.BinanceAlpha

/**
 * «Token & TradFi» (Einstellungen › Merkliste): Was die Paar-Auswahl (Paar hinzufügen, Suche über
 * alle Börsen, «Alle …-Paare») zeigt.
 *
 *  - [TOKENS] (Standard): nur Krypto — Spot, Perpetuals und (mit QTLY) Laufzeit-Futures.
 *  - [TRADFI]: nur Futures auf Aktien, Rohstoffe, Devisen und Pre-IPO ([ExchangePair.tradFi]).
 *  - [BOTH]: alles.
 *
 * Alpha-Tokens ([AssetKind.ALPHA]) gehören zu Token; der Schalter «inkl. Alpha-Tokens»
 * ([AppSettings.includeAlphaTokens], Standard aus) blendet sie in der Auswahl aus ([allows] mit
 * `hiddenAlpha`).
 *
 * Gemerkte Paare bleiben immer in der Merkliste, die Einstellung blendet dort nichts aus.
 * Gespeichert und gesichert unter [name] (Schlüssel «asset_scope» bzw. «assetScope», wie iOS).
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
     * Paar der Börse [exchangeKey] in der Auswahl zeigen? [hiddenAlpha]: Kürzel der Alpha-Tokens, die
     * ausgeblendet sind («inkl. Alpha-Tokens» aus; [hiddenAlphaSymbols]), sonst leer. Alpha-Paare von
     * Binance selbst ([BinanceAlpha]) nur mit «inkl. Alpha-Tokens». [pairNames] (Kürzel gross → Name aus
     * der Paarliste der Börse) und [alphaNames] wie bei [AssetKind.of]: klar anderer Name → kein Alpha.
     */
    fun allows(
        pair: ExchangePair,
        exchangeKey: String = "",
        hiddenAlpha: Set<String> = emptySet(),
        pairNames: Map<String, String> = emptyMap(),
        alphaNames: Map<String, String> = emptyMap(),
    ): Boolean = when {
        pair.tradFi -> allowsTradFi
        !allowsTokens -> false
        BinanceAlpha.isAlphaPair(exchangeKey, pair.pairId) -> hiddenAlpha.isEmpty()
        hiddenAlpha.isEmpty() -> true
        else -> AssetKind.of(
            exchangeKey, pair.base, tradFi = false, alphaSymbols = hiddenAlpha,
            pairName = pairNames[TokenIdentity.key(pair.base)], alphaNames = alphaNames,
        ) != AssetKind.ALPHA
    }

    /**
     * Börse in der Auswahl zeigen? Bei «Nur TradFi» nur Börsen, deren Paarliste TradFi kennt
     * ([CoinLogos.TRADFI_EXCHANGES]) — die übrigen (auch DEX) hätten dort keine Paare.
     */
    fun allowsExchange(exchangeKey: String): Boolean = allowsTokens || exchangeKey in CoinLogos.TRADFI_EXCHANGES

    companion object {
        val DEFAULT = TOKENS

        /** Gespeicherter Name; unbekannt (neuere Version) oder leer → null. */
        fun fromNameOrNull(name: String?): AssetScope? =
            name?.trim()?.uppercase()?.let { n -> entries.firstOrNull { it.name == n } }

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
 * Nur die Paare der Börse [exchangeKey], die [scope] in der Auswahl zeigt (dieselbe Liste, wenn
 * nichts wegfällt); [hiddenAlpha] wie bei [AssetScope.allows].
 */
fun ExchangePairsInfo.scoped(
    scope: AssetScope,
    exchangeKey: String = "",
    hiddenAlpha: Set<String> = emptySet(),
    pairNames: Map<String, String> = emptyMap(),
    alphaNames: Map<String, String> = emptyMap(),
): ExchangePairsInfo {
    val keep: (ExchangePair) -> Boolean = { scope.allows(it, exchangeKey, hiddenAlpha, pairNames, alphaNames) }
    return if (pairs.all(keep)) this else copy(pairs = pairs.filter(keep))
}
