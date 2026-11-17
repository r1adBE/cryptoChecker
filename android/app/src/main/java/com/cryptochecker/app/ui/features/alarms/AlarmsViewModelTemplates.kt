// Alarm aus einer Vorlage anlegen — Teil von AlarmsViewModel.
package com.cryptochecker.app.ui.features.alarms

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchlistStorageLock
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.remote.NearExtremeDataSource
import com.cryptochecker.app.data.remote.VolumeDataSource
import com.cryptochecker.app.domain.alarm.AlarmCheckStatus
import com.cryptochecker.app.domain.alarm.AlarmEdit
import com.cryptochecker.app.domain.alarm.AlarmSaveBuilder
import com.cryptochecker.app.domain.alarm.AlarmTemplates
import com.cryptochecker.app.domain.alarm.AlarmTimingHint
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.model.AlarmEntity
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.settings.SettingsRepository
import com.cryptochecker.app.domain.watch.AlarmSaveResult
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.domain.watch.WatchStore
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.app.notification.alarmsAllowed
import com.cryptochecker.app.notification.canShowAlarm
import com.cryptochecker.app.ui.navigation.ScreenRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Schnell-Alarm sofort anlegen (einmalig, Ton/Vibration wie neue Alarme). Ergebnis als
 * [AlarmEditorEvent.Created] (Id für «Rückgängig», Bedingung für die Bestätigung) bzw. [AlarmEditorEvent.Limit] (Paar
 * voll). Editor zu gleich nach bestätigter Rückfrage «nicht gespeichert», nicht bei «Abbrechen».
 */
fun AlarmsViewModel.createFromTemplate(template: AlarmTemplates.Template) {
    if (alarmSaveBlocked()) return
    createGuard.launch(viewModelScope, guarded = true) {
        // Rückfrage «nicht gespeichert» vor der Aktion; «Abbrechen» = nichts, keine Meldung, der Editor bleibt offen
        if (!watchRepository.confirmUserWrite()) return@launch else editor.close()
        val w = watch.value ?: watchRepository.getWatch(watchId) ?: return@launch
        val def = AlarmTemplates.definition(template, w.lastPrice) ?: return@launch
        val firstEver = notices.isFirstAlarm(existing = false)
        val alarm = AlarmEntity(
            watchId = watchId,
            condition = def.condition,
            threshold = def.threshold,
            enabled = true,
            repeating = def.repeating,
            referencePrice = def.referencePrice,
            windowHours = def.windowHours,
        )
        val id = saveIfWatchExists(alarm, editor::limit) ?: return@launch
        val timingHint = notices.markSaved(firstEver)
        // Dialoge zuerst: Die Bestätigung mit «Rückgängig» wartet, bis sie geschlossen sind (sonst liefe sie dahinter ab)
        if (firstEver) notices.showFirstAlarm()
        if (timingHint) notices.showTimingHint()
        editor.events.send(AlarmEditorEvent.Created(alarm.copy(id = id)))
    }
}
