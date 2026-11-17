package com.cryptochecker.app.wearable

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.app.domain.util.appScope
import com.cryptochecker.app.domain.wearable.WatchNotifyPolicy
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/**
 * «Auf der Uhr zeigen» (nur Android, Inhaber 15.11.): Kurs-Mitteilungen, Alarme und Markt-Mitteilungen gehen als
 * Nachricht ([PATH]) an die Crypto-Checker-Uhr-App, die sie selbst zeigt (`WatchNotifications`: Alarme mit Vibration,
 * der Rest still, Inhaber 16.11.) — unabhängig von der Weiterleitung des Systems (Samsung: nur bei unbenutztem Handy).
 * Nur wenn die Uhr-App sicher übernimmt ([ready]: zuletzt erreichbar und Erlaubnis gemeldet, [WatchNotifyPolicy]),
 * bleibt die Mitteilung des Handys nur auf dem Handy ([localOnly]); sonst leitet das System sie weiter wie ohne
 * Schalter, und es geht nichts an die Uhr-App (kein Alarm verloren, nichts doppelt). Nur Titel und Text, nie Beträge
 * des Portfolios darüber hinaus. Fehler bleiben ohne Folgen für das Handy (die Mitteilung dort ist schon gezeigt).
 */
@Singleton
class WatchNotifyBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private val scope = appScope(ioDispatcher, "Mitteilungen an die Uhr")
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Schalter an? (zuletzt gelesene Einstellungen, ohne zu warten) */
    fun enabled(): Boolean = settingsRepository.cached.watchNotifications

    /**
     * Soll [notification] auf die Uhr? Nicht Zusammenfassungen einer Gruppe und nicht Fortschritt/Dienst (Abruf läuft).
     */
    fun forwards(notification: Notification): Boolean =
        enabled() && (notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
            notification.category != NotificationCompat.CATEGORY_PROGRESS &&
            notification.category != NotificationCompat.CATEGORY_SERVICE

    /** Übernimmt die Uhr-App? (Schalter an, Uhr mit der App zuletzt erreichbar, Erlaubnis gemeldet) */
    fun ready(): Boolean = WatchNotifyPolicy.ready(enabled(), flag(KEY_REACHABLE), flag(KEY_PERMITTED))

    /** Meldung der Uhr: darf sie Mitteilungen zeigen? (Datenelement [STATE_PATH], `PhoneWatchListener`) */
    fun takePermitted(permitted: Boolean) = prefs.edit().putBoolean(KEY_PERMITTED, permitted).apply()

    /** Uhr mit der App erreichbar? (Fähigkeit geändert oder Ergebnis eines Sendeversuchs) */
    fun takeReachable(reachable: Boolean) = prefs.edit().putBoolean(KEY_REACHABLE, reachable).apply()

    private fun flag(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    /** Mitteilung des Handys nur auf dem Handy lassen (die Uhr-App zeigt ihre eigene). */
    fun localOnly(notification: Notification) {
        notification.flags = notification.flags or Notification.FLAG_LOCAL_ONLY
    }

    /** Gezeigte Mitteilung [id] an die Uhr (Titel und Text wie auf dem Sperrbildschirm, [WatchNotifyPolicy.payload]). */
    fun show(id: Int, notification: Notification) {
        val visibility = when (notification.visibility) {
            Notification.VISIBILITY_SECRET -> WatchNotifyPolicy.Visibility.SECRET
            Notification.VISIBILITY_PRIVATE -> WatchNotifyPolicy.Visibility.PRIVATE
            else -> WatchNotifyPolicy.Visibility.PUBLIC
        }
        val payload = WatchNotifyPolicy.payload(
            visibility,
            texts(notification),
            notification.publicVersion?.let(::texts),
            alarm = notification.category == NotificationCompat.CATEGORY_ALARM,
        ) ?: return
        send(
            JSONObject().put(KEY_OP, OP_SHOW).put(KEY_ID, id).put(KEY_TITLE, payload.title)
                .put(KEY_TEXT, payload.text).put(KEY_ALARM, payload.alarm)
        )
    }

    private fun texts(notification: Notification): Pair<String?, String?> {
        val extras = notification.extras
        return extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() to
            (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()
    }

    /** Mitteilung [id] auch auf der Uhr entfernen. */
    fun cancel(id: Int) {
        if (!enabled()) return
        send(JSONObject().put(KEY_OP, OP_CANCEL).put(KEY_ID, id))
    }

    /** Schalter eben eingeschaltet: die Uhr soll beim nächsten Öffnen um die Erlaubnis für Mitteilungen bitten. */
    fun hello() = send(JSONObject().put(KEY_OP, OP_HELLO))

    private fun send(payload: JSONObject) {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        scope.launch {
            try {
                val nodes = Tasks.await(
                    Wearable.getCapabilityClient(context)
                        .getCapability(PhoneWatchSync.CAPABILITY, CapabilityClient.FILTER_REACHABLE),
                    TIMEOUT_S,
                    TimeUnit.SECONDS
                ).nodes
                val client = Wearable.getMessageClient(context)
                var delivered = false
                nodes.forEach { node ->
                    try {
                        Tasks.await(client.sendMessage(node.id, PATH, bytes), TIMEOUT_S, TimeUnit.SECONDS)
                        delivered = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.d(e, "Uhr %s nicht erreicht", node.id)
                    }
                }
                // Nächste Mitteilung entscheidet danach: nicht erreicht → wieder die Weiterleitung des Systems
                takeReachable(delivered)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keine Play-Dienste bzw. keine Uhr: nichts zu tun
                Timber.d(e, "Mitteilung nicht an die Uhr")
            }
        }
    }

    companion object {
        /** Nachricht an die Uhr (gleich in `WatchNotifications.PATH`). */
        const val PATH = "/cc/notify"
        const val KEY_OP = "op"
        const val KEY_ID = "id"
        const val KEY_TITLE = "title"
        const val KEY_TEXT = "text"
        const val OP_SHOW = "show"
        const val OP_CANCEL = "cancel"
        const val KEY_ALARM = "alarm"
        const val OP_HELLO = "hello"

        /** Meldung der Uhr, ob sie Mitteilungen zeigen darf (gleich in `WatchNotifications.STATE_PATH`). */
        const val STATE_PATH = "/cc/notify/state"
        const val STATE_KEY_PERMITTED = "permitted"
        private const val PREFS = "watch_notify"
        private const val KEY_PERMITTED = "permitted"
        private const val KEY_REACHABLE = "reachable"
        private const val TIMEOUT_S = 10L
    }
}
