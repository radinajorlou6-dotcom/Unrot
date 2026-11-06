package com.radin.unrot

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max

/** Your last 7 days, from the stats Unrot keeps (phone, plus doomscrolling from a synced laptop). */
object Recap {
    data class Week(
        val earnedSec: Long, val doomSec: Long, val laptopDoomSec: Long, val lockedSec: Long, val calls: Long,
        val top: List<Pair<String, Long>>, val bestLockDay: Pair<String, Long>?, val streak: Int,
        val daysUnderTarget: Int, val days: Int,
        /** Where your time came from, biggest first (phone + laptop). */
        val gains: List<Pair<String, Long>> = emptyList(),
        /** What you were locked in on, biggest first (phone + laptop), and the lock-in time nothing explains. */
        val focus: List<Pair<String, Long>> = emptyList(),
        val undescribedSec: Long = 0,
        val allLockedSec: Long = 0,
    ) {
        val headline: String
            get() = "Locked in ${hm(lockedSec)}, doomscrolled ${hm(doomSec + laptopDoomSec)}, $calls family call" +
                (if (calls == 1L) "" else "s") + ". Tap to see your week."
    }

    fun hm(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun label(ctx: Context, key: String): String = if (key.startsWith("site:")) key.removePrefix("site:") else
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(key, 0)).toString()
        }.getOrDefault(key)

    fun week(ctx: Context, s: Store): Week {
        Ledger.flushStats(s)
        val all = s.obj("stats")
        val today = LocalDate.now()
        val st = Rules.current(s)
        var earned = 0L; var doom = 0L; var laptop = 0L; var locked = 0L; var calls = 0L
        var under = 0; var days = 0
        val apps = HashMap<String, Long>()
        var best: Pair<String, Long>? = null
        for (i in 0 until 7) {
            val day = today.minusDays(i.toLong())
            val d = all.optJSONObject(day.toString()) ?: continue
            days++
            earned += d.optLong("e"); calls += d.optLong("c")
            val dd = d.optLong("d"); doom += dd
            val dl = if (i == 0) Ledger.remotes(s).sumOf { it.doomOn(day.toString()) } else d.optLong("dl")
            laptop += dl
            if (dd + dl <= st.n("streakTargetMin") * 60L) under++
            val l = d.optLong("l"); locked += l
            if (l > 0 && (best == null || l > best.second)) best = day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() } to l
            d.optJSONObject("apps")?.let { a -> a.keys().forEach { k -> apps[k] = (apps[k] ?: 0L) + a.optLong(k) } }
        }
        val top = apps.entries.sortedByDescending { it.value }.take(3).map { label(ctx, it.key) to it.value }

        // Gainers and lock-in activity, this phone plus whatever the laptop reports.
        val mine = totals(s)
        val gains = HashMap<String, Long>()
        val focus = HashMap<String, Long>()
        var allLocked = 0L
        fun merge(o: JSONObject, labelOf: (String) -> String) {
            o.optJSONObject("gains")?.let { g -> g.keys().forEach { k -> gains[k] = (gains[k] ?: 0L) + g.optLong(k) } }
            o.optJSONObject("focus")?.let { f ->
                f.keys().forEach { k -> val n = labelOf(k); focus[n] = (focus[n] ?: 0L) + f.optLong(k) }
            }
            allLocked += o.optLong("locked")
        }
        merge(mine) { it }
        Ledger.remotes(s).forEach { r -> r.week?.let { merge(it) { k -> k } } }
        val described = focus.values.sum()
        return Week(
            earned, doom, laptop, locked, calls, top, best, s.streak, under, days,
            gains = gains.entries.filter { it.value > 0 }.sortedByDescending { it.value }
                .map { (Ledger.GAIN_LABELS[it.key] ?: it.key) to it.value },
            focus = focus.entries.filter { it.value >= 60 }.sortedByDescending { it.value }.map { it.key to it.value },
            undescribedSec = max(0L, allLocked - described),
            allLockedSec = allLocked,
        )
    }

    /** This phone's last 7 days (gains, lock-in activity, lock-in total), shared with the laptop through sync. */
    fun totals(s: Store): JSONObject {
        val all = s.obj("stats")
        val today = LocalDate.now()
        val gains = JSONObject()
        val focus = JSONObject()
        var locked = 0L
        for (i in 0 until 7) {
            val d = all.optJSONObject(today.minusDays(i.toLong()).toString()) ?: continue
            locked += d.optLong("l")
            d.optJSONObject("gains")?.let { g -> g.keys().forEach { k -> gains.put(k, gains.optLong(k) + g.optLong(k)) } }
            d.optJSONObject("focus")?.let { f -> f.keys().forEach { k -> focus.put(k, focus.optLong(k) + f.optLong(k)) } }
        }
        return JSONObject().put("gains", gains).put("focus", focus).put("locked", locked)
    }

    data class Day(val date: LocalDate, val doomSec: Long, val lockedSec: Long)

    /** The last 7 days, oldest first (doomscrolling includes a synced laptop). */
    fun series(s: Store): List<Day> {
        Ledger.flushStats(s)
        val all = s.obj("stats")
        val today = LocalDate.now()
        return (6 downTo 0).map { i ->
            val day = today.minusDays(i.toLong())
            val d = all.optJSONObject(day.toString())
            val dl = if (i == 0) Ledger.remotes(s).sumOf { it.doomOn(day.toString()) } else d?.optLong("dl") ?: 0L
            Day(day, (d?.optLong("d") ?: 0L) + dl, d?.optLong("l") ?: 0L)
        }
    }

    /** Was there any data for this day (so an empty day isn't shown as a "good" day)? */
    fun hasDay(s: Store, day: LocalDate): Boolean = s.obj("stats").has(day.toString())

    fun shareText(ctx: Context, s: Store): String {
        val w = week(ctx, s)
        val sb = StringBuilder("My week with Unrot 📊\n")
        sb.append("🔒 Locked in: ${hm(w.lockedSec)}\n")
        sb.append("📱 Doomscrolling: ${hm(w.doomSec + w.laptopDoomSec)}\n")
        sb.append("📞 Family calls: ${w.calls}\n")
        sb.append("🔥 Streak: ${w.streak} day${if (w.streak == 1) "" else "s"}\n")
        if (w.top.isNotEmpty()) sb.append("Top time sinks: ${w.top.joinToString(", ") { "${it.first} ${hm(it.second)}" }}\n")
        return sb.toString()
    }
}
