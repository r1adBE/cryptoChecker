// Kopf der Paarseite: Paar, Börse, Kurs gross, Zeile «↗ +41.71% · 24h» und das Alter des Kurses wie in der Zeile
// («Aktualisiert: vor 2 Min.»), für den Screenreader als ein Satz; darunter Herkunft, Sats und der Zustand (Fehler,
// deaktiviert, Vorschau lädt). Eigene Datei wie `PairHeader.swift`; die Seite `PairScreen` setzt sie zusammen.
package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.convert.Sats
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.marketdata.config.ExchangeRetirement
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.ui.components.ChangePillSize
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.components.ReferenceSourceLine
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.display
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.util.A11ySpeech
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.domain.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.marketdata.model.ReferenceSource

/**
 * Deaktiviertes Paar: ruhiger Hinweis unter dem Kurs («Deaktiviert · Keine Abfragen, keine Meldungen», Stand des
 * letzten Kurses) und gleich daneben «Aktivieren».
 */
@Composable
internal fun DisabledNotice(updatedAt: Long, onEnable: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Icon(
            painterResource(R.drawable.ic_pause_circle),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp).size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.watch_disabled) + " · " + stringResource(R.string.watch_disable_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (updatedAt > 0) {
                Text(
                    text = stringResource(R.string.watchlist_updated, PriceFormat.time(updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = onEnable) { Text(stringResource(R.string.watch_enable)) }
    }
}

/**
 * Der ganze Kopf als ein Satz für den Screenreader — wie der Zeilensatz der Merkliste ([A11yText.row]): Paar und
 * Börse, Kurs (Fiat als Wort), Änderung mit Zeitraum, Frische («zuletzt aktualisiert vor 2 Minuten» bzw. «Kurs
 * veraltet …»), Notiz und ein verständlicher Fehler.
 */
@Composable
internal fun pairHeaderSpoken(watch: WatchEntity, off: Boolean, now: Long, outdatedAfter: Long): String {
    val context = LocalContext.current
    val changeView = LocalChangeView.current
    val notTraded = isNotTraded(watch.lastError)
    val freshness = A11ySpeech.freshness(
        disabled = off,
        notTraded = notTraded,
        failed = watch.lastError != null && !notTraded,
        lastUpdate = watch.lastUpdate,
        now = now,
        outdatedAfter = outdatedAfter,
    )
    return A11yText.row(
        context = context,
        pair = watch.displayName,
        exchange = watch.exchangeName,
        price = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
        quote = watch.quoteAsset,
        change24h = watch.shownChange(changeView),
        basis = changeView.basis,
        state = listOf(A11yText.rowFreshness(context, freshness, watch.lastUpdate, now, watch.exchangeName)),
        extras = listOf(watch.note?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.a11y_note, it) }),
        rest = listOf(
            watch.lastError?.takeIf { freshness == A11ySpeech.Freshness.FRESH }?.let { friendlyError(it) },
        ),
    )
}

/**
 * Kopf der Paarseite: Logo, Paar (bricht bei grosser Schrift um statt «PERP» abzuschneiden) und Börse, Kurs gross
 * (wird schrittweise kleiner und bricht notfalls um, nie «…»), darunter «↗ +41.71% · 24h» und das Alter des Kurses
 * wie in der Zeile. Für den Screenreader eine Überschrift mit einem Satz ([pairHeaderSpoken]); die DEX-Adresse
 * (Tippen kopiert), Herkunft und Sats stehen ausserhalb und bleiben einzeln erreichbar.
 */
