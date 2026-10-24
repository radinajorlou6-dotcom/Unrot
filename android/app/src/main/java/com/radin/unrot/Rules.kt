package com.radin.unrot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Your rules. Ratios mean "minutes of real stuff per 1 minute of scrolling",
 * so 3.0 = lock in for 3 minutes, earn 1 minute.
 */
data class Settings(
    val workoutRatio: Double = 3.0,
    val focusRatio: Double = 3.0,
    val callRatio: Double = 3.0,
    val dailyBaseMin: Int = 10,
    val maxBankMin: Int = 180,
    val checkInMin: Int = 10,
    val bedStart: Int = 23 * 60,
    val bedEnd: Int = 7 * 60,
    val bedWaitSec: Int = 60,
    val countWalks: Boolean = false,
    val protection: Boolean = true,
    val flagged: Set<String> = DEFAULT_FLAGGED,
) {
    val bedtimeOn: Boolean get() = bedStart != bedEnd

    fun inBedtime(minuteOfDay: Int): Boolean = when {
        !bedtimeOn -> false
        bedStart < bedEnd -> minuteOfDay in bedStart until bedEnd
        else -> minuteOfDay >= bedStart || minuteOfDay < bedEnd
    }

    fun toJson(): JSONObject = JSONObject()
        .put("workoutRatio", workoutRatio)
        .put("focusRatio", focusRatio)
        .put("callRatio", callRatio)
        .put("dailyBaseMin", dailyBaseMin)
        .put("maxBankMin", maxBankMin)
        .put("checkInMin", checkInMin)
        .put("bedStart", bedStart)
        .put("bedEnd", bedEnd)
        .put("bedWaitSec", bedWaitSec)
        .put("countWalks", countWalks)
        .put("protection", protection)
        .put("flagged", JSONArray().also { a -> flagged.forEach { a.put(it) } })

    companion object {
        val DEFAULT_FLAGGED = setOf(
            "com.zhiliaoapp.musically",      // TikTok
            "com.ss.android.ugc.trill",      // TikTok (other regions)
            "com.instagram.android",
            "com.google.android.youtube",
            "com.reddit.frontpage",
            "com.twitter.android",
            "com.snapchat.android",
            "com.facebook.katana",
        )

        fun fromJson(raw: String): Settings {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return Settings()
            val d = Settings()
            val fl = o.optJSONArray("flagged")
            return Settings(
                workoutRatio = o.optDouble("workoutRatio", d.workoutRatio),
                focusRatio = o.optDouble("focusRatio", d.focusRatio),
                callRatio = o.optDouble("callRatio", d.callRatio),
                dailyBaseMin = o.optInt("dailyBaseMin", d.dailyBaseMin),
                maxBankMin = o.optInt("maxBankMin", d.maxBankMin),
                checkInMin = o.optInt("checkInMin", d.checkInMin),
                bedStart = o.optInt("bedStart", d.bedStart),
                bedEnd = o.optInt("bedEnd", d.bedEnd),
                bedWaitSec = o.optInt("bedWaitSec", d.bedWaitSec),
                countWalks = o.optBoolean("countWalks", d.countWalks),
                protection = o.optBoolean("protection", d.protection),
                flagged = if (fl == null) d.flagged else (0 until fl.length()).map { fl.getString(it) }.toSet(),
            )
        }
    }
}

data class Pending(val key: String, val value: String, val applyAt: Long, val label: String)

/**
 * Stricter changes apply instantly. Looser changes wait 24 hours,
 * unless the PIN holder has unlocked Unrot in the last 10 minutes.
 */
object Rules {
    const val DELAY_MS = 24L * 60 * 60 * 1000

    fun current(s: Store): Settings = synchronized(s.lock) {
        var st = Settings.fromJson(s.settingsJson)
        val pend = pending(s)
        val now = System.currentTimeMillis()
        val due = pend.filter { it.applyAt <= now }
        if (due.isNotEmpty()) {
            due.forEach { st = applyKey(st, it.key, it.value) }
            save(s, st, pend - due.toSet())
            due.forEach { s.log("Rule change kicked in: ${it.label}") }
        }
        st
    }

    fun pending(s: Store): List<Pending> {
        val a = runCatching { JSONArray(s.pendingJson) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Pending(o.optString("key"), o.optString("value"), o.optLong("applyAt"), o.optString("label"))
        }
    }

    private fun save(s: Store, st: Settings, pend: List<Pending>) {
        s.settingsJson = st.toJson().toString()
        val a = JSONArray()
        pend.forEach {
            a.put(JSONObject().put("key", it.key).put("value", it.value).put("applyAt", it.applyAt).put("label", it.label))
        }
        s.pendingJson = a.toString()
    }

    fun cancel(s: Store, key: String) = synchronized(s.lock) {
        val st = current(s)
        save(s, st, pending(s).filter { it.key != key })
    }

