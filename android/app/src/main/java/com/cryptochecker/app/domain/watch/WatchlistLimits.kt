package com.cryptochecker.app.domain.watch

/**
 * Obergrenzen der Merkliste überall in der App: höchstens [MAX_PAIRS] Paare und je Paar
 * [MAX_ALARMS_PER_PAIR] Alarme — beim Hinzufügen (einzeln, Sammelaufnahme, Start-Coins, DEX, Vorschau), beim Anlegen
 * von Alarmen (Editor, Schnell-Alarme) und beim Wiederherstellen einer Sicherung
 * ([com.cryptochecker.app.data.BackupRestoreLimits]). Mehr Paare machen jede Aktualisierung, die Widgets und die
 * Sicherung träge; mehr als zehn Alarme an einem Paar überblickt niemand. Portfolio-Alarme hängen an keinem Paar und
 * sind nicht begrenzt. «Rückgängig» nach dem Leeren bzw. Löschen holt den früheren Stand zurück (der lag schon unter
 * der Grenze) und prüft nicht. Gleich wie iOS `WatchlistLimits`.
 */
object WatchlistLimits {

    const val MAX_PAIRS = 2_000
    const val MAX_ALARMS_PER_PAIR = 10

    /** Darf zu [pairCount] Paaren noch eines dazukommen? */
    fun canAddPair(pairCount: Int): Boolean = pairCount < MAX_PAIRS

    /** Darf ein Paar mit [alarmCount] Alarmen (auch ausgeschalteten) noch einen neuen bekommen? */
    fun canAddAlarm(alarmCount: Int): Boolean = alarmCount < MAX_ALARMS_PER_PAIR

    /** Freie Plätze der Merkliste bei [pairCount] Paaren (nie negativ, auch wenn eine alte Liste darüber liegt). */
    fun freePairSlots(pairCount: Int): Int = (MAX_PAIRS - pairCount).coerceAtLeast(0)
}
