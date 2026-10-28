package com.cryptochecker.marketdata.model

/** Inhalt einer POST-Anfrage und allfällige zusätzliche Kopfzeilen. */
class PostRequest(val body: String, val headers: Map<String, String>? = null)
