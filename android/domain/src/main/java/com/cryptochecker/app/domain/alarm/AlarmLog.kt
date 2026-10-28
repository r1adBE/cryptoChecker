package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.util.json.JsonObject
import com.cryptochecker.app.util.json.JsonArray
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.refresh.CheckTrigger

/**
 * Ergebnis einer Alarm-Prüfung im Alarm-Protokoll ([AlarmLog]); [code] steht in der Datei und im gemeinsamen Testfall
 * `testdata/parity/alarm_log.json` (gleich auf iOS, `AlarmLogOutcome`).
 */
enum class AlarmLogOutcome(val code: String) {
    /** Gemeldet und gespeichert. */
    FIRED("fired"),

    /** Bedingung nicht erfüllt (auch: Bezug eben gesetzt, Kursmarke wieder scharf gestellt). */
    NOT_REACHED("not_reached"),

    /** Kursmarke schon gemeldet: wartet, bis der Kurs auf die andere Seite zurück ist. */
    ALREADY_TRIGGERED("already_triggered"),

    /** Erfüllt, aber Ruhezeit nach der letzten Meldung (bzw. nach einer unbestätigten Meldung). */
    COOLDOWN("cooldown"),

    /** Kurs laut Börse älter als [AlarmFreshness.EXCHANGE_STALE_MILLIS]. */
    STALE("stale"),

    /** Nur der Mark-Preis statt eines Handelskurses. */
    MARK_PRICE("mark_price"),

    /** DEX-Pool unter der Alarm-Liquidität oder ohne Angabe ([com.cryptochecker.app.domain.watch.DexLiquidity]). */
    LOW_LIQUIDITY("low_liquidity"),

    /** Erfüllt, aber Nachtruhe: wartet bis zum Ende ([QuietHours.holdsAlarms]). */
    QUIET_HOURS("quiet_hours"),

    /** Erfüllt, aber «1 h später» aus der Mitteilung läuft noch ([AlarmSnooze]). */
    SNOOZED("snoozed"),

    /** Erfüllt, aber keine zustellbare Mitteilung (Erlaubnis, Mitteilungen oder Kanal aus). */
    NOTIFICATIONS_OFF("notifications_off"),

    /** Erfüllt und gemeldet, die Mitteilung kam aber nicht an (Neuversuch nach der Abklingzeit). */
    NOT_SHOWN("not_shown"),

    /** Schwelle in anderer Währung, aber kein Devisenkurs: diesmal nicht geprüft. */
    NO_RATE("no_rate"),

    /** Kein Kurs: Abfrage bei der Börse fehlgeschlagen. */
    NO_PRICE("no_price"),

    /** Schwelle in anderer Währung, Devisenkurs älter als 36 h Bankarbeitszeit: diesmal nicht geprüft (bleibt scharf). */
    FX_STALE("fx_stale"),

    /** Schwelle in anderer Währung, USDT/USD nur über USDC und USDC/USDT mehr als 1 % neben 1: diesmal nicht geprüft. */
    FX_UNSURE("fx_unsure"),
    ;

