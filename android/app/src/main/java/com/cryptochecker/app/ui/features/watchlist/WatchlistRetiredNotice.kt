package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.RetiredNoticeStore
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.RetiredWatch
import com.cryptochecker.marketdata.config.ExchangeRetirement

/** Test-Tag der Zeile (UI-Tests). */
const val RETIRED_NOTICE_TAG = "retired_exchange_line"

/**
 * Einmaliger Hinweis über der Merkliste, solange Paare einer stillgelegten Börse darin stehen
 * ([ExchangeRetirement.noticeExchanges]): «Bitpanda liefert seit 1. Februar 2027 keine öffentlichen Kurse mehr. Wähle für
 * diese Coins eine andere Börse.» Davor, ab der Ankündigung ([ExchangeRetirement.advanceNoticeExchanges], 1. Januar
 * 2027): «Bitpanda liefert ab 1. Februar 2027 keine öffentlichen Kurse mehr …». Je mit ✕ (48 dp, «Hinweis schliessen»)
 * für diese Börse geschlossen — getrennt gespeichert ([RetiredNoticeStore]), an derselben Stelle wie «2 Coins ohne Logo»
 * ([WatchlistMissingLogos]). Wie iOS `WatchlistRetiredNotice`.
 */
@Composable
internal fun WatchlistRetiredNotice(watches: List<WatchEntity>, now: Long, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val store = LocalRetiredNoticeStore.current ?: return
    val dismissed by produceState<Set<String>?>(null, store) {
        store.load()
        store.dismissed.collect { value = it }
    }
    val closed = dismissed
    val notice = remember(watches, closed, now / NOTICE_TICK_MILLIS) {
        if (closed == null) return@remember null
        // Entschieden über den Schlüssel der Börse (nicht den gespeicherten Namen), gezeigt der Name aus dem Katalog
        val keys = watches.map { it.exchangeKey }
        ExchangeRetirement.noticeExchanges(keys, closed).firstOrNull()?.let { Notice(it, advance = false) }
            ?: ExchangeRetirement.advanceNoticeExchanges(keys, closed).firstOrNull()?.let { Notice(it, advance = true) }
    }
    // Beim Ausblenden den letzten Text stehen lassen (die Animation zeigt ihn noch)
    val shown = remember { arrayOf(Notice("", advance = false)) }
    notice?.let { shown[0] = it }
    val current = shown[0]
    val date = retirementDate(current.exchange, appLocale())
    AnimatedVisibility(
        visible = notice != null,
        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(FADE_MILLIS)) + expandVertically(),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(FADE_MILLIS)) + shrinkVertically(),
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag(RETIRED_NOTICE_TAG)) {
            Text(
                stringResource(
                    if (current.advance) R.string.exchange_retiring_notice else R.string.exchange_retired_notice,
                    RetiredWatch.nameOfKey(current.exchange),
                    date,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { if (current.advance) store.dismissAdvance(current.exchange) else store.dismiss(current.exchange) },
                modifier = Modifier.size(TOUCH_MIN),
            ) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.watchlist_missing_logos_dismiss),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Gezeigter Hinweis: Schlüssel der Börse und ob es die Ankündigung (vor dem Ende) ist. */
private data class Notice(val exchange: String, val advance: Boolean)

/** Von MainActivity gesetzt; ohne Anbieter (Vorschau, Tests ohne Hilt) kein Hinweis. */
val LocalRetiredNoticeStore = staticCompositionLocalOf<RetiredNoticeStore?> { null }

private const val FADE_MILLIS = 300
private val TOUCH_MIN = 48.dp

/** Der Hinweis prüft die Uhr höchstens minütlich neu (Ende um 00:00 UTC, [ExchangeRetirement.isRetired]). */
private const val NOTICE_TICK_MILLIS = 60_000L
