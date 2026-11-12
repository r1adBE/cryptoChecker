package com.cryptochecker.app.ui.features.watchlist

import android.content.Context
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.marketdata.config.ExchangeRetirement
import kotlinx.coroutines.CoroutineDispatcher
import androidx.compose.runtime.staticCompositionLocalOf
import com.cryptochecker.app.domain.util.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Test-Tag der Zeile (UI-Tests). */
const val RETIRED_NOTICE_TAG = "retired_exchange_line"

/**
 * Einmaliger Hinweis über der Merkliste, solange Paare einer stillgelegten Börse darin stehen
 * ([ExchangeRetirement.noticeExchanges]): «Bitpanda liefert seit 1. Februar 2027 keine öffentlichen Kurse mehr. Wähle für
 * diese Coins eine andere Börse.» Mit ✕ (48 dp, «Hinweis schliessen») für diese Börse geschlossen ([RetiredNoticeStore]),
 * an derselben Stelle wie «2 Coins ohne Logo» ([WatchlistMissingLogos]). Wie iOS `WatchlistRetiredNotice`.
 */
@Composable
internal fun WatchlistRetiredNotice(watches: List<WatchEntity>, now: Long, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val store = LocalRetiredNoticeStore.current ?: return
    val dismissed by produceState<Set<String>?>(null, store) {
        store.load()
        store.dismissed.collect { value = it }
    }
    val closed = dismissed
    val exchanges = remember(watches, closed, now / NOTICE_TICK_MILLIS) {
        if (closed == null) emptyList() else ExchangeRetirement.noticeExchanges(watches.map { it.exchangeName }, closed, now)
    }
    val shown = remember { arrayOf("") }
    exchanges.firstOrNull()?.let { shown[0] = it }
    val name = shown[0]
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    AnimatedVisibility(
        visible = exchanges.isNotEmpty(),
        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(FADE_MILLIS)) + expandVertically(),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(FADE_MILLIS)) + shrinkVertically(),
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag(RETIRED_NOTICE_TAG)) {
            Text(
                stringResource(R.string.exchange_retired_notice, name, retiredDate(name, locale)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { store.dismiss(name) }, modifier = Modifier.size(TOUCH_MIN)) {
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

/** Tag des Endes in der Sprache der App («1. Februar 2027»), als UTC-Datum (das Ende liegt um 00:00 UTC). */
private fun retiredDate(exchange: String, locale: Locale): String {
    val end = ExchangeRetirement.endsAt(exchange) ?: return ""
    val format = DateFormat.getDateInstance(DateFormat.LONG, locale)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(end))
}

/**
 * Mit ✕ geschlossene Hinweise ([WatchlistRetiredNotice]) je Börse: nur auf diesem Gerät (eigene SharedPreferences
 * «retired_exchange_notice»), nicht in der Systemsicherung (die Sicherungsregeln nehmen nur ausdrücklich Genanntes auf)
 * und nicht in der App-Sicherung.
 */
@Singleton
class RetiredNoticeStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val _dismissed = MutableStateFlow<Set<String>?>(null)
    val dismissed: StateFlow<Set<String>?> = _dismissed.asStateFlow()

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Einmal lesen (nicht im Haupt-Thread). */
    suspend fun load() {
        if (_dismissed.value != null) return
        val stored = withContext(io) { prefs().getStringSet(KEY, null)?.toSet().orEmpty() }
        _dismissed.compareAndSet(null, stored)
    }

    /** ✕: Hinweis für [exchange] schliessen. */
    fun dismiss(exchange: String) {
        if (exchange.isBlank()) return
        val updated = _dismissed.value.orEmpty() + exchange.trim().lowercase()
        _dismissed.value = updated
        prefs().edit { putStringSet(KEY, updated) }
    }

    private companion object {
        const val PREFS = "retired_exchange_notice"
        const val KEY = "dismissed"
    }
}

/** Von MainActivity gesetzt; ohne Anbieter (Vorschau, Tests ohne Hilt) kein Hinweis. */
val LocalRetiredNoticeStore = staticCompositionLocalOf<RetiredNoticeStore?> { null }

private const val FADE_MILLIS = 300
private val TOUCH_MIN = 48.dp

/** Der Hinweis prüft die Uhr höchstens minütlich neu (Ende um 00:00 UTC, [ExchangeRetirement.isRetired]). */
private const val NOTICE_TICK_MILLIS = 60_000L
