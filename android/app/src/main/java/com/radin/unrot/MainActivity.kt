package com.radin.unrot

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.health.connect.client.PermissionController
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * The app: four tabs. Home (your time, lock-in, today), Earn (every way to earn, as quests),
 * Stats (level, charts, badges, history) and Settings (everything you set up once).
 */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_LOCKIN = "lockin"
        private var lastTab = 0

        /** Opens Unrot on the Home tab, where lock-in lives. */
        fun lockInIntent(ctx: android.content.Context): Intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_LOCKIN, true)

        const val TAB_HOME = 0
        const val TAB_EARN = 1
        const val TAB_STATS = 2
        const val TAB_SETTINGS = 3
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var s: Store
    private var tab = lastTab
    private var shownTab = -1
    private var scrollView: ScrollView? = null
    private val tabScroll = IntArray(4)
    private val expanded = HashSet<String>()
    private var sectionPassThrough = false

    private var balanceView: TextView? = null
    private var balanceRing: RingView? = null
    private var focusClock: TextView? = null
    private var focusRing: RingView? = null
    private var focusPay: TextView? = null
    private var focusWasActive = false
    private var hcAvailable = false
    private var hcGranted = false
    private var refreshing = false

    private val REQ_PERMS = 10
    private val REQ_HC = 11
    private val REQ_FITNOTES = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Store.get(this)
        Notif.ensureChannels(this)
        addEngagingAppsOnce()
        addMovieSitesOnce()
        if (intent?.getBooleanExtra(EXTRA_LOCKIN, false) == true) tab = TAB_HOME
    }

    /** Movie sites (Cinejoy, Netflix…) are blocked at the cheaper rate. Runs once. */
    private fun addMovieSitesOnce() {
        if (s.p.getBoolean("movieSitesAdded", false)) return
        val st = Rules.current(s)
        val add = Settings.MOVIE_SITES - st.sites
        Rules.propose(s, st.copy(sites = st.sites + add, halfRate = st.halfRate + add.map { "site:$it" }))
        s.p.edit().putBoolean("movieSitesAdded", true).apply()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_LOCKIN, false)) {
            tab = TAB_HOME
            tabScroll[TAB_HOME] = 0
        }
    }

    /**
     * Games and streaming apps are blocked at a reduced cost by default, so procrastinating
     * with something engaging is cheaper than doomscrolling. Runs once.
     */
    private fun addEngagingAppsOnce() {
        if (s.p.getBoolean("engagingAdded", false)) return
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
        val engaging = installed.filter {
            it.category == android.content.pm.ApplicationInfo.CATEGORY_GAME || it.packageName in Settings.STREAMING
        }.map { it.packageName }.filter { it != packageName }.toSet()
        if (engaging.isNotEmpty()) {
            val st = Rules.current(s)
            val newOnes = engaging - st.flagged
            Rules.propose(s, st.copy(flagged = st.flagged + newOnes, halfRate = st.halfRate + newOnes))
            s.log("Games & streaming apps added at ${st.n("engagingCostPct")}% cost")
        }
        s.p.edit().putBoolean("engagingAdded", true).apply()
    }

    override fun onResume() {
        super.onResume()
        render()
        refreshInBackground()
        h.post(liveBalance)
    }

    override fun onPause() {
        h.removeCallbacks(liveBalance)
        lastTab = tab
        scrollView?.let { if (shownTab >= 0) tabScroll[shownTab] = it.scrollY }
        super.onPause()
    }

    @Deprecated("Back on another tab goes Home first")
    override fun onBackPressed() {
        if (tab != TAB_HOME) {
            switchTab(TAB_HOME)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private val liveBalance = object : Runnable {
        override fun run() {
            balanceView?.let {
                it.text = balanceText()
                it.setTextColor(balanceColor())
            }
            balanceRing?.let {
                it.fraction = balanceFraction()
                it.color = balanceColor()
            }
            val active = Ledger.focusActive(s)
            if (active) {
                val st = Rules.current(s)
                if (s.focusFree && Ledger.localFocusActive(s)) {
                    val el = Ledger.freeElapsedSec(s)
                    focusClock?.text = Ledger.fmt(el)
                    focusPay?.text = "+${Ledger.fmtShort(Ledger.freePayout(s, st))} so far"
                    focusRing?.fraction = (el % 3600) / 3600f
                } else {
                    val left = Ledger.focusRemainingSec(s)
                    focusClock?.text = Ledger.fmt(left)
                    if (Ledger.localFocusActive(s) && s.focusPlannedSec > 0) {
                        focusRing?.fraction = 1f - left.toFloat() / s.focusPlannedSec
                    }
                }
            }
            if (active != focusWasActive) {
                // A lock-in just ended (or started elsewhere): redraw so the card updates.
                Ledger.settleFocus(s, Rules.current(s))
                render()
            }
            h.postDelayed(this, 1000)
        }
    }

    private fun balanceText(): String {
        val b = Ledger.balanceSec(s)
        return when {
            b > 0 -> Ledger.fmt(b)
            b < 0 -> "-" + Ledger.fmt(-b)
            else -> "0:00"
        }
    }

    private fun balanceColor(): Int {
        val b = Ledger.balanceSec(s)
        return when {
            b <= 0 -> Col.DANGER
            b <= 120 -> Col.WARN
            else -> Col.GOLD
        }
    }

    private fun balanceFraction(): Float {
        val cap = Rules.current(s).maxBankMin * 60f
        return (Ledger.balanceSec(s) / cap).coerceIn(0f, 1f)
    }

    /** Pulls in new workouts, family calls and laptop totals, then redraws. */
    private fun refreshInBackground(toast: Boolean = false) {
        if (refreshing) return
        refreshing = true
        Thread {
            var earned = 0L
            try {
                hcAvailable = HealthSync.available(this)
                hcGranted = hcAvailable && HealthSync.hasAll(this)
                if (hcAvailable) earned += maxOf(0L, HealthSync.sync(this))
                earned += Calls.sync(this)
                if (Sync.configured(s)) Sync.run(s)
            } catch (_: Throwable) {
            }
            runOnUiThread {
                refreshing = false
                if (toast) {
                    Toast.makeText(
                        this,
                        if (earned > 0) "+${Ledger.fmtShort(earned)} earned" else "Nothing new yet. Workouts can take a few minutes to sync from Samsung Health.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                if (!isFinishing) render()
            }
        }.start()
    }

    // ================================================================== layout: content + bottom tabs

    private fun switchTab(t: Int) {
        if (t == tab) {
            scrollView?.smoothScrollTo(0, 0)
            return
        }
        tab = t
        lastTab = t
        render()
    }

    private fun render() {
        val st = Rules.current(s)
        Ledger.ensureDailyBase(s, st)
        focusWasActive = Ledger.focusActive(s)
        balanceView = null; balanceRing = null; focusClock = null; focusRing = null; focusPay = null
        scrollView?.let { if (shownTab >= 0) tabScroll[shownTab] = it.scrollY }

        val ui = Ui(this)
        val content = ui.col(18)
        content.setPadding(dp(18), dp(10), dp(18), dp(24))
        try {
            when (tab) {
                TAB_HOME -> ui.homeTab(content, st)
                TAB_EARN -> ui.earnTab(content, st)
                TAB_STATS -> ui.statsTab(content)
                else -> ui.settingsTab(content, st)
            }
        } catch (e: Throwable) {
            showBuildError(ui, content, e)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        val nav = ui.bottomNav()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Col.BG)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.padForSystemBars(top = true, bottom = false)
        nav.padForSystemBars(top = false, bottom = true)
        setContentView(root)
        scrollView = scroll
        shownTab = tab
        val y = tabScroll[tab]
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    private fun Ui.bottomNav(): LinearLayout {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Col.CARD)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val items = listOf(
            Triple(TAB_HOME, R.drawable.ic_home, "Home"),
            Triple(TAB_EARN, R.drawable.ic_bolt, "Earn"),
            Triple(TAB_STATS, R.drawable.ic_bar_chart, "Stats"),
            Triple(TAB_SETTINGS, R.drawable.ic_settings, "Settings"),
        )
        for ((t, iconRes, label) in items) {
            val on = t == tab
            val item = col().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
                isClickable = true
                setOnClickListener { switchTab(t) }
            }
            val pill = FrameLayout(ctx).apply {
                background = if (on) rounded(Col.alpha(Col.VIOLET, 0x40), 50) else null
            }
            pill.addView(icon(iconRes, if (on) Col.VIOLET else Col.MUTED, 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            item.addView(pill, LinearLayout.LayoutParams(dp(64), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            item.addView(text(label, 12f, if (on) Col.TEXT else Col.MUTED, bold = on).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            bar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    /** A titled card. Inside a Settings group, it adds straight into the group instead. */
    private fun Ui.section(root: LinearLayout, name: String, iconRes: Int = 0, color: Int = Col.VIOLET): LinearLayout {
        if (sectionPassThrough) return root
        val c = card()
        if (iconRes != 0) {
            val r = row()
            r.addView(iconBadge(iconRes, color, 36))
            r.addView(title(name, 20f).apply { setPadding(dp(10), 0, 0, 0) })
            c.add(r, 12)
        } else {
            c.add(title(name, 20f), 12)
        }
        root.add(c, 14)
        return c
    }

    private fun Ui.pageTitle(root: LinearLayout, name: String, subtitle: String? = null) {
        root.add(title(name, 30f), if (subtitle == null) 16 else 4)
        if (subtitle != null) root.add(text(subtitle, 14.5f, Col.MUTED), 16)
    }

    // ================================================================== HOME

    private fun Ui.homeTab(root: LinearLayout, st: Settings) {
        // Top bar: name, level, streak.
        val top = row()
        top.addView(title("Unrot", 26f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val lvl = Game.level(Game.xp(s))
        top.addView(chip("Lv $lvl", Col.VIOLET).apply { setOnClickListener { switchTab(TAB_STATS) } })
        top.addView(space(1).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        top.addView(chip("🔥 ${s.streak}", Col.FIRE).apply { setOnClickListener { switchTab(TAB_STATS) } })
        root.add(top, 14)

        if (!UnrotService.running && Setup.accessibilityOn(this@MainActivity)) {
            val c = card()
            c.add(listRow(R.drawable.ic_warning, Col.WARN, "The blocker isn't running", "Turn Unrot off and on again in Accessibility settings."), 10)
            c.add(button("Open Accessibility", Ui.Style.SECONDARY) { open(Setup.accessibilityIntent()) }, 0)
            root.add(c, 14)
        }
        crashCard(root)
        freeReviewCard(root, st)
        heroCard(root, st)
        setupCompact(root)
        lockInCard(root, st)
        todayCard(root, st)
        tomorrowCard(root)
    }

    /** The big balance ring with your level bar underneath. */
    private fun Ui.heroCard(root: LinearLayout, st: Settings) {
        val c = card()
        c.gravity = Gravity.CENTER_HORIZONTAL
        val frame = FrameLayout(ctx)
        val ring = RingView(ctx).apply {
            fraction = balanceFraction()
            color = balanceColor()
            strokeDp = 16
        }
        balanceRing = ring
        frame.addView(ring, FrameLayout.LayoutParams(dp(230), dp(230), Gravity.CENTER))
        val center = col().apply { gravity = Gravity.CENTER }
        val bal = title(balanceText(), 50f, balanceColor()).apply { gravity = Gravity.CENTER }
        balanceView = bal
        center.addView(bal)
        center.addView(text("scroll time", 14f, Col.MUTED).apply { gravity = Gravity.CENTER })
        frame.addView(center, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        c.addWrap(frame, 10, Gravity.CENTER_HORIZONTAL)

        val b = Ledger.balanceSec(s)
        val status = when {
            Ledger.focusActive(s) -> "🔒 Locked in right now"
            b < 0 -> "You owe ${Ledger.fmtShort(-b)} from laptop overtime. What you earn next pays it back."
            b == 0L -> "Out of time. Lock in or hit a quest in Earn."
            else -> "Saved up, max ${st.maxBankMin / 60}h ${st.maxBankMin % 60}m".replace(" 0m", "")
        }
        c.add(text(status, 14.5f, if (b <= 0) Col.WARN else Col.MUTED).apply { gravity = Gravity.CENTER }, if (b > 0) 6 else 16)
        if (b > 0) {
            c.addWrap(button("Give time back", Ui.Style.GHOST, R.drawable.ic_favorite) { giveBackDialog() }, 12, Gravity.CENTER_HORIZONTAL)
        }

        // Level / XP
        val xp = Game.xp(s)
        val lvl = Game.level(xp)
        val from = Game.xpFor(lvl)
        val to = Game.xpFor(lvl + 1)
        val lr = row()
        lr.addView(text("Level $lvl · ${Game.title(lvl)}", 14.5f, Col.TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lr.addView(text("${xp - from} / ${to - from} XP", 13f, Col.MUTED))
        c.add(lr, 6)
        c.add(progress((xp - from).toFloat() / (to - from), Col.VIOLET, 12), 0)
        root.add(c, 14)
    }

    /** On Home only while setup isn't done: what's left, with buttons. */
    private fun Ui.setupCompact(root: LinearLayout) {
        val steps = setupSteps()
        val missing = steps.filter { !it.done }
        if (s.onboarded && missing.isEmpty()) return
        val c = card()
        val r = row()
        r.addView(iconBadge(R.drawable.ic_rocket_launch, Col.BLUE, 36))
        r.addView(title("Finish setup", 20f).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(chip("${steps.size - missing.size}/${steps.size}", Col.BLUE))
        c.add(r, 10)
        c.add(progress((steps.size - missing.size).toFloat() / steps.size, Col.BLUE, 10), 12)
        setupStepsInto(c, steps)
        root.add(c, 14)
    }

    // ------------------------------------------------------------------ lock in

    private fun Ui.lockInCard(root: LinearLayout, st: Settings) {
        val c = card()
        val hdr = row()
        hdr.addView(iconBadge(R.drawable.ic_lock, Col.VIOLET, 36))
        hdr.addView(title("Lock in", 20f).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hdr.addView(chip("${Rules.fmtRatio(st.focusRatio)} : 1", Col.VIOLET))
        c.add(hdr, 14)
        root.add(c, 14)

        if (Ledger.focusActive(s)) {
            val free = s.focusFree && Ledger.localFocusActive(s)
            val frame = FrameLayout(ctx)
            val ring = RingView(ctx).apply {
                color = if (free) Col.ACCENT else Col.VIOLET
                strokeDp = 12
                fraction = if (free) (Ledger.freeElapsedSec(s) % 3600) / 3600f
                else if (Ledger.localFocusActive(s) && s.focusPlannedSec > 0) 1f - Ledger.focusRemainingSec(s).toFloat() / s.focusPlannedSec
                else 0f
            }
            focusRing = ring
            frame.addView(ring, FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER))
            val center = col().apply { gravity = Gravity.CENTER }
            val clock = title(if (free) Ledger.fmt(Ledger.freeElapsedSec(s)) else Ledger.fmt(Ledger.focusRemainingSec(s)), 38f).apply { gravity = Gravity.CENTER }
            focusClock = clock
            center.addView(clock)
            center.addView(text(if (free) "so far" else "left", 13.5f, Col.MUTED).apply { gravity = Gravity.CENTER })
            frame.addView(center, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            c.addWrap(frame, 12, Gravity.CENTER_HORIZONTAL)

            if (free) {
                val pay = chip("+${Ledger.fmtShort(Ledger.freePayout(s, st))} so far", Col.ACCENT)
                focusPay = pay
                c.addWrap(pay, 10, Gravity.CENTER_HORIZONTAL)
                c.add(text("Free lock-in at ${Rules.fmtRatio(st.freeRatio)}:1. Stop whenever; you're paid for every minute.", 14f, Col.MUTED).apply { gravity = Gravity.CENTER }, 12)
                c.add(button("Stop & collect", Ui.Style.SUCCESS, R.drawable.ic_check_circle) {
                    val ran = Ledger.freeElapsedSec(s)
                    workedDialog(
                        "Stop & collect",
                        "It's been running for ${minText(ran / 60)}. If you forgot to stop it, slide it down to the time you really worked.",
                        ran, "Collect",
                    ) { keep ->
                        val got = Ledger.stopFree(s, Rules.current(s), keep)
                        syncNow()
                        Toast.makeText(this@MainActivity, "+${Ledger.fmtShort(got)} 💪", Toast.LENGTH_LONG).show()
                        render()
                    }
                }, 0)
            } else if (Ledger.localFocusActive(s)) {
                c.addWrap(chip("+${Ledger.fmtShort((s.focusPlannedSec / st.focusRatio).toLong())} when it ends", Col.ACCENT), 10, Gravity.CENTER_HORIZONTAL)
                c.add(text("Blocked apps and sites (and laptop games, if synced) are off until then.", 14f, Col.MUTED).apply { gravity = Gravity.CENTER }, 12)
                c.add(button("End early (${st.n("earlyEndPct")}% pay)", Ui.Style.DANGER, R.drawable.ic_stop) {
                    val st2 = Rules.current(s)
                    dialog().setTitle("End early?")
                        .setMessage(
                            "You'll get ${Ledger.fmtShort(Ledger.earlyEndPayout(s, st2))} for the time you've done " +
                                "(${st2.n("earlyEndPct")}% rate) instead of ${Ledger.fmtShort((s.focusPlannedSec / st2.focusRatio).toLong())} for finishing.",
                        )
                        .setPositiveButton("End it") { _, _ ->
                            Ledger.cancelFocus(s, Rules.current(s))
                            syncNow()
                            render()
                        }
                        .setNegativeButton("Keep going", null).show()
                }, 0)
            } else {
                c.add(
                    text(
                        if (Ledger.remoteFocusFree(s)) "Free lock-in running on your laptop. Blocked apps are off until you stop it there."
                        else "Started on your laptop. Blocked apps are off until it ends.",
                        14f, Col.MUTED,
                    ).apply { gravity = Gravity.CENTER }, 0,
                )
            }
            c.add(space(8))
            c.add(row().apply {
                addView(icon(R.drawable.ic_mobile_off, Col.BLUE, 18))
                addView(text("  Keep your screen off for +${st.n("phoneDownPct")}%", 13f, Col.MUTED))
                gravity = Gravity.CENTER
            }, 0)
            return
        }

        c.add(text("Blocked apps and sites are off until the timer ends. Study, work, anything real.", 14f, Col.MUTED), 12)
        for (pair in listOf(listOf(25, 45), listOf(60, 90))) {
            val r = row()
            for (m in pair) {
                val tile = col().apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(14), dp(8), dp(14))
                    background = rounded(if (m == 25) Col.alpha(Col.VIOLET, 0x33) else Col.CARD2, 18, if (m == 25) Col.VIOLET else Col.STROKE)
                    isClickable = true
                    setOnClickListener {
                        Ledger.startFocus(s, m)
                        syncNow()
                        Toast.makeText(this@MainActivity, "Locked in for $m min. Go do the thing 💪", Toast.LENGTH_LONG).show()
                        render()
                    }
                }
                tile.addView(title("$m", 30f).apply { gravity = Gravity.CENTER })
                tile.addView(text("min", 12.5f, Col.MUTED).apply { gravity = Gravity.CENTER })
                tile.addView(chip("+${Ledger.fmtShort((m * 60 / st.focusRatio).toLong())}", Col.ACCENT).apply {
                    (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(6); gravity = Gravity.CENTER_HORIZONTAL }
                })
                r.addWeighted(tile, if (m == pair.last()) 0 else 10)
            }
            c.add(r, 10)
        }
        c.add(button("Free lock-in · stop anytime · ${Rules.fmtRatio(st.freeRatio)}:1", Ui.Style.SUCCESS, R.drawable.ic_play_arrow) {
            Ledger.startFree(s)
            syncNow()
            Toast.makeText(this@MainActivity, "Free lock-in started. Stop whenever you're done 💪", Toast.LENGTH_LONG).show()
            render()
        }, 10)
        c.add(text("End a timed one early and you get ${st.n("earlyEndPct")}% for the time you did. Keep your screen off for +${st.n("phoneDownPct")}%.", 12.5f, Col.MUTED), 0)
    }

    // ------------------------------------------------------------------ today

    private fun Ui.todayCard(root: LinearLayout, st: Settings) {
        val c = section(root, "Today", R.drawable.ic_local_fire_department, Col.FIRE)
        val doom = Ledger.doomToday(s)
        val cap = st.n("dailyCapMin") * 60L
        val target = st.n("streakTargetMin") * 60L
        val r = row()
        r.addView(text("Doomscrolling", 15f, Col.TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(text("${Recap.hm(doom)} / ${Recap.hm(cap)}", 14f, Col.MUTED))
        c.add(r, 6)
        val color = when {
            doom >= cap -> Col.DANGER
            doom > target -> Col.WARN
            else -> Col.ACCENT
        }
        c.add(progress(doom.toFloat() / cap, color, 12), 6)
        c.add(
            text(
                when {
                    doom >= cap -> "Daily limit hit. Scroll apps are done until midnight (games and movies still work)."
                    doom > target -> "Over ${Recap.hm(target)}, so today won't count for your streak."
                    else -> "${Recap.hm(target - doom)} left before today stops counting for your streak."
                },
                13f, Col.MUTED,
            ), 14,
        )

        // Streak: last 7 days as dots.
        val sr = row()
        sr.addView(title("🔥 ${s.streak}", 24f, Col.FIRE))
        sr.addView(text(if (s.streak == 1) "  day streak" else "  day streak", 14f, Col.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val next = listOf(3, 7, 30).firstOrNull { it > s.streak } ?: ((s.streak / 30 + 1) * 30)
        sr.addView(chip("next bonus: day $next", Col.FIRE))
        c.add(sr, 12)
        val days = Recap.series(s)
        val dots = row()
        days.forEachIndexed { i, d ->
            val isToday = i == days.lastIndex
            val has = Recap.hasDay(s, d.date)
            val good = d.doomSec <= target
            val dc = col().apply { gravity = Gravity.CENTER_HORIZONTAL }
            val dot = android.view.View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    when {
                        isToday -> { setColor(Col.alpha(Col.FIRE, 0x22)); setStroke(dp(2), Col.FIRE) }
                        !has -> setColor(Col.CARD2)
                        good -> setColor(Col.FIRE)
                        else -> { setColor(Col.alpha(Col.DANGER, 0x33)); setStroke(dp(2), Col.DANGER) }
                    }
                }
            }
            dc.addView(dot, LinearLayout.LayoutParams(dp(28), dp(28)))
            dc.addView(text(d.date.dayOfWeek.name.take(1), 12f, if (isToday) Col.TEXT else Col.MUTED, bold = isToday).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            dots.addView(dc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        c.add(dots, 0)
    }

    private fun Ui.tomorrowCard(root: LinearLayout) {
        val cutoff = System.currentTimeMillis() - 20L * 3600 * 1000
        val recent = s.notes.filter { it.type == NoteActivity.TYPE_TOMORROW && it.ts > cutoff }
        if (recent.isEmpty()) return
        val c = section(root, "First thing today", R.drawable.ic_sunny, Col.GOLD)
        recent.takeLast(3).forEach { c.add(text("• " + it.text, 16.5f), 4) }
    }

    // ------------------------------------------------------------------ keeping yourself honest

    private fun minText(min: Long): String = when {
        min >= 60 && min % 60 == 0L -> "${min / 60} h"
        min >= 60 -> "${min / 60} h ${min % 60} min"
        else -> "$min min"
    }

    /**
     * A dialog with a minutes slider (plus −/+ for fine steps). [describe] turns the chosen minutes into the
     * big line above the slider; [onConfirm] gets the chosen minutes.
     */
    private fun sliderDialog(
        title: String, message: String, maxMin: Int, startMin: Int, confirm: String,
        describe: (Int) -> String, onConfirm: (Int) -> Unit,
    ) {
        val ui = Ui(this)
        val box = ui.col().apply { setPadding(dp(22), dp(6), dp(22), 0) }
        box.add(ui.text(message, 14.5f, Col.MUTED), 16)
        val value = ui.title(describe(startMin), 24f, Col.GOLD).apply { gravity = Gravity.CENTER }
        box.add(value, 8)
        val bar = android.widget.SeekBar(this).apply {
            max = maxMin.coerceAtLeast(1)
            progress = startMin.coerceIn(0, max)
            val tint = android.content.res.ColorStateList.valueOf(Col.VIOLET)
            progressTintList = tint
            thumbTintList = tint
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: android.widget.SeekBar?, p: Int, fromUser: Boolean) { value.text = describe(p) }
                override fun onStartTrackingTouch(b: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(b: android.widget.SeekBar?) {}
            })
        }
        val r = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        fun stepper(label: String, d: Int) = ui.chip(label, Col.VIOLET).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { bar.progress = (bar.progress + d).coerceIn(0, bar.max) }
        }
        r.addView(stepper("−", -1))
        r.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(stepper("+", 1))
        box.add(r, 4)
        dialog().setTitle(title).setView(box)
            .setPositiveButton(confirm) { _, _ -> onConfirm(bar.progress) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "How long did you really work?" for a free lock-in that ran [ranSec]. Gives back seconds to keep. */
    private fun workedDialog(title: String, message: String, ranSec: Long, confirm: String, onKeep: (Long) -> Unit) {
        val ranMin = (ranSec / 60).toInt()
        val ratio = Rules.current(s).freeRatio
        sliderDialog(
            title, message, ranMin, ranMin, confirm,
            describe = { m -> "Worked ${minText(m.toLong())} → +${Ledger.fmtShort((m * 60L / ratio).toLong())}" },
        ) { m -> onKeep(if (m >= ranMin) ranSec else m * 60L) }
    }

    /** Remove any amount of your balance, to undo time you didn't really earn. */
    private fun giveBackDialog() {
        val bal = Ledger.balanceSec(s)
        if (bal <= 0) return
        val maxMin = (bal / 60).toInt().coerceAtLeast(1)
        sliderDialog(
            "Give time back",
            "Earned something you didn't really earn, like a lock-in you forgot to stop? Take it off here. " +
                "This can't be undone, and it doesn't count toward your daily limit.",
            maxMin, 0, "Remove it",
            describe = { m -> if (m == 0) "Slide to choose" else "−${minText(m.toLong())}" },
        ) { m ->
            if (m <= 0) return@sliderDialog
            val took = Ledger.giveBack(s, if (m >= maxMin) bal else m * 60L)
            syncNow()
            Toast.makeText(this, "Removed ${Ledger.fmtShort(took)}. Respect. 🫡", Toast.LENGTH_LONG).show()
            render()
        }
    }

    /** A free lock-in that hit the max sits here, unpaid, until you say how long was real. */
    private fun Ui.freeReviewCard(root: LinearLayout, st: Settings) {
        val ran = s.freeReviewSec
        if (ran <= 0) return
        val c = card()
        c.add(listRow(
            R.drawable.ic_hourglass_top, Col.WARN, "Your free lock-in ran out",
            "It hit the ${Ledger.FREE_MAX_MS / 3_600_000}-hour max, so it probably kept going after you stopped. " +
                "Say how long you really worked to get paid.",
        ), 12)
        c.add(button("How long did I work?", Ui.Style.PRIMARY, R.drawable.ic_timer) {
            workedDialog("Free lock-in", "It ran for ${minText(ran / 60)} in total.", ran, "Get paid") { keep ->
                val got = Ledger.resolveFreeReview(s, Rules.current(s), keep)
                syncNow()
                Toast.makeText(this@MainActivity, if (got > 0) "+${Ledger.fmtShort(got)} 💪" else "Nothing added", Toast.LENGTH_LONG).show()
                render()
            }
        }, 0)
        root.add(c, 14)
    }

    private fun Ui.crashCard(root: LinearLayout) {
        val crash = s.lastCrash
        if (crash.isEmpty()) return
        val c = section(root, "Something crashed", R.drawable.ic_warning, Col.WARN)
        c.add(text("Copy this and send it to Claude so it can be fixed.", 14f, Col.MUTED), 6)
        c.add(text(crash.take(400), 12f, Col.WARN), 8)
        val r = row()
        r.addWeighted(button("Copy", Ui.Style.SECONDARY) {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Unrot crash", crash))
            Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
        })
        r.addWeighted(button("Dismiss", Ui.Style.GHOST) { s.lastCrash = ""; render() }, 0)
        c.add(r, 0)
    }

    // ================================================================== EARN

    private fun Ui.earnTab(root: LinearLayout, st: Settings) {
        pageTitle(root, "Earn time", "Every quest is checked by your watch, your call log or a timer. No honor system.")
        root.add(button(if (refreshing) "Checking…" else "Check watch & calls now", Ui.Style.PRIMARY, R.drawable.ic_sync) {
            refreshInBackground(toast = true)
        }, 16)

        fun quest(iconRes: Int, color: Int, name: String, reward: String, desc: String, extra: (LinearLayout) -> Unit = {}) {
            val c = card()
            c.add(listRow(iconRes, color, name, null, chip(reward, color)), 8)
            c.add(text(desc, 14f, Col.MUTED), 0)
            extra(c)
            root.add(c, 12)
        }

        quest(R.drawable.ic_lock, Col.VIOLET, "Lock in", "${Rules.fmtRatio(st.focusRatio)} min → 1",
            "Timed 25–90 min, or free-form at ${Rules.fmtRatio(st.freeRatio)}:1 that you can stop anytime.") { c ->
            c.add(space(10))
            c.add(button("Start a lock-in", Ui.Style.SECONDARY, R.drawable.ic_play_arrow) { switchTab(TAB_HOME) }, 0)
        }

        quest(R.drawable.ic_call, Col.ACCENT, "Call family", "+${st.n("callRewardMin")} min",
            "A ${st.n("callMinMin")}+ min call (phone, WhatsApp, Telegram…), once per person every ${st.n("callCooldownH")}h.") { c ->
            c.add(space(10))
            val fam = s.family
            if (fam.isEmpty()) {
                c.add(button("Add family", Ui.Style.SECONDARY, R.drawable.ic_add) { openSetting("family") }, 0)
            } else {
                val paid = s.obj("callPaid")
                val cool = st.n("callCooldownH") * 3_600_000L
                for (f in fam) {
                    val wait = paid.optLong(f.key, 0L) + cool - System.currentTimeMillis()
                    val r = row()
                    r.addView(text(f.name, 15.5f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    r.addView(
                        if (wait <= 0) chip("Ready", Col.ACCENT, filled = true)
                        else chip("in ${wait / 3_600_000}h ${(wait / 60_000) % 60}m", Col.MUTED),
                    )
                    c.add(r, 8)
                }
            }
        }

        quest(R.drawable.ic_fitness_center, Col.FIRE, "Work out", "${Rules.fmtRatio(st.workoutRatio)} min → 1",
            "Gym, soccer, anything your Galaxy Watch logs." + if (st.countWalks) " Walks count too." else " Walks don't count.")

        val stepsToday = if (s.stepsDay == LocalDate.now().toString()) s.stepsPaidMin else 0L
        quest(R.drawable.ic_directions_walk, Col.BLUE, "Steps", "+${st.n("stepsRewardMin")} / ${"%,d".format(st.n("stepsUnit"))}",
            "Up to ${st.n("stepsCapMin")} min a day.") { c ->
            c.add(space(10))
            val r = row()
            r.addView(text("Today", 13.5f, Col.TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(text("$stepsToday / ${st.n("stepsCapMin")} min", 13.5f, Col.MUTED))
            c.add(r, 6)
            c.add(progress(stepsToday.toFloat() / maxOf(1, st.n("stepsCapMin")), Col.BLUE, 10), 0)
        }

        quest(R.drawable.ic_bedtime, Col.INDIGO, "Sleep on time", "+${st.n("sleepBonusMin")} min",
            if (st.bedtimeOn) "Asleep by ${Rules.fmtClock(st.bedStart)} with 6+ hours, per your watch. Paid the next morning."
            else "Turn bedtime on in Settings → Rules to unlock this.")

        quest(R.drawable.ic_directions_run, Col.GOLD, "Run PR", "+${st.n("runPrBonusMin")} min",
            if (s.bestPace > 0) "Beat your best pace of ${HealthSync.fmtPace(s.bestPace)}/km on a 2+ km run."
            else "Your first 2+ km run sets the pace to beat.")

        quest(R.drawable.ic_trophy, Col.GOLD, "Gym PR", "+${st.n("liftPrBonusMin")} min",
            "A new best in FitNotes, on a day your watch logged a workout. Max ${st.n("liftPrCapMin")} min a day.") { c ->
            c.add(space(10))
            c.add(button("Check FitNotes", Ui.Style.SECONDARY, R.drawable.ic_fitness_center) { fitNotesDialog() }, 0)
        }

        val next = listOf(3, 7, 30).firstOrNull { it > s.streak } ?: ((s.streak / 30 + 1) * 30)
        quest(R.drawable.ic_local_fire_department, Col.FIRE, "Streak", "+${st.n("streak3Min")}/${st.n("streak7Min")}/${st.n("streak30Min")}",
            "Stay under ${Recap.hm(st.n("streakTargetMin") * 60L)} of doomscrolling a day. Bonuses at 3, 7 and 30 days.") { c ->
            c.add(space(10))
            val r = row()
            r.addView(text("${s.streak} days", 13.5f, Col.TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(text("next bonus at $next", 13.5f, Col.MUTED))
            c.add(r, 6)
            c.add(progress(s.streak.toFloat() / next, Col.FIRE, 10), 0)
        }

        quest(R.drawable.ic_mobile_off, Col.BLUE, "Phone down", "+${st.n("phoneDownPct")}%",
            "Screen off for a whole lock-in (10+ min, 1 min of leeway). Works for laptop lock-ins too.")

        quest(R.drawable.ic_sunny, Col.GOLD, "Daily drop", "+${st.dailyBaseMin} min", "Free minutes every morning, just for showing up.")
    }

    private fun fitNotesDialog() {
        dialog().setTitle("Check FitNotes for PRs")
            .setMessage(
                "In FitNotes, export your workouts as a CSV (spreadsheet) file. If FitNotes lets you share it, pick " +
                    "\"Unrot: check for PRs\". Otherwise tap \"Pick file\" here and choose the export.",
            )
            .setPositiveButton("Pick file") { _, _ ->
                runCatching {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                        REQ_FITNOTES,
                    )
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ================================================================== STATS

    private fun Ui.statsTab(root: LinearLayout) {
        val st = Rules.current(s)
        pageTitle(root, "Stats")

        // Level card
        val xp = Game.xp(s)
        val lvl = Game.level(xp)
        val from = Game.xpFor(lvl)
        val to = Game.xpFor(lvl + 1)
        val lc = card()
        val lr = row()
        val badge = title("$lvl", 28f, Col.VIOLET).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Col.alpha(Col.VIOLET, 0x33))
                setStroke(dp(2), Col.VIOLET)
            }
        }
        lr.addView(badge, LinearLayout.LayoutParams(dp(64), dp(64)))
        val lt = col().apply { setPadding(dp(14), 0, 0, 0) }
        lt.addView(title(Game.title(lvl), 22f))
        lt.addView(text("Level $lvl · ${to - xp} XP to level ${lvl + 1}", 13.5f, Col.MUTED))
        lr.addView(lt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lc.add(lr, 12)
        lc.add(progress((xp - from).toFloat() / (to - from), Col.VIOLET, 12), 8)
        lc.add(text("1 XP for every minute you earn.", 12.5f, Col.MUTED), 0)
        root.add(lc, 14)

        // This week, in tiles
        val w = Recap.week(this@MainActivity, s)
        fun tile(iconRes: Int, color: Int, value: String, label: String): LinearLayout = col().apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Col.CARD, 20, Col.STROKE)
            addView(icon(iconRes, color, 22))
            addView(title(value, 24f).apply { setPadding(0, dp(8), 0, dp(2)) })
            addView(text(label, 12.5f, Col.MUTED))
        }
        root.add(text("Last 7 days", 16f, Col.MUTED, bold = true), 8)
        val t1 = row()
        t1.addWeighted(tile(R.drawable.ic_lock, Col.VIOLET, Recap.hm(w.lockedSec), "locked in"), 10)
        t1.addWeighted(tile(R.drawable.ic_apps, Col.DANGER, Recap.hm(w.doomSec + w.laptopDoomSec), "doomscrolling"), 0)
        root.add(t1, 10)
        val t2 = row()
        t2.addWeighted(tile(R.drawable.ic_call, Col.ACCENT, "${w.calls}", "family calls"), 10)
        t2.addWeighted(tile(R.drawable.ic_hourglass_top, Col.GOLD, Recap.hm(w.earnedSec), "time earned"), 0)
        root.add(t2, 14)

        // Charts
        val days = Recap.series(s)
        val labels = days.map { it.date.dayOfWeek.name.take(1) }
        val target = st.n("streakTargetMin").toFloat()
        val dc = section(root, "Doomscrolling", R.drawable.ic_bar_chart, Col.DANGER)
        dc.add(BarChartView(ctx).apply {
            values = days.map { it.doomSec / 60f }
            this.labels = labels
            this.target = target
            colorFor = { _, v -> if (v <= target) Col.ACCENT else Col.DANGER }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
        }, 8)
        dc.add(text("Minutes per day. Dashed line = your ${Recap.hm(target.toLong() * 60)} streak target. Green = under it.", 12.5f, Col.MUTED), 0)

        val fc = section(root, "Locked in", R.drawable.ic_timer, Col.VIOLET)
        fc.add(BarChartView(ctx).apply {
            values = days.map { it.lockedSec / 60f }
            this.labels = labels
            colorFor = { _, _ -> Col.VIOLET }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
        }, 8)
        fc.add(text(w.bestLockDay?.let { "Best day: ${it.first}, ${Recap.hm(it.second)}" } ?: "No lock-ins this week yet.", 12.5f, Col.MUTED), 0)

        // Top time sinks
        if (w.top.isNotEmpty()) {
            val tc = section(root, "Top time sinks", R.drawable.ic_apps, Col.WARN)
            val max = w.top.maxOf { it.second }.toFloat()
            for ((name, sec) in w.top) {
                val r = row()
                r.addView(text(name, 15f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(text(Recap.hm(sec), 13.5f, Col.MUTED))
                tc.add(r, 5)
                tc.add(progress(sec / max, Col.WARN, 8), 12)
            }
        }

        // Badges
        val badges = Game.badges(s)
        val bc = section(root, "Badges · ${badges.count { it.earned }}/${badges.size}", R.drawable.ic_military_tech, Col.GOLD)
        badges.chunked(3).forEach { chunk ->
            val r = row().apply { gravity = Gravity.TOP }
            chunk.forEachIndexed { i, b ->
                val cell = col().apply { gravity = Gravity.CENTER_HORIZONTAL }
                cell.addView(iconBadge(b.icon, if (b.earned) b.color else Col.MUTED, 52).apply {
                    alpha = if (b.earned) 1f else 0.45f
                })
                cell.addView(text(b.name, 13f, if (b.earned) Col.TEXT else Col.MUTED, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                })
                cell.addView(text(if (b.earned) "Unlocked" else b.goal, 11.5f, Col.MUTED).apply { gravity = Gravity.CENTER })
                if (!b.earned && b.progress > 0f) {
                    cell.addView(progress(b.progress, b.color, 6).apply {
                        (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(6); width = dp(70) }
                    })
                }
                r.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (i == chunk.lastIndex) repeat(3 - chunk.size) { r.addView(space(1), LinearLayout.LayoutParams(0, 1, 1f)) }
            }
            bc.add(r, 16)
        }

        // History
        val log = s.log.take(12)
        if (log.isNotEmpty()) {
            val ac = section(root, "Recent", R.drawable.ic_schedule, Col.BLUE)
            val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
            for (l in log) {
                val good = l.text.startsWith("+")
                val r = row().apply { gravity = Gravity.TOP }
                r.addView(android.view.View(ctx).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (good) Col.ACCENT else Col.STROKE)
                    }
                }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { topMargin = dp(7) })
                val t = col().apply { setPadding(dp(10), 0, 0, 0) }
                t.addView(text(l.text, 14.5f, if (good) Col.TEXT else Col.MUTED))
                t.addView(text(fmt.format(Date(l.ts)), 11.5f, Col.MUTED))
                r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                ac.add(r, 10)
            }
        }

        root.add(button("Send my week to…", Ui.Style.SECONDARY, R.drawable.ic_send) {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Recap.shareText(this@MainActivity, s))
            runCatching { startActivity(Intent.createChooser(i, "Send your week to…")) }
        }, 0)
    }

    // ================================================================== SETTINGS

    /** Opens the Settings tab with one group expanded. */
    private fun openSetting(key: String) {
        expanded.add(key)
        tabScroll[TAB_SETTINGS] = 0
        tab = TAB_SETTINGS
        lastTab = tab
        render()
    }

    /** A collapsible group: header row (icon, title, summary, chevron); body only when open. */
    private fun Ui.group(
        root: LinearLayout, key: String, iconRes: Int, color: Int, name: String, summary: String,
        body: Ui.(LinearLayout) -> Unit,
    ) {
        val c = card()
        val open = key in expanded
        val chevron = icon(if (open) R.drawable.ic_keyboard_arrow_down else R.drawable.ic_chevron_right, Col.MUTED, 24)
        val header = listRow(iconRes, color, name, summary, chevron).apply {
            isClickable = true
            setOnClickListener {
                if (!expanded.remove(key)) expanded.add(key)
                render()
            }
        }
        c.add(header, if (open) 14 else 0)
        if (open) {
            c.add(divider(), 14)
            sectionPassThrough = true
            try {
                body(c)
            } finally {
                sectionPassThrough = false
            }
        }
        root.add(c, 10)
    }

    private fun Ui.settingsTab(root: LinearLayout, st: Settings) {
        pageTitle(root, "Settings")
        if (Pin.unlocked(s)) {
            val c = card()
            c.add(listRow(R.drawable.ic_key, Col.ACCENT, "PIN unlocked", "Every change applies right away for the next few minutes."), 0)
            root.add(c, 12)
        }
        val steps = setupSteps()
        val done = steps.count { it.done }
        group(root, "setup", R.drawable.ic_rocket_launch, Col.BLUE, "Setup & permissions", "$done of ${steps.size} done") { c ->
            setupStepsInto(c, steps)
            c.add(button("App info (restricted settings, battery)", Ui.Style.GHOST) { open(Setup.appInfoIntent(this@MainActivity)) }, 0)
        }
        val pm = packageManager
        val apps = st.flagged.count { p -> runCatching { pm.getApplicationInfo(p, 0); true }.getOrDefault(false) }
        group(root, "apps", R.drawable.ic_apps, Col.DANGER, "Blocked apps", "$apps apps") { c -> appsCard(c, st) }
        group(root, "sites", R.drawable.ic_public, Col.WARN, "Blocked sites", "${st.sites.size} sites") { c -> sitesCard(c, st) }
        group(root, "family", R.drawable.ic_family_restroom, Col.ACCENT, "Family",
            if (s.family.isEmpty()) "Nobody added yet" else s.family.joinToString(", ") { it.name }) { c -> familyCard(c) }
        val pend = Rules.pending(s).size
        group(root, "rules", R.drawable.ic_tune, Col.VIOLET, "Rules",
            if (pend > 0) "$pend change${if (pend == 1) "" else "s"} waiting to kick in" else "Ratios, limits, bedtime, bonuses") { c -> rulesCard(c, st) }
        group(root, "pin", R.drawable.ic_key, Col.GOLD, "Family PIN",
            when {
                !Pin.isSet(s) -> "Not set yet"
                Pin.unlocked(s) -> "Unlocked"
                else -> "Locked"
            }) { c -> pinCard(c) }
        group(root, "sync", R.drawable.ic_computer, Col.BLUE, "Laptop sync",
            when {
                !Sync.configured(s) -> "Not set up"
                s.lastSyncError.isNotEmpty() -> "Last sync failed"
                s.lastSyncTs == 0L -> "Not synced yet"
                else -> "Synced ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(s.lastSyncTs))}"
            }) { c -> syncCard(c) }
        if (s.notes.isNotEmpty()) {
            group(root, "notes", R.drawable.ic_sticky_note_2, Col.INDIGO, "Night notes", "${s.notes.size} saved") { c -> notesCard(c) }
        }
        group(root, "help", R.drawable.ic_help, Col.MUTED, "If Unrot stops stepping in", "OnePlus battery tips") { c -> helpCard(c) }
        root.add(space(10))
        root.add(text("Unrot ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""}", 12f, Col.MUTED).apply { gravity = Gravity.CENTER }, 0)
    }

    // ------------------------------------------------------------------ setup checklist

    private data class Step(val done: Boolean, val title: String, val why: String, val action: String, val run: () -> Unit)

    private fun setupSteps(): List<Step> {
        val ctx = this
        val steps = mutableListOf(
            Step(
                Setup.accessibilityOn(ctx), "Turn on the blocker",
                "Accessibility → Installed apps → Unrot → On. If it's greyed out: go back, tap \"App info\", ⋮ (top right) → \"Allow restricted settings\", then try again.",
                "Open Accessibility",
            ) { open(Setup.accessibilityIntent()) },
            Step(
                Setup.overlayOn(ctx), "Show over other apps",
                "Lets Unrot put its screen on top of TikTok and co.", "Allow",
            ) { open(Setup.overlayIntent(ctx)) },
            Step(
                Setup.notificationsOn(ctx), "Notifications",
                "For your live balance and the 1-minute warning.", "Allow",
            ) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS) },
            Step(
                Setup.batteryOn(ctx), "Stop OnePlus from killing Unrot",
                "Choose \"Allow\". Then also: App info → Battery → Allow background activity, and in Recents, pull down on Unrot's card to lock it.",
                "Allow",
            ) { open(Setup.batteryIntent(ctx)) },
            Step(
                Setup.callsOn(ctx), "Calls",
                "So family calls earn time and you can call straight from the block screen.", "Allow",
            ) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE), REQ_PERMS)
            },
        )
        val appCallers = s.family.filter { it.via != "phone" }
        if (appCallers.isNotEmpty()) {
            val apps = appCallers.map { it.app.label }.distinct().joinToString(", ")
            steps += Step(
                Setup.notificationAccessOn(ctx), "$apps calls",
                "Allow notification access so Unrot can see who an ongoing $apps call is with. It only looks at call notifications from calling apps. If it's greyed out, use \"Allow restricted settings\" in App info.",
                "Allow notification access",
            ) { open(Setup.notificationAccessIntent(ctx)) }
        }
        if (hcAvailable) {
            steps += Step(
                hcGranted, "Watch: workouts, steps & sleep",
                "Allow Exercise, Steps, Sleep and Distance (and background access if asked). Then in Samsung Health: ⋮ → Settings → Health Connect, and turn syncing on.",
                "Connect Health Connect",
            ) {
                runCatching {
                    val perms = HealthSync.wantedPermissions(ctx)
                    startActivityForResult(
                        PermissionController.createRequestPermissionResultContract().createIntent(ctx, perms), REQ_HC,
                    )
                }.onFailure { open(Setup.healthConnectSettings()) }
            }
        }
        steps += Step(
            Setup.adminOn(ctx), "Uninstall protection",
            "Makes Unrot much harder to delete in a weak moment.", "Turn on",
        ) { open(Setup.adminIntent(ctx)) }
        return steps
    }

    private fun Ui.setupStepsInto(c: LinearLayout, steps: List<Step>) {
        val ctx = this@MainActivity
        for (st in steps) {
            c.add(
                listRow(
                    if (st.done) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked,
                    if (st.done) Col.ACCENT else Col.MUTED, st.title, if (st.done) null else st.why,
                ), if (st.done) 8 else 8,
            )
            if (!st.done) c.add(button(st.action, Ui.Style.PRIMARY) { st.run() }, 14)
        }
        if (!s.onboarded) {
            c.add(
                text(
                    "When you're done, finish setup. After that, Unrot guards its own settings and only your family PIN (or a 24-hour wait) can loosen things.",
                    13.5f, Col.MUTED,
                ), 8,
            )
            c.add(button("Finish setup", Ui.Style.SUCCESS, R.drawable.ic_check_circle) {
                if (!Setup.accessibilityOn(ctx)) {
                    Toast.makeText(ctx, "Turn on the blocker first.", Toast.LENGTH_LONG).show()
                } else {
                    s.onboarded = true
                    s.log("Setup finished, protection armed")
                    render()
                }
            }, 8)
        }
    }

    private fun open(i: Intent) {
        runCatching { startActivity(i) }.onFailure {
            Toast.makeText(this, "Couldn't open that screen. Find it in Settings.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Fine for a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FITNOTES && resultCode == RESULT_OK && data?.data != null) {
            startActivity(Intent(this, ImportActivity::class.java).setData(data.data).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return
        }
        refreshInBackground()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ------------------------------------------------------------------ apps, sites, family

    private fun Ui.appsCard(root: LinearLayout, st: Settings) {
        val c = section(root, "Blocked apps")
        val pm = packageManager
        val pct = st.n("engagingCostPct")
        val installed = st.flagged.mapNotNull { p ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() to p }.getOrNull()
        }.sortedBy { it.first.lowercase() }
        if (installed.isEmpty()) c.add(text("None of your blocked apps are installed yet.", 14.5f, Col.MUTED), 10)
        for ((label, p) in installed) {
            val half = p in st.halfRate
            val r = row()
            r.addView(text(label, 15.5f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(chip(if (half) "$pct%" else "Full price", if (half) Col.ACCENT else Col.DANGER))
            c.add(r, 8)
        }
        c.add(space(4))
        c.add(button("Edit blocked apps", Ui.Style.SECONDARY, R.drawable.ic_tune) {
            startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
        }, 0)
    }

    private fun Ui.sitesCard(root: LinearLayout, st: Settings) {
        val c = section(root, "Blocked sites")
        c.add(
            text(
                "Work like blocked apps in Brave, Chrome and most other browsers. Time only counts while the site is on screen. Tap a price to switch.",
                13.5f, Col.MUTED,
            ), 12,
        )
        val pct = st.n("engagingCostPct")
        for (d in st.sites.sortedWith(compareBy({ "site:$it" !in st.halfRate }, { it }))) {
            val half = "site:$d" in st.halfRate
            val r = row()
            r.addView(icon(if (half) R.drawable.ic_movie else R.drawable.ic_public, if (half) Col.ACCENT else Col.MUTED, 18))
            r.addView(text("  $d", 15.5f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(chip(if (half) "$pct%" else "Full", if (half) Col.ACCENT else Col.DANGER).apply {
                setOnClickListener {
                    propose(if (half) st.copy(halfRate = st.halfRate - "site:$d") else st.copy(halfRate = st.halfRate + "site:$d"))
                }
            })
            r.addView(icon(R.drawable.ic_close, Col.MUTED, 34).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { propose(st.copy(sites = st.sites - d)) }
            })
            c.add(r, 6)
        }
        c.add(text("Sites also match other endings (cinejoy.pk also catches cinejoy.to).", 12.5f, Col.MUTED), 10)
        c.add(button("Block a site", Ui.Style.SECONDARY, R.drawable.ic_add) {
            val ui2 = Ui(this@MainActivity)
            val box = ui2.col(16)
            val f = ui2.input("e.g. twitch.tv")
            box.add(f)
            val movie = android.widget.CheckBox(this@MainActivity).apply {
                text = "Movie / streaming site (costs ${Rules.current(s).n("engagingCostPct")}%)"
                setTextColor(Col.TEXT)
                textSize = 15f
            }
            box.add(movie, 0)
            dialog().setTitle("Block a site").setView(box)
                .setPositiveButton("Block") { _, _ ->
                    val d = f.text.toString().trim().lowercase()
                        .substringAfter("://").substringBefore('/').removePrefix("www.").removePrefix("m.")
                    val cur = Rules.current(s)
                    if (d.contains('.')) propose(
                        cur.copy(sites = cur.sites + d, halfRate = if (movie.isChecked) cur.halfRate + "site:$d" else cur.halfRate),
                    )
                    else Toast.makeText(this@MainActivity, "That doesn't look like a website.", Toast.LENGTH_LONG).show()
                }.setNegativeButton("Cancel", null).show()
        }, 0)
    }

    private fun Ui.familyCard(root: LinearLayout) {
        val c = section(root, "Family")
        c.add(text("Who the block screen suggests calling. Calls with them earn time.", 13.5f, Col.MUTED), 12)
        for (f in s.family) {
            val how = if (f.via == "phone") f.phone else f.app.label +
                (if (f.appName.isNotBlank()) " as \"${f.appName}\"" else "") +
                (if (f.phone.isNotBlank()) " · ${f.phone}" else "")
            val remove = icon(R.drawable.ic_delete, Col.MUTED, 38).apply {
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setOnClickListener {
                    s.family = s.family.filter { it != f }
                    render()
                }
            }
            c.add(listRow(R.drawable.ic_call, Col.ACCENT, f.name, how, remove), 10)
        }
        c.add(button("Add family member", Ui.Style.SECONDARY, R.drawable.ic_add) { addFamilyDialog() }, 0)
    }

    /** Step 1: how do you call them? */
    private fun addFamilyDialog() {
        val apps = CallApps.all
        dialog().setTitle("How do you call them?")
            .setItems(apps.map { if (it.id == "phone") "📞 Regular phone call" else it.label }.toTypedArray()) { _, i ->
                addFamilyDetails(apps[i])
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Step 2: their details for that way of calling. */
    private fun addFamilyDetails(app: CallApp) {
        val ui = Ui(this)
        val box = ui.col(16)
        val name = ui.input("Name (e.g. Mom)")
        box.add(name)
        val appName = if (app.id != "phone") ui.input("Their name in ${app.label} (leave blank if the same)") else null
        appName?.let { box.add(it) }
        val handle = app.handleHint?.let { hint ->
            ui.input(hint).apply { if (app.id != "telegram") inputType = InputType.TYPE_CLASS_PHONE }
        }
        handle?.let { box.add(it) }
        if (app.id != "phone") {
            box.add(
                ui.text(
                    "Calls count when ${app.label}'s call notification shows this name, and only while the call is connected.",
                    13f, Col.MUTED,
                ), 0,
            )
        }
        dialog().setTitle(if (app.id == "phone") "Add family member" else "Add family member (${app.label})").setView(box)
            .setPositiveButton("Add") { _, _ ->
                val n = name.text.toString().trim()
                val h = handle?.text?.toString()?.trim().orEmpty()
                val an = appName?.text?.toString()?.trim().orEmpty()
                when {
                    n.isEmpty() -> Toast.makeText(this, "Need a name.", Toast.LENGTH_LONG).show()
                    app.id == "phone" && Calls.norm(h).length < 7 -> Toast.makeText(this, "Need a full phone number.", Toast.LENGTH_LONG).show()
                    else -> {
                        s.family = s.family + Contact(n, h, app.id, an)
                        if (app.id != "phone" && !Setup.notificationAccessOn(this)) {
                            Toast.makeText(this, "Added. Do the new \"${app.label} calls\" setup step so calls count.", Toast.LENGTH_LONG).show()
                        }
                        render()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ rules

    private fun Ui.rulesCard(root: LinearLayout, st: Settings) {
        val c = section(root, "Rules")
        c.add(
            text(
                if (Pin.unlocked(s)) "PIN unlocked: every change applies right away."
                else "Stricter changes apply now. Looser ones wait 24 hours, so a 1am you can't undo a daytime you.",
                13.5f, if (Pin.unlocked(s)) Col.ACCENT else Col.MUTED,
            ), 12,
        )

        val pend = Rules.pending(s)
        if (pend.isNotEmpty()) {
            val pc = col(14).apply { background = rounded(Col.alpha(Col.WARN, 0x1F), 16, Col.alpha(Col.WARN, 0x55)) }
            pc.add(text("Waiting to kick in", 15f, Col.WARN, bold = true), 8)
            for (p in pend.sortedBy { it.applyAt }) {
                val left = maxOf(0L, p.applyAt - System.currentTimeMillis()) / 60000
                val r = row()
                r.addView(
                    text("${p.label}\nin ${left / 60}h ${left % 60}m", 14f),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                r.addView(button("Cancel", Ui.Style.GHOST) { Rules.cancel(s, p.key); render() })
                pc.add(r, 8)
            }
            c.add(pc, 14)
        }

        fun header(t: String) {
            c.add(text(t.uppercase(), 12f, Col.VIOLET, bold = true).apply { letterSpacing = 0.08f; setPadding(0, dp(8), 0, 0) }, 8)
        }

        fun rule(label: String, value: String, edit: () -> Unit) {
            val r = row().apply {
                isClickable = true
                setOnClickListener { edit() }
                setPadding(0, dp(6), 0, dp(6))
            }
            r.addView(text(label, 14.5f, Col.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(chip(value, Col.VIOLET))
            c.add(r, 4)
        }

        fun num(key: String) {
            val nr = NumRules.byKey(key)
            rule(nr.label, nr.fmt(st.n(key))) {
                numberDialog(nr.label, st.n(key).toString()) { v ->
                    propose(st.withNum(key, v.toInt().coerceIn(nr.min, nr.max)))
                }
            }
        }

        header("Earning")
        rule("Lock-in ratio", "${Rules.fmtRatio(st.focusRatio)} : 1") {
            numberDialog("Minutes locked in per 1 min earned", st.focusRatio.toString(), decimal = true) { v ->
                propose(st.copy(focusRatio = v.coerceIn(0.1, 100.0)))
            }
        }
        rule("Free lock-in ratio", "${Rules.fmtRatio(st.freeRatio)} : 1") {
            numberDialog("Free lock-in minutes per 1 min earned", st.freeRatio.toString(), decimal = true) { v ->
                propose(st.copy(freeRatio = v.coerceIn(0.1, 100.0)))
            }
        }
        num("earlyEndPct")
        num("phoneDownPct")
        rule("Workout ratio", "${Rules.fmtRatio(st.workoutRatio)} : 1") {
            numberDialog("Workout minutes per 1 min earned", st.workoutRatio.toString(), decimal = true) { v ->
                propose(st.copy(workoutRatio = v.coerceIn(0.1, 100.0)))
            }
        }
        rule("Count walks as workouts", if (st.countWalks) "Yes" else "No") { propose(st.copy(countWalks = !st.countWalks)) }
        listOf("callRewardMin", "callMinMin", "callCooldownH", "stepsUnit", "stepsRewardMin", "stepsCapMin",
            "sleepBonusMin", "runPrBonusMin", "liftPrBonusMin", "liftPrCapMin").forEach { num(it) }
        rule("Free minutes every day", "${st.dailyBaseMin} min") {
            numberDialog("Free minutes per day", st.dailyBaseMin.toString()) { v ->
                propose(st.copy(dailyBaseMin = v.toInt().coerceIn(0, 600)))
            }
        }

        header("Limits")
        num("dailyCapMin")
        rule("Most time you can save up", "${st.maxBankMin} min") {
            numberDialog("Max saved minutes", st.maxBankMin.toString()) { v ->
                propose(st.copy(maxBankMin = v.toInt().coerceIn(5, 1440)))
            }
        }
        num("engagingCostPct")

        header("Streaks")
        listOf("streakTargetMin", "streak3Min", "streak7Min", "streak30Min").forEach { num(it) }

        header("Nudges")
        num("introWaitSec")
        rule("\"Still glad you're here?\" check-in", if (st.checkInMin == 0) "Off" else "${st.checkInMin} min") {
            numberDialog("Minutes of non-stop scrolling before a check-in (0 = off)", st.checkInMin.toString()) { v ->
                propose(st.copy(checkInMin = v.toInt().coerceIn(0, 240)))
            }
        }
        rule("Bedtime", if (st.bedtimeOn) "${Rules.fmtClock(st.bedStart)}–${Rules.fmtClock(st.bedEnd)}" else "Off") { bedtimeDialog(st) }
        rule("Bedtime wait before \"continue\"", "${st.bedWaitSec}s") {
            numberDialog("Seconds to wait", st.bedWaitSec.toString()) { v ->
                propose(st.copy(bedWaitSec = v.toInt().coerceIn(0, 600)))
            }
        }

        header("Protection")
        rule("Protect Unrot's settings", if (st.protection) "On" else "Off") { propose(st.copy(protection = !st.protection)) }

        // Any rule not placed above still shows up (so new ones are never hidden).
        val placed = setOf(
            "earlyEndPct", "phoneDownPct", "callRewardMin", "callMinMin", "callCooldownH", "stepsUnit", "stepsRewardMin",
            "stepsCapMin", "sleepBonusMin", "runPrBonusMin", "liftPrBonusMin", "liftPrCapMin", "dailyCapMin",
            "engagingCostPct", "streakTargetMin", "streak3Min", "streak7Min", "streak30Min", "introWaitSec",
        )
        val rest = NumRules.all.filter { it.key !in placed }
        if (rest.isNotEmpty()) {
            header("More")
            rest.forEach { num(it.key) }
        }
    }

    private fun propose(new: Settings) {
        val r = Rules.propose(s, new)
        val msg = when {
            r.delayed > 0 -> "That loosens your rules, so it kicks in 24h from now. (The PIN skips the wait.)"
            r.applied > 0 -> "Updated."
            else -> "No change."
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        render()
    }

    private fun numberDialog(title: String, current: String, decimal: Boolean = false, onOk: (Double) -> Unit) {
        val ui = Ui(this)
        val box = ui.col(16)
        val field = ui.input(current, numeric = true).apply {
            setText(current.removeSuffix(".0"))
            if (!decimal) inputType = InputType.TYPE_CLASS_NUMBER
        }
        box.add(field)
        dialog().setTitle(title).setView(box)
            .setPositiveButton("Save") { _, _ ->
                field.text.toString().toDoubleOrNull()?.let(onOk)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun bedtimeDialog(st: Settings) {
        dialog().setTitle("Bedtime")
            .setItems(arrayOf("Change times", if (st.bedtimeOn) "Turn bedtime off" else "Turn bedtime on (11pm – 7am)")) { _, which ->
                if (which == 1) {
                    propose(if (st.bedtimeOn) st.copy(bedStart = 0, bedEnd = 0) else st.copy(bedStart = 23 * 60, bedEnd = 7 * 60))
                } else {
                    TimePickerDialog(this, android.R.style.Theme_Material_Dialog_Alert, { _, h1, m1 ->
                        TimePickerDialog(this, android.R.style.Theme_Material_Dialog_Alert, { _, h2, m2 ->
                            propose(st.copy(bedStart = h1 * 60 + m1, bedEnd = h2 * 60 + m2))
                        }, st.bedEnd / 60, st.bedEnd % 60, false).apply { setTitle("Bedtime ends at") }.show()
                    }, st.bedStart / 60, st.bedStart % 60, false).apply { setTitle("Bedtime starts at") }.show()
                }
            }.show()
    }

    // ------------------------------------------------------------------ PIN

    private fun Ui.pinCard(root: LinearLayout) {
        val c = section(root, "Family PIN")
        when {
            !Pin.isSet(s) -> {
                c.add(
                    text(
                        "Hand your phone to a family member and have them pick a PIN you don't know. With it, they can unlock Unrot for 10 minutes to skip the 24-hour wait or turn things off.",
                        13.5f, Col.MUTED,
                    ), 12,
                )
                c.add(button("Set family PIN", Ui.Style.SECONDARY, R.drawable.ic_key) { setPinDialog() }, 0)
            }
            Pin.unlocked(s) -> {
                val left = (s.adminUntil - System.currentTimeMillis()) / 60000 + 1
                c.add(text("Unlocked for about $left more min.", 15f, Col.ACCENT, bold = true), 12)
                c.add(button("Change PIN", Ui.Style.SECONDARY) { setPinDialog() })
                c.add(button("Lock now", Ui.Style.GHOST) { Pin.lockNow(s); render() }, 0)
            }
            else -> {
                c.add(text("Locked. Your family member can unlock it for 10 minutes.", 14.5f, Col.MUTED), 12)
                c.add(button("Enter PIN", Ui.Style.SECONDARY, R.drawable.ic_key) { enterPinDialog() }, 0)
            }
        }
    }

    private fun pinField(ui: Ui, hint: String): EditText = ui.input(hint).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun setPinDialog() {
        val ui = Ui(this)
        val box = ui.col(16)
        val a = pinField(ui, "New PIN (4+ digits)")
        val b = pinField(ui, "Repeat PIN")
        box.add(a)
        box.add(b)
        dialog().setTitle("Family member: choose a PIN").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val p = a.text.toString()
                if (p.length >= 4 && p == b.text.toString()) {
                    Pin.set(s, p)
                    Pin.lockNow(s)
                    s.log("Family PIN set")
                    Toast.makeText(this, "PIN saved. Keep it secret 😉", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "PINs didn't match or were too short.", Toast.LENGTH_LONG).show()
                }
                render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun enterPinDialog() {
        val ui = Ui(this)
        val box = ui.col(16)
        val a = pinField(ui, "PIN")
        box.add(a)
        dialog().setTitle("Enter family PIN").setView(box)
            .setPositiveButton("Unlock") { _, _ ->
                if (Pin.check(s, a.text.toString())) {
                    s.log("Unlocked with family PIN")
                    Toast.makeText(this, "Unlocked for 10 minutes.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Wrong PIN.", Toast.LENGTH_SHORT).show()
                }
                render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ laptop sync

    private fun Ui.syncCard(root: LinearLayout) {
        val c = section(root, "Laptop sync")
        c.add(
            text(
                "Shares one balance between this phone and your laptop, so games cost the same time you earn here. Setup steps are in the README on GitHub.",
                13.5f, Col.MUTED,
            ), 12,
        )
        val url = input("Firebase database URL (https://…firebasedatabase.app)")
        url.setText(s.dbUrl)
        c.add(url, 10)
        c.add(listRow(R.drawable.ic_key, Col.BLUE, if (s.syncCode.isEmpty()) "No sync code yet" else s.syncCode, "Sync code"), 10)
        val r = row()
        r.addWeighted(button("New code", Ui.Style.SECONDARY) {
            s.dbUrl = url.text.toString().trim()
            s.syncCode = Sync.newCode()
            s.log("Created a laptop sync code")
            refreshInBackground()
            render()
        })
        r.addWeighted(button("Enter code", Ui.Style.SECONDARY) {
            s.dbUrl = url.text.toString().trim()
            val ui2 = Ui(this@MainActivity)
            val box = ui2.col(16)
            val f = ui2.input("Sync code from your laptop")
            box.add(f)
            dialog().setTitle("Enter sync code").setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val code = f.text.toString().trim()
                    if (code.length >= 8) {
                        s.syncCode = code
                        refreshInBackground()
                    }
                    render()
                }.setNegativeButton("Cancel", null).show()
        }, 0)
        c.add(r, 10)
        c.add(button("Save URL & sync now", Ui.Style.PRIMARY, R.drawable.ic_sync) {
            s.dbUrl = url.text.toString().trim()
            refreshInBackground(toast = false)
            Toast.makeText(this@MainActivity, "Syncing…", Toast.LENGTH_SHORT).show()
        }, 10)
        val status = when {
            !Sync.configured(s) -> "Not set up"
            s.lastSyncError.isNotEmpty() -> "Last sync failed: ${s.lastSyncError}"
            s.lastSyncTs == 0L -> "Not synced yet"
            else -> "Last synced ${SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(s.lastSyncTs))}"
        }
        c.add(text(status, 12.5f, if (s.lastSyncError.isNotEmpty()) Col.WARN else Col.MUTED), 4)
        for (d in Ledger.remotes(s)) {
            c.add(text("• ${d.name}: earned ${Ledger.fmtShort(d.earned)}, spent ${Ledger.fmtShort(d.spent)}", 12.5f, Col.MUTED), 2)
        }
    }

    private fun syncNow() {
        if (Sync.configured(s)) Thread { Sync.run(s) }.start()
    }

    // ------------------------------------------------------------------ notes and help

    private fun Ui.notesCard(root: LinearLayout) {
        val notes = s.notes
        if (notes.isEmpty()) return
        val c = section(root, "Night notes")
        val fmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault())
        for (n in notes.takeLast(6).reversed()) {
            c.add(
                listRow(
                    if (n.type == NoteActivity.TYPE_DUMP) R.drawable.ic_psychology else R.drawable.ic_edit_note,
                    Col.INDIGO, n.text, fmt.format(Date(n.ts)),
                ), 10,
            )
        }
        c.add(button("Clear notes", Ui.Style.GHOST) {
            dialog().setTitle("Clear all notes?").setPositiveButton("Clear") { _, _ ->
                s.notes = emptyList(); render()
            }.setNegativeButton("Cancel", null).show()
        }, 0)
    }

    private fun Ui.helpCard(root: LinearLayout) {
        val c = section(root, "If Unrot stops stepping in")
        c.add(
            text(
                "OnePlus is aggressive about closing background apps. If blocking stops working:\n" +
                    "• App info → Battery → \"Allow background activity\" on\n" +
                    "• Settings → Apps → Auto launch → Unrot on\n" +
                    "• Recents → pull down on Unrot's card to lock it\n" +
                    "• Accessibility → Unrot: turn it off and on again",
                14f, Col.MUTED,
            ), 12,
        )
        c.add(button("Open App info", Ui.Style.SECONDARY) { open(Setup.appInfoIntent(this@MainActivity)) }, 0)
    }
}
