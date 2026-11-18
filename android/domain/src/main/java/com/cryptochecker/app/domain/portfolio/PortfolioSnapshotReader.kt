package com.cryptochecker.app.domain.portfolio

/** Letzte Momentaufnahme des Portfolios lesen (Widget-Ablage); null = keine oder nicht lesbar. */
fun interface PortfolioSnapshotReader {
    fun snapshot(): PortfolioSnapshot?
}
