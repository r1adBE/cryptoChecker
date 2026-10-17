package com.cryptochecker.app.notification

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.PriceFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Erstellt und entfernt alle Benachrichtigungen der App. */
@Singleton
class AppNotifier @Inject constructor(
    @param:ApplicationContext internal val context: Context,
    /** Für die Akzentfarbe der Benachrichtigungen. */
    internal val settingsRepository: SettingsRepository,
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
        return watch.id in showPrices(listOf(watch), ongoing = settings.ongoingNotifications)
    }

    /**
     * Kurs-Mitteilungen [watches] zeigen bzw. aktualisieren (ein Durchlauf oder eine Handlung):
     * höchstens [NotificationBudget.MAX_PRICE] einzeln — Android verwirft ab rund 50 offenen
     * Mitteilungen je App jede weitere still, auch Alarme —, der Rest in EINER Sammelmeldung
     * («+N weitere Kurse»). Reihenfolge von [watches] = Vorrang für freie Plätze.
     * @return Paare, deren Kurs jetzt gezeigt wird (einzeln oder in der Sammelmeldung).
     */
    @Synchronized
    fun showPrices(watches: List<WatchEntity>, ongoing: Boolean): Set<Long> {
        val posts = watches.filter { it.lastPrice != null }.distinctBy { it.id }
        if (posts.isEmpty() || !hasPermission()) return emptySet()
        val active = activeState()
        val plan = NotificationBudget.planPrices(
            shown = active?.prices?.keys?.toList().orEmpty(),
            overflowBefore = active?.overflow?.keys?.toList().orEmpty(),
            posts = posts.map { it.id },
            slots = NotificationBudget.priceSlots(active?.other ?: 0),
        )
        val byId = posts.associateBy { it.id }
        // Zuerst Platz schaffen, dann melden
        plan.demoted.forEach { cancelPriceSingle(it) }
        val shown = HashSet<Long>()
        for (id in plan.individual) {
            if (postPrice(byId.getValue(id), ongoing)) shown += id
        }
        // Zeilen: frischer Kurs, sonst die bisherige Zeile (Sammelmeldung bzw. verschobene Einzelmeldung)
        val lines = LinkedHashMap<Long, String>()
        for (id in plan.overflow) {
            val watch = byId[id]
            val price = watch?.lastPrice
            val line = if (watch != null && price != null) priceTitle(watch, price)
            else active?.overflow?.get(id) ?: active?.prices?.get(id)
            if (!line.isNullOrEmpty()) lines[id] = line
        }
        val unchanged = active != null && active.overflowShown &&
            active.overflow.entries.toList() == lines.entries.toList()
        if (unchanged || postOverflow(lines, ongoing)) {
            lines.keys.filterTo(shown) { it in byId }
        }
        return shown
    }

    fun cancelPrice(watchId: Long) = cancelPrices(listOf(watchId), shown = null)

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

    fun cancelAllPrices(watchIds: Collection<Long>) = cancelPrices(watchIds, shown = null)

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
    private fun NotificationCompat.Builder.lockScreenPrivacy(
        channelId: String,
        iconRes: Int,
        publicTitle: String,
    ): NotificationCompat.Builder =
        if (settingsRepository.cached.appLock) privateOnLockScreen(channelId, iconRes, publicTitle) else this

    // ---------------- Alarm ----------------

    /**
     * @param volumeRatio nur beim Volumen-Spike: Volumen der letzten Stunde im Vergleich zum Schnitt.
     * @param nearFire «Nahe am Hoch/Tief»: was gemeldet wird (eigener Meldungstext); null = Standardtext.
     * @return true erst, wenn die Meldung unter den offenen Mitteilungen steht ([awaitActive]) —
     *   sonst bleibt der Alarm scharf (RefreshEffects).
     */
    suspend fun showAlarm(
        watch: WatchEntity,
        alarm: AlarmEntity,
        price: Double,
        volumeRatio: Double? = null,
        nearFire: NearExtreme.Decision.Fire? = null,
        /** Funding (in %) bzw. Open-Interest-Veränderung (in %) beim Auslösen eines Funding- bzw. Open-Interest-Alarms. */
        derivativesValue: Double? = null,
        /** Funding/Open Interest nicht von der beobachteten Börse: Quelle des Richtwerts («Binance Futures»). */
        derivativesReference: String? = null,
        /**
         * Währung von [price], wenn nicht die Quote des Paars: Kursmarke mit eigener Alarm-Währung
         * ([com.cryptochecker.app.data.local.model.convertCurrency]) — [price] ist dann schon umgerechnet,
         * damit «jetzt …» in derselben Währung steht wie die Schwelle.
         */
        priceCurrency: String? = null,
    ): Boolean {
        val title = context.getString(
            R.string.notification_alarm_title,
            watch.displayName,
            watch.marketName
        )
        val body = if (derivativesValue != null) {
            // «Funding über 0,05 % — jetzt 0,061 %», «Open Interest steigt 10 % in 4 Std. — jetzt +12,3 %»
            val text = context.getString(
                R.string.notification_alarm_text,
                AlarmTexts.describe(context, alarm),
                AlarmTexts.derivativesValue(context, alarm.condition, derivativesValue)
            )
            // «… · Richtwert Binance Futures», wenn die Werte von einer anderen Börse stammen
            if (derivativesReference != null) {
                text + " · " + context.getString(R.string.futures_alarm_reference, derivativesReference)
            } else {
                text
            }
        } else if (nearFire != null) {
            // «BTC ist 1,6 % unter dem 30-Tage-Hoch (98’450 / 100’050)»
            AlarmTexts.nearExtremeText(context, alarm, watch.baseAsset, watch.quoteAsset, price, nearFire)
        } else if (volumeRatio != null) {
            // «Volumen 4.2× normal in der letzten Stunde»
            context.getString(R.string.notification_volume_spike_text, AlarmTexts.factor(volumeRatio))
        } else {
            context.getString(
                R.string.notification_alarm_text,
                AlarmTexts.describe(context, alarm),
                PriceFormat.priceWithCurrency(price, priceCurrency ?: watch.quoteAsset)
            )
        }

        // Nachtruhe: Alarme lösen dann nicht aus (QuietHours.holdsAlarms, RefreshEffects); der
        // lautlose Kanal bleibt nur als Rückfall, falls die Nachtruhe genau zwischen Prüfen und
        // Melden beginnt. Sonst der Kanal des «Alarm-Signals»; die Schalter Ton/Vibration des Alarms
        // nehmen davon höchstens etwas weg (Android 8+ beachtet nur den Kanal).
        val quiet = isQuietNow()
        val channelId = pairAlarmChannel(alarm, quiet)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .appBadge()
            .setGroup(Group.ALARMS.key)
            .setContentIntent(openAppIntent(watch.id))
        // «Was bewegt sich mit?» (Kurzform, Aktionsknöpfe sind schmal): öffnet die App direkt mit dem Blatt des Paars —
        // nur für gehandelte Paare
        if (!watch.isNotTraded) {
            builder.addAction(0, context.getString(R.string.watch_action_why_short), openWhyIntent(watch.id))
        }
        // Mit Portfolio-Sperre auf dem Sperrbildschirm nur «Alarm» (wie Kurs-Mitteilungen und Portfolio-Alarme)
        builder.lockScreenPrivacy(channelId, R.drawable.ic_stat_logo, context.getString(R.string.watch_action_alarm))

        // «Lautlos» kommt über den Kanal ohne Ton und Vibration und erscheint trotzdem
        // oben — setSilent würde auch das unterdrücken; nur die Nachtruhe bleibt ganz still.
        if (quiet) builder.setSilent(true)

        val id = alarmNotificationId(alarm.id)
        // Alarme haben Vorrang vor Kurs-Mitteilungen (Grenze von rund 50 offenen Mitteilungen je App)
        makeRoomForAlarm(id)
        if (!notifyInGroup(id, builder.build(), Group.ALARMS)) return false
        return awaitActive(id)
    }

    /** Kanal eines Paar-Alarms: in der Nachtruhe der lautlose, sonst der des «Alarm-Signals». */
    private fun pairAlarmChannel(alarm: AlarmEntity, quiet: Boolean): String =
        if (quiet) NotificationChannels.ensureQuietAlarmChannel(context)
        else alarmChannel(AlarmSignal.effective(settingsRepository.cached.alarmSignal, alarm.sound, alarm.vibrate))

    /**
     * Käme die Meldung dieses Alarms jetzt an? Erlaubnis (Android 13+), App-Benachrichtigungen an
     * und der Kanal, über den [showAlarm] meldet, nicht stummgeschaltet. Ohne: einmalige Alarme
     * bleiben scharf (RefreshEffects), statt sich unbemerkt abzuschalten.
     */
    fun canShowAlarm(alarm: AlarmEntity): Boolean {
        if (!hasPermission() || !manager.areNotificationsEnabled()) return false
        return runCatching {
            val channel = context.getSystemService(android.app.NotificationManager::class.java)
                ?.getNotificationChannel(pairAlarmChannel(alarm, isQuietNow()))
            channel == null || channel.importance != android.app.NotificationManager.IMPORTANCE_NONE
        }.getOrDefault(true)
    }

    /** Kann eine Portfolio-Alarm-Meldung gerade ankommen (Erlaubnis, Mitteilungen an, Kanal nicht stumm)? */
    fun canShowPortfolioAlarm(): Boolean {
        if (!hasPermission() || !manager.areNotificationsEnabled()) return false
        return runCatching {
            val channel = context.getSystemService(android.app.NotificationManager::class.java)
                ?.getNotificationChannel(alarmChannel(settingsRepository.cached.alarmSignal))
            channel == null || channel.importance != android.app.NotificationManager.IMPORTANCE_NONE
        }.getOrDefault(true)
    }

    /**
     * Alarm «Portfolio-Wert» ([measured] = Gesamtwert in der Alarmwährung bzw. Veränderung in
     * Prozent). Gleicher Kanal wie die Paar-Alarme («Alarm-Signal»; in der Nachtruhe löst
     * PortfolioAlarmChecker gar nicht aus, lautlos nur als Rückfall an der Grenze); Tipp
     * öffnet den Portfolio-Tab. «Beträge verbergen» gilt auch hier; mit Portfolio-Sperre zeigt
     * der Sperrbildschirm nur den Titel.
     * @return true erst, wenn die Meldung unter den offenen Mitteilungen steht ([awaitActive]) —
     *   sonst bleibt der Alarm scharf (PortfolioAlarmChecker), wie bei [showAlarm].
     */
    suspend fun showPortfolioAlarm(alarm: PortfolioAlarmEntity, measured: Double): Boolean {
        val settings = settingsRepository.cached
        // Mit Portfolio-Sperre auch ohne «Beträge verbergen» keine Beträge (Sperrbildschirm) — wie iOS
        val hidden = settings.hidePortfolioAmounts || settings.appLock
        val title = context.getString(R.string.notification_portfolio_alarm_title)
        val body = context.getString(
            R.string.notification_portfolio_alarm_text,
            PortfolioAlarmTexts.sentence(context, alarm, settings.changeBasis.storage, hidden),
            PortfolioAlarmTexts.measured(alarm, measured, hidden)
        )
        val quiet = isQuietNow()
        val channelId = if (quiet) NotificationChannels.ensureQuietAlarmChannel(context)
        else alarmChannel(settings.alarmSignal)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(settings.accentColor.seed)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .appBadge()
            .setGroup(Group.ALARMS.key)
            .setContentIntent(openTargetIntent(OPEN_PORTFOLIO, PORTFOLIO_REQUEST_CODE))
        if (settings.appLock) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.drawable.ic_stat_logo)
                        .setColor(settings.accentColor.seed)
                        .setContentTitle(title)
                        .build()
                )
        }
        if (quiet) builder.setSilent(true)
        val id = portfolioAlarmNotificationId(alarm.id)
        makeRoomForAlarm(id)
        if (!notifyInGroup(id, builder.build(), Group.ALARMS)) return false
        return awaitActive(id)
    }

    /**
     * Kanal des «Alarm-Signals» [signal] (legt ihn bei Bedarf an). Gilt für Kursalarme,
     * Volumen-Spike und «Nahe am Hoch/Tief»; Aktivität, Gas, Fear & Greed und Wirtschaftstermine haben eigene Kanäle.
     */
    fun alarmChannel(signal: AlarmSignal): String {
        val settings = settingsRepository.cached
        return NotificationChannels.ensureAlarmChannel(context, signal, settings.alarmChannelVersion, settings.alarmSoundUri)
    }

    // ---------------- Test-Alarm ----------------

    /**
     * Dürfen Alarme erscheinen? Erlaubnis (Android 13+), App-Benachrichtigungen
     * an und der Alarm-Kanal nicht stummgeschaltet.
     */
    fun alarmsAllowed(): Boolean {
        if (!hasPermission() || !manager.areNotificationsEnabled()) return false
        val channelId = alarmChannel(settingsRepository.cached.alarmSignal)
        val channel = context.getSystemService(android.app.NotificationManager::class.java)
            ?.getNotificationChannel(channelId)
        return channel == null || channel.importance != android.app.NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Beispiel-Alarm über denselben Kanal wie ein Kursalarm (gewähltes «Alarm-Signal»),
     * aber ohne Nachtruhe — es ist ein Test.
     */
    fun showTestAlarm() {
        val settings = settingsRepository.cached
        val channelId = alarmChannel(settings.alarmSignal)
        val text = context.getString(R.string.alarm_test_text)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(settings.accentColor.seed)
            .setContentTitle(context.getString(R.string.alarm_test_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .appBadge()
            .setContentIntent(openAppIntent(null))
            .build()
        notify(TEST_ALARM_NOTIFICATION_ID, notification)
    }

    // ---------------- Ungewöhnliche Aktivität ----------------

    /**
     * «SOL/USDT · Binance» mit dem wichtigsten Grund als Text; eine Meldung je Paar.
     * @return true, wenn die Meldung ans System ging (ActivityMonitor merkt sie sonst nicht als gemeldet).
     */
    fun showActivity(watch: WatchEntity, signal: ActivitySignal): Boolean {
        val text = ActivityTexts.signal(context, signal)
        val quiet = isQuietNow()
        val notification = NotificationCompat.Builder(context, marketOrQuietChannel(quiet))
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(context.getString(R.string.notification_activity_title, watch.displayName, watch.marketName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .appBadge()
            .setSilent(quiet)
            .setContentIntent(openAppIntent(watch.id))
            .lockScreenPrivacy(marketOrQuietChannel(quiet), R.drawable.ic_stat_logo, context.getString(R.string.app_name))
            .build()
        return notifyChecked(activityNotificationId(watch.id), notification)
    }

    fun cancelActivity(watchId: Long) = manager.cancel(activityNotificationId(watchId))

    // ---------------- Fear & Greed ----------------

    /**
     * Fear & Greed hat die eingestellte Grenze unter- bzw. überschritten. In der Nachtruhe
     * lautlos über den stillen Kanal — wie Aktivität, Gas und Wirtschaftstermine.
     * @return true, wenn die Meldung abgesetzt wurde (ZoneCheckWorker merkt den Stand erst dann).
     */
    fun showFearGreed(value: Int, below: Int? = null, above: Int? = null): Boolean {
        val text = when {
            below != null -> context.getString(R.string.notification_fng_below, below)
            else -> context.getString(R.string.notification_fng_above, above ?: 0)
        }
        val quiet = isQuietNow()
        val notification = NotificationCompat.Builder(context, marketOrQuietChannel(quiet))
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(context.getString(R.string.notification_fng_title, value))
            .setContentText(text)
            .setAutoCancel(true)
            .appBadge()
            .setSilent(quiet)
            .setContentIntent(openAppIntent(null))
            .lockScreenPrivacy(marketOrQuietChannel(quiet), R.drawable.ic_stat_logo, context.getString(R.string.app_name))
            .build()
        return notifyChecked(FNG_NOTIFICATION_ID, notification)
    }

    /**
     * Eigene Grenze einer Bitcoin-Kennzahl erreicht ([com.cryptochecker.app.domain.market.IndicatorAlertKind]): Titel
     * «Mayer Multiple: 2.43×», Text «Über deiner Grenze 2.40× – Messwert, keine Empfehlung.» Eine Meldung je Art.
     */
    fun showIndicatorAlert(kind: com.cryptochecker.app.domain.market.IndicatorAlertKind, name: String, value: String, threshold: String): Boolean {
        val quiet = isQuietNow()
        val notification = NotificationCompat.Builder(context, marketOrQuietChannel(quiet))
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(context.getString(R.string.notification_indicator_title, name, value))
            .setContentText(
                context.getString(if (kind.above) R.string.notification_indicator_above else R.string.notification_indicator_below, threshold)
            )
            .setAutoCancel(true)
            .appBadge()
            .setSilent(quiet)
            .setContentIntent(openAppIntent(null))
            .lockScreenPrivacy(marketOrQuietChannel(quiet), R.drawable.ic_stat_logo, context.getString(R.string.app_name))
            .build()
        return notifyChecked(INDICATOR_NOTIFICATION_BASE_ID + kind.ordinal, notification)
    }

    /**
     * Käme eine Marktmeldung (Fear & Greed) jetzt an? Erlaubnis (Android 13+),
     * App-Benachrichtigungen an und der Kanal, über den sie gerade käme (Markt bzw. in der
     * Nachtruhe der lautlose), nicht stummgeschaltet — wie [canShowAlarm]. Ohne: ZoneCheckWorker
     * behält den alten Stand und meldet den Wechsel, sobald die Meldung wieder ankommt.
     */
    fun canShowMarketNotice(): Boolean {
        if (!hasPermission() || !manager.areNotificationsEnabled()) return false
        return runCatching {
            val channel = context.getSystemService(android.app.NotificationManager::class.java)
                ?.getNotificationChannel(marketOrQuietChannel(isQuietNow()))
            channel == null || channel.importance != android.app.NotificationManager.IMPORTANCE_NONE
        }.getOrDefault(true)
    }

    // ---------------- Gas ----------------

    /**
     * Netzwerkgebühr ist unter die eingestellte Grenze gefallen (#167).
     * @return true, wenn die Meldung ans System ging (GasAlertChecker hält den Alarm sonst scharf).
     */
    fun showGas(id: Int, title: String, text: String, openTarget: String? = null): Boolean {
        val quiet = isQuietNow()
        val notification = NotificationCompat.Builder(context, marketOrQuietChannel(quiet))
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .appBadge()
            .setSilent(quiet)
            // Mit Ziel (z. B. «Wirtschaftstermine» → Markt-Tab), sonst einfach die App
            .setContentIntent(openTarget?.let { openTargetIntent(it, TARGET_REQUEST_BASE + id) } ?: openAppIntent(null))
            .build()
        return notifyChecked(id, notification)
    }

    // ---------------- USDT-Depeg ----------------

    /**
     * Käme die Depeg-Meldung jetzt an? Gleicher Kanal wie Kursalarme («Alarm-Signal») — wie
     * [canShowPortfolioAlarm]. Ohne: DepegAlarmChecker hält den Alarm scharf.
     */
    fun canShowDepegAlarm(): Boolean = canShowPortfolioAlarm()

    /**
     * USDT weicht um die Schwelle von 1 USD ab: Kanal der Kursalarme (in der Nachtruhe löst der
     * Alarm gar nicht aus, lautlos nur als Rückfall an der Grenze). Knopf «Börse öffnen» öffnet die
     * Seite USDC/USDT der Hauptbörse ([exchangeUrl], https) — kein Handel in der App.
     * @return true erst, wenn die Meldung unter den offenen Mitteilungen steht ([awaitActive]).
     */
    suspend fun showDepeg(title: String, text: String, exchangeUrl: String): Boolean {
        val settings = settingsRepository.cached
        val quiet = isQuietNow()
        val channelId = if (quiet) NotificationChannels.ensureQuietAlarmChannel(context)
        else alarmChannel(settings.alarmSignal)
        val openExchange = PendingIntent.getActivity(
            context,
            DEPEG_EXCHANGE_REQUEST_CODE,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(exchangeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(settings.accentColor.seed)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .appBadge()
            .setSilent(quiet)
            .setGroup(Group.ALARMS.key)
            .setContentIntent(openAppIntent(null))
            .addAction(0, context.getString(R.string.notification_depeg_action_exchange), openExchange)
            .build()
        makeRoomForAlarm(DEPEG_NOTIFICATION_ID)
        if (!notifyInGroup(DEPEG_NOTIFICATION_ID, notification, Group.ALARMS)) return false
        return awaitActive(DEPEG_NOTIFICATION_ID)
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

    private fun marketOrQuietChannel(quiet: Boolean): String =
        if (quiet) NotificationChannels.ensureQuietAlarmChannel(context) else NotificationChannels.MARKET

    // ---------------- Aktualisierung ----------------

    /**
     * Kurzzeitige Meldung, solange eine angestoßene Aktualisierung im
     * Hintergrund läuft. Nur bis Android 11 sichtbar — dort braucht
     * WorkManager dafür einen Vordergrunddienst.
     */
    fun refreshNotification(): Notification =
        NotificationCompat.Builder(context, NotificationChannels.SERVICE)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(context.getString(R.string.notification_refreshing))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    /**
     * «Zahl am App-Symbol»: Jede Alarm- bzw. Marktmeldung zählt 1 (der Startbildschirm zählt die
     * offenen Mitteilungen zusammen; Zahl oder Punkt entscheidet er). Aus: Hinweis «kein
     * Kennzeichen» — ganz aus nur über die Systemeinstellung der App.
     */
    private fun NotificationCompat.Builder.appBadge(): NotificationCompat.Builder =
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
        // 1 war die Meldung des früheren Live-Dienstes «Immer kurz» (vor 1.0 entfernt; nicht wiederverwenden)
        const val REFRESH_NOTIFICATION_ID = 2
        // 3 war die frühere Meldung «Marktphase gewechselt» (nicht wiederverwenden)
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
        private const val OPEN_PORTFOLIO = "portfolio"
        private const val PORTFOLIO_REQUEST_CODE = 400_000

        /** Request-Code für [showGas] mit Ziel: Basis plus Mitteilungs-Nummer. */
        private const val TARGET_REQUEST_BASE = 410_000
        /** Knopf «Börse öffnen» der Depeg-Meldung. */
        private const val DEPEG_EXCHANGE_REQUEST_CODE = 420_000
        internal const val WHY_REQUEST_BASE = 500_000L

        fun portfolioAlarmNotificationId(alarmId: Long): Int = NotificationIds.id(NotificationIds.Kind.PORTFOLIO_ALARM, alarmId)

        // Je Art eigener Bereich, ohne Überschneidung auch ab id 100 000 (siehe NotificationIds)
        fun priceNotificationId(watchId: Long): Int = NotificationIds.id(NotificationIds.Kind.PRICE, watchId)
        fun alarmNotificationId(alarmId: Long): Int = NotificationIds.id(NotificationIds.Kind.ALARM, alarmId)
        fun activityNotificationId(watchId: Long): Int = NotificationIds.id(NotificationIds.Kind.ACTIVITY, watchId)
    }
}
