package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.data.local.model.WatchEntity

/** Paar wird an seiner Börse nicht mehr gehandelt ([NotTraded]). */
val WatchEntity.isNotTraded: Boolean get() = NotTraded.isMarker(lastError)

/** 24-h-Veränderung zum Anzeigen: null («—») bei nicht gehandelten Paaren. */
val WatchEntity.shownChange24h: Double? get() = NotTraded.shownChange24h(lastError, change24h)
