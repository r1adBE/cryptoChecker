package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.domain.format.A11ySpeech
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.marketdata.model.ReferenceSource
import com.cryptochecker.resources.R

/**
 * Screenreader-Satz einer Zeile der Merkliste ([WatchRow]): Paar, Kurs, Änderung, ≈, Verlauf, Notiz, Alarme, Zustand.
 * [converted]: Umrechnung (ganzer Vermerk für den Screenreader, sonst die kurze Zeile); [changeMarker]: Anbieter fremder
 * Kerzen hinter der Änderung ([DayChange.rowChangeMarker]).
 */
@Composable
internal fun watchRowDescription(
    watch: WatchEntity,
    name: String?,
    now: Long,
    staleAfter: Long,
    off: Boolean,
    status: WatchRowStatus,
    changeView: ChangeView,
    changeMarker: String?,
    markPrice: Boolean,
    hourlyPrice: Boolean,
    reference: ReferenceSource?,
    converted: String?,
    sparkline: List<Double>?,
    chartSource: String?,
    kind: AssetKind,
    alarmFiredAt: Long,
    alarmCount: Int,
): String {
    val context = LocalContext.current
    val chartPeriod = stringResource(R.string.widget_range_24h)
    // Frische ausgeschrieben statt der Zeitzeile mit «·» und «Min.»: «Kurs veraltet, zuletzt
    // aktualisiert vor 45 Minuten» bzw. «Deaktiviert» / «Wird … nicht mehr gehandelt»
    val notTraded = isNotTraded(watch.lastError)
    val freshness = A11ySpeech.freshness(
        disabled = off,
        notTraded = notTraded,
        failed = watch.lastError != null && !notTraded,
        lastUpdate = watch.lastUpdate,
        now = now,
        outdatedAfter = staleAfter,
    )
    val priceNotificationOn = A11ySpeech.priceNotificationOn(
        pairOn = watch.notificationEnabled,
        masterOn = com.cryptochecker.app.ui.components.LocalPriceNotificationsOn.current,
    )
    return A11yText.row(
        context = context,
        pair = if (name == null) watch.displayName else "${watch.displayName}, $name",
        exchange = watch.exchangeName,
        price = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
        quote = watch.quoteAsset,
        change24h = watch.shownChange(changeView),
        basis = changeView.basis,
        state = listOf(
            status.paused,
            A11yText.rowFreshness(context, freshness, watch.lastUpdate, now, watch.exchangeName),
            status.trade,
            status.spokenAge,
            status.unchanged
        ),
        extras = listOf(
            changeMarker?.let { stringResource(R.string.change_candle_source, it) },
            if (markPrice) stringResource(R.string.watch_price_mark_fallback) else null,
            if (hourlyPrice) stringResource(R.string.watch_price_hourly) else null,
            reference?.let { com.cryptochecker.app.ui.components.referencePeriodText(it) },
            converted?.let { stringResource(R.string.a11y_converted, it.removePrefix("≈ ")) },
            sparkline?.takeIf { it.size >= 2 }?.let { A11yText.chart(context, chartPeriod, it) },
            chartSource?.takeIf { sparkline != null && sparkline.size >= 2 }?.let { stringResource(R.string.watch_row_source_chart, it) },
            watch.note?.let { stringResource(R.string.a11y_note, it) },
            if (watch.favorite) stringResource(R.string.a11y_favorite) else null,
        ),
        // Art vor der Glocke nur bei «TradFi» bzw. «Alpha-Token» — «Token» ist der Normalfall und
        // stünde sonst in jeder Zeile
        kind = if (kind == AssetKind.TOKEN) null else stringResource(kind.spokenRes),
        alarms = listOf(
            // Kursmitteilung nur, wenn sie aus ist — «an» ist der Normalfall und stünde sonst in jeder Zeile (wie iOS)
            if (!priceNotificationOn) stringResource(R.string.a11y_price_notification_off) else null,
            // Alarm eben ausgelöst (Mitteilung kam bei offener App): bis 15 Min. danach
            if (A11ySpeech.firedRecently(alarmFiredAt, now)) stringResource(R.string.a11y_alarm_fired) else null,
            // «Alarme (2)» wie die Glocke der Zeile und das Aktionsblatt (Mehrzahl je Sprache)
            if (alarmCount > 0) pluralStringResource(R.plurals.watchlist_alarms_count, alarmCount, alarmCount) else null,
        ),
        rest = listOf(
            // Verständlicher Fehler (z. B. Paar unbekannt); «nicht mehr gehandelt» steht schon oben
            status.error.takeIf { freshness != A11ySpeech.Freshness.NOT_TRADED },
        ),
    )
}
