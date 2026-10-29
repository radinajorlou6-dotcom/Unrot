package com.radin.unrot

import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * The time balance. Each device keeps running totals of what it earned and spent;
 * the balance is the sum over this phone and every synced device (your laptop).
 */
object Ledger {
    data class Remote(
        val id: String, val name: String, val earned: Long, val spent: Long,
        val focusUntil: Long, val updated: Long,
        val focusFree: Boolean = false,
        val doomDay: String = "", val doomSec: Long = 0, val doomPrevDay: String = "", val doomPrevSec: Long = 0,
    ) {
        /** This device's doomscrolling on [day], if it knows. */
        fun doomOn(day: String): Long = when (day) {
            doomDay -> doomSec
            doomPrevDay -> doomPrevSec
            else -> 0
        }
    }

    private var cacheRaw: String? = null
    private var cache: List<Remote> = emptyList()

    fun remotes(s: Store): List<Remote> {
        val raw = s.remoteJson
        if (raw == cacheRaw) return cache
        val o = runCatching { JSONObject(raw) }.getOrNull()
        val list = mutableListOf<Remote>()
        if (o != null) {
            for (k in o.keys()) {
                if (k == s.deviceId) continue
                val d = o.optJSONObject(k) ?: continue
                list += Remote(
                    k, d.optString("name", "device"), d.optLong("earned"), d.optLong("spent"),
                    d.optLong("focusUntil"), d.optLong("updated"), d.optBoolean("focusFree"),
                    d.optString("doomDay"), d.optLong("doomSec"), d.optString("doomPrevDay"), d.optLong("doomPrevSec"),
                )
            }
        }
        cacheRaw = raw
        cache = list
        return list
    }

    fun balanceSec(s: Store): Long =
        s.earnedSec - s.spentSec + remotes(s).sumOf { it.earned - it.spent }

    /** Adds time, respecting the max-bank cap. Returns seconds actually added. */
    fun credit(s: Store, st: Settings, sec: Long, reason: String): Long = synchronized(s.lock) {
        if (sec <= 0) return 0
        val cap = st.maxBankMin * 60L
        val add = min(sec, max(0L, cap - balanceSec(s)))
        if (add > 0) {
            s.earnedSec = s.earnedSec + add
            stat("e", add)
        }
        val note = if (add < sec) " (bank full at ${st.maxBankMin} min)" else ""
        s.log("+${fmtShort(add)} · $reason$note")
        add
    }

    fun spend(s: Store, sec: Long) = synchronized(s.lock) {
        s.spentSec = s.spentSec + sec
        stat("s", sec)
    }

    // ---------------------------------------------------------------- daily doomscroll cap + streaks

    fun today(): String = LocalDate.now().toString()

    /** One second of full-price (doomscroll) time in [key]. */
    fun addDoom(s: Store, sec: Long, key: String) {
        rollover(s, Rules.current(s))
        s.doomSec = s.doomSec + sec
        stat("d", sec)
        appStat(key, sec)
    }

    /** Doomscrolling today across this phone and synced devices. */
    fun doomToday(s: Store): Long {
        val t = today()
        return (if (s.doomDay == t) s.doomSec else 0L) + remotes(s).sumOf { it.doomOn(t) }
    }

    /** What you can actually use in [key] right now: balance, and for full-price stuff the daily cap too. */
    fun availableSec(s: Store, st: Settings, key: String): Long {
        val bal = balanceSec(s)
        if (key in st.halfRate) return bal
        return min(bal, st.n("dailyCapMin") * 60L - doomToday(s))
    }

    /**
     * New day: judge yesterday for the streak (under the target = +1 day, with bonuses at 3, 7, 30 and
     * every 30 after; over = back to 0), then start counting today.
     */
    fun rollover(s: Store, st: Settings) = synchronized(s.lock) {
        storeRef = s
        val t = today()
        if (s.doomDay == t) return
        val prev = s.doomDay
        if (prev.isNotEmpty()) {
            val laptop = remotes(s).sumOf { it.doomOn(prev) }
            val total = s.doomSec + laptop
            dayStat(prev, "dl", laptop)
            val target = st.n("streakTargetMin") * 60L
            if (total <= target) {
                val n = s.streak + 1
                s.streak = n
                if (n > s.bestStreak) s.bestStreak = n
                val bonus = when {
                    n == 3 -> st.n("streak3Min")
                    n == 7 -> st.n("streak7Min")
                    n >= 30 && n % 30 == 0 -> st.n("streak30Min")
                    else -> 0
                }
                s.log("🔥 $n-day streak (${total / 60} min of doomscrolling yesterday)")
                if (bonus > 0) credit(s, st, bonus * 60L, "$n-day streak bonus")
            } else {
                if (s.streak > 0) s.log("Streak ended at ${s.streak} days (${total / 60} min of doomscrolling yesterday)")
                s.streak = 0
            }
        }
        s.doomPrevDay = prev
        s.doomPrevSec = s.doomSec
        s.doomDay = t
        s.doomSec = 0
    }

