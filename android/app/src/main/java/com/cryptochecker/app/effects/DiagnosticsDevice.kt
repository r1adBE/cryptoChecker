package com.cryptochecker.app.effects

import android.content.Context
import com.cryptochecker.app.diagnostics.ErrorLogFile
import com.cryptochecker.app.domain.alarm.AlarmReadiness
import com.cryptochecker.app.domain.diagnostics.DiagnosticsFiles
import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.ui.components.NotificationPrePrompt
import com.cryptochecker.app.work.AlarmReadinessProbe
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Was der Diagnose-Bericht vom Gerät liest: Zustand für «Damit Alarme ankommen», Sprache, Fehlerprotokoll. */
interface DiagnosticsDevice {
    /** Zustand des Geräts für die Alarm-Bereitschaft (Mitteilungen, Akku, exakte Prüfungen …). */
    fun readiness(settings: AppSettings, lastBackgroundCheck: Long?, hasAlarms: Boolean, now: Long): AlarmReadiness.Input

    /** Sprache des Geräts als BCP-47-Tag; leer = unbekannt. */
    fun localeTag(): String

    /** Zeilen des Fehlerprotokolls auf dem Gerät. */
    fun errorLogLines(): List<String>

    /** Liegende Berichtsdatei vom letzten Teilen löschen; gibt die Anzahl gelöschter Dateien zurück. */
    fun clearReportFiles(): Int
}

/** Android-Umsetzung über den Kontext der App. */
class AndroidDiagnosticsDevice @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DiagnosticsDevice {
    override fun readiness(settings: AppSettings, lastBackgroundCheck: Long?, hasAlarms: Boolean, now: Long) =
        AlarmReadinessProbe.read(
            context,
            settings,
            lastBackgroundCheck,
            hasAlarms,
            now,
            notificationsNotDetermined = NotificationPrePrompt.notDetermined(context),
        )

    override fun localeTag(): String = context.resources.configuration.locales[0]?.toLanguageTag().orEmpty()

    override fun errorLogLines(): List<String> = ErrorLogFile.of(context).readLines()

    override fun clearReportFiles(): Int = DiagnosticsFiles.clear(context.cacheDir)
}
