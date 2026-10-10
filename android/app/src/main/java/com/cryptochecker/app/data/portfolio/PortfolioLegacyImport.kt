package com.cryptochecker.app.data.portfolio

import androidx.sqlite.db.SupportSQLiteDatabase
import timber.log.Timber

/**
 * Einmalige Übernahme des Portfolios aus der Hauptdatenbank (bis Version 12) in die eigene
 * Portfolio-Datenbank ([PortfolioDatabase]). Läuft bei jedem Öffnen der Portfolio-Datenbank und
 * ist danach ein billiger Blick in `sqlite_master`.
 *
 * Ablauf, so dass nie Daten verloren gehen:
 * 1. [com.cryptochecker.app.data.local.AppDatabase.MIGRATION_12_13] benennt die Tabellen in der
 *    Hauptdatenbank nur um (`legacy_portfolio_tx`, `legacy_portfolio_alarms`) — nichts wird gelöscht.
 * 2. Hier: alle Zeilen in einer Transaktion der Portfolio-Datenbank kopieren, die Anzahl prüfen und im
 *    selben Schritt den Merker [MARKER_KEY] setzen. Schlägt etwas fehl, rollt alles zurück; der
 *    Altbestand bleibt und der nächste Start versucht es erneut.
 * 3. Erst danach die `legacy_*`-Tabellen in der Hauptdatenbank löschen. Bricht die App dazwischen ab,
 *    zeigt der Merker beim nächsten Start, dass nur noch das Löschen fehlt (kein zweites Kopieren).
 *
 * Ids: In eine leere Portfolio-Datenbank werden die alten Ids übernommen. Ist sie nicht leer, obwohl der
 * Merker fehlt (eine frühere Übernahme schlug fehl und der Nutzer hat inzwischen neu erfasst), werden die
 * alten Zeilen mit neuen Ids angehängt — nichts wird überschrieben.
 */
object PortfolioLegacyImport {

    const val LEGACY_TX = "legacy_portfolio_tx"
    const val LEGACY_ALARMS = "legacy_portfolio_alarms"

    /** Kleine Merker-Tabelle der Portfolio-Datenbank (keine Room-Entity; Room prüft nur seine Tabellen). */
    const val META_TABLE = "portfolio_meta"
    const val MARKER_KEY = "legacy_import_done"

    private val TX_COLUMNS = listOf("coin", "type", "amount", "priceUsdt", "time", "note")
    private val ALARM_COLUMNS = listOf(
        "kind", "threshold", "currency", "enabled", "repeating", "referenceAt", "lastTriggeredAt", "lastTriggeredValue",
    )

    /**
     * @param portfolioDb die gerade geöffnete Portfolio-Datenbank (aus `Callback.onOpen`).
     * @param mainDb öffnet die Hauptdatenbank (Room führt dabei ihre Migrationen aus, auch 12 → 13).
     */
    fun run(portfolioDb: SupportSQLiteDatabase, mainDb: () -> SupportSQLiteDatabase) {
        portfolioDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `$META_TABLE` (`key` TEXT NOT NULL PRIMARY KEY, `value` TEXT NOT NULL)"
        )
        val main = mainDb()
        val legacy = listOf(LEGACY_TX, LEGACY_ALARMS).filter { tableExists(main, it) }
        val plan = PortfolioLegacyPlan.decide(
            legacyPresent = legacy.isNotEmpty(),
            alreadyImported = markerSet(portfolioDb),
            portfolioEmpty = count(portfolioDb, "portfolio_tx") == 0 && count(portfolioDb, "portfolio_alarms") == 0,
        )
        if (plan == PortfolioLegacyPlan.NOTHING) return

        if (plan == PortfolioLegacyPlan.COPY_KEEP_IDS || plan == PortfolioLegacyPlan.COPY_NEW_IDS) {
            val keepIds = plan == PortfolioLegacyPlan.COPY_KEEP_IDS
            // Erst alles lesen (Hauptdatenbank), dann in einer Transaktion schreiben (Portfolio-Datenbank)
            val txRows = if (LEGACY_TX in legacy) readRows(main, LEGACY_TX, TX_COLUMNS) else emptyList()
            val alarmRows = if (LEGACY_ALARMS in legacy) readRows(main, LEGACY_ALARMS, ALARM_COLUMNS) else emptyList()
            portfolioDb.beginTransaction()
            try {
                copy(portfolioDb, "portfolio_tx", TX_COLUMNS, txRows, keepIds)
                copy(portfolioDb, "portfolio_alarms", ALARM_COLUMNS, alarmRows, keepIds)
                portfolioDb.execSQL(
                    "INSERT OR REPLACE INTO `$META_TABLE` (`key`, `value`) VALUES (?, ?)",
                    arrayOf<Any?>(MARKER_KEY, System.currentTimeMillis().toString()),
                )
                portfolioDb.setTransactionSuccessful()
            } finally {
                portfolioDb.endTransaction()
            }
            Timber.i("Portfolio: %d Transaktionen und %d Alarme in die eigene Datenbank übernommen", txRows.size, alarmRows.size)
        }

        // Kopie ist bestätigt (Merker): Altbestand aus der Hauptdatenbank entfernen
        legacy.forEach { main.execSQL("DROP TABLE IF EXISTS `$it`") }
        // Gelöschte Seiten nicht in der Hauptdatei (und damit in der Systemsicherung) liegen lassen.
        // Einmalig und nur bestmöglich: scheitert es (z. B. belegt), bleibt es beim DROP.
        runCatching {
            main.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            main.execSQL("VACUUM")
        }.onFailure { Timber.w(it, "Portfolio: Hauptdatenbank nach der Übernahme nicht verdichtet") }
    }

    private fun copy(
        db: SupportSQLiteDatabase,
        table: String,
        columns: List<String>,
        rows: List<Row>,
        keepIds: Boolean,
    ) {
        if (rows.isEmpty()) return
        val before = count(db, table)
        val cols = if (keepIds) listOf("id") + columns else columns
        val sql = "INSERT INTO `$table` (${cols.joinToString { "`$it`" }}) VALUES (${cols.joinToString { "?" }})"
        rows.forEach { row ->
            val args = if (keepIds) arrayOf<Any?>(row.id, *row.values) else row.values
            db.execSQL(sql, args)
        }
        // Anzahl prüfen: sonst Ausnahme → Rollback, Altbestand bleibt
        val after = count(db, table)
        check(after - before == rows.size) { "Portfolio-Übernahme $table: ${after - before} statt ${rows.size} Zeilen" }
    }

    /** Zeile mit Id und den Werten in Spaltenreihenfolge (Typen wie gespeichert). */
    private class Row(val id: Long, val values: Array<Any?>)

    private fun readRows(db: SupportSQLiteDatabase, table: String, columns: List<String>): List<Row> {
        val sql = "SELECT `id`, ${columns.joinToString { "`$it`" }} FROM `$table` ORDER BY `id`"
        return db.query(sql).use { c ->
            buildList<Row> {
                while (c.moveToNext()) {
                    val values = Array<Any?>(columns.size) { i ->
                        val index = i + 1
                        when (c.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index)
                            android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(index)
                            else -> c.getString(index)
                        }
                    }
                    add(Row(c.getLong(0), values))
                }
            }
        }
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf<Any?>(table))
            .use { it.moveToFirst() }

    private fun markerSet(db: SupportSQLiteDatabase): Boolean =
        db.query("SELECT 1 FROM `$META_TABLE` WHERE `key` = ?", arrayOf<Any?>(MARKER_KEY)).use { it.moveToFirst() }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}
