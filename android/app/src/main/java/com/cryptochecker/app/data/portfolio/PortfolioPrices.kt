package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.ChainAssets
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.PricePlausibility

// Ergebnis der Portfolio-Kurse ([PortfolioPriceSource]): Kurse je Coin samt Herkunft und Frische, Grund für fehlende Kurse.

/** Aktuelle USDT-Kurse je Coin und der Zeitpunkt der ältesten verwendeten Abfrage. */
data class PortfolioPrices(
    val prices: Map<String, Double> = emptyMap(),
    /** 0 = noch keine Kurse. */
    val updatedAt: Long = 0,
    /**
     * Coins OHNE Kurs, weil nur die letzte Stundenkerze einen hatte und er mangels unabhängigem Bezug
     * nicht geprüft werden konnte ([PricePlausibility.candleUnchecked]): Sie stehen nicht in [prices],
     * zählen also nirgends als Geld; die Zeile nennt sie «ungeprüft».
     */
    val unchecked: Set<String> = emptySet(),
    /** Abfragezeit je Coin (Epoch-ms) — für die Frische je Coin ([PortfolioPriceFallback.freshShare]). */
    val times: Map<String, Long> = emptyMap(),
    /** Coin → Börse, deren Kurs gilt (eingetragene Börse, [PortfolioExchangeRoute]); fehlt = Binance. */
    val sources: Map<String, String> = emptyMap(),
    /** Coin → eingetragene Börse, deren Kurs diesmal nicht ging (dann Binance). */
    val exchangeFailed: Map<String, String> = emptyMap(),
    /** Coins mit mehreren eingetragenen Börsen — bewertet mit Binance. */
    val mixedExchange: Set<String> = emptySet(),
    /** Binance-Kurs der Coins mit eigener Börse — zum Skalieren der Tageskurse ([PortfolioExchangeRoute.scaleCloses]). */
    val binance: Map<String, Double> = emptyMap(),
    /**
     * Coins OHNE Kurs, weil die Abfrage gerade scheiterte (Netz, Sperre/Backoff der Quellen) — nicht, weil es keinen Kurs
     * gibt ([PortfolioPriceIssue]). Zeile: «Kurs gerade nicht abrufbar» statt «kein Kurs». Mit gemerktem Kurs fehlt der
     * Coin hier (er zeigt den letzten Stand, veraltet).
     */
    val unavailable: Set<String> = emptySet(),
    /**
     * Gekapselte/gebrückte Coins ohne eigenen Börsenkurs, bewertet mit dem Kurs ihrer Basis ([ChainAssets.priceViaBase]):
     * Coin → Basis. Die Zeile zeigt dann «≈ BTC-Kurs (gebrückt/verpackt)» — nie als eigener Kurs ausgegeben.
     */
    val viaBase: Map<String, String> = emptyMap(),
    /**
     * Coins MIT Binance-Kurs, dessen Token nicht bestätigt ist (seit Build 101): kein Name bekannt und unter dem Kürzel
     * führen andere Listen klar verschiedene Token ([com.cryptochecker.app.domain.portfolio.PortfolioPriceIdentity.BinanceCheck.UNVERIFIED]).
     * Sie zählen mit dem Kurs (stehen in [prices]); die Zeile nennt sie «Kurs ungeprüft», Portfolio-Alarme behandeln sie
     * wie einen alten Kurs ([com.cryptochecker.app.domain.portfolio.PortfolioSnapshot.unverifiedCoins]).
     */
    val unverified: Set<String> = emptySet(),
)

/** Warum ein Coin keinen Kurs hat: [NO_PRICE] = keine Quelle führt ihn; [UNAVAILABLE] = Abfrage gerade gescheitert. */
enum class PortfolioPriceIssue {
    NO_PRICE, UNAVAILABLE;

    companion object {
        /**
         * Kerzen-Kette ohne Kurs für einen Coin: [UNAVAILABLE], wenn die Abfrage scheiterte ([failed]: Ausnahme), alle
         * Kerzen-Quellen gesperrt/pausiert sind ([allSourcesBlocked]) oder schon die Sammelabfrage am Netz scheiterte
         * ([networkFailed]) — dann ist «kein Kurs» nicht belegt. Sonst [NO_PRICE].
         */
        fun of(failed: Boolean, allSourcesBlocked: Boolean, networkFailed: Boolean): PortfolioPriceIssue =
            if (failed || allSourcesBlocked || networkFailed) UNAVAILABLE else NO_PRICE
    }
}
