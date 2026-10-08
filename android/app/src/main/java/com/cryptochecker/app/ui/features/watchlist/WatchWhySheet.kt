@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.activity.Reason
import com.cryptochecker.app.domain.activity.ReasonKind
import com.cryptochecker.app.domain.activity.WhyFactors
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.domain.activity.WhySummary
import com.cryptochecker.app.notification.ActivityTexts
import com.cryptochecker.app.ui.components.FactorRow
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.ui.theme.title
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ladezustand des «Warum»-Blatts. */
private sealed interface WhyState {
    data object Loading : WhyState
    data class Loaded(val report: WhyReport) : WhyState
    data object Failed : WhyState
}

/**
 * «Warum bewegt sich BTC?» als klare Faktorliste: Kopf mit Paar und Veränderung,
 * «Wahrscheinliche Gründe · Sicherheit», bis zu fünf Faktoren ([WhyFactors], stärkster
 * oben, neutrale abgeblendet), «Kurz gesagt: …» als ein Satz, dann «Details anzeigen»
 * (die Gründe mit Erklärung) und die Fusszeile. Nur Marktdaten: Markt vs. Coin, Volumen,
 * Volatilität, Futures (Open Interest, Funding), Nähe zum 30-Tage-Hoch, Stimmung.
 */
@Composable
internal fun WhySheet(
    watch: WatchEntity,
    signals: List<ActivitySignal>,
    load: suspend (WatchEntity) -> WhyReport?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<WhyState>(initialValue = WhyState.Loading, watch.id, attempt) {
        value = WhyState.Loading
        value = load(watch)?.let { WhyState.Loaded(it) } ?: WhyState.Failed
    }
    val context = LocalContext.current

    val motion = !rememberReduceMotion()
    // Platzhalter → Inhalt: überblenden (bei reduzierter Bewegung sofort)
    val fade = tween<Float>(durationMillis = if (motion) SWAP_MILLIS else 0)
    val resize = if (motion) Modifier.animateContentSize(tween(SWAP_MILLIS)) else Modifier

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Gleich in voller Höhe: wächst der Inhalt (Laden, «Details»), springt das Blatt nicht
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
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
                watch.marketName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val report = (state as? WhyState.Loaded)?.report
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
                ChangeBadge(stringResource(R.string.why_change_24h), report?.change24h)
            }

            // Was gerade auffällt (die Signale hinter dem ⚡)
            if (signals.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    signals.take(3).forEach { signal ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                painterResource(R.drawable.ic_bolt), null,
                                tint = AppColors.warning,
                                modifier = Modifier.padding(top = 2.dp).size(14.dp)
                            )
                            Text(
                                ActivityTexts.signal(context, signal),
                                style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            // «Wahrscheinliche Gründe · Sicherheit», Faktorliste, «Kurz gesagt», Details
            // (beim Laden ein form-gleicher Platzhalter an derselben Stelle)
            Crossfade(targetState = state, animationSpec = fade, modifier = resize, label = "whyFactors") { s ->
                when (s) {
                    WhyState.Loading -> FactorCardSkeleton()
                    WhyState.Failed -> EmptyReasons(onRetry = { attempt++ })
                    is WhyState.Loaded -> {
                        val r = s.report
                        val factors = remember(r) { WhyFactors.rank(r) }
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            if (!r.hasMarketData) {
                                EmptyReasons(onRetry = null)
                            }
                            if (factors.isNotEmpty()) WhyFactorCard(r, factors, watch.baseAsset)
                            if (r.reasons.isNotEmpty()) ReasonDetails(r.reasons, animate = motion)
                        }
                    }
                }
            }

            // Fusszeile: Hinweis und Datenzeit
            Text(
                stringResource(R.string.why_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                stringResource(R.string.pulse_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            report?.let {
                Text(
                    stringResource(R.string.why_data_time, PriceFormat.time(it.dataTime)),
                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** «1h +2.31 %» — grün/rot, grau bei 0.00 %; «—» ohne Daten. */
@Composable
private fun ChangeBadge(label: String, change: Double?) {
    val zero = change == null || abs(change) < 0.005
    // Pfeil wie in der Merkliste (folgt dem Vorzeichen, nie dem Farbtausch)
    val text = change?.let { value ->
        val arrow = PriceFormat.changeArrow(value)
        (if (arrow.isEmpty()) "" else "$arrow ") + ActivityTexts.percent(value, 2)
    } ?: "—"
    val color = if (zero) MaterialTheme.colorScheme.onSurfaceVariant else PriceColors.forChange(change)
    // Screenreader: «1h, gestiegen um 2.31%» statt «1h +2.31 %»
    val spoken = change?.let { "$label, " + A11yText.change(LocalContext.current, it) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = Spacing.sm, vertical = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.tabularNumbers(),
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(start = Spacing.xs)
        )
    }
}

/**
 * «Details anzeigen»: die einzelnen Gründe als Checkliste ✓ / – / ! mit ihrer Erklärung
 * (wie bisher unter «Details»); eingeklappt nur der Knopf.
 */
@Composable
private fun ReasonDetails(reasons: List<Reason>, animate: Boolean) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Details auf-/zuklappen: wächst weich, das Blatt bleibt stehen
            .then(if (animate) Modifier.animateContentSize(tween(SWAP_MILLIS)) else Modifier)
    ) {
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(stringResource(if (showDetails) R.string.why_details_hide else R.string.why_details))
        }
        if (showDetails) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = Spacing.md, vertical = 8.dp)
            ) {
                reasons.forEach { reason ->
                    val factor = reasonFactor(reason)
                    FactorRow(
                        mark = WhySummary.mark(reason),
                        title = factor.title,
                        value = factor.value,
                        spokenValue = factor.spokenValue,
                        detail = reasonExplanation(reason)
                    )
                }
            }
        }
    }
}

