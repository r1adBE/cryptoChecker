// Countdown im Status der Merkliste, solange die Aktualisierung aus dem Widget auf ihren nächsten Versuch wartet
// (Datenbank-Schlüssel noch nicht lesbar, [RetryWait]): Sekunden zählen, Kurzform, einmalige Ansage.
// Eigene Datei, damit `WatchlistStatusPill` nur den Text wählt.
package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.refresh.RetryWait
import kotlinx.coroutines.delay

/**
 * Sekunden bis [retryAt] (0 = keiner bzw. vorbei), einmal je Sekunde genau auf der Sekundengrenze neu —
 * nur solange ein Countdown läuft.
 */
@Composable
internal fun rememberRetrySeconds(retryAt: Long?): Int {
    var seconds by remember(retryAt) {
        mutableIntStateOf(RetryWait.remainingSeconds(retryAt, System.currentTimeMillis()))
    }
    LaunchedEffect(retryAt) {
        if (retryAt == null) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            seconds = RetryWait.remainingSeconds(retryAt, now)
            if (seconds == 0) break
            delay(RetryWait.millisUntilNextChange(retryAt, now))
        }
    }
    return seconds
}

/** Kurzform bei Platzmangel: «Neuer Versuch in 27 s» (gleicher Text wie im Widget). */
@Composable
internal fun retryShortText(seconds: Int): String =
    stringResource(R.string.widget_retry_countdown, stringResource(R.string.settings_seconds, seconds))

/**
 * Sagt [spoken] einmal an, sobald der Countdown erscheint ([waiting] wird true) — nicht bei jeder Sekunde; den
 * aktuellen Stand liest der Screenreader, wenn er den Status erreicht.
 */
@Composable
internal fun AnnounceRetry(waiting: Boolean, spoken: String) {
    val view = LocalView.current
    val text by rememberUpdatedState(spoken)
    LaunchedEffect(waiting) {
        if (!waiting) return@LaunchedEffect
        // Compose kennt keine eigene Ansage; eine Live-Region spräche jede Sekunde
        @Suppress("DEPRECATION")
        view.announceForAccessibility(text)
    }
}
