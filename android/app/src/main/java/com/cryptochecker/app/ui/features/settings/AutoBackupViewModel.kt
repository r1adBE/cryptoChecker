package com.cryptochecker.app.ui.features.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.domain.backup.AutoBackupJobs
import com.cryptochecker.app.domain.backup.AutoBackupSettings
import com.cryptochecker.app.domain.backup.AutoBackupState
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.app.effects.BackupFolderAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Automatische Sicherung ([com.cryptochecker.app.domain.backup.AutoBackupPolicy]): Ordner wählen (dauerhafte Freigabe),
 * Passwort merken (`AutoBackupStore`), «Jetzt sichern» (`AutoBackupRunner`). Wie iOS `AutoBackupSection`.
 */
@HiltViewModel
class AutoBackupViewModel @Inject constructor(
    /** Ordnerfreigabe und Ordnername (System). */
    private val folders: BackupFolderAccess,
    private val store: AutoBackupSettings,
    private val runner: AutoBackupJobs,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    val state: StateFlow<AutoBackupState> = store.state

    /** «Jetzt sichern» läuft. */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Einschalten mit gewähltem Ordner [tree] und [password]; danach gleich die erste Sicherung. */
    fun enable(tree: Uri, password: String) = viewModelScope.launch {
        val ok = withContext(ioDispatcher) {
            folders.keep(tree) && store.enable(tree.toString(), folders.folderName(tree), password)
        }
        if (ok) backUpNow()
    }

    /** Anderer Ordner: alte Freigabe zurückgeben, neue behalten. */
    fun changeFolder(tree: Uri) = viewModelScope.launch {
        withContext(ioDispatcher) {
            if (!folders.keep(tree)) return@withContext
            store.state.value.folderUri?.let(Uri::parse)?.takeIf { it != tree }?.let(folders::release)
            store.changeFolder(tree.toString(), folders.folderName(tree))
        }
        backUpNow()
    }

    /** Ausschalten: Passwort und Ordner vergessen, Freigabe zurückgeben (vorhandene Dateien bleiben im Ordner). */
    fun disable() = viewModelScope.launch {
        withContext(ioDispatcher) {
            store.state.value.folderUri?.let(Uri::parse)?.let(folders::release)
            store.disable()
        }
    }

    fun backUpNow() {
        if (_running.value) return
        _running.value = true
        viewModelScope.launch {
            try {
                runner.run()
            } finally {
                _running.value = false
            }
        }
    }
}
