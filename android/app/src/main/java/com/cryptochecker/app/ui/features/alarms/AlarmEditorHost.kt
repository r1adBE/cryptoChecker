// Alarm-Editor eines Paars samt allem, was dazugehört: Rückfrage «Löst sofort aus», «Alarm löschen?», Bestätigung des
// allerersten Alarms, Hinweis «Wann Alarme geprüft werden» und «Rückgängig» nach einem Schnell-Alarm.
// Eigene Datei, weil zwei Seiten denselben Ablauf brauchen: «Alarme» (`AlarmsScreen`) und die Paarseite (`PairScreen`).
// Der offene Editor liegt im ViewModel (`AlarmEditorState`), übersteht also Drehen und Prozess-Neustart.
package com.cryptochecker.app.ui.features.alarms

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.model.AlarmEntity
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import kotlinx.coroutines.launch

/** Öffnet Editor bzw. Rückfrage «Alarm löschen?» des [AlarmEditorHost] (Liste, «Neuer Alarm», «Alarm setzen»). */
@Stable
internal class AlarmEditorHostState(private val viewModel: AlarmsViewModel) {
    /** Rückfrage «Alarm löschen?» für diesen Alarm offen; null = keine. */
    var askDelete by mutableStateOf<AlarmEntity?>(null)

    /** Neuer Alarm im einfachen Modus: Feld leer, aktueller Kurs nur als Platzhalter. */
    fun openNew() {
        viewModel.editor.draft = AlarmDraft.blankFor()
    }

    /** Bestehenden Alarm bearbeiten. */
    fun edit(alarm: AlarmEntity) {
        viewModel.editor.draft = AlarmDraft.from(alarm)
    }
}

@Composable
internal fun rememberAlarmEditorHost(viewModel: AlarmsViewModel): AlarmEditorHostState =
    remember(viewModel) { AlarmEditorHostState(viewModel) }

/**
 * Editor, Rückfragen und Rückmeldungen der Alarme eines Paars ([viewModel]); Bestätigungen und Hinweise in [snackbar]
 * des Bildschirms. Geöffnet wird über [host].
 */
