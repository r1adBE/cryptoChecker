// Entscheidungen der Mitteilungs-Zustellung ohne Android-Typen: Platz für einen Alarm, Zusammenfassung der Gruppe,
// Sammelmeldung «+N weitere», Kanalwahl und das Aufteilen der offenen Mitteilungen. Die Notifier-Dateien
// (`AppNotifierDelivery.kt`, `AppNotifierPrices.kt`, `AppNotifierAlarms.kt`, `AppNotifierMarket.kt`) lesen den
// Systemdienst, fragen hier nach dem Plan und melden bzw. entfernen danach. Reines Kotlin, per JVM-Test geprüft.
package com.cryptochecker.app.notification

object AppNotifierDeliveryPlan {

    // ---------------- Platz für einen Alarm ----------------

    /** Offene Mitteilungen aus Sicht eines neuen Alarms (aus `AppNotifier.ActiveState`). */
    data class AlarmRoomInput(
        /** Alle offenen Mitteilungen der App. */
        val total: Int,
        /** Die Mitteilung des neuen Alarms steht schon da (gleiche Id, wird ersetzt). */
        val alarmShown: Boolean,
        /** Die Zusammenfassung der Alarme steht schon da. */
        val summaryShown: Boolean,
        /** Einzelne Kurs-Mitteilungen: Paar → Titel, neueste zuerst. */
        val prices: List<Pair<Long, String>>,
        /** Paare der Sammelmeldung → Zeile, neueste zuerst. */
        val overflow: Map<Long, String>,
        val overflowShown: Boolean,
        /** Aktivitäts-Meldungen, älteste zuerst. */
        val activity: List<Int>,
        /** Alarm-Meldungen (Paar- und Portfolio-Alarme), älteste zuerst. */
        val alarms: List<Int>,
        /** Mitteilungs-Nummer des neuen Alarms. */
        val newId: Int,
    )

    /** Was für einen Alarm weichen muss — in dieser Reihenfolge ausgeführt. */
    data class AlarmRoomPlan(
        /** Kurs-Mitteilungen (Paar-ids), die einzeln entfernt werden — die ältesten. */
        val demote: List<Long>,
        /** Nicht null: Sammelmeldung mit diesen Zeilen neu melden (verschobene Kurse plus bisherige Zeilen). */
        val overflowLines: LinkedHashMap<Long, String>?,
        /** Aktivitäts-Meldungen, die entfernt werden (älteste zuerst). */
        val cancelActivity: List<Int>,
        /** Sammelmeldung ganz entfernen. */
        val removeOverflow: Boolean,
        /** Älteste Alarm-Meldungen, die dem neuen Alarm weichen. */
        val evictAlarms: List<Int>,
    )

    /**
     * Alarme haben Vorrang: Nahe an der Grenze von Android (rund 50 offene Mitteilungen je App) wandern zuerst
     * Kurs-Mitteilungen (die ältesten) in die Sammelmeldung, danach weichen Aktivitäts-Meldungen, die Sammelmeldung
     * selbst und zuletzt die ältesten Alarm-Meldungen. null = es ist genug Platz, nichts tun.
     */
    fun alarmRoom(input: AlarmRoomInput): AlarmRoomPlan? {
        val need = NotificationBudget.neededForAlarm(input.total, input.alarmShown, input.summaryShown)
        if (need == 0) return null
        val room = NotificationBudget.roomForAlarm(need, input.prices.size, input.overflowShown)
        val moved = if (room.demote > 0) input.prices.takeLast(room.demote) else emptyList()
        val lines = if (room.demote > 0 && room.merge) mergeOverflow(moved, input.overflow) else null
        var remaining = room.remaining
        val activity = ArrayList<Int>()
        for (activityId in input.activity) {
            if (remaining <= 0) break
            activity += activityId
            remaining--
        }
        val removeOverflow = remaining > 0 && input.overflowShown
        if (removeOverflow) remaining--
        // Zuletzt die ältesten Alarm-Meldungen: sonst verwarf Android ab rund 48 offenen Alarmen jeden neuen still,
        // er blieb unbestätigt und meldete sich endlos alle paar Minuten erneut
        val evicted = NotificationBudget.alarmsToEvict(remaining, input.alarms, input.newId)
        return AlarmRoomPlan(moved.map { it.first }, lines, activity, removeOverflow, evicted)
    }

