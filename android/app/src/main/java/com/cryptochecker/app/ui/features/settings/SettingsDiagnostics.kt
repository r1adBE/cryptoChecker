package com.cryptochecker.app.ui.features.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.BuildConfig
import com.cryptochecker.app.R
import com.cryptochecker.app.data.DiagnosticsFiles
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.alarm.AlarmReadiness
import com.cryptochecker.app.domain.diagnostics.DiagnosticInput
import com.cryptochecker.app.domain.diagnostics.DiagnosticReport
import com.cryptochecker.app.domain.diagnostics.DiagnosticSettings
import com.cryptochecker.app.domain.refresh.AlarmLogStore
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.AlarmReadinessProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

// «Diagnose teilen» (Build 81) unter Einstellungen › Über die App — wie iOS `DiagnosticsRow`.

/**
 * Baut den Bericht ([DiagnosticReport]) nur aus Daten auf dem Gerät: Einstellungen für Alarme, Zustand von
 * «Damit Alarme ankommen», «Letzte Prüfungen», Alarm-Protokoll (ohne Kurse und Schwellen) und Pausen der Börsen.
 * Portfolio, Beträge, Adressen und Notizen liest er gar nicht. Gesendet wird nichts — teilen erst nach der Vorschau.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val checkRunStore: CheckRunStore,
    private val alarmLogStore: AlarmLogStore,
    private val refreshStats: RefreshStats,
    private val watchRepository: WatchRepository,
) : ViewModel() {

    private val _preview = MutableStateFlow<String?>(null)

    /** Text der Vorschau; null = keine Vorschau offen. */
    val preview: StateFlow<String?> = _preview.asStateFlow()

    suspend fun build(context: Context): String = withContext(Dispatchers.IO) {
        val settings = runCatchingCancellable { settingsRepository.settings.first() }.getOrDefault(settingsRepository.cached)
        val alarms = runCatchingCancellable { watchRepository.observeAllAlarms().first() }.getOrDefault(emptyList())
        val activeAlarms = alarms.count { it.alarm.enabled }
        val runs = checkRunStore.runs.value
        alarmLogStore.load()
        val now = System.currentTimeMillis()
        // Portfolio liest der Bericht nicht: Portfolio-Alarme zählen hier nicht mit (sonst wie «Damit Alarme ankommen»)
        val hasAlarms = AlarmReadiness.hasBackgroundAlarms(
            com.cryptochecker.app.ui.features.alarms.alarmKinds(settings, pairAlarm = activeAlarms > 0, portfolioAlarm = false),
            AlarmReadiness.Platform.ANDROID,
        )
        val readiness = AlarmReadiness.rows(
            AlarmReadinessProbe.read(
                context, settings, com.cryptochecker.app.domain.refresh.CheckRunLog.lastBackgroundCheck(runs), hasAlarms, now,
                notificationsNotDetermined = com.cryptochecker.app.ui.components.NotificationPrePrompt.notDetermined(context),
            )
        )
        DiagnosticReport.build(
            DiagnosticInput(
                createdAt = now,
                zone = ZoneId.systemDefault().id,
                appVersion = BuildConfig.VERSION_NAME,
                appBuild = BuildConfig.VERSION_CODE.toString(),
                sourceCommit = BuildConfig.GIT_COMMIT,
                system = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                locale = context.resources.configuration.locales[0]?.toLanguageTag().orEmpty(),
                settings = DiagnosticSettings(
                    updateMode = settings.updateMode.name,
                    backgroundIntervalMinutes = settings.backgroundIntervalMinutes,
                    exactChecks = settings.exactChecks,
                    priceNotifications = settings.priceNotifications,
                    alarmSignal = settings.alarmSignal.name,
                    alarmCooldownMinutes = settings.alarmCooldownMinutes,
                    quietHoursEnabled = settings.quietHoursEnabled,
                    quietHoursStart = settings.quietHoursStart,
                    quietHoursEnd = settings.quietHoursEnd,
                    activeAlarms = activeAlarms,
                ),
                readiness = readiness,
                checkRuns = runs,
                alarmEvents = alarmLogStore.state.value.events,
                exchangePauses = refreshStats.backoffStates(),
                // Der Release-Build führt kein Fehlerprotokoll (das HTTP-Protokoll gibt es nur im Debug-Build)
                errorLog = null,
            )
        )
    }

    fun showPreview(text: String) {
        _preview.value = text
    }

    fun dismiss() {
        _preview.value = null
    }

    /** Vorschau ohne Teilen geschlossen: eine von früher liegende Berichtsdatei gleich mit löschen ([DiagnosticsFiles]). */
    fun cancel(cacheDir: File) {
        dismiss()
        viewModelScope.launch(Dispatchers.IO) { DiagnosticsFiles.clear(cacheDir) }
    }
}

/** Zeile «Diagnose teilen» mit Hinweis; Tipp zeigt erst die Vorschau, «Teilen» öffnet das Teilen-Menü. */
@Composable
internal fun DiagnosticsCard(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    GroupCard {
        SettingsAnchor("about.diagnostics") {
            SettingsNavRow(
                title = stringResource(R.string.diagnostics_share),
                onClick = {
                    scope.launch {
                        runCatchingCancellable { viewModel.build(context.applicationContext) }
                            .onSuccess(viewModel::showPreview)
                            .onFailure { Timber.w(it, "Diagnose nicht erstellt") }
                    }
                },
            )
        }
        Hint(stringResource(R.string.diagnostics_share_hint))
    }
    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { viewModel.cancel(context.cacheDir) },
            title = { Text(stringResource(R.string.diagnostics_preview_title)) },
            text = {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    shareDiagnostics(context, text)
                    viewModel.dismiss()
                }) { Text(stringResource(R.string.diagnostics_action_share)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancel(context.cacheDir) }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * Bericht als Datei im Cache ([DiagnosticsFiles], nie gesichert, beim nächsten Start gelöscht) und über das System-Teilen-Menü weitergeben
 * (FileProvider, nur Lesezugriff für die gewählte App). Ohne Datei (Speicher voll): als Text.
 */
private fun shareDiagnostics(context: Context, text: String) {
    val subject = context.getString(R.string.diagnostics_share)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject)
    val uri = runCatching {
        val dir = DiagnosticsFiles.dir(context.cacheDir).apply { mkdirs() }
        val file = File(dir, DiagnosticReport.FILE_NAME)
        file.writeText(text, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}$DIAGNOSTICS_AUTHORITY_SUFFIX", file)
    }.onFailure { Timber.w(it, "Diagnose-Datei nicht geschrieben") }.getOrNull()
    if (uri != null) {
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.clipData = ClipData.newRawUri(DiagnosticReport.FILE_NAME, uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        send.putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }.onFailure { Timber.w(it, "Teilen nicht möglich") }
}

/** Kennung des FileProviders im Manifest: `${applicationId}.diagnostics`. */
private const val DIAGNOSTICS_AUTHORITY_SUFFIX = ".diagnostics"
