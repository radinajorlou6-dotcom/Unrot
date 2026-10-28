package com.radin.unrot

import android.content.Context
import java.time.LocalDate

/** Your last 7 days, from the stats Unrot keeps (phone, plus doomscrolling from a synced laptop). */
object Recap {
    data class Week(
        val earnedSec: Long, val doomSec: Long, val laptopDoomSec: Long, val lockedSec: Long, val calls: Long,
        val top: List<Pair<String, Long>>, val bestLockDay: Pair<String, Long>?, val streak: Int,
        val daysUnderTarget: Int, val days: Int,
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
        return Week(earned, doom, laptop, locked, calls, top, best, s.streak, under, days)
    }

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
