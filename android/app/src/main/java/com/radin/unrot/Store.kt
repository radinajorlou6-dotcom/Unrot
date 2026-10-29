package com.radin.unrot

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A family member. [via] is how you call them ("phone", "whatsapp", "telegram", …, see [CallApps]).
 * [phone] is a phone number or @username (optional for app calls). [appName] is their name as it
 * shows in that app's call notification, if different from [name].
 */
data class Contact(val name: String, val phone: String, val via: String = "phone", val appName: String = "") {
    val app: CallApp get() = CallApps.byId(via)
    val matchName: String get() = appName.ifBlank { name }
    /** Cooldowns and "last called" are per person, whichever way you call them. */
    val key: String get() = name.trim().lowercase()
}
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

    // ---- Small JSON maps (call cooldowns, PR minutes per day, …) ----
    fun obj(k: String): JSONObject = runCatching { JSONObject(str(k)) }.getOrNull() ?: JSONObject()
    fun putObj(k: String, o: JSONObject) = putStr(k, o.toString())

    var stepsDay: String
        get() = str("stepsDay")
        set(v) = putStr("stepsDay", v)
    var stepsPaidMin: Long
        get() = long("stepsPaidMin")
        set(v) = putLong("stepsPaidMin", v)
    /** Best run pace in seconds per km (0 = no run yet). */
    var bestPace: Long
        get() = long("bestPace")
        set(v) = putLong("bestPace", v)

    var pillX: Int
        get() = p.getInt("pillX", -1)
        set(v) = p.edit().putInt("pillX", v).apply()
    var pillY: Int
        get() = p.getInt("pillY", -1)
        set(v) = p.edit().putInt("pillY", v).apply()

    /** The last crash, shown on the main screen so bugs can be reported exactly. */
    var lastCrash: String
        get() = str("lastCrash")
        set(v) = p.edit().putString("lastCrash", v).commit().let { }

    // ---- Free-form lock-in ----
    var focusFree: Boolean
        get() = p.getBoolean("focusFree", false)
        set(v) = p.edit().putBoolean("focusFree", v).apply()
    var focusStart: Long
        get() = long("focusStart")
        set(v) = putLong("focusStart", v)
    /** A free lock-in that hit the max on its own: seconds it ran, unpaid until you say how much was real. */
    var freeReviewSec: Long
        get() = long("freeReviewSec")
        set(v) = putLong("freeReviewSec", v)
    /** That lock-in also kept the screen off (phone-down bonus owed on whatever you keep). */
    var freeReviewBonus: Boolean
        get() = p.getBoolean("freeReviewBonus", false)
        set(v) = p.edit().putBoolean("freeReviewBonus", v).apply()

    // ---- Doomscrolling today (full-price apps/sites), for the daily cap and streaks ----
    var doomDay: String
        get() = str("doomDay")
        set(v) = putStr("doomDay", v)
    var doomSec: Long
        get() = long("doomSec")
        set(v) = putLong("doomSec", v)
    var doomPrevDay: String
        get() = str("doomPrevDay")
        set(v) = putStr("doomPrevDay", v)
    var doomPrevSec: Long
        get() = long("doomPrevSec")
        set(v) = putLong("doomPrevSec", v)
    var streak: Int
        get() = p.getInt("streak", 0)
        set(v) = p.edit().putInt("streak", v).apply()

    // ---- Phone-down tracking for the current lock-in ----
    var pdStart: Long
        get() = long("pdStart")
        set(v) = putLong("pdStart", v)
    var pdScreenSec: Long
        get() = long("pdScreenSec")
        set(v) = putLong("pdScreenSec", v)
    var pdLocal: Boolean
        get() = p.getBoolean("pdLocal", false)
        set(v) = p.edit().putBoolean("pdLocal", v).apply()
    var pdFree: Boolean
        get() = p.getBoolean("pdFree", false)
        set(v) = p.edit().putBoolean("pdFree", v).apply()
    /** What the last local lock-in paid (for the phone-down bonus). */
    var lastFocusPaid: Long
        get() = long("lastFocusPaid")
        set(v) = putLong("lastFocusPaid", v)

    // ---- Lifetime counters (levels and badges) ----
    var lifeLockSec: Long
        get() = long("lifeLockSec")
        set(v) = putLong("lifeLockSec", v)
    var lifeCalls: Long
        get() = long("lifeCalls")
        set(v) = putLong("lifeCalls", v)
    var bestStreak: Int
        get() = p.getInt("bestStreak", 0)
        set(v) = p.edit().putInt("bestStreak", v).apply()
    var lifePhoneDown: Long
        get() = long("lifePhoneDown")
        set(v) = putLong("lifePhoneDown", v)
    var lifePrs: Long
        get() = long("lifePrs")
        set(v) = putLong("lifePrs", v)

    var recapWeek: String
        get() = str("recapWeek")
        set(v) = putStr("recapWeek", v)

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
                Contact(o.optString("name"), o.optString("phone"), o.optString("via", "phone"), o.optString("appName"))
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach {
                a.put(JSONObject().put("name", it.name).put("phone", it.phone).put("via", it.via).put("appName", it.appName))
            }
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
