package com.cryptochecker.app.ui.features.info

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.sectionTitleMarker
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.Spacing

/** Einheitliche Karte für die Bereiche des Markt-Tabs. */
@Composable
internal fun InsightCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = title,
                style = SectionTitle.style,
                color = SectionTitle.color,
                modifier = Modifier.padding(bottom = 12.dp).semantics { heading() }.sectionTitleMarker()
            )
            content()
        }
    }
}

@Composable
internal fun FailedRow(onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.something_went_wrong),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/**
 * Eine kompakte Zeile «Marktdaten gerade nicht verfügbar» mit «Erneut» — die Karte
 * bleibt stehen, damit darunter nichts springt (Crypto Pulse, Krypto-Markt).
 */
@Composable
internal fun UnavailableRow(onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.pulse_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/** Restliche Grössenwechsel (Platzhalter → Inhalt) weich, 200 ms; aus bei reduzierter Bewegung. */
internal fun Modifier.sizeAnimation(enabled: Boolean): Modifier =
    if (enabled) animateContentSize(animationSpec = tween(200)) else this

/** Art des Zustands (lädt, geladen, gescheitert) — nur ein Wechsel der Art wird überblendet. */
internal fun loadKey(state: LoadState<*>): Int = when (state) {
    LoadState.Loading -> 0
    is LoadState.Loaded<*> -> 1
    LoadState.Failed -> 2
}

/**
 * Inhalt einer schon sichtbaren Karte, der vom Ladezustand abhängt: Wechselt die Art
 * ([key], z. B. Platzhalter → Inhalt, weil Daten spät kommen), wird überblendet und nur
 * diese Karte ändert ihre Höhe ([MarketReveal.SWAP_MILLIS]); neue Werte derselben Art
 * (Ziehen nach unten, Hintergrund) ersetzen still an Ort und Stelle.
 * Bei reduzierter Bewegung ohne Animation.
 */
@Composable
internal fun <S> CardSwap(state: S, key: (S) -> Any, content: @Composable ColumnScope.(S) -> Unit) {
    if (rememberReduceMotion()) {
        Column(modifier = Modifier.fillMaxWidth()) { content(state) }
    } else {
        AnimatedContent(
            targetState = state,
            contentKey = key,
            transitionSpec = {
                (fadeIn(tween(MarketReveal.SWAP_MILLIS)) togetherWith fadeOut(tween(MarketReveal.SWAP_MILLIS)))
                    .using(SizeTransform(clip = true) { _, _ -> tween(MarketReveal.SWAP_MILLIS) })
            },
            label = "card_swap",
            modifier = Modifier.fillMaxWidth(),
        ) { shown ->
            Column(modifier = Modifier.fillMaxWidth()) { content(shown) }
        }
    }
}

@Composable
internal fun SourceText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm)
    )
}
