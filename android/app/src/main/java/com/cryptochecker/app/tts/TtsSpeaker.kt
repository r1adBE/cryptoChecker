package com.cryptochecker.app.tts

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kapselt die Android-Sprachausgabe. Die Engine wird beim ersten Aufruf gestartet
 * und danach offen gehalten, weil das Starten mehrere Sekunden dauern kann.
 *
 * Automatische Ansagen ([enqueue]) laufen über eine eigene Warteschlange
 * ([AnnouncementQueue]) und werden einzeln an die Engine übergeben — die nächste erst,
 * wenn die vorige gesprochen ist. So gehen mehrere Alarme eines Durchlaufs nicht
 * verloren (kein QUEUE_FLUSH untereinander) und Kursansagen stauen sich nicht in der
 * Engine, sondern werden je Paar auf die neueste zusammengefasst.
 *
 * Threadsicherheit: Warteschlange unter [lock], Start/Freigabe der Engine unter
 * [engineMutex], Aufrufe der Engine auf dem Main-Thread.
 */
@Singleton
class TtsSpeaker @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    // App-weit, ein Singleton: läuft so lange wie der Prozess
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val engineMutex = Mutex()

    @Volatile
    private var engine: TextToSpeech? = null
    private var initResult: CompletableDeferred<Boolean>? = null
    private val utteranceCounter = AtomicInteger()

    /** Utterance-Id → fertig gesprochen (oder abgebrochen / Fehler). */
    private val finished = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val lock = Any()

    /** Automatische Ansagen; nur unter [lock]. */
    private val queue = AnnouncementQueue()

    /** Eine Ansage wird gerade an die Engine übergeben; nur unter [lock]. */
    private var handingOver = false

    /** Art der gerade gesprochenen automatischen Ansage (null = keine). */
    @Volatile
    private var speakingKind: AnnouncementQueue.Kind? = null

    /** Ansagen in [queue], die noch nicht an die Engine übergeben sind. */
    private val pendingCount = MutableStateFlow(0)

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = complete(utteranceId)

        @Deprecated("Ersetzt durch onError(String, Int)")
        override fun onError(utteranceId: String?) = complete(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = complete(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId)

        private fun complete(utteranceId: String?) {
            utteranceId?.let { finished[it]?.complete(Unit) }
        }
    }

    init {
        scope.launch {
            while (true) {
                wakeUp.receive()
                drain()
            }
        }
    }

    /**
     * Wie [speak], aber ohne zu warten: Die Ansage kommt in die Warteschlange und wird
     * im Hintergrund abgesetzt. So hält das Starten der Engine (bis zu einige Sekunden)
     * die Kurs-Aktualisierung nicht auf.
     *
     * @param flush true = Alarm: wird nie verworfen, kommt vor wartenden Kursansagen und
     *   unterbricht eine gerade laufende Kursansage (nicht aber einen anderen Alarm).
     * @param key Paar (Watch-Id) einer Kursansage: Von einem Paar wartet nur die neueste.
     */
    fun enqueue(text: String, speechRate: Float = 1.0f, flush: Boolean = false, key: Long? = null) {
        if (text.isBlank()) return
        val kind = if (flush) AnnouncementQueue.Kind.ALARM else AnnouncementQueue.Kind.PRICE
        synchronized(lock) {
            queue.offer(AnnouncementQueue.Item(text, speechRate, kind, key.takeUnless { flush }))
        }
        updatePending()
        if (kind == AnnouncementQueue.Kind.ALARM && speakingKind == AnnouncementQueue.Kind.PRICE) {
            // Laufende Kursansage abbrechen; onStop gibt die Warteschlange frei
            scope.launch(Dispatchers.Main) { runCatching { engine?.stop() } }
        }
        wakeUp.trySend(Unit)
    }

    /**
     * Wartet höchstens [timeoutMillis], bis alle Ansagen aus [enqueue] an die Engine
     * übergeben sind — für den Hintergrund-Job, damit Android den Prozess nicht vorher
     * beendet. Ist nichts in der Warteschlange, kehrt es sofort zurück.
     * @return true, wenn die Warteschlange leer ist.
     */
    suspend fun awaitQueued(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { pendingCount.first { it <= 0 } } != null

    /**
     * Liest den Text sofort vor (vom Nutzer angestossen: Stimme testen, Alarm testen) —
     * auch im Lautlos-/Vibrationsmodus, anders als die automatischen Ansagen.
     * @param flush true bricht laufende Ansagen ab, false hängt an.
     * @return true, wenn die Ansage abgesetzt werden konnte.
     */
    suspend fun speak(text: String, speechRate: Float = 1.0f, flush: Boolean = false): Boolean {
        if (text.isBlank()) return false
        val id = submit(text, speechRate, flush) ?: return false
        // Nicht abwarten; das Ende räumt der Listener auf
        finished[id]?.invokeOnCompletion { finished.remove(id) }
        return true
    }

    private suspend fun drain() {
        while (true) {
            val item = synchronized(lock) {
                queue.poll()?.also { handingOver = true }
            } ?: return
            var id: String? = null
            try {
                // Lautlos/Vibration: automatische Ansagen schweigen — auch Alarme, deren Ton
                // (Benachrichtigungskanal) in diesen Modi ebenfalls stumm ist
                if (!ringerAllowsSpeech()) {
                    Timber.d("Ansage übersprungen: Telefon lautlos oder auf Vibration")
                } else {
                    speakingKind = item.kind
                    id = submit(item.text, item.speechRate, flush = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Ansage fehlgeschlagen")
            } finally {
                synchronized(lock) { handingOver = false }
                updatePending()
            }
            // Erst die nächste übergeben, wenn diese gesprochen ist (höchstens geschätzte Dauer)
            if (id != null) {
                withTimeoutOrNull(estimatedMillis(item.text, item.speechRate)) { finished[id]?.await() }
                finished.remove(id)
            }
            speakingKind = null
        }
    }

    /** Übergibt den Text an die Engine. @return Utterance-Id oder null. */
    private suspend fun submit(text: String, speechRate: Float, flush: Boolean): String? {
        val tts = engineMutex.withLock { ensureEngine() } ?: return null
        return withContext(Dispatchers.Main) {
            tts.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
            // Stimme in der App-Sprache, sofern installiert — sonst bleibt die Standardstimme.
            val locale = context.resources.configuration.locales[0]
            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = "cryptochecker-${utteranceCounter.incrementAndGet()}"
            finished[id] = CompletableDeferred()
            if (tts.speak(text, mode, null, id) == TextToSpeech.SUCCESS) {
                id
            } else {
                finished.remove(id)
                null
            }
        }
    }

    private fun ringerAllowsSpeech(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return true
        return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    private fun updatePending() {
        pendingCount.value = synchronized(lock) { queue.size + if (handingOver) 1 else 0 }
    }

    /** Nur unter [engineMutex]. */
    private suspend fun ensureEngine(): TextToSpeech? {
        initResult?.let { pending ->
            return if (withTimeoutOrNull(INIT_TIMEOUT_MS) { pending.await() } == true) engine else null
        }

        val deferred = CompletableDeferred<Boolean>()
        initResult = deferred

        val listener = OnInitListener { status ->
            deferred.complete(status == TextToSpeech.SUCCESS)
        }

        val created = withContext(Dispatchers.Main) {
            runCatching {
                TextToSpeech(context, listener).apply { setOnUtteranceProgressListener(progressListener) }
            }
                .onFailure { Timber.w(it, "Sprachausgabe konnte nicht gestartet werden") }
                .getOrNull()
        }

        if (created == null) {
            deferred.complete(false)
            initResult = null
            return null
        }

        engine = created

        val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { deferred.await() } == true
        if (!ok) {
            Timber.w("Sprachausgabe nicht verfügbar")
            releaseEngine()
            return null
        }
        return engine
    }

    fun shutdown() {
        // Wartende Ansagen verwerfen: Sonst startete die Warteschlange die Engine nach
        // dem Ausschalten der Sprachausgabe gleich wieder und spräche sie noch.
        synchronized(lock) { queue.clear() }
        updatePending()
        scope.launch { engineMutex.withLock { releaseEngine() } }
    }

    /** Nur unter [engineMutex]. */
    private suspend fun releaseEngine() {
        val old = engine
        engine = null
        initResult = null
        withContext(Dispatchers.Main) {
            runCatching {
                old?.stop()
                old?.shutdown()
            }
        }
        finished.values.forEach { it.complete(Unit) }
        finished.clear()
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 8_000L

        /** Obergrenze, wie lange auf das Ende einer Ansage gewartet wird. */
        const val MAX_UTTERANCE_MS = 60_000L

        /** Grobe Sprechdauer (ca. 150 ms je Zeichen bei normalem Tempo) plus Reserve. */
        fun estimatedMillis(text: String, speechRate: Float): Long =
            (3_000L + (text.length * 150L / speechRate.coerceIn(0.5f, 2.0f)).toLong())
                .coerceAtMost(MAX_UTTERANCE_MS)
    }
}
