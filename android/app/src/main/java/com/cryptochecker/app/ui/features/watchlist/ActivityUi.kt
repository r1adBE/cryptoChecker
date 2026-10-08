@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.activity.BtcLink
import com.cryptochecker.app.domain.activity.FearGreedLevel
import com.cryptochecker.app.domain.activity.Reason
import com.cryptochecker.app.domain.activity.ReasonKind
import com.cryptochecker.app.domain.activity.WhyBrief
import com.cryptochecker.app.domain.activity.WhyConfidence
import com.cryptochecker.app.domain.activity.WhyConfidenceLevel
import com.cryptochecker.app.domain.activity.WhyConfidenceRules
import com.cryptochecker.app.domain.activity.WhyExtra
import com.cryptochecker.app.domain.activity.WhyFactor
import com.cryptochecker.app.domain.activity.WhyFactorDirection
import com.cryptochecker.app.domain.activity.WhyFactorKind
import com.cryptochecker.app.domain.activity.WhyFactorNote
import com.cryptochecker.app.domain.activity.WhyFactors
import com.cryptochecker.app.domain.activity.WhyHeadline
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.domain.activity.WhySummary
import com.cryptochecker.app.notification.ActivityTexts
import com.cryptochecker.app.ui.components.FactorRow
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.body
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.ui.theme.title
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import kotlin.math.abs
import kotlin.math.roundToInt


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
            tint = AppColors.warning,
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
internal fun ActivityCard(
    hot: List<WatchEntity>,
    onOpen: (WatchEntity) -> Unit,
    /** Höchstens so viele Coins (Empfindlichkeit «Weniger»: die 3 stärksten); null = alle. */
    limit: Int? = null,
    /** «Anpassen»: öffnet die Einstellung «Empfindlichkeit»; null = ohne Link. */
    onAdjust: (() -> Unit)? = null,
) {
    if (hot.isEmpty()) return
    // Gleicher Coin an mehreren Börsen: einmal zeigen; [hot] ist nach Stärke sortiert
    val coins = remember(hot, limit) {
        hot.distinctBy { it.baseAsset.uppercase() }.let { if (limit != null) it.take(limit) else it }
    }
    val shown = coins.take(MAX_CARD_COINS)
    val more = coins.size - shown.size
    val amber = AppColors.warning

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, amber.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, top = 12.dp, bottom = 8.dp)) {
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
                    modifier = Modifier.padding(start = Spacing.sm).weight(1f)
                )
                // Kleiner Link zur Empfindlichkeit (zu viele/zu wenige Coins markiert?)
                if (onAdjust != null) {
                    val adjustA11y = stringResource(R.string.activity_adjust_a11y)
                    TextButton(
                        onClick = onAdjust,
                        modifier = Modifier.semantics { contentDescription = adjustA11y }
                    ) {
                        Text(
                            text = stringResource(R.string.activity_adjust),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
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

/** Platzhalter → Inhalt und «Details»: Dauer der Überblendung bzw. Grössenänderung. */
private const val SWAP_MILLIS = 220

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

/**
 * «Wahrscheinliche Gründe · Sicherheit: mittel», darunter die Faktoren (stärkster oben,
 * neutrale abgeblendet, höchstens fünf) und «Kurz gesagt: …» als ein Satz — nie als
 * sichere Ursache formuliert. Zeilen wie [com.cryptochecker.app.ui.features.info.MarketRow].
 */
@Composable
private fun WhyFactorCard(report: WhyReport, factors: List<WhyFactor>, base: String) {
    val confidence = WhyConfidenceRules.evaluate(report.reasons, report.hasMarketData)
    val brief = briefSentence(WhySummary.brief(report.reasons))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = Spacing.md, vertical = Spacing.md)
    ) {
        Column(modifier = Modifier.semantics(mergeDescendants = true) { }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.why_summary_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                confidence?.let {
                    Text(
                        stringResource(R.string.why_confidence, confidenceLabel(it.level)),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = Spacing.sm)
                    )
                }
            }
            confidence?.let {
                Text(
                    confidenceExplanation(it),
                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        factors.forEach { WhyFactorLine(it) }
        if (brief != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val label = stringResource(R.string.why_brief_label)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(label) }
                    append(" ")
                    append(brief)
                },
                style = MaterialTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.md)
            )
        }
        // Gleichlauf mit Bitcoin (Stunden-Renditen, Kerzen von «Warum?»): eng bzw. unabhängig
        report.btcLink?.let { link ->
            Text(
                stringResource(
                    when (link) {
                        BtcLink.TIGHT -> R.string.why_btc_tight
                        BtcLink.INDEPENDENT -> R.string.why_btc_independent
                    },
                    BidiText.isolate(base.trim().uppercase()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm)
            )
        }
    }
}

