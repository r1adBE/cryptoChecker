package com.cryptochecker.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import timber.log.Timber
import java.io.File

/**
 * Inhalt einer unverschlüsselten Datei in die Datenbank im Arbeitsspeicher kopieren
 * ([DatabaseEncryptionMigration.Decision.PlaintextKept]): Merkliste bzw. Portfolio zeigen den gespeicherten
 * Stand, aber nichts wird in die Datei geschrieben (wie iOS `ProtectedFile.write(requireSealed:)`). Die Datei wird nur
 * gelesen (`ATTACH`, dann `SELECT`). Kopiert wird nur bei gleichem Room-Schema (`identity_hash`); sonst bleibt der Speicher
 * leer und der nächste Start mit Schlüssel überführt die Datei samt Migrationen. Scheitert etwas, bleibt der Speicher leer
 * (alles in einer Transaktion).
 */
class PlaintextMemoryCopy(
    private val source: File,
    private val delegate: SupportSQLiteOpenHelper.Callback,
) : SupportSQLiteOpenHelper.Callback(delegate.version) {

    override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)

    override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
        delegate.onUpgrade(db, oldVersion, newVersion)

    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
        delegate.onDowngrade(db, oldVersion, newVersion)

    /** Vor Rooms eigenem onOpen: Wer danach liest (Callbacks, erste Abfrage), sieht schon den gespeicherten Stand. */
    override fun onOpen(db: SupportSQLiteDatabase) {
        runCatching { copy(db) }.onFailure { Timber.w(it, "%s: Inhalt nicht in den Arbeitsspeicher kopiert", source.name) }
        delegate.onOpen(db)
    }

    override fun onCorruption(db: SupportSQLiteDatabase) = delegate.onCorruption(db)

    private fun copy(db: SupportSQLiteDatabase) {
        db.execSQL("ATTACH DATABASE ? AS $SRC", arrayOf(source.absolutePath))
        try {
            if (identity(db, "main") == null || identity(db, "main") != identity(db, SRC)) {
                Timber.w("%s: anderes Schema, Arbeitsspeicher bleibt leer", source.name)
                return
            }
            val tables = PlaintextMemoryTables.copyable(names(db, "SELECT name FROM main.sqlite_master WHERE type = 'table'"))
            db.beginTransaction()
            try {
                // Fremdschlüssel erst am Ende prüfen: Die Reihenfolge der Tabellen ist alphabetisch, nicht nach Abhängigkeit
                db.execSQL("PRAGMA defer_foreign_keys = ON")
                for (table in tables) {
                    val columns = names(db, "PRAGMA main.table_info(\"$table\")", column = "name")
                    val list = columns.joinToString(",") { "\"$it\"" }
                    db.execSQL("INSERT INTO main.\"$table\" ($list) SELECT $list FROM $SRC.\"$table\"")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Timber.i("%s: %d Tabellen in den Arbeitsspeicher kopiert, Datei unberührt", source.name, tables.size)
        } finally {
            db.execSQL("DETACH DATABASE $SRC")
        }
    }

    private fun identity(db: SupportSQLiteDatabase, schema: String): String? = runCatching {
        db.query("SELECT identity_hash FROM $schema.room_master_table LIMIT 1").use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun names(db: SupportSQLiteDatabase, sql: String, column: String = "name"): List<String> =
        db.query(sql).use { cursor ->
            val index = cursor.getColumnIndexOrThrow(column)
            buildList { while (cursor.moveToNext()) add(cursor.getString(index)) }
        }

    private companion object {
        const val SRC = "plaintext_src"
    }
}

/** Welche Tabellen kopiert werden (reine Logik, Unit-Test `PlaintextMemoryTablesTest`). */
object PlaintextMemoryTables {
    /** Ohne SQLite-, Android- und Room-Verwaltung (die legt Room im Speicher selbst an). */
    fun copyable(tables: List<String>): List<String> =
        tables.filterNot { it.startsWith("sqlite_") || it == "android_metadata" || it == "room_master_table" }.sorted()
}
