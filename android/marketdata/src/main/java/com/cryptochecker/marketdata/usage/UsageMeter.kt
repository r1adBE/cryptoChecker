package com.cryptochecker.marketdata.usage

import java.io.File

/**
 * Zähler von «Akku & Aktivität» ([UsageLog]) für diesen Prozess: zählt im Speicher und schreibt höchstens alle
 * [FLUSH_GAP_MILLIS] in die Datei ([attach], Handy und Uhr je `noBackupFilesDir/usage_log.txt`, nicht gesichert) —
 * sofort mit [flush] (Ende eines Hintergrund-Laufs). Bis zum ersten [attach] bleibt alles im Speicher. Fehler beim Lesen
 * oder Schreiben bleiben still (die Zahlen bleiben dann im Speicher für den nächsten Versuch); endet der Prozess vor dem
 * Schreiben, fehlt höchstens die letzte Minute. Nur Zahlen, keine Inhalte und keine Hosts. Wie iOS `UsageMeter`.
 */
class UsageMeter(private val now: () -> Long = System::currentTimeMillis) {

    private val lock = Any()
    private var file: File? = null
    private var pending: List<UsageBucket> = emptyList()
    private var lastFlush = 0L

    /** Datei festlegen (einmal je Prozess); schon gezählte Zahlen gehen beim nächsten [flush] mit. */
    fun attach(file: File) {
        synchronized(lock) { this.file = file }
    }

    /** Ein Hintergrund-Lauf (WorkManager, Wecker, Widget, Komplikation). */
    fun countRun() = record(runs = 1)

    /** Eine Netzanfrage mit [bytes] empfangenen Bytes (Körper der Antwort). */
    fun countRequest(bytes: Long) = record(requests = 1, bytes = bytes)

    /** Weitere empfangene Bytes ohne neue Anfrage (Live-Nachrichten einer WebSocket-Verbindung). */
    fun countBytes(bytes: Long) = record(bytes = bytes)

    private fun record(runs: Int = 0, requests: Int = 0, bytes: Long = 0) {
        val at = now()
        val due = synchronized(lock) {
            pending = UsageLog.add(pending, at, runs, requests, bytes.coerceAtLeast(0))
            file != null && at - lastFlush >= FLUSH_GAP_MILLIS
        }
        if (due) flush()
    }

    /** Gezähltes jetzt in die Datei (zusammengeführt mit ihrem Stand); ohne Datei bleibt es im Speicher. */
    fun flush() {
        synchronized(lock) {
            val target = file ?: return
            val at = now()
            lastFlush = at
            if (pending.isEmpty()) return
            val merged = UsageLog.merge(read(target), pending, at)
            if (write(target, merged)) pending = emptyList()
        }
    }

    /** Summe der letzten 24 h: Datei und noch nicht geschriebene Zahlen dieses Prozesses. */
    fun totals(): UsageTotals = synchronized(lock) {
        val at = now()
        UsageLog.totals(UsageLog.merge(file?.let { read(it) }.orEmpty(), pending, at), at)
    }

    private fun read(file: File): List<UsageBucket> =
        runCatching { if (file.exists()) UsageLog.decode(file.readText()) else emptyList() }.getOrDefault(emptyList())

    /** Erst daneben schreiben, dann umbenennen: nie eine halbe Datei. */
    private fun write(file: File, buckets: List<UsageBucket>): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(UsageLog.encode(buckets))
        if (!tmp.renameTo(file)) {
            file.writeText(UsageLog.encode(buckets))
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    companion object {
        const val FILE_NAME = "usage_log.txt"
        const val FLUSH_GAP_MILLIS = 60_000L

        /** Zähler des Prozesses (Handy-App bzw. Uhr-App). */
        val shared = UsageMeter()
    }
}
