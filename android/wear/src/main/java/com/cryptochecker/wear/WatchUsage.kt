package com.cryptochecker.wear

import android.content.Context
import com.cryptochecker.marketdata.usage.UsageLog
import com.cryptochecker.marketdata.usage.UsageMeter
import com.cryptochecker.marketdata.usage.UsageReport
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import java.io.File

/**
 * «Akku & Aktivität» auf der Uhr: eigene Zähler ([UsageMeter.shared], `noBackupFilesDir/usage_log.txt`, nicht gesichert)
 * — Abrufe der Uhr ([WatchPriceSource]) und Komplikationen, die selbst abrufen. Die Summe der letzten 24 h geht als
 * kleiner Eintrag unter [PATH] in die Wear-Daten-Schicht ans Handy ([publish], nur Zahlen und Zeit, nie dringend,
 * höchstens alle [PUBLISH_GAP_MILLIS]); Gegenstück `PhoneWatchUsage` in der Handy-App. Wie watchOS `WatchLink.publish`.
 */
object WatchUsage {
    const val PATH = "/cc/usage"
    private const val KEY = "report"
    private const val TIMEOUT_S = 10L

    /** Höchstens so oft ans Handy: die Seite auf dem Handy nennt den Stand («Stand …»). */
    const val PUBLISH_GAP_MILLIS = 30 * 60_000L

    @Volatile
    private var lastPublished = 0L

    /** Datei der Zähler festlegen (jeder Einstieg: App, Komplikation, Daten-Dienst). */
    fun attach(context: Context) {
        UsageMeter.shared.attach(File(context.applicationContext.noBackupFilesDir, UsageMeter.FILE_NAME))
    }

    /** Bitte des Handys «Zähler jetzt schicken» (Nachricht; gleich in `PhoneWatchUsage.REQUEST_PATH`). */
    const val REQUEST_PATH = "/cc/usage/request"

    /** Auf Bitte des Handys höchstens so oft: die Seite «Akku & Aktivität» fragt bei jedem Öffnen. */
    const val REQUEST_GAP_MILLIS = 60_000L

    /**
     * Zähler schreiben und die Summe ans Handy geben, höchstens alle [PUBLISH_GAP_MILLIS]; auf Bitte des Handys
     * ([requested]) höchstens alle [REQUEST_GAP_MILLIS] und dringend (sonst darf das System bis zu 30 Min. bündeln).
     * Läuft im Prozess weiter.
     */
    fun publish(context: Context, requested: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastPublished < if (requested) REQUEST_GAP_MILLIS else PUBLISH_GAP_MILLIS) return
        lastPublished = now
        val app = context.applicationContext
        attach(app)
        WatchDataLayer.processScope.launch {
            UsageMeter.shared.flush()
            val text = UsageLog.encodeReport(UsageReport(now, UsageMeter.shared.totals()))
            WatchTaskAwait.catching {
                val request = PutDataMapRequest.create(PATH).apply { dataMap.putString(KEY, text) }.asPutDataRequest()
                if (requested) request.setUrgent()
                WatchTaskAwait.await(Wearable.getDataClient(app).putDataItem(request), TIMEOUT_S)
            }
        }
    }
}
