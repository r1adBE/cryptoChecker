package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.theme.LocalAccentColor
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.LocalPriceColorScheme
import com.cryptochecker.app.ui.theme.LocalPriceColorsInverted
import com.cryptochecker.app.ui.theme.MarketScalePalette
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.LocaleNumbers

// ───────────────────────── Fear & Greed ─────────────────────────

private fun fearGreedLevel(value: Int): Int = MarketScalePalette.level(value)

/** J-15: Stufenfarben aus den aktiven Kursfarben (Farbsehschwäche, hoher Kontrast, Tausch). */
@Composable
private fun fearGreedColors(): List<Color> = MarketScalePalette.steps(
    LocalPriceColorScheme.current,
    LocalDarkTheme.current,
    LocalHighContrast.current,
    LocalPriceColorsInverted.current,
    LocalAccentColor.current,
).map { Color(it) }

@Composable
internal fun fearGreedLabel(value: Int): String = stringResource(
    when (fearGreedLevel(value)) {
        0 -> R.string.fng_extreme_fear
        1 -> R.string.fng_fear
        2 -> R.string.fng_neutral
        3 -> R.string.fng_greed
        else -> R.string.fng_extreme_greed
    }
)

/**
 * Fear & Greed als erste Zeile unter «Einordnung»: Wert rechts, Stufe und Vortag darunter,
 * die Skala 0–100 in voller Breite direkt unter der Zeile. Tippen zeigt Verlauf und Quelle.
 */
@Composable
internal fun FearGreedRow(
    state: LoadState<FearGreed>,
    onRetry: () -> Unit,
    first: Boolean = true,
    /** Herkunft und Stand (alternative.me) für die Nebenzeile. */
    stamp: DataStamp? = null,
) {
    val fg = (state as? LoadState.Loaded)?.value
    var expanded by rememberSaveable { mutableStateOf(false) }
    val label = fg?.let { fearGreedLabel(it.value) }.orEmpty()
    MarketRow(
        title = stringResource(R.string.insights_fng_title),
        secondary = fg?.yesterday?.let { stringResource(R.string.market_row_fng_yesterday, label, LocaleNumbers.integer(it)) } ?: label,
        value = fg?.let { LocaleNumbers.integer(it.value) },
        first = first,
        loading = state is LoadState.Loading,
        failure = if (state is LoadState.Failed) stringResource(R.string.pulse_unavailable) else null,
        onRetry = onRetry,
        stamp = stamp,
        expanded = expanded,
        onToggle = if (fg != null) ({ expanded = !expanded }) else null,
        info = MarketInfoTopic.FEAR_GREED,
        // J-15: Zahl in der Textfarbe (Stufenfarben erreichten als Schrift kein AA); die Stufe zeigen
        // Skala und Markierung, das Wort steht daneben
        valueColor = fg?.let { MaterialTheme.colorScheme.onSurface },
        // Skala bleibt auch beim Laden und bei Fehler stehen (grau) — darunter springt nichts
        below = { FearGreedScale(fg?.value, fearGreedColors()) },
    ) {
        if (fg != null) {
            Text(
                text = stringResource(
                    R.string.insights_fng_history,
                    fg.yesterday?.let { LocaleNumbers.integer(it) } ?: "—",
                    fg.weekAgo?.let { LocaleNumbers.integer(it) } ?: "—",
                    fg.monthAgo?.let { LocaleNumbers.integer(it) } ?: "—"
                ),
                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SourceText(stringResource(R.string.insights_source_fng))
        }
    }
}

/** Skala 0–100 mit Markierung; [value] null = grauer Platzhalter in derselben Höhe. */
@Composable
private fun FearGreedScale(value: Int?, colors: List<Color>) {
    if (value == null) {
        Box(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            SkeletonBlock(Modifier.align(Alignment.Center).fillMaxWidth().height(8.dp))
        }
        return
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val gaugeDescription = stringResource(
        R.string.a11y_gauge,
        stringResource(R.string.fng_extreme_fear),
        stringResource(R.string.fng_extreme_greed),
        value
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .semantics { contentDescription = gaugeDescription }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                // Skala folgt der Leserichtung: Verlauf (absolut gezeichnet) bei RTL
                // umgedreht, die Markierung (offset) spiegelt sich selbst
                .background(Brush.horizontalGradient(if (rtl) colors.reversed() else colors))
        )
        val marker = 18.dp
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - marker) * (value / 100f))
                .size(marker)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface)
                .padding(3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
        )
    }
}
