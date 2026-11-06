package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.logos.MissingLogoNotice
import com.cryptochecker.app.ui.components.LocalCoinLogoSource
import com.cryptochecker.app.ui.theme.Spacing

/** Test-Tag der Zeile (UI-Tests). */
const val MISSING_LOGOS_TAG = "missing_logos_line"

/**
 * Ruhige Zeile «2 Coins ohne Logo» an der Stelle von «Logos werden geladen …» ([WatchlistLogoHint]), wenn Coins der
 * Merkliste nach dem ersten Laden weder ein mitgeliefertes noch ein geladenes Logo haben ([MissingLogoNotice]: welche
 * Coins zählen, wann sichtbar). Nie, solange Logos laden; Schalter «Coin-Logos › In der App» aus: keine Quelle, keine
 * Zeile. Tipp auf den Text: Liste dieser Coins (Kürzel, Name falls bekannt) mit einer Zeile Erklärung; ✕ (48 dp,
 * «Hinweis schliessen») merkt genau diese Coins ([com.cryptochecker.app.ui.components.CoinLogoSource.dismissMissing]) —
 * die Zeile kommt erst mit einem weiteren Coin ohne Logo zurück. Kommen Logos, verschwindet sie von selbst. Wie iOS
 * `WatchlistMissingLogos`.
 */
@Composable
internal fun WatchlistMissingLogos(watches: List<WatchEntity>, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val source = LocalCoinLogoSource.current ?: return
    val loadingLine by source.loading.collectAsStateWithLifecycle()
    val revision by source.revision.collectAsStateWithLifecycle()
    val tradFiPairs by source.tradFiPairs.collectAsStateWithLifecycle()
    val dismissed by source.missingDismissed.collectAsStateWithLifecycle()
    val keys = remember(watches, tradFiPairs) {
        MissingLogoNotice.candidates(
            watches.map {
                val pair = CoinLogos.pairKey(it.exchangeKey, it.baseAsset, it.quoteAsset, it.contractType.name)
                MissingLogoNotice.WatchedCoin(it.exchangeKey, it.baseAsset, tradFi = pair in tradFiPairs)
            }
        )
    }
    // Coins ohne Logo; null = noch offen (erstes Laden nicht erledigt oder Hinweis «Logos werden geladen» steht)
    val missing by produceState<List<String>?>(null, keys, revision, loadingLine) {
        source.loadMissingDismissed()
        value = if (loadingLine || !source.firstLoadDone()) null else keys.filterNot { source.hasLogo(it) }
    }
    val current = missing
    val stored = dismissed
    val notice = if (current == null || stored == null) MissingLogoNotice.State.HIDDEN
    else MissingLogoNotice.state(current, stored, loading = loadingLine, logosEnabled = true)
    // Zuletzt gezeigte Coins: beim Ausblenden bleibt der Text stehen (kein «0 Coins»)
    val shown = remember { arrayOf(emptyList<String>()) }
    if (notice.visible && current != null) shown[0] = current
    var showList by rememberSaveable { mutableStateOf(false) }
    AnimatedVisibility(
        visible = notice.visible,
        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(FADE_MILLIS)) + expandVertically(),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(FADE_MILLIS)) + shrinkVertically(),
        modifier = modifier,
    ) {
        val count = shown[0].size
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag(MISSING_LOGOS_TAG)) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = TOUCH_MIN)
                    .clickable(role = Role.Button) { showList = true },
            ) {
                Text(
                    pluralStringResource(R.plurals.watchlist_missing_logos, count, count),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { source.dismissMissing(shown[0].toSet()) }, modifier = Modifier.size(TOUCH_MIN)) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.watchlist_missing_logos_dismiss),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
    if (showList) MissingLogosDialog(shown[0], onClose = { showList = false })
}

/** Liste der Coins ohne Logo (Kürzel, Name falls bekannt) und eine Zeile Erklärung; nur «Schliessen». */
@Composable
private fun MissingLogosDialog(keys: List<String>, onClose: () -> Unit) {
    val names = LocalCoinLogoSource.current?.names?.collectAsStateWithLifecycle()?.value.orEmpty()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(pluralStringResource(R.plurals.watchlist_missing_logos, keys.size, keys.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.watchlist_missing_logos_explain), style = MaterialTheme.typography.bodyMedium)
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.heightIn(max = LIST_MAX).verticalScroll(rememberScrollState()),
                ) {
                    for (key in keys) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text(
                                MissingLogoNotice.displaySymbol(key),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            names[key]?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}

private const val FADE_MILLIS = 300

/** Mindest-Tippfläche (Text und ✕). */
private val TOUCH_MIN = 48.dp

/** Höchsthöhe der Liste im Dialog (länger: scrollt). */
private val LIST_MAX = 240.dp
