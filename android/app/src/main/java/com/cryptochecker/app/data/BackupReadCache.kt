package com.cryptochecker.app.data

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Eine gewählte Sicherung einmal je Wiederherstellen lesen. Früher las jeder Schritt die Datei neu
 * (Passwort nötig? → ungültige Einträge → Wiederherstellen): eine verschlüsselte Datei bis 64 MB dreimal, mit zweimal
 * Argon2id (64 MiB am Stück) — auf Geräten mit wenig Arbeitsspeicher endete das in einem OutOfMemoryError.
 *
 * Gemerkt wird nur der letzte Stand einer Datei [K]: die gelesene Datei [F] und nach erfolgreichem Entschlüsseln der
 * Klartext samt einem Fingerabdruck des Passworts, mit dem er entstand (anderes Passwort → neu entschlüsseln; falsches →
 * nichts gemerkt). Eine andere Datei ersetzt den Stand. [take] gibt ihn für das Wiederherstellen ab und vergisst ihn;
 * [clear] beim Abbrechen und beim Wählen einer Datei (die Datei kann sich inzwischen geändert haben).
 *
 * Das Passwort liegt nicht im Klartext im (prozessweiten) Speicher: verglichen wird ein gesalzener
 * SHA-256 ([digest], Salz je Instanz). Der Klartext der Sicherung gilt nur [plainTtlMillis] lang (Passwort prüfen →
 * Rückfrage → Wiederherstellen); danach wird er beim nächsten Zugriff verworfen und bei Bedarf neu entschlüsselt.
 *
 * Generisch über Datei-Schlüssel und Dateityp, damit die Unit-Tests ohne `Uri` und Kryptografie auskommen.
 */
internal class BackupReadCache<K : Any, F : Any>(
    private val decrypt: (F, String?) -> String,
    /** Monotone Uhr in ms (Tests: eigene). */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val plainTtlMillis: Long = PLAIN_TTL_MILLIS,
) {
    // Die Sperre nur um den Zustand, nie um Lesen oder Argon2id: [clear] kommt vom Hauptthread (Abbrechen) und darf
    // nicht sekundenlang warten. [generation] zählt [clear] mit — was ein vorher begonnenes Lesen liefert, wird dann
    // nicht mehr gemerkt.
    private val lock = Any()
    private val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
    private var generation = 0L
    private var key: K? = null
    private var file: F? = null
    private var plain: String? = null
    private var plainDigest: ByteArray? = null
    private var plainUntil = 0L

    /** Gelesene Datei zu [uri]; liest nur, wenn noch keine oder eine andere gemerkt ist. */
    fun file(uri: K, read: () -> F): F {
        val started = synchronized(lock) {
            if (key == uri) file?.let { return it }
            generation
        }
        val f = read()
        synchronized(lock) {
            if (generation == started) {
                key = uri
                file = f
                dropPlain()
            }
        }
        return f
    }

    /** Klartext zu [uri] mit [password]; entschlüsselt nur einmal je Datei und Passwort (innerhalb von [plainTtlMillis]). */
    fun plain(uri: K, password: String?, read: () -> F): String {
        val f = file(uri, read)
        val fingerprint = digest(password)
        synchronized(lock) {
            if (plain != null && clock() > plainUntil) dropPlain()
            if (key == uri && file === f && plainDigest.contentEquals(fingerprint)) plain?.let { return it }
        }
        val text = decrypt(f, password)
        synchronized(lock) {
            if (key == uri && file === f) {
                plain = text
                plainDigest = fingerprint
                plainUntil = clock() + plainTtlMillis
            }
        }
        return text
    }

    /** Datei und Klartext für das Wiederherstellen; danach ist nichts mehr gemerkt (auch bei einem Fehler). */
    fun take(uri: K, password: String?, read: () -> F): Pair<F, String> = try {
        val f = file(uri, read)
        f to plain(uri, password, read)
    } finally {
        clear()
    }

    fun clear() {
        synchronized(lock) {
            generation++
            key = null
            file = null
            dropPlain()
        }
    }

    /** Ist gerade ein Klartext gemerkt (Tests)? */
    internal fun holdsPlain(): Boolean = synchronized(lock) { plain != null }

    private fun dropPlain() {
        plain = null
        plainDigest = null
        plainUntil = 0L
    }

    /** Gesalzener Fingerabdruck des Passworts; null = ohne Passwort (lesbare Sicherung). */
    private fun digest(password: String?): ByteArray? = password?.let {
        MessageDigest.getInstance("SHA-256").run {
            update(salt)
            digest(it.toByteArray(Charsets.UTF_8))
        }
    }

    private companion object {
        /** Passwort prüfen → Rückfrage lesen → Wiederherstellen: fünf Minuten reichen, länger bleibt nichts liegen. */
        const val PLAIN_TTL_MILLIS = 5 * 60_000L
        const val SALT_BYTES = 16
    }
}
