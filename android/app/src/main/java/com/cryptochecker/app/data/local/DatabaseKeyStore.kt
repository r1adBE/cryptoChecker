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
    /** Nur Tests: eigene Menge «in diesem Lauf vermerkt» = neuer Prozess ([DatabaseKeyRetry.SuspectMarker]). */
    runSeen: MutableSet<String>? = null,
) : DatabaseEncryptionMigration.Keys {

    private fun keyFile(database: String) = File(directory, "$database.key")

    /** Verdacht auf verlorenen Keystore-Schlüssel je Datenbank, erst im nächsten Lauf wirksam ([DatabaseKeyRetry]). */
    private val suspects = runSeen?.let { DatabaseKeyRetry.SuspectMarker(directory, it) } ?: DatabaseKeyRetry.SuspectMarker(directory)

    /**
     * Von [create] beiseitegelegte frühere Schlüssel ([PREVIOUS_SUFFIX], weitere nummeriert `.previous.1`, `.previous.2` …),
     * neuester zuerst. In älteren Versionen überschrieb ein zweites Beiseitelegen den ersten — der Schlüssel einer
     * früher beiseitegelegten Datei war dann weg.
     */
    private fun previousKeyFiles(database: String): List<File> =
        slotFiles(database, PREVIOUS_SUFFIX).sortedByDescending { slotNumber(it.name, keyFile(database).name + PREVIOUS_SUFFIX) }

    /** Dateien `<key><suffix>` und `<key><suffix>.<n>` im Schlüsselordner. */
    private fun slotFiles(database: String, suffix: String): List<File> {
        val base = keyFile(database).name + suffix
        return directory.listFiles()?.filter { slotNumber(it.name, base) != null }.orEmpty()
    }

    /** Nächster freier Platz `<key><suffix>` bzw. `<key><suffix>.<n>` ([nextSlotName]). */
    private fun nextSlot(database: String, suffix: String): File {
        val base = keyFile(database).name + suffix
        return File(directory, nextSlotName(slotFiles(database, suffix).map { it.name }.toSet(), base))
    }

    override fun load(database: String): KeyLookup {
        val file = keyFile(database)
        if (!file.exists()) return KeyLookup.Absent
        // Vorübergehende Keystore-Fehler (Dienst beschäftigt, kurz nach Start oder Update) kommen vor: wiederholen —
        // auch `UnrecoverableKeyException` und «kein Schlüssel» ([DatabaseKeyRetry])
        val outcome = DatabaseKeyRetry.run(ATTEMPTS, RETRY_DELAY_MILLIS, { Thread.sleep(it) }, { classify(it) }) {
            masterKey(database)?.let { decrypt(it, file.readBytes()) }
        }
        return when (outcome) {
            is DatabaseKeyRetry.Outcome.Value -> {
                suspects.clear(database)
                if (outcome.value.size != DatabaseEncryptionMigration.KEY_BYTES) KeyLookup.Absent
                else KeyLookup.Found(DatabaseEncryptionMigration.rawKeyLiteral(outcome.value))
            }
            // Endgültig: Keystore-Schlüssel ungültig gemacht oder ein anderer als beim Verschlüsseln
            is DatabaseKeyRetry.Outcome.Permanent -> KeyLookup.Absent
            is DatabaseKeyRetry.Outcome.Transient -> KeyLookup.Unavailable(outcome.error)
            is DatabaseKeyRetry.Outcome.Suspect -> {
                val error = outcome.error ?: IllegalStateException("$database: Keystore liefert keinen Schlüssel")
                // Eintrag noch da, nur gerade nicht herausgegeben: nie als verloren werten (Datei bleibt unberührt)
                if (outcome.error == null && aliasPresent(database) != false) KeyLookup.Unavailable(error)
                // Erst im nächsten Lauf als verloren werten: dann legt die Migration die Datei beiseite
                else if (suspects.escalate(database)) KeyLookup.Absent
                else KeyLookup.Unavailable(error)
            }
        }
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
                // Datenbank; die andere hat ihren eigenen und bleibt unberührt). Nie im selben Lauf, in dem
                // er zum ersten Mal unbrauchbar war ([DatabaseKeyRetry.SuspectMarker]): dann kein neuer Schlüssel in diesem Lauf
                if (!e.isPermanent()) throw e
                if (!suspects.escalate(database)) throw IOException("$database: Keystore-Eintrag erst im nächsten Lauf ersetzt", e)
                keyStore().deleteEntry(aliasFor(alias, database))
                null
            }
            usable ?: generateMasterKey(database)
        }
        val raw = ByteArray(DatabaseEncryptionMigration.KEY_BYTES).also(SecureRandom()::nextBytes)
        val sealed = encrypt(master, raw)
        directory.mkdirs()
        val file = keyFile(database)
        // Den bisherigen Schlüssel (endgültig unbrauchbar oder nie genutzt) nicht überschreiben, sondern nummeriert daneben
        // behalten — falls «endgültig» doch falsch war, holt der nächste Start die beiseitegelegte Datenbank damit zurück
        // (DatabaseEncryptionMigration); gelöscht erst, wenn keine beiseitegelegte Datei mehr wartet ([discardPrevious])
        if (file.exists() && !file.renameTo(nextSlot(database, PREVIOUS_SUFFIX))) {
            throw IOException("$database: bisheriger Schlüssel nicht beiseitegelegt")
        }
        val temp = File(file.path + ".tmp")
        temp.outputStream().use { out ->
            out.write(sealed)
            out.fd.sync()
        }
        if (!temp.renameTo(file)) throw IOException("$database: Schlüsseldatei nicht gespeichert")
        suspects.clear(database)
        return DatabaseEncryptionMigration.rawKeyLiteral(raw)
    }

    /**
     * Früheren Schlüssel löschen: Die verschlüsselte Datenbank liess sich eben mit dem gespeicherten Schlüssel öffnen
     * ([DatabaseEncryptionMigration]) — der beiseitegelegte bliebe sonst für immer liegen.
     */
    override fun discardPrevious(database: String) {
        for (previous in previousKeyFiles(database)) {
            if (previous.exists() && !previous.delete()) throw IOException("$database: früherer Schlüssel nicht gelöscht")
        }
    }

    override fun previous(database: String): List<DatabaseEncryptionMigration.PreviousKey> {
        val files = previousKeyFiles(database)
        if (files.isEmpty()) return emptyList()
        // Mit einem neu erzeugten Keystore-Schlüssel sind die früheren Dateien nicht mehr zu öffnen: dann keiner
        val master = runCatching { masterKey(database) }.getOrNull() ?: return emptyList()
        return files.mapNotNull { f ->
            val raw = runCatching { decrypt(master, f.readBytes()) }.getOrNull() ?: return@mapNotNull null
            if (raw.size != DatabaseEncryptionMigration.KEY_BYTES) return@mapNotNull null
            DatabaseEncryptionMigration.PreviousKey(f.name, DatabaseEncryptionMigration.rawKeyLiteral(raw))
        }
    }

    override fun promote(database: String, id: String) {
        val source = File(directory, id)
        val base = keyFile(database).name + PREVIOUS_SUFFIX
        require(slotNumber(id, base) != null && source.exists()) { "$database: kein früherer Schlüssel $id" }
        val file = keyFile(database)
        // Der bisher gespeicherte gehört zur beiseitegelegten neuen Datei: als «ersetzt» behalten, nicht löschen
        if (file.exists() && !file.renameTo(nextSlot(database, REPLACED_SUFFIX))) {
            throw IOException("$database: gespeicherter Schlüssel nicht beiseitegelegt")
        }
        if (!source.renameTo(file)) throw IOException("$database: früherer Schlüssel nicht übernommen")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun masterKey(database: String): SecretKey? = keyStore().getKey(aliasFor(alias, database), null) as SecretKey?

    /** Steht der Keystore-Eintrag von [database] noch im Verzeichnis? null = unbekannt (Keystore nicht lesbar). */
    private fun aliasPresent(database: String): Boolean? =
        runCatching { keyStore().containsAlias(aliasFor(alias, database)) }.getOrNull()

    /** Ungültig gemacht oder falsches Chiffrat: sofort endgültig; `UnrecoverableKeyException`: erst verdächtig. */
    private fun classify(e: Throwable): DatabaseKeyRetry.Failure = when {
        e.isDefinite() -> DatabaseKeyRetry.Failure.PERMANENT
        e.isPermanent() -> DatabaseKeyRetry.Failure.SUSPECT
        else -> DatabaseKeyRetry.Failure.TRANSIENT
    }

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

    private fun Throwable.isDefinite(): Boolean =
        this is KeyPermanentlyInvalidatedException || this is AEADBadTagException || cause?.isDefinite() == true

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

        /** Endung der von [create] beiseitegelegten früheren Schlüssel (weitere mit «.1», «.2» …). */
        const val PREVIOUS_SUFFIX = ".previous"

        /** Endung eines durch einen früheren ersetzten Schlüssels ([promote]; gehört zur beiseitegelegten neuen Datei). */
        const val REPLACED_SUFFIX = ".replaced"

        /**
         * Name eines Platzes [base] bzw. `[base].<n>` → Nummer (0 für [base] selbst); null = kein solcher Platz. Rein, für
         * Tests.
         */
        fun slotNumber(name: String, base: String): Int? = when {
            name == base -> 0
            name.startsWith("$base.") -> name.removePrefix("$base.").toIntOrNull()?.takeIf { it > 0 }
            else -> null
        }

        /** Nächster freier Platz neben [existing]: zuerst [base], dann `[base].<grösste Nummer + 1>`. Rein, für Tests. */
        fun nextSlotName(existing: Set<String>, base: String): String {
            val numbers = existing.mapNotNull { slotNumber(it, base) }
            if (0 !in numbers) return base
            return "$base.${(numbers.maxOrNull() ?: 0) + 1}"
        }
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val ATTEMPTS = 3
        /** 3 × 500 ms: ein nach Start oder Update noch beschäftigter Keystore braucht oft mehr als 150 ms. */
        private const val RETRY_DELAY_MILLIS = 500L

        /** Sperre für Lesen/Anlegen des Keystore-Schlüssels, über alle Instanzen (App und Sicherungs-Agent). */
        private val MASTER_KEY_LOCK = Any()
    }
}