@Composable
internal fun PairHeader(
    watch: WatchEntity,
    preview: Boolean,
    off: Boolean,
    now: Long,
    outdatedAfter: Long,
    candleSource: String?,
    rowSource: com.cryptochecker.app.domain.watch.DayChange.RowSource?,
    satsRate: suspend (String) -> Pair<String, Double>?,
) {
    val spoken = pairHeaderSpoken(watch, off, now, outdatedAfter)
    val headerChangeView = LocalChangeView.current
    val candleNote = candleSource?.takeIf { watch.lastPrice != null }
        ?.let { stringResource(R.string.change_candle_source, it) }
    Column(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken; heading() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoinBadge(
                watch.baseAsset,
                size = 32.dp,
                logo = CoinLogos.allowedFor(watch.exchangeKey),
                pair = CoinLogos.pairKey(watch.exchangeKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name),
                modifier = Modifier.padding(end = 12.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(watch.displayName, style = MaterialTheme.typography.headline, fontWeight = FontWeight.SemiBold)
                Text(
                    // Vorschau: «Binance · Nicht in der Merkliste»
                    if (preview) "${watch.exchangeName} · ${stringResource(R.string.watch_preview_not_watched)}"
                    else watch.exchangeName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Langer Kurs wird kleiner (bis halbe Grösse) und bricht notfalls um — nie abgeschnitten
        val priceStyle = MaterialTheme.typography.display.amountNumbers()
        BasicText(
            PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
            style = priceStyle.copy(fontWeight = FontWeight.SemiBold, color = LocalContentColor.current),
            maxLines = 2,
            autoSize = TextAutoSize.StepBased(
                minFontSize = priceStyle.fontSize * 0.5f,
                maxFontSize = priceStyle.fontSize,
                stepSize = 1.sp,
            ),
            modifier = Modifier.padding(top = 12.dp)
        )
        if (watch.lastPrice != null) {
            // Börse meldet den Mittelkurs (z. B. Hyperliquid), fehlte er den Mark-Preis; WOO X Spot: Stundenkurs
            val exchange = com.cryptochecker.marketdata.util.ExchangesConfigUtils.getExchangeByKey(watch.exchangeKey)
            val kind = com.cryptochecker.app.domain.watch.PriceKindLabel.of(
                priceIsMid = exchange.priceIsMid,
                markFallback = watch.id in LocalMarkFallback.current,
                hourlyClose = exchange.priceIsHourlyClose || watch.id in LocalHourlyClose.current,
                dailyReference = exchange.priceIsDailyReference,
                weeklyReference = ReferenceSource.of(watch.exchangeKey)?.weekly == true,
            )
            val midText = when (kind) {
                com.cryptochecker.app.domain.watch.PriceKindLabel.MARK -> " · " + stringResource(R.string.watch_price_mark_fallback)
                com.cryptochecker.app.domain.watch.PriceKindLabel.MID -> " · " + stringResource(R.string.watch_price_mid)
                com.cryptochecker.app.domain.watch.PriceKindLabel.HOURLY -> " · " + stringResource(R.string.watch_price_hourly)
                com.cryptochecker.app.domain.watch.PriceKindLabel.DAILY_REFERENCE -> " · " + stringResource(R.string.watch_price_reference)
                com.cryptochecker.app.domain.watch.PriceKindLabel.WEEKLY_REFERENCE -> " · " + stringResource(R.string.watch_price_reference_weekly)
                null -> ""
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Zeitraum steht hier in der Zeile dahinter, nicht in der Änderung selbst
                CompositionLocalProvider(LocalChangeView provides headerChangeView.copy(showPeriod = false)) {
                    DayChangePill(
                        change = watch.shownChange(headerChangeView),
                        note = candleNote,
                        neutral = off,
                        size = ChangePillSize.MEDIUM,
                        mark = com.cryptochecker.app.domain.watch.ChangeBasisMath.showsMark(exchange.changeIsMark, headerChangeView.basis),
                    )
                }
                // Wöchentlicher Referenzkurs: Veränderung gegen die Vorwoche — kein «24h», der Takt steht in midText
                val basisText = if (kind == com.cryptochecker.app.domain.watch.PriceKindLabel.WEEKLY_REFERENCE) ""
                else " · ${ChangeBasisText.shortLabel(headerChangeView.basis)}"
                Text(
                    text = "$basisText$midText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        // Alter des Kurses wie in der Zeile der Merkliste («vor 2 Min.»); Vorschau ohne Kurs: nichts
        if (watch.lastUpdate > 0) {
            Text(
                stringResource(R.string.watchlist_updated, ago(watch.lastUpdate, now)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
    // DEX-Pool: Token-Adresse gekürzt, Tippen kopiert
    DexTokenAddressLine(watch)
    // Referenzkurs: «Quelle: EZB · umgerechnet» (Hinweis bei Kreuzkurs oder berechneter Veränderung)
    ReferenceSource.of(watch.exchangeKey)?.takeIf { watch.lastPrice != null }?.let { source ->
        ReferenceSourceLine(source, watch.baseAsset, hasChange = watch.shownChange(headerChangeView) != null, Modifier.padding(top = 2.dp))
    }
    // Herkunft aus der Zeile: «Änderung & Chart: Binance»; sonst Änderung aus fremden Kerzen
    val sourceNote = rowSource?.takeIf { watch.lastPrice != null && !preview }?.let { rowSourceText(it) } ?: candleNote
    if (sourceNote != null) {
        Text(
            sourceNote,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    // Bitcoin: «1 CHF = 1’234 Sats» in der Umrechnungswährung
    val bitcoin = Sats.isBitcoin(watch.baseAsset) && !watch.isNotTraded
    val satsFactor by produceState<Pair<String, Double>?>(null, watch.quoteAsset, bitcoin) {
        value = if (bitcoin) satsRate(watch.conversionQuote) else null
    }
    satsFactor?.let { (currency, rate) ->
        Sats.perUnit(watch.lastPrice, rate)?.let { sats ->
            Text(
                stringResource(
                    R.string.sats_per_unit,
                    LocaleNumbers.integer(1),
                    currency,
                    LocaleNumbers.decimal(sats, maxDecimals = Sats.decimals(sats), grouping = true),
                ),
                style = MaterialTheme.typography.bodySmall.amountNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Zustand unter dem Kopf: Börse stillgelegt bzw. ihr Ende angekündigt (Grund mit Datum und «Börse wechseln», öffnet
 * «Paar bearbeiten»), deaktiviert (mit «Aktivieren»), Börse nicht erreichbar bzw. anderer Fehler (mit «Erneut
 * versuchen»), «nicht mehr gehandelt», Vorschau lädt. Sonst nichts — das Alter steht schon im Kopf.
 */
@Composable
internal fun PairStatusLine(
    watch: WatchEntity,
    preview: Boolean,
    off: Boolean,
    onRetry: () -> Unit,
    onEnable: () -> Unit,
    /** «Börse wechseln» bei stillgelegter bzw. angekündigter Börse; null = kein Knopf (Vorschau). */
    onChangeExchange: (() -> Unit)? = null,
) {
    val error = watch.lastError
    retirementReason(watch, alarms = false)?.let { RetirementLine(it, onChangeExchange) }
    when {
        off -> DisabledNotice(updatedAt = watch.lastUpdate, onEnable = onEnable)
        // Börse stillgelegt: der Grund steht schon darüber (mit Datum und «Börse wechseln»)
        ExchangeRetirement.isMarker(error) -> Unit
        watch.lastPrice != null && isRetryableExchangeError(error) ->
            RetryLine(stringResource(R.string.error_exchange_unreachable), isError = false, onRetry = onRetry)
        // Jeder andere Fehler: Text plus «Erneut versuchen» — nur «nicht gehandelt» bleibt ohne Knopf (wie iOS)
        error != null && !isNotTraded(error) -> RetryLine(friendlyError(error), isError = true, onRetry = onRetry)
        error != null -> Text(
            friendlyError(error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        // Vorschau: Kurs wird noch geholt
        preview && watch.lastUpdate <= 0 -> Text(
            stringResource(R.string.loading_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Grund zur Stilllegung der Börse ([retirementReason]) mit «Börse wechseln» ([onChangeExchange], null = ohne). */
@Composable
private fun RetirementLine(text: String, onChangeExchange: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (onChangeExchange != null) {
            TextButton(onClick = onChangeExchange) { Text(stringResource(R.string.watch_action_change_exchange)) }
        }
    }
}

@Composable
private fun RetryLine(text: String, isError: Boolean, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
    }
}
