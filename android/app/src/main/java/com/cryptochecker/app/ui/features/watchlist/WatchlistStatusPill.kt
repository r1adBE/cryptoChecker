// Status der Merkliste als Pille (Punkt in der Statusfarbe und Satz, z. B. «Kurse der gewählten Börsen ·
// vor 2 Min.»), die passende Kurzform je Breite und der Chip «3 deaktiviert».
// Eigene Datei, weil die Pille ihre Text-Varianten und Bewegung selbst regelt; `WatchlistStatusRow` ordnet
// nur an.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.A11ySpeech
import com.cryptochecker.app.util.PriceFormat

/** Punkt in der Statusfarbe und der Satz dazu, z. B. «Kurse der gewählten Börsen · vor 2 Min.». */
@Composable
internal fun WatchlistStatusPill(
    status: WatchlistStatus,
    groupEmpty: Boolean,
    /** Alle Paare der Ansicht sind deaktiviert. */
    allDisabled: Boolean,
    now: Long,
    tone: Color,
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
        // Kurzform bei Platzmangel (nur «Kurse der gewählten Börsen · …»), siehe [FirstFitText]
        var shortText: String? = null
        val statusText = when {
            groupEmpty -> stringResource(R.string.watchlist_status_group_empty)
            allDisabled -> stringResource(R.string.watchlist_status_all_disabled)
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
            // D3: ein Wort vorne — «Live» nur, wenn ALLE Paare frisch am Strom sind (K-1), sonst
            // «Kurse der gewählten Börsen · vor 2 Min.» (Zeit wie [LiveStatus.shownTime]: bei gemischten
            // Paaren die der abgefragten): sagt wie der Store-Text, woher der Kurs kommt und wie alt er ist
            newest > 0 && status.allLive -> stringResource(R.string.status_live)
            newest > 0 -> {
                val age = ago(status.shownTime, now)
                shortText = stringResource(R.string.watchlist_status_exchange_prices_short, age)
                stringResource(R.string.watchlist_status_exchange_prices, age)
            }
            else -> stringResource(R.string.watchlist_pull_to_refresh)
        }
        // Screenreader: derselbe Satz, Zeit ausgeschrieben («vor 2 Minuten» statt «vor 2 Min.»)
        // und « · » als Pause
        // «vor X Min.» im Satz: bei «Kurse der gewählten Börsen» die gezeigte Zeit, sonst die jüngste
        val agoTime = if (!status.warn && !status.deviceOffline) status.shownTime else newest
        val shortAgo = if (agoTime > 0) ago(agoTime, now) else ""
        val longAgo = if (agoTime > 0) spokenAgo(agoTime, now) else ""
        val spoken = A11ySpeech.spokenDots(
            if (shortAgo.isNotEmpty() && shortAgo != longAgo) statusText.replace(shortAgo, longAgo) else statusText
        )
        // Höflich ansagen, aber nur beim Wechsel aktuell / veraltet / offline (nicht jede Minute)
        val kind = A11ySpeech.statusKind(
            none = status.none,
            offline = status.deviceOffline || status.offline,
            warn = status.warn,
            newest = newest,
        )
        AnnounceStatusChange(kind, spoken)
        // Einzeilig: zu eng (z. B. 390 dp mit «3 deaktiviert») → Kurzform «Börsenkurse · vor 1 Min.»,
        // noch enger nur die Zeit — das Alter hat Vorrang vor dem Wort davor (Screenreader: ganzer Satz)
        val variants = listOfNotNull(statusText, shortText, shortAgo.takeIf { it.isNotEmpty() && it != statusText && statusText.contains(it) })
        Crossfade(
            targetState = variants,
            animationSpec = if (reduceMotion) snap() else tween(STATUS_FADE_MILLIS),
            label = "status_text",
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 8.dp)
                .clearAndSetSemantics { contentDescription = spoken }
        ) { texts ->
            FirstFitText(
                variants = texts,
                style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        // Kurse per WebSocket: steht jetzt als Wort «Live» im Status (D3), kein zweites Abzeichen
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
 * Erste der [variants], die einzeilig in die Breite passt; passt keine, die letzte (mit «…»).
 * Wie `statusTextFit` (`ViewThatFits`) im Status (iOS).
 */
@Composable
private fun FirstFitText(variants: List<String>, style: TextStyle, color: Color) {
    Layout(content = {
        variants.forEach { Text(it, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }) { measurables, constraints ->
        val fits = measurables.indexOfFirst { it.maxIntrinsicWidth(Constraints.Infinity) <= constraints.maxWidth }
        val placeable = measurables[if (fits < 0) measurables.lastIndex else fits].measure(constraints.copy(minWidth = 0))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * Hinweis «3 deaktiviert» neben dem Status: kleine neutrale Pille im Stil des Status (Pause-Symbol,
 * Zahl). Tipp zeigt nur die deaktivierten Paare ([QuickView.DISABLED]); ist die Ansicht an
 * ([selected]), steht ✕ am Ende und der Tipp zeigt wieder alle. Wie iOS `disabledChip`.
 */
@Composable
internal fun DisabledChip(
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = MaterialTheme.colorScheme.onSurfaceVariant
    val clickLabel = stringResource(if (selected) R.string.watchlist_show_all else R.string.watchlist_show_only_disabled)
    // Druck-Rückmeldung nur auf der sichtbaren Pille, nicht auf der höheren Tippfläche
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            // Tippfläche 48 dp hoch (Review 19, wie Gruppe, Suche und ⚡): je 8 dp über und unter der Pille; nach
            // aussen meldet sie nur die Pillen-Höhe, die Statuszeile wächst nicht
            .layout { measurable, constraints ->
                val extra = DISABLED_TOUCH_EXTRA.roundToPx()
                val placeable = measurable.measure(constraints.offset(vertical = 2 * extra))
                layout(placeable.width, (placeable.height - 2 * extra).coerceAtLeast(0)) {
                    placeable.placeRelative(0, -extra)
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = clickLabel,
                role = Role.Button,
                onClick = onClick
            )
            .padding(vertical = DISABLED_TOUCH_EXTRA)
            .clip(RoundedCornerShape(50))
            .background(tone.copy(alpha = if (selected) 0.22f else 0.12f))
            .indication(interaction, LocalIndication.current)
            .padding(horizontal = 10.dp, vertical = Spacing.sm)
    ) {
        Icon(
            painterResource(R.drawable.ic_pause_circle),
            contentDescription = null,
            tint = tone,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = pluralStringResource(R.plurals.watchlist_disabled_count, count, count),
            style = MaterialTheme.typography.labelMedium.tabularNumbers(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp)
        )
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = null,
                tint = tone,
                modifier = Modifier.padding(start = 6.dp).size(14.dp)
            )
        }
    }
}

/** Tippfläche über und unter der Pille «N deaktiviert»: 32 + 2 × 8 = 48 dp. */
private val DISABLED_TOUCH_EXTRA = 8.dp
