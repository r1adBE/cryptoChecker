package com.cryptochecker.app.effects

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Ordner der automatischen Sicherung: dauerhafte Freigabe (SAF) behalten bzw. zurückgeben, Anzeigename lesen. */
interface BackupFolderAccess {
    /** Freigabe für [tree] dauerhaft behalten; false = nicht möglich. */
    fun keep(tree: Uri): Boolean

    /** Freigabe zurückgeben; Fehler bleiben still. */
    fun release(tree: Uri)

    /** Anzeigename des Ordners; unbekannt → null. */
    fun folderName(tree: Uri): String?
}

/** Über den ContentResolver der App. */
class ContentResolverBackupFolderAccess @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : BackupFolderAccess {
    override fun keep(tree: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }.isSuccess

    override fun release(tree: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    override fun folderName(tree: Uri): String? = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
