package com.cryptochecker.app.domain.portfolio

/**
 * Guthaben fällt auf 0: Eine einzige Antwort «0» — oft «leer, aber gültig» (TON-Jettons `[]`, TRON `{}`,
 * Koios `[]`, XRP `actNotFound`, Solana ohne Token-Konto) — gilt erst, wenn der NÄCHSTE Abruf sie bestätigt. Bis dahin
 * bleibt der alte Wert, der Schlüssel steht in [WatchedAddress.zeroPending] (Token zusätzlich in
 * [WatchedAddress.staleTokens]): Anzeige «nicht bestätigt», Portfolio-Alarme warten wie bei einem alten Kurs
 * ([AddressAlarmState]). Gleiche Regel für jede Kette und jeden Schlüssel; [MAIN] = Guthaben der Kette selbst.
 * Wie iOS `ZeroConfirm` (Fälle in `address_balance.json`, «zeroConfirm»).
 */
object ZeroConfirm {
    /** Schlüssel des Ketten-Guthabens ([WatchedAddress.lastBalance]) in [WatchedAddress.zeroPending]. */
    const val MAIN = "*"

    /** Zu übernehmendes Guthaben, Token und die Schlüssel, deren 0 noch auf Bestätigung wartet. */
    data class Result(val balance: String, val tokens: Map<String, String>?, val pending: List<String>)

    fun apply(a: WatchedAddress, balance: String, tokens: Map<String, String>?): Result {
        val pending = ArrayList<String>()
        val main = if (holds(a, MAIN, a.lastBalance, balance)) {
            pending += MAIN
            a.lastBalance ?: balance
        } else {
            balance
        }
        val checked = tokens?.let { map ->
            val out = LinkedHashMap<String, String>()
            for (key in map.keys.sorted()) {
                val text = map.getValue(key)
                val old = a.tokenBalances[key]
                if (old != null && holds(a, key, old, text)) {
                    pending += key
                    out[key] = old
                } else {
                    out[key] = text
                }
            }
            out
        }
        // Noch wartende Token, die dieser Abruf nicht gelesen hat (Token-Abfrage gescheitert): warten weiter
        for (key in a.zeroPending) if (key != MAIN && tokens?.containsKey(key) != true && key !in pending) pending += key
        return Result(main, checked, pending)
    }

    /** Neu 0, vorher > 0 und noch nicht einmal gemeldet: alten Wert halten. */
    private fun holds(a: WatchedAddress, key: String, old: String?, new: String): Boolean {
        val next = PortfolioDecimal.parse(new) ?: return false
        val before = PortfolioDecimal.parse(old) ?: return false
        return next.signum() == 0 && before.signum() > 0 && key !in a.zeroPending
    }
}
