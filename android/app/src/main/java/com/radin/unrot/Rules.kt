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
    /** Free-form lock-in (stop whenever): minutes per 1 minute earned. */
    val freeRatio: Double = 5.0,
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
    /** All the simple whole-number rules (see [NumRules]); missing keys use their defaults. */
    val nums: Map<String, Int> = emptyMap(),
    /** Blocked apps that cost less (games, movies): see the engagingCostPct rule. */
    val halfRate: Set<String> = emptySet(),
    /** Blocked websites (in Brave, Chrome and other browsers). */
    val sites: Set<String> = DEFAULT_SITES,
) {
    val bedtimeOn: Boolean get() = bedStart != bedEnd

    fun n(key: String): Int = nums[key] ?: NumRules.byKey(key).def

    fun withNum(key: String, v: Int): Settings = copy(nums = nums + (key to v))

    fun inBedtime(minuteOfDay: Int): Boolean = when {
        !bedtimeOn -> false
        bedStart < bedEnd -> minuteOfDay in bedStart until bedEnd
        else -> minuteOfDay >= bedStart || minuteOfDay < bedEnd
    }

    fun toJson(): JSONObject = JSONObject()
        .put("workoutRatio", workoutRatio)
        .put("focusRatio", focusRatio)
        .put("freeRatio", freeRatio)
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
        .put("nums", JSONObject().also { o -> nums.forEach { (k, v) -> o.put(k, v) } })
        .put("halfRate", JSONArray().also { a -> halfRate.forEach { a.put(it) } })
        .put("sites", JSONArray().also { a -> sites.forEach { a.put(it) } })

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

        /** Movie sites, blocked at the cheaper "engaging" rate. */
        val MOVIE_SITES = setOf("cinejoy.pk", "netflix.com", "disneyplus.com", "primevideo.com", "crunchyroll.com")

        /**
         * Does [host] belong to blocked site [site]? Also matches the same name on a different ending
         * (cinejoy.pk → cinejoy.to), since movie sites hop domains. Short names like "x" must match exactly.
         */
        fun siteMatches(host: String, site: String): Boolean {
            if (host == site || host.endsWith(".$site")) return true
            val base = site.substringBefore('.')
            return base.length >= 5 && host.split('.').dropLast(1).contains(base)
        }

        val DEFAULT_SITES = setOf(
            "youtube.com", "instagram.com", "x.com", "twitter.com", "tiktok.com",
            "reddit.com", "facebook.com", "snapchat.com",
        )

        /** Streaming apps that count as "engaging" (cheaper) by default. Installed games are added too. */
        val STREAMING = setOf(
            "com.netflix.mediaclient", "com.disney.disneyplus", "com.amazon.avod.thirdpartyclient",
            "com.crunchyroll.crunchyroid", "com.wbd.stream", "com.hbo.hbonow", "com.apple.atve.androidtv.appletv",
            "ca.bell.fiberemote.crave", "com.crave.android",
        )

        fun fromJson(raw: String): Settings {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return Settings()
            val d = Settings()
            val fl = o.optJSONArray("flagged")
            return Settings(
                workoutRatio = o.optDouble("workoutRatio", d.workoutRatio),
                focusRatio = o.optDouble("focusRatio", d.focusRatio),
                freeRatio = o.optDouble("freeRatio", d.freeRatio),
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
                halfRate = o.optJSONArray("halfRate")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
                sites = o.optJSONArray("sites")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: d.sites,
                nums = o.optJSONObject("nums")?.let { n -> n.keys().asSequence().associateWith { n.optInt(it) } } ?: emptyMap(),
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
        key == "freeRatio" -> st.copy(freeRatio = v.toDouble())
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
        key.startsWith("num:") -> st.withNum(key.removePrefix("num:"), v.toInt())
        key.startsWith("half:") -> st.copy(halfRate = st.halfRate + key.removePrefix("half:"))
        key.startsWith("unsite:") -> st.copy(sites = st.sites - key.removePrefix("unsite:"))
        else -> st
    }

    /** Minutes of the day covered by a bedtime window. */
    private fun bedMinutes(start: Int, end: Int): Set<Int> {
        val tmp = Settings(bedStart = start, bedEnd = end)
        return (0 until 1440).filter { tmp.inBedtime(it) }.toSet()
    }

    data class Result(val applied: Int, val delayed: Int)

    fun propose(s: Store, new: Settings, labelFor: (String) -> String = { it.removePrefix("site:") }): Result = synchronized(s.lock) {
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
        handle("freeRatio", old.freeRatio.toString(), new.freeRatio.toString(),
            new.freeRatio < old.freeRatio, "Free lock-in ratio → ${fmtRatio(new.freeRatio)}:1")
        for (r in NumRules.all) {
            val o = old.n(r.key)
            val v = new.n(r.key)
            handle("num:${r.key}", o.toString(), v.toString(), if (r.looserIfHigher) v > o else v < o, "${r.label} → ${r.fmt(v)}")
        }
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

        // Making an app full price is instant. Making an already-blocked app cheaper waits.
        val fullAgain = old.halfRate - new.halfRate
        if (fullAgain.isNotEmpty()) {
            st = st.copy(halfRate = st.halfRate - fullAgain)
            fullAgain.forEach { pkg -> pend.removeAll { it.key == "half:$pkg" } }
            applied += fullAgain.size
        }
        for (pkg in new.halfRate - old.halfRate) {
            val wasFullPrice = pkg in old.flagged ||
                (pkg.startsWith("site:") && pkg.removePrefix("site:") in old.sites)
            handle("half:$pkg", "0", "1", wasFullPrice, "${labelFor(pkg)} costs ${new.n("engagingCostPct")}%")
        }

        // Sites: same idea as apps.
        val newSites = new.sites - old.sites
        if (newSites.isNotEmpty()) {
            st = st.copy(sites = st.sites + newSites)
            newSites.forEach { d -> pend.removeAll { it.key == "unsite:$d" } }
            applied += newSites.size
        }
        for (d in old.sites - new.sites) {
            handle("unsite:$d", "0", "1", true, "Unblock $d")
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

/** A whole-number rule. [looserIfHigher] decides which direction has to wait 24 hours. */
data class NumRule(
    val key: String, val label: String, val def: Int, val min: Int, val max: Int,
    val looserIfHigher: Boolean, val unit: String,
) {
    fun fmt(v: Int) = when {
        unit == "steps" -> "%,d steps".format(v)
        unit.isEmpty() -> v.toString()
        unit == "%" -> "$v%"
        else -> "$v $unit"
    }
}

object NumRules {
    val all = listOf(
        NumRule("dailyCapMin", "Most doomscrolling per day (phone apps + social sites)", 120, 15, 1440, true, "min"),
        NumRule("streakTargetMin", "Streak: stay under this much doomscrolling a day", 60, 5, 600, true, "min"),
        NumRule("streak3Min", "Streak bonus at 3 days", 10, 0, 240, true, "min"),
        NumRule("streak7Min", "Streak bonus at 7 days", 30, 0, 240, true, "min"),
        NumRule("streak30Min", "Streak bonus at 30 days (and every 30 after)", 60, 0, 600, true, "min"),
        NumRule("phoneDownPct", "Phone-down bonus (screen off all lock-in)", 20, 0, 100, true, "%"),
        NumRule("engagingCostPct", "Games & movies cost (% of normal)", 50, 10, 100, false, "%"),
        NumRule("earlyEndPct", "Pay for ending a lock-in early (% of normal)", 50, 0, 100, true, "%"),
        NumRule("introWaitSec", "Wait before \"Continue\" on the opening card", 15, 0, 300, false, "s"),
        NumRule("callRewardMin", "Minutes earned per family call", 10, 1, 60, true, "min"),
        NumRule("callMinMin", "Shortest call that counts", 5, 1, 60, false, "min"),
        NumRule("callCooldownH", "Hours before the same person pays again", 3, 0, 48, false, "h"),
        NumRule("workoutEffortPct", "Workouts: effort that counts (% of the way from resting to max heart rate)", 30, 5, 80, false, "%"),
        NumRule("maxHr", "Your max heart rate", 195, 140, 220, false, "bpm"),
        NumRule("workoutRestMin", "Workouts: rest between sets that still counts", 3, 0, 15, true, "min"),
        NumRule("stepsUnit", "Steps per step reward", 1000, 100, 20000, false, "steps"),
        NumRule("stepsRewardMin", "Minutes per step reward", 2, 1, 60, true, "min"),
        NumRule("stepsCapMin", "Most minutes from steps per day", 30, 0, 600, true, "min"),
        NumRule("sleepBonusMin", "Bonus for being asleep by bedtime", 15, 0, 120, true, "min"),
        NumRule("runPrBonusMin", "Bonus for beating your best run pace", 15, 0, 120, true, "min"),
        NumRule("liftPrBonusMin", "Bonus per gym PR (FitNotes)", 15, 0, 120, true, "min"),
        NumRule("liftPrCapMin", "Most gym-PR minutes per day", 45, 0, 600, true, "min"),
    )
    private val map = all.associateBy { it.key }
    fun byKey(k: String): NumRule = map.getValue(k)
}
