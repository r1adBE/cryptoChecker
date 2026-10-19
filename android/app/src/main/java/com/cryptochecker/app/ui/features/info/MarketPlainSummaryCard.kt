package com.cryptochecker.app.ui.features.info

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.CycleSource
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.MarketPlainSummary
import com.cryptochecker.app.domain.market.MarketTotals
import com.cryptochecker.app.domain.market.PlainDirection
import com.cryptochecker.app.domain.market.PlainDirectionKind
import com.cryptochecker.app.domain.market.PlainMood
import com.cryptochecker.app.domain.market.PlainMoodKind
import com.cryptochecker.app.domain.market.PlainStandout
import com.cryptochecker.app.domain.market.PlainStandoutKind
import com.cryptochecker.app.domain.market.PlainSummary
import com.cryptochecker.app.domain.market.PlainSummaryInput
import com.cryptochecker.app.domain.market.PulseReport
import com.cryptochecker.app.domain.market.UnusualReport
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import kotlinx.coroutines.delay

/** So oft wird neu geprüft, ob ein Satz inzwischen auf veralteten Daten stünde. */
private const val PLAIN_TICK_MILLIS = 30_000L

/**
 * «Kurz gesagt»: bis zu drei Sätze in Alltagssprache ([MarketPlainSummary]) als Körper der Heldenkarte
 * ([CryptoPulseCard], direkt unter der Schlagzeile). Gibt es erst, wenn Puls, «Heute auffällig» und
 * Fear & Greed ihren ersten Stand haben (geladen, gescheitert oder aus dem Zwischenspeicher) — so kommen
 * nicht Satz für Satz neue Zeilen dazu. null = noch nicht bereit oder kein einziger gültiger Satz (dann
 * entfällt der Block ganz: kein Platzhalter, keine Vermutung). Die Uhr läuft mit: ein Satz verschwindet,
 * sobald seine Daten veraltet sind. Wie iOS `MarketPlainSummaryBlock.summary`.
 */
@Composable
internal fun rememberPlainSummary(
    pulse: LoadState<PulseReport>,
    marketTotals: LoadState<MarketTotals>,
    fearGreed: LoadState<FearGreed>,
    unusual: LoadState<UnusualReport>,
    stamps: Map<CycleSource, DataStamp>,
): PlainSummary? {
    val ready = pulse !is LoadState.Loading && unusual !is LoadState.Loading && fearGreed !is LoadState.Loading
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(PLAIN_TICK_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    if (!ready) return null
    val fg = (fearGreed as? LoadState.Loaded)?.value
    val summary = MarketPlainSummary.evaluate(
        PlainSummaryInput(
            now = now,
            pulseMarketChange24h = (pulse as? LoadState.Loaded)?.value?.marketCap24h,
            pulseStamp = stamps[CycleSource.PULSE],
            globalMarketChange24h = (marketTotals as? LoadState.Loaded)?.value?.changePercent24h,
            globalStamp = stamps[CycleSource.GLOBAL],
            fearGreed = fg?.value,
            fearGreedTime = fg?.time,
            fearGreedStamp = stamps[CycleSource.FEAR_GREED],
            unusual = (unusual as? LoadState.Loaded)?.value,
            unusualStamp = stamps[CycleSource.UNUSUAL],
        )
    )
    return summary.takeUnless { it.isEmpty }
}

/** Richtung (Marktkapitalisierung) und «Heute auffällig» (Umsatz-Anteil) nutzen CoinGecko-Daten. */
internal val PlainSummary.usesCoinGecko: Boolean get() = direction != null || standout != null

/**
 * Die Sätze von «Kurz gesagt» in der Heldenkarte: kleine Unterüberschrift (für den Screenreader eine
 * Überschrift), darunter je Satz eine Zeile mit Symbol. Kommt der Block erst nach der Schlagzeile
 * (erster Start ohne Zwischenspeicher), schiebt er die Belege weich nach unten statt mit einem Sprung;
 * ohne Animation bei reduzierter Bewegung. Hinweis und Quelle stehen am Fuss der Karte.
 */
@Composable
internal fun PlainSummaryBlock(summary: PlainSummary?) {
    val animate = !rememberReduceMotion()
    // Beim Ausblenden den letzten Stand weiter zeigen, bis die Animation fertig ist
    val last = remember { mutableStateOf(summary) }
    if (summary != null) last.value = summary
    AnimatedVisibility(
        visible = summary != null,
        enter = if (animate) expandVertically() + fadeIn() else EnterTransition.None,
        exit = if (animate) shrinkVertically() + fadeOut() else ExitTransition.None,
    ) {
        last.value?.let { PlainSummaryContent(it) }
    }
}

@Composable
private fun PlainSummaryContent(summary: PlainSummary) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)) {
        Text(
            stringResource(R.string.market_plain_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() }
        )
        summary.direction?.let { PlainLine(directionIcon(it.kind), directionText(it)) }
        summary.mood?.let { PlainLine(R.drawable.ic_gauge, moodText(it)) }
        summary.standout?.let { PlainLine(R.drawable.ic_flag, standoutText(it)) }
    }
}

