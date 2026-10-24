package com.radin.unrot

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Contact(val name: String, val phone: String)
data class Note(val type: String, val text: String, val ts: Long)
data class LogLine(val text: String, val ts: Long)

/** Everything Unrot remembers, kept in one SharedPreferences file. */
class Store private constructor(ctx: Context) {
    val p: SharedPreferences = ctx.getSharedPreferences("unrot", Context.MODE_PRIVATE)
    val lock = Any()

    init {
        if (!p.contains("installTs")) {
            val now = System.currentTimeMillis()
            p.edit()
                .putLong("installTs", now)
                .putLong("lastCallTs", now)
                .putString("deviceId", "phone-" + UUID.randomUUID().toString().take(8))
                .apply()
        }
    }

    private fun long(k: String) = p.getLong(k, 0L)
    private fun putLong(k: String, v: Long) = p.edit().putLong(k, v).apply()
    private fun str(k: String) = p.getString(k, "") ?: ""
    private fun putStr(k: String, v: String) = p.edit().putString(k, v).apply()

    val installTs: Long get() = long("installTs")
    val deviceId: String get() = str("deviceId")

    var earnedSec: Long
        get() = long("earnedSec")
        set(v) = putLong("earnedSec", v)
    var spentSec: Long
        get() = long("spentSec")
        set(v) = putLong("spentSec", v)

    var lastCallTs: Long
        get() = long("lastCallTs")
        set(v) = putLong("lastCallTs", v)
    var lastBaseDay: String
        get() = str("lastBaseDay")
        set(v) = putStr("lastBaseDay", v)

    var focusUntil: Long
        get() = long("focusUntil")
        set(v) = putLong("focusUntil", v)
    var focusPlannedSec: Long
        get() = long("focusPlannedSec")
        set(v) = putLong("focusPlannedSec", v)

    var pinHash: String
        get() = str("pinHash")
        set(v) = putStr("pinHash", v)
    var pinSalt: String
        get() = str("pinSalt")
        set(v) = putStr("pinSalt", v)
    var adminUntil: Long
        get() = long("adminUntil")
        set(v) = putLong("adminUntil", v)

    var dbUrl: String
        get() = str("dbUrl")
        set(v) = putStr("dbUrl", v)
    var syncCode: String
        get() = str("syncCode")
        set(v) = putStr("syncCode", v)
    var remoteJson: String
        get() = str("remoteJson")
        set(v) = putStr("remoteJson", v)
    var lastSyncTs: Long
        get() = long("lastSyncTs")
        set(v) = putLong("lastSyncTs", v)
    var lastSyncError: String
        get() = str("lastSyncError")
        set(v) = putStr("lastSyncError", v)

    var settingsJson: String
        get() = str("settings")
        set(v) = putStr("settings", v)
    var pendingJson: String
        get() = str("pending")
        set(v) = putStr("pending", v)

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    // ---- Health Connect sessions that were already turned into minutes ----
    var creditedIds: Set<String>
        get() {
            val a = runCatching { JSONArray(str("creditedIds")) }.getOrNull() ?: return emptySet()
            return (0 until a.length()).map { a.getString(it) }.toSet()
        }
        set(v) {
            val a = JSONArray()
            v.toList().takeLast(400).forEach { a.put(it) }
            putStr("creditedIds", a.toString())
        }

    // ---- Family ----
    var family: List<Contact>
        get() {
            val a = runCatching { JSONArray(str("family")) }.getOrNull() ?: return emptyList()
            return (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Contact(o.optString("name"), o.optString("phone"))
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("name", it.name).put("phone", it.phone)) }
            putStr("family", a.toString())
        }

    // ---- Brain dumps and tomorrow lists ----
    var notes: List<Note>
        get() {
            val a = runCatching { JSONArray(str("notes")) }.getOrNull() ?: return emptyList()
            return (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Note(o.optString("type"), o.optString("text"), o.optLong("ts"))
            }
        }
        set(v) {
            val a = JSONArray()
            v.takeLast(60).forEach { a.put(JSONObject().put("type", it.type).put("text", it.text).put("ts", it.ts)) }
            putStr("notes", a.toString())
        }

    fun addNote(type: String, text: String) {
        notes = notes + Note(type, text, System.currentTimeMillis())
    }

    // ---- Activity log (newest first) ----
    val log: List<LogLine>
        get() {
            val a = runCatching { JSONArray(str("log")) }.getOrNull() ?: return emptyList()
            return (0 until a.length()).map {
                val o = a.getJSONObject(it)
                LogLine(o.optString("text"), o.optLong("ts"))
            }
        }

    fun log(text: String) = synchronized(lock) {
        val a = JSONArray()
        a.put(JSONObject().put("text", text).put("ts", System.currentTimeMillis()))
        log.take(39).forEach { a.put(JSONObject().put("text", it.text).put("ts", it.ts)) }
        putStr("log", a.toString())
    }

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) {
            inst ?: Store(ctx.applicationContext).also { inst = it }
        }
    }
}
