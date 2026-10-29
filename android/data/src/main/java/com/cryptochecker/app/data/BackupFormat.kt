package com.cryptochecker.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Sicherungsdatei lesen ([BackupManager]): lesbares JSON oder verschlüsselte Hülle ([BackupCrypto]),
 * Format- und Versionsprüfung, Klartext mit Passwort.
 */
object BackupFormat {
    const val FORMAT = "cryptochecker-backup"
    /** Geschriebene Version = höchste lesbare ([BackupVersion.SUPPORTED]). */
    const val VERSION = BackupVersion.SUPPORTED

    /** Gelesene Sicherungsdatei: lesbares JSON oder verschlüsselte Hülle. */
    sealed interface BackupFile {
        class Plain(val json: String) : BackupFile
        class Encrypted(val envelope: BackupCrypto.Envelope) : BackupFile
    }

    fun read(context: Context, uri: Uri): BackupFile {
        // Höchstens 64 MB und flach verschachtelt ([BackupLimits]) — nie eine beliebig grosse Datei in den Speicher
        val text = context.contentResolver.openInputStream(uri)?.use { BackupLimits.readLimited(it).toString(Charsets.UTF_8) }
            ?: error("Datei kann nicht gelesen werden")
        return parse(text)
    }

    /**
     * Text einer Sicherungsdatei: kompakt ([encode]) oder eingerückt — der Parser nimmt beides, auch Sicherungen von iOS.
     * Streng wie iOS (`JSONSerialization`): nach dem Objekt nichts mehr ausser Leerraum; Zahlen der Hülle nur als ganze
     * JSON-Zahlen, Texte nur als JSON-Texte ([integral], [jsonText]) — ein `"m":"65536"` oder `65536.9` gilt als fehlend.
     */
    fun parse(text: String): BackupFile {
        BackupLimits.checkDepth(text)
        val tokener = JSONTokener(text)
        val root = JSONObject(tokener)
        if (tokener.nextClean() != '\u0000') throw JSONException("Zeichen nach dem Ende der Sicherung")
        // Verschlüsselt: eigenes «format» (cryptochecker-backup-enc), damit ein Leser ohne Entschlüsselung
        // die Datei ablehnt statt sie als leere Sicherung zu übernehmen
        if (!BackupCrypto.isEnvelope(root.optString("format"), root.optString("enc"))) {
            require(root.optString("format") == FORMAT) { "Keine Crypto-Checker-Sicherung" }
            return BackupFile.Plain(text)
        }
        return BackupFile.Encrypted(
            BackupCrypto.envelope(
                // Fehlt oder keine ganze Zahl: 0 — die Hülle lehnt das ab (Version bzw. Argon2-Grenzen)
                version = integral(root.opt("v")) ?: 0,
                enc = jsonText(root.opt("enc")),
                kdf = jsonText(root.opt("kdf")),
                salt = jsonText(root.opt("salt")),
                iv = jsonText(root.opt("iv")),
                data = jsonText(root.opt("data")),
                // Argon2id: Speicher in KiB, Durchgänge, Bahnen
                memoryKiB = integral(root.opt("m")) ?: 0,
                time = integral(root.opt("t")) ?: 0,
                parallelism = integral(root.opt("p")) ?: 0,
            )
        )
    }

    /** Ganze JSON-Zahl im Int-Bereich (auch `65536.0`, wie iOS); Text, Wahrheitswert oder Bruch → null. */
    fun integral(value: Any?): Int? {
        if (value !is Number) return null
        val d = value.toDouble()
        if (d.isNaN() || d != Math.floor(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) return null
        return d.toInt()
    }

    /** JSON-Text; Zahl oder anderes → null (org.json würde sonst umwandeln). */
    private fun jsonText(value: Any?): String? = value as? String

    /**
     * Klartext der Sicherung als Bytes: kompakt ohne Einrückung (eingerückt war die Datei gut ein Drittel
     * grösser). Grösser als lesbar ([BackupLimits.MAX_BYTES]) → [BackupLimits.ExportTooLargeException].
     */
    fun encode(root: JSONObject): ByteArray =
        root.toString().toByteArray(Charsets.UTF_8).also { BackupLimits.checkWritable(it.size) }

    /** «version» der Sicherung ([BackupVersion.check]): neuere → [BackupVersion.NewerVersionException]. */
    fun checkVersion(root: JSONObject) {
        BackupVersion.check(root.opt("version").takeIf { it != JSONObject.NULL })
    }

    /** Klartext der Sicherung; verschlüsselt ohne Passwort gilt als falsches Passwort. */
    fun plainText(file: BackupFile, password: String?): String = when (file) {
        is BackupFile.Plain -> file.json
        is BackupFile.Encrypted -> withPasswordChars(password ?: throw BackupCrypto.WrongPasswordException()) {
            BackupCrypto.decrypt(file.envelope, it)
        }.toString(Charsets.UTF_8).also { BackupLimits.checkDepth(it) }
    }

    /**
     * [BackupCrypto] nimmt das Passwort als CharArray, damit es nach der Schlüsselableitung überschrieben werden kann
     * (ein String bleibt bis zur Speicherbereinigung lesbar). Der String aus dem Dialog selbst lässt sich nicht löschen.
     */
    inline fun <T> withPasswordChars(password: String, block: (CharArray) -> T): T {
        val chars = password.toCharArray()
        try {
            return block(chars)
        } finally {
            chars.fill('\u0000')
        }
    }
}
