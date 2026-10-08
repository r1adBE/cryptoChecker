@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.WatchJump
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Sprungknopf: Durchmesser und Abstand zum unteren und seitlichen Rand. */
internal val JumpButtonSize = 40.dp

internal val JumpButtonMargin = 16.dp

/** So weit rückt der Sprungknopf nach oben, solange ein Banner unten steht. */
private val JumpBannerLift = 64.dp

/** Kleiner runder Knopf mit Pfeil: «Zum Ende» (nach unten) bzw. «Zum Anfang» (nach oben). */
@Composable
private fun JumpButton(down: Boolean, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(JumpButtonSize)
    ) {
        Icon(
            painterResource(if (down) R.drawable.ic_arrow_downward else R.drawable.ic_arrow_upward),
            contentDescription = stringResource(if (down) R.string.watchlist_jump_end else R.string.watchlist_jump_start),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Läuft ein Screenreader mit «Tippen zum Erkunden» (TalkBack)? Folgt Änderungen. */
@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * Sprungknopf «Zum Anfang» / «Zum Ende»: nur bei mehr als 30 sichtbaren (ggf. gesuchten)
 * Paaren, nicht beim Sortieren ([eligible]). Erscheint beim Scrollen, verschwindet 2 s danach;
 * mit TalkBack bleibt er stehen, damit er erreichbar ist ([visible]).
 */
@Stable
internal class WatchlistJump(
    val eligible: Boolean,
    val visible: Boolean,
    private val pointsDown: State<Boolean>,
    private val onJump: () -> Unit,
) {
    /** Obere Hälfte → Pfeil nach unten (ans Ende), sonst nach oben (an den Anfang). */
    val down: Boolean get() = pointsDown.value

    fun jump() = onJump()
}

@Composable
internal fun rememberWatchlistJump(
    listState: LazyListState,
    count: Int,
    sortMode: Boolean,
    reduceMotion: Boolean,
    scope: CoroutineScope,
): WatchlistJump {
    val view = LocalView.current
    val eligible = WatchJump.eligible(count, sortMode)
    val touchExploration = rememberTouchExplorationEnabled()
    var scrolled by remember { mutableStateOf(false) }
    LaunchedEffect(eligible) {
        if (!eligible) {
            scrolled = false
            return@LaunchedEffect
        }
        // Läuft, solange gezogen oder geschwungen wird (auch mit ruhendem Finger)
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                scrolled = true
            } else {
                delay(WatchJump.HIDE_DELAY_MILLIS)
                scrolled = false
            }
        }
    }
    val down = remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val items = info.visibleItemsInfo
            WatchJump.pointsDown(
                firstVisible = items.firstOrNull()?.index ?: 0,
                lastVisible = items.lastOrNull()?.index ?: 0,
                total = info.totalItemsCount,
            )
        }
    }
    return WatchlistJump(eligible, eligible && (scrolled || touchExploration), down) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            val target = if (down.value) total - 1 else 0
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            scope.launch {
                // Kurze Strecke sanft, lange sofort (keine lange Animation); «Bewegung reduzieren» sofort
                if (WatchJump.animate(target - listState.firstVisibleItemIndex, reduceMotion)) {
                    listState.animateScrollToItem(target)
                } else {
                    listState.scrollToItem(target)
                }
            }
        }
    }
}

/** Der Knopf unten am Ende; rückt über ein stehendes Banner ([bannerShown]). */
@Composable
internal fun BoxScope.WatchlistJumpButton(
    jump: WatchlistJump,
    bannerShown: Boolean,
    reduceMotion: Boolean,
    endInset: Dp,
) {
    val lift by animateDpAsState(
        targetValue = if (bannerShown) JumpBannerLift else 0.dp,
        animationSpec = if (reduceMotion) snap() else tween(200),
        label = "jump_lift"
    )
    androidx.compose.animation.AnimatedVisibility(
        visible = jump.visible,
        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(180)),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(250)),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp + endInset, bottom = JumpButtonMargin + lift)
    ) {
        JumpButton(down = jump.down, onClick = jump::jump)
    }
}
