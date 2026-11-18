package com.cryptochecker.app.ui.fakes

import com.cryptochecker.app.domain.PairEditResult
import com.cryptochecker.app.domain.model.AlarmWithWatch
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.WatchStore
import com.cryptochecker.marketdata.model.ExchangePair
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Merkliste für ViewModel-Tests: Paare und Alarme als Flows, Schreibwege in [calls]. [confirm] = Antwort auf die Rückfrage
 * «nicht gespeichert», [writesAllowed] = Schreibwege angenommen (false wie bei gesperrter Merkliste).
 */
internal class FakeUiWatchStore(
    val calls: MutableList<String> = mutableListOf(),
) : WatchStore by recorder(WatchStore::class.java, calls) {

    val watches = MutableStateFlow<List<WatchEntity>>(emptyList())
    val alarms = MutableStateFlow<List<AlarmWithWatch>>(emptyList())
    var confirm = true
    var writesAllowed = true
    var pairEditResult = PairEditResult.SAVED

    override fun observeWatches(): Flow<List<WatchEntity>> = watches

    override fun observeAllAlarms(): Flow<List<AlarmWithWatch>> = alarms

    override suspend fun getWatch(id: Long): WatchEntity? = watches.value.firstOrNull { it.id == id }

    override suspend fun confirmUserWrite(): Boolean {
        calls += "confirmUserWrite()"
        return confirm
    }

    override suspend fun setAlarmEnabled(id: Long, enabled: Boolean, fromUser: Boolean): Boolean {
        calls += "setAlarmEnabled($id, $enabled)"
        return writesAllowed
    }

    override suspend fun changePair(id: Long, exchange: ExchangeInfo, pair: ExchangePair): PairEditResult {
        calls += "changePair($id, ${exchange.key}, ${pair.pairId})"
        return pairEditResult
    }
}
