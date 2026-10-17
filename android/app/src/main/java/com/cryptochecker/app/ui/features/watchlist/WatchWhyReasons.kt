// Gründe von «Was bewegt sich mit?» als Liste: Titel, Wert, Erklärung je Grund, leerer Zustand mit «Erneut» und die
// Änderungs-Kennzahlen im Kopf.
// Eigene Datei, weil die Texte je Grund-Art viel Platz brauchen und vom Aufbau des Blatts getrennt
// lesbar sein sollen.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.activity.MarketRef
import com.cryptochecker.app.domain.activity.Reason
import com.cryptochecker.app.domain.activity.ReasonKind
import com.cryptochecker.app.domain.activity.WhySummary
import com.cryptochecker.app.notification.ActivityTexts
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.ExpandToggleButton
import com.cryptochecker.app.ui.components.FactorRow
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.title
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.LocaleNumbers
import kotlin.math.roundToInt

/** «1h» und daneben die Änderung als [ChangePill] («—» ohne Daten). */
@Composable
internal fun ChangeBadge(label: String, change: Double?, note: String? = null) {
    // Screenreader: «1h, gestiegen um 2.31%» statt «1h +2.31 %»; mit [note] danach der Hinweis
    val spoken = change?.let { "$label, " + A11yText.change(LocalContext.current, it) + (note?.let { n -> ", $n" } ?: "") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = Spacing.xs)
        )
        ChangePill(change = change, dashWhenMissing = true)
    }
}

/**
 * «Details anzeigen»: die einzelnen Gründe als Checkliste ✓ / – / ! mit ihrer Erklärung
 * (wie bisher unter «Details»); eingeklappt nur der Knopf.
 */
@Composable
internal fun ReasonDetails(reasons: List<Reason>, animate: Boolean) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Details auf-/zuklappen: wächst weich, das Blatt bleibt stehen
            .then(if (animate) Modifier.animateContentSize(tween(SWAP_MILLIS)) else Modifier)
    ) {
        ExpandToggleButton(
            text = stringResource(if (showDetails) R.string.why_details_hide else R.string.why_details),
            expanded = showDetails,
            onClick = { showDetails = !showDetails },
        )
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
    // Markt-Referenz des Grundes: BTC, ETH oder «Alts» (breiter Markt)
    val symbol = marketRefLabel(r.ref)
    fun btc(v: Double?): ReasonFactor? = v?.let {
        ReasonFactor("", "$symbol " + ActivityTexts.percent(it, 1), "$symbol, " + A11yText.change(context, it, decimals = 1))
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
        ReasonKind.PEG -> {
            val value = ActivityTexts.percent(r.value, 2)
            ReasonFactor(stringResource(R.string.why_factor_peg), value, A11yText.change(context, r.value, decimals = 2))
        }
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
            ReasonFactor(stringResource(R.string.why_f_leverage_longs), fundingText(r.value, r.intervalHours))
        ReasonKind.LEVERAGE_SHORTS ->
            ReasonFactor(stringResource(R.string.why_f_leverage_shorts), fundingText(r.value, r.intervalHours))
        ReasonKind.LEVERAGE_BALANCED ->
            ReasonFactor(stringResource(R.string.why_f_leverage_balanced), fundingText(r.value, r.intervalHours))
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
internal fun EmptyReasons(onRetry: (() -> Unit)?) {
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
    val estimated = if (r.approximate) " " + stringResource(R.string.why_volume_estimated_text) else ""
    return when (r.kind) {
        // Markt nur ähnlich (unter seiner Schwelle): nicht «deutlich» nennen
        ReasonKind.MARKET_WIDE -> if (WhySummary.isSimilarOnly(r)) {
            stringResource(R.string.why_market_similar_text, pct(r.secondary))
        } else {
            stringResource(R.string.why_market_wide_text, pct(r.secondary))
        }
        ReasonKind.COIN_ONLY -> stringResource(R.string.why_coin_only_text, pct(r.value))
        ReasonKind.AGAINST_MARKET -> if (r.ref == null || r.ref == MarketRef.BTC) {
            stringResource(R.string.why_against_market_text, pct(r.value), pct(r.secondary))
        } else {
            stringResource(R.string.why_against_ref_text, pct(r.value), marketRefLabel(r.ref), pct(r.secondary))
        }
        ReasonKind.MARKET_CALM -> stringResource(R.string.why_market_calm_text, pct(r.secondary))
        ReasonKind.MARKET_LEADER -> r.secondary?.let { stringResource(R.string.why_market_leader_text, pct(it)) }
            ?: stringResource(R.string.why_market_leader_text_no_eth)
        ReasonKind.VOLUME_HIGH -> stringResource(R.string.why_volume_high_text) + estimated
        ReasonKind.VOLUME_LOW -> stringResource(R.string.why_volume_low_text) + estimated
        ReasonKind.VOLUME_NORMAL -> stringResource(R.string.why_volume_normal_text) + estimated
        ReasonKind.LEVERAGE_LONGS -> withOpenInterest(stringResource(R.string.why_leverage_longs_text), r.secondary)
        ReasonKind.LEVERAGE_SHORTS -> withOpenInterest(stringResource(R.string.why_leverage_shorts_text), r.secondary)
        ReasonKind.LEVERAGE_BALANCED -> withOpenInterest(stringResource(R.string.why_leverage_balanced_text), r.secondary)
        ReasonKind.VOLATILITY_HIGH -> stringResource(R.string.why_volatility_high_text, pct(r.secondary))
        // |z| über der Schwelle, aber unter 0,5 %: ungewöhnlich für den Coin, absolut gering
        ReasonKind.VOLATILITY_NORMAL -> if (r.value >= ActivityAnalyzer.VOLATILITY_HIGH_Z) {
            stringResource(R.string.why_volatility_tiny_text, pct(r.secondary))
        } else {
            stringResource(R.string.why_volatility_normal_text, pct(r.secondary))
        }
        ReasonKind.SENTIMENT -> r.secondary?.let { stringResource(R.string.why_sentiment_text, signedInt(it.roundToInt())) }
            ?: stringResource(R.string.why_sentiment_text_no_change)
        ReasonKind.PEG -> stringResource(R.string.why_peg_text, ActivityTexts.percent(r.value, 2))
    }
}
