package com.cryptochecker.app.notification

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.macro.MacroEvent
import com.cryptochecker.app.domain.macro.MacroEventType
import com.cryptochecker.app.domain.macro.MacroHint
import java.util.Date

/**
 * Texte zu den Wirtschaftsdaten (Hinweis im Markt-Tab und Morgen-Meldung), z. B.
 * «Heute 14:30: US-Inflationsdaten (CPI) – an solchen Tagen schwankt der Markt oft stärker.»
 * Uhrzeit in der Zeitzone des Geräts, im Format der App-Sprache (12/24 h wie im System).
 */
object MacroTexts {

    fun label(context: Context, type: MacroEventType): String = context.getString(
        when (type) {
            MacroEventType.CPI -> R.string.macro_cpi
            MacroEventType.PPI -> R.string.macro_ppi
            MacroEventType.NFP -> R.string.macro_nfp
            MacroEventType.FOMC -> R.string.macro_fomc
            MacroEventType.PCE -> R.string.macro_pce
        }
    )

    fun time(context: Context, millis: Long): String =
        android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))

    /** Ein Satz für den Hinweis (bevorstehend oder «veröffentlicht»). */
    fun hint(context: Context, hint: MacroHint): String {
        val items = hint.items
        if (hint.released) {
            return if (items.size == 1) {
                context.getString(R.string.macro_released_one, time(context, items[0].event.time), label(context, items[0].event.type))
            } else {
                context.getString(R.string.macro_released_list, list(context, items.map { it.event }))
            }
        }
        if (items.size == 1) {
            val e = items[0].event
            return context.getString(
                if (items[0].tomorrow) R.string.macro_hint_tomorrow else R.string.macro_hint_today,
                time(context, e.time),
                label(context, e.type)
            )
        }
        return when {
            hint.allToday -> context.getString(R.string.macro_hint_today_list, list(context, items.map { it.event }))
            hint.allTomorrow -> context.getString(R.string.macro_hint_tomorrow_list, list(context, items.map { it.event }))
            else -> context.getString(
                R.string.macro_hint_list,
                items.joinToString(SEPARATOR) {
                    context.getString(
                        if (it.tomorrow) R.string.macro_item_tomorrow else R.string.macro_item_today,
                        time(context, it.event.time),
                        label(context, it.event.type)
                    )
                }
            )
        }
    }

    /** Text der Morgen-Meldung: die Termine von heute. */
    fun todayText(context: Context, events: List<MacroEvent>): String =
        if (events.size == 1) {
            context.getString(R.string.macro_hint_today, time(context, events[0].time), label(context, events[0].type))
        } else {
            context.getString(R.string.macro_hint_today_list, list(context, events))
        }

    /** «14:30 US-Arbeitsmarktdaten · 20:00 Zinsentscheid der US-Notenbank». */
    private fun list(context: Context, events: List<MacroEvent>): String =
        events.joinToString(SEPARATOR) {
            context.getString(R.string.macro_item, time(context, it.time), label(context, it.type))
        }

    private const val SEPARATOR = " · "
}
