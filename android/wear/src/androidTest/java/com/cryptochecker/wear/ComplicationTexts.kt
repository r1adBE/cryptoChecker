package com.cryptochecker.wear

import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationText
import java.time.Instant

/** Gezeigter Text einer Komplikation zum Zeitpunkt [at] (wie ihn das Zifferblatt zeichnet). */
internal fun ComplicationText.shown(context: Context, at: Instant = Instant.now()): String =
    getTextAt(context.resources, at).toString()
