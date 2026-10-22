package com.cryptochecker.app.data.local

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.FileKind
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Fingerprint
import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.TableFingerprint
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.IOException

/**
 * Datei- und SQL-Schicht der Datenbank-Verschlüsselung ([DatabaseEncryptionMigration.Files]) mit SQLCipher.
 * Alle Schritte laufen über SQLCipher, auch auf der unverschlüsselten Datei (leere Passphrase = unverschlüsselt):
 * Zwei verschiedene SQLite-Bibliotheken gleichzeitig auf derselben Datei im selben Prozess vertragen sich nicht
 * (POSIX-Sperren). Jede Verbindung ist geschlossen, bevor Room die Datei öffnet.
 */
class SqlCipherDatabaseFiles(private val context: Context) : DatabaseEncryptionMigration.Files {

    init {
        SqlCipherLibrary.load()
    }

    private fun file(database: String): File = context.getDatabasePath(database)

    /** Exportkopie neben der Datei; erst nach der Prüfung wird sie auf den Dateinamen umbenannt. */
    private fun copy(database: String): File = File(file(database).path + COPY_SUFFIX)

    override fun kind(database: String): FileKind = kindOf(file(database))

    override fun hasExportCopy(database: String): Boolean = copy(database).exists()

    override fun deleteExportCopy(database: String) {
        val copy = copy(database)
        for (f in listOf(copy, File(copy.path + "-journal"), File(copy.path + "-wal"), File(copy.path + "-shm"))) {
            if (f.exists() && !f.delete()) throw IOException("${f.name} nicht gelöscht")
        }
    }

    override fun checkpoint(database: String) {
        // Schliessen der letzten Verbindung entfernt das (dann leere) WAL
        open(file(database), passphrase = null).use { db ->
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
    }

    override fun walBytes(database: String): Long = File(file(database).path + "-wal").length()

    override fun exportEncrypted(database: String, passphrase: ByteArray) {
        // CREATE_IF_NECESSARY: ATTACH übernimmt die Öffnungs-Flags der Hauptverbindung; ohne Erlaubnis zum Anlegen
        // scheitert die neue Exportkopie mit SQLITE_CANTOPEN (code 14) – gesehen auf dem Gerät, Build 61
        open(file(database), passphrase = null, create = true).use { db ->
            db.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf<Any?>(copy(database).path, String(passphrase, Charsets.US_ASCII)))
            try {
                db.query("SELECT sqlcipher_export('encrypted')").use { it.moveToFirst() }
                // sqlcipher_export übernimmt user_version bereits; ausdrücklich gesetzt, weil Room daran die Migrationen misst
                db.execSQL("PRAGMA encrypted.user_version = ${db.version}")
            } finally {
                db.execSQL("DETACH DATABASE encrypted")
            }
        }
    }

    override fun fingerprintOriginal(database: String): Fingerprint = fingerprint(file(database), passphrase = null)

    override fun fingerprintExportCopy(database: String, passphrase: ByteArray): Fingerprint =
        fingerprint(copy(database), passphrase)

