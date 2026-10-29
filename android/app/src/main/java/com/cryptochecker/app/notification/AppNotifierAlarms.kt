// Alarm-Mitteilungen (Teil von `AppNotifier`): Paar-Alarme, Portfolio-Alarme, USDT-Depeg, der Kanal des
// «Alarm-Signals», die Prüfung, ob eine Alarm-Meldung ankäme, und der Test-Alarm.
// Eigene Datei, weil Alarme denselben Kanal und dieselbe Bestätigung ([awaitActive]) teilen;
// Erweiterungsfunktionen, der Zustand bleibt in der Klasse.
package com.cryptochecker.app.notification

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.domain.alarm.AlarmTextNote
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.notification.AppNotifier.Companion.DEPEG_EXCHANGE_REQUEST_CODE
import com.cryptochecker.app.notification.AppNotifier.Companion.DEPEG_NOTIFICATION_ID
import com.cryptochecker.app.notification.AppNotifier.Companion.OPEN_PORTFOLIO
import com.cryptochecker.app.notification.AppNotifier.Companion.PORTFOLIO_REQUEST_CODE
import com.cryptochecker.app.notification.AppNotifier.Companion.TEST_ALARM_NOTIFICATION_ID
import com.cryptochecker.app.notification.AppNotifier.Companion.alarmNotificationId
import com.cryptochecker.app.notification.AppNotifier.Companion.portfolioAlarmNotificationId
import com.cryptochecker.app.notification.AppNotifier.Group

/**
 * @param volumeRatio nur beim Volumen-Spike: Volumen der letzten Stunde im Vergleich zum Schnitt.
 * @param nearFire «Nahe am Hoch/Tief»: was gemeldet wird (eigener Meldungstext); null = Standardtext.
 * @return true erst, wenn die Meldung unter den offenen Mitteilungen steht ([awaitActive]) —
 *   sonst bleibt der Alarm scharf (RefreshEffects).
 */
suspend fun AppNotifier.showAlarm(
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
    /** Zusatz am Text: Börse fremder Kerzen («Binance (Kerzen)») bzw. «USDT = 1 angenommen». */
    textNote: AlarmTextNote? = null,
): Boolean {
    val title = context.getString(
        R.string.notification_alarm_title,
        watch.displayName,
        watch.exchangeName
    )
    val baseBody = if (derivativesValue != null) {
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

    val body = AlarmTextNote.join(
        baseBody,
        listOf(
            textNote?.candleSource?.let { context.getString(R.string.why_src_candles, it) },
            if (textNote?.usdtAssumed == true) context.getString(R.string.portfolio_usdt_assumed) else null,
        ),
    )

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
    // «1 h später» und «Anpassen» (AlarmNotificationActions.kt); Coin-Logo nur, wenn es schon auf dem Gerät liegt
    addAlarmActions(builder, watch, alarm)
    alarmLargeIcon(watch)?.let { builder.setLargeIcon(it) }
    // Mit Portfolio-Sperre auf dem Sperrbildschirm nur «Alarm» (wie Kurs-Mitteilungen und Portfolio-Alarme)
    builder.lockScreenPrivacy(channelId, R.drawable.ic_stat_logo, context.getString(R.string.watch_action_alarm))

    // «Lautlos» kommt über den Kanal ohne Ton und Vibration und erscheint trotzdem
    // oben — setSilent würde auch das unterdrücken; nur die Nachtruhe bleibt ganz still.
    if (quiet) builder.setSilent(true)

    val id = alarmNotificationId(alarm.id)
    // Alarme haben Vorrang vor Kurs-Mitteilungen (Grenze von rund 50 offenen Mitteilungen je App)
    makeRoomForAlarm(id)
    // Zeit vor dem Melden: nur eine Meldung ab da bestätigt den Alarm (awaitActive)
    val postedAt = System.currentTimeMillis()
    if (!notifyInGroup(id, builder.build(), Group.ALARMS)) return false
    return awaitActive(id, postedAt)
}

/** Kanal eines Paar-Alarms: in der Nachtruhe der lautlose, sonst der des «Alarm-Signals». */
private fun AppNotifier.pairAlarmChannel(alarm: AlarmEntity, quiet: Boolean): String = channelIdFor(
    AppNotifierDeliveryPlan.alarmChannel(quiet),
    AlarmSignal.effective(settingsRepository.cached.alarmSignal, alarm.sound, alarm.vibrate),
)

/**
 * Käme die Meldung dieses Alarms jetzt an? Erlaubnis (Android 13+), App-Benachrichtigungen an
 * und der Kanal, über den [showAlarm] meldet, nicht stummgeschaltet. Ohne: einmalige Alarme
 * bleiben scharf (RefreshEffects), statt sich unbemerkt abzuschalten.
 */
fun AppNotifier.canShowAlarm(alarm: AlarmEntity): Boolean {
    if (!hasPermission() || !manager.areNotificationsEnabled()) return false
    return runCatching { channelDelivers(pairAlarmChannel(alarm, isQuietNow())) }.getOrDefault(true)
}

/** Kann eine Portfolio-Alarm-Meldung gerade ankommen (Erlaubnis, Mitteilungen an, Kanal nicht stumm)? */
fun AppNotifier.canShowPortfolioAlarm(): Boolean {
    if (!hasPermission() || !manager.areNotificationsEnabled()) return false
    return runCatching { channelDelivers(alarmChannel(settingsRepository.cached.alarmSignal)) }.getOrDefault(true)
}

/**
 * Alarm «Portfolio-Wert» ([measured] = Gesamtwert in der Alarmwährung bzw. Veränderung in
 * Prozent). Gleicher Kanal wie die Paar-Alarme («Alarm-Signal»; in der Nachtruhe löst
 * PortfolioAlarmChecker gar nicht aus, lautlos nur als Rückfall an der Grenze); Tipp
 * öffnet den Portfolio-Tab. «Beträge verbergen» gilt auch hier; mit Portfolio-Sperre steht wie
 * auf iOS nur ein neutraler Satz ohne Schwelle, Beträge und Prozente da.
 * @return true erst, wenn die Meldung unter den offenen Mitteilungen steht ([awaitActive]) —
 *   sonst bleibt der Alarm scharf (PortfolioAlarmChecker), wie bei [showAlarm].
 */
suspend fun AppNotifier.showPortfolioAlarm(alarm: PortfolioAlarmEntity, measured: Double, usdtAssumed: Boolean = false): Boolean {
    val settings = settingsRepository.cached
    val title = context.getString(R.string.notification_portfolio_alarm_title)
    val locked = context.getString(R.string.notification_portfolio_alarm_locked)
    val body = if (settings.appLock) {
        locked
    } else {
        // Alarm in anderer Währung, umgerechnet mit USDT = 1: Vermerk dahinter ([PortfolioAlarmLogic.usdtAssumed])
        AlarmTextNote.join(
            context.getString(
                R.string.notification_portfolio_alarm_text,
                PortfolioAlarmTexts.sentence(context, alarm, settings.changeBasis.storage, settings.hidePortfolioAmounts),
                PortfolioAlarmTexts.measured(alarm, measured, settings.hidePortfolioAmounts)
            ),
            listOf(if (usdtAssumed) context.getString(R.string.portfolio_usdt_assumed) else null),
        )
    }
    val quiet = isQuietNow()
    val channelId = channelIdFor(AppNotifierDeliveryPlan.alarmChannel(quiet), settings.alarmSignal)
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
                    .setContentText(locked)
                    .build()
            )
    }
    if (quiet) builder.setSilent(true)
    val id = portfolioAlarmNotificationId(alarm.id)
    makeRoomForAlarm(id)
    // Zeit vor dem Melden: nur eine Meldung ab da bestätigt den Alarm (awaitActive)
    val postedAt = System.currentTimeMillis()
    if (!notifyInGroup(id, builder.build(), Group.ALARMS)) return false
    return awaitActive(id, postedAt)
}

