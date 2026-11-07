package com.radin.unrot

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * Everything here is measured by your Galaxy Watch (Samsung Health → Health Connect),
 * so none of it relies on your word.
 */
object HealthSync {
    val READ_EXERCISE: String = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    val READ_STEPS: String = HealthPermission.getReadPermission(StepsRecord::class)
    val READ_SLEEP: String = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val READ_DISTANCE: String = HealthPermission.getReadPermission(DistanceRecord::class)
    val READ_HEART_RATE: String = HealthPermission.getReadPermission(HeartRateRecord::class)
    const val READ_BACKGROUND: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    val CORE = setOf(READ_EXERCISE, READ_STEPS, READ_SLEEP, READ_DISTANCE, READ_HEART_RATE)

    /** Without heart rate (watch off, or no permission), a workout counts for at most this long. */
    private const val NO_HR_CAP_SEC = 60 * 60L

    private val RUN_TYPES = setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    )
    private const val MIN_RUN_METERS = 2000.0

    fun available(ctx: Context): Boolean =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    private fun client(ctx: Context) = HealthConnectClient.getOrCreate(ctx)

    /** Permissions to ask for (background reading only if this phone supports it). */
    fun wantedPermissions(ctx: Context): Set<String> = runBlocking {
        val set = CORE.toMutableSet()
        val bg = runCatching {
            client(ctx).features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)
        if (bg) set += READ_BACKGROUND
        set
    }

    fun granted(ctx: Context): Set<String> {
        if (!available(ctx)) return emptySet()
        return runCatching { runBlocking { client(ctx).permissionController.getGrantedPermissions() } }
            .getOrDefault(emptySet())
    }

    fun hasAll(ctx: Context) = granted(ctx).containsAll(CORE)

    private fun sessions(ctx: Context, from: Instant, to: Instant): List<ExerciseSessionRecord> =
        runBlocking {
            client(ctx).readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            ).records
        }

    /**
     * Pulls everything new: workouts, steps, sleep, run pace. Blocking; run on a background thread.
     * Returns seconds credited, or -1 if Health Connect couldn't be read at all.
     */
    fun sync(ctx: Context): Long {
        if (!available(ctx)) return -1
        val s = Store.get(ctx)
        var total = 0L
        var ok = false
        runCatching { total += workouts(ctx, s); ok = true }
        runCatching { total += steps(ctx, s); ok = true }
        runCatching { total += sleep(ctx, s); ok = true }
        runCatching { sleepWindows(ctx, s) }
        return if (ok) total else -1
    }

    /** Workout minutes at your ratio, plus a bonus when a run beats your best pace. */
    private fun workouts(ctx: Context, s: Store): Long {
        val st = Rules.current(s)
        val now = Instant.now()
        val since = Instant.ofEpochMilli(max(s.installTs, now.toEpochMilli() - 7L * 24 * 3600 * 1000))
        val done = s.creditedIds.toMutableSet()
        var total = 0L
        for (r in sessions(ctx, since, now)) {
            val id = r.metadata.id
            if (id in done) continue
            done += id
            s.creditedIds = done
            val sec = Duration.between(r.startTime, r.endTime).seconds
            if (r.exerciseType in RUN_TYPES) total += runPace(ctx, s, st, r, sec)
            if (!st.countWalks && r.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WALKING) continue
            if (sec < 5 * 60) continue
            val name = r.title?.takeIf { it.isNotBlank() } ?: "workout"
            val active = activeSec(ctx, st, r)
            val (counted, how) = when {
                active != null -> active to "${active / 60} with your heart rate up"
                sec > NO_HR_CAP_SEC -> NO_HR_CAP_SEC to "no heart rate, so capped at ${NO_HR_CAP_SEC / 60}"
                else -> sec to "no heart rate"
            }
            if (counted < 60) {
                s.log("$name, ${sec / 60} min: heart rate never got up to ${workingHr(ctx, st)} bpm, so nothing earned")
                continue
            }
            total += Ledger.credit(s, st, (counted / st.workoutRatio).toLong(), "$name, ${sec / 60} min ($how)", "workout")
        }
        return total
    }

    /**
     * The part of a workout you were actually working: minutes where your average heart rate was at least
     * [workingHr], plus short dips between them (rest between sets, up to [workoutRestMin]). Long breaks
     * spent chatting don't count. Returns null if the watch recorded no heart rate for this workout.
     */
    private fun activeSec(ctx: Context, st: Settings, r: ExerciseSessionRecord): Long? {
        val samples = runCatching {
            runBlocking {
                val out = mutableListOf<HeartRateRecord.Sample>()
                var token: String? = null
                do {
                    val page = client(ctx).readRecords(
                        ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(r.startTime, r.endTime),
                            pageToken = token,
                        )
                    )
                    page.records.forEach { rec ->
                        rec.samples.filterTo(out) { !it.time.isBefore(r.startTime) && it.time.isBefore(r.endTime) }
                    }
                    token = page.pageToken
                } while (token != null)
                out
            }
        }.getOrNull() ?: return null
        if (samples.size < 10) return null

        val startMs = r.startTime.toEpochMilli()
        val minutes = ((r.endTime.toEpochMilli() - startMs) / 60_000L).toInt() + 1
        val sum = LongArray(minutes)
        val count = IntArray(minutes)
        for (smp in samples) {
            val i = ((smp.time.toEpochMilli() - startMs) / 60_000L).toInt().coerceIn(0, minutes - 1)
            sum[i] += smp.beatsPerMinute
            count[i]++
        }
        val threshold = workingHr(ctx, st)
        val maxGap = st.n("workoutRestMin")
        val working = BooleanArray(minutes) { count[it] > 0 && sum[it] / count[it] >= threshold }
        var active = 0
        var lastWorking = -1
        for (i in 0 until minutes) {
            if (!working[i]) continue
            val gap = if (lastWorking < 0) 0 else i - lastWorking - 1
            active += 1 + (if (gap in 1..maxGap) gap else 0)
            lastWorking = i
        }
        return active * 60L
    }

    /**
     * The heart rate that counts as working: [workoutEffortPct] of the way from your resting rate to your max
     * (e.g. resting 60, max 195, 30% → 101 bpm). Fits you, and keeps fitting as you get fitter.
     */
    fun workingHr(ctx: Context, st: Settings): Int {
        val rest = restingHr(ctx)
        val max = st.n("maxHr").coerceAtLeast(rest + 20)
        return (rest + (max - rest) * st.n("workoutEffortPct") / 100.0).toInt()
    }

    /**
     * Your resting heart rate, worked out once a day from the last week of watch readings
     * (the low end of them, which is you sitting still or asleep). 65 until there's enough data.
     */
    fun restingHr(ctx: Context): Int {
        val s = Store.get(ctx)
        val today = LocalDate.now().toString()
        if (s.restingHrDay == today && s.restingHr > 0) return s.restingHr.toInt()
        val now = Instant.now()
        val bpms = runCatching {
            runBlocking {
                val out = mutableListOf<Long>()
                var token: String? = null
                var pages = 0
                do {
                    val page = client(ctx).readRecords(
                        ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofDays(7)), now),
                            pageToken = token,
                        )
                    )
                    page.records.forEach { rec -> rec.samples.mapTo(out) { it.beatsPerMinute } }
                    token = page.pageToken
                } while (token != null && ++pages < 20)
                out
            }
        }.getOrNull().orEmpty()
        val rest = if (bpms.size >= 50) bpms.sorted()[bpms.size / 20].toInt().coerceIn(35, 100) else 65
        if (bpms.size >= 50) {
            s.restingHr = rest.toLong()
            s.restingHrDay = today
        }
        return rest
    }

    private fun runPace(ctx: Context, s: Store, st: Settings, r: ExerciseSessionRecord, sec: Long): Long {
        val meters = runCatching {
            runBlocking {
                client(ctx).aggregate(
                    AggregateRequest(
                        metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(r.startTime, r.endTime),
                    )
                )[DistanceRecord.DISTANCE_TOTAL]?.inMeters
            }
        }.getOrNull() ?: return 0
        if (meters < MIN_RUN_METERS || sec <= 0) return 0
        val pace = (sec / (meters / 1000.0)).toLong()
        val best = s.bestPace
        val km = "%.1f".format(meters / 1000)
        return when {
            best == 0L -> {
                s.bestPace = pace
                s.log("Run pace baseline set: ${fmtPace(pace)}/km over $km km")
                0
            }
            pace < best -> {
                s.bestPace = pace
                Ledger.credit(s, st, st.n("runPrBonusMin") * 60L, "new best run pace ${fmtPace(pace)}/km ($km km)")
            }
            else -> 0
        }
    }

    fun fmtPace(secPerKm: Long) = "%d:%02d".format(secPerKm / 60, secPerKm % 60)

    /** Every [stepsUnit] steps today earns [stepsRewardMin], up to a daily cap. */
    private fun steps(ctx: Context, s: Store): Long {
        val st = Rules.current(s)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant()
        val count = runBlocking {
            client(ctx).aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, Instant.now()),
                )
            )[StepsRecord.COUNT_TOTAL] ?: 0L
        }
        if (s.stepsDay != today.toString()) {
            s.stepsDay = today.toString()
            s.stepsPaidMin = 0
        }
        val unit = max(1, st.n("stepsUnit"))
        val target = min((count / unit) * st.n("stepsRewardMin"), st.n("stepsCapMin").toLong())
        val add = target - s.stepsPaidMin
        if (add <= 0) return 0
        s.stepsPaidMin = target
        return Ledger.credit(s, st, add * 60, "%,d steps today".format(count))
    }

    /** Saves the last 2 days of watch sleep (for checking bedtime lock-ins), then settles held bonuses. */
    private fun sleepWindows(ctx: Context, s: Store) {
        val now = Instant.now()
        val records = runBlocking {
            client(ctx).readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofHours(48)), now),
                )
            ).records
        }
        val a = org.json.JSONArray()
        records.sortedBy { it.startTime }.forEach {
            a.put(org.json.JSONArray().put(it.startTime.toEpochMilli()).put(it.endTime.toEpochMilli()))
        }
        s.sleepJson = a.toString()
        Ledger.resolveBonusHolds(s, Rules.current(s))
    }

    /** A morning bonus when your watch says you were asleep by bedtime (6+ hours of sleep). */
    private fun sleep(ctx: Context, s: Store): Long {
        val st = Rules.current(s)
        if (!st.bedtimeOn || st.n("sleepBonusMin") <= 0) return 0
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val records = runBlocking {
            client(ctx).readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofHours(36)), now),
                )
            ).records
        }
        val done = s.creditedIds.toMutableSet()
        var total = 0L
        for (r in records) {
            val key = "sleep:" + r.metadata.id
            if (key in done) continue
            if (r.endTime.toEpochMilli() < s.installTs) continue
            val dur = Duration.between(r.startTime, r.endTime)
            if (dur.toHours() < 6) continue
            // Bedtime on the evening this sleep belongs to.
            val endDay = r.endTime.atZone(zone).toLocalDate()
            val bedDay = if (st.bedStart >= 12 * 60) endDay.minusDays(1) else endDay
            val bedtime = bedDay.atTime(LocalTime.of(st.bedStart / 60, st.bedStart % 60)).atZone(zone).toInstant()
            done += key
            s.creditedIds = done
            if (r.startTime.isAfter(bedtime.plus(Duration.ofMinutes(15)))) {
                s.log("Slept after bedtime last night, so no sleep bonus")
                continue
            }
            total += Ledger.credit(s, st, st.n("sleepBonusMin") * 60L, "asleep by bedtime (${dur.toHours()}h ${dur.toMinutesPart()}m)")
        }
        return total
    }

    /** True if the watch logged a non-walking workout on [day] (used to verify gym PRs). */
    fun hadWorkoutOn(ctx: Context, day: LocalDate): Boolean {
        val zone = ZoneId.systemDefault()
        val from = day.atStartOfDay(zone).toInstant()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant()
        return runCatching {
            sessions(ctx, from, to).any {
                it.exerciseType != ExerciseSessionRecord.EXERCISE_TYPE_WALKING &&
                    Duration.between(it.startTime, it.endTime).toMinutes() >= 10
            }
        }.getOrDefault(false)
    }
}
