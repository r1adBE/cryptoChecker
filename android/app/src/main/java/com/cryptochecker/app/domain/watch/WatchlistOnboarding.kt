package com.cryptochecker.app.domain.watch

/**
 * Merkliste nach der Start-Auswahl schrittweise: Der erste Bildschirm hat EINE Aufgabe — Kurse sehen und den ersten
 * Alarm setzen; die Karte «Ersten Alarm setzen» ([FirstAlarmCard]) bleibt der einzige hervorgehobene Hinweis.
 * Gruppen-Hinweis, Puls über der Liste und Gesten-Hinweis kommen erst dazu, sobald es einen Alarm gibt (auch einen
 * ausgeschalteten) oder die App an [UNLOCK_DAYS] verschiedenen Tagen offen war. Wer schon Alarme hat (Update,
 * Wiederherstellen), sieht also alles sofort. Sortierzeile und Gruppen-Chips bleiben immer (Grundbedienung bzw.
 * vom Nutzer selbst angelegt). Reine Regel, Swift-Spiegel `WatchlistOnboarding.swift` (gleiche Testfälle in
 * WatchlistOnboardingTest / WatchlistOnboardingTests).
 */
object WatchlistOnboarding {
    /** Ab so vielen Tagen mit offener App zeigt die Merkliste alles, auch ohne Alarm. */
    const val UNLOCK_DAYS = 3

    /** Was die Merkliste über die Grundbedienung hinaus zeigen darf. */
    data class Visible(
        /** Grauer Hinweis «Hier erscheinen deine Gruppen» in der leeren Chip-Zeile. */
        val groupsHint: Boolean,
        /** Puls («↗ 7 steigen · ↘ 3 fallen · Ø …») über der Liste. */
        val pulse: Boolean,
        /** Gesten-Hinweis (Wischen, langes Drücken). */
        val gestureHint: Boolean,
    )

    /**
     * @param alarmCount alle Alarme wie bei [FirstAlarmCard] (auch ausgeschaltete); null = noch nicht geladen → wie
     *   keiner, damit beim ersten Start nichts kurz aufblitzt
     * @param usageDays verschiedene Tage, an denen die App offen war ([UsageDays])
     * @param hasGroupsOrFavorites eigene Gruppen oder Favoriten: dann stehen dort die Chips, kein Hinweis
     */
    fun visible(alarmCount: Int?, usageDays: Int, hasGroupsOrFavorites: Boolean): Visible {
        val unlocked = (alarmCount ?: 0) > 0 || usageDays >= UNLOCK_DAYS
        return Visible(
            groupsHint = unlocked && !hasGroupsOrFavorites,
            pulse = unlocked,
            gestureHint = unlocked,
        )
    }
}

/**
 * Zähler der verschiedenen Tage mit offener App: [count] Tage, zuletzt am lokalen Epoch-Tag [lastDay] (null = nie).
 * Nur auf diesem Gerät, nicht in der Sicherung. Swift-Spiegel `UsageDays` in `WatchlistOnboarding.swift`.
 */
data class UsageDays(val count: Int, val lastDay: Long?) {
    /**
     * Stand nach einem Öffnen am Tag [today]: ein neuer, späterer Tag zählt einmal; derselbe Tag nicht. Liegt
     * [today] vor [lastDay] (Uhr zurückgestellt, Zeitzone), wird nur der Tag übernommen — sonst zählte ein
     * Hin und Her der Uhr mehrfach.
     */
    fun next(today: Long): UsageDays {
        val last = lastDay ?: return UsageDays((count + 1).coerceIn(1, MAX_COUNT), today)
        return when {
            today > last -> UsageDays((count + 1).coerceIn(1, MAX_COUNT), today)
            today < last -> copy(lastDay = today)
            else -> this
        }
    }

    companion object {
        /** Obergrenze, damit der gespeicherte Wert nie überläuft (für die Regel zählt nur «mindestens 3»). */
        const val MAX_COUNT = 9_999
    }
}
