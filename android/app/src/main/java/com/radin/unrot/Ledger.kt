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
        val focusId: Long = 0, val focusStart: Long = 0, val focusPlanned: Long = 0, val focusPaused: Long = 0,
        val focusRatio: Double = 0.0, val earlyPct: Int = 50,
        /** That device's last 7 days: {"gains":{cat:sec}, "focus":{name:sec}, "locked":sec}. */
        val week: JSONObject? = null,
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
                    d.optLong("focusId"), d.optLong("focusStart"), d.optLong("focusPlanned"), d.optLong("focusPaused"),
                    d.optDouble("focusRatio", 0.0), d.optInt("earlyPct", 50),
                    d.optJSONObject("week"),
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
    fun credit(s: Store, st: Settings, sec: Long, reason: String, cat: String = guessCat(reason)): Long = synchronized(s.lock) {
        if (sec <= 0) return 0
        val cap = st.maxBankMin * 60L
        val add = min(sec, max(0L, cap - balanceSec(s)))
        if (add > 0) {
            s.earnedSec = s.earnedSec + add
            stat("e", add)
            mapStat("gains", cat, add)
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
        mapStat("apps", key, sec)
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

    /** Where earned time came from, for "biggest time gainers". */
    val GAIN_LABELS = linkedMapOf(
        "lockin" to "Lock-ins", "workout" to "Workouts", "steps" to "Steps", "sleep" to "Sleep on time",
        "calls" to "Family calls", "prs" to "PRs", "phonedown" to "Phone-down bonus", "streak" to "Streak bonuses",
        "daily" to "Free daily minutes", "other" to "Other",
    )

    fun guessCat(reason: String): String {
        val r = reason.lowercase()
        return when {
            "phone-down" in r -> "phonedown"
            "lock-in" in r || "locked in" in r -> "lockin"
            "streak" in r -> "streak"
            "free daily" in r -> "daily"
            "call with" in r -> "calls"
            r.startsWith("pr:") || "best run pace" in r -> "prs"
            "steps" in r -> "steps"
            "asleep" in r -> "sleep"
            else -> "other"
        }
    }

    private val pending = HashMap<String, Long>()
    /** Per-day breakdowns: "apps" (time wasters), "gains" (time gainers), "focus" (what you were locked in on). */
    private val pendingMaps = HashMap<String, HashMap<String, Long>>()

    private fun stat(k: String, v: Long) = synchronized(pending) { pending[k] = (pending[k] ?: 0L) + v }
    private fun mapStat(map: String, k: String, v: Long) = synchronized(pending) {
        val m = pendingMaps.getOrPut(map) { HashMap() }
        m[k] = (m[k] ?: 0L) + v
    }
    fun countStat(k: String, v: Long = 1) = stat(k, v)
    fun addAppTime(key: String, sec: Long) = mapStat("apps", key, sec)
    /** One more second of a lock-in spent on [key] (an app, "site:host", or a laptop program). */
    fun addFocusTime(key: String, sec: Long) = mapStat("focus", key, sec)

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
        val (p, maps) = synchronized(pending) {
            val p = HashMap(pending); val m = pendingMaps.mapValues { HashMap(it.value) }
            pending.clear(); pendingMaps.clear()
            p to m
        }
        if (p.isEmpty() && maps.isEmpty()) return
        p["l"]?.let { s.lifeLockSec = s.lifeLockSec + it }
        p["c"]?.let { s.lifeCalls = s.lifeCalls + it }
        val all = s.obj("stats")
        val day = today()
        val d = all.optJSONObject(day) ?: JSONObject()
        p.forEach { (k, v) -> d.put(k, d.optLong(k) + v) }
        for ((name, m) in maps) {
            val o = d.optJSONObject(name) ?: JSONObject()
            m.forEach { (k, v) -> o.put(k, o.optLong(k) + v) }
            d.put(name, o)
        }
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
    //
    // A lock-in belongs to the device it was started on (that device pays it out when it finishes). The other
    // device can pause, resume or finish it: pause/resume are sent as a command; finishing pays the device you
    // finish it on straight away and tells the owner to stop without paying.

    /** A running lock-in, here or on another device, with everything needed to show and finish it. */
    data class Session(
        /** null = this phone. */
        val deviceId: String?,
        val deviceName: String,
        val id: Long,
        val free: Boolean,
        val start: Long,
        val until: Long,
        val plannedSec: Long,
        val pausedAt: Long,
        val ratio: Double,
        val earlyPct: Int,
    ) {
        val local get() = deviceId == null
        val paused get() = pausedAt > 0
        private fun clock() = if (pausedAt > 0) pausedAt else System.currentTimeMillis()
        fun remainingSec(): Long = max(0L, (until - clock()) / 1000)
        fun elapsedSec(): Long =
            if (free) max(0L, (min(clock(), until) - start) / 1000) else max(0L, plannedSec - remainingSec())
        /** What finishing right now pays (free: everything so far; timed: the early-end rate). */
        fun payNow(keepSec: Long = Long.MAX_VALUE): Long {
            val done = keepSec.coerceIn(0L, elapsedSec())
            return if (free) (done / ratio).toLong() else (done / ratio * earlyPct / 100.0).toLong()
        }
        val fullPay get() = (plannedSec / ratio).toLong()
    }

    private fun localSession(s: Store): Session? {
        if (!localFocusActive(s)) return null
        val st = Rules.current(s)
        return Session(
            null, "phone", s.focusId, s.focusFree, s.focusStart, s.focusUntil, s.focusPlannedSec, s.focusPausedAt,
            if (s.focusFree) st.freeRatio else st.focusRatio, st.n("earlyEndPct"),
        )
    }

    private fun ended(s: Store) = s.endedRemote.split(',').filter { it.isNotBlank() }.toSet()

    private fun remoteSessions(s: Store): List<Session> {
        val now = System.currentTimeMillis()
        val done = ended(s)
        return remotes(s).filter {
            it.focusUntil > 0 && (it.focusPaused > 0 || it.focusUntil > now) && "${it.id}:${it.focusId}" !in done
        }.map {
            Session(
                it.id, it.name, it.focusId, it.focusFree, it.focusStart, it.focusUntil, it.focusPlanned, it.focusPaused,
                if (it.focusRatio > 0) it.focusRatio else if (it.focusFree) 5.0 else 3.0, it.earlyPct,
            )
        }
    }

    /** The lock-in that's running right now, on this phone first, else on another device. */
    fun session(s: Store): Session? = localSession(s) ?: remoteSessions(s).firstOrNull()

    fun localFocusActive(s: Store) =
        s.focusUntil != 0L && (s.focusPausedAt > 0 || s.focusUntil > System.currentTimeMillis())

    fun remoteFocusFree(s: Store): Boolean = remoteSessions(s).any { it.free }

    fun focusActive(s: Store) = session(s) != null

    fun focusRemainingSec(s: Store): Long = session(s)?.remainingSec() ?: 0L

    private fun newSession(s: Store) {
        val now = System.currentTimeMillis()
        s.focusId = now
        s.focusStart = now
        s.focusPausedAt = 0
    }

    fun startFocus(s: Store, minutes: Int) {
        newSession(s)
        s.focusFree = false
        s.focusPlannedSec = minutes * 60L
        s.focusUntil = s.focusStart + minutes * 60_000L
        s.log("Locked in for $minutes min")
    }

    /** Free-form lock-in: no set length. Ends itself after 4 hours (of unpaused time) if you forget. */
    const val FREE_MAX_MS = 4L * 3600 * 1000

    fun startFree(s: Store) {
        newSession(s)
        s.focusFree = true
        s.focusPlannedSec = FREE_MAX_MS / 1000
        s.focusUntil = s.focusStart + FREE_MAX_MS
        s.log("Started a free lock-in")
    }

    /** Freezes the timer. Everything stays blocked; the lock-in just doesn't count down (or up) until you resume. */
    fun pause(s: Store, from: String? = null) = synchronized(s.lock) {
        if (!localFocusActive(s) || s.focusPausedAt > 0) return
        s.focusPausedAt = System.currentTimeMillis()
        s.log("Lock-in paused" + (from?.let { " from your $it" } ?: "") + " (everything stays blocked)")
    }

    fun resume(s: Store, from: String? = null) = synchronized(s.lock) {
        val at = s.focusPausedAt
        if (at <= 0 || s.focusUntil == 0L) return
        val gap = max(0L, System.currentTimeMillis() - at)
        s.focusUntil = s.focusUntil + gap
        s.focusStart = s.focusStart + gap
        s.focusPausedAt = 0
        s.log("Lock-in resumed after ${gap / 60_000} min" + (from?.let { " from your $it" } ?: ""))
    }

    fun freeElapsedSec(s: Store): Long = localSession(s)?.takeIf { it.free }?.elapsedSec() ?: 0L

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
        endSession(s, pay)
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
        endSession(s, -1)
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

    /** Clears the running lock-in, remembering what it paid ([paid] = -1: parked for review). */
    private fun endSession(s: Store, paid: Long) {
        s.lastEndedId = s.focusId
        s.lastEndedPaid = paid
        s.focusUntil = 0
        s.focusPlannedSec = 0
        s.focusFree = false
        s.focusPausedAt = 0
    }

    /** Seconds you'd get for ending right now: the time done, at the early-end rate. */
    fun earlyEndPayout(s: Store, st: Settings): Long = localSession(s)?.payNow() ?: 0L

    /** Ends a lock-in early: you're paid for the time you did, at a reduced rate. */
    fun cancelFocus(s: Store, st: Settings): Long = synchronized(s.lock) {
        val sess = localSession(s) ?: return 0
        if (s.focusFree) return stopFree(s, st)
        val pay = sess.payNow()
        val doneSec = sess.elapsedSec()
        endSession(s, pay)
        stat("l", doneSec)
        s.lastFocusPaid = pay
        if (pay > 0) credit(s, st, pay, "ended a lock-in early after ${doneSec / 60} min (${st.n("earlyEndPct")}% rate)")
        else s.log("Ended a lock-in early")
        pay
    }

    /** Pays out a finished lock-in. Returns seconds earned, or -1 if nothing finished. */
    fun settleFocus(s: Store, st: Settings): Long = synchronized(s.lock) {
        val until = s.focusUntil
        if (until == 0L || s.focusPausedAt > 0 || System.currentTimeMillis() < until) return -1
        if (s.focusFree) {
            parkFree(s)
            return NEEDS_REVIEW
        }
        val planned = s.focusPlannedSec
        endSession(s, 0)
        stat("l", planned)
        val pay = credit(s, st, (planned / st.focusRatio).toLong(), "locked in ${planned / 60} min")
        s.lastFocusPaid = pay
        s.lastEndedPaid = pay
        pay
    }

    // ---- Controlling a lock-in that's running on another device ----

    fun command(s: Store, sess: Session, action: String, paid: Long = 0): JSONObject = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("action", action)
        .put("session", sess.id)
        .put("paid", paid)
        .put("from", "phone")

    /**
     * Finishes another device's lock-in here: this phone gets paid, and that device is told (already, by the
     * caller) to stop without paying. Free lock-ins pay only [keepSec] if given.
     */
    fun finishRemote(s: Store, st: Settings, sess: Session, keepSec: Long = Long.MAX_VALUE): Long = synchronized(s.lock) {
        val done = if (sess.free) keepSec.coerceIn(0L, sess.elapsedSec()) else sess.elapsedSec()
        val pay = sess.payNow(keepSec)
        s.endedRemote = (ended(s).toList().takeLast(4) + "${sess.deviceId}:${sess.id}").joinToString(",")
        stat("l", done)
        val what = if (sess.free) "free lock-in" else "lock-in, ended early (${sess.earlyPct}% rate)"
        if (pay > 0) credit(s, st, pay, "finished your ${sess.deviceName}'s $what after ${done / 60} min")
        else s.log("Finished your ${sess.deviceName}'s lock-in")
        pay
    }

    /** Applies a pause/resume/finish sent from another device. Returns a line worth showing, if any. */
    fun applyCommand(s: Store, cmd: JSONObject): String? = synchronized(s.lock) {
        val id = cmd.optLong("id")
        if (id == 0L || id == s.lastCmdId) return null
        s.lastCmdId = id
        val sid = cmd.optLong("session")
        val from = cmd.optString("from", "laptop")
        val paid = cmd.optLong("paid")
        val mine = localFocusActive(s) && sid == s.focusId
        when (cmd.optString("action")) {
            "pause" -> if (mine) { pause(s, from); return "Lock-in paused from your $from" }
            "resume" -> if (mine) { resume(s, from); return "Lock-in resumed from your $from" }
            "end" -> {
                if (mine) {
                    endSession(s, paid)
                    s.lastFocusPaid = paid
                    s.log("Lock-in finished on your $from (it got the +${fmtShort(paid)})")
                    return "Lock-in finished on your $from"
                }
                if (sid != 0L && sid == s.lastEndedId) {
                    // It finished here at the same moment: don't pay it twice.
                    if (s.lastEndedPaid < 0) {
                        s.freeReviewSec = 0
                        s.freeReviewBonus = false
                    } else {
                        val undo = min(paid, s.lastEndedPaid)
                        if (undo > 0) {
                            s.earnedSec = s.earnedSec - undo
                            stat("e", -undo)
                            s.log("−${fmtShort(undo)} · that lock-in was also paid on your $from")
                        }
                    }
                    s.lastEndedId = 0
                }
            }
        }
        null
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
