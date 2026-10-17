package com.cryptochecker.app.domain.live

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wann kam für ein Paar (Watch-Id) zuletzt ein Kurs aus dem Datenstrom? Schreibt
 * `LivePriceStream`, liest `PriceRefresher`: Paare mit frischem Live-Kurs lässt die
 * REST-Abfrage aus ([LiveRules.skipRest]) — nur solange die Merkliste offen ist.
 */
@Singleton
class LiveCoverage @Inject constructor() {
    private val lastTickAt = ConcurrentHashMap<Long, Long>()

    fun mark(watchIds: Collection<Long>, now: Long) {
        for (id in watchIds) lastTickAt[id] = now
    }

    fun lastTickAt(watchId: Long): Long? = lastTickAt[watchId]

    fun clear() = lastTickAt.clear()
}
