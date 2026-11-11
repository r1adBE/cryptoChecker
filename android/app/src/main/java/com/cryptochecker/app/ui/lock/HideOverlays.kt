package com.cryptochecker.app.ui.lock

import android.os.Build
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.lock.findFragmentActivity
import java.util.WeakHashMap

/**
 * Fremde Fenster über der App ausblenden (`Window.setHideOverlayWindows`, ab Android 12, Berechtigung
 * HIDE_OVERLAY_WINDOWS), solange [active]: Über dem Portfolio und über heiklen Rückfragen (Sperre ein/aus,
 * Wiederherstellen, «App zurücksetzen») kann keine andere App etwas darüberlegen (Tapjacking). Gilt für das Fenster
 * der Aktivität; Dialoge liegen darüber, die Aktivität bleibt dabei sichtbar. Das eigene Preis-Overlay ist in dieser
 * Zeit ebenfalls ausgeblendet. Mehrere gleichzeitig aktive Stellen zählen ([OverlayShield]); erst die letzte hebt auf.
 */
@Composable
fun HideOverlaysEffect(active: Boolean = true) {
    val activity = LocalContext.current.findFragmentActivity()
    DisposableEffect(active, activity) {
        val window = activity?.window
        if (active && window != null) OverlayShield.acquire(window)
        onDispose {
            if (active && window != null) OverlayShield.release(window)
        }
    }
}

/** Zählt je Fenster, wie viele Stellen das Ausblenden verlangen (nur Hauptthread, wie alle Effekte). */
internal object OverlayShield {
    private val holds = WeakHashMap<Window, Int>()

    fun acquire(window: Window) = set(window, (holds[window] ?: 0) + 1)

    fun release(window: Window) = set(window, (holds[window] ?: 0) - 1)

    private fun set(window: Window, count: Int) {
        if (count > 0) holds[window] = count else holds.remove(window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(PortfolioLockPolicy.hideOverlayWindows(holders = count, sdkInt = Build.VERSION.SDK_INT))
        }
    }
}
