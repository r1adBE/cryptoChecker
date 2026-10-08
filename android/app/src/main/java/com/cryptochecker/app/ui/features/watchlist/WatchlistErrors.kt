@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.exceptions.UserFriendlyMarketError
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import com.cryptochecker.app.domain.watch.NotTraded
import com.cryptochecker.app.domain.watch.isNotTraded

/** Netzwerk-Fehler (kein Internet, Server nicht erreichbar, Zeitüberschreitung)? */
internal fun isConnectionError(error: String?): Boolean {
    if (error == null) return false
    val e = error.lowercase()
    return CONNECTION_HINTS.any { it in e }
}

private val CONNECTION_HINTS = listOf(
    "unknownhost", "unable to resolve host", "connectexception", "failed to connect",
    "sockettimeout", "timeout", "timed out", "noroutetohost", "network is unreachable",
    "connection reset", "connection refused", "ssl", "eof",
)

/** Paar wird an der Börse nicht mehr gehandelt — ein Zustand, kein Fehler. */
internal fun isNotTraded(error: String?): Boolean = NotTraded.isMarker(error)

/**
 * Börse gerade nicht erreichbar (Netz, Zeitüberschreitung, HTTP-Fehler, zu viele Anfragen):
 * ein neuer Versuch kann helfen. Nicht bei «nicht mehr gehandelt» oder unbekanntem Paar.
 */
internal fun isRetryableMarketError(error: String?): Boolean {
    if (error == null || isNotTraded(error)) return false
    return isConnectionError(error) || RefreshReportLogic.classify(error) in RETRYABLE_FAILURES
}

private val RETRYABLE_FAILURES = setOf(
    RefreshFailure.TIMEOUT, RefreshFailure.OFFLINE, RefreshFailure.RATE_LIMIT, RefreshFailure.SERVER,
)

/** Technische Fehlermeldung → verständlicher Text (z. B. «Keine Verbindung»). */
@Composable
internal fun friendlyError(error: String): String = when {
    isNotTraded(error) -> stringResource(R.string.watch_not_traded)
    isConnectionError(error) -> stringResource(R.string.watch_error_offline)
    // HTTP-Fehler, zu viele Anfragen: kein Code, nur was es bedeutet
    isRetryableMarketError(error) -> stringResource(R.string.error_market_unreachable_short)
    error == UserFriendlyMarketError.EMPTY_RESPONSE || error == UserFriendlyMarketError.NO_TICKER_DATA ->
        stringResource(R.string.market_data_empty_error)
    error == UserFriendlyMarketError.UNKNOWN_EMPTY -> stringResource(R.string.something_went_wrong)
    // Früher auf Deutsch gespeichert; ältere Einträge zeigen so ebenfalls den übersetzten Text
    error == UserFriendlyMarketError.MARKET_UNAVAILABLE || error == LEGACY_MARKET_UNAVAILABLE ->
        stringResource(R.string.market_unavailable_error)
    // Technische Meldungen (HTTP-Code, Ausnahmetext) nicht roh anzeigen; Details stehen im HTTP-Log
    else -> stringResource(R.string.something_went_wrong)
}

private const val LEGACY_MARKET_UNAVAILABLE = "Börse nicht verfügbar"
