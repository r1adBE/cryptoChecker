package com.cryptochecker.marketdata.model

import java.time.DayOfWeek.FRIDAY
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Art eines Kontrakts; NONE = Spot. [value] ist die gespeicherte Zahl — Werte nie neu vergeben.
 */
enum class FuturesContractType(val value: Int) {
    NONE(0),
    PERPETUAL(1),
    WEEKLY(2),
    BIWEEKLY(3),
    MONTHLY(4),
    BIMONTHLY(5),
    QUARTERLY(6),
    BIQUARTERLY(7),

    /** In der Basiswährung abgerechnetes Perpetual (Binance COIN-M, OKX «-USD-SWAP», Deribit). */
    INVERSE_PERPETUAL(10);

    /** Linear oder invers: gleiche Abfragen für Funding, Kerzen usw. */
    val isPerpetual: Boolean
        get() = this == PERPETUAL || this == INVERSE_PERPETUAL

    override fun toString(): String = getShortName(this) ?: "None"

    companion object {
        fun fromInt(value: Int): FuturesContractType = entries.find { it.value == value } ?: NONE

        /**
         * Kurzform für Anzeige und Paarnamen; null für Spot.
         */
        @Suppress("SpellCheckingInspection")
        fun getShortName(contractType: FuturesContractType): String? = when (contractType) {
            NONE -> null
            PERPETUAL -> "Perp"
            INVERSE_PERPETUAL -> "InvPerp"
            WEEKLY -> "1W"
            BIWEEKLY -> "2W"
            MONTHLY -> "1M"
            BIMONTHLY -> "2M"
            QUARTERLY -> "1Q"
            BIQUARTERLY -> "2Q"
        }

        /**
         * Erkennt ein inverses Perpetual an der Kennung der Börse: Binance COIN-M «2:BTCUSD_PERP» (ohne Datum),
         * OKX «BTC-USD-SWAP», Deribit «BTC-PERPETUAL» (ohne «_»; linear wäre «SOL_USDC-PERPETUAL»). Nötig für Paare,
         * die vor Build 47 noch als PERPETUAL gespeichert wurden ([normalized]); neue Paarlisten setzen die Art selbst.
         */
        fun isInversePerpetualId(marketKey: String, pairId: String?): Boolean {
            val id = pairId?.trim()
            if (id.isNullOrEmpty()) return false
            return when (marketKey) {
                "BinanceFutures" -> id.startsWith("2:") && deliveryCodeOf(id) == null
                "OkexFutures" -> id.endsWith("-USD-SWAP")
                "Deribit" -> id.endsWith("-PERPETUAL") && !id.contains('_')
                else -> false
            }
        }

        /**
         * Gespeicherte Art an die heutige Paarliste angleichen: ein altes PERPETUAL mit inverser Kennung wird
         * INVERSE_PERPETUAL, alles andere bleibt. iOS: `FuturesContractType.normalized`.
         */
        fun normalized(marketKey: String, pairId: String?, type: FuturesContractType): FuturesContractType =
            when {
                type != PERPETUAL -> type
                isInversePerpetualId(marketKey, pairId) -> INVERSE_PERPETUAL
                else -> type
            }

        /**
         * Verfall als «yyMMdd», wenn die Kennung mit «_» und sechs Ziffern endet («BTCUSDT_251226», COIN-M
         * «2:BTCUSD_251226»); sonst null (Perpetual, ältere Kennungen). Abfragen richten sich nur danach, nie nach
         * dem Kalender.
         */
        fun deliveryCodeOf(pairId: String?): String? {
            val id = pairId?.trim() ?: return null
            val cut = id.lastIndexOf('_')
            if (cut < 0) return null
            val code = id.substring(cut + 1)
            return code.takeIf { c -> c.length == 6 && c.all { it in '0'..'9' } }
        }

        /**
         * Ungefährer Verfallstag nach Kalender (Freitage, UTC) — nur für die Anzeige und als Ersatz, wenn die
         * Kennung kein Datum trägt. null für Spot und Perpetuals.
         */
        fun getDeliveryDate(contractType: FuturesContractType): LocalDate? {
            val today = LocalDate.now(ZoneOffset.UTC)
            return when (contractType) {
                NONE, PERPETUAL, INVERSE_PERPETUAL -> null
                WEEKLY -> fridayOnOrAfter(today)
                BIWEEKLY -> fridayOnOrAfter(today.plusWeeks(1))
                MONTHLY -> lastFridayOfMonth(today)
                BIMONTHLY -> lastFridayOfMonth(today.plusMonths(1))
                QUARTERLY -> lastFridayOfQuarter(today)
                BIQUARTERLY -> lastFridayOfQuarter(today.plusMonths(3))
            }
        }

        private fun fridayOnOrAfter(day: LocalDate): LocalDate = day.with(TemporalAdjusters.nextOrSame(FRIDAY))

        private fun lastFridayOfMonth(day: LocalDate): LocalDate = day.with(TemporalAdjusters.lastInMonth(FRIDAY))

        /** Letzter Freitag im dritten Monat des Quartals, in dem [day] liegt. */
        private fun lastFridayOfQuarter(day: LocalDate): LocalDate {
            val thirdMonth = day.month.firstMonthOfQuarter().plus(2)
            return lastFridayOfMonth(LocalDate.of(day.year, thirdMonth, 1))
        }
    }
}
