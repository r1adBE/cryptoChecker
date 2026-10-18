// Steuer-Rechnung, Ereignisregeln und Steuerjahr (Teil von `TaxTemplates.kt`, Spiegel: `TaxTemplates.swift`).
package com.cryptochecker.app.domain.portfolio

import java.time.LocalDate

/**
 * Zuordnung Abgang ↔ Einstand im Steuer-Export (reines Kotlin, Spiegel: `TaxTemplates.swift`).
 *  - [FIFO], [FIFO_WALLET], [AVERAGE]: die Einstandsmethoden des Portfolios ([CostMethod], [PortfolioCalculator.disposals]).
 *  - [UK_POOL]: Section-104-Pool mit Same-Day- und 30-Tage-Regel ([TaxEngines]).
 *  - [CA_ACB]: Durchschnitt (ACB) mit Superficial-Loss-Regel.
 *  - [FR_PTA]: «prix total d'acquisition», globale Formel über das ganze Portfolio (Tausch Krypto↔Krypto aufgeschoben).
 *  - [JP_TOTAL_AVERAGE]: 総平均法, Durchschnitt je Kalenderjahr (移動平均法 = [AVERAGE]).
 *  - [AT_MOVING_AVERAGE]: Altbestand (vor 1.3.2021, FIFO) und Neubestand (gleitender Durchschnitt je Coin und Wallet, Tausch
 *    steuerneutral, Belohnung mit Anschaffungskosten 0, Zins aus Lending mit dem Wert beim Zufluss).
 * Name wird nicht gespeichert (folgt aus Vorlage + Einstandsmethode, [TaxTemplate.methodFor]).
 */
enum class TaxMethod {
    FIFO, FIFO_WALLET, AVERAGE, UK_POOL, CA_ACB, FR_PTA, JP_TOTAL_AVERAGE, AT_MOVING_AVERAGE;

    /** Einstandsmethode des Portfolios dahinter; null = eigene Rechnung in [TaxEngines]. */
    val costMethod: CostMethod?
        get() = when (this) {
            FIFO -> CostMethod.FIFO
            FIFO_WALLET -> CostMethod.FIFO_WALLET
            AVERAGE -> CostMethod.AVERAGE
            else -> null
        }

    companion object {
        fun of(method: CostMethod): TaxMethod = when (method) {
            CostMethod.FIFO -> FIFO
            CostMethod.FIFO_WALLET -> FIFO_WALLET
            CostMethod.AVERAGE -> AVERAGE
        }
    }
}

/**
 * Wie die Rechnung Vorgänge ohne Verkauf behandelt ([TaxEngines.applyRules]) — aus der Vorlage ([TaxTemplate.eventRules]);
 * ohne Vorlage nach der Rechnung ([of]). Die Einstandsmethoden FIFO/Ø teilen sich mehrere Vorlagen, darum hängen die
 * Regeln an der Vorlage, nicht an der Methode.
 */
data class TaxEventRules(
    /** Verschenkt = Veräusserung zum Marktwert ([TaxTemplate.giftIsDisposal]). */
    val giftIsDisposal: Boolean,
    /** Verschenkt an Ehe-/Lebenspartner = kein Gewinn, kein Verlust ([TaxTemplate.spouseGiftNeutral]). */
    val spouseGiftNeutral: Boolean,
    /** Netzwerkgebühr in Krypto = eigene Veräusserung zum Marktwert ([TaxTemplate.networkFeeIsDisposal]). */
    val networkFeeIsDisposal: Boolean,
    /** Gebühr USDT/Fiat einer Auszahlung oder Umbuchung = kein Verlust, kein Einstand, nur Information ([TaxTemplate.quoteFeeIsInfo]). */
    val quoteFeeIsInfo: Boolean,
) {
    companion object {
        /** Keine Umschreibung (Schenkung wie eine Auszahlung, Netzwerkgebühr senkt nur die Menge). */
        val NONE = TaxEventRules(
            giftIsDisposal = false, spouseGiftNeutral = false, networkFeeIsDisposal = false, quoteFeeIsInfo = false,
        )

        /** Standard ohne Vorlage: die eigenen Rechnungen UK, CA und JP (総平均法) wie ihre Vorlage, sonst [NONE]. */
        fun of(method: TaxMethod): TaxEventRules = when (method) {
            TaxMethod.UK_POOL -> TaxTemplate.UK.eventRules
            TaxMethod.CA_ACB -> TaxTemplate.CA.eventRules
            TaxMethod.JP_TOTAL_AVERAGE -> TaxTemplate.JP.eventRules
            TaxMethod.FIFO, TaxMethod.FIFO_WALLET, TaxMethod.AVERAGE, TaxMethod.FR_PTA, TaxMethod.AT_MOVING_AVERAGE -> NONE
        }
    }
}

/** Steuerjahr [start]…[end] (beide inklusive); [startYear] = Kalenderjahr des Beginns (Schlüssel der Jahresauswahl). */
data class TaxPeriod(val startYear: Int, val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}
