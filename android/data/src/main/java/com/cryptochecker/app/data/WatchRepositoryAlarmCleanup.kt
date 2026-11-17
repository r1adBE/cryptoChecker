// Vermerke gelöschter Alarme entfernen — Teil von WatchRepository.
package com.cryptochecker.app.data

import androidx.room.withTransaction
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.notification.AlarmDeliveryMarkers
import com.cryptochecker.app.data.refresh.AlarmLogStore
import com.cryptochecker.app.domain.PairEditResult
import com.cryptochecker.app.domain.WatchMove
import com.cryptochecker.app.domain.WatchSnapshot
import com.cryptochecker.app.domain.model.AlarmEntity
import com.cryptochecker.app.domain.model.AlarmWithWatch
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.GROUP_NAME_MAX
import com.cryptochecker.app.domain.model.NOTE_MAX
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.app.domain.util.TextLimit
import com.cryptochecker.app.domain.watch.WatchEdit
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.marketdata.model.ExchangePair
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Nach dem Löschen von Paaren (Alarme per Fremdschlüssel weg): deren letzte Prüfungen aus dem Alarm-Protokoll
 * entfernen — sonst wüchse der Stand, den jeder Durchlauf versiegelt schreibt, mit jedem gelöschten
 * Paar. Versiegeltes Schreiben nicht auf dem Hauptfaden.
 */
internal suspend fun WatchRepository.forgetDeletedAlarms() {
    val ids = watchDao.getAlarmIds().toHashSet()
    withContext(ioDispatcher) {
        alarmLogStore.retainAlarms(ids)
        // Nicht bei Kopie im Speicher — der nächste Prozess bringt die Alarme der Datei zurück
        if (AlarmDeliveryMarkers.prunable()) deliveryMarkers.retain(ids)
    }
}
