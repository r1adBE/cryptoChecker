package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.runtime.compositionLocalOf
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeView

/**
 * «Basis der %-Änderung» der Merkliste ([ChangeView]: Basis und ob die gespeicherten Werte noch
 * passen); von der Merkliste bereitgestellt, Änderung, Puls und Aktionsblatt lesen daraus.
 */
val LocalChangeView = compositionLocalOf { ChangeView() }

/** Steht über den Paaren der Spaltenkopf «Name · Kurs · 24h» ([WatchlistSortBar]) mit dem Zeitraum der Änderung? */
internal val LocalChangePeriodInHeader = compositionLocalOf { false }

/**
 * Zeitraum an der Änderung einer Zeile: grundsätzlich immer, damit die Basis der Prozentzahl nie verborgen ist —
 * nur «seit letzt. Abruf» nicht, wenn der Spaltenkopf ihn schon zeigt (der lange Zeitraum stünde sonst in jeder
 * Zeile doppelt). «24h» und «heute» bleiben in der Zeile. Wie `WatchlistRowMarks.showsPeriod` (iOS); Fälle in
 * `testdata/parity/watchlist_row_marks.json`.
 */
internal fun rowShowsPeriod(basis: ChangeBasis, periodInHeader: Boolean): Boolean =
    !(basis.isSinceLast && periodInHeader)
