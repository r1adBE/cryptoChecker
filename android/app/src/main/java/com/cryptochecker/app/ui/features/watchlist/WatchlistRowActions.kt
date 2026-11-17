package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.WatchMove
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.components.LocalCoinLogoSource
import com.cryptochecker.app.ui.components.LocalRowHighlight
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.resources.R

/** Beschriftungen der Zeilen-Aktionen ([watchRowActions]) für Screenreader. */
internal class WatchRowLabels(
    val moveUp: String,
    val moveDown: String,
    val delete: String,
    val favorite: String,
    val select: String,
    val open: String,
    val sourceHint: String,
)

@Composable
internal fun rememberWatchRowLabels(favorite: Boolean) = WatchRowLabels(
    moveUp = stringResource(R.string.a11y_move_up),
    moveDown = stringResource(R.string.a11y_move_down),
    delete = stringResource(R.string.action_delete),
    favorite = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add),
    select = stringResource(R.string.watchlist_select),
    // Tipp öffnet das Aktionsblatt: TalkBack sagt «zum Kurs und Aktionen zeigen doppeltippen» statt «zum Aktivieren»
    open = stringResource(R.string.unusual_action_open),
    sourceHint = stringResource(R.string.change_candle_source_action),
)

/**
 * Tippen, langes Drücken und Screenreader der Merklisten-Zeile ([WatchRow]): die ganze Zeile als ein Satz
 * ([description]); Favorit, Löschen, Auswählen, Herkunft der Änderung und Verschieben als Screenreader-Aktionen.
 */
internal fun Modifier.watchRowActions(
    labels: WatchRowLabels,
    description: String,
    sortMode: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onFavoriteAction: (() -> Unit)?,
    onDeleteAction: (() -> Unit)?,
    onSourceHint: (() -> Unit)?,
    canReorder: Boolean,
    onMove: (WatchMove) -> Unit,
): Modifier = this
    .then(
        // Auswahl: langes Drücken löst beim Loslassen wie ein Tipp aus (hakt an/ab)
        if (selecting) {
            Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
        } // Lange drücken startet die Mehrfachauswahl; im Sortiermodus nicht (dort zieht man)
        else if (!sortMode && onLongClick != null) {
            Modifier.combinedClickable(
                onClickLabel = labels.open,
                onLongClickLabel = labels.select,
                onLongClick = onLongClick,
                onClick = onClick,
            )
        } else {
            Modifier.clickable(onClickLabel = labels.open.takeIf { !sortMode }, onClick = onClick)
        }
    )
    .semantics {
        contentDescription = description
        val actions = buildList {
            // Wie die Wisch-Gesten; im Sortier- und Auswahlmodus nicht
            if (!sortMode && !selecting) {
                onFavoriteAction?.let { action ->
                    add(
                        CustomAccessibilityAction(labels.favorite) {
                            action()
                            true
                        }
                    )
                }
                onDeleteAction?.let { action ->
                    add(
                        CustomAccessibilityAction(labels.delete) {
                            action()
                            true
                        }
                    )
                }
                // «Auswählen» wie langes Drücken (startet die Mehrfachauswahl mit dieser Zeile)
                onLongClick?.let { action ->
                    add(
                        CustomAccessibilityAction(labels.select) {
                            action()
                            true
                        }
                    )
                }
            }
            onSourceHint?.let { action ->
                add(
                    CustomAccessibilityAction(labels.sourceHint) {
                        action()
                        true
                    }
                )
            }
            if (canReorder) {
                add(
                    CustomAccessibilityAction(labels.moveUp) {
                        onMove(WatchMove.UP)
                        true
                    }
                )
                add(
                    CustomAccessibilityAction(labels.moveDown) {
                        onMove(WatchMove.DOWN)
                        true
                    }
                )
            }
        }
        if (actions.isNotEmpty()) customActions = actions
    }

/**
 * Coin-Logo (bzw. Initialen) vor dem Paar, Favorit als kleiner Stern daran; feste Grösse, nimmt nur dem Paar-Text Platz.
 * Logos aus: keine Plakette, Favorit als eigener kleiner Stern.
 */
@Composable
internal fun RowScope.RowLogo(watch: WatchEntity, sortMode: Boolean, off: Boolean) {
    if (LocalCoinLogoSource.current == null && watch.favorite && !sortMode) {
        Icon(
            painterResource(R.drawable.ic_star),
            contentDescription = null,
            // Wie der Stern am Logo: weiss, in der Themenfarbe nur mit gewähltem ★-Chip
            tint = if (LocalRowHighlight.current.favorites) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(start = 4.dp, end = Spacing.sm)
                .size(16.dp)
        )
    }
    CoinBadge(
        watch.baseAsset,
        size = ROW_BADGE_SIZE,
        logo = CoinLogos.allowedFor(watch.exchangeKey),
        pair = CoinLogos.pairKey(watch.exchangeKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name),
        favorite = watch.favorite && !sortMode,
        modifier = Modifier
            .align(Alignment.CenterVertically)
            .padding(end = 8.dp)
            .alpha(if (off) DISABLED_ICON_ALPHA else 1f)
    )
}
