package com.cryptochecker.app.wearable

import android.content.Context
import androidx.core.net.toUri
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.marketdata.usage.UsageLog
import com.cryptochecker.marketdata.usage.UsageReport
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Akku & Aktivität» › Uhr: die Uhr legt ihre eigenen Zähler der letzten 24 h unter [PATH] in die Wear-Daten-Schicht
 * ([UsageLog.encodeReport], nur Zahlen und Zeit; Gegenstück `WatchDataLayer.publishUsage`). Das Handy merkt sich den
 * neuesten Stand in [FILE_NAME] (`noBackupFilesDir`, nicht gesichert) — aus [PhoneWatchListener] und beim Öffnen der
 * Seite ([pull]). Wie iOS `PhoneWatchLink` (Schlüssel `usage` im Anwendungskontext der Uhr).
 */
@Singleton
class PhoneWatchUsage @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val file get() = File(context.noBackupFilesDir, FILE_NAME)
    private val lock = Any()

    /** Zuletzt gemerkter Stand der Uhr; null = noch keiner (dann «Keine Uhr verbunden»). */
    fun load(): UsageReport? = synchronized(lock) {
        runCatching { if (file.exists()) UsageLog.decodeReport(file.readText()) else null }.getOrNull()
    }

    /** Stand der Uhr übernehmen, wenn lesbar und nicht älter als der gemerkte. */
    fun take(text: String?) {
        val report = UsageLog.decodeReport(text) ?: return
        synchronized(lock) {
            val known = runCatching { if (file.exists()) UsageLog.decodeReport(file.readText()) else null }.getOrNull()
            if (known != null && known.at >= report.at) return
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(UsageLog.encodeReport(report))
                if (!tmp.renameTo(file)) {
                    file.writeText(UsageLog.encodeReport(report))
                    tmp.delete()
                }
            }
        }
    }

    /** Neuesten Stand aus der Daten-Schicht holen (alle Uhren, der jüngste zählt); ohne Play-Dienste der gemerkte. */
    suspend fun pull(): UsageReport? = withContext(ioDispatcher) {
        runCatchingCancellable {
            val buffer = Tasks.await(Wearable.getDataClient(context).getDataItems("wear://*$PATH".toUri()),
                TIMEOUT_S, TimeUnit.SECONDS)
            val texts = try {
                buffer.map { DataMapItem.fromDataItem(it).dataMap.getString(KEY) }
            } finally {
                buffer.release()
            }
            texts.mapNotNull { UsageLog.decodeReport(it) }.maxByOrNull { it.at }?.let { take(UsageLog.encodeReport(it)) }
        }
        load()
    }

    companion object {
        const val PATH = "/cc/usage"
        const val KEY = "report"
        const val FILE_NAME = "watch_usage.txt"
        private const val TIMEOUT_S = 10L

        /** Stand der Uhr aus [events] — sofort gelesen, solange der Puffer gültig ist; null = keiner darunter. */
        fun changed(events: DataEventBuffer): String? = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PATH }
            .mapNotNull { DataMapItem.fromDataItem(it.dataItem).dataMap.getString(KEY) }
            .lastOrNull()
    }
}
