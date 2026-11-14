package com.cryptochecker.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.WatchlistWriteRefusal
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Kurzer Hinweis, wenn das Repository einen Schreibweg der Merkliste abgelehnt hat ([WatchlistWriteRefusal], gespeicherter
 * Stand gerade nicht lesbar): Warn-Haptik ([HapticEvent.WARNING]) und [show] mit dem Text
 * «watchlist_storage_locked» über die Snackbar bzw. das Banner des Bildschirms. Nur in Bildschirmen, die Paare oder
 * Alarme ändern (Merkliste, Paar hinzufügen, Alarme, «Jetzt»). Ein neuer Hinweis ersetzt einen noch laufenden.
 * Ebenso, wenn ein Schreibweg am Speicher scheiterte ([WatchlistWriteRefusal.failures], z. B. Speicher voll): Text
 * «portfolio_write_failed» («Konnte nicht gespeichert werden») statt Absturz — wie iOS.
 */
@Composable
fun WatchlistLockedHint(show: suspend (String) -> Unit) {
    val text = stringResource(R.string.watchlist_storage_locked)
    val failedText = stringResource(R.string.portfolio_write_failed)
    val haptics = rememberHaptics()
    val currentText by rememberUpdatedState(text)
    val currentFailedText by rememberUpdatedState(failedText)
    val currentHaptics by rememberUpdatedState(haptics)
    val currentShow by rememberUpdatedState(show)
    LaunchedEffect(Unit) {
        merge(WatchlistWriteRefusal.events.map { true }, WatchlistWriteRefusal.failures.map { false }).collectLatest { locked ->
            currentHaptics.play(HapticEvent.WARNING)
            currentShow(if (locked) currentText else currentFailedText)
        }
    }
}
