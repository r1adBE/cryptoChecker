package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.alarm.DerivativesValues
import com.cryptochecker.app.data.ErrorWrite
import com.cryptochecker.app.data.PriceWrite
import com.cryptochecker.app.data.pairIdentity
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.VolumeSpike
import com.cryptochecker.app.data.remote.WindowRanges
import com.cryptochecker.app.domain.alarm.AlarmCheckStatus
import com.cryptochecker.app.domain.alarm.AlarmEvaluator
import com.cryptochecker.app.domain.alarm.DerivativesAlarmData
import com.cryptochecker.app.domain.alarm.MoveWindow
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.domain.alarm.AlarmTextNote
import com.cryptochecker.app.domain.convert.FiatAlarmGuard
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.marketdata.model.Ticker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections

/*
 * Fakes für die Auswertung nach einem neuen Kurs (Schnittstellen aus RefreshPorts.kt): im Speicher, ohne
 * Android-Kontext, Room oder DataStore. Gemeinsam für RefreshEffectsAlarmTest, RefreshResultProcessorTest,
 * LivePriceApplierLockTest und AlarmChainIntegrationTest.
 */

/**
 * Merkliste und Alarme im Speicher. [markAlarmTriggered] verhält sich wie die bedingte Abfrage in WatchDao:
 * gespeichert wird nur, wenn der Alarm seit dem Lesen unverändert ist (sonst false, wie «inzwischen bearbeitet»).
 */
internal class FakeRefreshStore(
    watches: List<WatchEntity> = emptyList(),
    alarms: List<AlarmEntity> = emptyList(),
) : RefreshStore {
    val watches: MutableMap<Long, WatchEntity> = Collections.synchronizedMap(LinkedHashMap(watches.associateBy { it.id }))
    val alarms: MutableMap<Long, AlarmEntity> = Collections.synchronizedMap(LinkedHashMap(alarms.associateBy { it.id }))

    /** Jeder Aufruf von [applyRefresh] (Reihenfolge der Schreibvorgänge). */
    val refreshWrites: MutableList<List<PriceWrite>> = Collections.synchronizedList(ArrayList())
    val errorWrites: MutableList<ErrorWrite> = Collections.synchronizedList(ArrayList())
    val notifiedPrices: MutableList<Pair<Long, Double>> = Collections.synchronizedList(ArrayList())
    val rearmed: MutableList<Long> = Collections.synchronizedList(ArrayList())
    val references: MutableList<Pair<Long, Double>> = Collections.synchronizedList(ArrayList())

    /** Wird vor jedem [applyRefresh] aufgerufen (z. B. um gleichzeitige Schreibvorgänge zu erkennen). */
    var beforeApply: suspend () -> Unit = {}

    fun alarm(id: Long): AlarmEntity = checkNotNull(alarms[id])

    override suspend fun getWatches(): List<WatchEntity> = synchronized(watches) { watches.values.toList() }

    override suspend fun applyRefresh(prices: List<PriceWrite>, errors: List<ErrorWrite>): Set<Long> {
        beforeApply()
        refreshWrites += prices
        errorWrites += errors
        val written = HashSet<Long>()
        for (p in prices) {
            val old = watches[p.id] ?: continue
            // Wie WatchDao.updatePriceForPair: Kurs eines inzwischen anderen Paars wird verworfen
            if (p.pair != null && p.pair != old.pairIdentity) continue
            // Wie die Bedingung in WatchDao: ein älterer Kurs als der gespeicherte wird verworfen (PriceWriteOrder)
            if (!PriceWriteOrder.accepts(p.time, old.lastUpdate, System.currentTimeMillis())) continue
            watches[p.id] = old.copy(
                previousPrice = old.lastPrice,
                lastPrice = p.price,
                lastUpdate = p.time,
                lastError = null,
                change24h = p.change24h,
                priceChangedAt = p.priceChangedAt,
                exchangeTime = p.exchangeTime,
            )
            written += p.id
        }
        for (e in errors) watches[e.id]?.let { watches[e.id] = it.copy(lastError = e.error) }
        return written
    }

    override suspend fun setNotifiedPrices(prices: List<Pair<Long, Double>>, time: Long) {
        notifiedPrices += prices
    }

    override suspend fun getAllEnabledAlarms(): List<AlarmEntity> =
        synchronized(alarms) { alarms.values.filter { it.enabled } }

    override suspend fun markAlarmTriggered(
        alarm: AlarmEntity,
        price: Double,
        time: Long,
        candleOpenTime: Long?,
        nearLevel: Double?,
    ): Boolean {
        val stored = alarms[alarm.id] ?: return false
        if (!stored.enabled || stored.condition != alarm.condition || stored.threshold != alarm.threshold ||
            stored.windowHours != alarm.windowHours || stored.repeating != alarm.repeating || stored.currency != alarm.currency
        ) return false
        alarms[alarm.id] = AlarmEvaluator.triggered(stored, price, time, candleOpenTime, nearLevel)
        return true
    }

    override suspend fun setAlarmReferenceIfUnchanged(alarm: AlarmEntity, price: Double, time: Long) {
        references += alarm.id to price
        alarms[alarm.id]?.let { alarms[alarm.id] = it.copy(referencePrice = price, referenceAt = time) }
    }

    override suspend fun rearmAlarmIfUnchanged(alarm: AlarmEntity) {
        rearmed += alarm.id
        alarms[alarm.id]?.let { alarms[alarm.id] = it.copy(referenceAt = 0) }
    }
}

