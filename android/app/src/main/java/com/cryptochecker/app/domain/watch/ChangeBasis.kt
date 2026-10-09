package com.cryptochecker.app.domain.watch

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * «Basis der %-Änderung» (Einstellungen › Darstellung, wie bei Binance «Change(%) & Chart
 * Timezone»): Worauf sich Prozent-Pille, Puls-Zeile, Aktionsblatt, Widgets und Live Activity
 * beziehen. Alarme rechnen unabhängig davon.
 *
 *  - [ROLLING_24H]: rollende 24 Stunden (Standard — Ticker-Wert, sonst Kerzen).
 *  - [SINCE_LAST]: seit der letzten Aktualisierung (letzter gegen vorherigen Kurs des Paars,
 *    [ChangeBasisMath.sinceLast]); gerechnet und gespeichert wird dabei rollend ([storage]).
 *  - [LOCAL_DAY]: seit 00:00 Ortszeit (Zeitzone des Geräts, mit Sommerzeit).
 *  - [utc]: seit 00:00 in einer festen Zone UTC−12 … UTC+14 (volle Stunden, ohne Sommerzeit);
 *    [UTC_DAY] ist UTC+0.
 *
 * Gespeichert und gesichert unter [name] (Schlüssel «changeBasis», wie iOS): «ROLLING_24H»,
 * «SINCE_LAST», «LOCAL_DAY», «UTC_DAY» (UTC+0) und «UTC_DAY+8» / «UTC_DAY-5». Ältere Versionen kennen die
 * Namen mit Versatz nicht und nehmen dann den Standard.
 */
class ChangeBasis private constructor(val kind: Kind, val utcOffsetHours: Int) {

    enum class Kind { ROLLING_24H, SINCE_LAST, LOCAL_DAY, UTC_DAY }

    /** Tages-Basis (seit 00:00)? Dann nie der rollende Ticker-Wert. */
    val isDay: Boolean get() = kind == Kind.LOCAL_DAY || kind == Kind.UTC_DAY

    /** Seit der letzten Aktualisierung? Angezeigt wird dann [ChangeBasisMath.sinceLast]. */
    val isSinceLast: Boolean get() = kind == Kind.SINCE_LAST

    /**
     * Basis, mit der die gespeicherten Veränderungen gerechnet und gestempelt werden:
     * «Seit letzter Aktualisierung» rechnet nichts Eigenes und nimmt die rollenden Werte, so
     * zeigt ein Wechsel zwischen den beiden nie «—».
     */
    val storage: ChangeBasis get() = if (kind == Kind.SINCE_LAST) ROLLING_24H else this

    /** Gespeicherter Name, siehe Klasse. */
    val name: String
        get() = when (kind) {
            Kind.ROLLING_24H -> "ROLLING_24H"
            Kind.SINCE_LAST -> "SINCE_LAST"
            Kind.LOCAL_DAY -> "LOCAL_DAY"
            Kind.UTC_DAY -> when {
                utcOffsetHours > 0 -> "UTC_DAY+$utcOffsetHours"
                utcOffsetHours < 0 -> "UTC_DAY$utcOffsetHours"
                else -> "UTC_DAY"
            }
        }

    override fun equals(other: Any?): Boolean =
        other is ChangeBasis && other.kind == kind && other.utcOffsetHours == utcOffsetHours

    override fun hashCode(): Int = kind.hashCode() * 31 + utcOffsetHours

    override fun toString(): String = name

