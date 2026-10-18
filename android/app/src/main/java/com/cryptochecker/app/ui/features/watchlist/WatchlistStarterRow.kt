// Zeile der Start-Auswahl der leeren Merkliste (Teil von `StarterPicker`): Coin mit Logo, Kurs und Auswahl.
// Eigene Datei, weil die Auswahl sonst zu lang würde.
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.ui.components.PriceWithQuote
import com.cryptochecker.app.ui.components.ListSegment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.domain.starter.StarterPrice
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat

/** [parts] (je das erste Vorkommen) in [text] mit [style] hervorheben. */
internal fun emphasized(text: String, parts: List<String>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    for (part in parts) {
        val start = if (part.isEmpty()) -1 else text.indexOf(part)
        if (start >= 0) addStyle(style, start, start + part.length)
    }
}

/**
 * Eine Zeile der Start-Auswahl. Tippen schaltet die Auswahl; für den Screenreader ein
 * Element («Bitcoin, BTC, 98’450 USDT, gestiegen um 2.30 %», Kontrollkästchen an/aus).
 */
@Composable
internal fun StarterRow(
    coin: StarterPairs.Coin,
    selected: Boolean,
    price: StarterPrice?,
    loading: Boolean,
    logosReady: Boolean,
    quote: String,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    // «XRP XRP» vermeiden: Symbol nur, wenn es vom Namen abweicht
    val showSymbol = !coin.symbol.equals(coin.name, ignoreCase = true)
    // Eigene Quote je Coin, sonst die Start-Quote der Region
    val shownQuote = price?.quote ?: quote
    val priceText = price?.let { PriceFormat.priceWithCurrency(it.price, shownQuote) }
    val spoken = listOfNotNull(
        coin.name,
        coin.symbol.takeIf { showSymbol },
        priceText,
        // Tageswert: «gestiegen um 1.20% seit 0 Uhr UTC» statt ohne Zeitraum
        price?.let { p ->
            p.change24h?.let {
                if (p.sinceUtcDay) ChangeBasisText.spoken(context, ChangeBasis.UTC_DAY, it) else A11yText.change(context, it)
            }
        },
        if (price == null && loading) stringResource(R.string.starter_loading_prices) else null,
    ).joinToString(", ")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = spoken }
            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.md, bottom = Spacing.md)
    ) {
        Box(Modifier.clearAndSetSemantics { }) {
            // Abstand an der Plakette: Logos aus → keine Plakette, kein Einzug. Erster Start: ruhiger
            // Platzhalter, bis die Logos da sind (kein Umspringen von Initialen auf Logos)
            if (logosReady) {
                CoinBadge(coin.symbol, size = ListSegment.Logo, modifier = Modifier.padding(end = 8.dp))
            } else {
                SkeletonPulse(modifier = Modifier.padding(end = 8.dp)) {
                    SkeletonBlock(Modifier.size(ListSegment.Logo))
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { }
        ) {
            Text(
                text = coin.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showSymbol) {
                Text(
                    text = coin.symbol,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(start = 8.dp).clearAndSetSemantics { }
        ) {
            when {
                price != null -> {
                    // Gegenwährung klein und grau wie der Börsenname (wie in der Merkliste)
                    PriceWithQuote(
                        price = PriceFormat.price(price.price),
                        quote = shownQuote,
                        priceStyle = MaterialTheme.typography.titleSmall.amountNumbers()
                    )
                    price.change24h?.let { change ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                            // Tageswert statt gleitender 24 h — klein und grau davor «Seit 00:00 UTC»
                            if (price.sinceUtcDay) {
                                Text(
                                    text = ChangeBasisText.longLabel(ChangeBasis.UTC_DAY),
                                    style = MaterialTheme.typography.label,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                            ChangePill(change = change)
                        }
                    }
                }
                // Platzhalter, solange die Kurse laden
                loading -> SkeletonPulse(horizontalAlignment = Alignment.End) {
                    SkeletonBlock(Modifier.width(76.dp).height(14.dp))
                    SkeletonBlock(Modifier.padding(top = Spacing.xs).width(52.dp).height(14.dp))
                }
                // Ohne Kurs (Fehler): Zeile bleibt wählbar, nur ohne Zahlen
            }
        }
        Box(Modifier.clearAndSetSemantics { }) {
            // Nur Anzeige; die ganze Zeile schaltet
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(start = Spacing.sm, end = 8.dp))
        }
    }
}
