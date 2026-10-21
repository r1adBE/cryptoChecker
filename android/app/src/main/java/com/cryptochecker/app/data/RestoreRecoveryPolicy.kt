package com.cryptochecker.app.data

/**
 * Darf ein beim Start gefundenes Journal ([RestoreJournal]) noch zurückgerollt werden? Gespiegelt in
 * `RestoreRecoveryPolicy.swift`, gleiche Fälle auf beiden Seiten.
 *
 * Seit Build 93: Scheiterte das Zurückrollen, blieb das Journal liegen und jeder Start versuchte es erneut. Der Nutzer
 * arbeitet aber weiter — ein späteres, gelungenes Zurückrollen hätte Tage an Änderungen mit dem Stand vor dem
 * Wiederherstellen überschrieben, und der Hinweis «nicht vollständig» kam bei jedem Start. Darum genau ein weiterer
 * Versuch beim nächsten Start und nie mit einem Journal, das älter als [MAX_AGE_MS] ist; danach aufgeben (Journal
 * löschen, Hinweis «nicht zurückgeschrieben» einmal).
 */
object RestoreRecoveryPolicy {
    /** Älter: der Nutzer hat inzwischen sehr wahrscheinlich weitergearbeitet. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    enum class Decision { ROLL_BACK, GIVE_UP }

    /**
     * Zeitpunkt des Journals: [stored] aus dem Inhalt (seit Build 93), sonst die Änderungszeit der Datei [fileModified]
     * (Journale älterer Versionen; die Datei wird nur einmal geschrieben). ≤ 0 = unbekannt.
     */
    fun createdAt(stored: Long, fileModified: Long): Long = if (stored > 0) stored else fileModified

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
