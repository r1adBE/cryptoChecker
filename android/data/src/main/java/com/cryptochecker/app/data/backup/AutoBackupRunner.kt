package com.cryptochecker.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.cryptochecker.app.domain.backup.AutoBackupPolicy
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.util.appScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Führt die automatische Sicherung aus ([AutoBackupPolicy]): neue Datei im gewählten Ordner, verschlüsselt mit dem
 * gemerkten Passwort und zurückgelesen ([BackupManager.export]), danach ältere eigene Dateien über [AutoBackupPolicy.KEEP]
 * hinaus löschen. Ein Lauf zur Zeit; Fehler landen im Zustand ([AutoBackupState.lastFailed]) und im Fehlerprotokoll.
 */
@Singleton
class AutoBackupRunner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: AutoBackupStore,
    private val backupManager: BackupManager,
    @param:com.cryptochecker.app.domain.util.IoDispatcher private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {
    private val mutex = Mutex()

    /** Eigener Bereich: ein Lauf überdauert das Schliessen der Activity. */
    private val scope = appScope(ioDispatcher, "Automatische Sicherung")

    /** Beim Öffnen der App ([runIfDue]) — kehrt sofort zurück. */
    fun startIfDue() {
        if (!store.state.value.enabled) return
        scope.launch { runIfDue() }
    }

    /** Beim Öffnen der App: nur wenn fällig ([AutoBackupPolicy.isDue]). */
    suspend fun runIfDue(now: Long = System.currentTimeMillis()) {
        val s = store.state.value
        if (!AutoBackupPolicy.isDue(s.enabled, s.lastSuccessAt, s.lastAttemptAt, now)) return
        run(now)
    }

    /** «Jetzt sichern»; false = gescheitert oder nicht eingerichtet. */
    suspend fun run(now: Long = System.currentTimeMillis()): Boolean = mutex.withLock {
        val s = store.state.value
        val folder = s.folderUri?.let(Uri::parse)
        if (!s.enabled || folder == null) return false
        store.recordAttempt(now)
        val ok = runCatchingCancellable { backUp(folder, now) }
            .onFailure { Timber.w(it, "Automatische Sicherung fehlgeschlagen") }
            .isSuccess
        store.recordResult(ok, now)
        ok
    }

    private suspend fun backUp(tree: Uri, now: Long) {
        val password = checkNotNull(store.password()) { "Passwort nicht verfügbar" }
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = AutoBackupPolicy.fileName(now, ZoneId.systemDefault())
        val file = checkNotNull(DocumentsContract.createDocument(resolver, parent, MIME, name)) { "Datei nicht angelegt" }
        try {
            backupManager.export(file, password)
        } catch (e: Exception) {
            // Halbe oder ungeprüfte Datei nicht liegen lassen
            runCatchingCancellable { DocumentsContract.deleteDocument(resolver, file) }
            throw e
        }
        prune(tree)
    }

    /** Eigene Dateien über die neuesten [AutoBackupPolicy.KEEP] hinaus löschen; fremde nie. */
    private fun prune(tree: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val files = HashMap<String, String>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val display = c.getString(1) ?: continue
                files[display] = id
            }
        }
        AutoBackupPolicy.toDelete(files.keys.toList()).forEach { display ->
            val id = files[display] ?: return@forEach
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
                .onFailure { Timber.w(it, "Alte automatische Sicherung nicht gelöscht") }
        }
    }

    private companion object {
        const val MIME = "application/json"
    }
}
