package com.radin.unrot

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.math.max

/**
 * Your Galaxy Watch logs gym and soccer sessions in Samsung Health,
 * Samsung Health shares them with Health Connect, and this reads them.
 */
object HealthSync {
    val READ_EXERCISE: String = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    const val READ_BACKGROUND: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun available(ctx: Context): Boolean =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    private fun client(ctx: Context) = HealthConnectClient.getOrCreate(ctx)

    /** Permissions to ask for (background reading only if this phone supports it). */
    fun wantedPermissions(ctx: Context): Set<String> = runBlocking {
        val set = mutableSetOf(READ_EXERCISE)
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

    fun hasRead(ctx: Context) = READ_EXERCISE in granted(ctx)

    /**
     * Turns new workouts into minutes. Blocking; call from a background thread.
     * Returns seconds credited, or -1 if it couldn't read.
     */
    fun sync(ctx: Context): Long {
        if (!available(ctx)) return -1
        val s = Store.get(ctx)
        val st = Rules.current(s)
        val now = Instant.now()
        val since = Instant.ofEpochMilli(max(s.installTs, now.toEpochMilli() - 7L * 24 * 3600 * 1000))
        val records = runCatching {
            runBlocking {
                client(ctx).readRecords(
                    ReadRecordsRequest(
                        recordType = ExerciseSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(since, now),
                    )
                ).records
            }
        }.getOrElse { return -1 }

        val done = s.creditedIds.toMutableSet()
        var total = 0L
        for (r in records) {
            val id = r.metadata.id
            if (id in done) continue
            done += id
            if (!st.countWalks && r.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WALKING) continue
            val sec = Duration.between(r.startTime, r.endTime).seconds
            if (sec < 5 * 60) continue
            val name = r.title?.takeIf { it.isNotBlank() } ?: "workout"
            total += Ledger.credit(s, st, (sec / st.workoutRatio).toLong(), "$name, ${sec / 60} min")
        }
        s.creditedIds = done
        return total
    }
}
