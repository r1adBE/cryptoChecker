package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.util.DenseRowText

/**
 * Zeile «Börse ◇ 🔔 🔔 1» der Merkliste: Passen Börsentext ([lead], samt ⚠) und Symbole ([icons]) nicht
 * nebeneinander, stehen die Symbole in einer eigenen Zeile unter der Börse ([DenseRowText.metaWraps]) — statt die
 * Börse zu kürzen («Bi…») oder das Mini-Chart auszublenden. Der Börsentext bekommt dann die ganze Breite; gekürzt
 * wird er nur noch als letzter Ausweg (eigene Zeilengrenze). Gleiche Regel wie iOS `WatchlistMetaLine`.
 */
@Composable
internal fun RowMetaLine(
    lead: @Composable () -> Unit,
    icons: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Abstand zwischen Text und Symbolen in einer Zeile. */
    gap: Dp,
    /** Abstand zwischen Börsenzeile und umgebrochenen Symbolen. */
    lineGap: Dp = 2.dp,
) {
    Layout(
        modifier = modifier,
        content = {
            Box(Modifier.layoutId(META_LEAD)) { lead() }
            Box(Modifier.layoutId(META_ICONS)) { icons() }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gapPx = gap.roundToPx()
        val lineGapPx = lineGap.roundToPx()
        val leadM = measurables.first { it.layoutId == META_LEAD }
        val iconsP = measurables.first { it.layoutId == META_ICONS }.measure(loose.copy(maxWidth = Constraints.Infinity))
        val hasIcons = iconsP.width > 0
        val wrapped = hasIcons && DenseRowText.metaWraps(
            available = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else null,
            lead = leadM.maxIntrinsicWidth(Constraints.Infinity).toFloat(),
            icons = iconsP.width.toFloat(),
            gap = gapPx.toFloat(),
        )
        val iconsRoom = if (hasIcons) iconsP.width + gapPx else 0
        val leadMax = when {
            !constraints.hasBoundedWidth -> Constraints.Infinity
            wrapped -> constraints.maxWidth
            else -> (constraints.maxWidth - iconsRoom).coerceAtLeast(0)
        }
        val leadP = leadM.measure(loose.copy(maxWidth = leadMax))
        val width = if (wrapped) maxOf(leadP.width, iconsP.width) else leadP.width + iconsRoom
        val height = if (wrapped) leadP.height + lineGapPx + iconsP.height else maxOf(leadP.height, iconsP.height)
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height.coerceAtLeast(constraints.minHeight)) {
            // placeRelative spiegelt bei RTL (Arabisch, Hebräisch, Persisch)
            if (wrapped) {
                leadP.placeRelative(0, 0)
                iconsP.placeRelative(0, leadP.height + lineGapPx)
            } else {
                // Eine Zeile: Mitte an Mitte
                leadP.placeRelative(0, (height - leadP.height) / 2)
                iconsP.placeRelative(leadP.width + gapPx, (height - iconsP.height) / 2)
            }
        }
    }
}

private const val META_LEAD = "lead"
private const val META_ICONS = "icons"
