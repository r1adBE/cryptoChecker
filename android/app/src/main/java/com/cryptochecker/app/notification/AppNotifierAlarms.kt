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
import com.cryptochecker.app.domain.model.AlarmEntity
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.alarm.AlarmSignal
import com.cryptochecker.app.domain.alarm.AlarmTextNote
import com.cryptochecker.app.domain.alarm.NearExtreme
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.notification.AppNotifier.Companion.DEPEG_EXCHANGE_REQUEST_CODE
import com.cryptochecker.app.notification.AppNotifier.Companion.DEPEG_NOTIFICATION_ID
import com.cryptochecker.app.notification.AppNotifier.Companion.PORTFOLIO_REQUEST_CODE
import com.cryptochecker.app.notification.AppNotifier.Companion.TEST_ALARM_NOTIFICATION_ID
import com.cryptochecker.app.notification.AppNotifier.Companion.alarmNotificationId
import com.cryptochecker.app.notification.AppNotifier.Companion.portfolioAlarmNotificationId
import com.cryptochecker.app.notification.AppNotifier.Group
import com.cryptochecker.app.ui.navigation.PortfolioAlarmTarget

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
     * ([com.cryptochecker.app.domain.model.convertCurrency]) — [price] ist dann schon umgerechnet,
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
 * Alarm «Portfolio-Wert» [alarmId]. Gleicher Kanal wie die Paar-Alarme («Alarm-Signal»; in der Nachtruhe löst
 * PortfolioAlarmChecker gar nicht aus, lautlos nur als Rückfall an der Grenze); Tipp öffnet «Alle Alarme» mit diesem
 * Portfolio-Alarm hervorgehoben ([PortfolioAlarmTarget]; das Alarm-Protokoll führt nur Paar-Alarme).
 * Immer neutral wie iOS `PortfolioAlarmNotice.content()` (Entscheid des Inhabers): nur Titel und neutraler Satz
 * ([PortfolioAlarmNotice]) — weder Schwelle, Betrag noch Prozent in Titel, Text, grossem Text, Ticker oder
 * Vorlesetext, unabhängig von der Portfolio-Sperre und davon, ob das System sensible Inhalte auf dem Sperrbildschirm
 * zeigt. Darum nimmt die Funktion nur die Kennung entgegen; die Einzelheiten zeigt die App (Alarmliste/Protokoll).
 * Zusätzlich VISIBILITY_PRIVATE mit derselben neutralen öffentlichen Fassung. Kursalarme folgen weiter der Sperre
 * ([lockScreenPrivacy]).
 * @return true erst, wenn die Meldung unter den offenen Mitteilungen steht ([awaitActive]) —
 *   sonst bleibt der Alarm scharf (PortfolioAlarmChecker), wie bei [showAlarm].
 */
suspend fun AppNotifier.showPortfolioAlarm(alarmId: Long): Boolean {
    val settings = settingsRepository.cached
    val notice = PortfolioAlarmNotice.content { context.getString(it) }
    val title = notice.title
    val body = notice.text
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
        // Tipp: «Alle Alarme» mit diesem Alarm hervorgehoben (die Kennung steht nur im Ziel, nie im Text)
        .setContentIntent(openTargetIntent(PortfolioAlarmTarget.of(alarmId), PORTFOLIO_REQUEST_CODE))
    // Zusätzlich privat auf dem Sperrbildschirm; die öffentliche Fassung trägt denselben neutralen Satz
    builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_logo)
                .setColor(settings.accentColor.seed)
                .setContentTitle(title)
                .setContentText(body)
                .build()
        )
    if (quiet) builder.setSilent(true)
    val id = portfolioAlarmNotificationId(alarmId)
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
 * USDT (bzw. ein weiterer Stablecoin, je Stablecoin eigene [notificationId]) weicht um die Schwelle von 1 USD ab: Kanal
 * der Kursalarme (in der Nachtruhe löst der Alarm gar nicht aus, lautlos nur als Rückfall an der Grenze). Knopf «Börse
 * öffnen» öffnet die Seite USDC/USDT der Hauptbörse ([exchangeUrl], https; null = kein Knopf) — kein Handel in der App.
 * @return true erst, wenn die Meldung unter den offenen Mitteilungen steht ([awaitActive]).
 */
suspend fun AppNotifier.showDepeg(
    title: String,
    text: String,
    exchangeUrl: String?,
    notificationId: Int = DEPEG_NOTIFICATION_ID,
    requestCode: Int = DEPEG_EXCHANGE_REQUEST_CODE,
): Boolean {
    val settings = settingsRepository.cached
    val quiet = isQuietNow()
    val channelId = channelIdFor(AppNotifierDeliveryPlan.alarmChannel(quiet), settings.alarmSignal)
    val openExchange = exchangeUrl?.let { url ->
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    val builder = NotificationCompat.Builder(context, channelId)
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
    // Mit Portfolio-Sperre auf dem Sperrbildschirm nur «Alarm» (wie Paar- und Markt-Mitteilungen)
    builder.lockScreenPrivacy(channelId, R.drawable.ic_stat_depeg, context.getString(R.string.watch_action_alarm))
    // Knopf «Börse öffnen» nur mit passender Seite (USDT, USDC: USDC/USDT der Hauptbörse)
    openExchange?.let { builder.addAction(0, context.getString(R.string.notification_depeg_action_exchange), it) }
    val notification = builder.build()
    makeRoomForAlarm(notificationId)
    // Zeit vor dem Melden: nur eine Meldung ab da bestätigt den Alarm (awaitActive)
    val postedAt = System.currentTimeMillis()
    if (!notifyInGroup(notificationId, notification, Group.ALARMS)) return false
    return awaitActive(notificationId, postedAt)
}
