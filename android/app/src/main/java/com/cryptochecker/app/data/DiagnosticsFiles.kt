package com.cryptochecker.app.data

import java.io.File

/**
 * Datei des geteilten Diagnoseberichts im Cache (`cache/diagnostics/`, freigegeben in `res/xml/diagnostics_paths.xml`).
 * Sie liegt unverschlüsselt da und darf nicht bleiben: weg bei jedem App-Start und wenn die Vorschau ohne Teilen
 * schliesst. Nicht gleich nach «Teilen» — die gewählte App liest sie dann noch über den FileProvider.
 * iOS schreibt die Datei erst beim Teilen und löscht sie beim Verlassen der Vorschau (`DiagnosticsShareFile`).
 */
object DiagnosticsFiles {
    /** Ordner im Cache (wie `path` in `diagnostics_paths.xml`). */
    const val DIR = "diagnostics"

    fun dir(cacheDir: File): File = File(cacheDir, DIR)

    /** Löscht alle Dateien im Ordner; Anzahl gelöschter Dateien. Fehler (Speicher) still: beim nächsten Mal wieder. */
    fun clear(cacheDir: File): Int {
        val files = dir(cacheDir).listFiles() ?: return 0
        return files.count { it.isFile && it.delete() }
    }
}
