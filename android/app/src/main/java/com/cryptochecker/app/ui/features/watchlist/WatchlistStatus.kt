@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat

/** Weicher Wechsel von Farbe und Text der Status-Pille (z. B. nach einer Aktualisierung). */
private const val STATUS_FADE_MILLIS = 200

/** «gerade eben» bzw. «vor 2 Min.» in der Sprache des Geräts. */
@Composable
internal fun ago(millis: Long, now: Long): String =
    if (now - millis < 60_000) stringResource(R.string.time_just_now)
    else DateUtils.getRelativeTimeSpanString(
        millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

/**
 * Ehrlicher Status der gezeigten Paare: wie viele Kurse sind veraltet, gescheitert, offline?
 * Der technische Bericht steht nur noch im Menü. «Nicht mehr gehandelt» ist kein Fehler:
 * zählt weder als veraltet noch als gescheitert.
 */
internal data class WatchlistStatus(
    /** Gehandelte Paare der Ansicht. */
    val traded: Int,
    val stale: Int,
    val failed: Int,
    /** Jüngste Aktualisierung (0 = nie). */
    val newest: Long,
    /** Der letzte Durchlauf scheiterte bei ALLEN Paaren am Netz. */
    val offline: Boolean,
    /** Gerät ohne Netz: ruhig (neutral), kein Rot — es ist kein Fehler der App. */
    val deviceOffline: Boolean,
) {
    /** Kein gehandeltes Paar in der Ansicht (leere Gruppe, alle nicht mehr gehandelt). */
    val none: Boolean get() = traded == 0
    val warn: Boolean get() = stale > 0 || offline || failed > 0

    companion object {
        /** Gehandeltes Paar ohne frischen Kurs (gleiche Regel wie Zeile und Status). */
        fun isStale(watch: WatchEntity, now: Long, staleAfter: Long): Boolean =
            !isNotTraded(watch.lastError) && (watch.lastUpdate <= 0 || now - watch.lastUpdate > staleAfter)

        fun of(visible: List<WatchEntity>, now: Long, staleAfter: Long, online: Boolean): WatchlistStatus {
            val traded = visible.filterNot { isNotTraded(it.lastError) }
            return WatchlistStatus(
                traded = traded.size,
                stale = traded.count { isStale(it, now, staleAfter) },
                failed = traded.count { it.lastError != null },
                newest = traded.maxOfOrNull { it.lastUpdate } ?: 0L,
                offline = traded.isNotEmpty() && traded.all { isConnectionError(it.lastError) },
                deviceOffline = !online && traded.isNotEmpty(),
            )
        }
    }
}

/**
 * Status links, Lupe und «+» (Paar hinzufügen) rechts — beim Suchen wird die Zeile zum Suchfeld. Farbe und Text
 * wechseln nach einer Aktualisierung weich (nicht hart).
 */
@Composable
internal fun WatchlistStatusRow(
    visible: List<WatchEntity>,
    now: Long,
    staleAfter: Long,
    online: Boolean,
    /** Kurse kommen per WebSocket. */
    live: Boolean,
    reduceMotion: Boolean,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    /** «Paar hinzufügen» rechts neben der Lupe. */
    onAddPair: () -> Unit,
    /** Lupe und «+» (nicht im Sortiermodus). */
    searchAvailable: Boolean,
    /** Ansicht «nur veraltete» ist an: Status mit ✕, Tipp zeigt wieder alle. */
    staleOnly: Boolean = false,
    /** Tipp auf den Status (nur mit veralteten Paaren bzw. in der Ansicht «nur veraltete»). */
    onToggleStale: () -> Unit = {},
) {
    val status = WatchlistStatus.of(visible, now, staleAfter, online)
    // Kein gehandeltes Paar: neutral statt grün — es gibt nichts, das «aktuell» sein könnte
    val tone = when {
        status.none || status.deviceOffline -> MaterialTheme.colorScheme.onSurfaceVariant
        status.warn -> MaterialTheme.colorScheme.error
        else -> PriceColors.ok
    }
    val shownTone by animateColorAsState(
        targetValue = tone,
        animationSpec = if (reduceMotion) snap() else tween(STATUS_FADE_MILLIS),
        label = "status_tone"
    )
    AnimatedContent(
        targetState = searching,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(140)))
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.CenterStart,
        label = "status_search",
        modifier = Modifier.fillMaxWidth()
    ) { isSearching ->
        if (isSearching) {
            WatchSearchField(
                query = query,
                onQueryChange = onQueryChange,
                onClose = onCloseSearch
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().heightIn(min = SearchRowHeight)
            ) {
                WatchlistStatusPill(
                    status = status,
                    groupEmpty = visible.isEmpty(),
                    now = now,
                    tone = shownTone,
                    live = live,
                    reduceMotion = reduceMotion,
                    staleOnly = staleOnly,
                    onClick = if (staleOnly || status.stale > 0) onToggleStale else null,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (searchAvailable) {
                    // Lupe und «+» als Paar rechts (SpaceBetween verteilt sonst drei Teile über die Breite)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchButton(onClick = onOpenSearch)
                        AddPairButton(onClick = onAddPair)
                    }
                }
            }
        }
    }
}

