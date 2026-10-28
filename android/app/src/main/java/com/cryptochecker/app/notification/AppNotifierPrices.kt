// Kurs-Mitteilungen (Teil von `AppNotifier`): Mitteilung je Paar bzw. Sammelmeldung über der Grenze, Titel
// und Vergleichstext, Entfernen einer einzelnen Mitteilung und der Abgleich mit den offenen Mitteilungen.
// Eigene Datei, weil diese Regeln (Grenze, Sammelmeldung, «eben gemeldet») zusammen lesbar sein sollen;
// Erweiterungsfunktionen, die öffentlichen Aufrufe der Kurs-Mitteilungen und der Zustand bleiben in der Klasse.
package com.cryptochecker.app.notification

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.notification.AppNotifier.Companion.PRICE_SUMMARY_ID
import com.cryptochecker.app.notification.AppNotifier.Companion.PRICE_OVERFLOW_ID
import com.cryptochecker.app.notification.AppNotifier.Companion.EXTRA_WATCH_ID
import com.cryptochecker.app.notification.AppNotifier.Companion.EXTRA_OVERFLOW_IDS
import com.cryptochecker.app.notification.AppNotifier.Companion.EXTRA_OVERFLOW_LINES
import com.cryptochecker.app.notification.AppNotifier.Companion.RECENT_MILLIS
import com.cryptochecker.app.notification.AppNotifier.Companion.priceNotificationId
import com.cryptochecker.app.notification.AppNotifier.ActiveState
import com.cryptochecker.app.notification.AppNotifier.Group

/** Titel einer Kurs-Mitteilung und Zeile der Sammelmeldung: «BTC: 98’450 USDT • Binance». */
internal fun AppNotifier.priceTitle(watch: WatchEntity, price: Double): String =
    context.getString(
        R.string.notification_price_title,
        watch.baseAsset,
        PriceFormat.price(price),
        watch.quoteAsset,
        watch.exchangeName
    )

/**
 * Einzelne Kurs-Benachrichtigung (nur über [showPrices]). Die zweite Zeile bezieht sich auf die
 * vorige Meldung, nicht auf die vorige Abfrage — sonst stünde dort bei einer Melde-Schwelle von
 * 5 % dauernd eine Veränderung von 0,1 %.
 */
