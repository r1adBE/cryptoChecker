// Kurs-Mitteilungen eines Laufs unter der Sperre (showPrices) — Teil von AppNotifier.
package com.cryptochecker.app.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.notification.NotificationBudget
import com.cryptochecker.app.domain.notification.NotificationIds
import com.cryptochecker.app.domain.notification.PriceNotificationRule
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.resources.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [showPrices] unter [priceRunLock]. */
internal suspend fun AppNotifier.showPricesLocked(watches: List<WatchEntity>, ongoing: Boolean): Set<Long> {
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
        // aber als gezeigt). Die Pausen halten keinen Thread fest (delay statt sleep), brechen aber
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
        // Zeilen EINMAL bauen: frischer Kurs, sonst die bisherige Zeile (Sammelmeldung bzw. verschobene
        // Einzelmeldung). Die Grundlage (Plan, Kurse, Zustand von vorher) bleibt gleich; nach der Pause fallen nur
        // die inzwischen entfernten Paare weg
        val (lines, unchanged) = synchronized(this) {
            val built = overflowLines(plan.overflow, byId, active)
            val same = active != null && active.overflowShown &&
                active.overflow.entries.toList() == built.entries.toList()
            built to same
        }
        if (!unchanged && lines.isNotEmpty()) spaceOut()
        synchronized(this) {
            lines.keys.removeAll(cancelledDuringRun)
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
