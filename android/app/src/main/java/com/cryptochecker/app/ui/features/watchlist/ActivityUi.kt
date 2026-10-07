@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.activity.FearGreedLevel
import com.cryptochecker.app.domain.activity.Reason
import com.cryptochecker.app.domain.activity.ReasonKind
import com.cryptochecker.app.domain.activity.WhyExtra
import com.cryptochecker.app.domain.activity.WhyHeadline
import com.cryptochecker.app.domain.activity.WhyMark
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.domain.activity.WhySummary
import com.cryptochecker.app.notification.ActivityTexts
import com.cryptochecker.app.ui.components.FactorRow
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Warmes Bernstein für ⚡ — unabhängig von der Akzentfarbe, damit es bei
 * jedem Akzent als «Achtung, hier ist etwas» lesbar bleibt.
 */
internal object ActivityColors {
    private val AmberDark = Color(0xFFFFC94D)
    private val AmberLight = Color(0xFFB26B00)

    val amber: Color
        @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) AmberDark else AmberLight

    /** Dunkleres Bernstein für Text (das «!» vor Gründen): AA-Kontrast auf hellen Karten. */
    private val AmberTextLight = Color(0xFF8F5600)

    val amberText: Color
        @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) AmberDark else AmberTextLight
}

/** Kleines ⚡ in der Zeile eines Paars mit aktiven Signalen; Tipp öffnet «Warum». */
@Composable
internal fun ActivityBolt(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Icon(
            painterResource(R.drawable.ic_bolt),
            contentDescription = stringResource(R.string.activity_indicator),
            tint = ActivityColors.amber,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Schlanke Karte über der Liste: «⚡ Hier passiert gerade etwas» mit bis zu
 * vier Coins; Tipp auf einen Coin öffnet dessen «Warum»-Blatt.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ActivityCard(hot: List<WatchEntity>, onOpen: (WatchEntity) -> Unit) {
    if (hot.isEmpty()) return
    // Gleicher Coin an mehreren Börsen: einmal zeigen
    val coins = remember(hot) { hot.distinctBy { it.baseAsset.uppercase() } }
    val shown = coins.take(MAX_CARD_COINS)
    val more = coins.size - shown.size
    val amber = ActivityColors.amber

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, amber.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(28.dp).clip(CircleShape).background(amber.copy(alpha = 0.16f))
                ) {
                    Icon(painterResource(R.drawable.ic_bolt), null, tint = amber, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = stringResource(R.string.activity_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            // Alle Coins umbrechend; «+n» klappt den Rest auf, «−» wieder zu.
            var expanded by rememberSaveable { mutableStateOf(false) }
            val visible = if (expanded) coins else shown
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.padding(top = 4.dp).animateContentSize()
            ) {
                visible.forEach { watch ->
                    AssistChip(
                        onClick = { onOpen(watch) },
                        label = { Text(watch.baseAsset, fontWeight = FontWeight.Medium, maxLines = 1) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = amber.copy(alpha = 0.10f)
                        )
                    )
                }
                if (more > 0) {
                    AssistChip(
                        onClick = { expanded = !expanded },
                        label = {
                            Text(
                                text = if (expanded) "−" else stringResource(R.string.activity_more, more),
                                style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    }
}

private const val MAX_CARD_COINS = 4

/** Ladezustand des «Warum»-Blatts. */
private sealed interface WhyState {
    data object Loading : WhyState
    data class Loaded(val report: WhyReport) : WhyState
    data object Failed : WhyState
}

/**
 * «Warum bewegt sich BTC?» als Erklärmoment: zuerst «Kurz gesagt» (ein, zwei
 * Sätze), dann die 2–5 Gründe als kompakte Checkliste ✓ / – / ! (gleiche Zeile
 * wie im Crypto Pulse). Erklärungen stehen nur bei «!» oder unter «Details anzeigen».
 * Nur Marktdaten: Markt vs. Coin, Volumen, Hebel, Volatilität, Stimmung.
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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                watch.marketName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val report = (state as? WhyState.Loaded)?.report
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp)
            ) {
                Text(
                    PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
                    style = MaterialTheme.typography.titleLarge.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ChangeBadge(stringResource(R.string.why_change_1h), report?.change1h)
                Spacer(Modifier.width(8.dp))
                ChangeBadge(stringResource(R.string.why_change_24h), report?.change24h)
            }

            // «Kurz gesagt»: ein Satz aus denselben Gründen, die unten stehen
            report?.takeIf { it.hasMarketData }?.let { WhyBriefBox(it) }

            // Was gerade auffällt (die Signale hinter dem ⚡)
            if (signals.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    signals.take(3).forEach { signal ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                painterResource(R.drawable.ic_bolt), null,
                                tint = ActivityColors.amber,
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

            when (val s = state) {
                WhyState.Loading -> ReasonSkeleton()
                WhyState.Failed -> EmptyReasons(onRetry = { attempt++ })
                is WhyState.Loaded -> {
                    val r = s.report
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!r.hasMarketData) {
                            EmptyReasons(onRetry = null)
                        }
                        if (r.reasons.isNotEmpty()) ReasonChecklist(r.reasons)
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
            .padding(horizontal = 10.dp, vertical = 4.dp)
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
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/**
 * Gründe als Checkliste. Erklärungen: bei «!» immer, sonst erst nach
 * «Details anzeigen» (nur angeboten, wenn es etwas aufzuklappen gibt).
 */
@Composable
private fun ReasonChecklist(reasons: List<Reason>) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val marks = reasons.map { WhySummary.mark(it) }
    val hasHidden = marks.any { it != WhyMark.CAUTION }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        reasons.forEachIndexed { index, reason ->
            val mark = marks[index]
            val factor = reasonFactor(reason)
            val explanation = reasonExplanation(reason)
            FactorRow(
                mark = mark,
                title = factor.title,
                value = factor.value,
                spokenValue = factor.spokenValue,
                detail = explanation.takeIf { showDetails || mark == WhyMark.CAUTION }
            )
        }
        if (hasHidden) {
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(stringResource(if (showDetails) R.string.why_details_hide else R.string.why_details))
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
            ReasonFactor(stringResource(R.string.factor_fear_greed), "$value · $label", "$value, $label")
        }
    }
}

/** «Kurz gesagt» oben im Blatt; nichts, wenn keine Regel passt. */
@Composable
private fun WhyBriefBox(report: WhyReport) {
    val brief = WhySummary.brief(report.reasons)
    if (brief.isEmpty) return
    val sentences = buildList {
        brief.headline?.let { headline ->
            add(
                stringResource(
                    when (headline) {
                        WhyHeadline.CALM -> R.string.why_summary_calm
                        WhyHeadline.COIN_VOLUME -> R.string.why_summary_coin_volume
                        WhyHeadline.COIN -> R.string.why_summary_coin
                        WhyHeadline.AGAINST -> R.string.why_summary_against
                        WhyHeadline.MARKET_VOLUME -> R.string.why_summary_market_volume
                        WhyHeadline.MARKET -> R.string.why_summary_market
                    }
                )
            )
        }
        brief.extras.forEach { extra ->
            add(
                stringResource(
                    when (extra) {
                        WhyExtra.THIN -> R.string.why_summary_thin
                        WhyExtra.LONGS -> R.string.why_summary_longs
                        WhyExtra.SHORTS -> R.string.why_summary_shorts
                    }
                )
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .semantics(mergeDescendants = true) { }
            .padding(14.dp)
    ) {
        Text(
            stringResource(R.string.why_summary_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(
            sentences.joinToString(" "),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Drei ruhig pulsierende Platzhalter-Karten (bei reduzierter Bewegung stehend). */
@Composable
private fun ReasonSkeleton() {
    val transition = rememberInfiniteTransition(label = "why")
    val animated by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )
    // Bewegung reduziert: ruhiger, stehender Platzhalter
    val pulse = if (rememberReduceMotion()) 0.7f else animated
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.alpha(pulse)) {
        repeat(3) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(14.dp)
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(block))
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Box(Modifier.width(180.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(block))
                    Box(
                        Modifier.padding(top = 8.dp).width(230.dp).height(10.dp)
                            .clip(RoundedCornerShape(5.dp)).background(block)
                    )
                }
            }
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

/** Funding mit drei Nachkommastellen, z. B. «+0.080 %». */
private fun fundingText(value: Double): String = ActivityTexts.percent(value, 3)

@Composable
private fun withOpenInterest(text: String, change: Double?): String =
    if (change == null) text
    else text + " " + stringResource(R.string.why_open_interest_change, ActivityTexts.percent(change, 1))

private fun signedInt(value: Int): String = when {
    value > 0 -> "+$value"
    value < 0 -> "−${-value}"
    else -> "±0"
}

@Composable
private fun fearGreedLabel(value: Int): String = stringResource(
    when (ActivityAnalyzer.fearGreedLevel(value)) {
        FearGreedLevel.EXTREME_FEAR -> R.string.fng_extreme_fear
        FearGreedLevel.FEAR -> R.string.fng_fear
        FearGreedLevel.NEUTRAL -> R.string.fng_neutral
        FearGreedLevel.GREED -> R.string.fng_greed
        FearGreedLevel.EXTREME_GREED -> R.string.fng_extreme_greed
    }
)
