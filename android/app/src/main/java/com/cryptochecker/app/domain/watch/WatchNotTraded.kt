package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.data.local.model.WatchEntity

/** Paar wird an seiner Börse nicht mehr gehandelt ([NotTraded]). */
val WatchEntity.isNotTraded: Boolean get() = NotTraded.isMarker(lastError)

/** 24-h-Veränderung zum Anzeigen: null («—») bei nicht gehandelten Paaren. */
val WatchEntity.shownChange24h: Double? get() = NotTraded.shownChange24h(lastError, change24h)

/**
 * Veränderung zum Anzeigen gemäss [view] (Pille, Puls, Aktionsblatt, Widgets): null («—») bei
 * nicht gehandelten Paaren oder veralteter Basis; «Seit letzter Aktualisierung» aus letztem und
 * vorherigem Kurs.
 */
fun WatchEntity.shownChange(view: ChangeView): Double? =
    view.shown(change24h, lastPrice, previousPrice, traded = !isNotTraded)
