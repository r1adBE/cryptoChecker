package com.cryptochecker.app.domain.watch

import kotlinx.coroutines.flow.StateFlow

/** Watch-Ids, deren letzter Kurs der Mark-Preis statt des Mittelkurses war (Umsetzung: `MarkFallbackStore`). */
interface MarkFallbackIds {
    val ids: StateFlow<Set<Long>>
}

/** Watch-Ids, deren letzter Kurs ein Stundenkurs war (Umsetzung: `HourlyCloseStore`). */
interface HourlyCloseIds {
    val ids: StateFlow<Set<Long>>
}