/** Kurzer Titel, Wert rechts und gesprochener Wert einer Checklisten-Zeile. */
private data class ReasonFactor(val title: String, val value: String, val spokenValue: String = value)

@Composable
private fun reasonFactor(r: Reason): ReasonFactor {
    val context = LocalContext.current
    fun btc(v: Double?): ReasonFactor? = v?.let {
        ReasonFactor("", "BTC " + ActivityTexts.percent(it, 1), "BTC, " + A11yText.change(context, it, decimals = 1))
    }
    return when (r.kind) {
        ReasonKind.MARKET_WIDE ->
            (btc(r.value) ?: ReasonFactor("", "")).copy(title = stringResource(R.string.why_f_market_wide))
        ReasonKind.COIN_ONLY ->
            (btc(r.secondary) ?: ReasonFactor("", "")).copy(title = stringResource(R.string.why_f_coin_only))
        ReasonKind.AGAINST_MARKET ->
            (btc(r.secondary) ?: ReasonFactor("", "")).copy(title = stringResource(R.string.why_f_against))
        ReasonKind.MARKET_CALM ->
            (btc(r.value) ?: ReasonFactor("", "")).copy(title = stringResource(R.string.why_f_market_calm))
        // Der Coin ist Bitcoin selbst (24h steht oben): rechts ETH zum Vergleich
        ReasonKind.MARKET_LEADER -> {
            val title = stringResource(R.string.why_f_leader)
            r.secondary?.let {
                ReasonFactor(title, "ETH " + ActivityTexts.percent(it, 1), "ETH, " + A11yText.change(context, it, decimals = 1))
            } ?: ReasonFactor(title, "")
        }
        ReasonKind.VOLUME_HIGH, ReasonKind.VOLUME_LOW, ReasonKind.VOLUME_NORMAL -> {
            val number = "%.2f".format(r.value)
            ReasonFactor(
                stringResource(R.string.factor_volume), "$number×",
                stringResource(R.string.factor_spoken_volume, number)
            )
        }
        ReasonKind.LEVERAGE_LONGS ->
            ReasonFactor(stringResource(R.string.why_f_leverage_longs), fundingText(r.value))
        ReasonKind.LEVERAGE_SHORTS ->
            ReasonFactor(stringResource(R.string.why_f_leverage_shorts), fundingText(r.value))
        ReasonKind.LEVERAGE_BALANCED ->
            ReasonFactor(stringResource(R.string.why_f_leverage_balanced), fundingText(r.value))
        ReasonKind.VOLATILITY_HIGH, ReasonKind.VOLATILITY_NORMAL -> {
            val number = ActivityTexts.factor(r.value)
            ReasonFactor(
                stringResource(R.string.factor_volatility), "$number×",
                stringResource(R.string.factor_spoken_volatility, number)
            )
        }
        ReasonKind.SENTIMENT -> {
            val value = r.value.roundToInt()
            val label = fearGreedLabel(value)
            val number = LocaleNumbers.integer(value)
            ReasonFactor(stringResource(R.string.factor_fear_greed), "$number · $label", "$number, $label")
        }
    }
}

/** Leerzustand: Binance führt das Paar nicht (oder nichts erreichbar). */
@Composable
private fun EmptyReasons(onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp)
    ) {
        Text(
            stringResource(R.string.why_no_data),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.why_no_data_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/** Erklärung eines Grundes mit formatierten Zahlen (unter «Details» bzw. bei «!»). */
@Composable
private fun reasonExplanation(r: Reason): String {
    fun pct(v: Double?) = v?.let { ActivityTexts.percent(it, 1) } ?: "—"
    return when (r.kind) {
        ReasonKind.MARKET_WIDE -> stringResource(R.string.why_market_wide_text, pct(r.secondary))
        ReasonKind.COIN_ONLY -> stringResource(R.string.why_coin_only_text, pct(r.value))
        ReasonKind.AGAINST_MARKET -> stringResource(R.string.why_against_market_text, pct(r.value), pct(r.secondary))
        ReasonKind.MARKET_CALM -> stringResource(R.string.why_market_calm_text, pct(r.secondary))
        ReasonKind.MARKET_LEADER -> r.secondary?.let { stringResource(R.string.why_market_leader_text, pct(it)) }
            ?: stringResource(R.string.why_market_leader_text_no_eth)
        ReasonKind.VOLUME_HIGH -> stringResource(R.string.why_volume_high_text)
        ReasonKind.VOLUME_LOW -> stringResource(R.string.why_volume_low_text)
        ReasonKind.VOLUME_NORMAL -> stringResource(R.string.why_volume_normal_text)
        ReasonKind.LEVERAGE_LONGS -> withOpenInterest(stringResource(R.string.why_leverage_longs_text), r.secondary)
        ReasonKind.LEVERAGE_SHORTS -> withOpenInterest(stringResource(R.string.why_leverage_shorts_text), r.secondary)
        ReasonKind.LEVERAGE_BALANCED -> withOpenInterest(stringResource(R.string.why_leverage_balanced_text), r.secondary)
        ReasonKind.VOLATILITY_HIGH -> stringResource(R.string.why_volatility_high_text, pct(r.secondary))
        ReasonKind.VOLATILITY_NORMAL -> stringResource(R.string.why_volatility_normal_text, pct(r.secondary))
        ReasonKind.SENTIMENT -> r.secondary?.let { stringResource(R.string.why_sentiment_text, signedInt(it.roundToInt())) }
            ?: stringResource(R.string.why_sentiment_text_no_change)
    }
}
