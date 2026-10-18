package com.cryptochecker.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * Kapselt die Android-Sprachausgabe. Die Engine wird beim ersten Aufruf gestartet und
 * offen gehalten, solange gesprochen wird (das Starten kann mehrere Sekunden dauern);
 * nach [IDLE_RELEASE_MS] ohne Ansage wird sie freigegeben und bei Bedarf neu gestartet.
 *
 * Ton: Wecker-Kanal ([AudioAttributes.USAGE_ALARM], Sprache) — hörbar auch bei leiser
 * Medienlautstärke; während einer Ansage holt sie den Audio-Fokus «kurz, andere leiser»
 * (Musik wird abgesenkt) und gibt ihn danach wieder ab. Verweigert Android den Fokus (ab
 * Android 15 im Hintergrund), wird trotzdem gesprochen, nur ohne Absenken.
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

    /** Eine Ansage wird gerade übergeben oder gesprochen (bis zu ihrem Ende); nur unter [lock]. */
    private var handingOver = false

    /** Art der gerade gesprochenen automatischen Ansage (null = keine). */
    @Volatile
    private var speakingKind: AnnouncementQueue.Kind? = null

    /** Ansagen in [queue] plus die gerade übergebene bzw. gesprochene (bis zu ihrem Ende). */
    private val pendingCount = MutableStateFlow(0)

    /** Laufende Übergaben an die Engine ([submit]); solange > 0, wird sie nicht freigegeben. */
    private val submitting = AtomicInteger()

    /** Freigabe der Engine nach [IDLE_RELEASE_MS] ohne Ansage. */
    @Volatile
    private var idleRelease: Job? = null

    private val focusLock = Any()

    /** Laufende Ansagen mit Audio-Fokus; nur unter [focusLock]. */
    private var focusUsers = 0

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(SPEECH_ATTRIBUTES)
            .build()
    }

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
            queue.offer(
                AnnouncementQueue.Item(text, speechRate, kind, key.takeUnless { flush }, createdAt = SystemClock.elapsedRealtime()),
            )
        }
        updatePending()
        if (kind == AnnouncementQueue.Kind.ALARM && speakingKind == AnnouncementQueue.Kind.PRICE) {
            // Laufende Kursansage abbrechen; onStop gibt die Warteschlange frei
            scope.launch(Dispatchers.Main) { runCatching { engine?.stop() } }
        }
        wakeUp.trySend(Unit)
    }

    /**
     * Wartet höchstens [timeoutMillis], bis alle Ansagen aus [enqueue] gesprochen sind (die
     * letzte fertig: onDone/onError/onStop, höchstens ihre geschätzte Dauer) — für den
     * Hintergrund-Job, damit Android den Prozess nicht mitten in der Ansage einfriert.
     * Ist nichts in der Warteschlange, kehrt es sofort zurück.
     * @return true, wenn alles gesprochen ist.
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
        // Nicht abwarten; das Ende räumt der Listener auf (meldet die Engine nichts: nach der geschätzten Dauer)
        val done = finished[id] ?: return true
        done.invokeOnCompletion {
            finished.remove(id)
            scheduleIdleRelease()
        }
        scope.launch {
            withTimeoutOrNull(estimatedMillis(text, speechRate)) { done.await() }
            done.complete(Unit)
        }
        return true
    }

    private suspend fun drain() {
        while (true) {
            val item = synchronized(lock) {
                queue.poll()?.also { handingOver = true }
            } ?: return
            var id: String? = null
            try {
                // Lautlos/Vibration, Telefonat oder «Bitte nicht stören»: automatische Ansagen schweigen —
                // auch Alarme, deren Ton (Benachrichtigungskanal) in diesen Modi ebenfalls stumm ist
                if (item.isStale(SystemClock.elapsedRealtime())) {
                    // Engine kam zu spät (Start nach Wartezeit/eingefrorenem Prozess): nicht Veraltetes sprechen
                    Timber.d("Ansage verworfen: älter als %d s", AnnouncementQueue.MAX_AGE_MILLIS / 1000)
                } else if (!ringerAllowsSpeech()) {
                    Timber.d("Ansage übersprungen: Telefon lautlos oder auf Vibration")
                } else if (!callAndDndAllowSpeech()) {
                    Timber.d("Ansage übersprungen: Telefonat oder «Bitte nicht stören»")
                } else {
                    speakingKind = item.kind
                    id = submit(item.text, item.speechRate, flush = false) {
                        // Nach dem (evtl. sekundenlangen) Start der Engine nochmals prüfen: zu alt,
                        // inzwischen Telefonat, lautlos oder «Bitte nicht stören»?
                        item.isStale(SystemClock.elapsedRealtime()) || !ringerAllowsSpeech() || !callAndDndAllowSpeech()
                    }
                }
                // Erst die nächste übergeben, wenn diese gesprochen ist (höchstens geschätzte Dauer)
                if (id != null) {
                    withTimeoutOrNull(estimatedMillis(item.text, item.speechRate)) { finished[id]?.await() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Ansage fehlgeschlagen")
            } finally {
                // Auch nach Zeitablauf abschliessen: gibt den Audio-Fokus frei
                id?.let { finished.remove(it)?.complete(Unit) }
                speakingKind = null
                // Erst jetzt zählt sie nicht mehr als offen (awaitQueued wartet bis hierher)
                synchronized(lock) { handingOver = false }
                updatePending()
                scheduleIdleRelease()
            }
        }
    }

    /**
     * Übergibt den Text an die Engine. @return Utterance-Id oder null.
     * @param skip nach dem Start der Engine geprüft: true = nicht mehr sprechen (zu alt, Telefonat …).
     */
    private suspend fun submit(
        text: String,
        speechRate: Float,
        flush: Boolean,
        skip: () -> Boolean = { false },
    ): String? {
        submitting.incrementAndGet()
        idleRelease?.cancel()
        try {
            val tts = engineMutex.withLock { ensureEngine() } ?: return null
            if (skip()) {
                Timber.d("Ansage verworfen: nach dem Start der Engine nicht mehr sprechen")
                return null
            }
            return withContext(Dispatchers.Main) {
                tts.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
                // Stimme in der App-Sprache, sofern installiert — sonst bleibt die Standardstimme.
                val locale = SpokenText.ttsLocale(context)
                if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
                val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val id = "cryptochecker-${utteranceCounter.incrementAndGet()}"
                val done = CompletableDeferred<Unit>()
                // Ohne Audio-Fokus trotzdem sprechen (Wecker-Kanal, nur ohne Absenken): Ab Android 15
                // erhalten Apps ohne Vordergrund den Fokus nicht. Telefonat / «Bitte nicht stören» /
                // lautlos prüft drain() vorher.
                if (!holdFocusUntil(done)) Timber.d("Kein Audio-Fokus: Ansage ohne Absenken")
                finished[id] = done
                if (tts.speak(text, mode, null, id) == TextToSpeech.SUCCESS) {
                    id
                } else {
                    // Abschliessen statt nur entfernen: gibt den Audio-Fokus wieder frei
                    finished.remove(id)?.complete(Unit)
                    null
                }
            }
        } finally {
            submitting.decrementAndGet()
            scheduleIdleRelease()
        }
    }

    /**
     * Audio-Fokus «kurz, andere leiser» bis [done]; abgegeben, wenn keine Ansage mehr läuft.
     * @return false, wenn Android den Fokus verweigert (ab Android 15 im Hintergrund üblich) —
     *   dann ohne Fokus sprechen; [done] gibt dann nichts frei.
     */
    private fun holdFocusUntil(done: CompletableDeferred<Unit>): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return true
        synchronized(focusLock) {
            if (focusUsers == 0) {
                val result = runCatching { audio.requestAudioFocus(focusRequest) }
                    .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
            }
            focusUsers++
        }
        done.invokeOnCompletion {
            synchronized(focusLock) {
                if (--focusUsers == 0) runCatching { audio.abandonAudioFocusRequest(focusRequest) }
            }
            scheduleIdleRelease()
        }
        return true
    }

    /** Engine nach [IDLE_RELEASE_MS] ohne Ansage freigeben (startet beim nächsten Mal neu). */
    private fun scheduleIdleRelease() {
        if (!isIdle()) return
        idleRelease?.cancel()
        idleRelease = scope.launch {
            delay(IDLE_RELEASE_MS)
            engineMutex.withLock {
                if (isIdle() && engine != null) {
                    Timber.d("Sprachausgabe freigegeben (unbenutzt)")
                    releaseEngine()
                }
            }
        }
    }

    private fun isIdle(): Boolean =
        submitting.get() == 0 && finished.isEmpty() && pendingCount.value <= 0

    private fun ringerAllowsSpeech(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return true
        return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    /** Kein Telefonat (Audio-Modus normal) und «Bitte nicht stören» aus (alle Unterbrechungen erlaubt). */
    private fun callAndDndAllowSpeech(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio != null && audio.mode != AudioManager.MODE_NORMAL) return false
        val filter = runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter
        }.getOrNull() ?: return true
        return filter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL ||
            filter == android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
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
                TextToSpeech(context, listener).apply {
                    setOnUtteranceProgressListener(progressListener)
                    // Wecker-Kanal statt Medien: Ansagen sind Alarme (Lautstärke «Wecker»)
                    setAudioAttributes(SPEECH_ATTRIBUTES)
                }
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

        /** So lange bleibt die Engine nach der letzten Ansage offen. */
        const val IDLE_RELEASE_MS = 60_000L

        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        /** Obergrenze, wie lange auf das Ende einer Ansage gewartet wird. */
        const val MAX_UTTERANCE_MS = 60_000L

        /** Grobe Sprechdauer (ca. 150 ms je Zeichen bei normalem Tempo) plus Reserve. */
        fun estimatedMillis(text: String, speechRate: Float): Long =
            (3_000L + (text.length * 150L / speechRate.coerceIn(0.5f, 2.0f)).toLong())
                .coerceAtMost(MAX_UTTERANCE_MS)
    }
}
