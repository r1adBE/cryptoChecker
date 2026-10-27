package com.cryptochecker.app.data

import com.cryptochecker.app.util.json.JsonArray
import com.cryptochecker.app.util.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject

// Übergang zwischen org.json (Codecs in :data/:app) und dem eigenen JSON von :domain (Build 153, Schritt zu Kotlin
// Multiplatform): über den Text, beide lesen und schreiben gleich (`JsonCompatibilityTest`).

/** org.json → JSON von `:domain`. */
fun JSONObject.asDomainJson(): JsonObject = JsonObject(toString())

/** org.json → JSON von `:domain`. */
fun JSONArray.asDomainJson(): JsonArray = JsonArray(toString())

/** JSON von `:domain` → org.json. */
fun JsonObject.asOrgJson(): JSONObject = JSONObject(toString())

/** JSON von `:domain` → org.json. */
fun JsonArray.asOrgJson(): JSONArray = JSONArray(toString())