/**
 * Kanal des «Alarm-Signals» [signal] (legt ihn bei Bedarf an). Gilt für Kursalarme,
 * Volumen-Spike und «Nahe am Hoch/Tief»; Aktivität, Gas, Fear & Greed und Wirtschaftstermine haben eigene Kanäle.
 */
fun AppNotifier.alarmChannel(signal: AlarmSignal): String {
    val settings = settingsRepository.cached
    return NotificationChannels.ensureAlarmChannel(context, signal, settings.alarmChannelVersion, settings.alarmSoundUri)
}

// ---------------- Test-Alarm ----------------

/**
 * Dürfen Alarme erscheinen? Erlaubnis (Android 13+), App-Benachrichtigungen
 * an und der Alarm-Kanal nicht stummgeschaltet.
 */
fun AppNotifier.alarmsAllowed(): Boolean {
    if (!hasPermission() || !manager.areNotificationsEnabled()) return false
    return channelDelivers(alarmChannel(settingsRepository.cached.alarmSignal))
}

/**
 * Beispiel-Alarm über denselben Kanal wie ein Kursalarm (gewähltes «Alarm-Signal»),
 * aber ohne Nachtruhe — es ist ein Test.
 */
fun AppNotifier.showTestAlarm() {
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

/**
 * Käme die Depeg-Meldung jetzt an? Gleicher Kanal wie Kursalarme («Alarm-Signal») — wie
 * [canShowPortfolioAlarm]. Ohne: DepegAlarmChecker hält den Alarm scharf.
 */
fun AppNotifier.canShowDepegAlarm(): Boolean = canShowPortfolioAlarm()

/**
 * USDT weicht um die Schwelle von 1 USD ab: Kanal der Kursalarme (in der Nachtruhe löst der
 * Alarm gar nicht aus, lautlos nur als Rückfall an der Grenze). Knopf «Börse öffnen» öffnet die
 * Seite USDC/USDT der Hauptbörse ([exchangeUrl], https) — kein Handel in der App.
 * @return true erst, wenn die Meldung unter den offenen Mitteilungen steht ([awaitActive]).
 */
suspend fun AppNotifier.showDepeg(title: String, text: String, exchangeUrl: String): Boolean {
    val settings = settingsRepository.cached
    val quiet = isQuietNow()
    val channelId = channelIdFor(AppNotifierDeliveryPlan.alarmChannel(quiet), settings.alarmSignal)
    val openExchange = PendingIntent.getActivity(
        context,
        DEPEG_EXCHANGE_REQUEST_CODE,
        Intent(Intent.ACTION_VIEW, exchangeUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_stat_depeg)
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
    // Zeit vor dem Melden: nur eine Meldung ab da bestätigt den Alarm (awaitActive)
    val postedAt = System.currentTimeMillis()
    if (!notifyInGroup(DEPEG_NOTIFICATION_ID, notification, Group.ALARMS)) return false
    return awaitActive(DEPEG_NOTIFICATION_ID, postedAt)
}
