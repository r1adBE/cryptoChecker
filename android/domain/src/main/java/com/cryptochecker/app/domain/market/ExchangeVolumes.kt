package com.cryptochecker.app.domain.market

import kotlinx.coroutines.flow.StateFlow

/** Volumen der Börsen für die Reihenfolge der Börsenauswahl (Umsetzung: `ExchangeRankingStore`). */
interface ExchangeVolumes {
    /** Börsen-Schlüssel → Volumen (BTC); null = noch kein Ranking gespeichert. */
    val volumes: StateFlow<Map<String, Double>?>

    /** Holt das Ranking, falls keins gespeichert ist und in dieser Sitzung noch nicht versucht wurde. */
    suspend fun ensureLoaded()
}
