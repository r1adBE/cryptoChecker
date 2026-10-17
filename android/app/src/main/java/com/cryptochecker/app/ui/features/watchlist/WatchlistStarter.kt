package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Arrangement
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.ui.components.PriceWithQuote
import com.cryptochecker.app.ui.components.ListSegment
import com.cryptochecker.app.ui.components.segmentLines
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.testTag
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.domain.starter.StarterPrice
import com.cryptochecker.app.domain.starter.StarterSelection
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.theme.LocalAccentColor
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.ui.UiTestTags

/**
 * Leere Merkliste: die fünf grössten Coins (ohne Stablecoins) als Auswahlliste im Stil
 * der Karte «Ungewöhnliche Aktivität» — je Zeile Coin, aktueller Kurs, 24-Stunden-Änderung
 * und Häkchen; anfangs alle gewählt. Ein Knopf legt alle gewählten auf einmal an
 * (gleicher Weg und Erst-Moment wie bisher). Darunter der bisherige Hinweis und der
 * Weg zu jedem anderen Coin (Seite «Paar hinzufügen» mit der Coin-Suche, auch über «+» oben rechts). [coins]
 * sofort aus Zwischenspeicher bzw. Ausweich-Liste; frischere Daten ersetzen sie still.
 */
@Composable
internal fun StarterPicker(
    modifier: Modifier,
    coins: List<StarterPairs.Coin>,
    deselected: Set<String>,
    prices: StarterPricesState,
    quote: String,
    adding: Boolean,
    /** Logos der Start-Coins geladen (bzw. aufgegeben): bis dahin Platzhalter statt Initialen. */
    logosReady: Boolean = true,
    onLoad: () -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onAdd: () -> Unit,
    onAddClick: () -> Unit,
    /** Glocke oben rechts: Übersicht bzw. ohne Alarm zum Anlegen ([com.cryptochecker.app.domain.watch.AlarmBellRoute]). */
    onOpenAlarms: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onLoad() }
    val selectedCount = StarterSelection.selected(coins, deselected).size
    val allSelected = StarterSelection.allSelected(coins, deselected)

    // «+» oben rechts (der Hinweis unten verweist darauf); in der Merkliste steht es neben der Lupe
    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
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
                modifier = Modifier.padding(top = Spacing.md, start = 16.dp, end = 16.dp)
            )
            // Unterzeile «Ohne Konto. Ohne Werbung. Ohne Tracking.»: letzte Zeile von welcome_text (wie «Über die App»)
            Text(
                text = stringResource(R.string.welcome_text).substringAfterLast('\n'),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp)
            )
            Text(
                text = stringResource(R.string.starter_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)
            )

            // Wie die Merkliste: die Zeilen als eine Karte mit feinen Trennlinien ([ListSegment])
            Column(
                verticalArrangement = Arrangement.spacedBy(ListSegment.Gap),
                modifier = Modifier.padding(top = Spacing.lg).fillMaxWidth()
            ) {
                coins.forEachIndexed { index, coin ->
                    val shape = ListSegment.shape(index, coins.size)
                    Card(
                        shape = shape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier
                            .segmentLines(shape, divider = ListSegment.dividerColor(), outline = ListSegment.outlineColor())
                            .fillMaxWidth()
                    ) {
                    StarterRow(
                        coin = coin,
                        selected = StarterSelection.isSelected(coin.symbol, deselected),
                        price = prices.prices[coin.symbol],
                        loading = prices.loading,
                        logosReady = logosReady,
                        quote = quote,
                        onToggle = { onToggle(coin.symbol) },
                    )
                    }
                }
            }

            // Ein Satz, was die App hier tut: «Kurs beobachten, Alarm setzen» (Glocke oben rechts)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = Spacing.md, start = 16.dp, end = 16.dp)
            ) {
                // Dieselbe Alarm-Glocke wie oben rechts (die schlichte Glocke = Kurs-Mitteilung)
                Icon(
                    painterResource(R.drawable.ic_notifications_active_outline),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.starter_watch_alarm_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            // Und was man danach bekommt: Tipp auf einen Coin zeigt «Was bewegt sich mit?» (Symbol wie der ⚡ der Zeile)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = Spacing.xs, start = 16.dp, end = 16.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_bolt),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.watch_hint_tap_why),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            Button(
                onClick = onAdd,
                enabled = selectedCount > 0 && !adding,
                // Kennung für den Baseline-Profile-Generator
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(min = 48.dp).testTag(UiTestTags.STARTER_ADD)
            ) {
                Text(stringResource(R.string.starter_add_selected, selectedCount))
            }
            TextButton(onClick = onToggleAll, modifier = Modifier.padding(top = 2.dp)) {
                Text(stringResource(if (allSelected) R.string.starter_select_none else R.string.starter_select_all))
            }
            // Der Weg zu jedem anderen Coin: «Paar hinzufügen» mit der Coin-Suche oben. Keine Werbung für das
            // Portfolio hier — es ist ab Werk aus und gehört nicht zum ersten Schritt eines Preis-Checkers.
            OutlinedButton(
                onClick = onAddClick,
                modifier = Modifier.padding(top = Spacing.sm).fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.starter_search_coin), modifier = Modifier.padding(start = 8.dp))
            }
        }
        // Glocke und «+» wie in der Merkliste, hier nur als Kreise (darunter steht der Textknopf)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = Spacing.sm, end = Spacing.md)
        ) {
            IconButton(onClick = onOpenAlarms) {
                // Alarm-Glocke mit Wellen (Umriss: noch kein Alarm) wie im Kopf der Merkliste
                Icon(
                    painterResource(R.drawable.ic_notifications_active_outline),
                    contentDescription = stringResource(R.string.alarms_overview_title)
                )
            }
            AddPairButton(onClick = onAddClick, modifier = Modifier, labelled = false)
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
