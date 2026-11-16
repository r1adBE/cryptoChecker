package com.cryptochecker.app.domain.wearable

/**
 * «Auf der Uhr zeigen»: reine Regeln ohne Android, damit sie ohne Gerät testbar sind (`WatchNotifyPolicyTest`).
 *
 * Die Mitteilung des Handys bleibt nur dann allein auf dem Handy («nur lokal»), wenn die Uhr-App sie sicher selbst
 * zeigen kann: eine Uhr mit der App war zuletzt erreichbar UND sie hat gemeldet, dass sie Mitteilungen zeigen darf.
 * Sonst übernimmt die Weiterleitung des Systems wie ohne Schalter – so geht kein Alarm verloren, wenn die Uhr ausser
 * Reichweite ist, die App fehlt oder die Erlaubnis verweigert wurde.
 */
object WatchNotifyPolicy {

    /** Uhr-App übernimmt? null = noch nichts gemeldet (zählt wie nein). */
    fun ready(enabled: Boolean, reachable: Boolean?, permitted: Boolean?): Boolean =
        enabled && reachable == true && permitted == true

    /** Sichtbarkeit der Mitteilung auf dem Handy, wie `Notification.VISIBILITY_*`. */
    enum class Visibility { PUBLIC, PRIVATE, SECRET }

    /** Was an die Uhr geht: Titel und Text, gekürzt; Alarme tippen ans Handgelenk. */
    data class Payload(val title: String, val text: String, val alarm: Boolean)

    /**
     * Inhalt für die Uhr wie auf dem Sperrbildschirm: geheim → nichts, privat mit öffentlicher Fassung → nur diese.
     * Ohne Titel und Text nichts. [full] und [public] sind (Titel, Text) der Mitteilung bzw. ihrer öffentlichen Fassung.
     */
    fun payload(
        visibility: Visibility,
        full: Pair<String?, String?>,
        public: Pair<String?, String?>?,
        alarm: Boolean,
    ): Payload? {
        if (visibility == Visibility.SECRET) return null
        val source = public?.takeIf { visibility == Visibility.PRIVATE } ?: full
        val title = source.first.orEmpty()
        val text = source.second.orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        return Payload(title.take(MAX_TITLE), text.take(MAX_TEXT), alarm)
    }

    const val MAX_TITLE = 120
    const val MAX_TEXT = 600
}
