package com.cryptochecker.app.ui.features.alarms

import androidx.compose.runtime.saveable.Saver
import com.cryptochecker.app.data.local.model.AlarmCondition

/**
 * Entwurf im Alarm-Editor über Drehen, Sprachwechsel und Prozess-Neustart retten (`rememberSaveable`):
 * [AlarmDraft] ist kein Parcelable, darum als Liste einfacher Werte (Long, String, Boolean, Int, Double,
 * null — alles, was ins Bundle passt). Reihenfolge = [save]; eine fremde oder alte Liste ergibt null,
 * dann bleibt der Editor zu statt mit halbem Entwurf aufzugehen.
 */
internal object AlarmDraftSaver {

    /** Anzahl der Felder in [save]; ändert sich mit jedem neuen Feld (alte Stände gelten dann als fremd). */
    const val FIELDS = 14

    fun save(draft: AlarmDraft): List<Any?> = listOf(
        draft.id,
        draft.condition.name,
        draft.thresholdText,
        draft.repeating,
        draft.sound,
        draft.vibrate,
        draft.speak,
        draft.windowHours,
        draft.currency,
        draft.priceHint,
        draft.decimalSeparator.toString(),
        draft.newExtremeOnly,
        draft.suggestedText,
        draft.blankStart,
    )

    fun restore(saved: List<*>): AlarmDraft? {
        if (saved.size != FIELDS) return null
        return runCatching {
            AlarmDraft(
                id = saved[0] as Long,
                condition = AlarmCondition.valueOf(saved[1] as String),
                thresholdText = saved[2] as String,
                repeating = saved[3] as Boolean,
                sound = saved[4] as Boolean,
                vibrate = saved[5] as Boolean,
                speak = saved[6] as Boolean,
                windowHours = saved[7] as Int,
                currency = saved[8] as String?,
                priceHint = saved[9] as Double?,
                decimalSeparator = (saved[10] as String).single(),
                newExtremeOnly = saved[11] as Boolean,
                suggestedText = saved[12] as String?,
                blankStart = saved[13] as Boolean,
            )
        }.getOrNull()
    }

    /** Für `rememberSaveable(stateSaver = …)`: kein Entwurf (Editor zu) wird gar nicht gespeichert. */
    val saver: Saver<AlarmDraft?, Any> = Saver(
        save = { draft -> draft?.let { ArrayList(save(it)) } },
        restore = { saved -> (saved as? List<*>)?.let(::restore) },
    )
}
