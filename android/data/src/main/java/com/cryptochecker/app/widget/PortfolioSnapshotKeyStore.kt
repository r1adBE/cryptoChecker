package com.cryptochecker.app.widget

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.cryptochecker.app.data.local.DatabaseKeyRetry
import com.cryptochecker.app.widget.PortfolioSnapshotSeal.KeyLookup
import timber.log.Timber
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Schlüssel der versiegelten Widget-Aufnahme ([PortfolioSnapshotSeal]): ein AES-256-GCM-Schlüssel im Android Keystore
 * unter eigenem Alias ([ALIAS]) — bewusst nicht an die Datenbank-Schlüssel (DatabaseKeyStore) gekoppelt: Geht er
 * verloren, fehlt nur die Widget-Aufnahme, die die App beim nächsten Aktualisieren neu berechnet.
 *
 * Ohne Nutzer-Bestätigung (wie DatabaseKeyStore): Widgets und WorkManager lesen im Hintergrund, ab dem ersten
 * Entsperren nach dem Start. Der Schlüssel verlässt den Keystore nie und geht in keine Sicherung.
 *
 * Gleiche Klasse für Journal des Wiederherstellens und Alarm-Protokoll (eigene Aliase, mit [suspects]): Daran hängen
 * Daten, die sich nicht neu berechnen lassen. Dort gilt die Regel der Datenbank-Schlüssel ([DatabaseKeyRetry]): Nur ein
 * ungültig gemachter Schlüssel ist sofort verloren; `UnrecoverableKeyException` erst, wenn sie auch in einem späteren
 * Lauf wieder auftritt, und ein vorhandener Eintrag, den der Keystore gerade nicht herausgibt, nie.
 */
class PortfolioSnapshotKeyStore(
    private val alias: String = ALIAS,
    /** Verdachts-Merker (Journal, Alarm-Protokoll); null = Widget-Aufnahme, ein kaputter Eintrag wird sofort ersetzt. */
    private val suspects: DatabaseKeyRetry.SuspectMarker? = null,
) : PortfolioSnapshotSeal.Keys {

    override fun lookup(): KeyLookup {
        var lastError: Exception? = null
        // Vorübergehende Keystore-Fehler (Dienst beschäftigt) kommen vor: kurz wiederholen
        repeat(ATTEMPTS) { attempt ->
            try {
                // Prozessweit gesperrt: zwei gleichzeitige erste Zugriffe (Widget, Aktualisierung) erzeugten sonst je
                // einen Schlüssel unter demselben Alias, und die eben versiegelte Aufnahme wäre nicht mehr lesbar
                val key = synchronized(KEY_LOCK) { existing() ?: withheld() ?: generate() }
                suspects?.clear(alias)
                return KeyLookup.Found(key)
            } catch (e: Exception) {
                // Endgültig kaputter Eintrag: ersetzen ([discardIfBroken])
                discardIfBroken(e)
                lastError = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MILLIS)
            }
        }
        Timber.d(lastError, "Portfolio-Widget: Schlüssel nicht verfügbar")
        return KeyLookup.Unavailable
    }

    override fun discardIfBroken(error: Throwable) {
        if (!error.isPermanent()) return
        val marker = suspects
        // Widget-Aufnahme (Zwischenspeicher) oder ungültig gemacht: sofort ersetzen; sonst erst im nächsten Lauf — ein
        // vorübergehender Fehler ist bis dahin meist vorbei
        if (marker == null || error.isInvalidated() || marker.escalate(alias)) deleteEntry()
    }

    /**
     * Eintrag vorhanden, aber vom Keystore gerade nicht herausgegeben: mit [suspects] nie überschreiben (Fehler, nichts
     * lesen und schreiben); null = keiner da oder Widget-Aufnahme, neu erzeugen.
     */
    private fun withheld(): SecretKey? {
        if (suspects == null) return null
        // Verzeichnis nicht lesbar: unbekannt, also ebenfalls nicht überschreiben
        val present = runCatching { keyStore().containsAlias(alias) }.getOrDefault(true)
        if (!present) return null
        throw IllegalStateException("Keystore gibt den Schlüssel gerade nicht heraus")
    }

    private fun deleteEntry() {
        runCatching { synchronized(KEY_LOCK) { keyStore().deleteEntry(alias) } }
            .onFailure { Timber.d(it, "Portfolio-Widget: Schlüssel nicht entfernt") }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existing(): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun Throwable.isPermanent(): Boolean =
        this is KeyPermanentlyInvalidatedException || this is UnrecoverableKeyException || cause?.isPermanent() == true

    private fun Throwable.isInvalidated(): Boolean =
        this is KeyPermanentlyInvalidatedException || cause?.isInvalidated() == true

    companion object {
        const val ALIAS = "cryptochecker_widget_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ATTEMPTS = 3
        private const val RETRY_DELAY_MILLIS = 150L

        /** Sperre für Lesen/Anlegen des Keystore-Schlüssels, über alle Instanzen. */
        private val KEY_LOCK = Any()
    }
}