/** «Kurz gesagt»: genau ein Satz — die Einordnung, sonst der erste Zusatz; null = keiner. */
@Composable
private fun briefSentence(brief: WhyBrief): String? {
    brief.headline?.let { headline ->
        return stringResource(
            when (headline) {
                WhyHeadline.CALM -> R.string.why_summary_calm
                WhyHeadline.COIN_VOLUME -> R.string.why_summary_coin_volume
                WhyHeadline.COIN -> R.string.why_summary_coin
                WhyHeadline.AGAINST -> R.string.why_summary_against
                WhyHeadline.MARKET_VOLUME -> R.string.why_summary_market_volume
                WhyHeadline.MARKET -> R.string.why_summary_market
            }
        )
    }
    return brief.extras.firstOrNull()?.let { extra ->
        stringResource(
            when (extra) {
                WhyExtra.THIN -> R.string.why_summary_thin
                WhyExtra.LONGS -> R.string.why_summary_longs
                WhyExtra.SHORTS -> R.string.why_summary_shorts
            }
        )
    }
}

/** Texte einer Faktorzeile: Titel, Wert rechts, Nebenzeile und der gesprochene Satz. */
private data class FactorTexts(val title: String, val value: String, val note: String, val spoken: String)

/**
 * Eine Faktorzeile: Pfeil im Kreis, Titel mit kurzer Nebenzeile, Wert rechts; neutrale
 * abgeblendet. Screenreader: ein Satz («Volumen: höher als üblich, 3,4-mal so viel wie üblich»).
 */
@Composable
private fun WhyFactorLine(factor: WhyFactor) {
    val texts = factorTexts(factor)
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.sm)
                .alpha(if (factor.neutral) NEUTRAL_ALPHA else 1f)
                .clearAndSetSemantics { contentDescription = texts.spoken }
        ) {
            FactorArrow(factor.direction, factor.neutral)
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(
                    texts.title,
                    style = MaterialTheme.typography.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    texts.note,
                    style = MaterialTheme.typography.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (texts.value.isNotEmpty()) {
                Text(
                    texts.value,
                    style = MaterialTheme.typography.title.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.padding(start = Spacing.md)
                )
            }
        }
    }
}

/** Neutrale Faktoren abgeblendet. */
private const val NEUTRAL_ALPHA = 0.6f

/** Kreis mit ↑ / ↓ (oder – ohne Richtung); Akzentfarbe, neutral grau — nie Kursfarben. */
@Composable
private fun FactorArrow(direction: WhyFactorDirection, neutral: Boolean) {
    val color = if (neutral) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(FACTOR_ICON).clip(CircleShape).background(color.copy(alpha = 0.12f))
    ) {
        Text(
            when (direction) {
                WhyFactorDirection.UP -> "↑"
                WhyFactorDirection.DOWN -> "↓"
                WhyFactorDirection.NONE -> "–"
            },
            style = MaterialTheme.typography.title,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private val FACTOR_ICON = 32.dp

@Composable
private fun factorTexts(f: WhyFactor): FactorTexts {
    val context = LocalContext.current
    fun pct(v: Double, decimals: Int = 1) = ActivityTexts.percent(v, decimals)
    val title: String
    var value = ""
    var spokenValue = ""
    val note: String
    when (f.kind) {
        WhyFactorKind.VOLUME -> {
            val number = ActivityTexts.factor(f.value)
            title = stringResource(R.string.factor_volume)
            value = "$number×"
            spokenValue = stringResource(R.string.factor_spoken_volume, number)
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.VOLUME_HIGHER -> R.string.why_fn_volume_higher
                    WhyFactorNote.VOLUME_LOWER -> R.string.why_fn_volume_lower
                    else -> R.string.why_fn_volume_usual
                }
            )
        }
        WhyFactorKind.MARKET -> {
            title = stringResource(R.string.why_factor_market)
            // Bei Bitcoin selbst steht BTC oben: rechts ETH zum Vergleich
            val leader = f.note == WhyFactorNote.MARKET_LEADER_MOVES || f.note == WhyFactorNote.MARKET_LEADER_CALM
            val (symbol, change) = if (leader) "ETH" to f.secondary else "BTC" to f.value
            if (change != null) {
                value = "$symbol " + pct(change)
                spokenValue = "$symbol, " + A11yText.change(context, change, decimals = 1)
            }
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.MARKET_PULLS -> R.string.why_fn_market_pulls
                    WhyFactorNote.MARKET_COIN_LAGS -> R.string.why_fn_market_lags
                    WhyFactorNote.MARKET_COIN_ALONE -> R.string.why_fn_market_alone
                    WhyFactorNote.MARKET_AGAINST -> R.string.why_fn_market_against
                    WhyFactorNote.MARKET_LEADER_MOVES -> R.string.why_fn_leader_moves
                    WhyFactorNote.MARKET_LEADER_CALM -> R.string.why_fn_leader_calm
                    else -> R.string.why_fn_market_calm
                }
            )
        }
        WhyFactorKind.VOLATILITY -> {
            val number = ActivityTexts.factor(f.value)
            title = stringResource(R.string.factor_volatility)
            value = "$number×"
            spokenValue = stringResource(R.string.factor_spoken_volatility, number)
            note = stringResource(
                if (f.note == WhyFactorNote.VOLATILITY_STRONGER) R.string.why_fn_volatility_stronger
                else R.string.why_fn_volatility_usual
            )
        }
        WhyFactorKind.OPEN_INTEREST -> {
            title = stringResource(R.string.why_factor_futures)
            value = pct(f.value)
            spokenValue = A11yText.change(context, f.value, decimals = 1)
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.OI_UP -> R.string.why_fn_oi_up
                    WhyFactorNote.OI_DOWN -> R.string.why_fn_oi_down
                    else -> R.string.why_fn_oi_flat
                }
            )
        }
        WhyFactorKind.NEAR_HIGH -> {
            title = stringResource(R.string.why_factor_near_high)
            if (f.note == WhyFactorNote.HIGH_BELOW) {
                value = pct(-f.value)
                spokenValue = A11yText.change(context, -f.value, decimals = 1)
            }
            note = stringResource(
                if (f.note == WhyFactorNote.HIGH_AT) R.string.why_fn_high_at else R.string.why_fn_high_below
            )
        }
        WhyFactorKind.FUNDING -> {
            title = stringResource(R.string.why_factor_funding)
            value = fundingText(f.value)
            spokenValue = value
            note = stringResource(
                when (f.note) {
                    WhyFactorNote.FUNDING_LONGS -> R.string.why_fn_funding_longs
                    WhyFactorNote.FUNDING_SHORTS -> R.string.why_fn_funding_shorts
                    else -> R.string.why_fn_funding_neutral
                }
            )
        }
        WhyFactorKind.SENTIMENT -> {
            val points = f.value.roundToInt()
            title = stringResource(R.string.factor_fear_greed)
            value = LocaleNumbers.integer(points)
            spokenValue = value
            note = fearGreedLabel(points)
        }
    }
    val spoken = "$title: $note" + (if (spokenValue.isNotBlank()) ", $spokenValue" else "") + "."
    return FactorTexts(title, value, note, spoken)
}

