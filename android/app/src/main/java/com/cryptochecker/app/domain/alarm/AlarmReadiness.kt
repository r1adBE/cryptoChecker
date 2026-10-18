package com.cryptochecker.app.domain.alarm

/**
 * Seite «Damit Alarme ankommen» (Build 81): aus dem echten Zustand von System und App je Punkt eine Zeile mit
 * ✓ / ⚠ / Hinweis und dem passenden Knopf. Reine Logik (ohne android.*) — wie `AlarmReadiness.swift`; gemeinsame
 * Fälle in `testdata/parity/alarm_delivery.json`. Liest nur (Einstellungen, Erlaubnisse, «Letzte Prüfungen»): an der
 * Prüfung der Alarme selbst ändert die Seite nichts.
 */
object AlarmReadiness {

    /** Letzte Prüfung im Hintergrund älter als das: «länger her» (bei Android-Takt 15 Min. und iOS-Laune ehrlich genug). */
    const val STALE_CHECK_MILLIS = 6L * 60 * 60_000

    enum class Platform { ANDROID, IOS }

    /** Darf die App Mitteilungen zeigen? (Android: Erlaubnis und App-Benachrichtigungen an.) */
    enum class Access(val code: String) { GRANTED("granted"), DENIED("denied"), NOT_DETERMINED("not_determined") }

    /** Wichtigkeit des Alarm-Kanals (nur Android); null = noch nicht angelegt (entsteht mit «hoch»). */
    enum class Channel(val code: String) { HIGH("high"), LOW("low"), OFF("off") }

    enum class Row(val code: String) {
        NOTIFICATIONS("notifications"),
        CHANNEL("channel"),
        ALERT_SOUND("alert_sound"),
        TIME_SENSITIVE("time_sensitive"),
        EXACT("exact"),
        BATTERY("battery"),
        BACKGROUND("background"),
        BACKGROUND_REFRESH("background_refresh"),
        LOW_POWER("low_power"),
        DND("dnd"),
        QUIET_HOURS("quiet_hours"),
        LAST_CHECK("last_check"),
        SWIPE_AWAY("swipe_away"),
        LIVE_ACTIVITY("live_activity"),
    }

    /** ✓, ⚠ oder neutraler Hinweis (nur zur Kenntnis, z. B. Akku-Optimierung — die App fragt bewusst nicht nach der Ausnahme). */
    enum class State(val code: String) { OK("ok"), WARN("warn"), INFO("info") }

    /** Welcher Text in der Zeile steht (Zuordnung zum Text in der Oberfläche). */
    enum class Detail(val code: String) {
        ALLOWED("allowed"),
        ON("on"),
        OFF("off"),
        NOT_ASKED("not_asked"),
        CHANNEL_HIGH("channel_high"),
        CHANNEL_LOW("channel_low"),
        CHANNEL_OFF("channel_off"),
        EXACT_ON("exact_on"),
        EXACT_OFF("exact_off"),
        EXACT_NO_PERMISSION("exact_no_permission"),
        BATTERY_UNRESTRICTED("battery_unrestricted"),
        BATTERY_OPTIMIZED("battery_optimized"),
        RESTRICTED("restricted"),
        ALERT_OFF("alert_off"),
        SOUND_OFF("sound_off"),
        QUIET_NOW("quiet_now"),
        QUIET_SET("quiet_set"),
        CHECK_RECENT("check_recent"),
        CHECK_OLD("check_old"),
        CHECK_NEVER("check_never"),
        NOTE("note"),
    }

    /** Knopf rechts: Systemseite bzw. Seite der App; nur bei Zeilen, die nicht ✓ sind. */
    enum class Action(val code: String) {
        NONE("none"),
        NOTIFICATION_SETTINGS("notification_settings"),
        REQUEST_PERMISSION("request_permission"),
        CHANNEL_SETTINGS("channel_settings"),
        EXACT_PERMISSION("exact_permission"),
        UPDATE_SETTINGS("update_settings"),
        BATTERY_SETTINGS("battery_settings"),
        APP_SETTINGS("app_settings"),
        ALARM_SETTINGS("alarm_settings"),
        CHECK_RUNS("check_runs"),
    }

    data class Item(val row: Row, val state: State, val detail: Detail, val action: Action = Action.NONE)

