package com.cryptochecker.app.settings

/** Momentaufnahme aller Einstellungen. */
data class AppSettings(
    /** Hintergrund-Aktualisierung über WorkManager. */
    val backgroundUpdates: Boolean = true,

    /** Intervall der Hintergrund-Aktualisierung in Minuten (mindestens 15). */
    val backgroundIntervalMinutes: Int = 15,

    /** Dauerbetrieb im Vordergrunddienst mit kurzem Intervall. */
    val liveService: Boolean = false,

    /** Intervall des Vordergrunddienstes in Sekunden. */
    val liveIntervalSeconds: Int = 60,

    /**
     * Live-Kurse per WebSocket, solange die Merkliste offen ist (Binance, Bybit, OKX, Coinbase,
     * Kraken; siehe `LivePriceStream`). Hintergrund und Widgets fragen weiter per REST ab.
     */
    val liveWebSocket: Boolean = false,

    /** Kurs-Benachrichtigungen global erlauben. */
    val priceNotifications: Boolean = true,

    /** Kurs-Benachrichtigungen sind nicht wegwischbar. */
    val ongoingNotifications: Boolean = true,

    /**
     * Mindestveränderung in Prozent, ab der eine Kurs-Benachrichtigung
     * gezeigt wird. 0 bedeutet: bei jeder Aktualisierung melden.
     */
    val notificationChangePercent: Double = 5.0,

    /** Sprachausgabe global erlauben. */
    val ttsEnabled: Boolean = false,

    /** Nur Alarme vorlesen, nicht jede Aktualisierung. */
    val ttsAlarmsOnly: Boolean = true,

    /** Sprechgeschwindigkeit (0.5 – 2.0). */
    val ttsSpeechRate: Float = 1.0f,

    /** Ruhezeit, bevor derselbe Alarm erneut auslöst (Minuten). */
    val alarmCooldownMinutes: Int = 0,

    /**
     * Quartals- und andere Laufzeit-Futures einzeln nachprüfen, wenn sie in
     * der Sammelabfrage fehlen (ihre Kennung wechselt beim Verfall). Aus:
     * werden wie andere fehlende Paare als „nicht mehr gehandelt“ markiert.
     */
    val includeRollingFutures: Boolean = false,

    /**
     * Futures auf Aktien, Rohstoffe, Devisen und Pre-IPO (Binance «TradFi-Perpetuals») in der
     * Auswahl zeigen. Aus (Standard): nur Krypto; schon gemerkte Paare bleiben in der Merkliste.
     */
    val includeTradFiFutures: Boolean = false,

    /** Akzentfarbe für App, Widgets, Benachrichtigungen und App-Icon. */
    val accentColor: AccentColor = AccentColor.DEFAULT,

    /**
     * Dunkles Erscheinungsbild für App und Widgets. null = wie das System
     * (Standard bei neuer Installation).
     */
    val darkMode: Boolean? = null,

    /** Entwickleroption: HTTP-Protokoll unten im Märkte-Tab anzeigen. */
    val showHttpLog: Boolean = false,

    /** Entwickler-Bereich freigeschaltet (sieben Mal auf die Version tippen). */
    val developerUnlocked: Boolean = false,

    /** Bei einem Wechsel der Bitcoin-Marktphase benachrichtigen. */
    val zoneAlerts: Boolean = true,

    /** Fear & Greed: melden, wenn der Index unter/über diesen Wert fällt/steigt (0 = aus). */
    val fearGreedBelow: Int = 25,
    val fearGreedAbove: Int = 75,

    /** Gas-Alarm Ethereum: melden, wenn die normale Gebühr unter diesen Wert fällt — in Zehntel-gwei (0 = aus). */
    val gasAlertEthTenths: Int = 0,

    /** Gas-Alarm Bitcoin: melden, wenn die normale Gebühr unter diesen Wert (sat/vB) fällt (0 = aus). */
    val gasAlertBtc: Int = 0,

    /** Eigener Alarmton (Inhalts-URI). null = Standardton des Systems. */
    val alarmSoundUri: String? = null,

    /** Anzeigename des Alarmtons. */
    val alarmSoundName: String? = null,

    /**
     * Zähler für den Mitteilungskanal der Alarme: Android erlaubt keinen
     * Tonwechsel bei einem bestehenden Kanal, also entsteht je Ton ein neuer.
     */
    val alarmChannelVersion: Int = 0,

    /**
     * «Alarm-Signal»: System (bisheriger Kanal), Ton und Vibration, nur Ton, nur Vibration
     * oder lautlos (nur Mitteilung). Je Signal ein eigener Mitteilungskanal.
     */
    val alarmSignal: com.cryptochecker.app.domain.alarm.AlarmSignal =
        com.cryptochecker.app.domain.alarm.AlarmSignal.DEFAULT,

    /** Der Gesten-Hinweis in der Merkliste wurde weggeklickt. */
    val gestureHintSeen: Boolean = false,

    /** Der Willkommensdialog wurde bereits bestätigt. */
    val aboutSeen: Boolean = false,

    /** Der Hinweis zur Akku-Optimierung wurde bereits gezeigt. */
    val batteryPromptSeen: Boolean = false,

    /** Gewählte Gruppe in der Merkliste. null = «Alle». */
    val watchlistGroup: String? = null,

    /** Ungewöhnliche Aktivität (Bewegung, Volumen, Futures) als Benachrichtigung melden. */
    val activityAlerts: Boolean = true,

    /** Empfindlichkeit von «Ungewöhnliche Aktivität» (Karte und Meldungen); Standard = bisherige Schwellen. */
    val activitySensitivity: com.cryptochecker.app.domain.activity.ActivitySensitivity =
        com.cryptochecker.app.domain.activity.ActivitySensitivity.NORMAL,

    /** Morgen-Meldung (08:00) an Tagen mit wichtigen US-Wirtschaftsdaten. */
    val macroNotifications: Boolean = true,

    /** Optionaler Bereich «Portfolio» (eigener Tab vor den Optionen). */
    val portfolioEnabled: Boolean = false,

    /**
     * Umrechnungswährung: Zielwährung der Umrechnungszeile im Portfolio und der
     * umgerechneten Kurse in der Merkliste (USD = keine Umrechnung im Portfolio).
     */
    val portfolioCurrency: String = defaultCurrency(),

    /** Kurse in der Merkliste zusätzlich in der Umrechnungswährung zeigen (« ≈ 61’234 CHF »). */
    val showConverted: Boolean = false,

    /** Kursfarben steigend/fallend: Grün/Rot oder Blau/Orange (Rot-Grün-Sehschwäche). */
    val priceColorScheme: PriceColorScheme = PriceColorScheme.DEFAULT,

    /** Mini-Chart (24-Stunden-Verlauf) in den Zeilen der Merkliste. */
    val watchlistSparkline: Boolean = true,

    /**
     * «Basis der %-Änderung»: rollende 24 Stunden (Standard), seit 00:00 UTC oder seit 00:00
     * Ortszeit — für Pille, Puls, Aktionsblatt und Widgets; Alarme rechnen unabhängig davon.
     */
    val changeBasis: com.cryptochecker.app.domain.watch.ChangeBasis =
        com.cryptochecker.app.domain.watch.ChangeBasis.DEFAULT,

    /**
     * Hoher Kontrast: kräftigere Kursfarben und dunklere Nebentexte (App und Widgets).
     * Wirkt zusätzlich, wenn das System mehr Kontrast verlangt (siehe [HighContrast]).
     */
    val highContrast: Boolean = false,

    /**
     * Kursfarben tauschen (Rot = steigend, Grün = fallend, wie in Ostasien üblich).
     * Nur die Farben; Vorzeichen und Screenreader-Wörter bleiben. Standard nach Region.
     */
    val priceColorsInverted: Boolean = defaultPriceColorsInverted(),

    /** Nachtruhe: Alarme kommen in diesem Zeitraum lautlos (siehe QuietHours). */
    val quietHoursEnabled: Boolean = false,

    /** Beginn der Nachtruhe in Minuten seit Mitternacht (Standard 23:00). */
    val quietHoursStart: Int = com.cryptochecker.app.domain.alarm.QuietHours.DEFAULT_START,

    /** Ende der Nachtruhe in Minuten seit Mitternacht (Standard 07:00). */
    val quietHoursEnd: Int = com.cryptochecker.app.domain.alarm.QuietHours.DEFAULT_END,

    /**
     * Portfolio-Sperre (Schlüssel «app_lock» wie bisher): Portfolio-Tab, seine Unterseiten,
     * Sicherung mit Portfolio-Daten und Portfolio-Widget erst nach Biometrie oder Geräte-PIN
     * (Kaltstart, nach > 60 s im Hintergrund). Die übrige App ist nie gesperrt.
     */
    val appLock: Boolean = false,

    /**
     * «Beträge verbergen»: Portfolio-Beträge und -Werte als «•••» (Prozente bleiben) — im
     * Portfolio, im Portfolio-Widget und in Portfolio-Alarmen. Auch in der Sicherung.
     */
    val hidePortfolioAmounts: Boolean = false,

    /**
     * Die Bestätigung nach dem ersten Alarm wurde gezeigt (oder es gab schon Alarme).
     * Nur auf diesem Gerät, nicht in der Sicherung.
     */
    val firstAlarmShown: Boolean = false,

    /**
     * Das allererste Paar wurde hinzugefügt (Erst-Moment gezeigt oder die Merkliste
     * war beim Update schon gefüllt). Nur auf diesem Gerät, nicht in der Sicherung.
     */
    val firstPairAdded: Boolean = false,

    /**
     * Chart im Aktionsblatt eines Paars als Linie statt Kerzen (zuletzt gewählt, für alle
     * Paare gleich). Nur auf diesem Gerät, nicht in der Sicherung.
     */
    val sheetChartLine: Boolean = false,

    /** Karte «Wertverlauf» im Portfolio aufgeklappt (Standard zu). Nur auf diesem Gerät, nicht in der Sicherung. */
    val portfolioHistoryExpanded: Boolean = false,

    /**
     * Der Markt-Tab wurde schon einmal gesehen (mindestens 3 s sichtbar): «Einordnung» und
     * «Daten» beginnen danach zugeklappt. Nur auf diesem Gerät, nicht in der Sicherung.
     */
    val marketTabSeen: Boolean = false,

    /** Zuletzt gewählter Zeitraum des Wertverlaufs. Nur auf diesem Gerät, nicht in der Sicherung. */
    val portfolioHistoryRange: com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange =
        com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange.MONTH,
) {
    companion object {
        const val MIN_BACKGROUND_INTERVAL_MINUTES = 15
        const val MIN_LIVE_INTERVAL_SECONDS = 15

        val BACKGROUND_INTERVAL_CHOICES = listOf(15, 30, 60)
        val LIVE_INTERVAL_CHOICES = listOf(15, 30, 60, 300)
        val ALARM_COOLDOWN_CHOICES = listOf(0, 5, 15, 60)
        val FEAR_GREED_BELOW_CHOICES = listOf(0, 10, 20, 25)
        val FEAR_GREED_ABOVE_CHOICES = listOf(0, 75, 80, 90)

        /** Gas-Alarm Ethereum in Zehntel-gwei: aus, 0.5, 1, 2, 5. */
        val GAS_ETH_CHOICES = listOf(0, 5, 10, 20, 50)

        /** Gas-Alarm Bitcoin in sat/vB. */
        val GAS_BTC_CHOICES = listOf(0, 1, 2, 5, 10)
    }
}

