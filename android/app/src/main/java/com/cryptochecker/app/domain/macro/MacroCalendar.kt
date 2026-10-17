package com.cryptochecker.app.domain.macro

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Wichtige US-Wirtschaftsdaten mit oft grösseren Marktschwankungen. */
enum class MacroEventType {
    /** Verbraucherpreise (Consumer Price Index). */
    CPI,

    /** Erzeugerpreise (Producer Price Index). */
    PPI,

    /** Arbeitsmarktbericht (Employment Situation, Non-Farm Payrolls). */
    NFP,

    /** Zinsentscheid der US-Notenbank (FOMC-Statement). */
    FOMC,

    /** Konsumausgaben-Preise (Personal Income and Outlays, PCE). */
    PCE,
}

/** Ein Termin; [time] ist der Zeitpunkt der Veröffentlichung (Epoch-ms, UTC). */
data class MacroEvent(val type: MacroEventType, val time: Long)

/**
 * Hinweis «Wirtschaftsdaten» oben im Abschnitt «Jetzt».
 *
 * @param items Termine in zeitlicher Reihenfolge
 * @param released alle Termine liegen schon mehr als 2 h zurück («… veröffentlicht»)
 */
data class MacroHint(val items: List<MacroHintItem>, val released: Boolean) {
    /** Alle Termine heute (Ortszeit). */
    val allToday: Boolean get() = items.all { !it.tomorrow }

    /** Alle Termine morgen (Ortszeit). */
    val allTomorrow: Boolean get() = items.all { it.tomorrow }
}

/** @param tomorrow Termin liegt am nächsten Kalendertag (Ortszeit) */
data class MacroHintItem(val event: MacroEvent, val tomorrow: Boolean)

/**
 * Kalender wichtiger US-Wirtschaftsdaten — reine Logik, testbar; wie `MacroCalendar.swift` (iOS).
 *
 * Quelle: eine öffentliche Datei auf GitHub Pages (einmal am Tag, 24 h zwischengespeichert),
 * bei Fehlern der Zwischenspeicher, sonst die mitgelieferte Datei (`assets/macro_events.json`).
 * Format: `{"version":1,"generated":"…","source":"…","events":[{"type":"CPI","time":"2026-10-14T12:30:00Z"},…]}`.
 */
object MacroCalendar {
    /** Datei der App-Webseite (GitHub Pages). */
    const val URL = "https://r1adbe.github.io/cryptoChecker/macro/events.json"

    /** Zwischenspeicher gilt einen Tag. */
    const val TTL_MILLIS = 24 * 60 * 60_000L

    /** Hinweis schon so lange vor dem Termin (auch über Mitternacht hinweg). */
    const val LOOKAHEAD_MILLIS = 18 * 60 * 60_000L

    /** So lange nach dem Termin gilt er noch als «bevorstehend», danach «veröffentlicht». */
    const val RELEASED_AFTER_MILLIS = 2 * 60 * 60_000L

    /** Ältere Termine werden beim Lesen verworfen. */
    const val MAX_AGE_MILLIS = 2 * 24 * 60 * 60_000L

    /** Morgen-Meldung um 08:00 Ortszeit. */
    const val NOTIFY_MINUTE_OF_DAY = 8 * 60

    /** Kommt die Hintergrund-Aufgabe zu spät (nach 12:00), wird die Meldung des Tages ausgelassen. */
    const val NOTIFY_LATEST_MINUTE_OF_DAY = 12 * 60

