@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import com.cryptochecker.app.ui.components.ReadableMaxWidth
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.components.RollingNumberText
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.portfolio.CoinPosition
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.domain.portfolio.PortfolioSummary
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.theme.display
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat

/** Portfolio-Tab: Gesamtwert, Coins nach Wert, Erfassen per «+». */
@Composable
fun PortfolioScreen(
    onOpenCoin: (String) -> Unit,
    viewModel: PortfolioViewModel = hiltViewModel(),
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val prices by viewModel.prices.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val currency by viewModel.currency.collectAsStateWithLifecycle()
    val fxRate by viewModel.fxRate.collectAsStateWithLifecycle()
    val todayPercent by viewModel.todayPercent.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val historyRange by viewModel.historyRange.collectAsStateWithLifecycle()
    val historyExpanded by viewModel.historyExpanded.collectAsStateWithLifecycle()
    val hideAmounts by viewModel.hideAmounts.collectAsStateWithLifecycle()
    val coinChanges by viewModel.coinChanges.collectAsStateWithLifecycle()
    val changeBasis by viewModel.changeBasis.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start() }
    // Beim Öffnen des Tabs Kurse auffrischen (60 s Zwischenspeicher)
    LifecycleResumeEffect(Unit) {
        viewModel.refresh(force = false)
        onPauseOrDispose { }
    }

    var sheet by remember { mutableStateOf<TxDraft?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var pickCurrency by remember { mutableStateOf(false) }
    var showClosed by rememberSaveable { mutableStateOf(false) }
    // Saveable: Der Dialog muss die Dateiauswahl (eigene Activity) überstehen
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    // Neuer Alarm «Portfolio-Wert»
    var alarmOpen by rememberSaveable { mutableStateOf(false) }

    sheet?.let { draft ->
        PortfolioTxSheet(initial = draft, viewModel = viewModel, onDismiss = { sheet = null })
    }
    if (pickCurrency) {
        CurrencyDialog(
            selected = currency,
            onSelect = { viewModel.setCurrency(it); pickCurrency = false },
            onDismiss = { pickCurrency = false }
        )
    }

    if (exportOpen) {
        PortfolioExportDialog(currency = currency, onDismiss = { exportOpen = false })
    }
    if (alarmOpen) {
        PortfolioAlarmDialog(
            currency = currency,
            basis = changeBasis,
            onSave = { kind, threshold, repeating ->
                viewModel.addAlarm(kind, threshold, repeating)
                alarmOpen = false
            },
            onDismiss = { alarmOpen = false }
        )
    }

    val hasTransactions = !transactions.isNullOrEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.portfolio_title), fontWeight = FontWeight.SemiBold) },
                actions = {
                    // «Beträge verbergen»: alle Beträge als «•••» (Prozente bleiben), auch im Widget
                    if (hasTransactions) {
                        IconButton(onClick = { viewModel.setHideAmounts(!hideAmounts) }) {
                            Icon(
                                painterResource(if (hideAmounts) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                                contentDescription = stringResource(
                                    if (hideAmounts) R.string.a11y_portfolio_show_amounts else R.string.portfolio_hide_amounts
                                )
                            )
                        }
                    }
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(horizontal = Spacing.md).size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else if (hasTransactions) {
                        IconButton(onClick = { viewModel.refresh(force = true) }) {
                            Icon(
                                painterResource(R.drawable.ic_refresh),
                                contentDescription = stringResource(R.string.action_refresh)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                contentDescription = stringResource(R.string.action_more)
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.portfolio_convert_to_value, currency)) },
                                onClick = { menuOpen = false; pickCurrency = true }
                            )
                            if (hasTransactions) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.portfolio_export_action)) },
                                    onClick = { menuOpen = false; exportOpen = true }
                                )
                                // Alarm «Portfolio-Wert» (erscheint in der Alarm-Übersicht)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.portfolio_alarm_action)) },
                                    onClick = { menuOpen = false; alarmOpen = true }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (hasTransactions) {
                FloatingActionButton(onClick = { sheet = TxDraft() }) {
                    Icon(
                        painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.portfolio_add_tx)
                    )
                }
            }
        }
    ) { padding ->
        val current = summary
        if (transactions == null || current == null) {
            SkeletonList(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        if (!hasTransactions) {
            EmptyPortfolio(
                modifier = Modifier.fillMaxSize().padding(padding),
                currency = currency,
                onAdd = { sheet = TxDraft() }
            )
            return@Scaffold
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh(force = true) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Tablet/Querformat: Inhalt höchstens 640 dp breit, Liste bleibt voll breit scrollbar
            ReadableInset { inset ->
                CompositionLocalProvider(LocalHidePortfolioAmounts provides hideAmounts) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Unten Platz für den «+»-Knopf
                        contentPadding = PaddingValues(start = 16.dp + inset, top = 4.dp, end = 16.dp + inset, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "total") {
                            TotalCard(
                                summary = current,
                                updatedAt = prices.updatedAt,
                                currency = currency,
                                fxRate = fxRate,
                                todayPercent = todayPercent
                            )
                        }
                        // Wertverlauf über den Positionen
                        item(key = "history") {
                            PortfolioHistoryCard(
                                history = history,
                                range = historyRange,
                                onRange = viewModel::setHistoryRange,
                                expanded = historyExpanded,
                                onExpandedChange = viewModel::setHistoryExpanded
                            )
                        }
                        // Aufteilung (vier grösste Coins + «Andere»), erst ab zwei Teilen
                        val slices = PortfolioInsights.allocation(current.open)
                        if (slices.size >= 2) {
                            item(key = "allocation") { AllocationCard(slices) }
                        }
                        // Grösste Bewegungen über die %-Basis (sobald es eine Vergleichsbasis gibt)
                        val movers = PortfolioInsights.movers(current.open, coinChanges)
                        if (movers.isNotEmpty()) {
                            item(key = "movers") { MoversCard(movers, changeBasis) }
                        }
                        items(current.open, key = { "open:${it.coin}" }) { position ->
                            CoinRow(position = position, onClick = { onOpenCoin(position.coin) })
                        }
                        if (current.closed.isNotEmpty()) {
                            item(key = "closed_header") {
                                Text(
                                    text = (if (showClosed) "▾ " else "▸ ") +
                                        pluralStringResource(R.plurals.portfolio_closed, current.closed.size, current.closed.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showClosed = !showClosed }
                                        .padding(horizontal = 4.dp, vertical = Spacing.md)
                                )
                            }
                            if (showClosed) {
                                items(current.closed, key = { "closed:${it.coin}" }) { position ->
                                    ClosedRow(position = position, onClick = { onOpenCoin(position.coin) })
                                }
                            }
                        }
                        item(key = "disclaimer") { PortfolioDisclaimer() }
                    }
                }
            }
        }
    }
}