/** Regionen, in denen Rot für steigende Kurse steht. */
private val INVERTED_PRICE_COLOR_REGIONS = setOf("CN", "TW", "HK", "MO", "JP", "KR")

/**
 * Standard für «Farben tauschen»: an in CN, TW, HK, MO, JP und KR (Region des Geräts).
 * Region aus der System-Konfiguration: Ist in der App eine Sprache gewählt, setzt
 * [AppLanguages] `Locale.setDefault` auf ein Tag ohne Region (z. B. «ja»), dann wäre
 * `Locale.getDefault().country` leer.
 */
fun defaultPriceColorsInverted(locale: java.util.Locale = deviceRegionLocale()): Boolean =
    locale.country.uppercase() in INVERTED_PRICE_COLOR_REGIONS

/** Region des Geräts (System-Konfiguration), auch wenn in der App eine Sprache gewählt ist. */
internal fun deviceRegionLocale(): java.util.Locale =
    runCatching { android.content.res.Resources.getSystem().configuration.locales[0] }.getOrNull()
        ?.takeIf { it.country.isNotEmpty() }
        ?: java.util.Locale.getDefault()

/**
 * Standard-Umrechnungswährung: die Währung des Geräte-Landes (Schweiz → CHF,
 * Deutschland → EUR, USA → USD …), sofern dafür ein Devisenkurs verfügbar ist,
 * sonst USD. Gilt nur, bis jemand selbst eine Währung wählt.
 */
fun defaultCurrency(locale: java.util.Locale = java.util.Locale.getDefault()): String {
    val code = runCatching { java.util.Currency.getInstance(locale)?.currencyCode }.getOrNull()
    return code?.takeIf { it in com.cryptochecker.app.data.portfolio.FxRateSource.CURRENCIES } ?: "USD"
}
