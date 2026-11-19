package com.cryptochecker.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Kopfzeile mit dem Ladebalken ganz oben (gleich unter der Statusleiste, über dem Titel) — an derselben Stelle wie in
 * der Merkliste, deren Titel im Inhalt steht. Für Markt und Portfolio (Titel in der [topBar]).
 */
@Composable
fun LoadTopBar(progress: Float?, reduceMotion: Boolean, topBar: @Composable () -> Unit) {
    Box {
        topBar()
        LoadProgressBar(
            progress,
            reduceMotion,
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
        )
    }
}