/** Hält Alarm- und Kurs-Meldungen fest; [shows] = false wie «von Android verworfen», [deliverable] = false wie «Kanal stumm». */
internal class FakeRefreshNotifier(
    var shows: Boolean = true,
    var deliverable: Boolean = true,
) : RefreshNotifier {
    /** Gezeigte Alarm-Meldungen: Alarm-Id, gemeldeter Kurs, Währung des Kurses. */
    val alarmPosts: MutableList<Triple<Long, Double, String?>> = Collections.synchronizedList(ArrayList())
    /** Zusatz am Alarmtext je Meldung (gleiche Reihenfolge wie [alarmPosts]). */
    val textNotes: MutableList<AlarmTextNote?> = Collections.synchronizedList(ArrayList())
    val cancelledAlarms: MutableList<Long> = Collections.synchronizedList(ArrayList())
    val pricePosts: MutableList<Long> = Collections.synchronizedList(ArrayList())
    val cancelledPrices: MutableList<Long> = Collections.synchronizedList(ArrayList())

    override fun canShowAlarm(alarm: AlarmEntity): Boolean = deliverable

    /** Laufende «1 h später»-Pausen: Alarm-Id → Ende. */
    val snoozedUntil: MutableMap<Long, Long> = java.util.concurrent.ConcurrentHashMap()

    override fun alarmSnoozeUntil(alarmId: Long): Long? = snoozedUntil[alarmId]

    override suspend fun showAlarm(
        watch: WatchEntity,
        alarm: AlarmEntity,
        price: Double,
        volumeRatio: Double?,
        nearFire: NearExtreme.Decision.Fire?,
        derivativesValue: Double?,
        derivativesReference: String?,
        priceCurrency: String?,
        textNote: AlarmTextNote?,
    ): Boolean {
        alarmPosts += Triple(alarm.id, price, priceCurrency)
        textNotes += textNote
        onShow()
        return shows
    }

    /** Nach jeder Meldung (Tests: z. B. den Lauf abbrechen). */
    var onShow: () -> Unit = {}

    override fun cancelAlarms(alarmIds: Collection<Long>) {
        cancelledAlarms += alarmIds
    }

    override fun activeNotificationIds(): Set<Int>? = emptySet()

    override fun cancelPrices(watchIds: Collection<Long>, shown: Set<Int>?) {
        cancelledPrices += watchIds
    }

    /** Vor jedem [showPrices] (Tests: Kurs-Meldungen aufhalten). */
    var beforeShowPrices: suspend () -> Unit = {}

    /** Welche Kurs-Meldungen als gezeigt gelten (sonst «von Android verworfen»). */
    var priceShown: (Long) -> Boolean = { true }

    override suspend fun showPrices(watches: List<WatchEntity>, ongoing: Boolean): Set<Long> {
        beforeShowPrices()
        pricePosts += watches.map { it.id }
        return watches.map { it.id }.filterTo(HashSet()) { priceShown(it) }
    }
}

