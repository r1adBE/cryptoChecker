package com.cryptochecker.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.settings.AccentColor
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.widget.ListWidgetRowData
import com.cryptochecker.app.widget.ListWidgetRows
import com.cryptochecker.app.widget.WidgetColors
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Widgets auf dem Gerät: Was der Launcher zeigt, ist ein [RemoteViews], den das System aufbläht — erlaubt sind
 * dort nur bestimmte View-Klassen und Methoden. Dieser Test bläht jedes Widget-Layout und echte Zeilen des Listen-Widgets
 * wie der Launcher auf ([RemoteViews.apply]) und prüft den Inhalt: Paar, Kurs, Börse, deaktiviert. Ergänzt die Regeln auf
 * der JVM (Texte, Farben, Platz) um das, was nur ein Gerät zeigt (Aufblähen, unerlaubte Aufrufe, Absturz im Launcher).
 */
@HiltAndroidTest
class WidgetRenderDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @Inject lateinit var rows: ListWidgetRows

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun inject() = hiltRule.inject()

    /** Jedes Widget-Layout (`widget_*`) lässt sich als RemoteViews aufblähen — wie beim Ablegen auf dem Startbildschirm. */
    @Test
    fun everyWidgetLayoutInflatesAsRemoteViews() {
        val layouts = R.layout::class.java.fields.filter { it.name.startsWith("widget_") }.associate { it.name to it.getInt(null) }
        assertTrue("nur ${layouts.size} Widget-Layouts gefunden", layouts.size >= 8)
        for ((name, id) in layouts) {
            val view = apply(RemoteViews(context.packageName, id))
            assertTrue("$name: leer aufgebläht", view is ViewGroup || view.visibility != View.GONE)
        }
    }

    /** Zeilen des Listen-Widgets: Paar, Kurs und Börse stehen da, in hell und dunkel für jede Akzentfarbe. */
    @Test
    fun listRowsShowPairPriceAndExchange() {
        val watches = listOf(
            watch(1, "BTC", "USDT", 65_000.0),
            watch(2, "ETH", "USDT", 3_200.5),
            watch(3, "SOL", "USDT", 150.0).copy(disabled = true),
        )
        for (accent in AccentColor.entries) {
            for (dark in listOf(false, true)) {
                val data = ListWidgetRowData(
                    watches = watches,
                    background = WidgetColors.of(accent, dark),
                    changeView = ChangeView(),
                    outdatedAfter = OUTDATED_AFTER,
                )
                val first = apply(rows.row(data, 0))
                assertEquals("BTC/USDT", text(first, R.id.item_pair))
                assertTrue(text(first, R.id.item_price), text(first, R.id.item_price).contains("65"))
                assertEquals("Binance", text(first, R.id.item_exchange))
                // Deaktiviert: «Binance · deaktiviert» (Text der App)
                val off = apply(rows.row(data, 2))
                assertEquals(
                    context.getString(R.string.watchlist_row_disabled, "Binance").filter { it.isLetterOrDigit() },
                    text(off, R.id.item_exchange).filter { it.isLetterOrDigit() },
                )
                // Ausserhalb der Liste: leere Zeile statt Absturz
                apply(rows.row(data, watches.size))
            }
        }
    }

    private fun watch(id: Long, base: String, quote: String, price: Double) = WatchEntity(
        id = id,
        exchangeKey = "binance",
        exchangeName = "Binance",
        baseAsset = base,
        quoteAsset = quote,
        lastPrice = price,
        lastUpdate = System.currentTimeMillis(),
    )

    /** Wie der Launcher: auf dem Hauptthread in einen Elternteil aufblähen. */
    private fun apply(views: RemoteViews): View {
        var view: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = views.apply(context, FrameLayout(context))
        }
        return view!!
    }

    private fun text(root: View, id: Int): String = root.findViewById<TextView>(id).text.toString()

    private companion object {
        const val OUTDATED_AFTER = 30 * 60_000L
    }
}
