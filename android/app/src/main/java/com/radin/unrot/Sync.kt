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
            syncRules(s)
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
            s.lastSyncError = ""
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
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code ${text.take(120)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}
