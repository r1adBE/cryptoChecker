package com.cryptochecker.app.wearable

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.app.domain.util.appScope
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Auf der Uhr zeigen» (nur Android, Inhaber 15.11.): Kurs-Mitteilungen, Alarme und Markt-Mitteilungen gehen als
 * Nachricht ([PATH]) an die Crypto-Checker-Uhr-App, die sie still selbst zeigt (`WatchNotifications`) — unabhängig von
 * der Weiterleitung des Systems (Samsung: nur bei unbenutztem Handy). Die Mitteilung des Handys bleibt dann nur auf
 * dem Handy ([localOnly]), sonst stünde sie doppelt auf der Uhr. Nur Titel und Text, nie Beträge des Portfolios
 * darüber hinaus; ohne Uhr mit der App ([PhoneWatchSync.CAPABILITY]) geht nichts weg. Fehler bleiben ohne Folgen für
 * das Handy (die Mitteilung dort ist schon gezeigt).
 */
@Singleton
class WatchNotifyBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private val scope = appScope(ioDispatcher, "Mitteilungen an die Uhr")

    /** Schalter an? (zuletzt gelesene Einstellungen, ohne zu warten) */
    fun enabled(): Boolean = settingsRepository.cached.watchNotifications

    /**
     * Soll [notification] auf die Uhr? Nicht Zusammenfassungen einer Gruppe und nicht Fortschritt/Dienst (Abruf läuft).
     */
    fun forwards(notification: Notification): Boolean =
        enabled() && (notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
            notification.category != NotificationCompat.CATEGORY_PROGRESS &&
            notification.category != NotificationCompat.CATEGORY_SERVICE

    /** Mitteilung des Handys nur auf dem Handy lassen (die Uhr-App zeigt ihre eigene). */
    fun localOnly(notification: Notification) {
        notification.flags = notification.flags or Notification.FLAG_LOCAL_ONLY
    }

    /** Gezeigte Mitteilung [id] an die Uhr (Titel und Text wie auf dem Handy). */
    fun show(id: Int, notification: Notification) {
        // Geheim (Sperre): nichts; privat mit öffentlicher Fassung: nur die (wie auf dem Sperrbildschirm)
        if (notification.visibility == Notification.VISIBILITY_SECRET) return
        val source = notification.publicVersion
            ?.takeIf { notification.visibility == Notification.VISIBILITY_PRIVATE } ?: notification
        val extras = source.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        send(JSONObject().put(KEY_OP, OP_SHOW).put(KEY_ID, id).put(KEY_TITLE, title.take(MAX_TITLE))
            .put(KEY_TEXT, text.take(MAX_TEXT)))
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
                    TIMEOUT_S, TimeUnit.SECONDS
                ).nodes
                val client = Wearable.getMessageClient(context)
                nodes.forEach { node ->
                    try {
                        Tasks.await(client.sendMessage(node.id, PATH, bytes), TIMEOUT_S, TimeUnit.SECONDS)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.d(e, "Uhr %s nicht erreicht", node.id)
                    }
                }
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
        const val OP_HELLO = "hello"
        private const val MAX_TITLE = 120
        private const val MAX_TEXT = 600
        private const val TIMEOUT_S = 10L
    }
}
