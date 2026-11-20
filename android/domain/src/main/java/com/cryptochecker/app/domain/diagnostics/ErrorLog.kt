package com.cryptochecker.app.domain.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Fehlerprotokoll für den Diagnosebericht: die letzten [MAX_ENTRIES] Fehler mit Zeit, Stufe, Bereich bzw. Codestelle und
 * Fehlertyp — nie Meldungstexte (dort könnten Paare, Beträge oder Adressen stehen). Bereich und Typ werden auf wenige
 * Zeichen gekürzt und gefiltert ([field]), damit kein freier Text hineingerät. Die Codestelle eines Release-Builds ist
 * verschleiert und wird mit der R8-Zuordnung genau dieses Builds (Quell-Commit im Bericht) lesbar. Ein gleicher Eintrag
 * innert [REPEAT_WINDOW_MILLIS] wird nicht nochmals geschrieben. Liegt nur auf dem Gerät (ohne Systemsicherung) und
 * geht nur mit dem Bericht hinaus, den man selbst teilt. Reine Regeln; Ablage über [Sink]. Wie iOS `ErrorLog`,
 * gemeinsame Testfälle `error_log.json`.
 */
object ErrorLog {
    const val MAX_ENTRIES = 100
    const val FIELD_MAX_LENGTH = 80
    const val REPEAT_WINDOW_MILLIS = 60_000L
    private const val SEPARATOR = '|'
    private const val NONE = "-"

    /** Ablage der Zeilen (in der App eine Datei ohne Systemsicherung). */
    interface Sink {
        fun append(line: String)

        /** Wie [append], aber geschrieben, bevor es zurückkehrt (Absturz: der Prozess endet gleich danach). */
        fun appendNow(line: String) = append(line)
    }

    @Volatile private var sink: Sink? = null

    /** Einmal beim Start; vorher (Unit-Tests) wird nichts geschrieben. */
    fun install(target: Sink?) {
        sink = target
    }

    /** Einen Fehler festhalten ([line]); wirft nie. */
    fun record(level: Char, area: String, type: String?, now: Long = System.currentTimeMillis()) {
        val target = sink ?: return
        runCatching { target.append(line(now, level, area, type)) }
    }

    /** Absturz festhalten ([line]), geschrieben bevor es zurückkehrt; wirft nie. */
    fun recordNow(level: Char, area: String, type: String?, now: Long = System.currentTimeMillis()) {
        val target = sink ?: return
        runCatching { target.appendNow(line(now, level, area, type)) }
    }

    /** Erlaubte Zeichen eines Felds: Buchstaben, Ziffern und `_ . $ : - < >`; Rest fällt weg; leer → «-». */
    fun field(text: String?): String =
        (text ?: "").filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_.$:-<>" }
            .take(FIELD_MAX_LENGTH).ifEmpty { NONE }

    /** Gespeicherte Zeile «Zeit|Stufe|Bereich|Typ»; Stufe E oder W (sonst E). */
    fun line(millis: Long, level: Char, area: String, type: String?): String =
        listOf(millis.toString(), if (level == 'W') "W" else "E", field(area), field(type)).joinToString(SEPARATOR.toString())

    /** Eintrag aus einer gespeicherten Zeile; null = unlesbar. */
    data class Entry(val millis: Long, val level: String, val area: String, val type: String)

    fun parse(line: String): Entry? {
        val parts = line.trim().split(SEPARATOR)
        if (parts.size != 4) return null
        val millis = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val level = parts[1].takeIf { it == "E" || it == "W" } ?: return null
        return Entry(millis, level, field(parts[2]), field(parts[3]))
    }

    /**
     * Zeilen nach dem Anfügen von [line]: gleicher Bereich und Typ wie der letzte Eintrag innert
     * [REPEAT_WINDOW_MILLIS] → unverändert; sonst angefügt und auf die letzten [MAX_ENTRIES] gekürzt. Unlesbare Zeilen
     * fallen weg.
     */
    fun appended(lines: List<String>, line: String): List<String> {
        val kept = lines.filter { parse(it) != null }
        val new = parse(line) ?: return kept.takeLast(MAX_ENTRIES)
        val last = kept.lastOrNull()?.let(::parse)
        val repeat = last != null && last.area == new.area && last.type == new.type && last.level == new.level &&
            new.millis - last.millis in 0 until REPEAT_WINDOW_MILLIS
        return if (repeat) kept.takeLast(MAX_ENTRIES) else (kept + line).takeLast(MAX_ENTRIES)
    }

    /** Zeilen für den Bericht, neueste zuletzt: «2026-11-17 18:40 E refresh SocketTimeoutException». */
    fun shown(lines: List<String>, zone: ZoneId): List<String> {
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(zone)
        return lines.mapNotNull(::parse).map { "${format.format(Instant.ofEpochMilli(it.millis))} ${it.level} ${it.area} ${it.type}" }
    }
}
