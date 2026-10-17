package com.cryptochecker.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.KeyLookup
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Schlüssel der verschlüsselten Datenbanken ([DatabaseEncryptionMigration.Keys]): je Datenbank 32 zufällige Bytes,
 * mit einem AES-256-GCM-Schlüssel im Android Keystore verschlüsselt (je Datenbank ein eigener, [aliasFor]; verlässt
 * den Keystore nie) und als
 * kleine Datei in `noBackupFilesDir/[DIRECTORY]` gespeichert — nie in einer Sicherung (Auto Backup und Übertragung
 * Gerät zu Gerät lassen `noBackupFilesDir` immer aus; ein Keystore-Schlüssel ist ohnehin an dieses Gerät gebunden).
 *
 * Ohne Nutzer-Bestätigung (keine Biometrie, keine Geräte-Sperre je Zugriff): WorkManager, Widgets und Alarm-Empfänger
 * öffnen die Datenbank im Hintergrund. Nutzbar ab dem ersten Entsperren nach dem Start; kein Teil der App läuft vorher
 * (keine `directBootAware`-Komponenten).
 *
 * Dateiformat: 1 Byte Version (1), 12 Bytes IV, Chiffrat samt 16-Byte-Tag.
 */
class DatabaseKeyStore(
    context: Context,
    /** Stamm der Keystore-Aliase; je Datenbank ein eigener Schlüssel ([aliasFor]). */
    private val alias: String = ALIAS,
    private val directory: File = File(context.noBackupFilesDir, DIRECTORY),
) : DatabaseEncryptionMigration.Keys {

    private fun keyFile(database: String) = File(directory, "$database.key")

    /** Von [create] beiseitegelegter früherer Schlüssel ([PREVIOUS_SUFFIX]). */
    private fun previousKeyFile(database: String) = File(keyFile(database).path + PREVIOUS_SUFFIX)

    override fun load(database: String): KeyLookup {
        val file = keyFile(database)
        if (!file.exists()) return KeyLookup.Absent
        var lastError: Exception? = null
        // Vorübergehende Keystore-Fehler (Dienst beschäftigt) kommen vor: kurz wiederholen
        repeat(ATTEMPTS) { attempt ->
            try {
                val master = masterKey(database) ?: return KeyLookup.Absent
                val raw = decrypt(master, file.readBytes())
                if (raw.size != DatabaseEncryptionMigration.KEY_BYTES) return KeyLookup.Absent
                return KeyLookup.Found(DatabaseEncryptionMigration.rawKeyLiteral(raw))
            } catch (e: Exception) {
                // Endgültig: Keystore-Schlüssel ungültig, nicht mehr herstellbar oder ein anderer als beim Verschlüsseln
                if (e.isPermanent()) return KeyLookup.Absent
                lastError = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MILLIS)
            }
        }
        return KeyLookup.Unavailable(lastError ?: IllegalStateException("Keystore"))
    }

    override fun create(database: String): ByteArray {
        // Prozessweit gesperrt: Zwei gleichzeitige erste Zugriffe (Room-Thread, WorkManager) erzeugten sonst je einen
        // Keystore-Schlüssel unter demselben Alias — der zweite überschriebe den ersten, und die eben geschriebene
        // Schlüsseldatei liesse sich nach dem nächsten Start nie mehr entschlüsseln
        val master = synchronized(MASTER_KEY_LOCK) {
            val usable = try {
                masterKey(database)
            } catch (e: Exception) {
                // Endgültig kaputter Keystore-Eintrag: Daran hängt nichts Lesbares mehr — ersetzen (nur der dieser
                // Datenbank; die andere hat ihren eigenen und bleibt unberührt)
                if (!e.isPermanent()) throw e
                keyStore().deleteEntry(aliasFor(alias, database))
                null
            }
            usable ?: generateMasterKey(database)
        }
        val raw = ByteArray(DatabaseEncryptionMigration.KEY_BYTES).also(SecureRandom()::nextBytes)
        val sealed = encrypt(master, raw)
        directory.mkdirs()
        val file = keyFile(database)
        // Den bisherigen Schlüssel (endgültig unbrauchbar oder nie genutzt) nicht überschreiben, sondern daneben
        // behalten — falls «endgültig» doch falsch war, liesse sich die beiseitegelegte Datenbank noch öffnen
        // — bis sich die Datenbank mit dem neuen Schlüssel öffnen liess ([discardPrevious])
        if (file.exists()) file.renameTo(previousKeyFile(database))
        val temp = File(file.path + ".tmp")
        temp.outputStream().use { out ->
            out.write(sealed)
            out.fd.sync()
        }
        if (!temp.renameTo(file)) throw IOException("$database: Schlüsseldatei nicht gespeichert")
        return DatabaseEncryptionMigration.rawKeyLiteral(raw)
    }

    /**
     * Früheren Schlüssel löschen: Die verschlüsselte Datenbank liess sich eben mit dem gespeicherten Schlüssel öffnen
     * ([DatabaseEncryptionMigration]) — der beiseitegelegte bliebe sonst für immer liegen.
     */
    override fun discardPrevious(database: String) {
        val previous = previousKeyFile(database)
        if (previous.exists() && !previous.delete()) throw IOException("$database: früherer Schlüssel nicht gelöscht")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun masterKey(database: String): SecretKey? = keyStore().getKey(aliasFor(alias, database), null) as SecretKey?

    private fun generateMasterKey(database: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(aliasFor(alias, database), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(master: SecretKey, raw: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, master) // IV vom Keystore (zufällig, randomizedEncryptionRequired)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unerwartete IV-Länge ${iv.size}" }
        return byteArrayOf(FORMAT_VERSION) + iv + cipher.doFinal(raw)
    }

    private fun decrypt(master: SecretKey, sealed: ByteArray): ByteArray {
        if (sealed.size < 1 + IV_BYTES + TAG_BYTES || sealed[0] != FORMAT_VERSION) throw AEADBadTagException("Format")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(TAG_BYTES * 8, sealed, 1, IV_BYTES))
        return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
    }

    private fun Throwable.isPermanent(): Boolean =
        this is KeyPermanentlyInvalidatedException || this is UnrecoverableKeyException || this is AEADBadTagException ||
            cause?.isPermanent() == true

    companion object {
        const val ALIAS = "cryptochecker_db_key_v1"

        /**
         * Keystore-Alias je Datenbank: Ein endgültig verlorener Schlüssel trifft nur diese eine Datenbank
         * (`…_app`, `…_portfolio`; andere Namen, z. B. in Tests, mit dem Dateinamen).
         */
        fun aliasFor(base: String, database: String): String = base + "_" + when (database) {
            AppDatabase.DB_NAME -> "app"
            PortfolioDatabase.DB_NAME -> "portfolio"
            else -> database
        }
        const val DIRECTORY = "db_keys"

        /** Endung des von [create] beiseitegelegten früheren Schlüssels. */
        const val PREVIOUS_SUFFIX = ".previous"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val ATTEMPTS = 3
        private const val RETRY_DELAY_MILLIS = 150L

        /** Sperre für Lesen/Anlegen des Keystore-Schlüssels, über alle Instanzen (App und Sicherungs-Agent). */
        private val MASTER_KEY_LOCK = Any()
    }
}
