package com.cryptochecker.app.ui.features.alarms

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.ui.components.ScreenEvents
import kotlin.reflect.KProperty

/** Seit Build 107 (Review ARC5-R01): Ergebnis von Speichern bzw. Schnell-Alarm für den aktuellen Bildschirm. */
internal sealed interface AlarmEditorEvent {
    /**
     * Gespeichert: Erlaubnis fragen, Haptik, Bestätigung. [isNew]: neu angelegt; [allowed]: darf als Mitteilung erscheinen;
     * [firstEver]: gleich kommt die Bestätigung des allerersten Alarms (dann keine eigene Zeile).
     */
    data class Saved(val alarm: AlarmEntity, val isNew: Boolean, val allowed: Boolean, val firstEver: Boolean) : AlarmEditorEvent

    /** Schnell-Alarm angelegt: Erlaubnis fragen, Haptik, «Rückgängig» anbieten. */
    data class Created(val alarm: AlarmEntity) : AlarmEditorEvent

    /** Paar inzwischen voll: nichts angelegt, Hinweis. */
    data object Limit : AlarmEditorEvent
}

/**
 * Seit Build 107 (Review ARC5-R01): offener Alarm-Editor samt Eingaben im ViewModel ([SavedStateHandle], übersteht Drehen und
 * Prozess-Ende wie vorher `rememberSaveable`) statt im Bildschirm. So schliesst «Trotzdem» in der Rückfrage F-08 den Editor
 * auch, wenn die Activity inzwischen neu erstellt wurde (sonst bliebe er offen und ein zweites «Speichern» legte einen
 * zweiten Alarm an). Der Bildschirm liest und schreibt [draft] als Delegat (`var draft by viewModel.editor`).
 */
internal class AlarmEditorState(private val handle: SavedStateHandle) {
    private val state = mutableStateOf(handle.get<List<*>>(KEY)?.let(AlarmDraftSaver::restore))

    /** Ergebnisse fürs aktuelle Bild ([com.cryptochecker.app.ui.components.CollectScreenEvents]). */
    val events = ScreenEvents<AlarmEditorEvent>()

    /** Offener Entwurf; null = Editor zu. */
    var draft: AlarmDraft?
        get() = state.value
        set(value) {
            state.value = value
            // Wie AlarmDraftSaver.saver: Bundle-taugliche Liste, kein Entwurf = nichts gespeichert
            handle[KEY] = value?.let { ArrayList(AlarmDraftSaver.save(it)) }
        }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): AlarmDraft? = draft

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: AlarmDraft?) {
        draft = value
    }

    /** Nach bestätigter Rückfrage F-08 bzw. gesperrt: Editor zu. */
    fun close() {
        draft = null
    }

    /** Paar voll ([AlarmEditorEvent.Limit]). */
    fun limit() = events.send(AlarmEditorEvent.Limit)

    private companion object {
        const val KEY = "alarm_editor_draft"
    }
}
