package com.cryptochecker.app.data

/**
 * Darf ein beim Start gefundenes Journal ([RestoreJournal]) noch zurückgerollt werden? Gespiegelt in
 * `RestoreRecoveryPolicy.swift`, gleiche Fälle auf beiden Seiten.
 *
 * Scheiterte das Zurückrollen, blieb das Journal liegen und jeder Start versuchte es erneut. Der Nutzer
 * arbeitet aber weiter — ein späteres, gelungenes Zurückrollen hätte Tage an Änderungen mit dem Stand vor dem
 * Wiederherstellen überschrieben, und der Hinweis «nicht vollständig» kam bei jedem Start. Darum je Journal genau ein
 * Zurückrollen beim Start (nie mit einem Journal, das älter als [MAX_AGE_MS] ist); scheitert es, gibt der nächste Start
 * auf (Journal löschen, Hinweis «nicht zurückgeschrieben» einmal).
 */
object RestoreRecoveryPolicy {
    /** Älter: der Nutzer hat inzwischen sehr wahrscheinlich weitergearbeitet. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    enum class Decision { ROLL_BACK, GIVE_UP }

    /**
     * [attemptedFor] = Zeitpunkt des Journals, für das ein früherer Start schon zurückrollen wollte (gescheitert) oder
     * aufgab; null = noch keiner. Unbekanntes Alter: nicht zurückrollen — lieber den Stand stehen lassen als womöglich
     * neuere Änderungen überschreiben.
     */
    fun decide(createdAt: Long, attemptedFor: Long?, now: Long): Decision = when {
        createdAt <= 0 -> Decision.GIVE_UP
        attemptedFor == createdAt -> Decision.GIVE_UP
        now - createdAt > MAX_AGE_MS -> Decision.GIVE_UP
        else -> Decision.ROLL_BACK
    }

    /** Beim Aufgeben den Hinweis nur zeigen, wenn ihn nicht schon der gescheiterte Versuch gezeigt hat (einmal je Journal). */
    fun noticeOnGiveUp(createdAt: Long, attemptedFor: Long?): Boolean = attemptedFor != createdAt
}
