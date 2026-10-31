package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * Was hinter der Basis steht, wenn der Platz knapp wird. Der Kontrakt («PERP») fällt nie weg — eher
 * die Quote (sie steht ohnehin beim Kurs) und als letzter Ausweg wird die Basis gekürzt. Wie iOS
 * `WatchlistRow.pairName`.
 */
internal enum class PairRestFit {
    /** Ganzer Rest («/USDT PERP»). */
    FULL,

    /** Nur der Kontrakt («PERP»), die Quote entfällt. */
    CONTRACT,

    /** Nur der Kontrakt; die Basis wird gekürzt, damit er sichtbar bleibt. */
    CONTRACT_SHRINK_BASE,

    /** Spot: nichts hinter der Basis. */
    NONE;

    companion object {
        /**
         * [room] Platz hinter der Basis; [restMin] längstes Wort des Rests; [contractWidth] Breite des
         * Kontrakts, null bei Spot.
         */
        fun choose(room: Int, restMin: Int, hasRest: Boolean, contractWidth: Int?): PairRestFit = when {
            hasRest && room >= restMin -> FULL
            contractWidth == null -> NONE
            room >= contractWidth -> CONTRACT
            else -> CONTRACT_SHRINK_BASE
        }
    }
}

/**
 * Paar in zwei Teilen: Basis gross und halbfett («BTC»), dahinter klein und grau der Rest
 * («/USDT», bei Futures «/USDT PERP»), auf gemeinsamer Grundlinie. Die Basis wird nie zugunsten der
 * Quote gekürzt: Der Rest erscheint nur, wenn mindestens sein erstes Wort («/USDT») ganz Platz hat,
 * sonst entfällt die Quote — der Kontrakt ([contract]) bleibt aber immer stehen ([PairRestFit]).
 */
@Composable
internal fun PairName(
    base: String,
    rest: String,
    contract: String?,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            Text(
                text = base,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = maxLines,
                // Nur als letzter Ausweg (überlange Symbole): die Quote ist dann schon weg
                overflow = TextOverflow.Ellipsis,
            )
            for (part in listOf(rest, contract?.let { " $it" }.orEmpty())) {
                Text(
                    text = part,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val baseM = measurables[0]
        val restM = measurables[1]
        val contractM = measurables[2]
        // Jedes Kind darf nur einmal gemessen werden: entscheiden über die Eigenbreiten, dann messen
        val baseWidth = baseM.maxIntrinsicWidth(Constraints.Infinity)
        val room = if (constraints.hasBoundedWidth) constraints.maxWidth - baseWidth else Constraints.Infinity
        val contractWidth = if (contract == null) null else contractM.maxIntrinsicWidth(Constraints.Infinity)
        val fit = PairRestFit.choose(room, restM.minIntrinsicWidth(Constraints.Infinity), rest.isNotEmpty(), contractWidth)
        val baseMax = if (fit == PairRestFit.CONTRACT_SHRINK_BASE) {
            (constraints.maxWidth - (contractWidth ?: 0)).coerceAtLeast(0)
        } else constraints.maxWidth
        val baseP = baseM.measure(loose.copy(maxWidth = baseMax))
        val restRoom = (constraints.maxWidth - baseP.width).coerceAtLeast(0)
        val restP = when (fit) {
            PairRestFit.FULL -> restM.measure(loose.copy(maxWidth = restRoom))
            PairRestFit.CONTRACT, PairRestFit.CONTRACT_SHRINK_BASE -> contractM.measure(loose.copy(maxWidth = restRoom))
            PairRestFit.NONE -> null
        }
        // Gemeinsame Grundlinie: der kleine Rest sitzt auf der Grundlinie der Basis
        val baseLine = baseP[FirstBaseline]
        val restLine = restP?.get(FirstBaseline) ?: 0
        val restTop = (baseLine - restLine).coerceAtLeast(0)
        val baseTop = (restLine - baseLine).coerceAtLeast(0)
        val width = baseP.width + (restP?.width ?: 0)
        val height = maxOf(baseTop + baseP.height, restP?.let { restTop + it.height } ?: 0)
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height.coerceAtLeast(constraints.minHeight)) {
            baseP.placeRelative(0, baseTop)
            restP?.placeRelative(baseP.width, restTop)
        }
    }
}