    companion object {
        /** Feste Zonen: UTC−12 … UTC+14 (wie Binance, volle Stunden). */
        const val MIN_OFFSET = -12
        const val MAX_OFFSET = 14

        /**
         * Je Zone genau ein Objekt: Compose vergleicht Parameter dieser Klasse über die Identität,
         * so zeichnet ein gleicher Wert aus den Einstellungen nichts neu.
         */
        private val ZONES = Array(MAX_OFFSET - MIN_OFFSET + 1) { ChangeBasis(Kind.UTC_DAY, MIN_OFFSET + it) }

        val ROLLING_24H = ChangeBasis(Kind.ROLLING_24H, 0)
        val SINCE_LAST = ChangeBasis(Kind.SINCE_LAST, 0)
        val LOCAL_DAY = ChangeBasis(Kind.LOCAL_DAY, 0)
        val UTC_DAY: ChangeBasis = ZONES[-MIN_OFFSET]

        val DEFAULT = ROLLING_24H

        /** Seit 00:00 in UTC+[hours]; ausserhalb von [MIN_OFFSET] … [MAX_OFFSET] null. */
        fun utc(hours: Int): ChangeBasis? =
            if (hours in MIN_OFFSET..MAX_OFFSET) ZONES[hours - MIN_OFFSET] else null

        /**
         * Alle Möglichkeiten in der Reihenfolge der Auswahl: rollend, seit letzter
         * Aktualisierung, Gerät, dann UTC+14 … UTC−12.
         */
        val entries: List<ChangeBasis> =
            listOf(ROLLING_24H, SINCE_LAST, LOCAL_DAY) + (MAX_OFFSET downTo MIN_OFFSET).mapNotNull { utc(it) }

        /** Name → Basis; null bei unbekanntem Namen oder Versatz ausserhalb des Bereichs. */
        fun parse(name: String?): ChangeBasis? {
            if (name == null) return null
            when (name) {
                "ROLLING_24H" -> return ROLLING_24H
                "SINCE_LAST" -> return SINCE_LAST
                "LOCAL_DAY" -> return LOCAL_DAY
                "UTC_DAY" -> return UTC_DAY
            }
            if (!name.startsWith("UTC_DAY")) return null
            // Nur «+n» oder «-n» mit Ziffern; «+0»/«-0» heisst «UTC_DAY»
            val rest = name.removePrefix("UTC_DAY")
            if (rest.length < 2 || (rest[0] != '+' && rest[0] != '-')) return null
            val digits = rest.substring(1)
            if (!digits.all { it in '0'..'9' }) return null
            val value = digits.toIntOrNull()?.takeIf { it != 0 } ?: return null
            return utc(if (rest[0] == '-') -value else value)
        }

        /** Unbekannt oder fehlend (ältere Version, neuere Sicherung): Standard. */
        fun fromName(name: String?): ChangeBasis = parse(name) ?: DEFAULT
    }
}

/**
 * Mit welcher Basis und welchem Tagesbeginn die gespeicherten Veränderungen der Merkliste
 * zuletzt (voller Durchlauf) berechnet wurden. Passt er nicht mehr zur Einstellung — Basis
 * gewechselt oder ein neuer Tag hat begonnen —, zeigt die App «—» statt eines falschen Werts,
 * bis der nächste Durchlauf neu rechnet ([ChangeBasisMath.isCurrent]).
 * [dayStart] 0 bei [ChangeBasis.ROLLING_24H].
 */
data class ChangeStamp(val basis: ChangeBasis, val dayStart: Long) {

    /** «UTC_DAY@1760054400000». */
    fun encode(): String = "${basis.name}@$dayStart"

    companion object {
        /** null bei fehlendem oder kaputtem Text. */
        fun decode(text: String?): ChangeStamp? {
            val parts = text?.split('@') ?: return null
            if (parts.size != 2) return null
            val basis = ChangeBasis.parse(parts[0]) ?: return null
            val start = parts[1].toLongOrNull() ?: return null
            return ChangeStamp(basis, start)
        }
    }
}

/**
 * Gewählte %-Basis und ob die gespeicherten Werte noch dazu passen ([current], siehe
 * [ChangeBasisMath.isCurrent]) — sonst zeigen Pille, Puls und Widgets «—», bis neu gerechnet ist.
 */
data class ChangeView(
    val basis: ChangeBasis = ChangeBasis.DEFAULT,
    val current: Boolean = true,
    /** Zeitraum («24h», «heute», «letzter Stand») neben Pille, Puls und Einzel-Widget zeigen? Ab Werk aus. */
    val showPeriod: Boolean = true,
) {

    /** Veränderung zum Anzeigen: null («—»), wenn die Werte nicht mehr zur Basis passen. */
    fun shown(change: Double?): Double? = if (current) change?.takeIf { it.isFinite() } else null

    /**
     * Wie [shown], aber «Seit letzter Aktualisierung» nimmt statt [change] den letzten gegen den
     * vorherigen Kurs ([ChangeBasisMath.sinceLast]). [change] null (nicht gehandelt) bleibt «—».
     */
    fun shown(change: Double?, last: Double?, previous: Double?, traded: Boolean = true): Double? =
        if (!traded) null
        else if (basis.isSinceLast) ChangeBasisMath.sinceLast(last, previous)
        else shown(change)

    companion object {
        fun of(
            stamp: ChangeStamp?,
            basis: ChangeBasis,
            now: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            showPeriod: Boolean = true,
        ): ChangeView = ChangeView(basis, ChangeBasisMath.isCurrent(stamp, basis, now, zone), showPeriod)
    }
}

