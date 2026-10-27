package com.cryptochecker.app.notification

import kotlin.math.max
import kotlin.math.min

/**
 * Wie viele Mitteilungen die App gleichzeitig zeigt. Android verwirft ab rund
 * [SYSTEM_LIMIT] offenen Mitteilungen je App jede weitere still — auch Alarme. Darum
 * (Entscheid Oktober 2026): höchstens [MAX_PRICE] Kurs-Mitteilungen einzeln, der Rest in EINER
 * Sammelmeldung («+N weitere Kurse»); Alarme haben Vorrang — für sie wandern zuerst
 * Kurs-Mitteilungen in die Sammelmeldung. Reines Kotlin.
 */
object NotificationBudget {

    /** Offene Mitteilungen je App, ab denen Android neue still verwirft. */
    const val SYSTEM_LIMIT = 50

    /** Bis hierhin dürfen Alarme auffüllen (etwas Abstand für gleichzeitige Meldungen). */
    const val ALARM_LIMIT = SYSTEM_LIMIT - 2

    /** Höchstens so viele Kurs-Mitteilungen einzeln. */
    const val MAX_PRICE = 20

    /** So viele Plätze lassen Kurs-Mitteilungen immer für Alarme frei. */
    const val ALARM_RESERVE = 10

    /** Sammelmeldung und Gruppen-Zusammenfassung der Kurs-Mitteilungen. */
    private const val PRICE_EXTRAS = 2

    /** Zeilen der Sammelmeldung (danach «…»). */
    const val OVERFLOW_LINES = 5

    /** Nicht bestätigter Alarm (nicht unter den offenen Mitteilungen): frühestens so spät erneut melden. */
    const val MIN_RETRY_MINUTES = 5

    /**
     * Plätze für einzelne Kurs-Mitteilungen, wenn [otherActive] andere Mitteilungen offen sind
     * (ohne Kurs-Mitteilungen, Sammelmeldung und Zusammenfassung der Kurs-Gruppe).
     */
    fun priceSlots(otherActive: Int): Int =
        (SYSTEM_LIMIT - ALARM_RESERVE - PRICE_EXTRAS - max(0, otherActive)).coerceIn(0, MAX_PRICE)

    data class PricePlan(
        /** Einzeln zeigen bzw. aktualisieren (Paar-ids, Reihenfolge der Meldungen). */
        val individual: List<Long>,
        /** In der Sammelmeldung, neueste zuerst. */
        val overflow: List<Long>,
        /** Bisher einzeln gezeigt, jetzt in der Sammelmeldung: einzeln entfernen. */
        val demoted: List<Long>,
    )

    /**
     * Verteilung der Kurs-Mitteilungen. [shown] = einzeln offen (wichtigste/neueste zuerst; behalten
     * ihren Platz), [overflowBefore] = bisher in der Sammelmeldung, [posts] = jetzt zu melden,
     * [slots] = [priceSlots]. Neue Meldungen bekommen freie Plätze, sonst die Sammelmeldung; ein
     * Paar der Sammelmeldung kommt wieder einzeln, sobald es neu gemeldet wird und Platz frei ist.
     */
    fun planPrices(shown: List<Long>, overflowBefore: List<Long>, posts: List<Long>, slots: Int): PricePlan {
        val limit = max(0, slots)
        val open = shown.distinct()
        val keep = open.take(limit).toHashSet()
        val demoted = open.drop(limit)
        var used = keep.size
        val individual = ArrayList<Long>()
        val newOverflow = ArrayList<Long>()
        for (id in posts.distinct()) {
            when {
                id in keep -> individual += id
                used < limit -> {
                    individual += id
                    used++
                }
                else -> newOverflow += id
            }
        }
        val single = keep + individual
        val overflow = (newOverflow + demoted + overflowBefore).distinct().filterNot { it in single }
        return PricePlan(individual, overflow, demoted)
    }

    /** Wie viele offene Mitteilungen müssen weichen, damit ein Alarm (und die Zusammenfassung der Alarme) Platz hat? */
    fun neededForAlarm(active: Int, alarmShown: Boolean, summaryShown: Boolean): Int =
        max(0, active + (if (alarmShown) 0 else 1) + (if (summaryShown) 0 else 1) - ALARM_LIMIT)

