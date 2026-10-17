package com.cryptochecker.app.ui.features.info

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.MarketTotals
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.CompactAmount

// ───────────────────────── Krypto-Markt (Marktkapitalisierung, Volumen) ─────────────────────────

/**
 * «Krypto-Markt» als erste Zeile unter «Daten»: rechts die gesamte Marktkapitalisierung mit
 * Veränderung in 24 Std., darunter das 24-Stunden-Volumen, in der Umrechnungswährung
 * [currency] (sonst USD). Beim Laden ein form-gleicher Platzhalter, ohne Daten
 * «gerade nicht verfügbar» mit «Erneut». Tippen zeigt die Quelle.
 */
@Composable
internal fun MarketTotalsRow(
    state: LoadState<MarketTotals>,
    currency: String,
    onRetry: () -> Unit,
    first: Boolean = true,
    /** Herkunft und Stand (CoinGecko) für die Nebenzeile. */
    stamp: DataStamp? = null,
) {
    val title = stringResource(R.string.market_cap_title)
    val capLabel = stringResource(R.string.market_cap_label)
    val volumeLabel = stringResource(R.string.market_volume_label)
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var expanded by rememberSaveable { mutableStateOf(false) }

    val totals = (state as? LoadState.Loaded)?.value
    val values = totals?.valuesIn(currency)
    val cap = remember(values, locale) { values?.let { CompactAmount.format(it.marketCap, it.currency, locale) } }
    val volume = remember(values, locale) { values?.let { CompactAmount.format(it.volume, it.currency, locale) } }
    val change = totals?.changePercent24h
    // Screenreader: ein Satz mit Titel, Marktkapitalisierung samt Veränderung in Worten und Volumen
    val spoken = if (cap != null && volume != null) buildString {
        append(title).append(": ").append(capLabel).append(' ').append(cap)
        if (change != null) append(", ").append(A11yText.change(context, change))
        append("; ").append(volumeLabel).append(' ').append(volume)
    } else null
    MarketRow(
        title = title,
        secondary = volume?.let { "$volumeLabel $it" }.orEmpty(),
        value = cap,
        first = first,
        loading = state is LoadState.Loading,
        // Fehler oder keine Werte (auch nicht in USD)
        failure = if (state !is LoadState.Loading && cap == null) stringResource(R.string.pulse_unavailable) else null,
        onRetry = onRetry,
        // Farbige Schrift ohne Pille wie überall: Vorzeichen nach der Richtung, Farbe nach «Kursfarben»
        change = change,
        spoken = spoken,
        stamp = stamp,
        expanded = expanded,
        onToggle = if (cap != null) ({ expanded = !expanded }) else null,
        info = MarketInfoTopic.TOTALS,
    ) {
        SourceText(stringResource(R.string.market_cap_source))
        CoinGeckoAttribution()
    }
}
