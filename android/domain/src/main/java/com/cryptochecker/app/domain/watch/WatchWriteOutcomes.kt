// Ausgänge der Schreibwege der Merkliste ([WatchStore]): Alarm speichern, Paar anlegen, gelöschtes Paar zurückholen.
package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.model.AlarmEntity

/** Ausgang von [WatchStore.trySaveAlarm] bzw. [WatchStore.trySaveAlarmMerged]. */
sealed interface AlarmSaveResult {
    /** Gespeichert, mit seiner Id. */
    data class Saved(val alarm: AlarmEntity) : AlarmSaveResult

    /** Neuer Alarm, aber das Paar hat schon [WatchlistLimits.MAX_ALARMS_PER_PAIR] Alarme: nur dann «Grenze erreicht». */
    data object Full : AlarmSaveResult

    /** Merkliste gesperrt bzw. nicht bestätigt (`userWriteRefused`): nichts gespeichert, keine Meldung «voll». */
    data object Refused : AlarmSaveResult
}

/** Ausgang von [WatchStore.tryAddWatch]. */
sealed interface AddWatchOutcome {
    data class Added(val id: Long) : AddWatchOutcome

    /** Paar stand schon in der Merkliste. */
    data object Duplicate : AddWatchOutcome

    /** Merkliste hat schon [WatchlistLimits.MAX_PAIRS] Paare. */
    data object Full : AddWatchOutcome

    /** Merkliste gesperrt (`userWriteRefused`): nichts angelegt, der kurze Hinweis ist schon ausgelöst. */
    data object Locked : AddWatchOutcome
}

/** Ausgang von [WatchStore.restore]. */
enum class RestoreOutcome {
    RESTORED,

    /** Paar besteht noch, wurde inzwischen neu angelegt, Id vergeben oder Merkliste gesperrt. */
    BLOCKED,

    /** Merkliste hat schon [WatchlistLimits.MAX_PAIRS] Paare: nicht zurückgeholt. */
    FULL,
}
