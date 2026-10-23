package com.cryptochecker.app.data.local

import java.io.File

/**
 * Beiseitegelegte, unlesbare Datenbankdateien (`<db>.unreadable…`, [DatabaseEncryptionMigration]) nicht
 * mehr für immer behalten. Früher blieben sie samt den früheren Schlüsseln liegen (Speicher, und eine Kopie der
 * Nutzerdaten mehr auf dem Gerät).
 *
 * Gelöscht wird vorsichtig, nur wenn alles zutrifft:
 *  - die aktuelle Datei öffnet mit dem gespeicherten Schlüssel (der Aufruf kommt nur von dort — nie die aktuelle Datei),
 *  - kein früherer Schlüssel öffnete die Kopie bei diesem Start ([restoreFromAside] lief vorher ohne Erfolg),
 *  - der Hinweis «Gespeicherte Daten liessen sich nicht entsperren» ist bestätigt ([allowed]),
 *  - die App kennt die Kopie seit mindestens [MAX_AGE_DAYS] Tagen ([seen]; gezählt ab dem ersten Start, der sie sah —
 *    die Änderungszeit der Datei taugt nicht, Umbenennen behält die des letzten Schreibens).
 * Danach dürfen auch die früheren Schlüssel weg (wie bisher, sobald keine beiseitegelegte Datei mehr wartet).
 * Reines Kotlin bis auf die kleine Merkdatei ([SeenStore]).
 */
object AsideRetention {

    const val MAX_AGE_DAYS = 30
    const val MAX_AGE_MILLIS = MAX_AGE_DAYS * 24L * 60 * 60 * 1000

    /** [delete]: jetzt zu löschende Kopien; [seen]: neuer Stand der Merkdatei (nur noch vorhandene Kopien). */
    data class Selection(val delete: List<String>, val seen: Map<String, Long>)

    /**
     * Welche der [copies] weg dürfen. [seen]: seit wann die App die Kopie kennt (ms); neue Kopien beginnen mit [now], eine
     * zurückgestellte Uhr (Zeit in der Zukunft) ebenso — lieber länger behalten.
     */
    fun select(copies: List<String>, seen: Map<String, Long>, now: Long, allowed: Boolean, maxAgeMillis: Long = MAX_AGE_MILLIS): Selection {
        val updated = copies.associateWith { copy -> seen[copy]?.takeIf { it <= now } ?: now }
        val delete = if (!allowed) emptyList() else copies.filter { now - updated.getValue(it) >= maxAgeMillis }
        // Gelöschte bleiben vermerkt: scheitert das Löschen, beginnt die Frist nicht von vorn (der nächste Start räumt auf)
        return Selection(delete, updated)
    }

    /** Merkdatei «Kopie=seit (ms)» je Zeile, je Datenbank eine, neben den Schlüsseldateien (nie in einer Sicherung). */
    class SeenStore(private val file: File) {
        fun read(): Map<String, Long> = runCatching {
            if (!file.exists()) return emptyMap()
            file.readLines(Charsets.UTF_8).mapNotNull { line ->
                val at = line.lastIndexOf('=')
                if (at <= 0) null else line.substring(at + 1).toLongOrNull()?.let { line.substring(0, at) to it }
            }.toMap()
        }.getOrDefault(emptyMap())

        fun write(seen: Map<String, Long>) {
            if (seen.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            file.writeText(seen.entries.joinToString("\n") { "${it.key}=${it.value}" }, Charsets.UTF_8)
        }
    }

    /** Ablauf für eine Datenbank: Merkdatei lesen, auswählen, Merkdatei schreiben; zurück die zu löschenden Kopien. */
    fun expired(store: SeenStore, copies: List<String>, now: Long, allowed: Boolean): List<String> {
        val selection = select(copies, store.read(), now, allowed)
        runCatching { store.write(selection.seen) }
        return selection.delete
    }
}
