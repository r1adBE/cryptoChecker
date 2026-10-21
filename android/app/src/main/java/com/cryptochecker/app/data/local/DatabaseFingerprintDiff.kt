// Abweichung zweier Datei-Fingerabdrücke fürs Protokoll (Teil von `DatabaseEncryptionMigration`, ausgelagert seit
// Build 102 wegen der Dateilänge). Aufruf weiter als `DatabaseEncryptionMigration.difference`.
package com.cryptochecker.app.data.local

import com.cryptochecker.app.data.local.DatabaseEncryptionMigration.Fingerprint

/** Kurzbeschreibung der Abweichung fürs Protokoll (Tabellennamen, keine Inhalte). */
fun DatabaseEncryptionMigration.Companion.difference(before: Fingerprint, after: Fingerprint): String = buildList {
    if (before.userVersion != after.userVersion) add("user_version ${before.userVersion} → ${after.userVersion}")
    (before.tables.keys + after.tables.keys).sorted().forEach { table ->
        val a = before.tables[table]
        val b = after.tables[table]
        when {
            a == null -> add("$table nur in der Kopie")
            b == null -> add("$table fehlt in der Kopie")
            a.rows != b.rows -> add("$table: ${a.rows} → ${b.rows} Zeilen")
            a.checksum != b.checksum -> add("$table: Prüfsumme")
        }
    }
}.joinToString()
