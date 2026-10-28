package com.cryptochecker.app.widget

import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.ui.features.watchlist.assetKind
import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.TypedValue
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.cryptochecker.app.R
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.shownChange
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.PriceFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Was die Zeilen eines Listen-Widgets brauchen: Paare der Gruppe, Farben, %-Basis, «veraltet»-Grenze. */
internal class ListWidgetRowData(
    val watches: List<WatchEntity>,
    val background: WidgetColors,
    /** %-Basis und ob die gespeicherten Werte noch passen (sonst «—»). */
    val changeView: ChangeView,
    /** Ab diesem Alter «veraltet» ([WidgetOutdated]). */
    val outdatedAfter: Long,
    /** Coin-Logo bzw. Initialen-Kreis je Basis-Symbol; null = Schalter «Coin-Logos in Widgets» aus. */
    val logos: Map<String, android.graphics.Bitmap>? = null,
    /** Breite des Widgets (dp, 0 = unbekannt): Kurs bei sehr grosser Schrift kleiner statt abgeschnitten. */
    val widthDp: Int = 0,
)

/**
 * Zeilen des Listen-Widgets «Merkliste». Ab Android 12 legt [ListWidgetRenderer] sie direkt in
 * das Widget (gleicher Schritt wie der Kopf); darunter liefert sie [PriceWidgetService].
 */
