package com.cryptochecker.app.data

import kotlinx.coroutines.flow.SharedFlow

interface HttpLogger {
    val messageFlow: SharedFlow<String>
}