/** Punkt in der Statusfarbe und der Satz dazu, z. B. «Alle Kurse aktuell · vor 2 Min.». */
@Composable
private fun WatchlistStatusPill(
    status: WatchlistStatus,
    groupEmpty: Boolean,
    now: Long,
    tone: Color,
    live: Boolean,
    reduceMotion: Boolean,
    /** «Nur veraltete» an: ✕ am Ende. */
    staleOnly: Boolean = false,
    /** Tipp: nur veraltete zeigen bzw. wieder alle; null = nicht antippbar. */
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val clickLabel = stringResource(if (staleOnly) R.string.watchlist_show_all else R.string.watchlist_show_only_stale)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
                else Modifier
            )
            .background(tone.copy(alpha = 0.12f))
            .animateContentSize(if (reduceMotion) snap() else tween(STATUS_FADE_MILLIS))
            .padding(horizontal = 12.dp, vertical = Spacing.sm)
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(tone))
        val newest = status.newest
        val statusText = when {
            groupEmpty -> stringResource(R.string.watchlist_status_group_empty)
            status.none -> stringResource(R.string.watchlist_status_none_traded)
            status.deviceOffline -> if (newest > 0) {
                stringResource(R.string.offline_status_since, PriceFormat.shortTime(newest))
            } else {
                stringResource(R.string.offline_status)
            }
            status.offline -> if (newest > 0) stringResource(R.string.watchlist_offline_since, ago(newest, now))
                else stringResource(R.string.watch_error_offline)
            status.stale > 0 -> pluralStringResource(R.plurals.watchlist_stale_count, status.stale, status.stale, status.traded)
            status.failed > 0 -> pluralStringResource(R.plurals.watchlist_failed_count, status.failed, status.failed, status.traded)
            newest > 0 -> stringResource(R.string.watchlist_all_fresh, ago(newest, now))
            else -> stringResource(R.string.watchlist_pull_to_refresh)
        }
        Crossfade(
            targetState = statusText,
            animationSpec = if (reduceMotion) snap() else tween(STATUS_FADE_MILLIS),
            label = "status_text",
            modifier = Modifier.padding(start = 8.dp)
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        // Kurse kommen per WebSocket (Runde 31)
        if (live) LiveBadge(tone, reduceMotion)
        if (staleOnly) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp).size(14.dp)
            )
        }
    }
}

/**
 * «+»: Seite «Paar hinzufügen» — überall derselbe gefüllte Kreis in der Themenfarbe (rechts neben
 * der Lupe und oben rechts in der Start-Auswahl), wie iOS `addPairButton`.
 */
@Composable
internal fun AddPairButton(onClick: () -> Unit, modifier: Modifier = Modifier.padding(start = 8.dp)) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            // Tippfläche 48 dp, sichtbar bleibt der kleine Kreis (wie Material-IconButton)
            .minimumInteractiveComponentSize()
            .size(SearchRowHeight)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
    ) {
        Icon(
            painterResource(R.drawable.ic_add),
            contentDescription = stringResource(R.string.shortcut_add),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}