/** Sprachausgabe: merkt nur die Texte (mit [flush] = Alarm-Ansage). */
internal class FakeRefreshSpeaker : RefreshSpeaker {
    val spoken: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(ArrayList())
    override fun enqueue(text: String, speechRate: Float, flush: Boolean, key: Long?) {
        spoken += text to flush
    }
}

internal class FakeSpokenText : RefreshSpokenText {
    override fun price(watch: WatchEntity, price: Double): String = "price ${watch.baseAsset} $price"
    override fun alarm(watch: WatchEntity, condition: AlarmCondition, price: Double, currency: String?): String =
        "alarm ${watch.baseAsset} ${condition.name} $price ${currency.orEmpty()}".trim()
}

internal class FakeVolumeSource(var spike: VolumeSpike? = null) : AlarmVolumeSource {
    var calls = 0
    override suspend fun hourlySpike(watch: WatchEntity, price: Double?): VolumeSpike? {
        calls++
        return spike
    }
}

/** Devisenkurse je Zielwährung (Grossbuchstaben); fehlt einer → null wie «kein Faktor». */
internal class FakeRateSource(val rates: Map<String, Double> = emptyMap()) : AlarmRateSource {
    /** Fiat-Alarme auslassen ([FiatAlarmGuard.skip]); null = prüfen. */
    var skip: FiatAlarmGuard.Skip? = null
    /** Faktor mit USDT = 1 (keine Messung). */
    var usdtAssumed: Boolean = false
    override suspend fun rate(quote: String, target: String): Double? =
        if (quote.equals(target, ignoreCase = true)) 1.0 else rates[target.uppercase()]
    override fun skip(quote: String, target: String, now: Long) = skip
    override fun usdtAssumed(quote: String, target: String, now: Long) = usdtAssumed
}

internal class FakeRangeSource(var ranges: WindowRanges? = null, var allowed: Boolean = true) : AlarmRangeSource {
    override suspend fun ranges(baseAsset: String, quoteAsset: String): WindowRanges? = ranges
    override fun seriesAllowed(ranges: WindowRanges, exchangeKey: String, baseAsset: String, price: Double?, quoteToSeries: Double?) =
        allowed
}

internal class FakeDerivativesSource(var values: DerivativesValues? = null) : AlarmDerivativesSource {
    override suspend fun prefetch(watch: WatchEntity, now: Long) = Unit
    override suspend fun values(watch: WatchEntity, price: Double, now: Long): DerivativesValues? = values
    override fun oiChange(watch: WatchEntity, values: DerivativesValues, hours: Int, now: Long): Double? = null
}

internal class FakeMoveHistory : AlarmMoveHistory {
    val points = HashMap<Long, MutableList<MoveWindow.PricePoint>>()
    override fun history(watchId: Long): List<MoveWindow.PricePoint> = points[watchId].orEmpty()
    override fun append(watchId: Long, point: MoveWindow.PricePoint, now: Long) {
        points.getOrPut(watchId) { ArrayList() } += point
    }
}

/** Veränderung aus dem Ticker (wie ein Ticker mit 24-h-Wert), ohne Kerzen. */
internal class FakeDayReferences : RefreshDayReferences {
    override fun notePillSource(watchId: Long, provider: String?) = Unit
    override fun change24h(watch: WatchEntity, price: Double, ticker: Ticker, basis: ChangeBasis, dayStart: Long?): Double? =
        ticker.change24hPercent
}

/** DEX-Liquidität: [blocked] = Pools, deren Alarme ruhen; [measured] = gemerkte Messungen. */
internal class FakeDexLiquidity(var blocked: Set<String?> = emptySet()) : RefreshDexLiquidity {
    val measured = HashMap<String?, Double?>()
    override fun put(pairId: String?, liquidityUsd: Double?) {
        measured[pairId] = liquidityUsd
    }
    override fun blocksAlarms(exchangeKey: String, pairId: String?): Boolean = pairId in blocked
}

internal class FakeRefreshSettings(var settings: AppSettings = AppSettings()) : RefreshSettings {
    override suspend fun current(): AppSettings = settings
}

