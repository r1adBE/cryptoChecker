package com.cryptochecker.wear

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.cryptochecker.marketdata.watch.WatchPair
import com.cryptochecker.marketdata.watch.WatchSettings
import com.google.common.util.concurrent.ListenableFuture

/**
 * Kachel (Wear OS, Inhaber 16.11.2026; Gegenstück zum Smart Stack der Apple Watch): die ersten [ROWS] Favoriten der
 * Uhr-Liste mit Kurs (knapp) und Änderung, aus dem Zwischenspeicher der Uhr ([WatchStore]) – die Kachel lädt selbst
 * nichts (Akku); neu gezeichnet wird, wenn neue Kurse ankommen ([requestTileUpdate]) und spätestens alle
 * [FRESHNESS_MS]. Ohne Favoriten ein Hinweis wie in der Komplikation; Tippen öffnet die Uhr-App.
 */
class PriceTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(tile(this))
            "tile"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
            "resources"
        }

    companion object {
        const val ROWS = 3
        private const val RESOURCES_VERSION = "1"
        private const val FRESHNESS_MS = 15 * 60_000L
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val GREY = 0xFFB0B4BA.toInt()

        /** Zeilen der Kachel: Paar, Kurs knapp und Änderung; ohne Kurs «—» (wie die Zeile der Uhr-App). */
        fun rows(shown: List<String>, quotes: Map<String, WatchCached>): List<String> =
            shown.take(ROWS).map { key ->
                val label = WatchPair.label(key)
                val q = quotes[key]?.quote
                if (q == null) "$label  —" else "$label  ${WatchFormat.compactPrice(q.price)}  ${WatchFormat.change(q.change24h)}"
            }

        private fun tile(context: Context): TileBuilders.Tile {
            val store = WatchStore(context)
            val rows = rows(store.settings.shown, store.quotes())
            val column = LayoutElementBuilders.Column.Builder()
                .addContent(text(context.getString(R.string.watch_app_name), 13f, GREY))
            if (rows.isEmpty()) {
                val phone = store.settings.mode == WatchSettings.Mode.PHONE
                column.addContent(text(context.getString(if (phone) R.string.watch_phone_empty else R.string.watch_choose), 15f, WHITE))
            } else {
                rows.forEach { column.addContent(text(it, 15f, WHITE)) }
            }
            val open = ModifiersBuilders.Clickable.Builder()
                .setId("open")
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(context.packageName)
                                .setClassName(WatchActivity::class.java.name)
                                .build()
                        )
                        .build()
                )
                .build()
            val root = LayoutElementBuilders.Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
                .addContent(column.build())
                .build()
            return TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(FRESHNESS_MS)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build()
        }

        private fun text(value: String, size: Float, color: Int): LayoutElementBuilders.LayoutElement =
            LayoutElementBuilders.Text.Builder()
                .setText(value)
                .setMaxLines(1)
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setPadding(ModifiersBuilders.Padding.Builder().setTop(dp(2f)).setBottom(dp(2f)).build())
                        .build()
                )
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build()
                )
                .build()
    }
}

/** Kachel neu zeichnen lassen (neue Kurse oder Wahl); Fehler ohne Folgen. */
fun requestTileUpdate(context: Context) {
    try {
        TileService.getUpdater(context).requestUpdate(PriceTileService::class.java)
    } catch (e: Exception) {
        android.util.Log.d("PriceTile", "Kachel nicht aktualisiert", e)
    }
}
