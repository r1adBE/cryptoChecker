package com.cryptochecker.app.widget

/**
 * Schriftstufen der Widgets (sp) = die Stufen der App-Typografie (`ui/theme/Typography.kt`): Label 12, Body 14,
 * Title 16, Headline 22, Display kompakt 28. Widgets (RemoteViews) kennen kein `MaterialTheme`, darum als Zahlen;
 * Layouts in `res/layout/widget_*.xml` nehmen dieselben Werte (`WidgetTypeTest` prüft beides). Kleinere Ausweich-
 * grössen beim Verkleinern (11 sp, [WidgetTextFit.CHANGE_LINE_SIZES_SP], [WidgetQuoteText.MIN_QUOTE_SP]) bleiben
 * bewusst darunter: lieber etwas kleiner als ohne Betrag.
 */
internal object WidgetType {
    /** App `label` (labelMedium): Nebentexte, Prozent, Zeilen der Listen. */
    const val LABEL = 12f

    /** App `body` (bodyMedium): Titel der Widgets, Kurs in der Liste. */
    const val BODY = 14f

    /** App `title` (titleMedium): Schlagzeile des Puls-Widgets. */
    const val TITLE = 16f

    /** App `headline` (titleLarge): Grundgrösse grosser Beträge (wachsen per autoSize bis [DISPLAY_COMPACT]). */
    const val HEADLINE = 22f

    /** App `displayCompact` (headlineMedium): grösster Betrag (Kurs im Einzel-Widget, Portfolio-Summe). */
    const val DISPLAY_COMPACT = 28f

    /** Alle Stufen: erlaubte `android:textSize`-Werte der Widget-Layouts. */
    val ALL = listOf(LABEL, BODY, TITLE, HEADLINE, DISPLAY_COMPACT)
}
