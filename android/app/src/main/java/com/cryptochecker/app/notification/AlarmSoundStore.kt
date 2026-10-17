package com.cryptochecker.app.notification

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * Eigener Alarmton (#202).
 *
 * Android spielt den Ton eines Mitteilungskanals selbst ab (System-UI). Eine
 * Datei, die nur unsere App lesen darf, bliebe dort stumm. Deshalb wird eine
 * gewählte Audiodatei als Mitteilungston in die Mediathek kopiert
 * (Ordner «Notifications/CryptoChecker») — dort darf das System sie lesen.
 * Ab Android 10 geht das ohne Speicher-Berechtigung.
 */
object AlarmSoundStore {

    /** Eigene Dateien nur ab Android 10 (ohne Speicher-Berechtigung). */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val supportsCustomFile: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Name eines Tons für die Anzeige (Systemton oder Datei). */
    fun title(context: Context, uri: Uri): String? = runCatching {
        RingtoneManager.getRingtone(context, uri)?.getTitle(context)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Kopiert eine vom Nutzer gewählte Audiodatei in die Mediathek.
     * @return (URI für den Kanal, Anzeigename) oder null bei Fehler
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun importFile(context: Context, source: Uri): Pair<Uri, String>? {
        val resolver = context.contentResolver
        val displayName = queryName(context, source) ?: "alarm"
        val mime = resolver.getType(source)?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, displayName.substringBeforeLast('.'))
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_NOTIFICATIONS}/CryptoChecker")
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openInputStream(source).use { input ->
                resolver.openOutputStream(target).use { output ->
                    requireNotNull(input) { "Quelle nicht lesbar" }
                    requireNotNull(output) { "Ziel nicht schreibbar" }
                    // Höchstens 10 MB — ein Alarmton ist kurz
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_BYTES) { "Datei zu gross" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            target to displayName.substringBeforeLast('.')
        } catch (e: Exception) {
            Timber.w(e, "Alarmton konnte nicht übernommen werden")
            runCatching { resolver.delete(target, null, null) }
            null
        }
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private const val MAX_BYTES = 10L * 1024 * 1024
}
