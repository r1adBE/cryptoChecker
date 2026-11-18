package com.cryptochecker.app.domain.diagnostics

import kotlinx.coroutines.flow.SharedFlow

/** Mitgeschnittene HTTP-Zeilen für das Entwickler-Protokoll im Explorer. */
interface HttpLogger {
    val messageFlow: SharedFlow<String>
}