/**
 * Reine Regeln der %-Basis (ohne Android, getestet in ChangeBasisTest; Swift-Spiegel
 * `ChangeBasis.swift`).
 *
 * Bezug für die Tages-Basen: Eröffnung der Stundenkerze, in der der Tagesbeginn liegt
 * ([openAt]) — bei ganzen Stunden genau die Kerze ab 00:00 (auch wenn der Tag erst
 * begonnen hat: die laufende Kerze). Zonen mit halben Stunden (z. B. Indien) nehmen die
 * Kerze davor angebrochen (Näherung bis 45 Minuten). Veränderung = (letzter − Eröffnung)
 * / Eröffnung, mit denselben Prüfungen wie der 24-h-Bezug ([DayChange.select]).
 */
object ChangeBasisMath {

    const val HOUR_MILLIS = 3_600_000L
    const val DAY_MILLIS = 24 * HOUR_MILLIS

    /**
     * So viele Stundenkerzen werden geladen: 24 für Mini-Chart und rollenden Bezug, zwei mehr,
     * damit auch ein 25-Stunden-Tag (Ende der Sommerzeit) und angebrochene Stunden
     * (Zonen mit halben Stunden) den Tagesbeginn enthalten.
     */
    const val CANDLES = 26

    /** Davon Mini-Chart und rollender 24-h-Bezug (die jüngsten). */
    const val ROLLING_CANDLES = 24

