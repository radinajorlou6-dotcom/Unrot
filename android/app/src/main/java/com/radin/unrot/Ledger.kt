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
    )

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
                    d.optLong("focusUntil"), d.optLong("updated"),
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
        if (add > 0) s.earnedSec = s.earnedSec + add
        val note = if (add < sec) " (bank full at ${st.maxBankMin} min)" else ""
        s.log("+${fmtShort(add)} · $reason$note")
        add
    }

    fun spend(s: Store, sec: Long) = synchronized(s.lock) {
        s.spentSec = s.spentSec + sec
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

    fun focusActive(s: Store) = localFocusActive(s) || remoteFocusUntil(s) > System.currentTimeMillis()

    fun focusRemainingSec(s: Store): Long {
        val until = max(s.focusUntil, remoteFocusUntil(s))
        return max(0L, (until - System.currentTimeMillis()) / 1000)
    }

    fun startFocus(s: Store, minutes: Int) {
        s.focusPlannedSec = minutes * 60L
        s.focusUntil = System.currentTimeMillis() + minutes * 60_000L
        s.log("Locked in for $minutes min")
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
        val pay = earlyEndPayout(s, st)
        val doneMin = max(0L, s.focusPlannedSec - max(0L, (s.focusUntil - System.currentTimeMillis()) / 1000)) / 60
        s.focusUntil = 0
        s.focusPlannedSec = 0
        if (pay > 0) credit(s, st, pay, "ended a lock-in early after $doneMin min (${st.n("earlyEndPct")}% rate)")
        else s.log("Ended a lock-in early")
        pay
    }

    /** Pays out a finished lock-in. Returns seconds earned, or -1 if nothing finished. */
    fun settleFocus(s: Store, st: Settings): Long = synchronized(s.lock) {
        val until = s.focusUntil
        if (until == 0L || System.currentTimeMillis() < until) return -1
        val planned = s.focusPlannedSec
        s.focusUntil = 0
        s.focusPlannedSec = 0
        credit(s, st, (planned / st.focusRatio).toLong(), "locked in ${planned / 60} min")
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
