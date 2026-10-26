// Kopf des Aktionsblatts: Paar, Kurs, Zeile «↗ +41.71% · 24h · Aktualisiert HH:MM», Herkunft und Sats, dazu der
// Vorlese-Text des Kurses und der Hinweis bei deaktivierten Paaren.
// Eigene Datei wie `WatchActionsHeader.swift`; das Blatt `WatchActionsSheet` setzt sie zusammen.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.PriceFreshness
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.util.A11ySpeech
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.domain.convert.Sats
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.ui.components.ChangePillSize
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.display
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.LocaleNumbers

/**
 * Deaktiviertes Paar im Aktionsblatt: ruhiger Hinweis unter dem Kurs («Deaktiviert · Keine Abfragen,
 * keine Meldungen», Stand des letzten Kurses) und gleich daneben «Aktivieren» — auch bei
 * zugeklapptem «Mehr» sofort erreichbar.
 */
@Composable
internal fun DisabledNotice(updatedAt: Long, onEnable: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
    ) {
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
 * Kurs im Kopf des Blatts für den Screenreader: «Kurs 61’234.00 US-Dollar» und, wenn veraltet
 * ([A11ySpeech.freshness]), «Kurs veraltet, zuletzt aktualisiert vor 45 Minuten». Deaktiviert,
 * nicht erreichbar und nicht mehr gehandelt stehen schon als Text darunter.
 */
@Composable
internal fun sheetPriceSpoken(watch: WatchEntity, priceText: String, off: Boolean, now: Long, outdatedAfter: Long): String {
    val context = LocalContext.current
    val notTraded = isNotTraded(watch.lastError)
    val freshness = A11ySpeech.freshness(
        disabled = off,
        notTraded = notTraded,
        failed = watch.lastError != null && !notTraded,
        lastUpdate = watch.lastUpdate,
        now = now,
        outdatedAfter = outdatedAfter,
    )
    val outdated = freshness == A11ySpeech.Freshness.OUTDATED || freshness == A11ySpeech.Freshness.UNREACHABLE_OUTDATED
    val stale = if (outdated) {
        A11yText.freshness(context, A11ySpeech.Freshness.OUTDATED, watch.lastUpdate, now, watch.marketName)
    } else if (!notTraded && watch.lastError == null && !off && outdatedAfter > 0 &&
        PriceFreshness.staleReason(watch, now, outdatedAfter) == PriceFreshness.StaleReason.TRADE
    ) {
        // Abruf frisch, letzter Handel laut Börse > 2 h (Regel der Alarmsperre): wie die Zeile
        stringResource(R.string.watchlist_row_last_trade_age, ago(watch.exchangeTime ?: 0L, now))
    } else {
        null
    }
    val price = stringResource(R.string.a11y_price, A11ySpeech.spokenPrice(priceText, watch.quoteAsset, A11yText.locale(context)))
    return A11ySpeech.join(listOf(price, stale))
}

/**
 * Kopf des Aktionsblatts: Paar und Börse mit Stift «Paar bearbeiten» ([onEditPair], nicht in der Vorschau), Kurs gross,
 * darunter «↗ +41.71% · 24h · Aktualisiert 01:42:19» ([updatedInline]: Stand in dieser Zeile), Herkunft der Änderung
 * und bei Bitcoin «1 CHF = … Sats».
 */
@Composable
internal fun WatchActionsHeader(
    watch: WatchEntity,
    preview: Boolean,
    off: Boolean,
    updatedInline: Boolean,
    now: Long,
    outdatedAfter: Long,
    candleSource: String?,
    rowSource: com.cryptochecker.app.domain.watch.DayChange.RowSource?,
    satsRate: suspend (String) -> Pair<String, Double>?,
    onEditPair: () -> Unit,
) {
    // Kopf: Paar, Börse, Kurs gross; rechts der Stift «Paar bearbeiten»
    Row(verticalAlignment = Alignment.CenterVertically) {
        CoinBadge(
            watch.baseAsset,
            size = 32.dp,
            logo = CoinLogos.allowedFor(watch.marketKey),
        pair = CoinLogos.pairKey(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name),
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                watch.displayName,
                style = MaterialTheme.typography.headline,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                // Vorschau: «Binance · Nicht in der Merkliste»
                if (preview) "${watch.marketName} · ${stringResource(R.string.watch_preview_not_watched)}"
                else watch.marketName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // DEX-Pool: Token-Adresse gekürzt, Tippen kopiert
            DexTokenAddressLine(watch)
        }
        // IconButton: 48 dp Tippfläche; Vorschau: nichts zu bearbeiten
        if (!preview) {
            IconButton(onClick = onEditPair) {
                Icon(
                    painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.watch_edit_title),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    // %-Basis der Änderung unter dem Kurs (Vorschau: rollende 24 h vom Aufrufer)
    val headerChangeView = LocalChangeView.current
    val candleNote = candleSource?.takeIf { watch.lastPrice != null }
        ?.let { stringResource(R.string.change_candle_source, it) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 12.dp)
    ) {
        // Langer Kurs wird kleiner statt abgeschnitten (schrittweise bis halbe Grösse);
        // Betragsschrift wie die Kurse in den Zeilen
        val priceStyle = MaterialTheme.typography.display.amountNumbers()
        val priceText = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset)
        // Screenreader: Kurs (Fiat als Wort) und, wenn veraltet, «Kurs veraltet, zuletzt
        // aktualisiert vor 45 Minuten» — sichtbar steht nur die Uhrzeit darunter
        val priceSpoken = sheetPriceSpoken(watch, priceText, off, now, outdatedAfter)
        BasicText(
            priceText,
            style = priceStyle.copy(fontWeight = FontWeight.SemiBold, color = LocalContentColor.current),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(
                minFontSize = priceStyle.fontSize * 0.5f,
                maxFontSize = priceStyle.fontSize,
                stepSize = 1.sp,
            ),
            modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = priceSpoken }
        )
    }
    if (watch.lastPrice != null) {
        val periodLabel = ChangeBasisText.shortLabel(headerChangeView.basis)
        val updatedText = stringResource(R.string.watchlist_updated, PriceFormat.time(watch.lastUpdate))
        // Börse meldet den Mittelkurs (z. B. Hyperliquid), keinen letzten Handel: so benennen; fehlte der
        // Mittelkurs (dünnes Orderbuch), steht der Mark-Preis da — dann so. WOO X Spot: Schluss der
        // jüngsten Stundenkerze
        val market = com.cryptochecker.marketdata.util.MarketsConfigUtils.getMarketByKey(watch.marketKey)
        val midText = when (com.cryptochecker.app.domain.watch.PriceKindLabel.of(
            priceIsMid = market.priceIsMid,
            markFallback = watch.id in LocalMarkFallback.current,
            hourlyClose = market.priceIsHourlyClose,
            dailyReference = market.priceIsDailyReference,
        )) {
            com.cryptochecker.app.domain.watch.PriceKindLabel.MARK -> " · " + stringResource(R.string.watch_price_mark_fallback)
            com.cryptochecker.app.domain.watch.PriceKindLabel.MID -> " · " + stringResource(R.string.watch_price_mid)
            com.cryptochecker.app.domain.watch.PriceKindLabel.HOURLY -> " · " + stringResource(R.string.watch_price_hourly)
            com.cryptochecker.app.domain.watch.PriceKindLabel.DAILY_REFERENCE -> " · " + stringResource(R.string.watch_price_reference)
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
                    // Änderung gegen den Mark-Preis (Market.changeIsMark): als «Mark» benennen
                    mark = com.cryptochecker.app.domain.watch.ChangeBasisMath.showsMark(
                        com.cryptochecker.marketdata.util.MarketsConfigUtils.getMarketByKey(watch.marketKey).changeIsMark,
                        headerChangeView.basis,
                    ),
                )
            }
            Text(
                text = " · $periodLabel" + midText + if (updatedInline) " · $updatedText" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
    // Herkunft aus der Zeile (L-14): «Änderung & Chart: Binance»; sonst Änderung aus fremden
    // Kerzen (z. B. Kraken-Paar → Binance) — klein darunter sagen, woher
    val sourceNote = rowSource?.takeIf { watch.lastPrice != null && !preview }?.let { rowSourceText(it) }
        ?: candleNote
    if (sourceNote != null) {
        Text(
            sourceNote,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    // Bitcoin: «1 CHF = 1’234 Sats» in der Umrechnungswährung (Kurs mit dem bestehenden Faktor)
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
