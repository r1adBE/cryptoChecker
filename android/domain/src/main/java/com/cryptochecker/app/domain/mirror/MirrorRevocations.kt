package com.cryptochecker.app.domain.mirror

import java.io.File

/**
 * Dauerhaft gesperrte Schlüssel des Daten-Spiegels (Schlüssel-Ids, [MirrorManifest.keyId]): Die Liste wächst nur —
 * eine Sperre aus einem gültigen Manifest ([MirrorManifest.Verification.revoke]) gilt auch nach einem Neustart und
 * ohne erneutes Manifest. Gespeichert eine Id je Zeile in [store] (null = nur im Speicher, z. B. in Tests); eine
 * unlesbare Datei gilt als leer. Gleich in iOS `MirrorRevocations.swift`.
 */
class MirrorRevocations(private val store: Store?) {

    /** Ablage der Ids (Text, eine Id je Zeile). */
    interface Store {
        fun load(): String?
        fun save(text: String)
    }

    /** Datei im App-Ordner ohne Sicherung; Schreiben über eine Zwischendatei. */
    class FileStore(private val file: File) : Store {
        override fun load(): String? =
            try {
                if (file.isFile) file.readText() else null
            } catch (_: Exception) {
                null
            }

        override fun save(text: String) {
            try {
                val staging = File(file.parentFile, "${file.name}.tmp")
                staging.writeText(text)
                if (staging.renameTo(file)) return
                // Manche Dateisysteme ersetzen beim Umbenennen nicht: alte Datei weg, dann nochmals
                file.delete()
                staging.renameTo(file)
            } catch (_: Exception) {
                // Scheitert das Schreiben, gilt die Sperre bis zum Neustart; das nächste Manifest sperrt erneut
            }
        }
    }

    private var cache: Set<String>? = null

    /** Gesperrte Schlüssel-Ids. */
    @Synchronized
    fun current(): Set<String> = cache ?: decode(store?.load()).also { cache = it }

    /** [ids] dauerhaft sperren (nie wieder entfernt); nichts Neues = nichts schreiben. */
    @Synchronized
    fun add(ids: Collection<String>) {
        val now = current()
        val next = LinkedHashSet(now).apply { addAll(ids.filter { valid(it) }) }
        if (next.size == now.size) return
        cache = next
        store?.save(next.joinToString("\n", postfix = "\n"))
    }

    companion object {
        /** Dateiname im Ordner ohne Sicherung (`noBackupFilesDir`). */
        const val FILE_NAME = "mirror_revoked.txt"

        /** Gemeinsame Liste der App; `CryptoCheckerApp` setzt beim Start die Datei, davor nur im Speicher. */
        @Volatile
        var shared: MirrorRevocations = MirrorRevocations(null)

        fun decode(text: String?): Set<String> =
            text?.lineSequence()?.map { it.trim() }?.filter { valid(it) }?.toCollection(LinkedHashSet()) ?: emptySet()

        private fun valid(id: String) = id.length == 64 && id.all { it in "0123456789abcdef" }
    }
}
