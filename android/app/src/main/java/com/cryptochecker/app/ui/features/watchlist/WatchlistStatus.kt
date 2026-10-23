@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import android.annotation.SuppressLint
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.DisabledView
import com.cryptochecker.app.domain.watch.LiveStatus
import com.cryptochecker.app.domain.watch.PriceFreshness
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.util.A11ySpeech

/** Weicher Wechsel von Farbe und Text der Status-Pille (z. B. nach einer Aktualisierung). */
internal const val STATUS_FADE_MILLIS = 200

/** «gerade eben» bzw. «vor 2 Min.» in der Sprache des Geräts. */
@Composable
internal fun ago(millis: Long, now: Long): String =
    if (now - millis < 60_000) stringResource(R.string.time_just_now)
    else DateUtils.getRelativeTimeSpanString(
        millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

/** Wie [ago], aber ausgeschrieben für den Screenreader: «vor 2 Minuten». */
@Composable
internal fun spokenAgo(millis: Long, now: Long): String =
    if (now - millis < 60_000) stringResource(R.string.time_just_now)
    else DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()

/**
 * Sagt [spoken] höflich an, wenn der Status zwischen aktuell / veraltet / offline wechselt
 * ([A11ySpeech.shouldAnnounce]) — nicht beim ersten Stand und nicht bei jeder neuen Zeitangabe.
 * Wie iOS (`AccessibilityNotification.Announcement`). Ohne Screenreader tut das System nichts.
 */
@Composable
internal fun AnnounceStatusChange(kind: A11ySpeech.StatusKind?, spoken: String) {
    val view = LocalView.current
    val text by rememberUpdatedState(spoken)
    val last = remember { mutableStateOf<A11ySpeech.StatusKind?>(null) }
    LaunchedEffect(kind) {
        if (A11ySpeech.shouldAnnounce(last.value, kind)) {
            // Compose kennt keine eigene Ansage; eine Live-Region würde jede Minute («vor 2 Min.») sprechen
            @Suppress("DEPRECATION")
            view.announceForAccessibility(text)
        }
        last.value = kind
    }
}

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
    /** Alle gehandelten Paare am Live-Strom und frisch: «Live» ([LiveStatus.allLive]). */
    val allLive: Boolean = false,
    /** Zeit für «vor X Min.» ([LiveStatus.shownTime]; gemischt: die der abgefragten Paare). */
    val shownTime: Long = newest,
) {
    /** Kein gehandeltes Paar in der Ansicht (leere Gruppe, alle nicht mehr gehandelt). */
    val none: Boolean get() = traded == 0
    val warn: Boolean get() = stale > 0 || offline || failed > 0

    companion object {
        /**
         * Gehandeltes, aktives Paar ohne frischen Kurs (gleiche Regel wie Zeile und Status).
         * [staleAfter] ist die Abfrage-Schwelle; Paare in [liveIds] (am Live-Strom) gelten schon
         * nach 2 Min. ohne Kurs als veraltet ([LiveStatus.staleAfterMillis]).
         */
        fun isStale(watch: WatchEntity, now: Long, staleAfter: Long, liveIds: Set<Long> = emptySet()): Boolean =
            PriceFreshness.isStale(watch, now, LiveStatus.staleAfterMillis(watch.id in liveIds, staleAfter))

        fun of(
            visible: List<WatchEntity>,
            now: Long,
            staleAfter: Long,
            online: Boolean,
            liveIds: Set<Long> = emptySet(),
        ): WatchlistStatus {
            // Deaktivierte Paare zählen nicht mit (weder veraltet noch gescheitert noch «aktuell»)
            val traded = DisabledView.active(visible) { it.disabled }.filterNot { isNotTraded(it.lastError) }
            val stale = traded.count { isStale(it, now, staleAfter, liveIds) }
            return WatchlistStatus(
                traded = traded.size,
                stale = stale,
                failed = traded.count { it.lastError != null },
                newest = traded.maxOfOrNull { it.lastUpdate } ?: 0L,
                offline = traded.isNotEmpty() && traded.all { isConnectionError(it.lastError) },
                deviceOffline = !online && traded.isNotEmpty(),
                allLive = LiveStatus.allLive(traded.map { it.id }, liveIds, stale),
                shownTime = LiveStatus.shownTime(traded.map { it.id to it.lastUpdate }, liveIds),
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
    /** Watch-Ids der Paare am Live-Strom (Börse liefert gerade per WebSocket). */
    liveIds: Set<Long>,
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
    /** Deaktivierte Paare der Gruppe: Hinweis «3 deaktiviert» neben dem Status (0 = keiner). */
    disabledCount: Int = 0,
    /** Ansicht «nur deaktivierte» ist an: Hinweis mit ✕, Tipp zeigt wieder alle. */
    disabledOnly: Boolean = false,
    /** Tipp auf den Hinweis «3 deaktiviert». */
    onToggleDisabled: () -> Unit = {},
    /** «+ Paar hinzufügen» mit Text statt nur «+» (kleine Merkliste, [ADD_PAIR_LABEL_MAX_PAIRS]). */
    addLabelled: Boolean = false,
) {
    val status = WatchlistStatus.of(visible, now, staleAfter, online, liveIds)
    // Alle Paare der Ansicht deaktiviert: eigener ruhiger Satz statt «Keine gehandelten Paare»
    val allDisabled = visible.isNotEmpty() && visible.all { it.disabled }
    // Kein gehandeltes Paar: neutral statt grün — es gibt nichts, das «aktuell» sein könnte
    // «veraltet» in Bernstein wie die Zeile, Fehler (keine Verbindung, gescheitert) in Fehlerfarbe
    val tone = when (
        com.cryptochecker.app.domain.watch.StatusPillTone.of(
            none = status.none, deviceOffline = status.deviceOffline, offline = status.offline,
            stale = status.stale, failed = status.failed,
        )
    ) {
        com.cryptochecker.app.domain.watch.StatusPillTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        com.cryptochecker.app.domain.watch.StatusPillTone.ERROR -> MaterialTheme.colorScheme.error
        com.cryptochecker.app.domain.watch.StatusPillTone.WARNING -> AppColors.warningText
        // Aktuell: ruhig in der Textfarbe, nicht in der Steigen-Farbe (frisch ≠ gestiegen)
        com.cryptochecker.app.domain.watch.StatusPillTone.NORMAL -> MaterialTheme.colorScheme.onSurface
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
                // Status und (falls es deaktivierte Paare gibt) der Hinweis «3 deaktiviert» als Paar links
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    WatchlistStatusPill(
                        status = status,
                        groupEmpty = visible.isEmpty(),
                        allDisabled = allDisabled,
                        now = now,
                        tone = shownTone,
                        reduceMotion = reduceMotion,
                        staleOnly = staleOnly,
                        onClick = if (staleOnly || status.stale > 0) onToggleStale else null,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (disabledCount > 0 || disabledOnly) {
                        DisabledChip(
                            count = disabledCount,
                            selected = disabledOnly,
                            onClick = onToggleDisabled,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                if (searchAvailable) {
                    // Lupe und «+» als Paar rechts (SpaceBetween verteilt sonst drei Teile über die Breite)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchButton(onClick = onOpenSearch)
                        // Kleine Merkliste (bis 3 Paare): «+ Paar hinzufügen» mit Text, damit der Weg zum nächsten
                        // Paar klar ist. Sonst nur «+» (Beschreibung «Paar hinzufügen»): lässt dem Status mehr Breite;
                        // ist es trotzdem zu eng, kürzt die Pille selbst («Börsenkurse · vor 1 Min.», [FirstFitText])
                        AddPairButton(onClick = onAddPair, labelled = addLabelled)
                    }
                }
            }
        }
    }
}

/** Bis zu so vielen Paaren in der Merkliste zeigt «+» auch den Text «Paar hinzufügen» (wie iOS). */
internal const val ADD_PAIR_LABEL_MAX_PAIRS = 3

/**
 * «+ Paar hinzufügen»: Seite «Paar hinzufügen» — gefüllte Pille in der Themenfarbe mit Symbol und
 * Text, wie iOS `addPairButton`. [labelled] = false: nur der Kreis mit «+» (neben der Lupe in der
 * Merkliste und oben rechts in der Start-Auswahl); die Beschreibung bleibt «Paar hinzufügen».
 */
// Standard «Abstand links» gehört zum Platz neben der Lupe; andere Aufrufer übergeben ihren eigenen Modifier
@SuppressLint("ModifierParameter")
@Composable
internal fun AddPairButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier.padding(start = 8.dp),
    labelled: Boolean = true,
) {
    val label = stringResource(R.string.shortcut_add)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            // Tippfläche mindestens 48 dp, sichtbar bleibt die 34-dp-Pille (wie Material-Knöpfe)
            .minimumInteractiveComponentSize()
            .heightIn(min = SearchRowHeight)
            .then(if (labelled) Modifier else Modifier.size(SearchRowHeight))
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .testTag(UiTestTags.WATCHLIST_ADD_PAIR)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (labelled) 12.dp else 0.dp)
    ) {
        Icon(
            painterResource(R.drawable.ic_add),
            // Mit Text liest der Screenreader den Text; ohne Text braucht das Symbol die Beschreibung
            contentDescription = if (labelled) null else label,
            tint = AppColors.onAccent,
            modifier = Modifier.size(20.dp)
        )
        if (labelled) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = AppColors.onAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
