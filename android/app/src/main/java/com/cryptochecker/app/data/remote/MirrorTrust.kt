package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.mirror.MirrorManifest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Lädt eine Adresse mit Höchstgrösse (Bytes); null bei jedem Fehler. */
internal typealias MirrorLoader = suspend (url: String, maxBytes: Long) -> String?

/**
 * Prüft Spiegel-Dateien gegen das signierte Manifest ([MirrorManifest]). Manifest und Signatur kommen nur in den
 * Speicher: gültig höchstens [OK_TTL_MILLIS], nach einem Fehlschlag (fehlt, ungültig, zu alt, nicht erreichbar) erst
 * nach [FAIL_TTL_MILLIS] wieder versucht — so kostet ein fehlendes Manifest nicht jede Spiegel-Anfrage zwei Abrufe.
 * Passt eine Datei nicht zum gemerkten Manifest (die Action lief seither), wird es einmal neu geholt, höchstens
 * einmal je [RELOAD_AFTER_MILLIS]. Gleich in iOS `MirrorTrust.swift`.
 */
internal object MirrorTrust {
    private const val OK_TTL_MILLIS = 15L * 60 * 1000
    private const val FAIL_TTL_MILLIS = 5L * 60 * 1000
    private const val RELOAD_AFTER_MILLIS = 60L * 1000

    private class Cached(val loadedAt: Long, val verification: MirrorManifest.Verification)

    private val mutex = Mutex()

    @Volatile
    private var cached: Cached? = null

    /** Stand der letzten Prüfung; null = noch keine. */
    val lastStatus: MirrorManifest.Status? get() = cached?.verification?.status

    /**
     * Darf [text] als Spiegel-Datei [file] genutzt werden (gültige Signatur, Manifest frisch, SHA-256 stimmt)?
     * [loader] lädt eine Adresse mit Höchstgrösse; null bei jedem Fehler (Abbruch wird weitergeworfen).
     */
    suspend fun verified(file: String, text: String, loader: MirrorLoader): Boolean {
        val first = manifest(loader, OK_TTL_MILLIS)
        if (matches(first, file, text)) return true
        if (first.status != MirrorManifest.Status.OK) return false
        // Datei neuer als das gemerkte Manifest: einmal neu holen
        return matches(manifest(loader, RELOAD_AFTER_MILLIS), file, text)
    }

    private fun matches(v: MirrorManifest.Verification, file: String, text: String): Boolean =
        v.manifest?.let { MirrorManifest.fileStatus(it, file, text) == MirrorManifest.FileStatus.OK } ?: false

    private suspend fun manifest(loader: MirrorLoader, okTtlMillis: Long): MirrorManifest.Verification = mutex.withLock {
        val now = System.currentTimeMillis()
        cached?.let { c ->
            val ttl = if (c.verification.status == MirrorManifest.Status.OK) okTtlMillis else FAIL_TTL_MILLIS
            if (now - c.loadedAt in 0 until ttl) return@withLock c.verification
        }
        val manifestText = loader(MirrorManifest.URL, MirrorManifest.MAX_BYTES)
        val signature = manifestText?.let { loader(MirrorManifest.SIGNATURE_URL, MirrorManifest.SIGNATURE_MAX_BYTES) }
        val verification = MirrorManifest.verify(manifestText, signature, System.currentTimeMillis())
        if (verification.status != MirrorManifest.Status.OK) Timber.d("Spiegel-Manifest: %s", verification.status)
        cached = Cached(now, verification)
        verification
    }

    /** Nur Tests: gemerktes Manifest vergessen. */
    internal fun resetForTest() {
        cached = null
    }
}