internal class FakeRefreshStats : RefreshStatsPort {
    override val changeStamp: StateFlow<ChangeStamp?> = MutableStateFlow(null)
    override val lastDurationMillis: StateFlow<Long> = MutableStateFlow(0L)
    var lastRefreshCalls = 0
    override fun setLastRefresh(durationMillis: Long) {
        lastRefreshCalls++
    }
}

internal class FakeRefreshWidgets : RefreshWidgets {
    var draws = 0

    /** Gesetzt: Zeichnen scheitert mit diesem Fehler (nach dem Zählen). */
    var failure: Exception? = null
    override suspend fun drawAll(deferWhenOff: Boolean, fetch: Boolean): Boolean {
        draws++
        failure?.let { throw it }
        return false
    }
}

internal class FakeRefreshCharts : RefreshCharts {
    var launches = 0
    override fun markIfStale() = Unit
    override fun launchIfPending() {
        launches++
    }
}

/** Alle Fakes einer Auswertung zusammen, mit den echten Klassen darüber. */
internal class RefreshFixture(
    watches: List<WatchEntity> = emptyList(),
    alarms: List<AlarmEntity> = emptyList(),
) {
    val store = FakeRefreshStore(watches, alarms)
    val notifier = FakeRefreshNotifier()
    val speaker = FakeRefreshSpeaker()
    val rates = FakeRateSource(mapOf("CHF" to 0.9))
    val volume = FakeVolumeSource()
    val ranges = FakeRangeSource()
    val derivatives = FakeDerivativesSource()
    val moveHistory = FakeMoveHistory()
    val checkStatus = AlarmCheckStatus()
    val dex = FakeDexLiquidity()
    /** Letzter gemerkter Mark-Ersatz je Watch-Id. */
    val markFallback = HashMap<Long, Boolean>()
    val settings = FakeRefreshSettings(AppSettings(alarmCooldownMinutes = 15))
    val stats = FakeRefreshStats()
    val widgets = FakeRefreshWidgets()
    val charts = FakeRefreshCharts()
    val lock = RefreshLock()

    val effects = RefreshEffects(
        watchRepository = store,
        alarmEvaluator = AlarmEvaluator(),
        notifier = notifier,
        ttsSpeaker = speaker,
        spokenText = FakeSpokenText(),
        volumeDataSource = volume,
        currencyConverter = rates,
        nearExtremeDataSource = ranges,
        derivativesAlarmData = derivatives,
        moveHistoryStore = moveHistory,
        alarmCheckStatus = checkStatus,
    )

    /** Alarm-Protokoll: alle übergebenen Einträge, in Reihenfolge. */
    val alarmLog: MutableList<com.cryptochecker.app.domain.alarm.AlarmLogEntry> = Collections.synchronizedList(ArrayList())

    val processor = RefreshResultProcessor(
        watchRepository = store,
        notifier = notifier,
        dayReferences = FakeDayReferences(),
        effects = effects,
        dexLiquidity = dex,
        markFallback = { id, on -> markFallback[id] = on },
        alarmLog = { entries, _ -> alarmLog += entries },
    )

    val live = LivePriceApplier(
        refreshLock = lock,
        watchRepository = store,
        settingsRepository = settings,
        refreshStats = stats,
        liveWidgetGate = widgets,
        chartRefresher = charts,
        resultProcessor = processor,
    )

    /** Ein Paar BTC/USDT (Binance) mit gespeichertem Kurs. */
    companion object {
        const val NOW = 1_700_000_000_000L

        fun watch(id: Long = 1, price: Double? = 99.0, exchangeKey: String = "Binance", pairId: String? = null) = WatchEntity(
            id = id, exchangeKey = exchangeKey, exchangeName = exchangeKey, baseAsset = "BTC", quoteAsset = "USDT",
            pairId = pairId, lastPrice = price, notificationEnabled = false,
        )

        fun above(level: Double, id: Long = 7, watchId: Long = 1, repeating: Boolean = false) =
            AlarmEntity(id = id, watchId = watchId, condition = AlarmCondition.PRICE_ABOVE, threshold = level, repeating = repeating)

        fun below(level: Double, id: Long = 8, watchId: Long = 1, repeating: Boolean = false) =
            AlarmEntity(id = id, watchId = watchId, condition = AlarmCondition.PRICE_BELOW, threshold = level, repeating = repeating)
    }
}