    /**
     * Zeilen der Sammelmeldung, wenn Kurs-Mitteilungen [moved] hineinwandern: zuerst die verschobenen (ohne leere
     * Titel), danach die bisherigen [previous] — ein Paar, das schon drin steht, behält seinen Platz und bekommt die
     * bisherige Zeile.
     */
    fun mergeOverflow(moved: List<Pair<Long, String>>, previous: Map<Long, String>): LinkedHashMap<Long, String> {
        val lines = LinkedHashMap<Long, String>()
        moved.filter { it.second.isNotEmpty() }.forEach { lines[it.first] = it.second }
        lines.putAll(previous)
        return lines
    }

    // ---------------- Sammelmeldung ----------------

    /** Zeilen im aufgeklappten Text der Sammelmeldung: die ersten [NotificationBudget.OVERFLOW_LINES], danach «…». */
    fun overflowInboxLines(lines: List<String>): List<String> {
        val shown = lines.take(NotificationBudget.OVERFLOW_LINES)
        return if (lines.size > NotificationBudget.OVERFLOW_LINES) shown + "…" else shown
    }

    /**
     * Zeilen der Sammelmeldung für die Paare [overflow] (Reihenfolge bleibt): ohne [cancelled]; frischer Kurs
     * ([fresh]), sonst die bisherige Zeile aus der Sammelmeldung bzw. der verschobenen Einzelmeldung; Paare ohne
     * Zeile fallen weg.
     */
    fun overflowLines(
        overflow: List<Long>,
        cancelled: Set<Long>,
        previousOverflow: Map<Long, String>?,
        previousPrices: Map<Long, String>?,
        fresh: (Long) -> String?,
    ): LinkedHashMap<Long, String> {
        val lines = LinkedHashMap<Long, String>()
        for (id in overflow) {
            if (id in cancelled) continue
            val line = fresh(id) ?: previousOverflow?.get(id) ?: previousPrices?.get(id)
            if (!line.isNullOrEmpty()) lines[id] = line
        }
        return lines
    }

    // ---------------- Gruppen-Zusammenfassung ----------------

    /** Eine offene Mitteilung, so weit es die Gruppen betrifft. */
    data class GroupEntry(val id: Int, val group: String?, val summary: Boolean, val ongoing: Boolean)

    /** [children] = offene Mitteilungen der Gruppe ohne Zusammenfassung; [summaryOngoing] null = keine. */
    data class GroupCount(val children: Int, val summaryOngoing: Boolean?)

    /** Mitteilungen der Gruppe [groupKey] zählen — ohne die Zusammenfassung [summaryId] und ohne [exclude]. */
    fun groupCount(entries: List<GroupEntry>, groupKey: String, summaryId: Int, exclude: Set<Int>): GroupCount {
        var children = 0
        var summaryOngoing: Boolean? = null
        for (e in entries) {
            when {
                e.id == summaryId -> summaryOngoing = e.ongoing
                e.id !in exclude && e.group == groupKey && !e.summary -> children++
            }
        }
        return GroupCount(children, summaryOngoing)
    }

    /**
     * Nach einer Meldung ([ongoing] = dauerhaft) die Zusammenfassung (neu) melden? Ab der zweiten Mitteilung der
     * Gruppe, nur wenn sie fehlt bzw. «dauerhaft» wechselt — Android drosselt zu viele Meldungen je Sekunde.
     */
    fun repostSummary(othersInGroup: GroupCount, ongoing: Boolean): Boolean =
        othersInGroup.children >= 1 && othersInGroup.summaryOngoing != ongoing

    /** Zusammenfassung entfernen? Nur, wenn sicher keine Mitteilung der Gruppe mehr offen ist (null = unbekannt). */
    fun cancelSummary(childrenLeft: Int?): Boolean = childrenLeft == 0

    // ---------------- Kanal ----------------

    enum class Channel {
        /** Lautloser Alarm-Kanal (Nachtruhe). */
        QUIET_ALARM,
        /** Kanal des gewählten «Alarm-Signals» (Kursalarme, Portfolio-Alarme, Depeg). */
        ALARM_SIGNAL,
        /** Markt-Kanal (Aktivität, Fear & Greed, Kennzahlen, Gebühren, Wirtschaftstermine). */
        MARKET,
    }

    /** Kanal einer Alarm-Meldung: in der Nachtruhe der lautlose, sonst der des «Alarm-Signals». */
    fun alarmChannel(quiet: Boolean): Channel = if (quiet) Channel.QUIET_ALARM else Channel.ALARM_SIGNAL

