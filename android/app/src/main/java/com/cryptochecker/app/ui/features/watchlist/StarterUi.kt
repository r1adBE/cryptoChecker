package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.domain.starter.StarterPrice
import com.cryptochecker.app.domain.starter.StarterSelection
import com.cryptochecker.app.ui.features.portfolio.CoinBadge
import com.cryptochecker.app.ui.theme.LocalAccentColor
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat

/**
 * Leere Merkliste: die fünf grössten Coins (ohne Stablecoins) als Auswahlliste im Stil
 * der Karte «Ungewöhnliche Aktivität» — je Zeile Coin, aktueller Kurs, 24-Stunden-Pille
 * und Häkchen; anfangs alle gewählt. Ein Knopf legt alle gewählten auf einmal an
 * (gleicher Weg und Erst-Moment wie bisher). Darunter der bisherige Hinweis und der
 * Weg zur eigenen Auswahl im Tab «Hinzufügen». [coins] sofort aus Zwischenspeicher bzw.
 * Ausweich-Liste; frischere Daten ersetzen sie still.
 */
@Composable
internal fun StarterPicker(
    modifier: Modifier,
    coins: List<StarterPairs.Coin>,
    deselected: Set<String>,
    prices: StarterPricesState,
    quote: String,
    adding: Boolean,
    onLoad: () -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onAdd: () -> Unit,
    onAddClick: () -> Unit,
) {
    LaunchedEffect(Unit) { onLoad() }
    val selectedCount = StarterSelection.selected(coins, deselected).size
    val allSelected = StarterSelection.allSelected(coins, deselected)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .verticalScroll(rememberScrollState())
            // Tablet/Querformat: höchstens 640 dp breit, mittig
            .readableWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        Image(
            painter = painterResource(LocalAccentColor.current.logoRes(LocalDarkTheme.current)),
            contentDescription = null,
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = stringResource(R.string.starter_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp, start = 16.dp, end = 16.dp)
        )
        Text(
            text = stringResource(R.string.starter_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)
        )

        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier.padding(top = 20.dp).fillMaxWidth()
        ) {
            coins.forEachIndexed { index, coin ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 66.dp)
                    )
                }
                StarterRow(
                    coin = coin,
                    selected = StarterSelection.isSelected(coin.symbol, deselected),
                    price = prices.prices[coin.symbol],
                    loading = prices.loading,
                    quote = quote,
                    onToggle = { onToggle(coin.symbol) },
                )
            }
        }

        Button(
            onClick = onAdd,
            enabled = selectedCount > 0 && !adding,
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Text(stringResource(R.string.starter_add_selected, selectedCount))
        }
        TextButton(onClick = onToggleAll, modifier = Modifier.padding(top = 2.dp)) {
            Text(stringResource(if (allSelected) R.string.starter_select_none else R.string.starter_select_all))
        }
        Text(
            text = stringResource(R.string.watchlist_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp, start = 16.dp, end = 16.dp)
        )
        TextButton(onClick = onAddClick, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.starter_custom), textAlign = TextAlign.Center)
        }
    }
}

/**
 * Eine Zeile der Start-Auswahl. Tippen schaltet die Auswahl; für den Screenreader ein
 * Element («Bitcoin, BTC, 98’450 USDT, gestiegen um 2.30 %», Kontrollkästchen an/aus).
 */
@Composable
private fun StarterRow(
    coin: StarterPairs.Coin,
    selected: Boolean,
    price: StarterPrice?,
    loading: Boolean,
    quote: String,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    // «XRP XRP» vermeiden: Symbol nur, wenn es vom Namen abweicht
    val showSymbol = !coin.symbol.equals(coin.name, ignoreCase = true)
    val priceText = price?.let { PriceFormat.priceWithCurrency(it.price, quote) }
    val spoken = listOfNotNull(
        coin.name,
        coin.symbol.takeIf { showSymbol },
        priceText,
        price?.change24h?.let { A11yText.change(context, it) },
        if (price == null && loading) stringResource(R.string.starter_loading_prices) else null,
    ).joinToString(", ")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = spoken }
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Box(Modifier.clearAndSetSemantics { }) {
            CoinBadge(coin.symbol, size = 40.dp)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
                .clearAndSetSemantics { }
        ) {
            Text(
                text = coin.name,
                style = MaterialTheme.typography.titleSmall,
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
                    Text(
                        text = priceText.orEmpty(),
                        style = MaterialTheme.typography.titleSmall.amountNumbers(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    price.change24h?.let { ChangePill(change = it) }
                }
                // Platzhalter, solange die Kurse laden
                loading -> {
                    PriceSkeleton(width = 76)
                    PriceSkeleton(width = 52, modifier = Modifier.padding(top = 6.dp))
                }
                // Ohne Kurs (Fehler): Zeile bleibt wählbar, nur ohne Zahlen
            }
        }
        Box(Modifier.clearAndSetSemantics { }) {
            // Nur Anzeige; die ganze Zeile schaltet
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(start = 10.dp, end = 8.dp))
        }
    }
}

/** Grauer Balken anstelle einer Zahl. */
@Composable
private fun PriceSkeleton(width: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(width.dp)
            .height(14.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    )
}
