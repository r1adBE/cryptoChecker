@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.activity.WhyFactors
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.ui.theme.title
import com.cryptochecker.app.util.PriceFormat

/** Ladezustand des «Was bewegt sich mit?»-Blatts. */
private sealed interface WhyState {
    data object Loading : WhyState
    data class Loaded(val report: WhyReport) : WhyState
    data object Failed : WhyState
}

/**
 * «Was bewegt sich mit BTC?»: Kopf mit Paar, Kurs und 1h/24h; darunter kurz die Karte «Kurz gesagt ·
 * Stand 14:00» ([WhyBriefCard]: ein Satz, bis zu drei auffällige Faktoren als grauer Text)
 * und zugeklappt «Alle Werte und Quellen» (für die Sitzung gemerkt) mit den
 * Teilen: (1) «Beobachtung» mit Datenstand — Signale und Messwerte als Tatsache;
 * (2) «Mögliche Einordnung» — nur als zeitliches Zusammentreffen formuliert, nie als Ursache;
 * (3) ausführlicher Hinweis «kein Ursachenbeweis» und die Quellen. Ganz unten kurz «Zeitgleich
 * heisst nicht Ursache · Keine Anlageberatung». Nur Marktdaten: Markt vs. Coin, Volumen,
 * Volatilität, Futures (Open Interest, Funding), Nähe zum 30-Tage-Hoch, Stimmung.
 */
@Composable
internal fun WhySheet(
    watch: WatchEntity,
    signals: List<ActivitySignal>,
    load: suspend (WatchEntity) -> WhyReport?,
    onDismiss: () -> Unit,
    /** 24-h-Wert (= Änderung, rollend) aus den Kerzen einer anderen Börse; null = nicht. */
    candleSource: String? = null,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<WhyState>(initialValue = WhyState.Loading, watch.id, attempt) {
        value = WhyState.Loading
        value = load(watch)?.let { WhyState.Loaded(it) } ?: WhyState.Failed
    }
    // «Alle Werte und Quellen»: Startwert zuletzt in dieser Sitzung
    var allValues by remember { mutableStateOf(WhyAllValuesState.expanded) }

    val motion = !rememberReduceMotion()
    // Platzhalter → Inhalt: überblenden (bei reduzierter Bewegung sofort)
    val fade = tween<Float>(durationMillis = if (motion) SWAP_MILLIS else 0)
    val resize = if (motion) Modifier.animateContentSize(tween(SWAP_MILLIS)) else Modifier

    // Als eigene Seite mit Zurück-Pfeil wie «Alarme» (kein Blatt mehr): Zurück (Pfeil oder
    // Geste) schliesst sie, aus dem Aktionsblatt geöffnet erscheint danach wieder das Blatt
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
      Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.why_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
      ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
        ) {
            // Kopf: Frage, Paar, Kurs, 1h/24h
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_lightbulb), null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    watch.baseAsset.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.why_title_coin, it) }
                        ?: stringResource(R.string.why_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                watch.displayName,
                style = MaterialTheme.typography.headline,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = Spacing.xs)
            )
            Text(
                watch.exchangeName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val report = (state as? WhyState.Loaded)?.report
            val candleNote = candleSource?.takeIf { report?.change24h != null }
                ?.let { stringResource(R.string.change_candle_source, it) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = Spacing.md)
            ) {
                Text(
                    PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
                    style = MaterialTheme.typography.titleLarge.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    // Grosse Schrift: Kurs bricht um statt abgeschnitten zu werden
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ChangeBadge(stringResource(R.string.why_change_1h), report?.change1h)
                Spacer(Modifier.width(8.dp))
                ChangeBadge(stringResource(R.string.why_change_24h), report?.change24h, note = candleNote)
            }
            // 24-h-Wert aus fremden Kerzen (z. B. Kraken-Paar → Binance): klein darunter sagen, woher
            if (candleNote != null) {
                Text(
                    candleNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = Spacing.sm),
                    textAlign = TextAlign.End
                )
            }

            // Oben kurz: «Kurz gesagt · Stand …» mit Satz und auffälligen Faktoren;
            // darunter zugeklappt «Alle Werte und Quellen» (Beobachtung, Mögliche Einordnung,
            // Quellen). Beim Laden ein form-gleicher Platzhalter
            Crossfade(targetState = state, animationSpec = fade, modifier = resize, label = "whyBrief") { s ->
                when (s) {
                    WhyState.Loading -> WhyBriefSkeleton()
                    WhyState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        EmptyReasons(onRetry = { attempt++ })
                        WhySignals(signals)
                    }
                    is WhyState.Loaded -> {
                        val r = s.report
                        val factors = remember(r) { WhyFactors.rank(r) }
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            if (!r.hasMarketData) {
                                EmptyReasons(onRetry = null)
                            }
                            WhyBriefCard(r, factors)
                            WhyAllValuesCard(
                                expanded = allValues,
                                onToggle = {
                                    allValues = !allValues
                                    WhyAllValuesState.expanded = allValues
                                },
                                animate = motion,
                            ) {
                                WhyAllValues(r, factors, signals, watch.baseAsset, motion)
                            }
                        }
                    }
                }
            }

            // Fusszeile kurz; der ausführliche Hinweis steht unter «Alle Werte und Quellen»
            Text(
                stringResource(R.string.why_footer_short),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
    }
}
