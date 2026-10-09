package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.compositionLocalOf
import com.cryptochecker.app.domain.watch.ChangeView

/**
 * «Basis der %-Änderung» der Merkliste ([ChangeView]: Basis und ob die gespeicherten Werte noch
 * passen); von der Merkliste bereitgestellt, Pille, Puls und Aktionsblatt lesen daraus.
 */
val LocalChangeView = compositionLocalOf { ChangeView() }
