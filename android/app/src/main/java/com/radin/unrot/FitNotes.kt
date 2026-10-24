package com.radin.unrot

import android.content.Context
import android.net.Uri
import java.time.LocalDate
import kotlin.math.min

/**
 * Reads a FitNotes CSV export and finds new personal records.
 * A PR only pays if your watch also logged a workout that day, so typed-in fake sets don't count.
 */
object FitNotes {
    data class Pr(val day: LocalDate, val exercise: String, val desc: String)
    data class Result(val sets: Int, val prs: List<Pr>, val paid: List<Pr>, val unverified: List<Pr>, val earnedSec: Long, val error: String?)

    private data class SetRow(val day: LocalDate, val exercise: String, val weight: Double, val reps: Int)

    /** How many days back a PR can be and still pay (so you can export a day late). */
    private const val RECENT_DAYS = 3L

    fun import(ctx: Context, uri: Uri): Result {
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return Result(0, emptyList(), emptyList(), emptyList(), 0, "Couldn't open that file.")
        val rows = parse(text) ?: return Result(0, emptyList(), emptyList(), emptyList(), 0,
            "That doesn't look like a FitNotes export. In FitNotes, export your workouts as a CSV (spreadsheet) file and share that.")
        val prs = findPrs(rows)
        val s = Store.get(ctx)
        val st = Rules.current(s)
        val today = LocalDate.now()
        val recent = prs.filter { !it.day.isBefore(today.minusDays(RECENT_DAYS)) && !it.day.isAfter(today) }
        val done = s.creditedIds.toMutableSet()
        val perDay = s.obj("prMinutes")
        val paid = mutableListOf<Pr>()
        val unverified = mutableListOf<Pr>()
        var earned = 0L
        val verifiedDays = HashMap<LocalDate, Boolean>()
        for (pr in recent) {
            val key = "pr:${pr.day}:${pr.exercise.lowercase()}"
            if (key in done) continue
            val ok = verifiedDays.getOrPut(pr.day) { HealthSync.hadWorkoutOn(ctx, pr.day) }
            if (!ok) {
                unverified += pr
                continue
            }
            done += key
            val already = perDay.optInt(pr.day.toString(), 0)
            val bonus = min(st.n("liftPrBonusMin"), st.n("liftPrCapMin") - already)
            if (bonus <= 0) continue
            perDay.put(pr.day.toString(), already + bonus)
            earned += Ledger.credit(s, st, bonus * 60L, "PR: ${pr.exercise} ${pr.desc}")
            paid += pr
        }
        s.creditedIds = done
        s.putObj("prMinutes", perDay)
        return Result(rows.size, recent, paid, unverified, earned, null)
    }

    /**
     * Strength PRs use estimated 1-rep max (Epley); bodyweight exercises use reps.
     * The first time you ever log an exercise isn't a PR, it's the baseline.
     */
    private fun findPrs(rows: List<SetRow>): List<Pr> {
        val out = mutableListOf<Pr>()
        for ((exercise, sets) in rows.groupBy { it.exercise }) {
            val weighted = sets.any { it.weight > 0 }
            var best = -1.0
            for ((day, daySets) in sets.groupBy { it.day }.toSortedMap()) {
                val top = daySets.maxByOrNull { score(it, weighted) } ?: continue
                val v = score(top, weighted)
                if (v <= 0) continue
                if (best > 0 && v > best * 1.005) {
                    val desc = if (weighted) "${fmtW(top.weight)} × ${top.reps}" else "${top.reps} reps"
                    out += Pr(day, exercise, desc)
                }
                if (v > best) best = v
            }
        }
        return out.sortedBy { it.day }
    }

    private fun score(r: SetRow, weighted: Boolean): Double =
        if (weighted) (if (r.weight > 0 && r.reps > 0) r.weight * (1 + min(r.reps, 15) / 30.0) else 0.0)
        else r.reps.toDouble()

    private fun fmtW(w: Double) = if (w == w.toLong().toDouble()) w.toLong().toString() else "%.1f".format(w)

    /** Handles both "Weight" + "Weight Unit" and older "Weight (kgs)" style headers. */
    private fun parse(text: String): List<SetRow>? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null
        val header = splitCsv(lines[0].removePrefix("﻿")).map { it.trim().lowercase() }
        val iDate = header.indexOf("date")
        val iEx = header.indexOf("exercise")
        val iW = header.indexOfFirst { it.startsWith("weight") && !it.contains("unit") }
        val iReps = header.indexOf("reps")
        if (iDate < 0 || iEx < 0 || iReps < 0) return null
        val rows = mutableListOf<SetRow>()
        for (line in lines.drop(1)) {
            val f = splitCsv(line)
            val day = runCatching { LocalDate.parse(f.getOrNull(iDate)?.trim()?.take(10)) }.getOrNull() ?: continue
            val ex = f.getOrNull(iEx)?.trim().orEmpty()
            if (ex.isEmpty()) continue
            val w = if (iW >= 0) f.getOrNull(iW)?.trim()?.toDoubleOrNull() ?: 0.0 else 0.0
            val reps = f.getOrNull(iReps)?.trim()?.toDoubleOrNull()?.toInt() ?: 0
            rows += SetRow(day, ex, w, reps)
        }
        return rows.takeIf { it.isNotEmpty() }
    }

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
