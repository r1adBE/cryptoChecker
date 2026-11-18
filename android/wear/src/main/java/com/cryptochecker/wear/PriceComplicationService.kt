package com.cryptochecker.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.cryptochecker.marketdata.usage.UsageMeter
import com.cryptochecker.marketdata.watch.WatchFreshness
import com.cryptochecker.marketdata.watch.WatchPair
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import com.cryptochecker.marketdata.watch.WatchSettings
import kotlinx.coroutines.CancellationException

/**
 * Komplikation: der erste Favorit — kurz «BTC / 84’450» (Kurs knapp, [WatchFormat.compactPrice]; die Änderung nennt die
 * Beschreibung für den Screenreader), lang «BTC • 84’450.00 USDT  +1.2%» mit der Gegenwährung des Paars
 * ([WatchFormat.priceLine]). Das Zifferblatt fragt etwa alle
 * 15 min (Manifest, fest); geladen wird nur, was nach [WatchPhoneQuotes.selfFetch] fällig ist: nichts, solange das
 * Handy frische Kurse liefert, sonst ab dem Intervall des Handys weniger 5 Min. ([WatchInterval]); ohne Netz oder ohne
 * neuen Kurs für diesen Coin der letzte. Lang mit der Börse, die den Kurs geliefert hat («… +1.2%  ·  Binance Futures»,
 * wie die Zeile der Uhr-App), veraltet ([WatchFreshness], 3 × Intervall des Handys ab dem Stand seines Abrufs) statt
 * ihrer mit dem Alter «… +1.2%  ·  vor 47 Min.», kurz mit dem Alter im Titel «BTC 47m» (vom Zifferblatt fortgezählt, [PriceComplicationData]); die
 * Beschreibung für den Screenreader nennt Alter und Börse immer. Farben bestimmt das Zifferblatt (kein Abblassen möglich). Tippen
 * öffnet die Uhr-App. Ohne gewählte Coins statt «--» ein Hinweis mit Tipp-Ziel ([PriceComplicationData.empty]): lang «Coins wählen» bzw. «Keine
 * Favoriten auf dem Handy», kurz «Kurs / +» — wie watchOS, das dann die Uhr-App öffnet.
 */
class PriceComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        PriceComplicationData.data(this, type, "BTC", WatchPair.USDT, 84_450.0, 1.2, "Binance", null, null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val store = WatchStore(this)
        val settings = store.settings
        val favorites = settings.shown
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WatchActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val coin = when (val first = ComplicationPlan.first(favorites, settings.mode)) {
            is ComplicationPlan.First.Empty -> return PriceComplicationData.empty(this, request.complicationType, first.phoneMode, tap)
            is ComplicationPlan.First.Coin -> first.key
        }
        var cached = store.quotes()[coin]
        val usePhone = settings.source == WatchSettings.Source.PHONE && WatchPhoneQuotes.usePhone(
            settings.source,
            WatchDataLayer.phoneConnected(this),
            store.phoneTime,
            System.currentTimeMillis(),
            settings.interval,
        )
        val need = WatchPhoneQuotes.selfFetch(
            favorites,
            store.quotes().mapValues { it.value.time },
            store.phoneCoins,
            usePhone,
            settings.interval,
            System.currentTimeMillis(),
            force = false
        )
        if (need.isNotEmpty()) {
            // Komplikation ruft selbst ab: zählt für «Akku & Aktivität» als Hintergrund-Lauf
            WatchUsage.attach(this)
            UsageMeter.shared.countRun()
            val startedAt = System.currentTimeMillis()
            // Abbruch des Aufrufs nicht schlucken; höchstens [WatchPriceSource.FETCH_DEADLINE_MS], danach der gespeicherte Kurs
            val fresh = try {
                WatchPriceSource.fetch(need)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
            // Je Coin zusammengeführt: fehlt der erste Coin im neuen Ergebnis oder hat ein später gestarteter Abruf
            // schon geschrieben, bleibt der gespeicherte Kurs samt Zeit
            if (fresh.isNotEmpty()) cached = store.mergeQuotes(fresh, startedAt)[coin] ?: cached
            UsageMeter.shared.flush()
            WatchUsage.publish(this)
        }
        // Börse des Paars stillgelegt (ExchangeRetirement) bzw. noch ohne Kurs: kein alter Kurs, sondern «Paar / —» mit
        // dem Grund und Tipp-Ziel (wie die Zeile der Uhr-App) statt «keine Daten»
        val retired = WatchPair.parse(coin)?.let { WatchPairQuotes.retired(it) } == true
        val shown =
            ComplicationPlan.shown(cached, retired) ?: return PriceComplicationData.missing(this, request.complicationType, coin, tap)
        val q = shown.quote
        val time = shown.time
        val now = System.currentTimeMillis()
        val age = if (WatchFreshness.isOutdated(time, now, settings.interval)) {
            PriceComplicationData.Age(WatchAge.short(time, now), WatchAge.spoken(this, time, now), time)
        } else {
            null
        }
        // Paar des Handys wie dort angezeigt («BANK Perp»), Kürzel der eigenen Liste unverändert (gegen USDT)
        // Börse, die den Kurs geliefert hat (wie die Zeile der Uhr-App), nicht nur das Paar
        val quote = WatchPair.parse(coin)?.quote ?: WatchPair.USDT
        return PriceComplicationData.data(
            this,
            request.complicationType,
            WatchPair.label(coin),
            quote,
            q.price,
            q.change24h,
            q.source.takeIf { it.isNotBlank() },
            age,
            tap
        )
    }
}
