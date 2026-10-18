// Teile einer Merklisten-Zeile: links Logo/Badge mit Paar und Börse, rechts Kurs mit Änderung und
// Umrechnung, im Sortiermodus das Menü «Verschieben».
// Eigene Datei, weil `WatchRow` nur Gesten, Vorlese-Text und Anordnung regelt.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.ui.components.ListSegment
import com.cryptochecker.app.ui.components.RollingNumberText
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.ChipShape
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.body
import com.cryptochecker.app.util.PriceFormat

/** Coin-Plakette in der Zeile: in allen Coin-Listen gleich gross ([ListSegment.Logo]). */
internal val ROW_BADGE_SIZE = ListSegment.Logo

/**
 * Watch-Ids, deren letzter Kurs der Mark-Preis statt des Mittelkurses war ([com.cryptochecker.app.data.MarkFallbackStore]):
 * Zeile und Aktionsblatt nennen ihn «Mark». Gesetzt von der Merkliste.
 */
internal val LocalMarkFallback = compositionLocalOf<Set<Long>> { emptySet() }

/**
 * Ende der Pause je Börsen-Schlüssel ([com.cryptochecker.app.data.RefreshStats.exchangePauses]): die Zeitzeile
 * pausierter Börsen sagt «Binance · pausiert bis 19:45» ([com.cryptochecker.app.domain.watch.RowPause]). Gesetzt von
 * der Merkliste.
 */
internal val LocalExchangePauses = compositionLocalOf<Map<String, Long>> { emptyMap() }

/**
 * Links: Griff im Sortiermodus, sonst etwas Abstand. Favoriten zeigt mit eingeschalteten
 * Coin-Logos ein kleiner Stern am Logo (sonst ein eigener kleiner Stern vor dem Paar) — kein eigener Stern-Knopf mehr,
 * der dem Paar Breite nimmt (Favorit wechseln: nach rechts wischen, Aktionsblatt, Screenreader-Aktion).
 */
@Composable
internal fun RowLeading(
    sortMode: Boolean,
    selecting: Boolean,
    selected: Boolean,
    accent: Color,
    handleModifier: Modifier,
) {
    if (selecting) {
        // Häkchen-Kreis: gefüllt in der Themenfarbe = ausgewählt, sonst nur Rand (Zustand liest
        // der Screenreader über das Ankreuzfeld der Zeile)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = Spacing.sm, end = Spacing.sm)
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(accent)
                    else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
        ) {
            if (selected) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = AppColors.onAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    } else if (sortMode) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = handleModifier
                .padding(start = Spacing.xs, end = Spacing.xs)
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.accentTint(0.12f))
        ) {
            Icon(
                painterResource(R.drawable.ic_drag_handle),
                contentDescription = stringResource(R.string.sort_drag_handle),
                tint = accent
            )
        }
    } else {
        Spacer(Modifier.width(Spacing.sm))
    }
}

/** Rechts: Kurs (rollende Ziffern, kurzes Aufblitzen), %-Änderung als farbige Schrift und ≈ Umrechnung. */
@Composable
internal fun RowPrice(
    watch: WatchEntity,
    converted: String?,
    /** Deaktiviert: Kurs in onSurfaceVariant (nicht abgeblasst), Änderung neutral grau. */
    off: Boolean,
    /** Veraltet: Kurs in onSurfaceVariant statt abgeblasst (mindestens 4.5:1 in hell und dunkel). */
    stale: Boolean,
    reveal: Animatable<Float, AnimationVector1D>,
    flash: PriceFlash,
    /** %-Änderung aus den Kerzen einer anderen Börse: kleiner Marker «*» daneben ([DayChangePill] marker). */
    changeMarker: Boolean = false,
    /** Kurs ist der Mark-Preis (kein Mittelkurs, [LocalMarkFallback]): klein «Mark» hinter der Gegenwährung. */
    markPrice: Boolean = false,
    /** Neben dem Paar Abstand links; unter dem Paar (grösste Schrift) rechtsbündig. */
    modifier: Modifier = Modifier.padding(start = 8.dp),
) {
    val flashColor = if (flash.up) PriceColors.up else PriceColors.down
    // Kurs bleibt auch im Sortiermodus sichtbar
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier.clearAndSetSemantics { }
    ) {
        // Geänderte Ziffern rollen (nach oben bei steigendem Kurs); die Gegenwährung klein und grau
        // dahinter wie der Börsenname links. Immer «Kurs Währung» von links nach rechts (wie zuvor
        // als ein Text), auch bei Schrift von rechts nach links.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = reveal.value
                        translationY = (1f - reveal.value) * 6.dp.toPx()
                    }
                    .clip(ChipShape)
                    .background(flashColor.copy(alpha = 0.28f * flash.strength.value))
                    .padding(horizontal = 4.dp)
            ) {
                RollingNumberText(
                    text = PriceFormat.price(watch.lastPrice),
                    value = watch.lastPrice,
                    style = MaterialTheme.typography.titleMedium.amountNumbers(),
                    color = if (stale || off) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.alignByBaseline()
                )
                if (watch.lastPrice != null && watch.lastPrice > 0.0) {
                    Text(
                        text = watch.quoteAsset,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline().padding(start = 4.dp)
                    )
                    if (markPrice) {
                        Text(
                            text = stringResource(R.string.watch_change_mark),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.alignByBaseline().padding(start = 4.dp)
                        )
                    }
                }
            }
        }
        // Höhe der Zeile wie der Name links (16 sp + 2 × 2 dp): die Änderung hat ohne Pille keinen
        // eigenen Innenabstand mehr, so stehen Paar/Kurs, Name/Änderung und Börse/≈ weiter auf einer Linie
        Box(Modifier.graphicsLayer { alpha = reveal.value }.padding(vertical = 2.dp)) {
            // Deaktiviert: letzter Wert in neutralem Grau, ohne Kursfarbe
            if (watch.lastPrice != null) {
                val view = LocalChangeView.current
                DayChangePill(
                    change = watch.shownChange(view),
                    neutral = watch.disabled,
                    // Hyperliquid: % Mark gegen Mark, Kurs = Mittelkurs — als «Mark» benennen
                    mark = com.cryptochecker.app.domain.watch.ChangeBasisMath.showsMark(
                        com.cryptochecker.marketdata.util.MarketsConfigUtils.getMarketByKey(watch.marketKey).changeIsMark,
                        view.basis,
                    ),
                    marker = changeMarker,
                )
            }
        }
        if (converted != null) {
            Text(
                text = converted,
                // Build 57: wie die Börsenzeile links (14 sp); die Spalte ist so breit wie ihr Inhalt
                style = MaterialTheme.typography.body.amountNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

/** Sortiermodus: «ganz nach oben/unten» im Zeilenmenü. */
@Composable
internal fun RowSortMenu(onMove: (WatchMove) -> Unit) {
    // Platz in der Zeile bleibt 36 dp, die Tippfläche ist 48 dp (ragt je 6 dp darüber hinaus)
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        var menu by remember { mutableStateOf(false) }
        IconButton(onClick = { menu = true }, modifier = Modifier.wrapContentSize(unbounded = true)) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.action_more)
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_move_top)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_move_top), null) },
                onClick = { menu = false; onMove(WatchMove.TOP) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_move_bottom)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_move_bottom), null) },
                onClick = { menu = false; onMove(WatchMove.BOTTOM) }
            )
        }
    }
}
