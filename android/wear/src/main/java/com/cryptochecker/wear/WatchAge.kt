package com.cryptochecker.wear

import android.content.Context
import android.text.format.DateUtils
import com.cryptochecker.marketdata.watch.WatchFreshness
import java.util.Locale
import kotlin.math.abs

/**
 * Alter veralteter Kurse ([WatchFreshness.isOutdated]) in Uhr-App und Komplikation — wie die Merkliste des Handys:
 * kurz «vor 47 Min.» (Systemtext, mitten in der Zeile klein) und für den Screenreader «Kurs veraltet, zuletzt
 * aktualisiert vor 47 Minuten». Wie iOS `WatchText.ago` / `WatchText.spokenAge`.
 */
object WatchAge {
    /** Ab diesem Abstand liefert [DateUtils.getRelativeTimeSpanString] ein Datum statt einer relativen Zeit. */
    private const val RELATIVE_SPAN_MAX_MILLIS = 7L * 24 * 60 * 60_000

    /** «vor 47 Min.» — erstes Zeichen klein (steht hinter «+1.23%  ·»), ein Datum ab einer Woche bleibt, wie es ist. */
    fun short(time: Long, now: Long, locale: Locale = Locale.getDefault()): String {
        val text = DateUtils.getRelativeTimeSpanString(
            time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
        return if (abs(now - time) >= RELATIVE_SPAN_MAX_MILLIS) text else text.replaceFirstChar { it.lowercase(locale) }
    }

    /** «Kurs veraltet, zuletzt aktualisiert vor 47 Minuten» (Teile mit «, » wie `A11ySpeech.join`). */
    fun spoken(context: Context, time: Long, now: Long): String {
        val age = WatchFreshness.age(time, now)
        val res = context.resources
        val updated = when (age.unit) {
            WatchFreshness.AgeUnit.JUST_NOW -> null
            WatchFreshness.AgeUnit.MINUTES -> R.plurals.a11y_watch_updated_minutes
            WatchFreshness.AgeUnit.HOURS -> R.plurals.a11y_watch_updated_hours
            WatchFreshness.AgeUnit.DAYS -> R.plurals.a11y_watch_updated_days
        }?.let { res.getQuantityString(it, age.count, age.count) }
        return listOfNotNull(context.getString(R.string.a11y_watch_outdated), updated).joinToString(", ")
    }
}