    /**
     * Datei lesen. null, wenn sie kein gültiges Kalender-Objekt ist (dann gilt die
     * nächste Quelle); unbekannte Typen und ungültige Zeiten werden übergangen,
     * Termine älter als [MAX_AGE_MILLIS] verworfen. Sortiert, ohne Doppelte.
     */
    fun parse(json: String, now: Long): List<MacroEvent>? {
        val parsed: Any? = try {
            MiniJson.parse(json)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val root = parsed as? Map<*, *> ?: return null
        val version = (root["version"] as? Double)?.toInt() ?: return null
        if (version < 1) return null
        val events = root["events"] as? List<*> ?: return null
        return events.mapNotNull { item ->
            val o = item as? Map<*, *> ?: return@mapNotNull null
            val type = (o["type"] as? String)?.trim()?.uppercase()
                ?.let { name -> MacroEventType.entries.firstOrNull { it.name == name } }
                ?: return@mapNotNull null
            val time = (o["time"] as? String)?.let { parseInstant(it) } ?: return@mapNotNull null
            MacroEvent(type, time)
        }
            .filter { it.time >= now - MAX_AGE_MILLIS }
            .distinct()
            .sortedWith(compareBy<MacroEvent> { it.time }.thenBy { it.type.ordinal })
    }

    /** ISO-8601-Zeitpunkt («2026-10-14T12:30:00Z», auch mit Versatz); null wenn ungültig. */
    fun parseInstant(text: String): Long? = try {
        java.time.OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    /**
     * Hinweis zur Zeit [now] in der Zeitzone [zone]: Termine von heute (Ortsdatum) und
     * solche in den nächsten [LOOKAHEAD_MILLIS]. Sind alle schon mehr als 2 h vorbei,
     * heisst es «veröffentlicht» (nur noch heute). null = kein Hinweis.
     */
    fun hint(events: List<MacroEvent>, now: Long, zone: ZoneId): MacroHint? {
        val today = localDate(now, zone)
        val items = events
            .filter { e ->
                val day = localDate(e.time, zone)
                day == today || (e.time > now && e.time - now <= LOOKAHEAD_MILLIS)
            }
            .sortedBy { it.time }
            .map { MacroHintItem(it, tomorrow = localDate(it.time, zone).isAfter(today)) }
        if (items.isEmpty()) return null
        val released = items.all { now >= it.event.time + RELEASED_AFTER_MILLIS }
        return MacroHint(items, released)
    }

    /** Termin näher als so viel (davor oder danach): Hinweis oben im Abschnitt «Jetzt». */
    const val TOP_WINDOW_MILLIS = 2 * 60 * 60_000L

    /**
     * Steht der Hinweis oben im Markt-Tab («Jetzt»)? Nur, wenn ein Termin höchstens
     * [TOP_WINDOW_MILLIS] entfernt ist (bevorstehend oder eben veröffentlicht); sonst steht
     * er im Abschnitt «Daten».
     */
    fun isImminent(hint: MacroHint, now: Long): Boolean =
        hint.items.any { kotlin.math.abs(it.event.time - now) <= TOP_WINDOW_MILLIS }

    /** Termine für die Morgen-Meldung: heute (Ortsdatum) und noch nicht vorbei. */
    fun notificationEvents(events: List<MacroEvent>, now: Long, zone: ZoneId): List<MacroEvent> {
        val today = localDate(now, zone)
        return events.filter { it.time > now && localDate(it.time, zone) == today }.sortedBy { it.time }
    }

    /** Nächster Zeitpunkt 08:00 Ortszeit nach [now] (heute, wenn noch nicht vorbei). */
    fun nextNotifyAt(now: Long, zone: ZoneId): Long {
        val nowZoned = Instant.ofEpochMilli(now).atZone(zone)
        var at = nowZoned.toLocalDate()
            .atTime(NOTIFY_MINUTE_OF_DAY / 60, NOTIFY_MINUTE_OF_DAY % 60)
            .atZone(zone)
        if (!at.isAfter(nowZoned)) {
            at = nowZoned.toLocalDate().plusDays(1)
                .atTime(NOTIFY_MINUTE_OF_DAY / 60, NOTIFY_MINUTE_OF_DAY % 60)
                .atZone(zone)
        }
        return at.toInstant().toEpochMilli()
    }

    /** Darf die Morgen-Meldung jetzt (Minute des Tages in Ortszeit) noch kommen? */
    fun isNotifyWindow(minuteOfDay: Int): Boolean =
        minuteOfDay in NOTIFY_MINUTE_OF_DAY until NOTIFY_LATEST_MINUTE_OF_DAY

    /** Zwischenspeicher frisch? (Zeitpunkt in der Zukunft oder ≤ 0 gilt nie als frisch.) */
    fun isFresh(savedAt: Long, now: Long): Boolean = savedAt in 1..now && now - savedAt < TTL_MILLIS

    fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}

/**
 * Sehr kleiner JSON-Leser (reines Kotlin, ohne Android): Objekte → Map, Arrays → List,
 * Zahlen → Double, dazu String, Boolean und null. Wirft [IllegalArgumentException] bei
 * ungültigem Inhalt.
 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipSpace()
        val value = reader.value()
        reader.skipSpace()
        require(reader.atEnd()) { "Unerwarteter Inhalt nach JSON" }
        return value
    }

    private class Reader(private val s: String) {
        private var i = 0
        private var depth = 0

        fun atEnd() = i >= s.length

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char {
            require(i < s.length) { "JSON endet unerwartet" }
            return s[i]
        }

        private fun expect(c: Char) {
            require(peek() == c) { "Erwartet '$c' an Stelle $i" }
            i++
        }

        fun value(): Any? {
            skipSpace()
            return when (val c = peek()) {
                '{' -> nested { obj() }
                '[' -> nested { array() }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("Ungültiges Zeichen '$c'")
            }
        }

        private fun <T> nested(block: () -> T): T {
            depth++
            require(depth <= MAX_DEPTH) { "JSON zu tief verschachtelt" }
            return block().also { depth-- }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                expect(':')
                out[key] = value()
                skipSpace()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return out
                    }
                    else -> throw IllegalArgumentException("Erwartet ',' oder '}' an Stelle $i")
                }
            }
        }

        private fun array(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') {
                i++
                return out
            }
            while (true) {
                out += value()
                skipSpace()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return out
                    }
                    else -> throw IllegalArgumentException("Erwartet ',' oder ']' an Stelle $i")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = peek()
                i++
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = peek()
                        i++
                        when (e) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "Ungültiges \\u" }
                                val code = s.substring(i, i + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Ungültiges \\u")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Ungültige Escape-Folge")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull()
                ?: throw IllegalArgumentException("Ungültige Zahl an Stelle $start")
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "Ungültiges Wort an Stelle $i" }
            i += word.length
            return value
        }
    }

    private const val MAX_DEPTH = 32
}
