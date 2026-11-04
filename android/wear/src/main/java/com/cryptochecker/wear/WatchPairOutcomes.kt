package com.cryptochecker.wear

import java.util.concurrent.ConcurrentHashMap

/**
 * Ergebnis des letzten eigenen Abrufs je Paar eines Favoriten ([WatchPairQuotes.Outcome]) — nur im Speicher, für den
 * Grund bei «—» ([missingReason]). Wie iOS `WatchPairOutcomes`.
 */
object WatchPairOutcomes {
    private val outcomes = ConcurrentHashMap<String, WatchPairQuotes.Outcome>()

    fun record(key: String, outcome: WatchPairQuotes.Outcome) {
        outcomes[key] = outcome
    }

    operator fun get(key: String): WatchPairQuotes.Outcome? = outcomes[key]
}
