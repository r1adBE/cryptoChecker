package com.cryptochecker.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject

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
     * Text einer Sicherungsdatei: eingerückt (ältere Sicherungen) oder kompakt ([encode]) — der Parser nimmt
     * beides, auch Sicherungen von iOS.
     */
    fun parse(text: String): BackupFile {
        BackupLimits.checkDepth(text)
        val root = JSONObject(text)
        // Verschlüsselt: eigenes «format» (cryptochecker-backup-enc), damit ältere App-Versionen
        // die Datei ablehnen statt sie als leere Sicherung zu übernehmen
        if (!BackupCrypto.isEnvelope(root.optString("format"), root.optString("enc"))) {
            require(root.optString("format") == FORMAT) { "Keine Crypto-Checker-Sicherung" }
            return BackupFile.Plain(text)
        }
        return BackupFile.Encrypted(
            BackupCrypto.envelope(
                version = root.optInt("v"),
                enc = root.optString("enc"),
                kdf = root.optString("kdf"),
                iterations = root.optInt("iter"),
                salt = root.optString("salt"),
                iv = root.optString("iv"),
                data = root.optString("data"),
                // v3 (Argon2id): Speicher in KiB, Durchgänge, Bahnen
                memoryKiB = root.optInt("m"),
                time = root.optInt("t"),
                parallelism = root.optInt("p"),
            )
        )
    }

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
