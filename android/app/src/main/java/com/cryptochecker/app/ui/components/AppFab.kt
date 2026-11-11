package com.cryptochecker.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.AddButtonScroll
import com.cryptochecker.app.ui.theme.AppColors

/**
 * Der eine «+»-Knopf unten rechts (Portfolio, Coin-Seite, Alarme): immer gefüllt in der
 * Akzentfarbe mit [AppColors.onAccent], 56 dp, Ecken 16 dp (`shapes.medium`). Ohne feste Farben
 * nähme der FAB `primaryContainer` — hell ein blasses Pfirsich, dunkel (dort = `primary`) kräftig
 * —, also dieselbe Aktion in zwei Farben. Wie iOS `PortfolioAddButton`.
 */
@Composable
fun AppFab(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = AppColors.onAccent,
        modifier = modifier,
    ) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = contentDescription)
    }
}

/**
 * [AppFab] über einer Liste mit Beträgen (Portfolio, Coin-Seite): blendet beim Scrollen nach unten aus, damit er keine
 * Beträge verdeckt, und beim Scrollen nach oben wieder ein ([FabScrollState]). Am Ende der Liste ist er immer da — dort
 * gibt `Spacing.fabClearance` ihm Platz unter der letzten Zeile. «Animationen entfernen» ([rememberReduceMotion]): der
 * Knopf erscheint und verschwindet ohne Skalieren und Blenden.
 */
@Composable
fun AppFab(onClick: () -> Unit, contentDescription: String, scroll: FabScrollState, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    AnimatedVisibility(
        visible = scroll.visible,
        enter = if (reduceMotion) EnterTransition.None else scaleIn() + fadeIn(),
        exit = if (reduceMotion) ExitTransition.None else scaleOut() + fadeOut(),
    ) {
        AppFab(onClick = onClick, contentDescription = contentDescription, modifier = modifier)
    }
}

/**
 * Sichtbarkeit des «+»-Knopfs zur Liste [list] ([AddButtonScroll]); [connection] gehört an den Rahmen der Seite
 * (`Modifier.nestedScroll`). Am Anfang und am Ende der Liste (auch wenn sie gar nicht scrollt oder ohne Geste dorthin
 * springt) immer sichtbar.
 */
@Stable
class FabScrollState internal constructor(private val density: Float, private val list: LazyListState) {
    private var state by mutableStateOf(AddButtonScroll.State())

    val visible: Boolean get() = state.visible || !list.canScrollBackward || !list.canScrollForward

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // consumed.y < 0: die Liste ist nach unten gescrollt (spätere Zeilen kommen)
            state = AddButtonScroll.next(
                state,
                delta = (-consumed.y / density).toDouble(),
                atTop = !list.canScrollBackward,
                atEnd = !list.canScrollForward,
            )
            return Offset.Zero
        }
    }
}

@Composable
fun rememberFabScroll(list: LazyListState): FabScrollState {
    val density = LocalDensity.current.density
    return remember(list, density) { FabScrollState(density, list) }
}
