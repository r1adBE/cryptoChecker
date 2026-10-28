// Coin-Logo-Dateien sicher schreiben (Teil von `LogoImageStore`), ohne Android-Klassen (getestet in LogoFilesTest).
package com.cryptochecker.app.data

import java.io.File
import java.io.IOException

/**
 * Schreibt ein Logo erst in eine eigene Hilfsdatei mit eindeutigem Namen im selben Ordner und benennt sie dann um
 * (Umbenennen im selben Dateisystem ersetzt die Zieldatei in einem Schritt). So liegt nie ein halbes Bild da, auch
 * wenn zwei Abrufe gleichzeitig dieselbe Datei schreiben (Start-Logos und Sammel-Abruf) — die zuletzt
 * umbenannte gilt. Wie iOS `Data.write(to:options: .atomic)`.
 */
object LogoFiles {
    /** Endung der Hilfsdateien (eindeutiger Name davor). */
    const val TEMP_SUFFIX = ".tmp"

    /** true = [file] enthält jetzt genau [bytes]; false = Schreiben oder Umbenennen scheiterte (nichts liegen gelassen). */
    fun write(file: File, bytes: ByteArray): Boolean {
        val dir = file.parentFile ?: return false
        val tmp = try {
            File.createTempFile(file.name + ".", TEMP_SUFFIX, dir)
        } catch (e: IOException) {
            return false
        } catch (e: SecurityException) {
            return false
        }
        val ok = runCatching {
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }.getOrDefault(false)
        if (!ok) tmp.delete()
        return ok
    }
}
