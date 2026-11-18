package com.cryptochecker.app.effects

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.notification.AlarmSoundStore
import com.cryptochecker.app.notification.NotificationChannels
import com.cryptochecker.app.setup.AppLanguages
import com.cryptochecker.app.ui.features.portfolio.UriExportTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Was die Einstellungen am Gerät tun: App-Sprache, Alarmton und -kanäle, Sicherungsdatei prüfen bzw. löschen. */
interface SettingsDevice {
    /** Gewählte App-Sprache; leer = Systemsprache. */
    fun languageTag(): String

    /** Sprache wählen; leer = Systemsprache. */
    fun selectLanguage(tag: String)

    /** Name des Tons [uri]; unbekannt → null. */
    fun alarmSoundTitle(uri: Uri): String?

    /** Eigene Audiodatei übernehmen: neue Adresse und Name; null = gescheitert. */
    fun importAlarmSound(source: Uri): Pair<Uri, String>?

    /** Vorigen eigenen Ton [previous] löschen, wenn er nicht [next] ist. */
    fun deletePreviousOwnTone(previous: String?, next: String?)

    /** Standard-Alarmkanal mit Ton [soundUri] in Version [version] anlegen; gibt die Kanal-ID zurück. */
    fun ensureAlarmChannel(version: Int, soundUri: String?): String

    /** Kanal des Signals [signal] mit Ton [soundUri] in Version [version] anlegen; gibt die Kanal-ID zurück. */
    fun ensureAlarmChannel(signal: AlarmSignal, version: Int, soundUri: String?): String

    /** Datei ist leer (eben von der Dateiauswahl angelegt)? Wirft, wenn sie nicht lesbar ist. */
    fun isEmptyDocument(uri: Uri): Boolean

    /** Datei löschen (gescheiterte Sicherung); Fehler werden nur protokolliert. */
    suspend fun deleteDocument(uri: Uri)
}

/** Android-Umsetzung über den Kontext der App. */
class AndroidSettingsDevice @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SettingsDevice {
    override fun languageTag(): String = AppLanguages.currentTag(context)

    override fun selectLanguage(tag: String) = AppLanguages.select(context, tag)

    override fun alarmSoundTitle(uri: Uri): String? = AlarmSoundStore.title(context, uri)

    override fun importAlarmSound(source: Uri): Pair<Uri, String>? = AlarmSoundStore.importFile(context, source)

    override fun deletePreviousOwnTone(previous: String?, next: String?) =
        AlarmSoundStore.deletePreviousIfOwn(context, previous, next)

    override fun ensureAlarmChannel(version: Int, soundUri: String?): String =
        NotificationChannels.ensureAlarmChannel(context, version, soundUri)

    override fun ensureAlarmChannel(signal: AlarmSignal, version: Int, soundUri: String?): String =
        NotificationChannels.ensureAlarmChannel(context, signal, version, soundUri)

    override fun isEmptyDocument(uri: Uri): Boolean =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            c.moveToFirst() && !c.isNull(0) && c.getLong(0) == 0L
        } ?: false

    override suspend fun deleteDocument(uri: Uri) = UriExportTarget(context).delete(uri)
}
