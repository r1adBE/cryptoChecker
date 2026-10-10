package com.cryptochecker.app.ui.features.watchlist

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Ab diesem Anteil der Breite löst das Wischen aus. */
private const val SWIPE_THRESHOLD = 0.38f

/**
 * Wisch-Aktionen einer Zeile der Merkliste. Zum Zeilenende hin (LTR: nach links) =
 * löschen: Die Zeile gleitet hinaus, dann [onDelete]. Zum Zeilenanfang hin (LTR: nach
 * rechts) = Favorit an/aus: [onToggleFavorite], die Zeile federt zurück. Darunter
 * erscheint beim Wischen rot «Löschen» bzw. in der Akzentfarbe der Stern. Unter
 * [SWIPE_THRESHOLD] der Breite federt die Zeile ohne Aktion zurück; beim Überschreiten
 * ein leichtes Haptik-Signal. In RTL spiegeln sich die Richtungen.
 *
 * Eigene Geste (Modifier.draggable, waagrecht) statt SwipeToDismissBox: Sie beginnt erst
 * nach waagrechter Bewegung über die Berührungsschwelle, lässt senkrechtes Scrollen,
 * Tippen und langes Drücken (Sortieren) in Ruhe und kommt ohne die je
 * nach Material-Version veränderte Bestätigungs-API aus.
 * Für den Screenreader sind beide Aktionen eigene Aktionen der Zeile (siehe WatchRow).
 * Ohne [onToggleFavorite] (Portfolio) gibt es nur das Löschen; zum Zeilenanfang hin bewegt
 * sich die Zeile dann nicht.
 */
@Composable
internal fun SwipeActionsRow(
    enabled: Boolean,
    favorite: Boolean,
    onDelete: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    /** Form der Zeile (bei Listen als Einheit je nach Platz, [com.cryptochecker.app.ui.components.ListSegment]). */
    shape: androidx.compose.ui.graphics.Shape? = null,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Physische Verschiebung in px (rechts positiv); «zum Ende hin» ist in LTR negativ
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    val currentDelete by rememberUpdatedState(onDelete)
    val currentFavorite by rememberUpdatedState(onToggleFavorite)
    val endSign = if (rtl) 1f else -1f

    fun threshold() = width * SWIPE_THRESHOLD
    fun isDelete(x: Float) = x * endSign > 0f
    fun beyond(x: Float) = width > 0 && abs(x) >= threshold()

    val dragState = rememberDraggableState { delta ->
        val before = offset
        val limit = width.toFloat()
        val next = (offset + delta).coerceIn(-limit, limit)
        // Nur Löschen: Bewegung zum Zeilenanfang hin sperren
        offset = if (currentFavorite == null && next * endSign < 0f) 0f else next
        // Schwelle überschritten (in beide Richtungen): ein leichtes Signal
        if (beyond(before) != beyond(offset)) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { width = it.width }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStopped = {
                    val x = offset
                    // Eigener Scope: unabhängig davon, wie die Geste ihr Ende meldet
                    scope.launch {
                        when {
                            beyond(x) && isDelete(x) -> {
                                // Hinausgleiten, dann löschen (die Liste schliesst die Lücke)
                                val target = width.toFloat() * if (x < 0f) -1f else 1f
                                if (reduceMotion) offset = target
                                else animate(x, target, animationSpec = tween(180)) { v, _ -> offset = v }
                                currentDelete()
                                // Zeile nach dem Löschen noch da (Löschen gescheitert)? Nicht unsichtbar lassen
                                delay(2_000)
                                offset = 0f
                            }
                            beyond(x) && currentFavorite != null -> {
                                currentFavorite?.invoke()
                                if (reduceMotion) offset = 0f
                                else animate(x, 0f, animationSpec = spring()) { v, _ -> offset = v }
                            }
                            else -> {
                                if (reduceMotion) offset = 0f
                                else animate(x, 0f, animationSpec = spring()) { v, _ -> offset = v }
                            }
                        }
                    }
                },
            )
    ) {
        if (offset != 0f) {
            SwipeBackground(
                delete = isDelete(offset),
                favorite = favorite,
                active = beyond(offset),
                shape = shape ?: MaterialTheme.shapes.medium,
                modifier = Modifier.matchParentSize()
            )
        }
        Box(Modifier.graphicsLayer { translationX = offset }) {
            content()
        }
    }
    // Sortiermodus an: halb gewischte Zeile zurücksetzen
    LaunchedEffect(enabled) {
        if (!enabled) offset = 0f
    }
}

/** Hintergrund unter der gewischten Zeile: rot mit Papierkorb bzw. Akzent mit Stern. */
@Composable
private fun SwipeBackground(
    delete: Boolean,
    favorite: Boolean,
    active: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier,
) {
    val container = if (delete) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val onContainer = if (delete) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    val icon = when {
        delete -> R.drawable.ic_delete
        favorite -> R.drawable.ic_star_outline
        else -> R.drawable.ic_star
    }
    val label = stringResource(
        when {
            delete -> R.string.action_delete
            favorite -> R.string.favorite_remove
            else -> R.string.favorite_add
        }
    )
    Box(
        contentAlignment = if (delete) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = modifier
            .clip(shape)
            .background(container.copy(alpha = if (active) 1f else 0.7f))
            .clearAndSetSemantics { }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = Spacing.lg)
        ) {
            // Symbol immer zur Mitte hin vom Rand: Löschen rechts, Favorit links (RTL gespiegelt)
            if (delete) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = onContainer)
                Icon(painterResource(icon), contentDescription = null, tint = onContainer, modifier = Modifier.size(22.dp))
            } else {
                Icon(painterResource(icon), contentDescription = null, tint = onContainer, modifier = Modifier.size(22.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = onContainer)
            }
        }
    }
}
