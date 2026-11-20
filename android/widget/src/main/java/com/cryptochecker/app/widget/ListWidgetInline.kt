package com.cryptochecker.app.widget

import android.os.Build
import android.os.Parcel
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/** Zeilen des Listen-Widgets direkt im Widget (RemoteCollectionItems) — Teil von [ListWidgetRenderer]. */
internal object ListWidgetInline {

    /** Bis so viele Paare direkt im Widget (Obergrenze vor dem Messen); darüber über den Dienst. */
    const val MAX_INLINE_ROWS = 500

    /**
     * Zeilen samt Logos höchstens so gross (Bytes) direkt im Widget — ein Binder-Aufruf fasst rund 1 MB, geteilt mit
     * dem übrigen Widget; darüber über den Dienst.
     */
    const val MAX_INLINE_BYTES = 400_000

    /** Zuletzt gemessene Bytes je Zeile je Widget: offensichtlich zu lange Listen gar nicht erst bauen. */
    private val bytesPerRow = ConcurrentHashMap<Int, Int>()

    /**
     * Zeilen für [appWidgetId] direkt im Widget (ab Android 12). Null unter Android 12, bei mehr als
     * [MAX_INLINE_ROWS] Paaren bzw. [MAX_INLINE_BYTES] (alles geht in einem Binder-Aufruf mit) oder bei einem Fehler —
     * dann liefert sie [PriceWidgetService].
     */
    @RequiresApi(Build.VERSION_CODES.S)
    suspend fun rows(rows: ListWidgetRows, appWidgetId: Int, data: ListWidgetRowData): RemoteViews.RemoteCollectionItems? {
        return runCatchingCancellable {
            val count = data.watches.size
            if (count > MAX_INLINE_ROWS) return@runCatchingCancellable null
            // Zuletzt gemessene Grösse je Zeile: offensichtlich zu gross, dann gar nicht erst bauen (lange Listen)
            val perRow = bytesPerRow[appWidgetId]
            if (perRow != null && perRow.toLong() * count > MAX_INLINE_BYTES * 11L / 10L) return@runCatchingCancellable null
            val builder = RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true)
                .setViewTypeCount(1)
            data.watches.forEachIndexed { index, watch -> builder.addItem(watch.id, rows.row(data, index)) }
            val items = builder.build()
            // Grösse des Binder-Aufrufs gemessen statt geschätzt (Zeilen, Logos — gleiche Coins teilen ein Bild): nur zu
            // gross geht über den Dienst, der beim Neuzeichnen die Zeilen kurz leert
            val bytes = Parcel.obtain().let { parcel ->
                try {
                    items.writeToParcel(parcel, 0)
                    parcel.dataSize()
                } finally {
                    parcel.recycle()
                }
            }
            if (count > 0) bytesPerRow[appWidgetId] = bytes / count
            items.takeIf { bytes <= MAX_INLINE_BYTES }
        }.onFailure {
            Timber.w(it, "Widget-Zeilen %d: weiter über den Dienst", appWidgetId)
        }.getOrNull()
    }
}