    /** Gelesener Zustand. Felder der anderen Plattform bleiben auf dem Standard und werden nicht ausgewertet. */
    data class Input(
        val platform: Platform,
        val notifications: Access,
        val now: Long,
        /** Minute des Tages in Ortszeit (für «Nachtruhe gerade aktiv»). */
        val minuteOfDay: Int,
        val quietEnabled: Boolean = false,
        val quietStart: Int = QuietHours.DEFAULT_START,
        val quietEnd: Int = QuietHours.DEFAULT_END,
        /** Ende der letzten vom System geplanten Prüfung (WorkManager, Wecker, BGTask); null = noch keine. */
        val lastBackgroundCheck: Long? = null,
        /** Gibt es eingeschaltete Alarme? Ohne sie ist eine alte Prüfung nur ein Hinweis. */
        val hasAlarms: Boolean = false,
        // ---- Android
        val channel: Channel? = null,
        val exactEnabled: Boolean = false,
        val exactPermitted: Boolean = true,
        val batteryUnrestricted: Boolean = false,
        val backgroundRestricted: Boolean = false,
        /** «Nicht stören» des Systems an; null = nicht lesbar (keine Zeile). */
        val systemDnd: Boolean? = null,
        // ---- iOS
        val alertEnabled: Boolean = true,
        val soundEnabled: Boolean = true,
        /** Alarm-Signal mit Ton gewählt? Bei «Lautlos» ist ein ausgeschalteter Ton gewollt. */
        val wantsSound: Boolean = true,
        /** «Dringliche Mitteilungen»; null = nicht unterstützt (keine Zeile). */
        val timeSensitive: Boolean? = null,
        val backgroundRefresh: Boolean = true,
        val lowPower: Boolean = false,
    )

    /** Zeilen in fester Reihenfolge je Plattform. */
    fun rows(input: Input): List<Item> = when (input.platform) {
        Platform.ANDROID -> listOfNotNull(
            notifications(input),
            channel(input),
            exact(input),
            battery(input),
            background(input),
            dnd(input),
            quietHours(input),
            lastCheck(input),
        )
        Platform.IOS -> listOfNotNull(
            notifications(input),
            alertSound(input),
            timeSensitive(input),
            backgroundRefresh(input),
            lowPower(input),
            quietHours(input),
            lastCheck(input),
            Item(Row.SWIPE_AWAY, State.INFO, Detail.NOTE),
            Item(Row.LIVE_ACTIVITY, State.INFO, Detail.NOTE),
        )
    }

    /** Mindestens eine Zeile mit ⚠? */
    fun hasWarning(items: List<Item>): Boolean = items.any { it.state == State.WARN }

    /** Alarm-Arten, die eine Prüfung im Hintergrund brauchen ([hasBackgroundAlarms]); Werte wie in den Einstellungen. */
    data class AlarmKinds(
        /** Mindestens ein eingeschalteter Paar-Alarm. */
        val pairAlarm: Boolean = false,
        val portfolioEnabled: Boolean = false,
        /** Mindestens ein scharfer Portfolio-Alarm (zählt nur mit eingeschaltetem Portfolio). */
        val portfolioAlarm: Boolean = false,
        val gasEthTenths: Int = 0,
        val gasBtc: Int = 0,
        val depeg: Boolean = false,
        val fearGreedBelow: Int = 0,
        val fearGreedAbove: Int = 0,
        /** Mindestens eine eigene Grenze einer Bitcoin-Kennzahl. */
        val indicatorAlert: Boolean = false,
    )

    /**
     * [Input.hasAlarms]: Gibt es etwas, das nur eine Prüfung im Hintergrund meldet? Paar- und Portfolio-Alarme, Gas,
     * USDT-Depeg, Angst-&-Gier- und Kennzahlen-Meldungen — nicht nur Paar-Alarme, sonst wäre eine ausbleibende Prüfung
     * bei den anderen bloss ein Hinweis.
     */
    fun hasBackgroundAlarms(k: AlarmKinds): Boolean =
        k.pairAlarm || (k.portfolioEnabled && k.portfolioAlarm) || k.gasEthTenths > 0 || k.gasBtc > 0 || k.depeg ||
            k.fearGreedBelow > 0 || k.fearGreedAbove > 0 || k.indicatorAlert

    /**
     * Zustand der Mitteilungen unter Android: [allowed] (Erlaubnis und App-Benachrichtigungen an) vor allem anderen;
     * sonst «noch nicht gefragt», solange Android 13+ die Abfrage noch zeigen würde ([notDetermined]) — wie iOS
     * `notDetermined`, mit Knopf, der fragt.
     */
    fun androidAccess(allowed: Boolean, notDetermined: Boolean): Access = when {
        allowed -> Access.GRANTED
        notDetermined -> Access.NOT_DETERMINED
        else -> Access.DENIED
    }

    private fun notifications(input: Input): Item = when (input.notifications) {
        Access.GRANTED -> Item(Row.NOTIFICATIONS, State.OK, Detail.ALLOWED)
        Access.DENIED -> Item(Row.NOTIFICATIONS, State.WARN, Detail.OFF, Action.NOTIFICATION_SETTINGS)
        Access.NOT_DETERMINED -> Item(Row.NOTIFICATIONS, State.WARN, Detail.NOT_ASKED, Action.REQUEST_PERMISSION)
    }

    private fun channel(input: Input): Item = when (input.channel) {
        // Noch nicht angelegt: entsteht beim ersten Alarm mit «hoch»
        null, Channel.HIGH -> Item(Row.CHANNEL, State.OK, Detail.CHANNEL_HIGH)
        Channel.LOW -> Item(Row.CHANNEL, State.WARN, Detail.CHANNEL_LOW, Action.CHANNEL_SETTINGS)
        Channel.OFF -> Item(Row.CHANNEL, State.WARN, Detail.CHANNEL_OFF, Action.CHANNEL_SETTINGS)
    }

