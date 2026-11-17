package com.cryptochecker.app.ui.features.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.backup.AutoBackupRunner
import com.cryptochecker.app.data.backup.AutoBackupState
import com.cryptochecker.app.data.backup.AutoBackupStore
import com.cryptochecker.app.domain.util.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Automatische Sicherung ([com.cryptochecker.app.domain.backup.AutoBackupPolicy]): Ordner wählen (dauerhafte Freigabe),
 * Passwort merken ([AutoBackupStore]), «Jetzt sichern» ([AutoBackupRunner]). Wie iOS `AutoBackupSection`.
 */
@HiltViewModel
class AutoBackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: AutoBackupStore,
    private val runner: AutoBackupRunner,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    val state: StateFlow<AutoBackupState> = store.state

    /** «Jetzt sichern» läuft. */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Einschalten mit gewähltem Ordner [tree] und [password]; danach gleich die erste Sicherung. */
    fun enable(tree: Uri, password: String) = viewModelScope.launch {
        val ok = withContext(ioDispatcher) {
            keep(tree) && store.enable(tree.toString(), folderName(tree), password)
        }
        if (ok) backUpNow()
    }

    /** Anderer Ordner: alte Freigabe zurückgeben, neue behalten. */
    fun changeFolder(tree: Uri) = viewModelScope.launch {
        withContext(ioDispatcher) {
            if (!keep(tree)) return@withContext
            store.state.value.folderUri?.let(Uri::parse)?.takeIf { it != tree }?.let(::release)
            store.changeFolder(tree.toString(), folderName(tree))
        }
        backUpNow()
    }

    /** Ausschalten: Passwort und Ordner vergessen, Freigabe zurückgeben (vorhandene Dateien bleiben im Ordner). */
    fun disable() = viewModelScope.launch {
        withContext(ioDispatcher) {
            store.state.value.folderUri?.let(Uri::parse)?.let(::release)
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

    private fun keep(tree: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }.isSuccess

    private fun release(tree: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /** Anzeigename des Ordners; unbekannt → null. */
    private fun folderName(tree: Uri): String? = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
