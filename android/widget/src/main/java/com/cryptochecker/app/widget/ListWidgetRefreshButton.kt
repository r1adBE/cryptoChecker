// Knopf «Aktualisieren» bzw. Countdown des Listen-Widgets — Teil von ListWidgetRenderer.
package com.cryptochecker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.refresh.OutdatedRule
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.refresh.WidgetRefreshState
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.domain.widget.ListWidgetDataVersion
import com.cryptochecker.app.domain.widget.ListWidgetHeader
import com.cryptochecker.app.domain.widget.WidgetGroupSelection
import com.cryptochecker.app.domain.widget.WidgetOnceRead
import com.cryptochecker.app.domain.widget.WidgetStatusRule
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.logoRes
import com.cryptochecker.app.util.AppIntents
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.resources.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Knopf oder Countdown: In der Sperre zählt ein Chronometer bis zu ihrem Ende herunter — das macht der
 * Launcher selbst, ohne Neuzeichnen je Sekunde. Am Ende wird einmal neu gezeichnet ([scheduleUnlock]),
 * dann ist wieder das Symbol da. Der Countdown nimmt Tipps an, startet
 * aber nichts (gleiche Sperre im Empfänger).
 */
internal fun ListWidgetRenderer.refreshButton(
    views: RemoteViews,
    appWidgetId: Int,
    background: WidgetColors,
    lockMillis: Long,
    lockSeconds: Int
) {
    val locked = lockMillis > 0
    views.setViewVisibility(R.id.widget_refresh, if (locked) View.GONE else View.VISIBLE)
    views.setViewVisibility(R.id.widget_refresh_countdown, if (locked) View.VISIBLE else View.GONE)
    if (!locked) {
        // Angehalten: zählt verborgen nicht weiter
        views.setChronometer(R.id.widget_refresh_countdown, SystemClock.elapsedRealtime(), null, false)
        return
    }
    views.setChronometer(
        R.id.widget_refresh_countdown,
        WidgetRefreshState.chronometerBase(SystemClock.elapsedRealtime(), lockMillis),
        null,
        true,
    )
    views.setChronometerCountDown(R.id.widget_refresh_countdown, true)
    views.setTextColor(R.id.widget_refresh_countdown, background.secondaryTextColor)
    // Screenreader: «Aktualisieren – wieder möglich in 12 s» statt der laufenden Ziffern
    views.setContentDescription(
        R.id.widget_refresh_countdown,
        context.getString(R.string.widget_refresh_blocked_a11y, lockSeconds),
    )
    views.setOnClickPendingIntent(R.id.widget_refresh_countdown, refreshIntent(appWidgetId))
}
