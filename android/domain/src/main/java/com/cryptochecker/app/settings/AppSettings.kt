package com.cryptochecker.app.settings

/** Momentaufnahme aller Einstellungen. */
data class AppSettings(
    /**
     * «Wie aktuell?»: Sparsam (Standard) oder Live, solange offen ([UpdateMode]); die
     * Hintergrund-Aktualisierung (WorkManager) läuft in jeder Wahl.
     */
    val updateMode: UpdateMode = UpdateMode.DEFAULT,

    /** Intervall der Hintergrund-Aktualisierung in Minuten (mindestens 15). */
    val backgroundIntervalMinutes: Int = 15,

    /**
     * «Pünktlich prüfen» (nur Android): exakter Wecker alle 15 Min. statt nur WorkManager
     * (`ExactCheckScheduler`). Standard aus; braucht «Wecker und Erinnerungen» (SCHEDULE_EXACT_ALARM).
     */
    val exactChecks: Boolean = false,

    /** Kurs-Benachrichtigungen global erlauben. */
    val priceNotifications: Boolean = true,

    /** Kurs-Benachrichtigungen sind nicht wegwischbar. Standard aus. */
    val ongoingNotifications: Boolean = false,

    /**
     * Preis-Overlay (nur Android): die Paare der dauerhaften Kurs-Mitteilung als schwebende Pille über
     * anderen Apps; wirkt nur mit [ongoingNotifications] und erteilter Berechtigung «Über anderen Apps einblenden».
     * Gerätebezogen, nicht in der Sicherung.
     */
    val priceOverlay: Boolean = false,

    /**
     * Mindestveränderung in Prozent, ab der eine Kurs-Benachrichtigung gezeigt wird. 0 bedeutet: bei jeder Aktualisierung melden.
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
     * Quartals- und andere Laufzeit-Futures einzeln nachprüfen, wenn sie in der Sammelabfrage fehlen (ihre Kennung wechselt
     * beim Verfall). Aus: werden wie andere fehlende Paare als „nicht mehr gehandelt“ markiert.
     */
    val includeRollingFutures: Boolean = false,

    /**
     * «Token & TradFi»: Was die Paar-Auswahl zeigt — nur Token (Krypto, Standard), nur TradFi
     * (Futures auf Aktien, Rohstoffe, Devisen und Pre-IPO) oder beides. Schon gemerkte Paare
     * bleiben in der Merkliste.
     */
    val assetScope: AssetScope = AssetScope.DEFAULT,

    /**
     * «inkl. Alpha-Tokens» (bei «Token & TradFi»): Alpha-Tokens ([com.cryptochecker.app.domain.watch.AssetKind.ALPHA])
     * in der Paar-Auswahl zeigen. Standard aus. Gemerkte Paare bleiben immer.
     */
    val includeAlphaTokens: Boolean = false,

    /** Akzentfarbe für App, Widgets, Benachrichtigungen und App-Icon. */
    val accentColor: AccentColor = AccentColor.DEFAULT,

    /** Dunkles Erscheinungsbild für App und Widgets. null = wie das System (Standard bei neuer Installation). */
    val darkMode: Boolean? = null,

    /** Entwickleroption: HTTP-Protokoll unten im Explorer («Paar hinzufügen») anzeigen. */
    val showHttpLog: Boolean = false,

    /** Entwickler-Bereich freigeschaltet (sieben Mal auf die Version tippen). */
    val developerUnlocked: Boolean = false,

    /**
     * Fear & Greed: melden, wenn der Index unter/über diesen Wert fällt/steigt (0 = aus). Ab Werk
     * aus — am ersten Tag soll nur kommen, was man selbst eingestellt hat.
     */
    val fearGreedBelow: Int = 0,
    val fearGreedAbove: Int = 0,

    /**
     * Eigene Grenzen für Bitcoin-Kennzahlen ([com.cryptochecker.app.domain.exchange.IndicatorAlertKind]), je Art die
     * gespeicherte Ganzzahl; fehlt bzw. 0 = aus. Ab Werk alles aus.
     */
    val indicatorAlerts: Map<com.cryptochecker.app.domain.market.IndicatorAlertKind, Int> = emptyMap(),

    /** Gas-Alarm Ethereum: melden, wenn die normale Gebühr unter diesen Wert fällt — in Zehntel-gwei (0 = aus). */
    val gasAlertEthTenths: Int = 0,

    /** Gas-Alarm Bitcoin: melden, wenn die normale Gebühr unter diesen Wert (sat/vB) fällt (0 = aus). */
    val gasAlertBtc: Int = 0,

    /**
     * USDT-Depeg melden ([com.cryptochecker.app.domain.alarm.DepegAlarm]): ab Werk aus (zusätzliche
     * Meldungen nur, wenn eingeschaltet). Bewertungen rechnen mit dem gemessenen USDT/USD-Marktkurs (gemessen auch ohne
     * Alarm, sobald ein Portfolio da ist).
     */
    val depegAlarm: Boolean = false,

    /** Schwelle des Depeg-Alarms in Zehntel-Prozent (3 / 5 / 10 / 20), Standard 0.5 %. */
    val depegThresholdTenths: Int = com.cryptochecker.app.domain.alarm.DepegAlarm.DEFAULT_THRESHOLD_TENTHS,

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
     * «Alarm-Signal»: System (Alarm-Kanal), Ton und Vibration, nur Ton, nur Vibration
     * oder lautlos (nur Mitteilung). Je Signal ein eigener Mitteilungskanal.
     */
    val alarmSignal: com.cryptochecker.app.domain.alarm.AlarmSignal =
        com.cryptochecker.app.domain.alarm.AlarmSignal.DEFAULT,

    /** Der Gesten-Hinweis in der Merkliste wurde weggeklickt. */
    val gestureHintSeen: Boolean = false,

    /** Die Karte «Ersten Alarm setzen» über der Merkliste wurde geschlossen. */
    val firstAlarmCardDismissed: Boolean = false,

    /**
     * Verschiedene Tage, an denen die App offen war ([com.cryptochecker.app.domain.watch.UsageDays]): ab 3 zeigt die
     * Merkliste auch ohne Alarm Puls und Hinweise ([com.cryptochecker.app.domain.watch.WatchlistOnboarding]).
     * Nur auf diesem Gerät, nicht in der Sicherung.
     */
    val usageDays: Int = 0,

    /** Lokaler Epoch-Tag des letzten gezählten Öffnens ([usageDays]); null = noch nie. */
    val usageLastDay: Long? = null,

    /** Willkommensdialog bestätigt; ohne Wirkung (die App zeigt keine Begrüssung), nur gespeichert. */
    val aboutSeen: Boolean = false,

    /** Der Hinweis zur Akku-Optimierung wurde bereits gezeigt. */
    val batteryPromptSeen: Boolean = false,

    /** Gewählte Gruppe in der Merkliste. null = «Alle». */
    val watchlistGroup: String? = null,

    /** Sortieren nach Spalte in der Merkliste ([com.cryptochecker.app.domain.watch.ColumnSort.encode]); null = eigene Reihenfolge. */
    val watchlistColumnSort: String? = null,

    /**
     * Ungewöhnliche Aktivität (Bewegung, Volumen, Futures) als Benachrichtigung melden. Ab Werk aus;
     * das ⚡ in der Merkliste und die Karte «Hier passiert gerade etwas» bleiben.
     */
    val activityAlerts: Boolean = false,

    /** Empfindlichkeit von «Ungewöhnliche Aktivität» (Karte und Meldungen); Standard = Grundschwellen. */
    val activitySensitivity: com.cryptochecker.app.domain.activity.ActivitySensitivity =
        com.cryptochecker.app.domain.activity.ActivitySensitivity.NORMAL,

    /** Morgen-Meldung (08:00) an Tagen mit wichtigen US-Wirtschaftsdaten. Ab Werk aus. */
    val macroNotifications: Boolean = false,

    /** Optionaler Bereich «Portfolio» (eigener Tab vor den Einstellungen). Ab Werk aus. */
    val portfolioEnabled: Boolean = false,

    /**
     * Umrechnungswährung: Zielwährung der Umrechnungszeile im Portfolio und der
     * umgerechneten Kurse in der Merkliste (USD = keine Umrechnung im Portfolio).
     */
    val portfolioCurrency: String = defaultCurrency(),

    /** Kurse in der Merkliste zusätzlich in der Umrechnungswährung zeigen (« ≈ 61’234 CHF »). Ab Werk an. */
    val showConverted: Boolean = true,

    /** Kursfarben steigend/fallend: Grün/Rot oder Blau/Orange (Rot-Grün-Sehschwäche). */
    val priceColorScheme: PriceColorScheme = PriceColorScheme.DEFAULT,

    /** Mini-Chart (24-Stunden-Verlauf) in den Zeilen der Merkliste. */
    val watchlistSparkline: Boolean = false,

    /** Name unter dem Paar in der Merkliste («Bitcoin», «NVIDIA»). Ab Werk an. */
    val watchlistNames: Boolean = true,

    /**
     * «Zahl am App-Symbol»: Alarme und Marktmeldungen zählen am App-Symbol (iOS-Kennzeichen;
     * Android gibt je Mitteilung 1 mit, Zahl oder Punkt entscheidet der Startbildschirm). Ab Werk an.
     */
    val appIconBadge: Boolean = true,

    /**
     * Karte «Hier passiert gerade etwas» (ungewöhnliche Aktivität) über der Merkliste. Nur die
     * Anzeige: Meldungen dazu stellt man unter Markt-Meldungen ein, das ⚡ an den Paaren bleibt.
     */
    val watchlistActivityCard: Boolean = true,

    /**
     * Echte Coin-Logos (alle auf einmal von CoinGecko geladen, auf dem Gerät gespeichert) in
     * Merkliste, Aktionsblatt, Start-Auswahl und Markt-Karten. Ab Werk an.
     */
    val coinLogos: Boolean = true,

    /** Coin-Logos im Portfolio (Positionen, Coin-Details, Auswertungen) — ab Werk an. */
    val portfolioCoinLogos: Boolean = true,

    /** Coin-Logos in den Widgets «Merkliste» und «Einzelner Coin» — ab Werk aus. */
    val widgetCoinLogos: Boolean = false,

    /**
     * «Basis der %-Änderung»: rollende 24 Stunden (Standard), seit 00:00 UTC oder seit 00:00
     * Ortszeit — für Änderung, Puls, Aktionsblatt und Widgets; Alarme rechnen unabhängig davon.
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

    /** Nachtruhe: Alarme warten in diesem Zeitraum, übrige Meldungen kommen lautlos (siehe QuietHours). */
    val quietHoursEnabled: Boolean = false,

    /** Beginn der Nachtruhe in Minuten seit Mitternacht (Standard 23:00). */
    val quietHoursStart: Int = com.cryptochecker.app.domain.alarm.QuietHours.DEFAULT_START,

    /** Ende der Nachtruhe in Minuten seit Mitternacht (Standard 07:00). */
    val quietHoursEnd: Int = com.cryptochecker.app.domain.alarm.QuietHours.DEFAULT_END,

    /**
     * Portfolio-Sperre (Schlüssel «app_lock»): Portfolio-Tab, seine Unterseiten,
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
     * «Portfolio in Systemsicherung» (Schlüssel «portfolio_system_backup», iOS/Sicherung
     * «portfolioSystemBackup»): an = Transaktionen und Portfolio-Alarme kommen in die Android-
     * Systemsicherung (über eine Kopie, siehe PortfolioBackupMirror). Standard aus: Wer die Sicherung
     * des Geräts wiederherstellt, bekäme sonst die Trades ohne die Portfolio-Sperre. Umziehen geht mit
     * der eigenen (verschlüsselten) Sicherung. Auch in der Sicherung.
     */
    val portfolioSystemBackup: Boolean = false,

    /**
     * Die Bestätigung nach dem ersten Alarm wurde gezeigt (oder es gab schon Alarme).
     * Nur auf diesem Gerät, nicht in der Sicherung.
     */
    val firstAlarmShown: Boolean = false,

    /**
     * Hinweis «Wann Alarme geprüft werden» ([com.cryptochecker.app.domain.alarm.AlarmTimingHint]) wurde
     * einmal gezeigt. Reiner Merker der Oberfläche: nur auf diesem Gerät, nicht in der Sicherung.
     */
    val alarmTimingHintShown: Boolean = false,

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
     * Markt-Tab gesehen; ohne Wirkung (der Markt-Tab hat Register statt aufklappbarer Teile), nur
     * gespeichert. Nur auf diesem Gerät, nicht in der Sicherung.
     */
    val marketTabSeen: Boolean = false,

    /**
     * Markt-Tab: «Mehr Marktdaten» (Zeilen zu «Stimmung & Zyklus» und «Marktdaten») aufgeklappt
     * (Standard zu). Nur auf diesem Gerät, nicht in der Sicherung.
     */
    val marketMoreExpanded: Boolean = false,

    /** Zuletzt gewählter Zeitraum des Wertverlaufs. Nur auf diesem Gerät, nicht in der Sicherung. */
    val portfolioHistoryRange: com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange =
        com.cryptochecker.app.domain.portfolio.PortfolioHistoryRange.MONTH,

    /**
     * Darstellung des Wertverlaufs bei einer Umrechnungswährung («CHF | USDT | Vergleich»,
     * Standard in der Währung). Nur auf diesem Gerät, nicht in der Sicherung.
     */
    val portfolioHistoryView: com.cryptochecker.app.domain.portfolio.PortfolioHistoryView =
        com.cryptochecker.app.domain.portfolio.PortfolioHistoryView.CURRENCY,

    /**
     * Wertverlauf als Kerzen statt Linie (Kerzen | Linie wie im Aktionsblatt, Standard Linie).
     * Nur auf diesem Gerät, nicht in der Sicherung.
     */
    val portfolioHistoryCandles: Boolean = false,

    /**
     * Wertverlauf zeigt «Gewinn/Verlust» statt «Wert» (Chips über dem Chart, Standard Wert).
     * Nur auf diesem Gerät, nicht in der Sicherung.
     */
    val portfolioHistoryPnl: Boolean = false,

    /**
     * Einstandsmethode des Portfolios (FIFO | Ø): Einstand, Ø, «Seit Kauf», realisiert und Steuer-Export.
     * Standard FIFO. In der App-Sicherung («costMethod»; unbekannter Wert → FIFO).
     */
    val costMethod: com.cryptochecker.app.domain.portfolio.CostMethod =
        com.cryptochecker.app.domain.portfolio.CostMethod.DEFAULT,

    /**
     * Steuer-Vorlage der Arbeitsunterlage (Neutral | DE | AT | CH | UK | US | CA | AU | FR | JP), Standard Neutral. Die
     * Region des Geräts wird nur vorgeschlagen, nie still übernommen. In der App-Sicherung («taxTemplate»; unbekannter
     * Wert → Neutral).
     */
    val taxTemplate: com.cryptochecker.app.domain.portfolio.TaxTemplate =
        com.cryptochecker.app.domain.portfolio.TaxTemplate.DEFAULT,
    /**
     * Hinweis «Beta, vereinfachte Regeln …» zu den Länder-Vorlagen einmal mit OK bestätigt (Dialog beim ersten Wählen einer
     * Länder-Vorlage). Nur auf diesem Gerät, nicht in der Sicherung.
     */
    val taxTemplateConfirmed: Boolean = false,
) {
    /**
     * Live-Kurse per WebSocket, solange die Merkliste offen ist (Binance, Bybit, OKX, Coinbase,
     * Kraken; siehe `LivePriceStream`) — bei «Live, solange offen». Hintergrund und
     * Widgets fragen weiter per REST ab.
     */
    val liveWebSocket: Boolean get() = updateMode.liveWhileOpen

    /**
     * Werden Kurse von Paaren mit «Kurs vorlesen» angesagt? Nur mit Sprachausgabe an und «Nur Alarme»
     * aus. Sonst schaltet «Kurs vorlesen» beides passend um (WatchlistViewModel.setTtsEnabled).
     */
    val speaksPairPrices: Boolean get() = ttsEnabled && !ttsAlarmsOnly

    /** Wahlmöglichkeiten erbt der Companion ([AppSettingsChoices]). */
    companion object : AppSettingsChoices() {
        const val MIN_BACKGROUND_INTERVAL_MINUTES = 15

        /**
         * Obergrenzen (weit über den Wahlmöglichkeiten): Werte aus Sicherungen und gespeicherten Einstellungen
         * werden darauf gekürzt, wie iOS (dort liessen riesige Werte Zeitrechnungen überlaufen).
         */
        const val MAX_BACKGROUND_INTERVAL_MINUTES = 1_440
        const val MAX_ALARM_COOLDOWN_MINUTES = 1_440
    }
}