@Composable
private fun confidenceLabel(level: WhyConfidenceLevel): String = stringResource(
    when (level) {
        WhyConfidenceLevel.HIGH -> R.string.why_confidence_high
        WhyConfidenceLevel.MEDIUM -> R.string.why_confidence_medium
        WhyConfidenceLevel.LOW -> R.string.why_confidence_low
    }
)

/** «2 von 4 Hinweisen deuten darauf hin», bei Lücken mit «· Daten unvollständig». */
@Composable
private fun confidenceExplanation(c: WhyConfidence): String {
    val hints = pluralStringResource(R.plurals.why_confidence_hints, c.agreeing, c.agreeing, c.total)
    return if (c.partialData) hints + " · " + stringResource(R.string.why_confidence_partial) else hints
}

/**
 * Platzhalter in der Form von [WhyFactorCard] und dem Knopf «Details anzeigen»: Kopf,
 * Zeile zur Sicherheit, fünf Faktorzeilen (Kreis, Titel, Nebenzeile, Wert) und zwei Zeilen
 * «Kurz gesagt» — gleiche Stile, Abstände und Höhen, beim Eintreffen springt nichts.
 */
@Composable
private fun FactorCardSkeleton() {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    SkeletonPulse(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = Spacing.md, vertical = Spacing.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonLine(MaterialTheme.typography.labelLarge, Modifier.width(140.dp))
                Spacer(Modifier.weight(1f))
                SkeletonLine(MaterialTheme.typography.labelMedium, Modifier.width(96.dp))
            }
            SkeletonLine(
                MaterialTheme.typography.labelSmall,
                Modifier.padding(top = Spacing.xs).width(170.dp)
            )
            Spacer(Modifier.height(Spacing.sm))
            repeat(WhyFactors.MAX_FACTORS) { index ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm)
                ) {
                    Box(Modifier.size(FACTOR_ICON).clip(CircleShape).background(block))
                    Column(modifier = Modifier.weight(1f).padding(start = Spacing.md)) {
                        SkeletonLine(MaterialTheme.typography.body, Modifier.width(90.dp + 12.dp * (index % 3)))
                        SkeletonLine(MaterialTheme.typography.label, Modifier.width(130.dp - 10.dp * (index % 2)))
                    }
                    SkeletonLine(
                        MaterialTheme.typography.title,
                        Modifier.padding(start = Spacing.md).width(52.dp)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SkeletonLine(MaterialTheme.typography.body, Modifier.padding(top = Spacing.md).fillMaxWidth())
            SkeletonLine(MaterialTheme.typography.body, Modifier.fillMaxWidth(0.6f))
        }
        // Wie der Knopf «Details anzeigen» (TextButton, 40 dp) mit dem Abstand davor
        Box(
            Modifier.padding(top = Spacing.sm).height(40.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            SkeletonLine(MaterialTheme.typography.labelLarge, Modifier.padding(start = 12.dp).width(110.dp))
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
    value > 0 -> "+" + LocaleNumbers.integer(value)
    value < 0 -> "−" + LocaleNumbers.integer(-value)
    else -> "±" + LocaleNumbers.integer(0)
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