    // ---------------------------------------------------------------- stats for the weekly recap

    private val pending = HashMap<String, Long>()
    private val pendingApps = HashMap<String, Long>()

    private fun stat(k: String, v: Long) = synchronized(pending) { pending[k] = (pending[k] ?: 0L) + v }
    private fun appStat(k: String, v: Long) = synchronized(pending) { pendingApps[k] = (pendingApps[k] ?: 0L) + v }
    fun countStat(k: String, v: Long = 1) = stat(k, v)
    fun addAppTime(key: String, sec: Long) = appStat(key, sec)

    private var storeRef: Store? = null

    private fun dayStat(day: String, k: String, v: Long) {
        val s = storeRef ?: return
        val all = s.obj("stats")
        val d = all.optJSONObject(day) ?: JSONObject()
        d.put(k, v)
        all.put(day, d)
        s.putObj("stats", all)
    }

    /** Writes the in-memory counters into today's stats (called every ~30s). Keeps 21 days. */
    fun flushStats(s: Store) {
        storeRef = s
        val (p, a) = synchronized(pending) {
            val p = HashMap(pending); val a = HashMap(pendingApps)
            pending.clear(); pendingApps.clear()
            p to a
        }
        if (p.isEmpty() && a.isEmpty()) return
        p["l"]?.let { s.lifeLockSec = s.lifeLockSec + it }
        p["c"]?.let { s.lifeCalls = s.lifeCalls + it }
        val all = s.obj("stats")
        val day = today()
        val d = all.optJSONObject(day) ?: JSONObject()
        p.forEach { (k, v) -> d.put(k, d.optLong(k) + v) }
        val apps = d.optJSONObject("apps") ?: JSONObject()
        a.forEach { (k, v) -> apps.put(k, apps.optLong(k) + v) }
        d.put("apps", apps)
        all.put(day, d)
        val keep = all.keys().asSequence().toList().sorted().takeLast(21).toSet()
        all.keys().asSequence().toList().filter { it !in keep }.forEach { all.remove(it) }
        s.putObj("stats", all)
    }

    fun ensureDailyBase(s: Store, st: Settings) {
        val today = LocalDate.now().toString()
        if (s.lastBaseDay == today) return
        s.lastBaseDay = today
        if (st.dailyBaseMin > 0) credit(s, st, st.dailyBaseMin * 60L, "free daily minutes")
    }

    // ---- Lock-in (focus) sessions ----
    fun localFocusActive(s: Store) = s.focusUntil > System.currentTimeMillis()

    fun remoteFocusUntil(s: Store): Long = remotes(s).maxOfOrNull { it.focusUntil } ?: 0L

    fun remoteFocusFree(s: Store): Boolean {
        val now = System.currentTimeMillis()
        return remotes(s).any { it.focusUntil > now && it.focusFree }
    }

    fun focusActive(s: Store) = localFocusActive(s) || remoteFocusUntil(s) > System.currentTimeMillis()

    fun focusRemainingSec(s: Store): Long {
        val until = max(s.focusUntil, remoteFocusUntil(s))
        return max(0L, (until - System.currentTimeMillis()) / 1000)
    }

    fun startFocus(s: Store, minutes: Int) {
        s.focusFree = false
        s.focusStart = System.currentTimeMillis()
        s.focusPlannedSec = minutes * 60L
        s.focusUntil = System.currentTimeMillis() + minutes * 60_000L
        s.log("Locked in for $minutes min")
    }

    /** Free-form lock-in: no set length. Ends itself after 4 hours if you forget. */
    const val FREE_MAX_MS = 4L * 3600 * 1000

    fun startFree(s: Store) {
        val now = System.currentTimeMillis()
        s.focusFree = true
        s.focusStart = now
        s.focusPlannedSec = FREE_MAX_MS / 1000
        s.focusUntil = now + FREE_MAX_MS
        s.log("Started a free lock-in")
    }

    fun freeElapsedSec(s: Store): Long =
        if (!s.focusFree || s.focusUntil == 0L) 0 else max(0L, (min(System.currentTimeMillis(), s.focusUntil) - s.focusStart) / 1000)

    fun freePayout(s: Store, st: Settings): Long = (freeElapsedSec(s) / st.freeRatio).toLong()

    /**
     * Stops a free lock-in and pays for the time done, or only [keepSec] of it if you say part of it
     * wasn't real (you forgot to stop it).
     */
    fun stopFree(s: Store, st: Settings, keepSec: Long = Long.MAX_VALUE): Long = synchronized(s.lock) {
        if (s.focusUntil == 0L || !s.focusFree) return 0
        val ran = freeElapsedSec(s)
        val done = keepSec.coerceIn(0L, ran)
        val pay = (done / st.freeRatio).toLong()
        clearFocus(s)
        stat("l", done)
        s.lastFocusPaid = pay
        val trimmed = if (done < ran) " (trimmed from ${ran / 60} min)" else ""
        if (pay > 0) credit(s, st, pay, "free lock-in, ${done / 60} min$trimmed") else s.log("Ended a free lock-in$trimmed")
        pay
    }

