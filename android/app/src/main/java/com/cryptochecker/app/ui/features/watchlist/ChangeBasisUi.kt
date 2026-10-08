package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.compositionLocalOf
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.shownChange24h

/**
 * «Basis der %-Änderung» der Merkliste ([ChangeView]: Basis und ob die gespeicherten Werte noch
 * passen); von der Merkliste bereitgestellt, Pille, Puls und Aktionsblatt lesen daraus.
 */
val LocalChangeView = compositionLocalOf { ChangeView() }

/** Veränderung zum Anzeigen gemäss [view]: null («—») bei nicht gehandelten Paaren oder veralteter Basis. */
fun WatchEntity.shownChange(view: ChangeView): Double? = view.shown(shownChange24h)