    /**
     * Beginn des laufenden Tags (00:00) für [basis] zum Zeitpunkt [now]; null bei der rollenden
     * Basis. Ortszeit: in [zone] mit Sommerzeit; fällt 00:00 in eine Lücke der Umstellung,
     * gilt die erste gültige Zeit des Tags.
     */
    fun dayStart(basis: ChangeBasis, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? = when (basis.kind) {
        ChangeBasis.Kind.ROLLING_24H, ChangeBasis.Kind.SINCE_LAST -> null
        ChangeBasis.Kind.UTC_DAY -> {
            // 00:00 in UTC+h liegt h Stunden vor 00:00 UTC
            val shift = basis.utcOffsetHours * HOUR_MILLIS
            Math.floorDiv(now + shift, DAY_MILLIS) * DAY_MILLIS - shift
        }
        ChangeBasis.Kind.LOCAL_DAY -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Zeitzone der Charts (wie Binance «Change(%) & Chart Timezone»): Uhrzeiten an der Achse,
     * beim Ziehen und die Tages-/Stundenraster folgen der gewählten Zone. Feste Zone UTC±h bei
     * [ChangeBasis.Kind.UTC_DAY]; rollend und «Gerät» nehmen die Zeitzone des Geräts ([device]).
     */
    fun chartZone(basis: ChangeBasis, device: ZoneId = ZoneId.systemDefault()): ZoneId =
        if (basis.kind == ChangeBasis.Kind.UTC_DAY) ZoneOffset.ofHours(basis.utcOffsetHours) else device

    /**
     * Ende des Tags, der bei [dayStart] beginnt (= nächster Tagesbeginn; Ortszeit mit
     * Sommerzeit 23 oder 25 Stunden später); null bei der rollenden Basis.
     */
    fun dayEnd(basis: ChangeBasis, dayStart: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        dayStart(basis, dayStart + 30 * HOUR_MILLIS, zone)?.takeIf { it > dayStart }

    /**
     * Tagesbeginne, deren Kerzen der Zwischenspeicher behält: UTC, Ortszeit und die gewählte
     * Basis (ohne doppelte). Mehr braucht keine Anzeige, auch nicht nach einem Wechsel zurück.
     */
    fun keptDayStarts(selected: ChangeBasis, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> =
        listOf(ChangeBasis.UTC_DAY, ChangeBasis.LOCAL_DAY, selected)
            .mapNotNull { dayStart(it, now, zone) }
            .distinct()

    /**
     * Zone als Text: «UTC», «UTC+8», «UTC-5», «UTC+5:30» (wie Binance; Bindestrich, damit die
     * Einstellungssuche «UTC-5» findet). [offsetSeconds] Abstand zu UTC in Sekunden.
     */
    fun zoneLabel(offsetSeconds: Int): String {
        if (offsetSeconds == 0) return "UTC"
        val sign = if (offsetSeconds < 0) "-" else "+"
        val total = Math.abs(offsetSeconds) / 60
        val hours = total / 60
        val minutes = total % 60
        return if (minutes == 0) "UTC$sign$hours" else "UTC$sign$hours:" + minutes.toString().padStart(2, '0')
    }

    /** Zone einer festen Basis («UTC+8»); null bei rollend und Ortszeit. */
    fun zoneLabel(basis: ChangeBasis): String? =
        if (basis.kind == ChangeBasis.Kind.UTC_DAY) zoneLabel(basis.utcOffsetHours * 3600) else null

    /** Zone des Geräts zum Zeitpunkt [now] (mit Sommerzeit): «UTC+2». */
    fun deviceZoneLabel(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        zoneLabel(zone.rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds)

    /** Beginn der Stunde (UTC-Raster der Kerzen), in der [time] liegt. */
    fun hourOf(time: Long): Long = Math.floorDiv(time, HOUR_MILLIS) * HOUR_MILLIS

    /**
     * Eröffnung der Stundenkerze, die [dayStart] enthält ([opens]: Startzeit → Eröffnung);
     * null, wenn sie fehlt oder kein gültiger Kurs ist.
     */
    fun openAt(opens: Map<Long, Double>, dayStart: Long): Double? =
        opens[hourOf(dayStart)]?.takeIf { it.isFinite() && it > 0.0 }

    /** Bezug seit Tagesbeginn: Eröffnung bei [dayStart] und letzter Schluss der Reihe. */
    fun reference(opens: Map<Long, Double>, lastClose: Double?, dayStart: Long): DayReference? =
        DayReference.of(openAt(opens, dayStart), lastClose)

    /** Braucht das Paar Kerzen? Tages-Basen immer, rollend nur ohne brauchbaren Ticker-Wert. */
    fun needsCandles(basis: ChangeBasis, tickerChange: Double?): Boolean =
        basis.isDay || DayChange.needsCandles(tickerChange)

    /**
     * Endgültiger Wert je Basis: rollend wie bisher ([DayChange.choose], Ticker vor Kerzen);
     * Tages-Basen nur aus Kerzen — der Ticker-Wert ist rollend und wäre hier falsch.
     */
    inline fun choose(basis: ChangeBasis, tickerChange: Double?, candles: () -> Double?): Double? =
        if (basis.isDay) candles()?.takeIf { it.isFinite() } else DayChange.choose(tickerChange, candles)

    /**
     * Gelten die gespeicherten Werte noch? Ohne Stempel (vor dieser Einstellung berechnet)
     * gelten sie als rollend. Tages-Basen: gleicher Tagesbeginn wie jetzt.
     */
    fun isCurrent(stamp: ChangeStamp?, basis: ChangeBasis, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val s = stamp ?: ChangeStamp(ChangeBasis.ROLLING_24H, 0L)
        if (s.basis.storage != basis.storage) return false
        if (!basis.isDay) return true
        return s.dayStart == dayStart(basis, now, zone)
    }

    /** Stempel für einen Durchlauf mit [basis] zum Zeitpunkt [now]. */
    fun stamp(basis: ChangeBasis, now: Long, zone: ZoneId = ZoneId.systemDefault()): ChangeStamp =
        ChangeStamp(basis.storage, dayStart(basis, now, zone) ?: 0L)

    /**
     * «Seit letzter Aktualisierung»: (letzter − vorheriger) / vorheriger Kurs in Prozent; null
     * («—»), solange es keinen gültigen vorherigen Kurs gibt (neues Paar, erster Abruf).
     */
    fun sinceLast(last: Double?, previous: Double?): Double? {
        if (last == null || previous == null || !last.isFinite() || !previous.isFinite()) return null
        if (last <= 0.0 || previous <= 0.0) return null
        return ((last - previous) / previous * 100.0).takeIf { it.isFinite() }
    }

    /**
     * Stundenkerzen ab Tagesbeginn für den Chart «Heute» ([openTime] je Kerze, aufsteigend):
     * ab der Kerze, die [dayStart] enthält. Bleiben weniger als zwei (Tag hat gerade begonnen),
     * die letzten zwei — ein Chart braucht zwei Kerzen.
     */
    fun <T> sinceDayStart(candles: List<T>, dayStart: Long, openTime: (T) -> Long): List<T> {
        val from = hourOf(dayStart)
        val kept = candles.filter { openTime(it) >= from }
        return if (kept.size >= 2) kept else candles.takeLast(2)
    }
}
