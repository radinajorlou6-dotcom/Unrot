package com.radin.unrot

/** Levels, XP and badges. XP = every minute you've ever earned. */
object Game {
    fun xp(s: Store): Long = s.earnedSec / 60

    /** Total XP needed to reach [level]: 0, 30, 90, 180, 300, 450… */
    fun xpFor(level: Int): Long = 15L * level * (level - 1)

    fun level(xp: Long): Int {
        var l = 1
        while (xpFor(l + 1) <= xp) l++
        return l
    }

    fun title(level: Int): String = when {
        level >= 20 -> "Unrotted"
        level >= 16 -> "Monk mode"
        level >= 12 -> "Disciplined"
        level >= 8 -> "Focused"
        level >= 5 -> "Locked in"
        level >= 3 -> "Unrotting"
        level >= 2 -> "Waking up"
        else -> "Fresh start"
    }

    data class Badge(
        val icon: Int, val color: Int, val name: String, val goal: String,
        val earned: Boolean, val progress: Float,
    )

    fun badges(s: Store): List<Badge> {
        val lockH = s.lifeLockSec / 3600f
        val lvl = level(xp(s))
        fun b(icon: Int, color: Int, name: String, goal: String, have: Float, need: Float) =
            Badge(icon, color, name, goal, have >= need, (have / need).coerceIn(0f, 1f))
        return listOf(
            b(R.drawable.ic_lock, Col.VIOLET, "First lock-in", "Finish any lock-in", if (s.lifeLockSec >= 60) 1f else 0f, 1f),
            b(R.drawable.ic_timer, Col.VIOLET, "Deep work", "10 h locked in", lockH, 10f),
            b(R.drawable.ic_rocket_launch, Col.VIOLET, "Grinder", "50 h locked in", lockH, 50f),
            b(R.drawable.ic_self_improvement, Col.VIOLET, "Monk", "200 h locked in", lockH, 200f),
            b(R.drawable.ic_local_fire_department, Col.FIRE, "Warming up", "3-day streak", s.bestStreak.toFloat(), 3f),
            b(R.drawable.ic_local_fire_department, Col.FIRE, "On fire", "7-day streak", s.bestStreak.toFloat(), 7f),
            b(R.drawable.ic_star, Col.FIRE, "Unstoppable", "30-day streak", s.bestStreak.toFloat(), 30f),
            b(R.drawable.ic_call, Col.ACCENT, "Family first", "10 family calls", s.lifeCalls.toFloat(), 10f),
            b(R.drawable.ic_favorite, Col.ACCENT, "Heart of gold", "50 family calls", s.lifeCalls.toFloat(), 50f),
            b(R.drawable.ic_mobile_off, Col.BLUE, "Phone down", "5 phone-down bonuses", s.lifePhoneDown.toFloat(), 5f),
            b(R.drawable.ic_trophy, Col.GOLD, "PR machine", "5 gym PRs", s.lifePrs.toFloat(), 5f),
            b(R.drawable.ic_military_tech, Col.GOLD, "Level 10", "Reach level 10", lvl.toFloat(), 10f),
        )
    }
}
