package com.cryptochecker.marketdata.watch

/**
 * Alter der Kurse auf der Uhr (Uhr-App und Komplikation): «veraltet» nach derselben Regel wie Merkliste und Widgets
 * (`OutdatedRule`: dreimal das Intervall, mindestens 15 Min.), mit dem Intervall des Handys ([WatchSettings.interval],
 * begrenzt wie [WatchInterval.clamp]) — also nach 45 Min. bei 15, 90 Min. bei 30, 3 Std. bei 60. Gezählt je Coin ab
 * dem Stand seines Abrufs (letzter Abruf, der diesen Coin lieferte), nicht ab dem letzten Handel: die 24-h-Ticker der
 * Börsen geben dafür keine verlässliche Zeit. Alter für den Screenreader in Minuten, Stunden, Tagen wie `A11ySpeech.age`. Wie iOS `WatchFreshness` (Paritäts-Fixture
 * `watch_quotes.json`, Abgleich mit `OutdatedRule` in `WatchFreshnessParityTest`).
 */
object WatchFreshness {
    /** Takt der Komplikation: Manifest `UPDATE_PERIOD_SECONDS` = 900, iOS-Zeitleiste 15 Min. */
    const val UPDATE_MINUTES = 15

    /**
     * = `OutdatedRule.afterMillis(WatchInterval.clamp(intervalMinutes))` = 3 × Intervall (das begrenzte Intervall ist
     * mindestens 15 Min., die Untergrenze von `OutdatedRule` greift also nie).
     */
    fun outdatedAfterMillis(intervalMinutes: Int): Long = 3L * WatchInterval.clamp(intervalMinutes) * 60_000L

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /**
     * Veraltet beim Intervall [intervalMinutes] ([outdatedAfterMillis])? Nie geladen (≤ 0) nie — dann steht ohnehin «—».
     * Wie `OutdatedRule.isOutdated` (strikt älter).
     */
    fun isOutdated(time: Long, now: Long, intervalMinutes: Int): Boolean =
        time > 0 && now - time > outdatedAfterMillis(intervalMinutes)

    enum class AgeUnit { JUST_NOW, MINUTES, HOURS, DAYS }

    /** Alter in einer Einheit ([count] ganze Einheiten; bei [AgeUnit.JUST_NOW] 0). */
    data class Age(val unit: AgeUnit, val count: Int)

    /** Alter von [time] zu [now]: unter 1 Min. (auch Zeit in der Zukunft) «gerade eben», dann Minuten, Stunden, Tage. */
    fun age(time: Long, now: Long): Age {
        val diff = now - time
        return when {
            diff < MINUTE -> Age(AgeUnit.JUST_NOW, 0)
            diff < HOUR -> Age(AgeUnit.MINUTES, (diff / MINUTE).toInt())
            diff < DAY -> Age(AgeUnit.HOURS, (diff / HOUR).toInt())
            else -> Age(AgeUnit.DAYS, (diff / DAY).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }
}
