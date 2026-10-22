package com.cryptochecker.app.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Erstellt und entfernt alle Benachrichtigungen der App. Hier Zustand, Kurs-Mitteilungen und Entfernen; als
 * Erweiterungsfunktionen in eigenen Dateien: Alarme (`AppNotifierAlarms.kt`), Markt-Mitteilungen
 * (`AppNotifierMarket.kt`), Kurs-Mitteilungen im Einzelnen (`AppNotifierPrices.kt`) und Zustellung (`AppNotifierDelivery.kt`).
 */
@Singleton
class AppNotifier @Inject constructor(
    @param:ApplicationContext internal val context: Context,
    /** Für die Akzentfarbe der Benachrichtigungen. */
    internal val settingsRepository: SettingsRepository,
    /** Coin-Logo als grosses Bild der Alarm-Meldung (nur vom Gerät, nie aus dem Netz); erst bei Bedarf erzeugt. */
    internal val coinLogos: dagger.Lazy<com.cryptochecker.app.data.CoinLogoRepository>,
) {
    internal val manager get() = NotificationManagerCompat.from(context)

    fun hasPermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) true
        else ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    // ---------------- Kurs ----------------

    /**
     * Kurs-Meldung nach einer bewussten Handlung (Paar-Mitteilung ein, «Alle an», «Rückgängig»): einziger
     * Weg dafür — nur mit Kurs-Mitteilungen an ([PriceNotificationRule.showNow]), dauerhaft nur mit
     * «Dauerhafte Mitteilung» (Einstellung), wie die Aktualisierung. @return true = gezeigt.
     */
    suspend fun showPriceNow(watch: WatchEntity): Boolean {
        val settings = settingsRepository.current()
        val show = PriceNotificationRule.showNow(
            masterOn = settings.priceNotifications,
            pairOn = watch.notificationEnabled,
            disabled = watch.disabled,
            notTraded = watch.isNotTraded,
            price = watch.lastPrice,
        )
        if (!show) return false
        // Über die Grenze von 20 Einzelmeldungen: in der Sammelmeldung (zählt als gezeigt)
        // Auf Dispatchers.IO: Systemdienst; ein laufender Kurs-Durchlauf lässt [showPrices] bis zu einigen Sekunden warten
        return withContext(Dispatchers.IO) {
            watch.id in showPrices(listOf(watch), ongoing = settings.ongoingNotifications)
        }
    }

    /**
     * Kurs-Mitteilungen [watches] zeigen bzw. aktualisieren (ein Durchlauf oder eine Handlung):
     * höchstens [NotificationBudget.MAX_PRICE] einzeln — Android verwirft ab rund 50 offenen
     * Mitteilungen je App jede weitere still, auch Alarme —, der Rest in EINER Sammelmeldung
     * («+N weitere Kurse»). Reihenfolge von [watches] = Vorrang für freie Plätze.
     * @return Paare, deren Kurs jetzt gezeigt wird (einzeln oder in der Sammelmeldung).
     */
    suspend fun showPrices(watches: List<WatchEntity>, ongoing: Boolean): Set<Long> =
        priceRunLock.withLock { showPricesLocked(watches, ongoing) }

    /** [showPrices] unter [priceRunLock]. */
    private suspend fun showPricesLocked(watches: List<WatchEntity>, ongoing: Boolean): Set<Long> {
        val posts = watches.filter { it.lastPrice != null }.distinctBy { it.id }
        if (posts.isEmpty() || !hasPermission()) return emptySet()
        // Die Objektsperre nur für kurze Schritte, nie während der Pausen: Sonst warteten Entfernen
        // (Paar gelöscht, Mitteilung aus) und das Melden eines Alarms ([makeRoomForAlarm]) bis zu
        // mehreren Sekunden. Durchläufe untereinander bleiben über [priceRunLock] nacheinander.
        val (active, plan) = synchronized(this) {
            priceRunActive = true
            cancelledDuringRun.clear()
            val active = activeState()
            val plan = NotificationBudget.planPrices(
                shown = active?.prices?.keys?.toList().orEmpty(),
                overflowBefore = active?.overflow?.keys?.toList().orEmpty(),
                posts = posts.map { it.id },
                slots = NotificationBudget.priceSlots(active?.other ?: 0),
            )
            // Zuerst Platz schaffen, dann melden
            plan.demoted.forEach { cancelPriceSingle(it) }
            active to plan
        }
        try {
            val byId = posts.associateBy { it.id }
            val shown = HashSet<Long>()
            // Mehr als 5 Meldungen: gedrosselt (Android verwirft sonst die überzähligen still, sie zählten
            // aber als gezeigt). Die Pausen halten keinen Thread fest (delay statt sleep), brechen aber wie bisher
            // nicht mitten im Durchlauf ab (sonst Meldungen ohne gespeicherten Meldekurs); ein einzelnes Paar nie
            val gap = NotificationBudget.postGapMillis(plan.individual.size + if (plan.overflow.isEmpty()) 0 else 1)
            var posted = 0
            suspend fun spaceOut() {
                if (gap > 0 && posted > 0) withContext(NonCancellable) { delay(gap) }
                posted++
            }
            for (id in plan.individual) {
                spaceOut()
                synchronized(this) {
                    // Inzwischen entfernt (der Plan stammt von vorher): nicht wieder zeigen
                    if (id !in cancelledDuringRun && postPrice(byId.getValue(id), ongoing)) shown += id
                }
            }
            // Zeilen: frischer Kurs, sonst die bisherige Zeile (Sammelmeldung bzw. verschobene Einzelmeldung)
            fun overflowLines(): LinkedHashMap<Long, String> {
                val lines = LinkedHashMap<Long, String>()
                for (id in plan.overflow) {
                    if (id in cancelledDuringRun) continue
                    val watch = byId[id]
                    val price = watch?.lastPrice
                    val line = if (watch != null && price != null) priceTitle(watch, price)
                    else active?.overflow?.get(id) ?: active?.prices?.get(id)
                    if (!line.isNullOrEmpty()) lines[id] = line
                }
                return lines
            }
            val (unchanged, anyLine) = synchronized(this) {
                val lines = overflowLines()
                val same = active != null && active.overflowShown &&
                    active.overflow.entries.toList() == lines.entries.toList()
                same to lines.isNotEmpty()
            }
            if (!unchanged && anyLine) spaceOut()
            synchronized(this) {
                val lines = overflowLines()
                if (unchanged || postOverflow(lines, ongoing)) {
                    lines.keys.filterTo(shown) { it in byId }
                }
            }
            return shown
        } finally {
            synchronized(this) {
                priceRunActive = false
                cancelledDuringRun.clear()
            }
        }
    }

    /** Hält Kurs-Durchläufe ([showPrices]) nacheinander, ohne die Objektsperre zu belegen; wartet, ohne zu blockieren. */
    private val priceRunLock = Mutex()

    /** Läuft gerade [showPrices]? Dann merkt sich [cancelPrices] die entfernten Paare. */
    private var priceRunActive = false

    /** Während des laufenden [showPrices] entfernte Paare: werden in diesem Durchlauf nicht mehr gemeldet. */
    private val cancelledDuringRun = HashSet<Long>()

    /** Kurs-Meldung eines Paars entfernen; läuft nie auf dem Haupt-Thread (Systemdienst, Sperre). */
    suspend fun cancelPrice(watchId: Long) = cancelAllPrices(listOf(watchId))

    /**
     * Ids aller gerade gezeigten Meldungen der App — EIN Aufruf an den Systemdienst;
     * null, wenn das nicht geht (dann gilt jede Meldung als möglicherweise gezeigt).
     */
    fun activeNotificationIds(): Set<Int>? = runCatching {
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.activeNotifications?.mapTo(HashSet()) { it.id }
    }.getOrNull()

    /**
     * Wie [cancelPrice], aber nur, wenn die Kurs-Meldung laut [shown] ([activeNotificationIds])
     * gezeigt wird; [shown] null = unbekannt, dann immer entfernen.
     */
    fun cancelPriceIfShown(watchId: Long, shown: Set<Int>?) = cancelPrices(listOf(watchId), shown)

    /**
     * Kurs-Meldungen von [watchIds] entfernen — einzeln und aus der Sammelmeldung (diese nur einmal
     * neu); [shown] wie bei [cancelPriceIfShown].
     */
    @Synchronized
    fun cancelPrices(watchIds: Collection<Long>, shown: Set<Int>?) {
        if (watchIds.isEmpty()) return
        if (priceRunActive) cancelledDuringRun.addAll(watchIds)
        val removed = HashSet<Int>()
        for (watchId in watchIds) {
            val id = priceNotificationId(watchId)
            if (shown == null || id in shown || recentPrices.containsKey(watchId)) {
                manager.cancel(id)
                removed += id
            }
            recentPrices.remove(watchId)
        }
        val overflowMaybeShown = shown == null || PRICE_OVERFLOW_ID in shown ||
            System.currentTimeMillis() - overflowPostedAt < RECENT_MILLIS
        if (overflowMaybeShown) {
            val members = overflowMembers()
            val ids = watchIds.toHashSet()
            if (members.keys.any { it in ids }) {
                val rest = LinkedHashMap(members).apply { keys.removeAll(ids) }
                if (rest.isEmpty()) removed += PRICE_OVERFLOW_ID
                postOverflow(rest, ongoing = settingsRepository.cached.ongoingNotifications)
            }
        }
        if (removed.isNotEmpty()) cancelSummaryIfEmpty(Group.PRICES, removed)
    }

    /**
     * Nach einem Wechsel der Akzentfarbe: sichtbare Kurs-Meldungen neu zeichnen,
     * damit sie die neue Farbe übernehmen. Sonst blieben sie in der alten Farbe,
     * bis sich der Kurs genug bewegt.
     */
    @Synchronized
    fun refreshColors(watches: List<WatchEntity>, ongoing: Boolean) {
        val active = activeState() ?: return
        watches.filter { it.id in active.prices }
            .forEach { postPrice(it, ongoing) }
        if (active.overflowShown) postOverflow(active.overflow, ongoing)
    }

    /**
     * Kurs-Meldungen von [watchIds] in EINEM Schritt entfernen (die Sammelmeldung nur einmal neu) — auf
     * Dispatchers.IO, damit Bildschirm-Aktionen nie auf den Systemdienst oder einen Kurs-Durchlauf warten.
     */
    suspend fun cancelAllPrices(watchIds: Collection<Long>) {
        if (watchIds.isEmpty()) return
        withContext(Dispatchers.IO) { cancelPrices(watchIds, shown = null) }
    }

    // ---------------- Mitteilungs-Budget ----------------

    /** Eben gemeldete Kurs-Mitteilungen (Paar → Zeit, Titel): Der Systemdienst führt sie evtl. noch nicht. */
    internal val recentPrices = HashMap<Long, Pair<Long, String>>()

    /** Paare und Zeilen der Sammelmeldung, solange sie offen ist (null = aus den Extras lesen). */
    internal var overflowCache: LinkedHashMap<Long, String>? = null
    internal var overflowPostedAt = 0L

    /** Offene Mitteilungen, nach Art aufgeteilt (eine Abfrage beim Systemdienst). */
    internal class ActiveState(
        val ids: Set<Int>,
        /** Einzelne Kurs-Mitteilungen: Paar → Titel, neueste zuerst. */
        val prices: LinkedHashMap<Long, String>,
        /** Paare der Sammelmeldung → Zeile, neueste zuerst. */
        val overflow: LinkedHashMap<Long, String>,
        val overflowShown: Boolean,
        /** Alle anderen offenen Mitteilungen (ohne Kurs-Gruppe). */
        val other: Int,
        /** Aktivitäts-Meldungen, älteste zuerst (weichen nach den Kurs-Mitteilungen einem Alarm). */
        val activity: List<Int>,
        /** Alarm-Meldungen (Paar- und Portfolio-Alarme), älteste zuerst — weichen als letzte einem neuen Alarm. */
        val alarms: List<Int>,
        val total: Int,
    )

    /** Mit Portfolio-Sperre: auf dem Sperrbildschirm nur [publicTitle] (wie Kurs-Mitteilungen und Portfolio-Alarme). */
    internal fun NotificationCompat.Builder.privateOnLockScreen(
        channelId: String,
        iconRes: Int,
        publicTitle: String,
    ): NotificationCompat.Builder =
        setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(iconRes)
                    .setColor(settingsRepository.cached.accentColor.seed)
                    .setContentTitle(publicTitle)
                    .setShowWhen(false)
                    .build()
            )

    /** Wie [privateOnLockScreen], aber nur mit eingeschalteter Portfolio-Sperre. */
    internal fun NotificationCompat.Builder.lockScreenPrivacy(
        channelId: String,
        iconRes: Int,
        publicTitle: String,
    ): NotificationCompat.Builder =
        if (settingsRepository.cached.appLock) privateOnLockScreen(channelId, iconRes, publicTitle) else this

    // ---------------- Aktivität und Alarme entfernen ----------------
    // Melden: Alarme in AppNotifierAlarms.kt, Markt-Mitteilungen (Aktivität, Fear & Greed, Kennzahlen, Gas) in
    // AppNotifierMarket.kt.

    fun cancelActivity(watchId: Long) = manager.cancel(activityNotificationId(watchId))

    /**
     * Alarm-Meldungen der Paar-Alarme [alarmIds] und der Portfolio-Alarme [portfolioAlarmIds] entfernen
     * (Sicherung wiederhergestellt: die alten Alarme gibt es nicht mehr); danach die Zusammenfassung
     * der Gruppe, falls sie leer ist.
     */
    fun cancelAlarms(alarmIds: Collection<Long>, portfolioAlarmIds: Collection<Long> = emptyList()) {
        if (alarmIds.isEmpty() && portfolioAlarmIds.isEmpty()) return
        val removed = HashSet<Int>()
        alarmIds.mapTo(removed) { alarmNotificationId(it) }
        portfolioAlarmIds.mapTo(removed) { portfolioAlarmNotificationId(it) }
        removed.forEach { manager.cancel(it) }
        cancelSummaryIfEmpty(Group.ALARMS, removed)
    }

    // ---------------- Nachtruhe ----------------

    /**
     * Gerade Nachtruhe? Ortszeit des Geräts im Moment der Meldung; Einstellungen aus
     * dem Zwischenspeicher (der Aufrufer hat sie in diesem Durchlauf frisch gelesen).
     */
    fun isQuietNow(): Boolean {
        val s = settingsRepository.cached
        return QuietHours.isQuiet(s.quietHoursEnabled, s.quietHoursStart, s.quietHoursEnd, QuietHours.minuteOfDay())
    }

    // Aktualisierung: [refreshNotification] in AppNotifierDelivery.kt

    /**
     * «Zahl am App-Symbol»: Jede Alarm- bzw. Marktmeldung zählt 1 (der Startbildschirm zählt die
     * offenen Mitteilungen zusammen; Zahl oder Punkt entscheidet er). Aus: Hinweis «kein
     * Kennzeichen» — ganz aus nur über die Systemeinstellung der App.
     */
    internal fun NotificationCompat.Builder.appBadge(): NotificationCompat.Builder =
        if (settingsRepository.cached.appIconBadge) setNumber(1)
        else setNumber(0).setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)

    /**
     * Mitteilungs-Gruppen (wie `threadIdentifier` auf iOS): viele Kurs-Mitteilungen bzw. Alarme
     * fallen unter einer Zusammenfassung zusammen.
     */
    internal enum class Group(val key: String, val summaryId: Int, val titleRes: Int, val iconRes: Int) {
        PRICES("com.cryptochecker.app.group.PRICES", PRICE_SUMMARY_ID, R.string.channel_prices, R.drawable.ic_stat_logo),
        ALARMS("com.cryptochecker.app.group.ALARMS", ALARM_SUMMARY_ID, R.string.channel_alarms, R.drawable.ic_stat_logo),
    }

    /** [children] wie [activeInGroup]; [summaryOngoing] = Zusammenfassung offen (dauerhaft ja/nein), null = keine. */
    internal class GroupState(val children: Int, val summaryOngoing: Boolean?)

    companion object {
        // 1 war die Meldung des früheren Live-Dienstes «Immer kurz», 3 die frühere «Marktphase gewechselt» (nie wiederverwenden)
        const val REFRESH_NOTIFICATION_ID = 2
        const val FNG_NOTIFICATION_ID = 4
        /** Eigene Grenzen der Bitcoin-Kennzahlen: je Art eine Meldung (Basis + Ordnungszahl). */
        const val INDICATOR_NOTIFICATION_BASE_ID = 7_400
        const val GAS_ETH_NOTIFICATION_ID = 5
        const val GAS_BTC_NOTIFICATION_ID = 6
        const val TEST_ALARM_NOTIFICATION_ID = 7
        /** USDT-Depeg-Alarm (eine Meldung, ersetzt die vorige). */
        const val DEPEG_NOTIFICATION_ID = 8
        /** Zusammenfassungen der Gruppen Kurs-Mitteilungen bzw. Alarme. */
        internal const val PRICE_SUMMARY_ID = 9
        internal const val ALARM_SUMMARY_ID = 10
        /** Sammelmeldung der Kurs-Mitteilungen über der Grenze von 20 (NotificationBudget). */
        internal const val PRICE_OVERFLOW_ID = 11

        /** Extras: Paar einer Kurs-Mitteilung bzw. Paare und Zeilen der Sammelmeldung. */
        internal const val EXTRA_WATCH_ID = "com.cryptochecker.app.extra.WATCH_ID"
        internal const val EXTRA_OVERFLOW_IDS = "com.cryptochecker.app.extra.OVERFLOW_IDS"
        internal const val EXTRA_OVERFLOW_LINES = "com.cryptochecker.app.extra.OVERFLOW_LINES"

        /** So lange gilt eine eben gemeldete Mitteilung als offen, auch wenn der Systemdienst sie noch nicht führt. */
        internal const val RECENT_MILLIS = 5_000L

        /** Alarm-Meldung prüfen: so oft, im Abstand von [VERIFY_STEP_MILLIS] (zusammen höchstens rund 0,5 s). */
        internal const val VERIFY_ATTEMPTS = 6
        internal const val VERIFY_STEP_MILLIS = 100L

        internal const val ACTION_OPEN_NOTIFICATION = "com.cryptochecker.app.action.OPEN_FROM_NOTIFICATION"

        /** Ziel des Portfolio-Alarms (wie das Portfolio-Widget). */
        internal const val OPEN_PORTFOLIO = "portfolio"
        internal const val PORTFOLIO_REQUEST_CODE = 400_000

        /** Request-Code für [showGas] mit Ziel: Basis plus Mitteilungs-Nummer. */
        internal const val TARGET_REQUEST_BASE = 410_000
        /** Knopf «Börse öffnen» der Depeg-Meldung. */
        internal const val DEPEG_EXCHANGE_REQUEST_CODE = 420_000
        internal const val WHY_REQUEST_BASE = 500_000L
        /** Knöpfe «1 h später» bzw. «Anpassen» der Alarm-Meldung: Basis plus Alarm-Id (eigene Bereiche). */
        internal const val SNOOZE_REQUEST_BASE = 600_000_000L
        internal const val EDIT_REQUEST_BASE = 700_000_000L

        fun portfolioAlarmNotificationId(alarmId: Long): Int = NotificationIds.id(NotificationIds.Kind.PORTFOLIO_ALARM, alarmId)

        // Je Art eigener Bereich, ohne Überschneidung auch ab id 100 000 (siehe NotificationIds)
        fun priceNotificationId(watchId: Long): Int = NotificationIds.id(NotificationIds.Kind.PRICE, watchId)
        fun alarmNotificationId(alarmId: Long): Int = NotificationIds.id(NotificationIds.Kind.ALARM, alarmId)
        fun activityNotificationId(watchId: Long): Int = NotificationIds.id(NotificationIds.Kind.ACTIVITY, watchId)
    }
}
