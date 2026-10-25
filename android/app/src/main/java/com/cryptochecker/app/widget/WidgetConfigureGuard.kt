package com.cryptochecker.app.widget

/**
 * Die Einrichten-Activities der Widgets sind exportiert (Startbildschirme öffnen sie direkt, auch für «Widget neu
 * einrichten» ab Android 12) — jede andere App kann sie also mit einer beliebigen Widget-Id starten. Gearbeitet wird
 * nur mit einer gültigen Id eines EIGENEN Widgets der passenden Art: Der Systemdienst nennt zur Id den Anbieter
 * (`AppWidgetManager.getAppWidgetInfo(id).provider`); er muss aus diesem Paket stammen und genau der Anbieter sein, den
 * die Activity einrichtet. Sonst schliesst sie sich ohne Ergebnis. Reines Kotlin.
 */
object WidgetConfigureGuard {

    /**
     * Darf die Einrichten-Activity mit [widgetId] arbeiten? [invalidId] = `AppWidgetManager.INVALID_APPWIDGET_ID`,
     * [providerPackage]/[providerClass] = Anbieter laut Systemdienst (null = unbekannt bzw. kein Zugriff),
     * [ownPackage] = Paket der App, [expectedProvider] = voller Klassenname des Anbieters dieser Activity.
     */
    fun accepts(
        widgetId: Int,
        invalidId: Int,
        providerPackage: String?,
        providerClass: String?,
        ownPackage: String,
        expectedProvider: String,
    ): Boolean =
        widgetId != invalidId && providerPackage != null && providerPackage == ownPackage &&
            providerClass == expectedProvider
}