    override fun deleteSidecars(database: String) {
        val path = file(database).path
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val f = File(path + suffix)
            if (f.exists() && !f.delete()) throw IOException("${f.name} nicht gelöscht")
        }
    }

    override fun replaceWithExportCopy(database: String) {
        // rename(2): ersetzt die Zieldatei atomar
        if (!copy(database).renameTo(file(database))) throw IOException("$database: Kopie nicht umbenannt")
    }

    override fun setAside(database: String) {
        val original = file(database)
        var target = File(original.path + ASIDE_SUFFIX)
        if (target.exists()) target = File(original.path + ASIDE_SUFFIX + "-" + System.currentTimeMillis())
        if (!original.renameTo(target)) throw IOException("$database nicht beiseitegelegt")
        // Nebendateien mit gleichem Stamm, damit SQLite sie zur beiseitegelegten Datei findet
        for (suffix in listOf("-wal", "-shm")) {
            val side = File(original.path + suffix)
            if (side.exists() && !side.renameTo(File(target.path + suffix))) side.delete()
        }
        File(original.path + "-journal").delete()
    }

    override fun opens(database: String, passphrase: ByteArray): Boolean = opensFile(file(database), passphrase)

    private fun opensFile(file: File, passphrase: ByteArray): Boolean =
        try {
            open(file, passphrase).use { db -> db.query("SELECT COUNT(*) FROM sqlite_master").use { it.moveToFirst() } }
            true
        } catch (e: Exception) {
            // SQLITE_NOTADB: mit diesem Schlüssel keine Datenbank (die Klasse der Ausnahme hängt von der Bibliothek ab)
            if (e.isNotADatabase()) false else throw e
        }

    override fun asideCopies(database: String): List<String> {
        val original = file(database)
        val base = original.name + ASIDE_SUFFIX
        return original.parentFile?.listFiles()
            ?.filter { f -> isAsideName(f.name, base) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name }
            .orEmpty()
    }

    override fun asideOpens(database: String, copy: String, passphrase: ByteArray): Boolean {
        val aside = File(file(database).parentFile, copy)
        require(isAsideName(aside.name, file(database).name + ASIDE_SUFFIX)) { "$copy ist keine beiseitegelegte Datei" }
        return opensFile(aside, passphrase)
    }

    override fun restoreAside(database: String, copy: String) {
        val original = file(database)
        val aside = File(original.parentFile, copy)
        require(isAsideName(aside.name, original.name + ASIDE_SUFFIX) && aside.exists()) { "$copy fehlt" }
        // Die inzwischen neu begonnene Datei bleibt erhalten (samt -wal/-shm): «.replaced», bei Bedarf mit Zeitstempel
        var replaced = File(original.path + REPLACED_SUFFIX)
        if (replaced.exists()) replaced = File(original.path + REPLACED_SUFFIX + "-" + System.currentTimeMillis())
        val hadOriginal = original.exists()
        if (hadOriginal) {
            if (!original.renameTo(replaced)) throw IOException("$database nicht beiseitegelegt")
            for (suffix in listOf("-wal", "-shm")) {
                val side = File(original.path + suffix)
                if (side.exists() && !side.renameTo(File(replaced.path + suffix))) side.delete()
            }
        }
        File(original.path + "-journal").delete()
        if (!aside.renameTo(original)) {
            // Zurück, wie es war: nie ohne Datei dastehen
            if (hadOriginal) replaced.renameTo(original)
            throw IOException("$copy nicht zurückgeholt")
        }
        for (suffix in listOf("-wal", "-shm")) {
            val side = File(aside.path + suffix)
            if (side.exists() && !side.renameTo(File(original.path + suffix))) side.delete()
        }
    }

    override fun deleteAside(database: String, copy: String) {
        val original = file(database)
        val aside = File(original.parentFile, copy)
        // Nur eine beiseitegelegte Datei, nie die aktuelle (anderer Name) oder eine «ersetzte»
        require(isAsideName(aside.name, original.name + ASIDE_SUFFIX)) { "$copy ist keine beiseitegelegte Datei" }
        for (f in listOf(aside, File(aside.path + "-wal"), File(aside.path + "-shm"), File(aside.path + "-journal"))) {
            if (f.exists() && !f.delete()) throw IOException("${f.name} nicht gelöscht")
        }
    }

    /** `<db>.unreadable` bzw. `<db>.unreadable-<Zeit>` ohne Nebendateien. */
    private fun isAsideName(name: String, base: String): Boolean =
        (name == base || name.startsWith("$base-")) && !name.endsWith("-wal") && !name.endsWith("-shm") && !name.endsWith("-journal")

    private fun Throwable.isNotADatabase(): Boolean =
        message.orEmpty().let { "SQLITE_NOTADB" in it || "file is not a database" in it } || cause?.isNotADatabase() == true

    /** Zustand einer Datei: `user_version` und je Tabelle Zeilen und Prüfsumme über alle Spalten. */
    fun fingerprint(file: File, passphrase: ByteArray?): Fingerprint =
        open(file, passphrase).use { db ->
            val tables = db.query(TABLES_SQL).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            Fingerprint(
                userVersion = db.version,
                tables = tables.associateWith { table ->
                    db.query("SELECT * FROM `${table.replace("`", "``")}`").use { c ->
                        var fp = TableFingerprint(0, 0)
                        while (c.moveToNext()) fp = DatabaseEncryptionMigration.addRow(fp, List(c.columnCount) { c.value(it) })
                        fp
                    }
                },
            )
        }

    private fun Cursor.value(column: Int): Any? = when (getType(column)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(column)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(column)
        Cursor.FIELD_TYPE_BLOB -> getBlob(column)
        else -> getString(column)
    }

    companion object {
        /** Kopf einer unverschlüsselten SQLite-Datei; eine SQLCipher-Datei beginnt mit 16 zufälligen Bytes (Salz). */
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        const val COPY_SUFFIX = ".encrypting"
        const val ASIDE_SUFFIX = ".unreadable"

        /** Durch eine zurückgeholte Datei ersetzte (inzwischen neu begonnene) Datenbank — bleibt, wird nie gelöscht. */
        const val REPLACED_SUFFIX = ".replaced"

        /** Alle Tabellen samt Zähler der AUTOINCREMENT-Ids; ohne SQLite-Statistik und Android-Metadaten (Gebietsschema). */
        private const val TABLES_SQL =
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name != 'android_metadata' " +
                "AND (name NOT LIKE 'sqlite\\_%' ESCAPE '\\' OR name = 'sqlite_sequence') ORDER BY name"

        fun kindOf(file: File): FileKind {
            if (!file.exists() || file.length() == 0L) return FileKind.ABSENT
            val head = ByteArray(SQLITE_HEADER.size)
            val read = file.inputStream().use { it.read(head) }
            // Zu kurz für einen Kopf: als unverschlüsselt behandeln — dann öffnet Room sie wie bis Build 58
            if (read < head.size) return FileKind.PLAINTEXT
            return if (head.contentEquals(SQLITE_HEADER)) FileKind.PLAINTEXT else FileKind.ENCRYPTED
        }

        /**
         * Nie löschen: Der Standard-Fehlerbehandler (wie bei Android) löscht eine Datei, die SQLite für beschädigt hält —
         * auch eine nur mit falschem Schlüssel geöffnete. Hier bleibt sie, der Fehler geht an den Aufrufer.
         */
        private val keepOnCorruption = DatabaseErrorHandler { _, _ -> }

        /**
         * Verbindung über SQLCipher zu einer bestehenden Datei; `passphrase` null = unverschlüsselte Datei.
         * [create]: angehängte Datenbanken (ATTACH) dürfen neu angelegt werden – die Hauptdatei besteht ohnehin.
         */
        fun open(file: File, passphrase: ByteArray?, create: Boolean = false): SupportSQLiteDatabase =
            SQLiteDatabase.openDatabase(
                file.path, passphrase?.let { String(it, Charsets.US_ASCII) } ?: "", null,
                if (create) SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY else SQLiteDatabase.OPEN_READWRITE,
                keepOnCorruption, null,
            )
    }
}

/** Lädt die native SQLCipher-Bibliothek einmal je Prozess (vor jedem Zugriff, auch im Sicherungs-Agenten). */
object SqlCipherLibrary {
    private val loaded by lazy { System.loadLibrary("sqlcipher") }

    fun load() = loaded
}
