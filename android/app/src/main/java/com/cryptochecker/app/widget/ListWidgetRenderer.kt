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
import com.cryptochecker.app.R
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.refresh.OutdatedRule
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.domain.refresh.WidgetRefreshState
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.settings.HighContrast
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.util.appScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listen-Widget «Merkliste»: Kopf mit Uhrzeit und Aktualisieren, darunter die Zeilen ([ListWidgetRows]).
 * Ab Android 12 kommen die Zeilen im selben Schritt wie der Kopf ins Widget (RemoteCollectionItems) —
 * kein Dienst, der erst gebunden werden muss und hängen bleiben kann («Wird geladen …»). Darunter
 * und bei sehr langen Listen liefert sie [PriceWidgetService].
 */
@Singleton
class ListWidgetRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val widgetPrefs: WidgetPrefs,
    private val refreshStats: RefreshStats,
    private val settingsRepository: SettingsRepository,
    private val toolkit: WidgetToolkit,
    private val rows: ListWidgetRows,
    /** Lazy: PriceRefresher zeichnet selbst über WidgetUpdater (sonst ein Abhängigkeitskreis). */
    private val priceRefresher: dagger.Lazy<PriceRefresher>,
) {
    /** Neuzeichnen am Ende der Sperre ([scheduleUnlock]); läuft unabhängig vom Aufrufer weiter. */
    private val unlockScope = appScope(Dispatchers.Default, "ListWidgetRenderer")

    /** Ende der Sperre, für das das Neuzeichnen schon geplant ist (nur einmal je Sperre). */
    @Volatile private var unlockScheduledFor = 0L
    @Volatile private var unlockJob: Job? = null

    /**
     * Nacheinander zeichnen: Läuft beim Speichern der Einrichtung (neue Gruppe) noch ein Zeichnen der
     * Aktualisierung mit der alten Gruppe, käme dieses sonst zuletzt an und überschriebe die neue
     * Auswahl («Gruppe aktualisiert nicht»). So liest jedes Zeichnen die Einstellungen erst, wenn das
     * vorige fertig ist.
     */
    private val drawLock = Mutex()

    /**
     * Ergebnis eines Zeichnens: [watches] = die dabei EINMAL gelesenen Paare (für den Wecker «veraltet»,
     * [WidgetUpdater]); null = nicht gelesen oder fehlgeschlagen.
     */
    class Drawn(val watches: List<WatchEntity>?)

    /**
     * Zeichnet die Listen-Widgets [appWidgetIds]; null, wenn nichts zu zeichnen war. [select]: wählt aus den gelesenen
     * Paaren, welche der Widgets neu müssen (unter der Sperre des Zeichnens, dieselbe Abfrage wie die Zeilen).
     */
    suspend fun update(appWidgetIds: IntArray, select: ((List<WatchEntity>) -> IntArray)? = null): Drawn? {
        if (appWidgetIds.isEmpty()) return null
        return drawLock.withLock {
            // Paare einmal je Zeichnen: Leer-Hinweis, Titel und Zeilen aller Widgets lesen dieselben
            val all = WidgetOnceRead { rows.allWatches() }
            val ids = if (select == null) appWidgetIds else select(all.get())
            if (ids.isEmpty() || !draw(ids, all)) null else Drawn(all.peek())
        }
    }

    private suspend fun draw(appWidgetIds: IntArray, all: WidgetOnceRead<List<WatchEntity>>): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false

        // Akzentfarbe wie in der App; Hell/Dunkel je Widget (System/Dunkel/Hell).
        val settings = settingsRepository.current()
        val accent = settings.accentColor
        val highContrast = HighContrast.isEffective(context, settings.highContrast)
        widgetPrefs.lastHighContrast = highContrast

        // Liegt die letzte erfolgreiche Aktualisierung zu weit zurück: «veraltet · 06:42» statt der Uhrzeit
        val lastRefresh = refreshStats.lastRefresh()
        val now = System.currentTimeMillis()
        val outdated = OutdatedRule.isOutdated(lastRefresh, now, WidgetOutdated.afterMillis(settings))
        // In der 15-s-Sperre nach einer vollständigen Aktualisierung (oder einem Tipp): Countdown statt Knopf
        val blockedUntil = WidgetRefreshState.blockedUntil(
            storedUntil = widgetPrefs.refreshBlockedUntil,
            lastFinishedAt = lastRefresh,
            runningSince = priceRefresher.get().fullRefreshStartedAt,
            now = now,
        )
        // Bildschirm aus: Symbol statt Countdown – niemand sieht ihn, und ein eingefrorener Prozess
        // zeichnet am Ende der Sperre womöglich nicht neu (Countdown bliebe nach dem Entsperren bei 0:00)
        val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val lockMillis = WidgetRefreshState.countdownMillis(WidgetRefreshState.remainingMillis(blockedUntil, now), screenOn)
        val lockSeconds = if (lockMillis > 0) WidgetRefreshState.remainingSeconds(blockedUntil, now) else 0
        if (lockMillis > 0) scheduleUnlock(blockedUntil, now)

        // Widgets, deren Zeilen der Dienst liefert: danach neu laden lassen
        val viaService = ArrayList<Int>()
        for (appWidgetId in appWidgetIds) {
            val dark = widgetPrefs.isDark(appWidgetId)
            val colors = WidgetColors.of(accent, dark, settings.priceColorScheme, highContrast, settings.priceColorsInverted)
            // Leer oder nicht: selbst entscheiden statt über setEmptyView — manche Launcher lassen den
            // Hinweis «Noch keine Paare» sonst stehen, obwohl inzwischen Paare da sind (z. B. nach dem
            // Einspielen einer Sicherung in ein leeres Widget)
            val empty = runCatchingCancellable { rows.count(appWidgetId, all.get()) == 0 }.getOrDefault(false)
            // Merkliste nur leer im Arbeitsspeicher (gespeicherter Stand gerade nicht lesbar; nach dem Zählen oben
            // verlässlich): nicht neu zeichnen — der Launcher behält den bisherigen Inhalt statt «Noch keine Paare»
            if (DatabaseEncryptionNotice.isStoredKept(AppDatabase.DB_NAME)) continue
            val inline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) inlineRows(appWidgetId, all) else null
            if (inline == null) viaService += appWidgetId
            val title = headerTitle(appWidgetId, all)
            // Neuer Stand je Zeichnen: Zeilen aus dem Dienst gehören so zur Uhrzeit im Kopf (ListWidgetDataVersion)
            val version = rows.versions.next(appWidgetId)
            runCatchingCancellable {
                manager.updateAppWidget(
                    appWidgetId,
                    render(
                        manager, appWidgetId, colors, accent.logoRes(dark), lastRefresh, outdated, inline, title, version, empty,
                        lockMillis, lockSeconds,
                    ),
                )
            }.onFailure { Timber.w(it, "Widget %d konnte nicht gezeichnet werden", appWidgetId) }
        }

        // Fordert die Liste an, ihre Daten neu zu laden (nur Zeilen aus dem Dienst).
        // Seit API 31 zugunsten von RemoteCollectionItems abgekündigt — die nutzen wir dort.
        if (viaService.isNotEmpty()) {
            @Suppress("DEPRECATION")
            runCatchingCancellable { manager.notifyAppWidgetViewDataChanged(viaService.toIntArray(), R.id.widget_list) }
                .onFailure { Timber.w(it, "Widget-Liste konnte nicht aktualisiert werden") }
        }
        return true
    }

    private fun render(
        manager: AppWidgetManager,
        appWidgetId: Int,
        background: WidgetColors,
        logoRes: Int,
        lastRefresh: Long,
        outdated: Boolean,
        /** Zeilen direkt im Widget (ab Android 12); null = Dienst. */
        inline: RemoteViews.RemoteCollectionItems?,
        /** Titel im Kopf: «Merkliste» oder die gewählte Gruppe ([headerTitle]). */
        title: String,
        /** Stand dieses Zeichnens ([ListWidgetDataVersion]); sein Token macht die Adapter-Intent neu. */
        version: Long,
        /** Keine Zeilen: Hinweis «Noch keine Paare» statt der Liste. */
        empty: Boolean,
        /** Restzeit der Sperre des Knopfs in ms ([WidgetRefreshState.remainingMillis]); 0 = Knopf zeigen. */
        lockMillis: Long,
        /** Dieselbe Restzeit in ganzen Sekunden (für den Screenreader). */
        lockSeconds: Int,
    ): RemoteViews {
        val opacity = widgetPrefs.getOpacity(appWidgetId)
        val views = RemoteViews(context.packageName, R.layout.widget_list)

        toolkit.background(views, R.id.widget_bg, background, opacity)
        // Kopf: Logo · «Merkliste» (bzw. die gewählte Gruppe) links, rechts «Uhrzeit · Dauer» und der Aktualisieren-Knopf.
        // Knopf neutral: weiss im Dunkeln, schwarz im Hellen — nicht in der Akzentfarbe
        views.setInt(R.id.widget_refresh, "setColorFilter", background.textColor)
        views.setImageViewResource(R.id.widget_logo, logoRes)

        // Uhrzeit zuerst: Nur daran erkennt man, ob die Daten alt sind. Nie abgeschnitten:
        // erst fällt die Dauer weg, dann wird der Titel kleiner, dann fällt die Uhrzeit weg.
        // Veraltet: «veraltet · 06:42» ohne Dauer; eng nur «veraltet» (das Wort bleibt).
        val header = ListWidgetHeader.layout(
            widthDp = toolkit.sizeDp(manager, appWidgetId).first,
            time = if (outdated) WidgetOutdated.label(context, lastRefresh) else PriceFormat.time(lastRefresh).takeIf { it != "—" },
            duration = if (outdated) null else PriceFormat.duration(refreshStats.lastDuration()),
            titleWidthDp = { toolkit.textWidthDp(title, it) },
            statusWidthDp = { toolkit.textWidthDp(it, 12f, bold = false, tabular = true) },
            fallback = if (outdated) context.getString(R.string.widget_outdated) else null,
        )
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, header.titleSp)
        views.setTextColor(R.id.widget_title, background.textColor)
        views.setTextViewText(R.id.widget_duration, header.status)
        views.setTextColor(R.id.widget_duration, background.secondaryTextColor)
        // Screenreader: «veraltet, letzte Aktualisierung 06:42» statt der Kurzform
        views.setContentDescription(
            R.id.widget_duration,
            if (outdated && header.status.isNotEmpty()) WidgetOutdated.spoken(context, lastRefresh) else null
        )
        // Aktualisierung aus dem Widget wartet auf den nächsten Versuch: «Neuer Versuch um 14:32» statt der Uhrzeit
        views.setTextColor(R.id.widget_retry_at, background.secondaryTextColor)
        ListWidgetRetry.applyTo(context, views, widgetPrefs.refreshRetryAt, System.currentTimeMillis())
        views.setTextViewText(R.id.widget_empty, context.getString(R.string.widget_empty))
        views.setTextColor(R.id.widget_empty, background.secondaryTextColor)

        if (inline != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setRemoteAdapter(R.id.widget_list, inline)
        } else {
            val adapterIntent = Intent(context, PriceWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // Wechselnder Token: der Launcher bindet einen frischen Adapter statt alte Zeilen aus seinem
                // Zwischenspeicher zu zeigen (sonst neue Uhrzeit, aber alte Kurse)
                putExtra(EXTRA_ROWS_TOKEN, ListWidgetDataVersion.token(version))
                // Ohne eigene data-Uri teilen sich mehrere Widgets eine Fabrik.
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.widget_list, adapterIntent)
        }
        views.setViewVisibility(R.id.widget_list, if (empty) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_empty, if (empty) View.VISIBLE else View.GONE)

        views.setPendingIntentTemplate(R.id.widget_list, openAppTemplate())
        // Der Kopf (Logo, Titel, Uhrzeit) öffnet die App
        views.setOnClickPendingIntent(R.id.widget_header, toolkit.openApp())
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(appWidgetId))
        refreshButton(views, appWidgetId, background, lockMillis, lockSeconds)

        return views
    }

    /**
     * Genau ein Neuzeichnen zum Ende der Sperre, damit das Symbol zurückkommt: pünktlich im Prozess, als
     * Absicherung (Prozess beendet oder eingefroren) der Wecker [WidgetRefreshUnlock].
     */
    private fun scheduleUnlock(blockedUntil: Long, now: Long) {
        // Nur überspringen, solange das Neuzeichnen für dieses Ende noch aussteht (sonst nie wieder eins)
        if (unlockScheduledFor == blockedUntil && unlockJob?.isActive == true) return
        unlockScheduledFor = blockedUntil
        WidgetRefreshUnlock.schedule(context, blockedUntil, now)
        unlockJob?.cancel()
        unlockJob = unlockScope.launch {
            // Etwas nach dem Ende: dann ist die Sperre sicher vorbei und es kommt das Symbol, kein «0»
            delay((blockedUntil - System.currentTimeMillis()).coerceAtLeast(0) + WidgetRefreshState.UNLOCK_SLACK_MILLIS)
            runCatchingCancellable { update(toolkit.ids(PriceWidgetProvider::class.java)) }
                .onFailure { Timber.w(it, "Listen-Widgets nach der Sperre nicht neu gezeichnet") }
        }
    }

    /**
     * Knopf oder Countdown: In der Sperre zählt ein Chronometer bis zu ihrem Ende herunter — das macht der
     * Launcher selbst, ohne Neuzeichnen je Sekunde. Am Ende wird einmal neu gezeichnet ([scheduleUnlock]),
     * dann ist wieder das Symbol da. Der Countdown nimmt Tipps an, startet
     * aber nichts (gleiche Sperre im Empfänger).
     */
    private fun refreshButton(views: RemoteViews, appWidgetId: Int, background: WidgetColors, lockMillis: Long, lockSeconds: Int) {
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

    /**
     * Titel im Kopf: «Merkliste» bei «Alle», sonst die gewählte Gruppe bzw. der Filter («Favoriten»,
     * «Token», «Alpha-Tokens», «TradFi», eigener Name — gekürzt, [WidgetGroupSelection.title]). Hat die
     * Gruppe keine Paare (das Widget zeigt dann alle) oder schlägt das Lesen fehl: «Merkliste».
     */
    private suspend fun headerTitle(appWidgetId: Int, all: WidgetOnceRead<List<WatchEntity>>): String {
        val default = context.getString(R.string.tab_watchlist)
        val group = runCatchingCancellable { rows.group(appWidgetId, all.get()) }.onFailure {
            Timber.w(it, "Widget-Gruppe %d nicht lesbar", appWidgetId)
        }.getOrNull()
        return WidgetGroupSelection.title(group, default) { filter ->
            when {
                WatchFilter.isFavorites(filter) -> context.getString(R.string.group_favorites)
                WatchFilter.isTokens(filter) -> context.getString(R.string.group_tokens)
                else -> context.getString(R.string.group_alpha)
            }
        }
    }

    /**
     * Zeilen für [appWidgetId] direkt im Widget (ab Android 12). Null unter Android 12, bei mehr als
     * [MAX_INLINE_ROWS] Paaren (alles geht in einem Binder-Aufruf mit) oder bei einem Fehler —
     * dann liefert sie [PriceWidgetService].
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun inlineRows(
        appWidgetId: Int,
        all: WidgetOnceRead<List<WatchEntity>>,
    ): RemoteViews.RemoteCollectionItems? {
        return runCatchingCancellable {
            val data = rows.load(appWidgetId, all.get())
            // Mit Logos reist je Zeile ein kleines Bild mit: früher an den Dienst abgeben
            val limit = if (data.logos != null) MAX_INLINE_ROWS_WITH_LOGOS else MAX_INLINE_ROWS
            if (data.watches.size > limit) return@runCatchingCancellable null
            val builder = RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true)
                .setViewTypeCount(1)
            data.watches.forEachIndexed { index, watch -> builder.addItem(watch.id, rows.row(data, index)) }
            builder.build()
        }.onFailure {
            Timber.w(it, "Widget-Zeilen %d: weiter über den Dienst", appWidgetId)
        }.getOrNull()
    }

    /** Vorlage für die Zeilen; die Zeile ergänzt nur noch die Id ihres Paares. */
    private fun openAppTemplate(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Eigene Action: sonst gleiche Identität wie die allgemeine Mitteilung (Request-Code 0)
            action = ACTION_OPEN_WIDGET_ROW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun refreshIntent(appWidgetId: Int): PendingIntent {
        val intent = Intent(context, PriceWidgetProvider::class.java).apply {
            action = PriceWidgetProvider.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val ACTION_OPEN_WIDGET_ROW = "com.cryptochecker.app.action.OPEN_FROM_WIDGET_ROW"

        /** Token der Adapter-Intent ([ListWidgetDataVersion.token]). */
        const val EXTRA_ROWS_TOKEN = "com.cryptochecker.app.extra.ROWS_TOKEN"

        /** Bis so viele Paare direkt im Widget; darüber über den Dienst (Grösse eines Binder-Aufrufs). */
        const val MAX_INLINE_ROWS = 200

        /** Mit Coin-Logos (je Zeile bis 48 × 48 px, gleiche Coins teilen ein Bild). */
        const val MAX_INLINE_ROWS_WITH_LOGOS = 40
    }
}
