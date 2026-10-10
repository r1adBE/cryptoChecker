package com.cryptochecker.app.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.util.PriceFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Erstellt und entfernt alle Benachrichtigungen der App. */
@Singleton
class AppNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    /** Für die Akzentfarbe der Benachrichtigungen. */
    private val settingsRepository: SettingsRepository,
) {
    private val manager get() = NotificationManagerCompat.from(context)

    fun hasPermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) true
        else ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    // ---------------- Kurs ----------------

    /**
     * Kurs-Benachrichtigung. Die zweite Zeile bezieht sich auf die vorige
     * Meldung, nicht auf die vorige Abfrage — sonst stünde dort bei einer
     * Melde-Schwelle von 5 % dauernd eine Veränderung von 0,1 %.
     */
    fun showPrice(watch: WatchEntity, ongoing: Boolean) {
        val price = watch.lastPrice ?: return

        val title = context.getString(
            R.string.notification_price_title,
            watch.baseAsset,
            PriceFormat.price(price),
            watch.quoteAsset,
            watch.marketName
        )

        val text = buildComparisonText(watch, price)

        val notification = NotificationCompat.Builder(context, NotificationChannels.PRICES)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(PriceFormat.time(watch.lastUpdate))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent(watch.id))
            .build()

        notify(priceNotificationId(watch.id), notification)
    }

    /** „-4.875%, from 0.000759 USDT (7d ago)" — oder ein Hinweis bei der ersten Meldung. */
    private fun buildComparisonText(watch: WatchEntity, price: Double): String {
        val reference = watch.notifiedPrice
        if (reference == null || reference <= 0.0) {
            return context.getString(R.string.notification_price_first)
        }

        val changePercent = (price - reference) / reference * 100.0
        val age = PriceFormat.age(watch.notifiedAt)
        val referenceText = PriceFormat.priceWithCurrency(reference, watch.quoteAsset)

        return if (age == null) {
            context.getString(
                R.string.notification_price_change_no_age,
                PriceFormat.changePercentDetailed(changePercent),
                referenceText
            )
        } else {
            context.getString(
                R.string.notification_price_change,
                PriceFormat.changePercentDetailed(changePercent),
                referenceText,
                age
            )
        }
    }

    fun cancelPrice(watchId: Long) = manager.cancel(priceNotificationId(watchId))

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
    fun cancelPriceIfShown(watchId: Long, shown: Set<Int>?) {
        if (shown == null || priceNotificationId(watchId) in shown) cancelPrice(watchId)
    }

    /**
     * Nach einem Wechsel der Akzentfarbe: sichtbare Kurs-Meldungen und die
     * Dienst-Meldung neu zeichnen, damit sie die neue Farbe übernehmen. Sonst
     * blieben sie in der alten Farbe, bis sich der Kurs genug bewegt.
     */
    fun refreshColors(watches: List<WatchEntity>, ongoing: Boolean) {
        val shown = runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)
                ?.activeNotifications?.map { it.id }?.toSet()
        }.getOrNull().orEmpty()
        watches.filter { priceNotificationId(it.id) in shown }
            .forEach { showPrice(it, ongoing) }
        val serviceText = lastServiceText
        if (serviceText != null && SERVICE_NOTIFICATION_ID in shown) {
            notify(SERVICE_NOTIFICATION_ID, serviceNotification(serviceText))
        }
    }

    /** Zuletzt gezeigter Text der Dienst-Meldung (für [refreshColors]). */
    @Volatile
    private var lastServiceText: String? = null

    fun cancelAllPrices(watchIds: Collection<Long>) = watchIds.forEach { cancelPrice(it) }

    // ---------------- Alarm ----------------

    /** @param volumeRatio nur beim Volumen-Spike: Volumen der letzten Stunde im Vergleich zum Schnitt. */
    /** @param nearFire «Nahe am Hoch/Tief»: was gemeldet wird (eigener Meldungstext); null = Standardtext. */
    fun showAlarm(
        watch: WatchEntity,
        alarm: AlarmEntity,
        price: Double,
        volumeRatio: Double? = null,
        nearFire: NearExtreme.Decision.Fire? = null,
        /** Funding (in %) bzw. Open-Interest-Veränderung (in %) beim Auslösen eines Funding- bzw. Open-Interest-Alarms. */
        derivativesValue: Double? = null,
    ) {
        val title = context.getString(
            R.string.notification_alarm_title,
            watch.displayName,
            watch.marketName
        )
        val body = if (derivativesValue != null) {
            // «Funding über 0,05 % — jetzt 0,061 %», «Open Interest steigt 10 % in 4 Std. — jetzt +12,3 %»
            context.getString(
                R.string.notification_alarm_text,
                AlarmTexts.describe(context, alarm),
                AlarmTexts.derivativesValue(context, alarm.condition, derivativesValue)
            )
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
                PriceFormat.priceWithCurrency(price, watch.quoteAsset)
            )
        }

        // Nachtruhe: ausgelöst wird wie sonst, nur lautlos über den eigenen Kanal.
        // Sonst der Kanal des «Alarm-Signals»; die Schalter Ton/Vibration des Alarms
        // nehmen davon höchstens etwas weg (Android 8+ beachtet nur den Kanal).
        val quiet = isQuietNow()
        val channelId = pairAlarmChannel(alarm, quiet)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .appBadge()
            .setContentIntent(openAppIntent(watch.id))
        // «Warum?»: öffnet die App direkt mit «Warum bewegt sich das?» des Paars — nur für gehandelte Paare
        if (!watch.isNotTraded) {
            builder.addAction(0, context.getString(R.string.watch_action_why), openWhyIntent(watch.id))
        }

        // «Lautlos» kommt über den Kanal ohne Ton und Vibration und erscheint trotzdem
        // oben — setSilent würde auch das unterdrücken; nur die Nachtruhe bleibt ganz still.
        if (quiet) builder.setSilent(true)

        notify(alarmNotificationId(alarm.id), builder.build())
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

    /**
     * Alarm «Portfolio-Wert» ([measured] = Gesamtwert in der Alarmwährung bzw. Veränderung in
     * Prozent). Gleicher Kanal wie die Paar-Alarme («Alarm-Signal», Nachtruhe lautlos); Tipp
     * öffnet den Portfolio-Tab. «Beträge verbergen» gilt auch hier; mit Portfolio-Sperre zeigt
     * der Sperrbildschirm nur den Titel.
     */
    fun showPortfolioAlarm(alarm: PortfolioAlarmEntity, measured: Double) {
        val settings = settingsRepository.cached
        val hidden = settings.hidePortfolioAmounts
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
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(settings.accentColor.seed)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .appBadge()
            .setContentIntent(openTargetIntent(OPEN_PORTFOLIO, PORTFOLIO_REQUEST_CODE))
        if (settings.appLock) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.drawable.ic_stat_alarm)
                        .setColor(settings.accentColor.seed)
                        .setContentTitle(title)
                        .build()
                )
        }
        if (quiet) builder.setSilent(true)
        notify(portfolioAlarmNotificationId(alarm.id), builder.build())
    }

    /**
     * Kanal des «Alarm-Signals» [signal] (legt ihn bei Bedarf an). Gilt für Kursalarme,
     * Volumen-Spike und «Nahe am Hoch/Tief»; Aktivität, Gas und Marktphase haben eigene Kanäle.
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
            .setSmallIcon(R.drawable.ic_stat_alarm)
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

    /** «SOL/USDT · Binance» mit dem wichtigsten Grund als Text; eine Meldung je Paar. */
    fun showActivity(watch: WatchEntity, signal: ActivitySignal) {
        val text = ActivityTexts.signal(context, signal)
        val quiet = isQuietNow()
        val notification = NotificationCompat.Builder(context, marketOrQuietChannel(quiet))
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(context.getString(R.string.notification_activity_title, watch.displayName, watch.marketName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .appBadge()
            .setSilent(quiet)
            .setContentIntent(openAppIntent(watch.id))
            .build()
        notify(activityNotificationId(watch.id), notification)
    }

    fun cancelActivity(watchId: Long) = manager.cancel(activityNotificationId(watchId))

    // ---------------- Marktphase ----------------

    /** Die Bitcoin-Marktphase hat gewechselt, z. B. von Neutral zu Bull. */
    fun showZoneChange(from: Int, to: Int) {
        val toLabel = context.getString(to)
        val text = context.getString(R.string.notification_zone_text, context.getString(from), toLabel)
        val notification = NotificationCompat.Builder(context, NotificationChannels.MARKET)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(context.getString(R.string.notification_zone_title, toLabel))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .appBadge()
            .setContentIntent(openAppIntent(null))
            .build()
        notify(ZONE_NOTIFICATION_ID, notification)
    }

    /** Fear & Greed hat die eingestellte Grenze unter- bzw. überschritten. */
    fun showFearGreed(value: Int, below: Int? = null, above: Int? = null) {
        val text = when {
            below != null -> context.getString(R.string.notification_fng_below, below)
            else -> context.getString(R.string.notification_fng_above, above ?: 0)
        }
        val notification = NotificationCompat.Builder(context, NotificationChannels.MARKET)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(context.getString(R.string.notification_fng_title, value))
            .setContentText(text)
            .setAutoCancel(true)
            .appBadge()
            .setContentIntent(openAppIntent(null))
            .build()
        notify(FNG_NOTIFICATION_ID, notification)
    }

    // ---------------- Gas ----------------

    /** Netzwerkgebühr ist unter die eingestellte Grenze gefallen (#167). */
    fun showGas(id: Int, title: String, text: String) {
        val quiet = isQuietNow()
        val notification = NotificationCompat.Builder(context, marketOrQuietChannel(quiet))
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .appBadge()
            .setSilent(quiet)
            .setContentIntent(openAppIntent(null))
            .build()
        notify(id, notification)
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

    // ---------------- Dienst ----------------

    fun serviceNotification(contentText: String): Notification =
        NotificationCompat.Builder(context, NotificationChannels.SERVICE)
            .also { lastServiceText = contentText }
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(settingsRepository.cached.accentColor.seed)
            .setContentTitle(context.getString(R.string.notification_service_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent(null))
            .build()

    /**
     * Kurzzeitige Meldung, solange eine angestoßene Aktualisierung im
     * Hintergrund läuft. Nur bis Android 11 sichtbar — dort braucht
     * WorkManager dafür einen Vordergrunddienst.
     */
    fun refreshNotification(): Notification =
        NotificationCompat.Builder(context, NotificationChannels.SERVICE)
            .setSmallIcon(R.drawable.ic_stat_alarm)
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

    // Erlaubnis prüft hasPermission() direkt davor (Android 13+); SecurityException fängt runCatching
    @SuppressLint("MissingPermission")
    private fun notify(id: Int, notification: Notification) {
        if (!hasPermission()) {
            Timber.d("Benachrichtigung unterdrückt: keine Berechtigung")
            return
        }
        runCatching { manager.notify(id, notification) }
            .onFailure { Timber.w(it, "Benachrichtigung fehlgeschlagen") }
    }

    private fun openAppIntent(watchId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Eigene Action: Extras zählen nicht zur Identität eines PendingIntent; ohne sie
            // teilten sich Mitteilungen und Widgets gleiche Request-Codes (0, 1, 2 …)
            action = ACTION_OPEN_NOTIFICATION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (watchId != null) putExtra(MainActivity.EXTRA_WATCH_ID, watchId)
        }
        return PendingIntent.getActivity(
            context,
            (watchId ?: 0L).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Öffnet die App mit dem Ziel [target] (wie die App-Verknüpfungen, siehe MainActivity.EXTRA_OPEN). */
    private fun openTargetIntent(target: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Eigene Action je Ziel: sonst teilten sich verschiedene Ziele mit gleichem Request-Code ein PendingIntent
            action = "$ACTION_OPEN_NOTIFICATION.$target"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN, target)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** «Warum?» aus einem Alarm: App öffnen, Merkliste, «Warum bewegt sich das?» des Paars. */
    private fun openWhyIntent(watchId: Long): PendingIntent =
        openTargetIntent(MainActivity.openWhyTarget(watchId), (WHY_REQUEST_BASE + watchId).toInt())

    companion object {
        const val SERVICE_NOTIFICATION_ID = 1
        const val REFRESH_NOTIFICATION_ID = 2
        const val ZONE_NOTIFICATION_ID = 3
        const val FNG_NOTIFICATION_ID = 4
        const val GAS_ETH_NOTIFICATION_ID = 5
        const val GAS_BTC_NOTIFICATION_ID = 6
        const val TEST_ALARM_NOTIFICATION_ID = 7

        private const val ACTION_OPEN_NOTIFICATION = "com.cryptochecker.app.action.OPEN_FROM_NOTIFICATION"

        /** Ziel des Portfolio-Alarms (wie das Portfolio-Widget). */
        private const val OPEN_PORTFOLIO = "portfolio"
        private const val PORTFOLIO_REQUEST_CODE = 400_000
        private const val WHY_REQUEST_BASE = 500_000L

        fun portfolioAlarmNotificationId(alarmId: Long): Int = (400_000 + alarmId).toInt()

        fun priceNotificationId(watchId: Long): Int = (100_000 + watchId).toInt()
        fun alarmNotificationId(alarmId: Long): Int = (200_000 + alarmId).toInt()
        fun activityNotificationId(watchId: Long): Int = (300_000 + watchId).toInt()
    }
}