    /** Kanal einer Markt-Meldung: in der Nachtruhe der lautlose, sonst der Markt-Kanal. */
    fun marketChannel(quiet: Boolean): Channel = if (quiet) Channel.QUIET_ALARM else Channel.MARKET

    /**
     * Käme eine Meldung über den Kanal an? Kanal noch nicht angelegt ([importance] null) = ja; sonst nur, wenn er
     * nicht stummgeschaltet ist ([none] = `NotificationManager.IMPORTANCE_NONE`).
     */
    fun channelDelivers(importance: Int?, none: Int): Boolean = importance == null || importance != none

    // ---------------- Offene Mitteilungen aufteilen ----------------

    /** Eine offene Mitteilung, so weit es das Mitteilungs-Budget betrifft. */
    data class ActiveEntry(
        val id: Int,
        val postTime: Long,
        /** Gehört zur Gruppe der Kurs-Mitteilungen und ist keine Zusammenfassung. */
        val priceChild: Boolean,
        /** Nur bei [priceChild]: Paar der Kurs-Mitteilung (null = nicht erkennbar). */
        val watchId: Long? = null,
        /** Nur bei [priceChild]: Titel der Kurs-Mitteilung. */
        val title: String = "",
    )

    data class ActiveSplit(
        /** Vom System geführte einzelne Kurs-Mitteilungen: Paar → Titel, neueste zuerst. */
        val listed: LinkedHashMap<Long, String>,
        /** Die Sammelmeldung steht unter den offenen. */
        val overflowListed: Boolean,
        /** Alle anderen offenen Mitteilungen (ohne Kurs-Gruppe). */
        val other: Int,
        /** Aktivitäts-Meldungen, älteste zuerst. */
        val activity: List<Int>,
        /** Alarm-Meldungen (Paar- und Portfolio-Alarme), älteste zuerst. */
        val alarms: List<Int>,
    )

    /** Offene Mitteilungen nach Art aufteilen ([priceSummaryId] und [overflowId] zählen nicht als «andere»). */
    fun splitActive(entries: List<ActiveEntry>, priceSummaryId: Int, overflowId: Int): ActiveSplit {
        val listed = LinkedHashMap<Long, String>()
        var other = 0
        var overflowListed = false
        val activity = ArrayList<ActiveEntry>()
        val alarms = ArrayList<ActiveEntry>()
        for (e in entries.sortedByDescending { it.postTime }) {
            when {
                e.id == overflowId -> overflowListed = true
                e.id == priceSummaryId -> Unit
                e.priceChild -> {
                    val watchId = e.watchId
                    if (watchId == null) other++ else listed[watchId] = e.title
                }
                else -> {
                    other++
                    if (NotificationIds.isKind(NotificationIds.Kind.ACTIVITY, e.id)) activity += e
                    if (NotificationIds.isKind(NotificationIds.Kind.ALARM, e.id) ||
                        NotificationIds.isKind(NotificationIds.Kind.PORTFOLIO_ALARM, e.id)
                    ) alarms += e
                }
            }
        }
        return ActiveSplit(
            listed = listed,
            overflowListed = overflowListed,
            other = other,
            activity = activity.sortedBy { it.postTime }.map { it.id },
            alarms = alarms.sortedBy { it.postTime }.map { it.id },
        )
    }

    /**
     * Einzelne Kurs-Mitteilungen in Vorrang-Reihenfolge: eben gemeldete ([recent]: Paar → Zeit, Titel), die der
     * Systemdienst noch nicht führt, zuerst (neueste zuerst), danach die geführten [listed].
     */
    fun orderPrices(recent: Map<Long, Pair<Long, String>>, listed: Map<Long, String>): LinkedHashMap<Long, String> {
        val prices = LinkedHashMap<Long, String>()
        recent.entries.sortedByDescending { it.value.first }
            .filter { it.key !in listed }
            .forEach { prices[it.key] = it.value.second }
        prices.putAll(listed)
        return prices
    }

    /**
     * Gilt die Sammelmeldung als offen? Vom System geführt oder eben erst gemeldet ([cached], gemeldet um
     * [postedAt], noch keine [recentMillis] her) — der Systemdienst führt sie evtl. noch nicht.
     */
    fun overflowShown(listed: Boolean, cached: Boolean, now: Long, postedAt: Long, recentMillis: Long): Boolean =
        listed || (cached && now - postedAt in 0 until recentMillis)
}
