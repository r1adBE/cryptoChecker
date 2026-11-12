package com.cryptochecker.app.domain.refresh

// Zusammengehörige Mitspieler von PriceRefresher, je als ein Konstruktor-Parameter (Obergrenze der Abhängigkeiten je
// Klasse, code_metrics.py). Reine Bündel ohne eigenes Verhalten: PriceRefresher reicht die Teile unter denselben Namen
// weiter wie zuvor.

/** Was ein Durchlauf liest und schreibt: Paare und Alarme, Einstellungen, Kennzahlen und Pausen, Netz-Zustand. */
class RefresherState(
    val watchRepository: RefresherStore,
    val settingsRepository: RefreshSettings,
    val refreshStats: RefresherStats,
    /** Ohne Netz keine Aktualisierung (keine Fehlerzustände, siehe [OfflineGate]). */
    val connectivity: RefresherOnline,
)

/** Zeichnen nach dem Durchlauf: Merklisten-Widgets, alle Widgets unter der Sperre, Widget-Charts danach. */
class RefresherDrawing(
    val widgetUpdater: RefresherWatchWidgets,
    /** Widgets zeichnen (unter der Sperre ohne Kerzen-Abruf). */
    val liveWidgetGate: RefreshWidgets,
    /** Widget-Charts ausserhalb der Sperre nachladen. */
    val chartRefresher: RefresherCharts,
)

/** Bezüge aus Kerzen. */
class RefresherCandles(
    /** 24-h-Bezug aus Kerzen — nur Ausweich-Weg, wenn der Ticker keinen 24-h-Wert liefert. */
    val sparklineRepository: RefresherSparklines,
    /** 24-h- und Tages-Bezüge aus Kerzen. */
    val dayReferences: RefresherDayReferences,
)
