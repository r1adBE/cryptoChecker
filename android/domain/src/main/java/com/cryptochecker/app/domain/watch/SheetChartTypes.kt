// Zeitraum, Umrechnung, Ergebnis und Kerzen-Anfrage des Charts im Paar-Blatt (SheetChart).
package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.UsdtUsd
import com.cryptochecker.app.domain.portfolio.UsdtUsdRates
import com.cryptochecker.app.domain.widget.ChartLevels
import com.cryptochecker.app.domain.widget.WidgetCandle
import com.cryptochecker.app.domain.widget.WidgetChartGeometry
import com.cryptochecker.app.domain.widget.WidgetChartType
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Zeitraum des Charts im Aktionsblatt eines Paars. Gleiche Namen wie `WidgetChartRange`
 * des Einzel-Widgets — von dort kommen Kerzenintervall, Anzahl (24 × 1 h, 42 × 4 h,
 * 30 × 1 Tag, 365 × 1 Tag nur im Blatt), Gitter und Beschriftungen. Hier nur, was das Blatt zusätzlich braucht:
 * Gültigkeit im Zwischenspeicher und die Vorlage für Datum/Uhrzeit beim Ziehen
 * (`DateFormat.getBestDateTimePattern` bzw. `setLocalizedDateFormatFromTemplate`).
 */
enum class SheetChartRange(val cacheMillis: Long, val timeTemplate: String) {
    /** «Di 14:00» */
    DAY(5 * 60_000L, "EEEjm"),

    /** «Di 14. Okt. 16:00» (4-h-Kerzen) */
    WEEK(30 * 60_000L, "EEEdMMMjm"),

    /** «Di 14. Okt.» (Tageskerzen) */
    MONTH(30 * 60_000L, "EEEdMMM"),

    /** «14. Okt. 2025» (365 Tageskerzen) */
    YEAR(60 * 60_000L, "dMMMy");

    /** Skelett für ICU/`DateFormatter`: «j» je nach 12/24-h-Einstellung als «H» bzw. «h». */
    fun timeSkeleton(is24: Boolean): String = timeTemplate.replace("j", if (is24) "H" else "h")
}

/** Umrechnung der Ausweich-Reihe (USDT → Fiat-Quote des Paars). */
enum class SheetChartFx {
    /** Keine Umrechnung: Kerzen des Paars selbst. */
    NONE,

    /** Je Kerze mit dem Devisenkurs ihres Tags («umgerechnet mit Tageskursen»). */
    DAILY,

    /** Ohne Tageskurse: ganze Reihe mit einem Faktor (aktueller Kurs) — «umgerechnet (aktueller Kurs)». */
    CURRENT,
}

/** Ergebnis für das Blatt: gar nicht zeigen, keine Kerzen oder fertige Kerzen. */
sealed interface SheetChartResult {
    /** DEX-Paar oder Kürzel, die die Kerzenquelle nie liefern kann: Chart-Block ausblenden. */
    data object Unsupported : SheetChartResult

    /** Keine Quelle lieferte passende Kerzen: «Keine Kursdaten für dieses Paar». */
    data object NoData : SheetChartResult

    /**
     * Kerzen in der Quote des Paars; [converted] = aus der USDT-Reihe umgerechnet.
     * Für die Zeile unter dem Chart: [provider] = Börse der Kerzen («Binance», «Coinbase» …),
     * [sourcePair] = abgefragte Reihe («BTC/USDT»), [fx] = Art der Umrechnung,
     * [fetchedAt] = Abfragezeit (Epoch-ms; 0 = unbekannt), [usdtAssumed] = mit USDT = 1 umgerechnet ([usdtParityNote]),
     * [usdtHistoryMissing] = Tagesschlüsse USDT/USD fehlten, ganze Reihe mit dem Live-Kurs umgerechnet ([usdtHistoryNote]).
     */
    data class Ready(
        val candles: List<WidgetCandle>,
        val converted: Boolean,
        val provider: String? = null,
        val sourcePair: String? = null,
        val fx: SheetChartFx = SheetChartFx.NONE,
        val fetchedAt: Long = 0L,
        val usdtAssumed: Boolean = false,
        val usdtHistoryMissing: Boolean = false,
    ) : SheetChartResult
}

/** Eine Kerzenabfrage: Paar oder Ausweich-Reihe gegen USDT ([convert] = umrechnen). */
data class SheetCandleRequest(val base: String, val quote: String, val convert: Boolean)
