// Zustandstexte einer Merklisten-Zeile (Warnung, Fehler, veraltet, pausiert), auch für den Screenreader.
// Eigene Datei, weil `RowInfo` nur anordnet; die Texte liest auch das Vorlesen der Zeile.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.PriceFreshness
import com.cryptochecker.app.domain.watch.RowPause
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.marketdata.config.ExchangeRetirement
import com.cryptochecker.app.domain.util.BidiText
import com.cryptochecker.app.util.PriceFormat

/**
 * Zustandstexte einer Zeile, auch für den Screenreader: [warning] ersetzt die Zeitzeile
 * («Binance · pausiert bis 19:45», «Vor 41 Min. · Binance nicht erreichbar», «Binance · veraltet · vor 4 Min.» bzw.
 * «Binance · letzter Handel vor 3 Std.»),
 * [error] ist der verständliche Fehler, [stale] der Hinweis «veraltet» ohne Warnung.
 */
internal class WatchRowStatus(
    val warning: String?,
    val error: String?,
    val stale: String?,
    /** Technischer Grund nur, wenn er mehr sagt als «nicht erreichbar» (z. B. Paar unbekannt). */
    val showErrorLine: Boolean,
    /** «Kurs unverändert seit 14:05» (Börse ohne eigene Zeit, [PriceFreshness.unchangedSince]); sonst null. */
    val unchanged: String? = null,
    /** «Binance · pausiert bis 19:45» (Börse pausiert, [RowPause]); sonst null. Steht dann auch in [warning]. */
    val paused: String? = null,
    /**
     * «letzter Handel vor 3 Std.» (vorgelesen; sichtbar mit Börse davor): Abruf frisch, aber die Börse meldet den letzten Handel vor mehr als 2 h
     * ([PriceFreshness.StaleReason.TRADE], dieselbe Grenze wie die Alarmsperre); sonst null. Steht dann auch in [warning].
     */
    val trade: String? = null,
    /**
     * Sichtbares Alter der gesunden Zeitzeile ohne Warnung und ohne «unverändert seit»: nur «letzter Handel vor 40 Min.»,
     * wenn die Börse einen Handel deutlich vor dem Abruf meldet ([PriceFreshness.rowTradeAgeTime]); sonst null — die
     * Zeile zeigt nur die Börse, das Alter des Abrufs steht im Kopf.
     */
    val age: String? = null,
    /**
     * Im Zeilensatz der letzte Handel laut Börse bei jeder gesunden Zeile mit Zeit der Börse
     * ([PriceFreshness.rowAgeTime]); das Alter des Abrufs sagt schon die Frische. Der Screenreader liest den Kopf
     * nicht mit jeder Zeile.
     */
    val spokenAge: String? = null,
)

@Composable
internal fun watchRowStatus(
    watch: WatchEntity,
    now: Long,
    stale: Boolean,
    outdatedAfter: Long,
    /** Ende einer laufenden Pause der Börse ([RowPause.shownUntil]); null = keine. */
    pausedUntil: Long? = null,
): WatchRowStatus {
    // Deaktiviert: weder Warnung noch Fehler noch «veraltet» — es wird bewusst nicht abgefragt; nur eine stillgelegte
    // Börse nennt auch hier ihren Grund («Bitpanda liefert keine Kurse mehr», Marker von RetiredWatch.shown)
    if (watch.disabled) {
        val retired = watch.lastError?.takeIf { ExchangeRetirement.isMarker(it) }
        return WatchRowStatus(
            warning = null, error = retired?.let { friendlyError(it) }, stale = null, showErrorLine = retired != null,
        )
    }
    // Letzter Abruf gescheitert, aber es gibt einen älteren Kurs: «Vor 41 Min. · Binance nicht erreichbar»
    val failed = watch.lastError != null && !isNotTraded(watch.lastError)
    val unreachableText = if (failed && watch.lastUpdate > 0) {
        // Zeit steht hier am Zeilenanfang: gross beginnend
        val age = ago(watch.lastUpdate, now)
        lineStart(stringResource(R.string.watchlist_row_unreachable, age, watch.exchangeName), age)
    } else {
        null
    }
    // Kein Fehler, aber älter als die Grenze ([OutdatedRule], live 2 Min.): «Binance · veraltet · vor 4 Min.»
    val outdatedText = if (!failed && !isNotTraded(watch.lastError) && watch.lastUpdate > 0 &&
        now - watch.lastUpdate > outdatedAfter
    ) {
        "${BidiText.isolate(watch.exchangeName)} · " +
            stringResource(R.string.watchlist_row_outdated_age, ago(watch.lastUpdate, now))
    } else {
        null
    }
    // Abruf frisch, letzter Handel laut Börse aber älter als die Alarmsperre: dieselbe Bedingung, dasselbe Wort
    val tradeAge = if (!failed && !isNotTraded(watch.lastError) && outdatedAfter > 0 &&
        PriceFreshness.staleReason(watch, now, outdatedAfter) == PriceFreshness.StaleReason.TRADE
    ) {
        stringResource(R.string.watchlist_row_last_trade_age, ago(watch.exchangeTime ?: 0L, now))
    } else {
        null
    }
    val tradeText = tradeAge?.let { "${BidiText.isolate(watch.exchangeName)} · $it" }
    // Börse pausiert (zu viele Anfragen, Zeitüberschreitungen): das sagt mehr als «nicht erreichbar» bzw. «veraltet»
    // und nennt, wann es weitergeht — gleiche Stelle und Farbe wie die übrigen Warnungen
    val pausedText = pausedUntil?.let {
        stringResource(R.string.watchlist_row_exchange_paused, BidiText.isolate(watch.exchangeName), PriceFormat.shortTime(it))
    }
    val warningText = pausedText ?: unreachableText ?: outdatedText ?: tradeText
    val showErrorLine = watch.lastError != null &&
        (unreachableText == null || !isRetryableExchangeError(watch.lastError))
    val errorText = watch.lastError?.takeIf { showErrorLine }?.let { friendlyError(it) }
    val staleText = if (warningText == null && stale && watch.lastUpdate > 0) {
        stringResource(R.string.a11y_stale, ago(watch.lastUpdate, now))
    } else {
        null
    }
    // Ohne Warnung: Börse ohne eigene Zeit und Kurs lange gleich → «Kurs unverändert seit 14:05»
    val unchangedText = if (warningText == null) {
        PriceFreshness.unchangedSince(watch, now, outdatedAfter)?.let {
            stringResource(R.string.watchlist_row_price_unchanged_since, PriceFormat.shortTime(it))
        }
    } else {
        null
    }
    // Gesund: sichtbar nur die Börse — der Kopf nennt das Alter. «letzter Handel vor …» nur, wenn die Börse einen
    // Handel deutlich vor dem Abruf meldet; vorgelesen wird der letzte Handel bei jeder Zeile mit Zeit der Börse
    val healthy = warningText == null && unchangedText == null
    val ageText = PriceFreshness.rowTradeAgeTime(watch, outdatedAfter)?.takeIf { healthy }?.let {
        stringResource(R.string.watchlist_row_last_trade_age, ago(it, now))
    }
    val spokenText = PriceFreshness.rowAgeTime(watch)?.takeIf { healthy && PriceFreshness.hasExchangeTime(watch) }?.let {
        stringResource(R.string.watchlist_row_last_trade_age, ago(it, now))
    }
    return WatchRowStatus(
        warningText, errorText, staleText, showErrorLine, unchangedText, pausedText,
        trade = tradeAge?.takeIf { warningText == tradeText },
        age = ageText,
        spokenAge = spokenText,
    )
}