    private fun exact(input: Input): Item = when {
        input.exactEnabled && input.exactPermitted -> Item(Row.EXACT, State.OK, Detail.EXACT_ON)
        input.exactEnabled -> Item(Row.EXACT, State.WARN, Detail.EXACT_NO_PERMISSION, Action.EXACT_PERMISSION)
        // Aus ist erlaubt (Standard): nur ein Hinweis mit dem Weg zum Schalter
        else -> Item(Row.EXACT, State.INFO, Detail.EXACT_OFF, Action.UPDATE_SETTINGS)
    }

    /** Nur zur Kenntnis: die App bittet bewusst nicht um die Ausnahme, sie öffnet nur die Akku-Seite der App. */
    private fun battery(input: Input): Item =
        if (input.batteryUnrestricted) Item(Row.BATTERY, State.OK, Detail.BATTERY_UNRESTRICTED)
        else Item(Row.BATTERY, State.INFO, Detail.BATTERY_OPTIMIZED, Action.BATTERY_SETTINGS)

    private fun background(input: Input): Item =
        if (input.backgroundRestricted) Item(Row.BACKGROUND, State.WARN, Detail.RESTRICTED, Action.APP_SETTINGS)
        else Item(Row.BACKGROUND, State.OK, Detail.ALLOWED)

    private fun dnd(input: Input): Item? = when (input.systemDnd) {
        null -> null
        true -> Item(Row.DND, State.WARN, Detail.ON)
        false -> Item(Row.DND, State.OK, Detail.OFF)
    }

    private fun quietHours(input: Input): Item {
        val configured = input.quietEnabled && input.quietStart != input.quietEnd &&
            QuietHours.isValidMinute(input.quietStart) && QuietHours.isValidMinute(input.quietEnd)
        return when {
            !configured -> Item(Row.QUIET_HOURS, State.OK, Detail.OFF)
            // Dieselbe Regel wie die Prüfung: in der Nachtruhe warten fällige Alarme
            QuietHours.holdsAlarms(true, input.quietStart, input.quietEnd, input.minuteOfDay) ->
                Item(Row.QUIET_HOURS, State.WARN, Detail.QUIET_NOW, Action.ALARM_SETTINGS)
            else -> Item(Row.QUIET_HOURS, State.INFO, Detail.QUIET_SET, Action.ALARM_SETTINGS)
        }
    }

    private fun lastCheck(input: Input): Item {
        val at = input.lastBackgroundCheck?.takeIf { it > 0 }
        // Ohne eingeschaltete Alarme ist eine fehlende oder alte Prüfung kein Problem, nur ein Hinweis
        val missing = if (input.hasAlarms) State.WARN else State.INFO
        return when {
            at == null -> Item(Row.LAST_CHECK, missing, Detail.CHECK_NEVER, Action.CHECK_RUNS)
            // Uhr zurückgestellt (Zeit in der Zukunft): als eben geprüft werten
            input.now - at <= STALE_CHECK_MILLIS -> Item(Row.LAST_CHECK, State.OK, Detail.CHECK_RECENT)
            else -> Item(Row.LAST_CHECK, missing, Detail.CHECK_OLD, Action.CHECK_RUNS)
        }
    }

    private fun alertSound(input: Input): Item? {
        if (input.notifications != Access.GRANTED) return null
        return when {
            !input.alertEnabled -> Item(Row.ALERT_SOUND, State.WARN, Detail.ALERT_OFF, Action.NOTIFICATION_SETTINGS)
            !input.soundEnabled && input.wantsSound ->
                Item(Row.ALERT_SOUND, State.WARN, Detail.SOUND_OFF, Action.NOTIFICATION_SETTINGS)
            else -> Item(Row.ALERT_SOUND, State.OK, Detail.ON)
        }
    }

    private fun timeSensitive(input: Input): Item? {
        if (input.notifications != Access.GRANTED) return null
        return when (input.timeSensitive) {
            null -> null
            true -> Item(Row.TIME_SENSITIVE, State.OK, Detail.ON)
            false -> Item(Row.TIME_SENSITIVE, State.INFO, Detail.OFF, Action.NOTIFICATION_SETTINGS)
        }
    }

    private fun backgroundRefresh(input: Input): Item =
        if (input.backgroundRefresh) Item(Row.BACKGROUND_REFRESH, State.OK, Detail.ON)
        else Item(Row.BACKGROUND_REFRESH, State.WARN, Detail.OFF, Action.APP_SETTINGS)

    private fun lowPower(input: Input): Item =
        if (input.lowPower) Item(Row.LOW_POWER, State.WARN, Detail.ON)
        else Item(Row.LOW_POWER, State.OK, Detail.OFF)
}
