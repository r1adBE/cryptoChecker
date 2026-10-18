package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.ui.components.SkeletonBlock
import com.cryptochecker.app.ui.theme.MarketScaleColors
import com.cryptochecker.app.ui.theme.MarketScalePalette
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.LocaleNumbers

// ───────────────────────── Fear & Greed ─────────────────────────

private fun fearGreedLevel(value: Int): Int = MarketScalePalette.level(value)

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
 * Fear & Greed als erste Zeile unter «Einordnung» im Stil des Puls-Widgets (Entscheid 17. Oktober 2026): die Zahl
 * gross mit dem Wort der Stufe daneben, darunter eine neutrale Spur 0–100 mit EINEM Punkt in der Farbe der Stufe —
 * kein Regenbogen-Verlauf. Tippen zeigt Gestern / Woche / Monat und die Quelle.
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
    MarketRow(
        title = stringResource(R.string.insights_fng_title),
        first = first,
        loading = state is LoadState.Loading,
        failure = if (state is LoadState.Failed) stringResource(R.string.pulse_unavailable) else null,
        onRetry = onRetry,
        stamp = stamp,
        expanded = expanded,
        onToggle = if (fg != null) ({ expanded = !expanded }) else null,
        info = MarketInfoTopic.FEAR_GREED,
        // Spur bleibt auch beim Laden und bei Fehler stehen (grau) — darunter springt nichts
        below = { FearGreedGauge(fg?.value) },
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

/**
 * Zahl gross und Wort der Stufe, darunter die neutrale Spur mit dem Stufenpunkt. Punktfarbe wie im Puls-Widget
 * aus den Kursfarben ([MarketScaleColors]: Angst = fallend, Gier = steigend, je Stufe ≥ 3:1 als Grafik), passend
 * zu den Kurspfeilen; die Zahl und das Wort stehen in der Textfarbe — die Farbe trägt nie allein. [value] null = grauer Platzhalter in derselben Höhe.
 */
@Composable
private fun FearGreedGauge(value: Int?) {
    if (value == null) {
        Box(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            SkeletonBlock(Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp))
        }
        return
    }
    val label = fearGreedLabel(value)
    val dot = MarketScaleColors.steps[fearGreedLevel(value)]
    val gaugeDescription = stringResource(
        R.string.a11y_gauge,
        stringResource(R.string.fng_extreme_fear),
        stringResource(R.string.fng_extreme_greed),
        value
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Ein Satz: «72, Gier, Extreme Angst bis Extreme Gier: 72»
            .clearAndSetSemantics { contentDescription = "${LocaleNumbers.integer(value)}, $label, $gaugeDescription" }
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = LocaleNumbers.integer(value),
                style = MaterialTheme.typography.headlineMedium.tabularNumbers(),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = Spacing.sm, bottom = 4.dp)
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            // Punkt mit Rand in der Fläche, damit er sich von der Spur abhebt; offset spiegelt sich bei RTL selbst
            val marker = 14.dp
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (maxWidth - marker) * (value / 100f))
                    .size(marker)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
        }
    }
}
