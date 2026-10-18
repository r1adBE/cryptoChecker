package com.cryptochecker.app.domain.refresh

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Herkunft des laufenden Durchlaufs ([CheckTrigger]) im Coroutine-Kontext: [BackgroundCheck][com.cryptochecker.app.work.BackgroundCheck]
 * setzt sie für WorkManager, pünktlichen Wecker und Widget-Knopf; ohne Eintrag gilt [CheckTrigger.FOREGROUND] (Ziehen,
 * Menü, Live-Kurse, einzelnes Paar). Nur fürs Alarm-Protokoll («über Widget») — keine Entscheidung hängt daran.
 * Über den Kontext statt als Parameter, weil der Weg bis zur Auswertung ([RefreshResultProcessor]) lang ist.
 */
class CheckOrigin(val trigger: CheckTrigger) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CheckOrigin>
}
