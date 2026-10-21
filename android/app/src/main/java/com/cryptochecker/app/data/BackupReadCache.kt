package com.cryptochecker.app.data

/**
 * Eine gewählte Sicherung einmal je Wiederherstellen lesen (seit Build 93). Bis dahin las jeder Schritt die Datei neu
 * (Passwort nötig? → ungültige Einträge → Wiederherstellen): eine verschlüsselte Datei bis 64 MB dreimal, mit zweimal
 * Argon2id (64 MiB am Stück) — auf Geräten mit wenig Arbeitsspeicher endete das in einem OutOfMemoryError.
 *
 * Gemerkt wird nur der letzte Stand einer Datei [K]: die gelesene Datei [F] und nach erfolgreichem Entschlüsseln der
 * Klartext samt dem Passwort, mit dem er entstand (anderes Passwort → neu entschlüsseln; falsches → nichts gemerkt).
 * Eine andere Datei ersetzt den Stand. [take] gibt ihn für das Wiederherstellen ab und vergisst ihn; [clear] beim
 * Abbrechen und beim Wählen einer Datei (die Datei kann sich inzwischen geändert haben).
 *
 * Generisch über Datei-Schlüssel und Dateityp, damit die Unit-Tests ohne `Uri` und Kryptografie auskommen.
 */
internal class BackupReadCache<K : Any, F : Any>(
    private val decrypt: (F, String?) -> String,
) {
    // Die Sperre nur um den Zustand, nie um Lesen oder Argon2id: [clear] kommt vom Hauptthread (Abbrechen) und darf
    // nicht sekundenlang warten. [generation] zählt [clear] mit — was ein vorher begonnenes Lesen liefert, wird dann
    // nicht mehr gemerkt.
    private val lock = Any()
    private var generation = 0L
    private var key: K? = null
    private var file: F? = null
    private var plain: String? = null
    private var plainPassword: String? = null

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
                plain = null
                plainPassword = null
            }
        }
        return f
    }

    /** Klartext zu [uri] mit [password]; entschlüsselt nur einmal je Datei und Passwort. */
    fun plain(uri: K, password: String?, read: () -> F): String {
        val f = file(uri, read)
        synchronized(lock) {
            if (key == uri && file === f && plainPassword == password) plain?.let { return it }
        }
        val text = decrypt(f, password)
        synchronized(lock) {
            if (key == uri && file === f) {
                plain = text
                plainPassword = password
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
            plain = null
            plainPassword = null
        }
    }
}
