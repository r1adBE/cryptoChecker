package com.cryptochecker.app.domain.refresh

/**
 * «Letzte Aktualisierung»: was der letzte volle Durchlauf geschafft hat und wo die
 * Zeit hingegangen ist. Baut [PriceRefresher], gespeichert in RefreshStats (JSON),
 * angezeigt im Blatt der Merkliste. Reines Kotlin — wie `RefreshReport.swift`.
 */
data class RefreshReport(
    /** Zeitpunkt des Durchlaufs (Ende), für «vor 8 Min.». */
    val at: Long,
    val totalMillis: Long,
    val pairs: Int,
    val networkMillis: Long,
    val markets: List<MarketRefresh>,
    /** null: auf dieser Plattform nicht gemessen (Zeile entfällt). */
    val dbMillis: Long? = null,
    val effectsMillis: Long? = null,
    val alarms: Int = 0,
    val notifications: Int = 0,
    val widgetMillis: Long? = null,
    /** Wartezeit zwischen Knopfdruck und Start (WorkManager); null = keine. */
    val waitMillis: Long? = null,
    /** Durchlauf abgebrochen: kurze technische Ursache. */
    val aborted: String? = null,
)

/** Ergebnis einer Börse; [updated] + [notTraded] + [failed] = [pairs]. */
data class MarketRefresh(
    val name: String,
    val millis: Long,
    val pairs: Int,
    val updated: Int,
    val notTraded: Int = 0,
    val failed: Int = 0,
    val bulkTried: Boolean = false,
    val bulkMillis: Long = 0,
    /** Kurse der Sammelabfrage; 0 bei versuchter Abfrage = fehlgeschlagen. */
    val bulkPrices: Int = 0,
    val singles: Int = 0,
    /** Häufigste Ursache der Fehler; null ohne Fehler. */
    val reason: RefreshFailure? = null,
    /**
     * Börse pausiert ([ExchangeBackoff]) bis dahin: übersprungen oder gerade in die Pause
     * geschickt; null = keine Pause. Ihre Paare behalten den letzten Kurs.
     */
    val pausedUntil: Long? = null,
    /** Grund der Pause: [RefreshFailure.RATE_LIMIT] oder [RefreshFailure.TIMEOUT]. */
    val pauseReason: RefreshFailure? = null,
)

/** Grün / Orange / Rot. */
enum class RefreshStatus { OK, PARTIAL, FAILED }

/** Kurze Fehlerursache für «2 Fehler (Zeitüberschreitung)». */
enum class RefreshFailure { TIMEOUT, OFFLINE, RATE_LIMIT, SERVER, NO_DATA, UNAVAILABLE, OTHER }

/** Grosse Statuszeile oben im Blatt. */
sealed interface RefreshHeadline {
    data object AllUpdated : RefreshHeadline
    data class PairsNotUpdated(val count: Int) : RefreshHeadline
    data class MarketUnreachable(val name: String) : RefreshHeadline
    data class MarketsUnreachable(val count: Int) : RefreshHeadline
    data object Aborted : RefreshHeadline
}

object RefreshReportLogic {

    /**
     * Alle Paare aktualisiert → grün; kein einziges und mindestens ein Fehler → rot;
     * sonst orange. Nicht mehr gehandelte Paare sind ein Zustand, kein Fehler:
     * höchstens orange, auch wenn die Börse nur solche hat.
     */
    fun status(market: MarketRefresh): RefreshStatus = when {
        market.updated >= market.pairs -> RefreshStatus.OK
        market.updated == 0 && market.failed > 0 -> RefreshStatus.FAILED
        else -> RefreshStatus.PARTIAL
    }

    /** Schlechtester Zustand aller Börsen; abgebrochen = rot. */
    fun overall(report: RefreshReport): RefreshStatus = when {
        report.aborted != null -> RefreshStatus.FAILED
        else -> report.markets.map(::status).maxByOrNull { it.ordinal } ?: RefreshStatus.OK
    }

    /** Rot zuerst, dann orange, dann grün; innerhalb davon die langsamsten zuerst. */
    fun sorted(markets: List<MarketRefresh>): List<MarketRefresh> =
        markets.sortedWith(
            compareByDescending<MarketRefresh> { status(it).ordinal }
                .thenByDescending { it.millis }
                .thenBy { it.name }
        )

    fun headline(report: RefreshReport): RefreshHeadline {
        if (report.aborted != null) return RefreshHeadline.Aborted
        val failed = report.markets.filter { status(it) == RefreshStatus.FAILED }
        if (failed.size == 1) return RefreshHeadline.MarketUnreachable(failed.first().name)
        if (failed.size > 1) return RefreshHeadline.MarketsUnreachable(failed.size)
        val missing = report.markets.sumOf { (it.pairs - it.updated).coerceAtLeast(0) }
        return if (missing > 0) RefreshHeadline.PairsNotUpdated(missing) else RefreshHeadline.AllUpdated
    }

    /** Häufigste Ursache der gespeicherten Fehlertexte; bei Gleichstand die frühere Art. */
    fun reason(errors: List<String?>): RefreshFailure? =
        errors.map(::classify)
            .groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<RefreshFailure, Int>> { it.value }.thenBy { it.key.ordinal })
            .firstOrNull()?.key

    /** Fehlertext (Android: Ausnahme bzw. Kennung; iOS: `ConnectionErrors`) → Ursache. */
    fun classify(error: String?): RefreshFailure {
        val e = error?.trim().orEmpty()
        val lower = e.lowercase()
        HTTP_CODE.find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let { code ->
            return if (code == 429 || code == 418) RefreshFailure.RATE_LIMIT else RefreshFailure.SERVER
        }
        return when {
            TIMEOUT_HINTS.any { it in lower } -> RefreshFailure.TIMEOUT
            e == OFFLINE_MARKER || OFFLINE_HINTS.any { it in lower } -> RefreshFailure.OFFLINE
            e in NO_DATA_ERRORS -> RefreshFailure.NO_DATA
            e in UNAVAILABLE_ERRORS -> RefreshFailure.UNAVAILABLE
            else -> RefreshFailure.OTHER
        }
    }

    /** Android `HttpMarketError` («HttpCode: 429»), iOS `MarketHTTP` («HTTP 429»). */
    private val HTTP_CODE = Regex("""\bhttp(?:code:)?\s*(\d{3})\b""")

    private val TIMEOUT_HINTS = listOf("timeout", "timed out")

    private val OFFLINE_HINTS = listOf(
        "unknownhost", "unable to resolve host", "connectexception", "failed to connect",
        "noroutetohost", "network is unreachable", "connection reset", "connection refused", "ssl", "eof",
    )

    /** iOS `ConnectionErrors.offlineMarker` (kein Netz, auch Zeitüberschreitung). */
    const val OFFLINE_MARKER = "NETWORK_OFFLINE"

    /** = UserFriendlyMarketError.EMPTY_RESPONSE / NO_TICKER_DATA (hier ohne Android-Abhängigkeit). */
    private val NO_DATA_ERRORS = setOf("Response data is empty", "Parsed ticker has no data")

    /** = UserFriendlyMarketError.MARKET_UNAVAILABLE und der früher deutsch gespeicherte Text. */
    private val UNAVAILABLE_ERRORS = setOf("Market unavailable", "Börse nicht verfügbar")
}
