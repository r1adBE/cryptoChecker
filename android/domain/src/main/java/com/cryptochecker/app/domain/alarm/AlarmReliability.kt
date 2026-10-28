package com.cryptochecker.app.domain.alarm

/**
 * Eine Zeile über der Merkliste: Kommen die Alarme an? Verdichtet denselben gelesenen Zustand wie «Damit
 * Alarme ankommen» ([AlarmReadiness.Input]) auf genau einen Satz — ruhig (grau) mit der letzten Prüfung, oder eine
 * Warnung (Bernstein) mit dem wichtigsten Grund; Tipp öffnet die Seite. Ohne Alarme keine Zeile. Reine Logik — wie
 * `AlarmReliability.swift`; gemeinsame Fälle in `testdata/parity/alarm_reliability.json`.
 */
object AlarmReliability {

    /**
     * Android: Letzte Prüfung im Hintergrund älter als das: Warnung. Strenger als die Seite ([AlarmReadiness.STALE_CHECK_MILLIS],
     * 6 h): Die Zeile soll früh zeigen, dass Alarme gerade nicht geprüft werden — bei 15-Min.-Takt sind 2 h klar zu viel.
     */
    const val STALE_MILLIS = 2L * 60 * 60_000

    /**
     * iOS: gleiche Grenze wie die Seite ([AlarmReadiness.STALE_CHECK_MILLIS], 6 h) — iOS startet die Hintergrund-Aufgabe
     * nach eigenem Ermessen, oft nur alle paar Stunden. Hier für den gemeinsamen Testfall.
     */
    const val STALE_MILLIS_IOS = AlarmReadiness.STALE_CHECK_MILLIS

    /** Grenze «zu alt» je Plattform. */
    fun staleMillis(platform: AlarmReadiness.Platform): Long =
        if (platform == AlarmReadiness.Platform.ANDROID) STALE_MILLIS else STALE_MILLIS_IOS

    enum class Kind(val code: String) {
        /** Keine Alarme: keine Zeile. */
        HIDDEN("hidden"),
        /** Ruhig: «Letzte Prüfung vor 12 Min.». */
        RECENT("recent"),
        /** Ruhig: noch keine Prüfung im Hintergrund (eben angelegt; die Seite zeigt den Rest). */
        NEVER("never"),
        /** Mitteilungen nicht erlaubt bzw. Alarm-Kanal aus (Android). */
        NOTIFICATIONS_OFF("notifications_off"),
        /**
         * Android: Merkliste ohne Schlüssel nur im Arbeitsspeicher — Prüfungen im Hintergrund sehen ihre Paare nicht, bis
         * ein Start mit Schlüssel die Datei anlegt. Geht dem Akku vor: Der wäre hier nicht der Grund.
         */
        STORAGE_NO_KEY("storage_no_key"),
        /** Android: Hintergrundnutzung eingeschränkt, oder Prüfung zu alt bei Akku-Optimierung/Akkusparen des Herstellers. */
        BATTERY("battery"),
        /** iOS: Hintergrundaktualisierung aus. */
        BACKGROUND_REFRESH("background_refresh"),
        /**
         * Letzte Prüfung älter als [staleMillis] (oder weit in der Zukunft — Uhr verstellt), oder alle gespeicherten
         * geplanten Läufe gescheitert ([AlarmReadiness.Input.lastFailedBackgroundCheck]).
         */
        STALE("stale"),
    }

    /** [warn]: Bernstein statt Grau. */
    data class Status(val kind: Kind, val warn: Boolean)

    /** Ein Satz aus dem gelesenen Zustand; wichtigster Grund zuerst (ohne Mitteilungen hilft keine Prüfung). */
    fun status(input: AlarmReadiness.Input): Status {
        if (!input.hasAlarms) return Status(Kind.HIDDEN, warn = false)
        val android = input.platform == AlarmReadiness.Platform.ANDROID
        if (input.notifications != AlarmReadiness.Access.GRANTED ||
            (android && input.channel == AlarmReadiness.Channel.OFF)
        ) {
            return Status(Kind.NOTIFICATIONS_OFF, warn = true)
        }
        if (android && input.storageNoKey) return Status(Kind.STORAGE_NO_KEY, warn = true)
        if (android && input.backgroundRestricted) return Status(Kind.BATTERY, warn = true)
        if (!android && !input.backgroundRefresh) return Status(Kind.BACKGROUND_REFRESH, warn = true)
        val at = input.lastBackgroundCheck?.takeIf { it > 0 }
        if (at == null) {
            // Geplante Läufe gab es, gelungen ist keiner: Warnung statt «noch keine Prüfung» (grau)
            val failing = input.lastFailedBackgroundCheck != null
            return Status(if (failing) Kind.STALE else Kind.NEVER, warn = failing)
        }
        val fresh = at - input.now <= AlarmReadiness.FUTURE_TOLERANCE_MILLIS && input.now - at <= staleMillis(input.platform)
        if (fresh) return Status(Kind.RECENT, warn = false)
        // Zu alt: Unter Android ist Akku-Optimierung bzw. das Akkusparen des Herstellers der wahrscheinliche Grund —
        // für sich allein aber nur ein Hinweis (fast jedes Gerät optimiert), darum erst zusammen mit der alten Prüfung
        val batteryLikely = android && (!input.batteryUnrestricted || input.oemVendor != null)
        return Status(if (batteryLikely) Kind.BATTERY else Kind.STALE, warn = true)
    }
}
