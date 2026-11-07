package com.radin.unrot

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Shares the balance with your laptop through a free Firebase Realtime Database.
 * Each device writes only its own totals; everyone reads everyone.
 */
object Sync {
    fun configured(s: Store) = s.dbUrl.startsWith("https://") && s.syncCode.length >= 8

    fun newCode(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..20).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun base(s: Store) = s.dbUrl.trim().trimEnd('/') + "/unrot/" + s.syncCode.trim()

    /** Blocking. Pushes this phone's totals and pulls the others'. */
    fun run(s: Store): Boolean {
        if (!configured(s)) return false
        return try {
            // Pause / resume / finish sent from your laptop for a lock-in running here.
            val cmdUrl = base(s) + "/cmd/" + s.deviceId + ".json"
            val cmd = http("GET", cmdUrl, null).trim()
            if (cmd.isNotEmpty() && cmd != "null") {
                runCatching { JSONObject(cmd) }.getOrNull()?.let { Ledger.applyCommand(s, it) }
                http("DELETE", cmdUrl, null)
            }
            // Rules sync problems are reported but don't stop your balance from syncing.
            val rulesErr = runCatching { syncRules(s) }.exceptionOrNull()?.let { "Rules: " + (it.message ?: it.javaClass.simpleName) }
            // Your installed apps, so blocked apps can be picked from the laptop too.
            if (s.phoneAppsJson.isNotEmpty() && s.phoneAppsJson != s.phoneAppsSent) {
                http("PUT", base(s) + "/phoneApps.json", s.phoneAppsJson)
                s.phoneAppsSent = s.phoneAppsJson
            }
            val st = Rules.current(s)
            val mine = JSONObject()
                .put("name", "phone")
                .put("earned", s.earnedSec)
                .put("spent", s.spentSec)
                .put("focusUntil", s.focusUntil)
                .put("focusFree", s.focusFree)
                .put("doomDay", s.doomDay)
                .put("doomSec", s.doomSec)
                .put("doomPrevDay", s.doomPrevDay)
                .put("doomPrevSec", s.doomPrevSec)
                .put("focusId", s.focusId)
                .put("focusStart", s.focusStart)
                .put("focusPlanned", s.focusPlannedSec)
                .put("focusPaused", s.focusPausedAt)
                .put("focusRatio", if (s.focusFree) st.freeRatio else st.focusRatio)
                .put("earlyPct", st.n("earlyEndPct"))
                .put("rateNow", UnrotService.currentRate)
                .put("week", Recap.totals(s))
                .put("updated", System.currentTimeMillis())
            http("PUT", base(s) + "/devices/" + s.deviceId + ".json", mine.toString())
            val all = http("GET", base(s) + "/devices.json", null)
            s.remoteJson = if (all.trim() == "null") "{}" else all
            s.lastSyncTs = System.currentTimeMillis()
            s.lastSyncError = rulesErr ?: ""
            true
        } catch (e: Exception) {
            s.lastSyncError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /**
     * One set of rules for both devices: whichever changed them last wins (every change already went
     * through the 24-hour wait on the device where it was made). The very first time, they're merged.
     */
    private fun syncRules(s: Store) {
        val url = base(s) + "/rules.json"
        val raw = http("GET", url, null).trim()
        val remote = if (raw.isEmpty() || raw == "null") null else runCatching { JSONObject(raw) }.getOrNull()
        val push: Boolean
        if (remote == null) {
            push = true
        } else if (!s.rulesSynced) {
            Rules.mergeFirst(s, remote)
            s.rulesSynced = true // merged once; from now on it's just "newest wins"
            push = true
        } else {
            val theirs = remote.optLong("updated")
            push = when {
                theirs > s.rulesUpdated -> { Rules.adopt(s, remote); false }
                s.rulesUpdated > theirs -> true
                else -> false
            }
        }
        if (push) {
            if (s.rulesUpdated == 0L) s.rulesUpdated = System.currentTimeMillis()
            http("PUT", url, Rules.sharedDoc(s).toString())
        }
        s.rulesSynced = true
    }

    /** Blocking. Sends a pause/resume/finish to the device running [sess]. */
    fun sendCommand(s: Store, sess: Ledger.Session, cmd: JSONObject) {
        http("PUT", base(s) + "/cmd/" + sess.deviceId + ".json", cmd.toString())
    }

    private fun http(method: String, url: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(FbKeys.encode(body).toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code ${text.take(120)}")
            return FbKeys.decode(text)
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Firebase doesn't allow . $ # [ ] / in key names (a site like "learn.uwaterloo.ca" as a key gets a
 * 400 Bad Request), so keys are escaped on the way up and unescaped on the way down. Same on the laptop.
 */
object FbKeys {
    private val MAP = listOf("%" to "%25", "." to "%2E", "$" to "%24", "#" to "%23", "[" to "%5B", "]" to "%5D", "/" to "%2F")

    private fun enc(k: String) = MAP.fold(k) { acc, (raw, esc) -> acc.replace(raw, esc) }
    private fun dec(k: String) = if ('%' !in k) k else MAP.asReversed().fold(k) { acc, (raw, esc) -> acc.replace(esc, raw, ignoreCase = true) }

    fun encode(json: String): String = transform(json, ::enc)
    fun decode(json: String): String = if (json.isBlank() || json.trim() == "null") json else transform(json, ::dec)

    private fun transform(json: String, key: (String) -> String): String = runCatching {
        when (val v = org.json.JSONTokener(json).nextValue()) {
            is JSONObject -> walk(v, key).toString()
            is org.json.JSONArray -> walk(v, key).toString()
            else -> json
        }
    }.getOrDefault(json)

    private fun walk(v: Any?, key: (String) -> String): Any? = when (v) {
        is JSONObject -> JSONObject().also { out -> v.keys().forEach { k -> out.put(key(k), walk(v.get(k), key)) } }
        is org.json.JSONArray -> org.json.JSONArray().also { out -> for (i in 0 until v.length()) out.put(walk(v.get(i), key)) }
        else -> v
    }
}
