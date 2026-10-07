package com.cryptochecker.app.lock

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entsperren mit Biometrie (schwach/stark) ODER Geräte-Sperre (PIN, Muster, Passwort),
 * über androidx.biometric.
 *  - ab API 30: setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
 *  - API 26–29: BIOMETRIC_WEAK mit setDeviceCredentialAllowed(true) (dort so verlangt)
 * Mit Geräte-Sperre als Ausweg gibt es keinen «Abbrechen»-Knopf (negativeButton).
 */
object AppLockAuth {

    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Kann dieses Gerät die App entsperren (Biometrie oder eingerichtete Displaysperre)? */
    fun canAuthenticate(context: Context): Boolean {
        val biometric = runCatching {
            BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
        if (biometric) return true
        // Vor API 30 prüft canAuthenticate die Geräte-Sperre nicht überall zuverlässig
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return keyguard?.isDeviceSecure == true
    }

    /**
     * Zeigt die System-Abfrage. [onResult] true = entsperrt; false = abgebrochen oder
     * Fehler (einzelne Fehlversuche lässt die Abfrage selbst wiederholen).
     */
    fun authenticate(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Timber.d("App-Sperre: %d %s", errorCode, errString)
                    onResult(false)
                }
            }
        )
        val builder = BiometricPrompt.PromptInfo.Builder().setTitle(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(AUTHENTICATORS)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        runCatching { prompt.authenticate(builder.build()) }
            .onFailure {
                Timber.w(it, "App-Sperre: Abfrage nicht möglich")
                onResult(false)
            }
    }
}

/** Die FragmentActivity hinter einem (Compose-)Context, falls es eine gibt. */
tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/**
 * Zustand der App-Sperre für die laufende App (Prozess).
 *
 * Gesperrt wird beim Kaltstart (neuer Prozess) und nach mehr als [BACKGROUND_LIMIT_MILLIS]
 * im Hintergrund — aber nur, wenn die Einstellung «App-Sperre» an ist (das prüft die
 * Oberfläche, weil die Einstellungen beim Kaltstart erst geladen werden müssen).
 */
@Singleton
class AppLockState @Inject constructor() {

    /** Startwert true: ein neuer Prozess beginnt gesperrt (sofern die Sperre an ist). */
    private val _lockRequested = MutableStateFlow(true)
    val lockRequested: StateFlow<Boolean> = _lockRequested.asStateFlow()

    /** Zeitpunkt, seit dem die App im Hintergrund ist; 0 = im Vordergrund. */
    @Volatile
    private var backgroundSince = 0L

    /** Läuft gerade eine Abfrage? Dann zählt deren Fenster nicht als «Hintergrund». */
    @Volatile
    var authenticating = false

    /**
     * Kaltstart der Oberfläche (Activity ohne gespeicherten Zustand). Ein neuer Prozess ist
     * ohnehin gesperrt (Startwert). Baut ein Tipp auf Mitteilung oder Widget die Activity
     * neu auf (FLAG_ACTIVITY_CLEAR_TOP), während die App sichtbar ist oder erst kurz im
     * Hintergrund war, wird nicht erneut gesperrt — sonst verlangte jeder Tipp auf einen
     * Alarm das Entsperren. Länger als [BACKGROUND_LIMIT_MILLIS] weg: sperrt [onForeground].
     */
    fun onColdStart(now: Long = System.currentTimeMillis()) {
        authenticating = false
        val since = backgroundSince
        if (since > 0L && now - since > BACKGROUND_LIMIT_MILLIS) _lockRequested.value = true
    }

    fun onBackground(now: Long = System.currentTimeMillis()) {
        if (authenticating) return
        backgroundSince = now
    }

    fun onForeground(now: Long = System.currentTimeMillis()) {
        val since = backgroundSince
        backgroundSince = 0L
        if (since > 0L && now - since > BACKGROUND_LIMIT_MILLIS) _lockRequested.value = true
        // Eine Abfrage, die nie zurückgemeldet hat (z. B. nicht angezeigt), blockiert sonst den Knopf
        authenticating = false
    }

    /** Erfolgreich entsperrt (oder Sperre gerade eingeschaltet). */
    fun unlock() {
        _lockRequested.value = false
    }

    companion object {
        const val BACKGROUND_LIMIT_MILLIS = 60_000L
    }
}
