package com.cryptochecker.app.tts

/**
 * Warteschlange der automatischen Ansagen (Kurs-Aktualisierung). Reines Kotlin,
 * nicht threadsicher — [TtsSpeaker] greift nur unter einer Sperre zu.
 *
 * - Alarme werden nie verworfen und kommen vor wartenden Kursansagen, untereinander
 *   in der Reihenfolge des Eintreffens (mehrere Alarme eines Durchlaufs: alle hörbar).
 * - Kursansagen werden je Paar zusammengefasst: Von einem Paar wartet höchstens die
 *   neueste Ansage (der ältere, überholte Kurs fällt weg) — so stauen sich im
 *   Live-Modus keine Ansagen auf.
 */
class AnnouncementQueue {

    enum class Kind { ALARM, PRICE }

    data class Item(
        val text: String,
        val speechRate: Float,
        val kind: Kind,
        /** Paar (Watch-Id) für Kursansagen; null bei Alarmen. */
        val key: Long? = null,
    )

    private val alarms = ArrayDeque<Item>()

    /** Reihenfolge des (letzten) Eintreffens; je Paar höchstens ein Eintrag. */
    private val prices = LinkedHashMap<Long, Item>()

    private var anonymousKey = Long.MIN_VALUE / 2

    val size: Int get() = alarms.size + prices.size

    fun isEmpty(): Boolean = size == 0

    /** @return true, wenn dabei eine ältere Kursansage desselben Paars ersetzt wurde. */
    fun offer(item: Item): Boolean {
        if (item.kind == Kind.ALARM) {
            alarms.addLast(item)
            return false
        }
        // Kursansage ohne Paar: nicht zusammenfassen (eigener, nie wiederkehrender Schlüssel)
        val key = item.key ?: --anonymousKey
        // Entfernen und neu anhängen: Der neue Kurs reiht sich hinten ein
        val replaced = prices.remove(key) != null
        prices[key] = item
        return replaced
    }

    /** Nächste Ansage: zuerst Alarme, dann Kursansagen; null, wenn leer. */
    fun poll(): Item? {
        alarms.removeFirstOrNull()?.let { return it }
        val first = prices.entries.firstOrNull() ?: return null
        prices.remove(first.key)
        return first.value
    }

    fun clear() {
        alarms.clear()
        prices.clear()
    }
}
