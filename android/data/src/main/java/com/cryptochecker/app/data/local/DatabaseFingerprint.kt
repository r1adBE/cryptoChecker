package com.cryptochecker.app.data.local

/**
 * Inhalt einer Datenbankdatei für den Vergleich vor und nach einem `sqlcipher_export` ([SqlCipherDatabaseFiles.exportVerified]):
 * `user_version` und alle Tabellen (inkl. `sqlite_sequence`, ohne übrige `sqlite_*`).
 */
data class DatabaseFingerprint(val userVersion: Int, val tables: Map<String, Table>) {

    /** Inhalt einer Tabelle: Zeilen und Summe der Zeilen-Prüfsummen ([rowChecksum]). */
    data class Table(val rows: Long, val checksum: Long)

    /** Kurzbeschreibung der Abweichung zu [after] fürs Protokoll (Tabellennamen, keine Inhalte). */
    fun difference(after: DatabaseFingerprint): String = buildList {
        if (userVersion != after.userVersion) add("user_version $userVersion → ${after.userVersion}")
        (tables.keys + after.tables.keys).sorted().forEach { table ->
            val a = tables[table]
            val b = after.tables[table]
            when {
                a == null -> add("$table nur in der Kopie")
                b == null -> add("$table fehlt in der Kopie")
                a.rows != b.rows -> add("$table: ${a.rows} → ${b.rows} Zeilen")
                a.checksum != b.checksum -> add("$table: Prüfsumme")
            }
        }
    }.joinToString()

    companion object {
        /**
         * Prüfsumme einer Zeile (FNV-1a, 64 Bit) über Typ und Wert jeder Spalte: `1`, `1.0` und `"1"` unterscheiden
         * sich. Je Tabelle werden die Zeilen-Prüfsummen addiert ([addRow]) — unabhängig von der Reihenfolge, in der
         * der Export die Zeilen schreibt.
         */
        fun rowChecksum(values: List<Any?>): Long {
            var hash = FNV_OFFSET
            fun mix(byte: Int) {
                hash = (hash xor (byte.toLong() and 0xff)) * FNV_PRIME
            }
            fun mixLong(v: Long) {
                for (shift in 0 until 64 step 8) mix((v ushr shift).toInt())
            }
            fun mixBytes(bytes: ByteArray) {
                mixLong(bytes.size.toLong())
                bytes.forEach { mix(it.toInt()) }
            }
            for (value in values) {
                when (value) {
                    null -> mix(0)
                    is Long -> { mix(1); mixLong(value) }
                    is Int -> { mix(1); mixLong(value.toLong()) }
                    is Double -> { mix(2); mixLong(value.toRawBits()) }
                    is String -> { mix(3); mixBytes(value.toByteArray(Charsets.UTF_8)) }
                    is ByteArray -> { mix(4); mixBytes(value) }
                    else -> throw IllegalArgumentException("Unbekannter Spaltentyp ${value::class.simpleName}")
                }
            }
            return hash
        }

        /** Zeile zur Tabellen-Prüfsumme hinzufügen (Addition mit Überlauf, reihenfolgeunabhängig). */
        fun addRow(table: Table, values: List<Any?>): Table = Table(table.rows + 1, table.checksum + rowChecksum(values))

        private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        private const val FNV_PRIME = 0x100000001b3L
    }
}