internal fun AppNotifier.postPrice(watch: WatchEntity, ongoing: Boolean): Boolean {
    val price = watch.lastPrice ?: return false
    val title = priceTitle(watch, price)

    val text = buildComparisonText(watch, price)
    val settings = settingsRepository.cached

    val builder = NotificationCompat.Builder(context, NotificationChannels.PRICES)
        .setSmallIcon(R.drawable.ic_stat_logo)
        .setColor(settings.accentColor.seed)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setSubText(PriceFormat.time(watch.lastUpdate))
        .setOnlyAlertOnce(true)
        .setOngoing(ongoing)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setGroup(Group.PRICES.key)
        // Paar merken: für die Sammelmeldung, wenn die Meldung später weichen muss
        .addExtras(Bundle().apply { putLong(EXTRA_WATCH_ID, watch.id) })
        .setContentIntent(openAppIntent(watch.id))
    // Mit Portfolio-Sperre zeigt der Sperrbildschirm keinen Kurs, nur «Kursmitteilungen» (wie die Portfolio-Alarme)
    if (settings.appLock) {
        builder.privateOnLockScreen(NotificationChannels.PRICES, R.drawable.ic_stat_logo, context.getString(R.string.channel_prices))
    } else {
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    val posted = notifyInGroup(priceNotificationId(watch.id), builder.build(), Group.PRICES)
    if (posted) recentPrices[watch.id] = System.currentTimeMillis() to title
    return posted
}

/**
 * Sammelmeldung der Kurs-Mitteilungen über der Grenze: «+N weitere Kurse» mit den ersten
 * Zeilen; leer = entfernen. Paare und Zeilen stehen in den Extras (überdauern einen Neustart
 * des Prozesses). @return true, wenn gemeldet.
 */
internal fun AppNotifier.postOverflow(lines: LinkedHashMap<Long, String>, ongoing: Boolean): Boolean {
    if (lines.isEmpty()) {
        if (overflowCache != null || overflowPostedAt > 0L) manager.cancel(PRICE_OVERFLOW_ID)
        overflowCache = null
        overflowPostedAt = 0L
        return false
    }
    val settings = settingsRepository.cached
    val count = lines.size
    val title = context.resources.getQuantityString(R.plurals.notification_price_overflow_title, count, count)
    val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
    AppNotifierDeliveryPlan.overflowInboxLines(lines.values.toList()).forEach { style.addLine(it) }
    val builder = NotificationCompat.Builder(context, NotificationChannels.PRICES)
        .setSmallIcon(R.drawable.ic_stat_logo)
        .setColor(settings.accentColor.seed)
        .setContentTitle(title)
        .setContentText(lines.values.first())
        .setStyle(style)
        .setOnlyAlertOnce(true)
        .setOngoing(ongoing)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setGroup(Group.PRICES.key)
        .addExtras(Bundle().apply {
            putLongArray(EXTRA_OVERFLOW_IDS, lines.keys.toLongArray())
            putStringArray(EXTRA_OVERFLOW_LINES, lines.values.toTypedArray())
        })
        .setContentIntent(openAppIntent(null))
    if (settings.appLock) {
        builder.privateOnLockScreen(NotificationChannels.PRICES, R.drawable.ic_stat_logo, context.getString(R.string.channel_prices))
    } else {
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }
    val posted = notifyInGroup(PRICE_OVERFLOW_ID, builder.build(), Group.PRICES)
    if (posted) {
        overflowCache = LinkedHashMap(lines)
        overflowPostedAt = System.currentTimeMillis()
    }
    return posted
}

/** „-4.875%, from 0.000759 USDT (7d ago)" — oder ein Hinweis bei der ersten Meldung. */
internal fun AppNotifier.buildComparisonText(watch: WatchEntity, price: Double): String {
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

/** Nur die einzelne Meldung (sie wandert in die Sammelmeldung bzw. macht einem Alarm Platz). */
internal fun AppNotifier.cancelPriceSingle(watchId: Long) {
    manager.cancel(priceNotificationId(watchId))
    recentPrices.remove(watchId)
}

/** Offene Mitteilungen nach Art ([AppNotifierDeliveryPlan.splitActive]); null = Systemdienst nicht abfragbar. */
internal fun AppNotifier.activeState(): ActiveState? {
    val list = runCatching {
        context.getSystemService(android.app.NotificationManager::class.java)?.activeNotifications
    }.getOrNull() ?: return null
    val now = System.currentTimeMillis()
    recentPrices.entries.removeAll { now - it.value.first !in 0 until RECENT_MILLIS }
    val ids = list.mapTo(HashSet()) { it.id }
    val entries = list.map { sbn ->
        val n = sbn.notification
        val priceChild = sbn.id != PRICE_OVERFLOW_ID && sbn.id != PRICE_SUMMARY_ID &&
            n.group == Group.PRICES.key && (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        if (priceChild) {
            AppNotifierDeliveryPlan.ActiveEntry(
                id = sbn.id,
                postTime = sbn.postTime,
                priceChild = true,
                watchId = watchIdOf(sbn),
                title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            )
        } else {
            AppNotifierDeliveryPlan.ActiveEntry(sbn.id, sbn.postTime, priceChild = false)
        }
    }
    val split = AppNotifierDeliveryPlan.splitActive(entries, PRICE_SUMMARY_ID, PRICE_OVERFLOW_ID)
    // Eben gemeldete, noch nicht geführte zuerst (sie sind die neuesten)
    val prices = AppNotifierDeliveryPlan.orderPrices(recentPrices, split.listed)
    val cache = overflowCache
    val overflowShown =
        AppNotifierDeliveryPlan.overflowShown(split.overflowListed, cache != null, now, overflowPostedAt, RECENT_MILLIS)
    val overflow = when {
        !overflowShown -> {
            overflowCache = null
            LinkedHashMap()
        }
        cache != null -> LinkedHashMap(cache)
        else -> {
            // Bei mehreren Meldungen mit dieser Nummer die älteste
            val overflowSbn = list.sortedByDescending { it.postTime }.lastOrNull { it.id == PRICE_OVERFLOW_ID }
            decodeOverflow(overflowSbn?.notification?.extras).also { overflowCache = LinkedHashMap(it) }
        }
    }
    return ActiveState(
        ids = ids,
        prices = prices,
        overflow = overflow,
        overflowShown = overflowShown,
        other = split.other,
        activity = split.activity,
        alarms = split.alarms,
        total = list.size,
    )
}

/** Paare der Sammelmeldung (leer, wenn keine offen ist). */
internal fun AppNotifier.overflowMembers(): LinkedHashMap<Long, String> {
    val cache = overflowCache
    if (cache != null && System.currentTimeMillis() - overflowPostedAt in 0 until RECENT_MILLIS) return LinkedHashMap(cache)
    return activeState()?.overflow ?: cache?.let { LinkedHashMap(it) } ?: LinkedHashMap()
}

internal fun AppNotifier.decodeOverflow(extras: Bundle?): LinkedHashMap<Long, String> {
    val result = LinkedHashMap<Long, String>()
    val ids = extras?.getLongArray(EXTRA_OVERFLOW_IDS) ?: return result
    val lines = extras.getStringArray(EXTRA_OVERFLOW_LINES)
    ids.forEachIndexed { i, id -> result[id] = lines?.getOrNull(i).orEmpty() }
    return result
}

/** Paar einer Kurs-Mitteilung: aus den Extras, bei älteren Meldungen aus der Nummer (nur kleiner Bereich). */
internal fun AppNotifier.watchIdOf(sbn: StatusBarNotification): Long? {
    val extra = sbn.notification.extras?.getLong(EXTRA_WATCH_ID, Long.MIN_VALUE) ?: Long.MIN_VALUE
    if (extra != Long.MIN_VALUE) return extra
    return NotificationIds.smallIdOf(NotificationIds.Kind.PRICE, sbn.id)
}