    data class AlarmRoom(
        /** So viele Kurs-Mitteilungen (unwichtigste zuerst) einzeln entfernen. */
        val demote: Int,
        /** true = in die Sammelmeldung übernehmen; false = ganz entfernen (eine neue Sammelmeldung bräuchte selbst einen Platz). */
        val merge: Boolean,
        /** Danach noch fehlende Plätze (andere, weniger wichtige Mitteilungen entfernen). */
        val remaining: Int,
    )

    /** Platz für einen Alarm: zuerst Kurs-Mitteilungen ([prices] einzeln offen) weichen lassen. */
    fun roomForAlarm(need: Int, prices: Int, overflowShown: Boolean): AlarmRoom {
        if (need <= 0) return AlarmRoom(0, true, 0)
        val available = max(0, prices)
        if (overflowShown) {
            val d = min(need, available)
            return AlarmRoom(d, true, need - d)
        }
        // Neue Sammelmeldung kostet selbst einen Platz: lohnt nur, wenn danach genug frei wird
        if (available > need) return AlarmRoom(need + 1, true, 0)
        return AlarmRoom(available, false, need - available)
    }

    /**
     * Fehlen nach Kurs-, Aktivitäts- und Sammelmeldung noch [remaining] Plätze: so viele der ältesten Alarm-Meldungen
     * ([alarmsOldestFirst], ohne die neue [newId]) entfernen. Ein neuer Alarm ist wichtiger als ein längst gesehener.
     */
    fun alarmsToEvict(remaining: Int, alarmsOldestFirst: List<Int>, newId: Int): List<Int> =
        if (remaining <= 0) emptyList() else alarmsOldestFirst.filter { it != newId }.take(remaining)

    /**
     * Alarm wurde gemeldet, stand danach aber nicht unter den offenen Mitteilungen: Er bleibt scharf,
     * meldet aber erst nach der Abklingzeit (mindestens [MIN_RETRY_MINUTES]) erneut — kein Dauerfeuer,
     * falls die Prüfung irrt.
     */
    fun retryBlocked(unconfirmedAt: Long?, now: Long, cooldownMinutes: Int): Boolean {
        if (unconfirmedAt == null || unconfirmedAt <= 0L) return false
        val wait = max(cooldownMinutes, MIN_RETRY_MINUTES) * 60_000L
        return now - unconfirmedAt in 0 until wait
    }

    /** Android verwirft mehr als rund so viele Aktualisierungen je Sekunde und App still. */
    const val POSTS_PER_SECOND = 5

    /** Abstand zwischen zwei Meldungen, wenn gedrosselt wird (etwas über 1 s / [POSTS_PER_SECOND]). */
    const val POST_GAP_MILLIS = 220L

    /**
     * Pause zwischen zwei Meldungen eines Stapels von [posts] Meldungen: bis [POSTS_PER_SECOND] ohne
     * Pause, darüber [POST_GAP_MILLIS] — sonst verwirft Android die überzähligen still, sie zählten
     * aber als gezeigt (und der Bezugskurs der Kurs-Mitteilung rückte vor).
     */
    fun postGapMillis(posts: Int): Long = if (posts > POSTS_PER_SECOND) POST_GAP_MILLIS else 0L

    /**
     * Ist die eben gemeldete Mitteilung [id] angekommen? [postTimes]: Meldezeit (StatusBarNotification.postTime)
     * je offener Mitteilung, null = nicht abfragbar (gilt als ja). Nur eine Meldezeit ab [postedAfter]
     * (Zeit direkt vor dem Melden) bestätigt die NEUE Meldung — sonst stünde eine ältere Meldung
     * desselben Alarms (gleiche Id) für eine verworfene neue.
     */
    fun confirmedPost(postTimes: Map<Int, Long>?, id: Int, postedAfter: Long): Boolean {
        if (postTimes == null) return true
        val postTime = postTimes[id] ?: return false
        return postTime >= postedAfter
    }
}