@Composable
internal fun AlarmEditorHost(host: AlarmEditorHostState, viewModel: AlarmsViewModel, snackbar: SnackbarHostState) {
    val watch by viewModel.watch.collectAsStateWithLifecycle()
    val targetCurrency by viewModel.targetCurrency.collectAsStateWithLifecycle()
    val rates by viewModel.rates.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val lastChecks by viewModel.lastChecks.collectAsStateWithLifecycle()
    val firstAlarm by viewModel.firstAlarm.collectAsStateWithLifecycle()
    val timingHint by viewModel.timingHint.collectAsStateWithLifecycle()
    val exactOffer by viewModel.exactOffer.collectAsStateWithLifecycle()
    /** Rückfrage «Alarm löst sofort aus»: der Entwurf, der gespeichert würde. */
    var confirmFiresNow by rememberSaveable(stateSaver = AlarmDraftSaver.saver) { mutableStateOf<AlarmDraft?>(null) }
    var draft by viewModel.editor
    LaunchedEffect(draft?.id) {
        // Schnell-Alarme: prüfen, ob das Paar Kerzen hat (nur beim Anlegen)
        if (draft?.id == 0L) viewModel.loadTemplateData()
    }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val lockedText = stringResource(R.string.alarm_save_storage_locked)
    // Hinweis «Nachtruhe» über die Snackbar dieses Bildschirms (statt eines System-Toasts)
    val testAlarm = com.cryptochecker.app.ui.components.rememberAlarmTest(viewModel::testAlarm) { text ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text, duration = SnackbarDuration.Long)
        }
    }
    // Erlaubnis, Haptik und Bestätigung nach dem Speichern im aktuellen Bildschirm
    AlarmEditorEffects(viewModel, snackbar, watch, testAlarm)

    // Einmal nach dem Speichern: ehrlich, wann bei geschlossener App geprüft wird
    if (timingHint) {
        AlarmTimingDialog(onDismiss = viewModel::dismissTimingHint)
    }

    // Nach dem allerersten Alarm: EIN Dialog statt mehrerer nacheinander — Bestätigung mit «Alarm testen», dazu
    // optional «Pünktlich prüfen» und, nur wenn die Akku-Optimierung die App einschränkt, die
    // Akku-Einstellungen. Der eigene Akku-Dialog entfällt dann in dieser Sitzung. Hauptknopf: «Damit Alarme ankommen».
    if (firstAlarm) {
        FirstAlarmDialog(viewModel = viewModel, exactOffer = exactOffer, testAlarm = testAlarm)
    }

    host.askDelete?.let { alarm ->
        DeleteAlarmDialog(
            onDelete = {
                viewModel.delete(alarm.id)
                host.askDelete = null
            },
            onDismiss = { host.askDelete = null },
        )
    }

    // Merkliste nur leer im Arbeitsspeicher: Alarm nicht anlegen, Editor zu und sagen warum (sonst still verloren)
    fun showSaveLocked(): Boolean {
        if (!viewModel.alarmSaveBlocked()) return false
        draft = null
        snackbar.showNotice(scope, lockedText, haptics, HapticEvent.ERROR)
        return true
    }

    /**
     * Speichern; Editor zu, Erlaubnis und Bestätigung ([AlarmEditorEffects]). Editor erst nach
     * bestätigter Rückfrage «nicht gespeichert» zu («Abbrechen»: offen mit Eingaben), Erlaubnis erst nach dem Speichern.
     */
    fun saveDraft(toSave: AlarmDraft) {
        if (showSaveLocked()) return
        viewModel.save(toSave)
    }

    PendingUndoSnackbar(viewModel.undo, viewModel.firstAlarm, viewModel.timingHint, snackbar, UNDO_MILLIS, viewModel::delete)

    draft?.let { raw ->
        // Aktueller Kurs in der Währung des Schwellwerts: entscheidet bei «60,000» (Tausender oder Komma?)
        val current = raw.copy(priceHint = AlarmDraft.priceHint(watch?.lastPrice, raw.currency, rates))
        AlarmEditorSheet(
            draft = current,
            baseAsset = watch?.baseAsset.orEmpty(),
            quoteAsset = watch?.quoteAsset.orEmpty(),
            targetCurrency = targetCurrency,
            rates = rates,
            templates = templates,
            // Funding/Open Interest nur für Perpetuals an Börsen mit eigenen Daten
            derivatives = watch?.let {
                DerivativesAlarm.supports(it.exchangeKey, it.contractType.isPerpetual)
            } == true,
            onLoadRate = viewModel::loadRate,
            onDraftChange = { draft = it },
            // Editor erst nach bestätigter Rückfrage «nicht gespeichert» zu, Erlaubnis erst nach dem Anlegen
            onTemplate = { template -> if (!showSaveLocked()) viewModel.createFromTemplate(template) },
            onDismiss = { draft = null },
            // Kopf mit Paar und aktuellem Kurs (wie iOS)
            watch = watch,
            lastCheck = lastChecks[current.id],
            onConfirm = {
                // Kursmarke schon erreicht: erst nachfragen (sonst meldet der Alarm sofort)
                if (viewModel.firesOnSave(current)) {
                    confirmFiresNow = current
                } else {
                    saveDraft(current)
                }
            }
        )
    }

    confirmFiresNow?.let { pending ->
        FiresNowDialog(
            pending = pending,
            quoteAsset = watch?.quoteAsset.orEmpty(),
            onSave = {
                confirmFiresNow = null
                saveDraft(pending)
            },
            onDismiss = { confirmFiresNow = null },
        )
    }
}

/** So lange steht «Alarm erstellt · Rückgängig». */
private const val UNDO_MILLIS = 6_000L
