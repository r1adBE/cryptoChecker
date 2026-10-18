package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.DexLiquidityStore
import com.cryptochecker.app.data.MarkFallbackStore
import com.cryptochecker.app.data.ErrorWrite
import com.cryptochecker.app.data.PriceWrite
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.pairIdentity
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.alarm.AlarmFreshness
import com.cryptochecker.app.domain.alarm.AlarmLog
import com.cryptochecker.app.domain.watch.PriceFreshness
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.marketdata.model.Ticker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Geholte Kurse speichern und auswerten — gemeinsamer letzter Schritt von voller und einzelner Aktualisierung
 * ([PriceRefresher]) und Live-Speichern ([LivePriceApplier]): Kurse und Fehler in einem Datenbank-Vorgang, danach
 * Alarme, Kurs-Meldungen und Ansagen. Läuft nur unter der Refresh-Sperre ([RefreshLockScope] als Beweis).
 */
@Singleton
class RefreshResultProcessor internal constructor(
    private val watchRepository: RefreshStore,
    private val notifier: RefreshNotifier,
    /** 24-h- und Tages-Bezüge aus Kerzen. */
    private val dayReferences: RefreshDayReferences,
    /** Alarme, Kurs-Meldungen und Ansagen nach einem neuen Kurs. */
    private val effects: RefreshEffects,
    /** Liquidität der DEX-Pools: unter der Mindestmenge keine Alarme ([com.cryptochecker.app.domain.watch.DexLiquidity]). */
    private val dexLiquidity: RefreshDexLiquidity,
    /** Paare mit Mark-Preis statt Mittelkurs ([Ticker.markFallback]): für «Mark» in Zeile, Blatt und Alarm-Karte. */
    private val markFallback: RefreshMarkFallback = RefreshMarkFallback.NONE,
    /** Alarm-Protokoll ([com.cryptochecker.app.domain.alarm.AlarmLog]): letzte Prüfung je Alarm und bemerkenswerte Prüfungen. */
    private val alarmLog: RefreshAlarmLog = RefreshAlarmLog.NONE,
) {
    /** Für Hilt: die App-Klassen hinter den schmalen Schnittstellen aus RefreshPorts.kt (Tests geben Fakes). */
    @Inject constructor(
        watchRepository: WatchRepository,
        notifier: AppNotifier,
        dayReferences: DayReferences,
        effects: RefreshEffects,
        dexLiquidity: DexLiquidityStore,
        markFallback: MarkFallbackStore,
        alarmLog: AlarmLogStore,
    ) : this(
        watchRepository.asRefreshStore(),
        notifier.asRefreshNotifier(),
        dayReferences.asRefreshDayReferences(),
        effects,
        dexLiquidity.asRefreshDexLiquidity(),
        markFallback.asRefreshMarkFallback(),
        alarmLog.asRefreshAlarmLog(),
    )

    /**
     * Wertet die geholten Kurse aus.
     *
     * Früher lief das Paar für Paar mit je vier Datenbankzugriffen (Kurs
     * schreiben, Paar neu lesen, Alarme lesen, Meldekurs schreiben). Bei
     * mehreren hundert Paaren waren das über tausend einzelne Schreibvorgänge,
     * nach jedem lud die Watchlist alle Paare neu. Jetzt: ein Schreibvorgang
     * für alle Kurse, eine Abfrage für alle Alarme, ein Schreibvorgang für
     * die Meldekurse.
     */
    internal suspend fun process(
        /** Beweis der gehaltenen Refresh-Sperre: Kurse schreiben nur unter ihr. */
        @Suppress("UNUSED_PARAMETER") lock: RefreshLockScope,
        watches: List<WatchEntity>,
        fetched: Map<Long, Fetched>,
        settings: AppSettings,
        /** Tagesbeginn der %-Basis; null = rollende 24 Stunden. */
        dayStart: Long?,
        /** Live-Kurse ([LivePriceApplier]): Veränderung steht schon im Ticker, keine Ansagen, keine Volumen-Alarme. */
        live: Boolean = false,
    ): Processed {
        val now = System.currentTimeMillis()

        val priceWrites = ArrayList<PriceWrite>()
        val errorWrites = ArrayList<ErrorWrite>()
        val updatedWatches = ArrayList<WatchEntity>()
        // Kurs = Mark-Preis mangels Mittelkurs (Hyperliquid, dünnes Orderbuch): keine Alarme in diesem Durchlauf
        val markFallbackIds = HashSet<Long>()

        for (watch in watches) {
            val result = fetched[watch.id]
            val ticker = result?.ticker
            val price = ticker?.last ?: 0.0

            if (ticker == null || result.error != null ||
                // NaN/∞ aus einer Antwort nie als Kurs übernehmen (∞ löste sonst «über» aus)
                !price.isFinite() || price <= Ticker.MISSING || price <= 0.0
            ) {
                errorWrites += ErrorWrite(watch.id, result?.error, now, watch.pairIdentity)
                continue
            }

            val time = ticker.timestamp.takeIf { it > 0 } ?: now
            // DEX-Pool: Liquidität jeder Abfrage merken (Alarm-Sperre unten, Hinweis in Suche und Alarm-Liste)
            if (!live && com.cryptochecker.app.domain.watch.DexLiquidity.isDex(watch.marketKey)) {
                dexLiquidity.put(watch.pairId, ticker.liquidityUsd)
            }
            if (ticker.markFallback) markFallbackIds += watch.id
            // Live-Kurse kommen nie als Mark-Ersatz: der Merker gilt bis zur nächsten Abfrage
            if (!live) markFallback.put(watch.id, ticker.markFallback)
            val dayChange = if (live) {
                // Live-Wert aus dem Ticker: kein Kerzen-Hinweis — aber nur, wenn er die Veränderung ersetzt hat.
                // Bleibt die bisherige ([LiveRules.chooseChange], z. B. Tages-Basis), bleibt auch ihr Anbieter:
                // sonst stünde ein Wert aus fremden Kerzen ohne Hinweis da, als käme er von der eigenen Börse
                if (ticker.change24hPercent != watch.change24h) dayReferences.notePillSource(watch.id, null)
                ticker.change24hPercent
            } else {
                dayReferences.change24h(watch, price, ticker, settings.changeBasis, dayStart)
            }
            // Frische (PriceFreshness): letzte echte Kursänderung und Zeit laut Börse (null = keine).
            // Live: nur eine im Strom belegte Handelszeit ([LiveRules.exchangeTime], in [applyLive] gesetzt).
            val changedAt = PriceFreshness.nextPriceChangedAt(watch.lastPrice, watch.priceChangedAt, price, time)
            val exchangeTime = ticker.exchangeTime
            // Mit dem Paar, für das der Kurs geholt wurde: ist der Eintrag inzwischen ein anderes, wird nichts gespeichert
            priceWrites += PriceWrite(watch.id, price, time, dayChange, changedAt, exchangeTime, watch.pairIdentity)
            // Entspricht dem, was das UPDATE in der Datenbank setzt.
            updatedWatches += watch.copy(
                previousPrice = watch.lastPrice,
                lastPrice = price,
                lastUpdate = time,
                lastError = null,
                change24h = dayChange,
                priceChangedAt = changedAt,
                exchangeTime = exchangeTime,
            )
        }

        val dbStartedAt = System.currentTimeMillis()
        val written = watchRepository.applyRefresh(priceWrites, errorWrites)
        // Nicht gespeichert (Eintrag ist inzwischen ein anderes Paar): weder Alarme noch Meldungen mit diesem Kurs
        updatedWatches.retainAll { it.id in written }
        val dbMillis = System.currentTimeMillis() - dbStartedAt

        val effectsStartedAt = System.currentTimeMillis()

        // Gezeigte Meldungen EINMAL abfragen: Bei hunderten Paaren ohne Kurs-Meldung kostete
        // sonst jedes Paar je Durchlauf einen Aufruf an den Systemdienst (cancel).
        val shownNotifications = notifier.activeNotificationIds()

        // Ohne Kurs gibt es nichts zu melden (entfernt wird gesammelt nach den Alarmen).
        val priceCancels = errorWrites.mapTo(ArrayList()) { it.id }
        val pricePosts = ArrayList<WatchEntity>()

        val alarmsByWatch: Map<Long, List<AlarmEntity>> = if (updatedWatches.isEmpty()) emptyMap()
        else watchRepository.getAllEnabledAlarms().groupBy { it.watchId }

        var alarms = 0
        val notifiedPrices = ArrayList<Pair<Long, Double>>()
        // Alarm-Protokoll: nur mitschreiben, am Ende einmal speichern
        val log = AlarmLogCollector(now)

        for (watch in updatedWatches) {
            val price = watch.lastPrice ?: continue
            // Ein Paar, dessen Alarme scheitern (Datenbank, Daten einer Quelle), hält die übrigen nicht auf
            val triggered = if (dexLiquidity.blocksAlarms(watch.marketKey, watch.pairId)) {
                // DEX-Pool mit zu wenig oder unbekannter Liquidität: Alarme bleiben, lösen aber nicht aus
                log.noteBlocked(
                    watch, alarmsByWatch[watch.id].orEmpty(), lowLiquidity = true,
                    markFallback = watch.id in markFallbackIds, cooldownMinutes = settings.alarmCooldownMinutes,
                )
                0
            } else if (AlarmFreshness.blocksAlarms(watch.exchangeTime, watch.id in markFallbackIds, now)) {
                // Kurs laut Börse zu alt (Handel ruht) bzw. nur Mark-Preis: Alarme bleiben scharf, lösen aber nicht aus
                Timber.d("Alarme für %s ausgelassen: kein aktueller handelbarer Kurs", watch.displayName)
                log.noteBlocked(
                    watch, alarmsByWatch[watch.id].orEmpty(), lowLiquidity = false,
                    markFallback = watch.id in markFallbackIds, cooldownMinutes = settings.alarmCooldownMinutes,
                )
                0
            } else try {
                effects.checkAlarms(
                    watch, price, watch.previousPrice,
                    alarmsByWatch[watch.id].orEmpty(), settings, now, live,
                    trace = log.sinkFor(watch),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Alarme für %s nicht geprüft", watch.displayName)
                0
            }
            alarms += triggered

            when (effects.priceUpdate(watch, settings)) {
                RefreshEffects.PriceUpdate.SHOW -> pricePosts += watch
                RefreshEffects.PriceUpdate.CANCEL -> priceCancels += watch.id
                RefreshEffects.PriceUpdate.NONE -> Unit
            }
            if (!live) effects.speakPriceIfWanted(watch, price, settings, spokenAlready = triggered > 0)
        }

        // Kurs-Mitteilungen NACH den Alarmen und gesammelt: höchstens 20 einzeln, der Rest in einer
        // Sammelmeldung (Android verwirft ab rund 50 offenen Mitteilungen je App weitere still).
        // Neuer Bezugspunkt nur für Paare, deren Kurs jetzt gezeigt wird.
        notifier.cancelPrices(priceCancels, shownNotifications)
        if (pricePosts.isNotEmpty()) {
            val shownPrices = notifier.showPrices(pricePosts, ongoing = settings.ongoingNotifications)
            for (watch in pricePosts) {
                val price = watch.lastPrice ?: continue
                if (watch.id in shownPrices) notifiedPrices += watch.id to price
            }
        }

        watchRepository.setNotifiedPrices(notifiedPrices, now)

        recordAlarmLog(log, watches, errorWrites, alarmsByWatch, live)

        return Processed(
            checked = watches.size,
            failed = errorWrites.size,
            alarms = alarms,
            notificationsShown = notifiedPrices.size,
            dbMillis = dbMillis,
            effectsMillis = System.currentTimeMillis() - effectsStartedAt,
            missingDayChange = updatedWatches.filter { it.change24h == null },
            failedIds = errorWrites.map { it.id },
        )
    }

    /**
     * Alarm-Protokoll speichern (nach allen Meldungen, ein Schreibvorgang). Paare ohne Kurs: «Kein Kurs» für ihre Alarme —
     * die Alarme werden dafür nur gelesen, wenn der Durchlauf sie nicht ohnehin geladen hat. Jeder Fehler bleibt hier.
     */
    private suspend fun recordAlarmLog(
        log: AlarmLogCollector,
        watches: List<WatchEntity>,
        errorWrites: List<ErrorWrite>,
        alarmsByWatch: Map<Long, List<AlarmEntity>>,
        live: Boolean,
    ) {
        try {
            if (errorWrites.isNotEmpty() && !live) {
                val byWatch = alarmsByWatch.ifEmpty { watchRepository.getAllEnabledAlarms().groupBy { it.watchId } }
                val watchById = watches.associateBy { it.id }
                for (error in errorWrites) {
                    val watch = watchById[error.id] ?: continue
                    log.noteNoPrice(watch, byWatch[error.id].orEmpty())
                }
            }
            // Wer geprüft hat (App, Hintergrund, Wecker, Widget): steht in der Zeile des Protokolls
            val source = currentCoroutineContext()[CheckOrigin]?.trigger ?: CheckTrigger.FOREGROUND
            alarmLog.record(AlarmLog.withSource(log.entries, source), live)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Alarm-Protokoll nicht gespeichert")
        }
    }

    internal class Processed(
        val checked: Int,
        val failed: Int,
        val alarms: Int,
        val notificationsShown: Int,
        val dbMillis: Long,
        val effectsMillis: Long,
        /** Paare mit neuem Kurs, aber ohne Veränderung (kein Bezug). */
        val missingDayChange: List<WatchEntity> = emptyList(),
        /** Paare ohne neuen Kurs (Fehler, nicht mehr gehandelt). */
        val failedIds: List<Long> = emptyList(),
    )
}