/**
 * Gesamtwert (gross, rollende Ziffern), «heute», ± unrealisiert, investiert/realisiert,
 * Umrechnung und Stand der Kurse. Kopf wie auf iOS: sehr dezenter Verlauf in der
 * Akzentfarbe (16 % → 4 %), Ecken 20 dp, Rand in der Akzentfarbe (25 %).
 */
@Composable
private fun TotalCard(
    summary: PortfolioSummary,
    updatedAt: Long,
    currency: String,
    fxRate: Double?,
    todayPercent: Double?,
) {
    val accent = MaterialTheme.colorScheme.primary
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Von oben links nach unten rechts, wie LinearGradient(.topLeading → .bottomTrailing)
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.04f))))
                .padding(Spacing.lg)
        ) {
            Text(
                stringResource(R.string.portfolio_total_value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FittedTotal(
                text = maskAmount(PortfolioFormat.usdt(summary.totalValue)),
                value = summary.totalValue,
                modifier = Modifier.padding(top = 2.dp)
            )
            // «≈ 12’345.67 CHF» — nur mit Devisenkurs und nicht bei USD
            if (currency != "USD" && fxRate != null) {
                Text(
                    "≈ " + maskAmount(PriceFormat.valueWithCurrency(summary.totalValue * fxRate, currency)),
                    style = MaterialTheme.typography.bodyMedium.amountNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Veränderung heute (wie im Portfolio-Widget), sobald es eine Vergleichsbasis gibt
            if (todayPercent != null) {
                TodayPill(todayPercent, modifier = Modifier.padding(top = Spacing.xs))
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.sm)) {
                Text(
                    summary.unrealized?.let { maskAmount(PortfolioFormat.signedUsdt(it)) } ?: "—",
                    style = MaterialTheme.typography.titleMedium.amountNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = plColor(summary.unrealized)
                )
                if (summary.unrealized != null) {
                    PlPill(summary.unrealizedPercent, modifier = Modifier.padding(start = 8.dp))
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Metric(
                    label = stringResource(R.string.portfolio_invested),
                    value = summary.invested?.let { maskAmount(PortfolioFormat.usdt(it)) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                if (!PortfolioFormat.isZero(summary.realized)) {
                    Metric(
                        label = stringResource(R.string.portfolio_realized),
                        value = maskAmount(PortfolioFormat.signedUsdt(summary.realized)),
                        valueColor = plColor(summary.realized),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Hinweise, warum ± fehlt
            if (summary.costMissing) {
                PortfolioHint(stringResource(R.string.portfolio_price_missing_hint))
            }
            if (summary.missingCurrentPrices.isNotEmpty()) {
                PortfolioHint(stringResource(R.string.portfolio_current_missing, summary.missingCurrentPrices.joinToString(", ")))
            }
            if (updatedAt > 0) {
                Text(
                    stringResource(R.string.portfolio_updated, PriceFormat.time(updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }
        }
    }
}

/**
 * Gesamtwert gross (displaySmall, Betragsschrift) mit rollenden Ziffern. Passt er nicht
 * in die Breite, wird die Schrift verkleinert (höchstens auf die Hälfte, wie iOS
 * `minimumScaleFactor(0.5)`).
 */
@Composable
private fun FittedTotal(text: String, value: Double, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val base = MaterialTheme.typography.display.amountNumbers().copy(fontWeight = FontWeight.SemiBold)
        val measurer = rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val style = remember(text, maxPx, base) {
            val width = measurer.measure(text, base, maxLines = 1, softWrap = false).size.width
            if (!constraints.hasBoundedWidth || width <= maxPx || width == 0) base
            else base.copy(fontSize = base.fontSize * (maxPx.toFloat() / width * 0.98f).coerceAtLeast(0.5f))
        }
        RollingNumberText(text = text, value = value, style = style)
    }
}

/** «heute ▲ +1.23%» als Pille in der Kursfarbe; vorgelesen «heute gestiegen um 1.23%». */
@Composable
private fun TodayPill(percent: Double, modifier: Modifier = Modifier) {
    val color = plColor(percent)
    val arrow = if (PortfolioFormat.isZero(percent)) "" else PriceFormat.changeArrow(percent)
    val value = PortfolioFormat.signedPercent(percent).let { if (arrow.isEmpty()) it else "$arrow $it" }
    RollingNumberText(
        text = stringResource(R.string.widget_portfolio_today, value),
        value = percent,
        style = MaterialTheme.typography.labelMedium.amountNumbers(),
        fontWeight = FontWeight.SemiBold,
        color = color,
        contentDescription = stringResource(R.string.widget_portfolio_today, A11yText.change(LocalContext.current, percent)),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
internal fun PortfolioHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/** Zeile je Coin: Plakette, Symbol, Menge, Ø/aktuell, Wert und ± %. */
@Composable
private fun CoinRow(position: CoinPosition, onClick: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 12.dp)
        ) {
            CoinBadge(position.coin)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        position.coin,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (position.oversold) {
                        Icon(
                            painterResource(R.drawable.ic_error),
                            contentDescription = stringResource(R.string.portfolio_oversold),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = Spacing.xs).size(14.dp)
                        )
                    }
                }
                Text(
                    maskAmount(PortfolioFormat.amount(position.holdings, position.coin)),
                    style = MaterialTheme.typography.bodySmall.amountNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Grosse Schrift: Menge bricht um statt abgeschnitten zu werden
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(
                        R.string.portfolio_avg_and_now,
                        PriceFormat.price(position.avgCost),
                        PriceFormat.price(position.currentPrice)
                    ),
                    style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    position.value?.let { maskAmount(PortfolioFormat.usdt(it)) } ?: "—",
                    style = MaterialTheme.typography.titleSmall.amountNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                if (position.priceMissing) {
                    Text(
                        stringResource(R.string.portfolio_price_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                } else {
                    PlPill(position.unrealizedPercent, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

/** Geschlossene Position: nur der realisierte Gewinn/Verlust. */
@Composable
private fun ClosedRow(position: CoinPosition, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = 8.dp)
    ) {
        CoinBadge(position.coin, size = 32.dp)
        Text(
            position.coin,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 12.dp)
        )
        Text(
            maskAmount(PortfolioFormat.signedUsdt(position.realized)),
            style = MaterialTheme.typography.bodyMedium.amountNumbers(),
            color = plColor(position.realized)
        )
    }
}

@Composable
internal fun PortfolioDisclaimer() {
    Text(
        stringResource(R.string.portfolio_disclaimer),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
    )
}

/** Kaufkurs im Beispiel des leeren Portfolios. */
private const val EXAMPLE_BUY_PRICE = 58_000.0

@Composable
private fun EmptyPortfolio(modifier: Modifier, currency: String, onAdd: () -> Unit) {
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = ReadableMaxWidth)
        ) {
            Icon(
                painterResource(R.drawable.ic_portfolio),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Text(
                stringResource(R.string.portfolio_empty_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                stringResource(R.string.portfolio_empty_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            // Beispiel mit Kaufkurs in der gewählten Umrechnungswährung
            Text(
                stringResource(
                    R.string.portfolio_empty_example,
                    PriceFormat.priceWithCurrency(EXAMPLE_BUY_PRICE, currency)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(onClick = onAdd, modifier = Modifier.padding(top = Spacing.lg)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.portfolio_empty_action), modifier = Modifier.padding(start = 8.dp))
            }
            PortfolioDisclaimer()
        }
    }
}

/** Zielwährung der Umrechnungszeile wählen. */
@Composable
private fun CurrencyDialog(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.portfolio_convert_to)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(FxRateSource.CURRENCIES, key = { it }) { code ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(code) }
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(selected = code == selected, onClick = { onSelect(code) })
                        Text(code, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}