    /** Result of [settleFocus] when a free lock-in hit the max: nothing paid until you review it. */
    const val NEEDS_REVIEW = -2L

    /** A free lock-in that ran all the way to the max was almost certainly forgotten: park it unpaid. */
    private fun parkFree(s: Store) {
        val ran = freeElapsedSec(s)
        clearFocus(s)
        s.lastFocusPaid = 0
        s.freeReviewBonus = false
        s.freeReviewSec = s.freeReviewSec + ran
        s.log("Free lock-in hit the ${FREE_MAX_MS / 3_600_000}-hour max. Unpaid until you say how long you really worked")
    }

    /** Pays a parked free lock-in for the [keepSec] you say were real. */
    fun resolveFreeReview(s: Store, st: Settings, keepSec: Long): Long = synchronized(s.lock) {
        val ran = s.freeReviewSec
        if (ran <= 0) return 0
        val kept = keepSec.coerceIn(0L, ran)
        val bonusOwed = s.freeReviewBonus
        s.freeReviewSec = 0
        s.freeReviewBonus = false
        stat("l", kept)
        val pay = (kept / st.freeRatio).toLong()
        if (pay <= 0) {
            s.log("Free lock-in that hit the max: you kept 0 min")
            return 0
        }
        var got = credit(s, st, pay, "free lock-in, ${kept / 60} min (you said, out of ${ran / 60})")
        val pct = st.n("phoneDownPct")
        if (bonusOwed && pct > 0) {
            got += credit(s, st, pay * pct / 100, "phone-down bonus (+$pct%, screen stayed off)")
            s.lifePhoneDown = s.lifePhoneDown + 1
        }
        got
    }

    /**
     * Takes time off your balance on purpose, to keep yourself honest (e.g. a lock-in you forgot to stop).
     * Never below zero, and it doesn't count as doomscrolling. Returns seconds removed.
     */
    fun giveBack(s: Store, sec: Long): Long = synchronized(s.lock) {
        val take = min(sec, max(0L, balanceSec(s)))
        if (take <= 0) return 0
        s.earnedSec = s.earnedSec - take
        stat("e", -take)
        s.log("−${fmtShort(take)} · gave time back (keeping it honest)")
        take
    }

    private fun clearFocus(s: Store) {
        s.focusUntil = 0
        s.focusPlannedSec = 0
        s.focusFree = false
    }

    /** Seconds you'd get for ending right now: the time done, at the early-end rate. */
    fun earlyEndPayout(s: Store, st: Settings): Long {
        if (s.focusUntil == 0L) return 0
        val done = max(0L, s.focusPlannedSec - max(0L, (s.focusUntil - System.currentTimeMillis()) / 1000))
        return (done / st.focusRatio * st.n("earlyEndPct") / 100.0).toLong()
    }

    /** Ends a lock-in early: you're paid for the time you did, at a reduced rate. */
    fun cancelFocus(s: Store, st: Settings): Long = synchronized(s.lock) {
        if (s.focusUntil == 0L) return 0
        if (s.focusFree) return stopFree(s, st)
        val pay = earlyEndPayout(s, st)
        val doneSec = max(0L, s.focusPlannedSec - max(0L, (s.focusUntil - System.currentTimeMillis()) / 1000))
        val doneMin = doneSec / 60
        clearFocus(s)
        stat("l", doneSec)
        s.lastFocusPaid = pay
        if (pay > 0) credit(s, st, pay, "ended a lock-in early after $doneMin min (${st.n("earlyEndPct")}% rate)")
        else s.log("Ended a lock-in early")
        pay
    }

    /** Pays out a finished lock-in. Returns seconds earned, or -1 if nothing finished. */
    fun settleFocus(s: Store, st: Settings): Long = synchronized(s.lock) {
        val until = s.focusUntil
        if (until == 0L || System.currentTimeMillis() < until) return -1
        if (s.focusFree) {
            parkFree(s)
            return NEEDS_REVIEW
        }
        val planned = s.focusPlannedSec
        clearFocus(s)
        stat("l", planned)
        val pay = credit(s, st, (planned / st.focusRatio).toLong(), "locked in ${planned / 60} min")
        s.lastFocusPaid = pay
        pay
    }

    fun fmt(sec: Long): String {
        val neg = sec < 0
        val a = kotlin.math.abs(sec)
        val h = a / 3600
        val m = (a % 3600) / 60
        val ss = a % 60
        val body = if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
        return if (neg) "-$body" else body
    }

    fun fmtShort(sec: Long): String {
        val m = sec / 60
        val ss = sec % 60
        return when {
            m == 0L -> "${ss}s"
            ss == 0L -> "${m} min"
            else -> "${m} min ${ss}s"
        }
    }
}
