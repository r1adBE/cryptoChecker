package com.cryptochecker.app.data.local

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cryptochecker.app.data.local.DatabaseOpenPlanner.FileKind
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.IOException

/**
 * Datei- und SQL-Schicht der Datenbank-Verschlüsselung ([DatabaseOpenPlanner.Files]) mit SQLCipher. Jede Verbindung ist
 * geschlossen, bevor Room die Datei öffnet. [restoredCopies]: je Datenbank der Name der unverschlüsselten Kopie, die eine
 * Systemsicherung wiederherstellt ([SystemBackupAgent]).
 */
class SqlCipherDatabaseFiles(
    private val context: Context,
    private val restoredCopies: Map<String, String> = mapOf(AppDatabase.DB_NAME to SystemBackupAgent.EXPORT_NAME),
) : DatabaseOpenPlanner.Files {

    init {
        SqlCipherLibrary.load()
    }

    private fun file(database: String): File = context.getDatabasePath(database)

    private fun restoredCopy(database: String): File? = restoredCopies[database]?.let(context::getDatabasePath)

    override fun kind(database: String): FileKind = kindOf(file(database))

    override fun hasRestoredCopy(database: String): Boolean = restoredCopy(database)?.exists() == true

    override fun encryptRestoredCopy(database: String, passphrase: ByteArray) {
        val copy = restoredCopy(database) ?: throw IOException("$database: keine wiederhergestellte Kopie vorgesehen")
        encryptCopy(copy, file(database), passphrase)
    }

    /**
     * Unverschlüsselte Datei [source] verschlüsselt mit [passphrase] zu [target] machen: erst in eine Kopie neben [target]
     * ([COPY_SUFFIX]), geprüft ([exportVerified]), dann atomar auf den Dateinamen umbenannt; erst danach wird [source]
     * gelöscht. Scheitert etwas, ist die Kopie weg und [source] unverändert.
     */
    fun encryptCopy(source: File, target: File, passphrase: ByteArray) {
        val pending = File(target.path + COPY_SUFFIX)
        exportVerified(source, null, pending, passphrase)
        try {
            for (suffix in SIDECARS) {
                val side = File(target.path + suffix)
                if (side.exists() && !side.delete()) throw IOException("${side.name} nicht gelöscht")
            }
            // rename(2): ersetzt die Zieldatei atomar
            if (!pending.renameTo(target)) throw IOException("${target.name}: Kopie nicht umbenannt")
        } catch (e: Exception) {
            deleteWithSidecars(pending)
            throw e
        }
        // Bleibt die Quelle liegen, räumt sie der nächste Start weg (DatabaseSystemBackup.cleanUpLeftover)
        deleteWithSidecars(source)
    }

    /**
     * [source] mit `sqlcipher_export` in die neue Datei [target] kopieren (Passphrase null = unverschlüsselt) und prüfen:
     * `user_version`, Zeilen und Prüfsumme je Tabelle müssen gleich sein ([DatabaseFingerprint]). Scheitert etwas, ist
     * [target] gelöscht und der Fehler geht an den Aufrufer; [source] bleibt unverändert.
     */
    fun exportVerified(source: File, sourcePassphrase: ByteArray?, target: File, targetPassphrase: ByteArray?) {
        for (f in listOf(target) + SIDECARS.map { File(target.path + it) }) {
            if (f.exists() && !f.delete()) throw IOException("${f.name} nicht gelöscht")
        }
        try {
            // CREATE_IF_NECESSARY: ATTACH übernimmt die Öffnungs-Flags der Hauptverbindung; ohne Erlaubnis zum Anlegen
            // scheitert die neue Datei mit SQLITE_CANTOPEN (code 14) – gesehen auf dem Gerät
            open(source, sourcePassphrase, create = true).use { db ->
                val key = targetPassphrase?.let { String(it, Charsets.US_ASCII) } ?: ""
                db.execSQL("ATTACH DATABASE ? AS export_target KEY ?", arrayOf<Any?>(target.path, key))
                try {
                    db.query("SELECT sqlcipher_export('export_target')").use { it.moveToFirst() }
                    // sqlcipher_export übernimmt user_version bereits; ausdrücklich gesetzt, weil Room daran das Schema misst
                    db.execSQL("PRAGMA export_target.user_version = ${db.version}")
                } finally {
                    db.execSQL("DETACH DATABASE export_target")
                }
            }
            val before = fingerprint(source, sourcePassphrase)
            val after = fingerprint(target, targetPassphrase)
            check(before == after) { "Kopie weicht ab: ${before.difference(after)}" }
        } catch (e: Exception) {
            deleteWithSidecars(target)
            throw e
        }
    }

    private fun deleteWithSidecars(file: File) {
        file.delete()
        for (suffix in SIDECARS) File(file.path + suffix).delete()
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

    override fun replacedCopies(database: String): List<String> {
        val original = file(database)
        val base = original.name + REPLACED_SUFFIX
        return original.parentFile?.listFiles()
            ?.filter { f -> isAsideName(f.name, base) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name }
            .orEmpty()
    }

    override fun deleteReplaced(database: String, copy: String) {
        val original = file(database)
        val replaced = File(original.parentFile, copy)
        // Nur eine ersetzte Datei, nie die aktuelle (anderer Name) oder eine beiseitegelegte
        require(isAsideName(replaced.name, original.name + REPLACED_SUFFIX)) { "$copy ist keine ersetzte Datei" }
        for (f in listOf(replaced, File(replaced.path + "-wal"), File(replaced.path + "-shm"), File(replaced.path + "-journal"))) {
            if (f.exists() && !f.delete()) throw IOException("${f.name} nicht gelöscht")
        }
    }

    /** `<db><Endung>` bzw. `<db><Endung>-<Zeit>` ohne Nebendateien (beiseitegelegte und ersetzte Dateien). */
    private fun isAsideName(name: String, base: String): Boolean =
        (name == base || name.startsWith("$base-")) && !name.endsWith("-wal") && !name.endsWith("-shm") && !name.endsWith("-journal")

    private fun Throwable.isNotADatabase(): Boolean =
        message.orEmpty().let { "SQLITE_NOTADB" in it || "file is not a database" in it } || cause?.isNotADatabase() == true

    /** Zustand einer Datei: `user_version` und je Tabelle Zeilen und Prüfsumme über alle Spalten. */
    fun fingerprint(file: File, passphrase: ByteArray?): DatabaseFingerprint =
        open(file, passphrase).use { db ->
            val tables = db.query(TABLES_SQL).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            DatabaseFingerprint(
                userVersion = db.version,
                tables = tables.associateWith { table ->
                    db.query("SELECT * FROM `${table.replace("`", "``")}`").use { c ->
                        var fp = DatabaseFingerprint.Table(0, 0)
                        while (c.moveToNext()) fp = DatabaseFingerprint.addRow(fp, List(c.columnCount) { c.value(it) })
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
        /** Verschlüsselte Kopie während [encryptCopy]; erst nach der Prüfung wird sie auf den Dateinamen umbenannt. */
        const val COPY_SUFFIX = ".encrypting"
        const val ASIDE_SUFFIX = ".unreadable"

        private val SIDECARS = listOf("-wal", "-shm", "-journal")

        /**
         * Durch eine zurückgeholte Datei ersetzte (inzwischen neu begonnene) Datenbank — bleibt bis zur Frist der
         * beiseitegelegten ([AsideRetention], [DatabaseOpenPlanner.pruneReplaced]).
         */
        const val REPLACED_SUFFIX = ".replaced"

        /** Alle Tabellen samt Zähler der AUTOINCREMENT-Ids; ohne SQLite-Statistik und Android-Metadaten (Gebietsschema). */
        private const val TABLES_SQL =
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name != 'android_metadata' " +
                "AND (name NOT LIKE 'sqlite\\_%' ESCAPE '\\' OR name = 'sqlite_sequence') ORDER BY name"

        fun kindOf(file: File): FileKind =
            if (!file.exists() || file.length() == 0L) FileKind.ABSENT else FileKind.ENCRYPTED

        /**
         * Nie löschen: Der Standard-Fehlerbehandler (wie bei Android) löscht eine Datei, die SQLite für beschädigt hält —
         * auch eine nur mit falschem Schlüssel geöffnete. Hier bleibt sie, der Fehler geht an den Aufrufer.
         */
        private val keepOnCorruption = DatabaseErrorHandler { _, _ -> }

        /**
         * Verbindung über SQLCipher zu einer bestehenden Datei; `passphrase` null = unverschlüsselte Datei (wiederhergestellte
         * oder zu sichernde Kopie der Systemsicherung). [create]: angehängte Datenbanken (ATTACH) dürfen neu angelegt werden –
         * die Hauptdatei besteht ohnehin.
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
