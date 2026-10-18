package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Arrangement
import com.cryptochecker.app.ui.components.ListSegment
import com.cryptochecker.app.ui.components.segmentLines
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.domain.starter.StarterSelection
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.ui.theme.LocalAccentColor
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.ui.UiTestTags

/**
 * Leere Merkliste: die fünf grössten Coins (ohne Stablecoins) als Auswahlliste im Stil
 * der Karte «Ungewöhnliche Aktivität» — je Zeile Coin, aktueller Kurs, 24-Stunden-Änderung
 * und Häkchen; anfangs alle gewählt. Ein Knopf legt alle gewählten auf einmal an
 * (gleicher Weg und Erst-Moment wie bisher). Oben steht laut, woher die Kurse kommen
 * («Kurse von Binance in USDT», [marketName] und [quote]). Darunter der bisherige Hinweis und der
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
    /** Anzeigename der Start-Börse («Binance», bei Standort-Sperre «Binance.US»). */
    marketName: String,
    adding: Boolean,
    /** Logos der Start-Coins geladen (bzw. aufgegeben): bis dahin Platzhalter statt Initialen. */
    logosReady: Boolean = true,
    onLoad: () -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onAdd: () -> Unit,
    onAddClick: () -> Unit,
    /**
     * Glocke oben rechts: Alarm-Übersicht (z. B. Portfolio-Alarme). null = keine Glocke — ohne Paar und ohne Alarm
     * führte sie nur zu «Coin hinzufügen» und doppelte das «+» daneben.
     */
    onOpenAlarms: (() -> Unit)? = null,
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
                // Screenreader: Überschrift (wie iOS .isHeader)
                modifier = Modifier.padding(top = Spacing.md, start = 16.dp, end = 16.dp).semantics { heading() }
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
            // Börse und Quote der Startpaare hervorgehoben: man soll sehen, woher die Kurse kommen
            val emphasis = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = emphasized(
                    stringResource(R.string.starter_source_text, BidiText.isolate(marketName), BidiText.isolate(quote)),
                    listOf(marketName, quote),
                    emphasis,
                ),
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
            // Kein einziger Kurs (offline, Börse nicht erreichbar): sagen, was los ist, und erneut laden lassen.
            // Hinzufügen geht trotzdem (gleich wie iOS)
            if (prices.failed && !prices.loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Spacing.sm).fillMaxWidth()
                ) {
                    Icon(
                        painterResource(R.drawable.ic_cloud_off),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.offline_status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = Spacing.xs)
                    )
                    TextButton(onClick = onLoad) { Text(stringResource(R.string.try_again)) }
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
                    modifier = Modifier.padding(start = Spacing.xs)
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
                    modifier = Modifier.padding(start = Spacing.xs)
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
        // «+» (und mit Alarmen die Glocke) wie in der Merkliste, hier nur als Kreise (darunter steht der Textknopf)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = Spacing.sm, end = Spacing.md)
        ) {
            if (onOpenAlarms != null) {
                IconButton(onClick = onOpenAlarms) {
                    // Alarm-Glocke mit Wellen wie im Kopf der Merkliste
                    Icon(
                        painterResource(R.drawable.ic_notifications_active_outline),
                        contentDescription = stringResource(R.string.alarms_overview_title)
                    )
                }
            }
            AddPairButton(onClick = onAddClick, modifier = Modifier, labelled = false)
        }
    }
}

/** Leere Merkliste: Start-Auswahl der grössten Coins ([StarterUiState]); [onStartersAdded] = mindestens ein Paar übernommen. */
@Composable
internal fun WatchlistStarterPicker(
    modifier: Modifier,
    starter: StarterUiState,
    viewModel: WatchlistViewModel,
    onAddClick: () -> Unit,
    onOpenAlarms: (() -> Unit)?,
    onStartersAdded: () -> Unit,
) {
    StarterPicker(
        modifier = modifier,
        coins = starter.coins,
        deselected = starter.deselected,
        prices = starter.prices,
        quote = starter.quote,
        marketName = starter.marketName,
        adding = starter.adding,
        logosReady = starter.logosReady,
        onLoad = viewModel::loadStarterCoins,
        onToggle = viewModel::toggleStarter,
        onToggleAll = viewModel::toggleAllStarters,
        onAdd = {
            val count = com.cryptochecker.app.domain.starter.StarterSelection.selected(starter.coins, starter.deselected).size
            viewModel.addSelectedStarters()
            // Mitteilungs-Erlaubnis erst nach dem Banner (siehe askNotificationsAfterHint in WatchlistScreen)
            if (count > 0) onStartersAdded()
        },
        onAddClick = onAddClick,
        onOpenAlarms = onOpenAlarms,
    )
}
