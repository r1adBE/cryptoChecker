package com.cryptochecker.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Erneutes Tippen auf den schon gewählten Tab (wie iOS `AppRouter.requestScrollToTop`): ein Zähler
 * je Tab-Route; die Bildschirme hören mit [OnTabReselect] darauf und rollen an den Anfang.
 */
@Stable
class TabReselectSignal {
    private val counts = mutableStateMapOf<String, Int>()

    fun request(route: String) {
        counts[route] = (counts[route] ?: 0) + 1
    }

    fun count(route: String): Int = counts[route] ?: 0
}

val LocalTabReselect = staticCompositionLocalOf { TabReselectSignal() }

/**
 * Führt [action] aus, sobald der Tab [route] erneut angetippt wird. Ein Bildschirm, der (wieder)
 * erscheint, reagiert nicht auf frühere Tipps: der Stand beim Erscheinen gilt als erledigt.
 */
@Composable
fun OnTabReselect(route: String, action: suspend () -> Unit) {
    val signal = LocalTabReselect.current
    val count = signal.count(route)
    var handled by remember { mutableIntStateOf(count) }
    val currentAction by rememberUpdatedState(action)
    LaunchedEffect(count) {
        if (count != handled) {
            handled = count
            currentAction()
        }
    }
}
