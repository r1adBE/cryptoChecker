package com.cryptochecker.app.effects

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject

/** Gewählte Datei (Dateiauswahl des Systems) zum Lesen öffnen; null = nicht lesbar. */
fun interface DocumentReader {
    fun openInputStream(uri: Uri): InputStream?
}

/** Über den ContentResolver der App. */
class ContentResolverDocumentReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DocumentReader {
    override fun openInputStream(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)
}
