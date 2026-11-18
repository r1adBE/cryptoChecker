package com.cryptochecker.app.data.backup

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.data.widget.PortfolioSnapshotKeyStore
import com.cryptochecker.app.data.widget.PortfolioSnapshotSeal
import com.cryptochecker.app.domain.backup.AutoBackupSettings
import com.cryptochecker.app.domain.backup.AutoBackupState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ablage der automatischen Sicherung ([com.cryptochecker.app.domain.backup.AutoBackupPolicy]): Schalter, Ordner und
 * Zeiten in den SharedPreferences [PREFS] (nicht in der Systemsicherung — die Ordnerfreigabe gilt nur auf diesem Gerät),
 * das Passwort versiegelt mit einem eigenen Schlüssel im Android Keystore ([PortfolioSnapshotSeal], Alias [KEY_ALIAS]);
 * es verlässt das Gerät nie.
 */
@Singleton
class AutoBackupStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AutoBackupSettings {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keys = PortfolioSnapshotKeyStore(KEY_ALIAS)
    private val _state = MutableStateFlow(read())
    override val state: StateFlow<AutoBackupState> = _state.asStateFlow()

    private fun read(): AutoBackupState = AutoBackupState(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        folderUri = prefs.getString(KEY_FOLDER, null),
        folderName = prefs.getString(KEY_FOLDER_NAME, null),
        lastSuccessAt = prefs.getLong(KEY_SUCCESS, 0L),
        lastAttemptAt = prefs.getLong(KEY_ATTEMPT, 0L),
        lastFailed = prefs.getBoolean(KEY_FAILED, false),
    )

    private fun update(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit(commit = true) { block() }
        _state.value = read()
    }

    /** Einschalten mit Ordner und Passwort; false = Passwort nicht versiegelbar (Keystore) — dann bleibt alles aus. */
    override fun enable(folderUri: String, folderName: String?, password: String): Boolean {
        val sealed = seal(password) ?: return false
        update {
            putBoolean(KEY_ENABLED, true)
            putString(KEY_FOLDER, folderUri)
            putString(KEY_FOLDER_NAME, folderName)
            putString(KEY_PASSWORD, sealed)
            putBoolean(KEY_FAILED, false)
            putLong(KEY_ATTEMPT, 0L)
        }
        return true
    }

    /** Anderer Ordner (Passwort bleibt). */
    override fun changeFolder(folderUri: String, folderName: String?) = update {
        putString(KEY_FOLDER, folderUri)
        putString(KEY_FOLDER_NAME, folderName)
        putBoolean(KEY_FAILED, false)
        putLong(KEY_ATTEMPT, 0L)
    }

    /** Ausschalten: Passwort und Ordner vergessen, Zeiten bleiben nicht. */
    override fun disable() = update { clear() }

    fun recordAttempt(now: Long) = update { putLong(KEY_ATTEMPT, now) }

    fun recordResult(success: Boolean, now: Long) = update {
        putLong(KEY_ATTEMPT, now)
        putBoolean(KEY_FAILED, !success)
        if (success) putLong(KEY_SUCCESS, now)
    }

    /** Gemerktes Passwort; null = keines oder Schlüssel nicht verfügbar. */
    fun password(): String? {
        val sealed = prefs.getString(KEY_PASSWORD, null) ?: return null
        val key = (keys.lookup() as? PortfolioSnapshotSeal.KeyLookup.Found)?.key ?: return null
        return runCatching { PortfolioSnapshotSeal.open(key, sealed) }.getOrNull()
    }

    private fun seal(password: String): String? {
        val key = (keys.lookup() as? PortfolioSnapshotSeal.KeyLookup.Found)?.key ?: return null
        return runCatching { PortfolioSnapshotSeal.seal(key, password) }.onFailure { keys.discardIfBroken(it) }.getOrNull()
    }

    private companion object {
        const val PREFS = "auto_backup"
        const val KEY_ALIAS = "cryptochecker_auto_backup_password"
        const val KEY_ENABLED = "enabled"
        const val KEY_FOLDER = "folder_uri"
        const val KEY_FOLDER_NAME = "folder_name"
        const val KEY_PASSWORD = "password_sealed"
        const val KEY_SUCCESS = "last_success_at"
        const val KEY_ATTEMPT = "last_attempt_at"
        const val KEY_FAILED = "last_failed"
    }
}