/**
 * Eine Zeile: Symbol (nur Schmuck, Nebenfarbe — die Richtung steht im Satz, nie nur in der
 * Farbe) und der Satz in normaler Lesegrösse. Screenreader liest nur den Satz.
 */
@Composable
private fun PlainLine(@DrawableRes icon: Int, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // Mittig zur ersten Textzeile (bodyLarge, 24 sp Zeilenhöhe)
            modifier = Modifier.padding(top = 2.dp).size(20.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@DrawableRes
private fun directionIcon(kind: PlainDirectionKind): Int = when (kind) {
    PlainDirectionKind.FLAT -> R.drawable.ic_trending_flat
    PlainDirectionKind.UP_SLIGHT, PlainDirectionKind.UP_CLEAR -> R.drawable.ic_trending_up
    PlainDirectionKind.DOWN_SLIGHT, PlainDirectionKind.DOWN_CLEAR -> R.drawable.ic_trending_down
}

@Composable
private fun directionText(direction: PlainDirection): String = stringResource(
    when (direction.kind) {
        PlainDirectionKind.FLAT -> R.string.market_plain_direction_flat
        PlainDirectionKind.UP_SLIGHT -> R.string.market_plain_direction_up_slight
        PlainDirectionKind.UP_CLEAR -> R.string.market_plain_direction_up_clear
        PlainDirectionKind.DOWN_SLIGHT -> R.string.market_plain_direction_down_slight
        PlainDirectionKind.DOWN_CLEAR -> R.string.market_plain_direction_down_clear
    },
    PriceFormat.signedPercent(direction.changePercent, 1)
)

@Composable
private fun moodText(mood: PlainMood): String = stringResource(
    when (mood.kind) {
        PlainMoodKind.EXTREME_FEAR -> R.string.market_plain_mood_extreme_fear
        PlainMoodKind.FEAR -> R.string.market_plain_mood_fear
        PlainMoodKind.NEUTRAL -> R.string.market_plain_mood_neutral
        PlainMoodKind.GREED -> R.string.market_plain_mood_greed
        PlainMoodKind.EXTREME_GREED -> R.string.market_plain_mood_extreme_greed
    },
    mood.value
)

@Composable
private fun standoutText(standout: PlainStandout): String {
    val coin = standout.coin.orEmpty()
    fun percent(value: Double?) = PriceFormat.signedPercent(value ?: 0.0, 1)
    return when (standout.kind) {
        PlainStandoutKind.ABOVE_BTC ->
            stringResource(R.string.market_plain_standout_above_btc, coin, percent(standout.change24h), percent(standout.btc24h))
        PlainStandoutKind.BELOW_BTC ->
            stringResource(R.string.market_plain_standout_below_btc, coin, percent(standout.change24h), percent(standout.btc24h))
        PlainStandoutKind.AGAINST_BTC ->
            stringResource(R.string.market_plain_standout_against_btc, coin, percent(standout.change24h), percent(standout.btc24h))
        PlainStandoutKind.VOLUME ->
            stringResource(R.string.market_plain_standout_volume, coin, LocaleNumbers.decimal(standout.volumeRatio ?: 0.0, 1))
        PlainStandoutKind.FUTURES_UP -> stringResource(R.string.market_plain_standout_futures_up, coin)
        PlainStandoutKind.FUTURES_DOWN -> stringResource(R.string.market_plain_standout_futures_down, coin)
        PlainStandoutKind.QUIET -> stringResource(R.string.market_plain_standout_quiet)
    }
}