    companion object {
        fun fromCode(code: String?): AlarmLogOutcome? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Was die Prüfung eines Alarms an den bestehenden Entscheidungsstellen gesehen hat (nur beobachtet — die Entscheidung
 * selbst fällt unverändert in [AlarmEvaluator], [AlarmFreshness], [AlarmDelivery] usw.). [AlarmLog.outcome] macht daraus
 * genau ein Ergebnis; welche Stelle zuerst greift, legt allein diese Reihenfolge fest (beide Plattformen gleich).
 */
data class AlarmLogTrace(
    val noPrice: Boolean = false,
    val lowLiquidity: Boolean = false,
    val markPrice: Boolean = false,
    val stale: Boolean = false,
    val noRate: Boolean = false,
    /** Devisenkurs zu alt ([com.cryptochecker.app.domain.convert.FiatAlarmGuard.Skip.FX_STALE]). */
    val fxStale: Boolean = false,
    /** USDT/USD unsicher ([com.cryptochecker.app.domain.convert.FiatAlarmGuard.Skip.UNSURE]). */
    val fxUnsure: Boolean = false,
    /** Bedingung erfüllt, der Alarm wäre fällig (bei einer Sperre: Vorschau mit denselben reinen Regeln). */
    val due: Boolean = false,
    /** Nicht fällig nur wegen der Ruhezeit nach der letzten Meldung (Bedingung selbst erfüllt). */
    val coolingDown: Boolean = false,
    /** Kursmarke schon gemeldet, noch nicht wieder scharf. */
    val waitingRearm: Boolean = false,
    val quietHours: Boolean = false,
    /** «1 h später» läuft ([AlarmSnooze.isSnoozed]). */
    val snoozed: Boolean = false,
    val notificationsOff: Boolean = false,
    /** Unbestätigte Meldung: Neuversuch erst nach der Abklingzeit. */
    val retryBlocked: Boolean = false,
    val notShown: Boolean = false,
)

/** Eine Prüfung eines Alarms, wie sie im Protokoll steht (Paar und Börse als Text — bleibt auch nach dem Löschen lesbar). */
data class AlarmLogEntry(
    val alarmId: Long,
    val at: Long,
    /** Paar wie in der Merkliste, z. B. «BTC/CHF». */
    val pair: String,
    val exchange: String,
    val quote: String,
    /** Gesehener Kurs in der Quote; null ohne Kurs. */
    val price: Double?,
    val condition: AlarmCondition,
    val threshold: Double,
    val windowHours: Int = 1,
    /** Währung der Schwelle, wenn sie nicht die Quote ist (Kursmarke in eigener Währung). */
    val currency: String? = null,
    /** Kurs in [currency] (umgerechnet); null ohne Umrechnung. */
    val shownPrice: Double? = null,
    val outcome: AlarmLogOutcome,
    /** Zeit des Kurses laut Börse (für «Kurs veraltet (Stand …)»). */
    val exchangeTime: Long? = null,
    /** Kommt ins Protokoll (nicht nur «zuletzt geprüft»): ausgelöst oder zurückgehalten, obwohl fällig. */
    val notable: Boolean = false,
    /** Gleiches Ergebnis mehrmals hintereinander: zuletzt gesehen um (0 = nur einmal). */
    val until: Long = 0,
    /** Wer geprüft hat (App, Hintergrund, pünktlicher Wecker, Widget); null = unbekannt. */
    val source: CheckTrigger? = null,
) {
    /** Letzte Prüfung dieses Eintrags. */
    val lastAt: Long get() = maxOf(at, until)
}

/** Stand des Protokolls: je Alarm die letzte Prüfung und die Liste bemerkenswerter Prüfungen (neueste zuerst). */
data class AlarmLogState(
    val lastChecks: Map<Long, AlarmLogEntry> = emptyMap(),
    val events: List<AlarmLogEntry> = emptyList(),
) {
    companion object {
        val EMPTY = AlarmLogState()
    }
}

/**
 * Alarm-Protokoll: beantwortet «Warum hat es nicht gepiepst?». Zwei Teile — je Kursalarm die letzte Prüfung
 * (bei jeder Prüfung überschrieben) und ein Ringpuffer mit höchstens [MAX_EVENTS] bemerkenswerten Prüfungen der letzten
 * [RETENTION_MILLIS] (ausgelöst bzw. zurückgehalten, obwohl fällig — nicht jedes «nicht erreicht»). Nur auf dem Gerät,
 * nie in einer Sicherung. Reine Logik — wie `AlarmLog.swift`.
 */
object AlarmLog {

    const val MAX_EVENTS = 200
    const val RETENTION_MILLIS = 14L * 24 * 60 * 60_000

    /** Live-Kurse kommen sekündlich: dann höchstens so oft schreiben (ein neuer Protokoll-Eintrag sofort, [startsEvent]). */
    const val LIVE_WRITE_INTERVAL_MILLIS = 60_000L

    /**
     * Übrige Durchläufe ohne Bemerkenswertes: die letzten Prüfungen höchstens so oft schreiben. Bis zu
     * 20'000 Alarme werden sonst bei jeder Aktualisierung komplett serialisiert und versiegelt.
     */
    const val CHECKS_WRITE_INTERVAL_MILLIS = 10 * 60_000L

    /**
     * Jetzt speichern? Ein neuer Protokoll-Eintrag ([startsEvent]) sofort; ausserhalb von Live-Kursen auch jede
     * bemerkenswerte Prüfung ([notable], z. B. ein verlängertes «von … bis …»); sonst erst nach
     * [LIVE_WRITE_INTERVAL_MILLIS] (Live) bzw. [CHECKS_WRITE_INTERVAL_MILLIS]. Uhr zurückgestellt: sofort.
     */
    fun shouldPersist(live: Boolean, startsEvent: Boolean, notable: Boolean, now: Long, lastWriteAt: Long): Boolean {
        if (startsEvent || now < lastWriteAt) return true
        if (!live && notable) return true
        val interval = if (live) LIVE_WRITE_INTERVAL_MILLIS else CHECKS_WRITE_INTERVAL_MILLIS
        return now - lastWriteAt >= interval
    }

    const val FORMAT_VERSION = 1

    /** Erfasste Alarme: Kursmarken und Prozent-Alarme (die übrigen brauchen Zusatzdaten mit eigenen Gründen). */
    fun covers(condition: AlarmCondition): Boolean = when (condition) {
        AlarmCondition.PRICE_ABOVE, AlarmCondition.PRICE_BELOW,
        AlarmCondition.CHANGE_PERCENT_UP, AlarmCondition.CHANGE_PERCENT_DOWN,
        AlarmCondition.MOVE_PERCENT_WINDOW -> true
        else -> false
    }

    /** Ein Ergebnis aus der beobachteten Prüfung — feste Reihenfolge, beide Plattformen gleich. */
    fun outcome(trace: AlarmLogTrace): AlarmLogOutcome = when {
        trace.noPrice -> AlarmLogOutcome.NO_PRICE
        trace.lowLiquidity -> AlarmLogOutcome.LOW_LIQUIDITY
        trace.markPrice -> AlarmLogOutcome.MARK_PRICE
        trace.stale -> AlarmLogOutcome.STALE
        trace.noRate -> AlarmLogOutcome.NO_RATE
        trace.fxStale -> AlarmLogOutcome.FX_STALE
        trace.fxUnsure -> AlarmLogOutcome.FX_UNSURE
        trace.due -> when {
            trace.quietHours -> AlarmLogOutcome.QUIET_HOURS
            trace.snoozed -> AlarmLogOutcome.SNOOZED
            trace.notificationsOff -> AlarmLogOutcome.NOTIFICATIONS_OFF
            trace.retryBlocked -> AlarmLogOutcome.COOLDOWN
            trace.notShown -> AlarmLogOutcome.NOT_SHOWN
            else -> AlarmLogOutcome.FIRED
        }
        trace.coolingDown -> AlarmLogOutcome.COOLDOWN
        trace.waitingRearm -> AlarmLogOutcome.ALREADY_TRIGGERED
        else -> AlarmLogOutcome.NOT_REACHED
    }

    /** Ins Protokoll? Ausgelöst, oder zurückgehalten, obwohl die Bedingung erfüllt war. */
    fun notable(outcome: AlarmLogOutcome, trace: AlarmLogTrace): Boolean = when (outcome) {
        AlarmLogOutcome.FIRED -> true
        AlarmLogOutcome.LOW_LIQUIDITY, AlarmLogOutcome.MARK_PRICE, AlarmLogOutcome.STALE,
        AlarmLogOutcome.FX_STALE, AlarmLogOutcome.FX_UNSURE -> trace.due
        AlarmLogOutcome.QUIET_HOURS, AlarmLogOutcome.SNOOZED, AlarmLogOutcome.NOTIFICATIONS_OFF, AlarmLogOutcome.NOT_SHOWN,
        AlarmLogOutcome.COOLDOWN -> true
        AlarmLogOutcome.NOT_REACHED, AlarmLogOutcome.ALREADY_TRIGGERED,
        AlarmLogOutcome.NO_RATE, AlarmLogOutcome.NO_PRICE -> false
    }

    /**
     * Ablauf «fälligen Alarm melden» ([AlarmDelivery.deliver]) von aussen beobachtet: [canShow] bzw. [shown] = was die
     * Schritte geliefert haben (null = nicht erreicht). null zurück = gemeldet, aber nicht gespeichert (Alarm inzwischen
     * bearbeitet oder gelöscht, Mitteilung zurückgezogen) — dann kein Eintrag. [snoozed]: «1 h später» lief.
     */
    fun deliveryTrace(
        holdAlarms: Boolean,
        canShow: Boolean?,
        shown: Boolean?,
        saved: Boolean,
        snoozed: Boolean = false,
    ): AlarmLogTrace? = when {
        holdAlarms -> AlarmLogTrace(due = true, quietHours = true)
        snoozed -> AlarmLogTrace(due = true, snoozed = true)
        canShow == false -> AlarmLogTrace(due = true, notificationsOff = true)
        shown == null -> AlarmLogTrace(due = true, retryBlocked = true)
        !shown -> AlarmLogTrace(due = true, notShown = true)
        saved -> AlarmLogTrace(due = true)
        else -> null
    }

    /**
     * Prüfungen übernehmen: je Alarm die neueste als letzte Prüfung; bemerkenswerte vorne ins Protokoll. Dasselbe
     * Ergebnis desselben Alarms gleich hintereinander (z. B. jede Prüfung der Nacht «Nachtruhe») wird zu einem Eintrag
     * «von … bis …» zusammengefasst — sonst füllte eine Nacht den Puffer; Auslösungen bleiben immer einzeln.
     */
    fun record(state: AlarmLogState, entries: List<AlarmLogEntry>, now: Long): AlarmLogState {
        if (entries.isEmpty()) return state.copy(events = prune(state.events, now))
        val last = LinkedHashMap(state.lastChecks)
        val events = state.events.toMutableList()
        for (entry in entries.sortedBy { it.at }) {
            val previous = last[entry.alarmId]
            if (previous == null || entry.at >= previous.at) last[entry.alarmId] = entry
            if (!entry.notable) continue
            val index = events.indexOfFirst { it.alarmId == entry.alarmId }
            if (index >= 0 && collapses(events[index], entry)) {
                val merged = entry.copy(at = events[index].at, until = entry.at)
                events.removeAt(index)
                events.add(0, merged)
            } else {
                events.add(0, entry)
            }
        }
        return AlarmLogState(last, prune(events, now))
    }

    /**
     * Beginnen [entries] auf [state] einen neuen Protokoll-Eintrag (Auslösung oder ein anderes Ergebnis als zuletzt)?
     * Verlängern sie nur ein bestehendes «von … bis …», nicht — dann schreiben Live-Kurse gedrosselt wie jede andere
     * Prüfung (sonst schriebe eine anhaltende Sperre die Datei jede Sekunde neu). Gleiche Zusammenfassung wie [record].
     */
    fun startsEvent(state: AlarmLogState, entries: List<AlarmLogEntry>): Boolean {
        val latest = HashMap<Long, AlarmLogEntry>()
        for (event in state.events) latest.putIfAbsent(event.alarmId, event)
        for (entry in entries.sortedBy { it.at }) {
            if (!entry.notable) continue
            val old = latest[entry.alarmId]
            if (old == null || !collapses(old, entry)) return true
            latest[entry.alarmId] = entry.copy(at = old.at, until = entry.at)
        }
        return false
    }

    private fun collapses(old: AlarmLogEntry, new: AlarmLogEntry): Boolean =
        new.outcome != AlarmLogOutcome.FIRED && old.outcome == new.outcome &&
            old.condition == new.condition && old.threshold == new.threshold &&
            old.currency == new.currency && new.at >= old.lastAt

    /** Älter als [RETENTION_MILLIS] fällt weg; höchstens [MAX_EVENTS], neueste (zuletzt gesehen) zuerst. */
    fun prune(events: List<AlarmLogEntry>, now: Long): List<AlarmLogEntry> =
        events.filter { now - it.lastAt <= RETENTION_MILLIS }
            .sortedByDescending { it.lastAt }
            .take(MAX_EVENTS)

    /** Einträge eines Durchlaufs mit seiner Herkunft [source] versehen (schon gesetzte bleiben). */
    fun withSource(entries: List<AlarmLogEntry>, source: CheckTrigger): List<AlarmLogEntry> =
        entries.map { if (it.source == null) it.copy(source = source) else it }

    /** Alarm gelöscht: seine letzte Prüfung fällt weg (Protokoll-Einträge bleiben mit ihrem Text). */
    fun removeAlarm(state: AlarmLogState, alarmId: Long): AlarmLogState =
        if (alarmId !in state.lastChecks) state else state.copy(lastChecks = state.lastChecks - alarmId)

    /** Nur letzte Prüfungen bestehender Alarme behalten (Paar samt Alarmen gelöscht). */
    fun retainAlarms(state: AlarmLogState, alarmIds: Set<Long>): AlarmLogState {
        val kept = state.lastChecks.filterKeys { it in alarmIds }
        return if (kept.size == state.lastChecks.size) state else state.copy(lastChecks = kept)
    }

    /** «Protokoll löschen»: die Liste leeren; die letzten Prüfungen bleiben (sie gehören zu den Alarmen). */
    fun clearEvents(state: AlarmLogState): AlarmLogState = state.copy(events = emptyList())

    // ---- Datei (JSON; Schlüssel gleich auf iOS)

    fun encode(state: AlarmLogState): String {
        val root = JsonObject()
        root.put("v", FORMAT_VERSION)
        root.put("last", JsonArray().apply { state.lastChecks.values.forEach { put(encodeEntry(it)) } })
        root.put("events", JsonArray().apply { state.events.forEach { put(encodeEntry(it)) } })
        return root.toString()
    }

    /** Unlesbares fällt weg (leerer Stand bzw. einzelne Einträge); höchstens [MAX_EVENTS] Einträge. */
    fun decode(text: String?): AlarmLogState {
        if (text.isNullOrBlank()) return AlarmLogState.EMPTY
        val root = runCatching { JsonObject(text) }.getOrNull() ?: return AlarmLogState.EMPTY
        if (root.optInt("v", 0) != FORMAT_VERSION) return AlarmLogState.EMPTY
        val last = LinkedHashMap<Long, AlarmLogEntry>()
        root.optJSONArray("last")?.let { array ->
            for (i in 0 until array.length()) {
                val entry = decodeEntry(array.optJSONObject(i)) ?: continue
                val previous = last[entry.alarmId]
                if (previous == null || entry.at >= previous.at) last[entry.alarmId] = entry
            }
        }
        val events = ArrayList<AlarmLogEntry>()
        root.optJSONArray("events")?.let { array ->
            for (i in 0 until array.length()) {
                if (events.size >= MAX_EVENTS) break
                events += decodeEntry(array.optJSONObject(i)) ?: continue
            }
        }
        return AlarmLogState(last, events)
    }

    private fun encodeEntry(e: AlarmLogEntry): JsonObject = JsonObject().apply {
        put("id", e.alarmId)
        put("at", e.at)
        put("pair", e.pair)
        put("ex", e.exchange)
        put("quote", e.quote)
        e.price?.takeIf { it.isFinite() }?.let { put("price", it) }
        put("cond", e.condition.name)
        if (e.threshold.isFinite()) put("thr", e.threshold)
        put("win", e.windowHours)
        e.currency?.let { put("cur", it) }
        e.shownPrice?.takeIf { it.isFinite() }?.let { put("shown", it) }
        put("out", e.outcome.code)
        e.exchangeTime?.let { put("exTime", it) }
        if (e.notable) put("n", true)
        if (e.until > 0) put("until", e.until)
        e.source?.let { put("src", it.code) }
    }

    private fun decodeEntry(o: JsonObject?): AlarmLogEntry? {
        if (o == null) return null
        val id = o.optLong("id", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: return null
        val at = o.optLong("at", 0L).takeIf { it > 0 } ?: return null
        val condition = AlarmCondition.entries.firstOrNull { it.name == o.optString("cond") } ?: return null
        val outcome = AlarmLogOutcome.fromCode(o.optString("out")) ?: return null
        val threshold = o.optDouble("thr", Double.NaN).takeIf { it.isFinite() } ?: return null
        return AlarmLogEntry(
            alarmId = id,
            at = at,
            pair = o.optString("pair"),
            exchange = o.optString("ex"),
            quote = o.optString("quote"),
            price = if (o.has("price")) o.optDouble("price", Double.NaN).takeIf { it.isFinite() } else null,
            condition = condition,
            threshold = threshold,
            windowHours = o.optInt("win", 1),
            currency = if (o.has("cur")) o.optString("cur").takeIf { it.isNotBlank() } else null,
            shownPrice = if (o.has("shown")) o.optDouble("shown", Double.NaN).takeIf { it.isFinite() } else null,
            outcome = outcome,
            exchangeTime = if (o.has("exTime")) o.optLong("exTime", 0L).takeIf { it > 0 } else null,
            notable = o.optBoolean("n", false),
            until = o.optLong("until", 0L).coerceAtLeast(0L),
            source = if (o.has("src")) CheckTrigger.fromCode(o.optString("src")) else null,
        )
    }
}