@Singleton
class ListWidgetRows @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val watchRepository: WatchRepository,
    private val settingsRepository: SettingsRepository,
    private val widgetPrefs: WidgetPrefs,
    private val toolkit: WidgetToolkit,
    private val coinLogos: WidgetCoinLogos,
    private val coinLogoRepository: CoinLogoRepository,
) {
    /** Stand je Widget: [ListWidgetRenderer] zählt hoch, [PriceWidgetService] lädt bei altem Stand neu. */
    internal val versions = ListWidgetDataVersion()

    /** Alle Paare (eine Abfrage); ein Zeichnen liest sie einmal für alle Widgets ([WidgetOnceRead]). */
    internal suspend fun allWatches(): List<WatchEntity> = watchRepository.getWatches()

    /** Lädt die Daten für das Widget [appWidgetId]; [all] = schon gelesene Paare, null = hier lesen. */
    internal suspend fun load(appWidgetId: Int, all: List<WatchEntity>? = null): ListWidgetRowData {
        val settings = settingsRepository.current()
        val background = WidgetColors.of(
            settings.accentColor,
            widgetPrefs.isDark(appWidgetId),
            settings.priceColorScheme,
            HighContrast.isEffective(context, settings.highContrast),
            settings.priceColorsInverted,
        )
        val watches = groupWatches(all ?: allWatches(), appWidgetId).second
        return ListWidgetRowData(
            watches = watches,
            background = background,
            changeView = toolkit.changeView(settings.changeBasis),
            outdatedAfter = WidgetOutdated.afterMillis(settings),
            logos = if (settings.widgetCoinLogos) widgetLogos(watches, background) else null,
            widthDp = toolkit.sizeDp(AppWidgetManager.getInstance(context), appWidgetId).first,
        )
    }

    /** Anzahl Zeilen des Widgets [appWidgetId] aus den Paaren [all] (Leer-Hinweis, [ListWidgetRenderer]). */
    internal fun count(appWidgetId: Int, all: List<WatchEntity>): Int = groupWatches(all, appWidgetId).second.size

    /**
     * Wirksame Gruppe des Widgets [appWidgetId] für den Titel im Kopf: null = «Alle» — auch wenn die
     * gewählte Gruppe keine Paare (mehr) in [all] hat und das Widget deshalb alle zeigt.
     */
    internal fun group(appWidgetId: Int, all: List<WatchEntity>): String? = groupWatches(all, appWidgetId).first

    /**
     * Gruppe des Widgets und ihre Paare; gibt es sie nicht mehr (gelöscht) oder hat sie keine Paare,
     * wieder alle Paare (Gruppe dann null).
     */
    private fun groupWatches(all: List<WatchEntity>, appWidgetId: Int): Pair<String?, List<WatchEntity>> {
        // «Alpha»: Art je Paar aus den gespeicherten Listen (wie die Merkliste der App)
        val tradFiPairs = coinLogoRepository.tradFiPairs.value
        val alphaSymbols = coinLogoRepository.alphaSymbols.value
        return WidgetGroupSelection.pick(
            selection = widgetPrefs.getGroup(appWidgetId),
            items = all,
            groupName = { it.groupName },
            favorite = { it.favorite },
            alpha = { it.assetKind(tradFiPairs, alphaSymbols) == AssetKind.ALPHA },
        )
    }

    /**
     * Logos je Basis-Symbol (TradFi-Paare eigener Schlüssel, [WidgetCoinLogos.logoKey]); DEX-Pools ([CoinLogos.allowedFor]) nur mit Initialen (Schlüssel mit
     * Börse, damit ein «BTC»-Pool nie das Bitcoin-Logo bekommt).
     */
    private suspend fun widgetLogos(watches: List<WatchEntity>, colors: WidgetColors): Map<String, android.graphics.Bitmap> {
        val (withLogo, withoutLogo) = watches.partition { CoinLogos.allowedFor(it.exchangeKey) }
        return coinLogos.bitmaps(withLogo.map { coinLogos.logoKey(it) }, colors) +
            withoutLogo.associate { logoKey(it) to coinLogos.initials(it.baseAsset, colors) }
    }

    private fun logoKey(watch: WatchEntity): String =
        if (CoinLogos.allowedFor(watch.exchangeKey)) coinLogos.logoKey(watch) else "${watch.exchangeKey}|${watch.baseAsset}"

    /**
     * Kursgrösse der Zeile: [PRICE_SP]; bei zu wenig Breite kleiner, dann weicht der Name
     * ([WidgetTextFit.listPriceFit]).
     */
    private fun listPriceSp(data: ListWidgetRowData, priceText: String, name: String, withLogo: Boolean): Float {
        if (data.widthDp <= 0) return PRICE_SP
        val available = data.widthDp - WidgetTextFit.LIST_ROW_FIXED_DP - WidgetTextFit.SAFETY_DP -
            if (withLogo) WidgetTextFit.LIST_ROW_LOGO_DP else 0f
        val price = toolkit.textWidthDp(priceText, PRICE_SP, bold = true, tabular = true)
        val nameWidth = toolkit.textWidthDp(name, PRICE_SP, bold = true, tabular = true)
        val fontScale = context.resources.configuration.fontScale
        return WidgetTextFit.listPriceFit(PRICE_SP, fontScale, price, nameWidth, available).sp
    }

    /** Zeile [position]; ausserhalb der Liste eine leere Zeile. */
    internal fun row(data: ListWidgetRowData, position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_list_item)
        val watch = data.watches.getOrNull(position) ?: return views
        val background = data.background

        val logo = data.logos?.get(logoKey(watch))
        if (logo != null) {
            views.setImageViewBitmap(R.id.item_logo, logo)
            views.setViewVisibility(R.id.item_logo, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.item_logo, View.GONE)
        }

        // Deaktiviertes Paar: ganz grau mit dem letzten Kurs, «Binance · deaktiviert» (wird nie abgefragt)
        val off = watch.disabled
        val mainText = if (off) background.secondaryTextColor else background.textColor
        views.setTextViewText(R.id.item_pair, watch.displayName)
        views.setTextColor(R.id.item_pair, mainText)
        // Logo gedämpft wie die Zeile in der App
        views.setInt(R.id.item_logo, "setImageAlpha", if (off) DISABLED_LOGO_ALPHA else 255)

        // Eigener Stand dieses Paares veraltet (z. B. Börse antwortet nicht): «Binance · veraltet»
        val now = System.currentTimeMillis()
        // Regel der App (Abruf zu alt oder letzter Handel > 2 h, [WidgetFreshness.outdatedTime])
        val outdatedAt = if (off) null else WidgetFreshness.outdatedTime(watch, now, data.outdatedAfter)
        val outdated = outdatedAt != null
        // Börse ohne eigene Zeit, Kurs steht lange: «Kraken · Kurs unverändert seit 14:05» (wie die Zeile der App)
        val unchanged = if (off || outdated) null
        else WidgetFreshness.unchangedSince(watch, now, data.outdatedAfter)?.let { WidgetOutdated.unchanged(context, it) }
        views.setTextViewText(
            R.id.item_exchange,
            when {
                off -> context.getString(R.string.watchlist_row_disabled, BidiText.isolate(watch.exchangeName))
                outdated -> context.getString(R.string.watchlist_row_outdated, BidiText.isolate(watch.exchangeName))
                unchanged != null -> "${BidiText.isolate(watch.exchangeName)} · $unchanged"
                else -> watch.exchangeName
            }
        )
        views.setTextColor(R.id.item_exchange, background.secondaryTextColor)

        // Währung klein und grau wie der Börsenname darunter (14 sp, widget_list_item.xml); passt der Kurs bei sehr
        // grosser Schrift nicht in die Zeile, wird er kleiner, dann weicht der Paarname — der Kurs ist immer
        // vollständig ([WidgetTextFit.listPriceFit])
        val priceText = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset)
        val priceSp = listPriceSp(data, priceText, watch.displayName, logo != null)
        if (priceSp < PRICE_SP) views.setTextViewTextSize(R.id.item_price, TypedValue.COMPLEX_UNIT_SP, priceSp)
        views.setAmountText(
            R.id.item_price,
            toolkit.quoteText(priceText, watch.quoteAsset, priceSp, background.secondaryTextColor)
        )
        views.setTextColor(R.id.item_price, mainText)

        // Veränderung gemäss %-Basis — derselbe Wert wie die Pille in der Merkliste
        // (nicht mehr gehandelt oder veraltete Basis: «—»)
        val change = watch.shownChange(data.changeView)
        val changeText = PriceFormat.changePercent(change)

        // Immer ein Wert, damit alle Zeilen gleich aussehen: Pfeil und Vorzeichen
        // zeigen die Richtung (Pfeil folgt dem Vorzeichen, nie dem Farbtausch), die Farbe
        // zusätzlich; ohne Bewegung grau «0.00%» ohne Pfeil, ohne 24-h-Bezug grau «—».
        if (change == null) {
            views.setAmountText(R.id.item_change, NO_CHANGE)
            views.setTextColor(R.id.item_change, background.neutralColor)
        } else if (changeText == null) {
            views.setAmountText(R.id.item_change, ZERO_CHANGE)
            views.setTextColor(R.id.item_change, background.neutralColor)
        } else {
            views.setAmountText(R.id.item_change, "${PriceFormat.changeArrow(change)} $changeText")
            // Deaktiviert: letzter Wert grau, ohne Kursfarbe
            views.setTextColor(
                R.id.item_change,
                when {
                    off -> background.neutralColor
                    change >= 0 -> background.upColor
                    else -> background.downColor
                }
            )
        }

        // Screenreader: die Zeile als ein Satz, Änderung mit Richtungswort statt Vorzeichen
        views.setContentDescription(
            R.id.item_root,
            A11yText.row(
                context = context,
                pair = watch.displayName,
                exchange = watch.exchangeName,
                price = PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
                quote = watch.quoteAsset,
                change24h = change,
                basis = data.changeView.basis,
                // Frische direkt nach der Veränderung (wie die Merkliste); Uhrzeit statt «vor …»,
                // weil das Widget nicht jede Minute neu gezeichnet wird
                state = listOf(
                    when {
                        off -> context.getString(R.string.watch_disabled)
                        outdatedAt != null -> WidgetOutdated.spoken(context, outdatedAt)
                        else -> unchanged
                    }
                ),
            )
        )

        // Trennlinie unter jeder Zeile ausser der letzten (neutrales Grau, geringe Deckkraft)
        views.setInt(R.id.item_divider, "setBackgroundColor", background.dividerColor)
        views.setViewVisibility(
            R.id.item_divider,
            if (position == data.watches.size - 1) View.GONE else View.VISIBLE
        )

        // Tippen auf eine Zeile öffnet die App bei diesem Paar.
        views.setOnClickFillInIntent(
            R.id.item_root,
            Intent().putExtra(MainActivity.EXTRA_WATCH_ID, watch.id)
        )
        return views
    }
}

/** Keine Bewegung über 24 Stunden: grau, im selben Format wie die übrigen Werte. */
private val ZERO_CHANGE: String get() = PriceFormat.zeroPercent()

/** Deckkraft des Coin-Logos bei deaktivierten Paaren (0–255, etwa wie die Zeile in der App). */
private const val DISABLED_LOGO_ALPHA = 115

/** Ohne 24-h-Bezug: Strich statt Zahl (nie die Veränderung seit der letzten Abfrage). */
private const val NO_CHANGE = "—"

/** Kursgrösse der Merklisten-Zeile (widget_list_item.xml `item_price`). */
private const val PRICE_SP = 14f
