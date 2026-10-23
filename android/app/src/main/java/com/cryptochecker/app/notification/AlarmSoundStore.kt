package com.cryptochecker.app.notification

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
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
            put(MediaStore.Audio.Media.RELATIVE_PATH, OWN_RELATIVE_PATH)
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

    /**
     * Ordner unserer Töne in der Mediathek (= `Environment.DIRECTORY_NOTIFICATIONS` + «/CryptoChecker»). Die
     * Mediathek liefert RELATIVE_PATH mit Schrägstrich am Ende zurück.
     */
    const val OWN_RELATIVE_PATH = "Notifications/CryptoChecker"

    /** Liegt [relativePath] (RELATIVE_PATH aus der Mediathek) genau in unserem Ordner? */
    fun isOwnRelativePath(relativePath: String?): Boolean = relativePath?.trim()?.trim('/') == OWN_RELATIVE_PATH

    /** Eintrag der Mediathek für Audio («content://media/…/audio/media/…»); System- und Standardtöne nicht. */
    fun isMediaStoreAudio(uri: String?): Boolean =
        uri != null && uri.startsWith("content://media/") && "/audio/media/" in uri

    /** Eigener, in unseren Ordner kopierter Ton — nur solche löscht die App wieder. */
    fun isOwnTone(uri: String?, relativePath: String?): Boolean = isMediaStoreAudio(uri) && isOwnRelativePath(relativePath)

    /**
     * Ein neu gewählter Ton ersetzt den vorigen eigenen — der alte würde sonst bei jedem Wechsel in
     * der Mediathek liegen bleiben. Nur löschen, wenn sich der Ton wirklich ändert.
     */
    fun shouldDeletePrevious(previous: String?, next: String?, previousRelativePath: String?): Boolean =
        previous != null && !sameTone(previous, next) && isOwnTone(previous, previousRelativePath)

    /**
     * Gleicher Ton? Die System-Auswahl kann denselben Eintrag über einen anderen Datenträger-Namen liefern
     * («external» statt «external_primary») — dann zählt die Nummer des Eintrags, sonst würde der eben gewählte
     * Ton gelöscht.
     */
    fun sameTone(a: String?, b: String?): Boolean {
        if (a == b) return true
        if (!isMediaStoreAudio(a) || !isMediaStoreAudio(b)) return false
        val id: (String) -> String = { it.substringAfter("/audio/media/").substringBefore('?').substringBefore('/') }
        return id(a!!).isNotEmpty() && id(a) == id(b!!)
    }

    /** Vorigen Ton [previous] löschen, wenn er ein eigener ist und [next] ihn ersetzt (Ein-/Ausgabe, nicht im Hauptthread). */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun deletePreviousIfOwn(context: Context, previous: String?, next: String?) {
        if (previous == null || sameTone(previous, next) || !isMediaStoreAudio(previous)) return
        val resolver = context.contentResolver
        val uri = Uri.parse(previous)
        try {
            val path = resolver.query(uri, arrayOf(MediaStore.Audio.Media.RELATIVE_PATH), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            if (shouldDeletePrevious(previous, next, path)) resolver.delete(uri, null, null)
        } catch (e: Exception) {
            // Fremder Eintrag (z. B. aus einer früheren Installation) oder schon weg: liegen lassen
            Timber.w(e, "Voriger Alarmton nicht gelöscht")
        }
    }

    /**
     * Alle eigenen Töne aus der Mediathek löschen — vor «App zurücksetzen»: `clearApplicationUserData` leert nur den
     * App-Speicher, die Mediathek gehört dem System. Gelöscht werden nur Einträge, die diese Installation angelegt
     * hat (die Mediathek filtert fremde selbst). Ein-/Ausgabe, nicht im Hauptthread.
     * @return Anzahl gelöschter Einträge
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun deleteAllOwn(context: Context): Int = try {
        context.contentResolver.delete(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "${MediaStore.Audio.Media.RELATIVE_PATH} IN (?, ?)",
            arrayOf("$OWN_RELATIVE_PATH/", OWN_RELATIVE_PATH),
        )
    } catch (e: Exception) {
        Timber.w(e, "Eigene Alarmtöne nicht gelöscht")
        0
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private const val MAX_BYTES = 10L * 1024 * 1024
}
