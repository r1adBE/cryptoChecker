package com.cryptochecker.app.domain.activity

import kotlinx.coroutines.flow.StateFlow

/** Aktivitäts-Berichte je Watch-Id (Umsetzung: `ActivityRepository`). */
interface ActivityReports {
    val reports: StateFlow<Map<Long, ActivityReport>>
}