    private fun applyKey(st: Settings, key: String, v: String): Settings = when {
        key == "workoutRatio" -> st.copy(workoutRatio = v.toDouble())
        key == "focusRatio" -> st.copy(focusRatio = v.toDouble())
        key == "callRatio" -> st.copy(callRatio = v.toDouble())
        key == "dailyBaseMin" -> st.copy(dailyBaseMin = v.toInt())
        key == "maxBankMin" -> st.copy(maxBankMin = v.toInt())
        key == "checkInMin" -> st.copy(checkInMin = v.toInt())
        key == "bedtime" -> {
            val (a, b) = v.split("-").map { it.toInt() }
            st.copy(bedStart = a, bedEnd = b)
        }
        key == "bedWaitSec" -> st.copy(bedWaitSec = v.toInt())
        key == "countWalks" -> st.copy(countWalks = v.toBoolean())
        key == "protection" -> st.copy(protection = v.toBoolean())
        key.startsWith("unflag:") -> st.copy(flagged = st.flagged - key.removePrefix("unflag:"))
        else -> st
    }

    /** Minutes of the day covered by a bedtime window. */
    private fun bedMinutes(start: Int, end: Int): Set<Int> {
        val tmp = Settings(bedStart = start, bedEnd = end)
        return (0 until 1440).filter { tmp.inBedtime(it) }.toSet()
    }

    data class Result(val applied: Int, val delayed: Int)

    fun propose(s: Store, new: Settings, labelFor: (String) -> String = { it }): Result = synchronized(s.lock) {
        val old = current(s)
        var st = old
        val pend = pending(s).toMutableList()
        val unlocked = Pin.unlocked(s)
        val now = System.currentTimeMillis()
        var applied = 0
        var delayed = 0

        fun handle(key: String, oldV: String, newV: String, looser: Boolean, label: String) {
            if (oldV == newV) return
            pend.removeAll { it.key == key }
            if (looser && !unlocked) {
                pend.add(Pending(key, newV, now + DELAY_MS, label))
                delayed++
            } else {
                st = applyKey(st, key, newV)
                applied++
            }
        }

        handle("workoutRatio", old.workoutRatio.toString(), new.workoutRatio.toString(),
            new.workoutRatio < old.workoutRatio, "Workout ratio → ${fmtRatio(new.workoutRatio)}:1")
        handle("focusRatio", old.focusRatio.toString(), new.focusRatio.toString(),
            new.focusRatio < old.focusRatio, "Lock-in ratio → ${fmtRatio(new.focusRatio)}:1")
        handle("callRatio", old.callRatio.toString(), new.callRatio.toString(),
            new.callRatio < old.callRatio, "Call ratio → ${fmtRatio(new.callRatio)}:1")
        handle("dailyBaseMin", old.dailyBaseMin.toString(), new.dailyBaseMin.toString(),
            new.dailyBaseMin > old.dailyBaseMin, "Free daily minutes → ${new.dailyBaseMin}")
        handle("maxBankMin", old.maxBankMin.toString(), new.maxBankMin.toString(),
            new.maxBankMin > old.maxBankMin, "Max saved time → ${new.maxBankMin} min")
        val checkLooser = when {
            new.checkInMin == 0 -> old.checkInMin != 0
            old.checkInMin == 0 -> false
            else -> new.checkInMin > old.checkInMin
        }
        handle("checkInMin", old.checkInMin.toString(), new.checkInMin.toString(), checkLooser,
            if (new.checkInMin == 0) "Scroll check-ins → off" else "Scroll check-in → every ${new.checkInMin} min")
        val bedLooser = !bedMinutes(new.bedStart, new.bedEnd).containsAll(bedMinutes(old.bedStart, old.bedEnd))
        handle("bedtime", "${old.bedStart}-${old.bedEnd}", "${new.bedStart}-${new.bedEnd}", bedLooser,
            if (new.bedtimeOn) "Bedtime → ${fmtClock(new.bedStart)}–${fmtClock(new.bedEnd)}" else "Bedtime → off")
        handle("bedWaitSec", old.bedWaitSec.toString(), new.bedWaitSec.toString(),
            new.bedWaitSec < old.bedWaitSec, "Bedtime wait → ${new.bedWaitSec}s")
        handle("countWalks", old.countWalks.toString(), new.countWalks.toString(),
            new.countWalks && !old.countWalks, "Count walks → ${if (new.countWalks) "on" else "off"}")
        handle("protection", old.protection.toString(), new.protection.toString(),
            !new.protection, "Protection → ${if (new.protection) "on" else "off"}")

        // Blocking a new app is instant; unblocking one waits.
        val added = new.flagged - old.flagged
        if (added.isNotEmpty()) {
            st = st.copy(flagged = st.flagged + added)
            added.forEach { pkg -> pend.removeAll { it.key == "unflag:$pkg" } }
            applied += added.size
        }
        for (pkg in old.flagged - new.flagged) {
            handle("unflag:$pkg", "0", "1", true, "Unblock ${labelFor(pkg)}")
        }

        save(s, st, pend)
        Result(applied, delayed)
    }

    fun fmtRatio(r: Double): String = if (r == r.toLong().toDouble()) r.toLong().toString() else "%.1f".format(r)
    fun fmtClock(m: Int): String {
        val h = (m / 60) % 24
        val mm = m % 60
        val ampm = if (h < 12) "am" else "pm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return if (mm == 0) "$h12$ampm" else "$h12:${"%02d".format(mm)}$ampm"
    }
}
