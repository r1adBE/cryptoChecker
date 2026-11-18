package com.cryptochecker.app.domain.live

import kotlinx.coroutines.flow.StateFlow

/** Live-Kurse der angezeigten Merkliste (Umsetzung: `LivePriceStream`). */
interface LivePrices {
    /** Letzter Live-Kurs je Watch-Id. */
    val prices: StateFlow<Map<Long, LiveQuote>>

    /** Namen der Börsen, deren Strom gerade Kurse liefert. */
    val liveExchanges: StateFlow<List<String>>

    /** Paare, die gerade live laufen. */
    val liveWatchIds: StateFlow<Set<Long>>

    /** Paare der angezeigten Merkliste (gewählte Gruppe). */
    fun setPairs(pairs: List<LivePair>)

    /** Merkliste zu sehen bzw. nicht mehr. */
    fun setVisible(visible: Boolean)
}
