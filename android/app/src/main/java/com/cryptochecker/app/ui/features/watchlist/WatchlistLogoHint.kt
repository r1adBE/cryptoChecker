package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.components.LocalCoinLogoSource
import com.cryptochecker.app.domain.util.PercentText

/** Test-Tag der Zeile (UI-Tests). */
const val LOGO_LOADING_TAG = "logo_loading_line"

/**
 * Eine ruhige Zeile unter dem Status, solange nach der Installation bzw. nach dem Leeren des Zwischenspeichers Logos
 * laden: «Logos werden geladen …» ([CoinLogoSource.loading][com.cryptochecker.app.ui.components.CoinLogoSource.loading],
 * [com.cryptochecker.app.domain.logos.LogoLoadingHint]), sobald die Gesamtzahl bekannt ist mit Fortschritt «Logos werden
 * geladen · 40 %» (Prozent in der Schreibweise der Sprache, [PercentText]; gedrosselt). Schrift wie der Status
 * («Börsenkurse · gerade eben»), in der ruhigen Nebenfarbe; verschwindet von selbst, wenn der Durchgang endet. Schalter
 * «Coin-Logos › In der App» aus: keine Quelle, keine Zeile. Screenreader: einmal höflich angesagt (ohne Prozent, nicht
 * bei jedem neuen Wert). Wie iOS `WatchlistLogoHint`.
 */
@Composable
internal fun WatchlistLogoHint(reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val source = LocalCoinLogoSource.current ?: return
    val loading by source.loading.collectAsStateWithLifecycle()
    val percent by source.loadingPercent.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = loading,
        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(HINT_FADE_MILLIS)) + expandVertically(),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(HINT_FADE_MILLIS)) + shrinkVertically(),
        modifier = modifier,
    ) {
        AnnounceOnce(stringResource(R.string.watchlist_logos_loading))
        val text = percent?.let { stringResource(R.string.watchlist_logos_loading_percent, PercentText.unsigned(it.toDouble(), 0)) }
            ?: stringResource(R.string.watchlist_logos_loading)
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().testTag(LOGO_LOADING_TAG),
        )
    }
}

/** Sagt [text] einmal an, solange die Zeile steht (nicht erneut nach einem Tab-Wechsel). */
@Composable
private fun AnnounceOnce(text: String) {
    val view = LocalView.current
    var announced by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (announced) return@LaunchedEffect
        // Compose kennt keine eigene Ansage; die Zeile ist ein ruhiger Hinweis (höflich, unterbricht nichts)
        @Suppress("DEPRECATION")
        view.announceForAccessibility(text)
        announced = true
    }
}

private const val HINT_FADE_MILLIS = 300
