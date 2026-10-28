package com.radin.unrot

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.time.LocalTime

/**
 * The engine. Android keeps accessibility services alive far more reliably than
 * normal background apps, which matters a lot on OnePlus/OxygenOS.
 */
class UnrotService : AccessibilityService() {

    companion object {
        @Volatile var running = false
            private set

        /** Bedtime "continue anyway" passes: package -> valid until (ms). */
        val nightPass = HashMap<String, Long>()
        @Volatile private var checkInAck = false

        fun grantNightPass(pkg: String) {
            nightPass[pkg] = System.currentTimeMillis() + 15 * 60_000L
        }

        fun ackCheckIn() {
            checkInAck = true
        }

        /** App waiting for its opening card to be answered. */
        @Volatile var introPending: String? = null
        /** Last time each blocked app was on screen, for the "skip the card within 2 min" rule. */
        val lastInApp = HashMap<String, Long>()
        const val INTRO_SKIP_MS = 2 * 60_000L

        fun introDone(pkg: String) {
            if (introPending == pkg) introPending = null
            lastInApp[pkg] = System.currentTimeMillis()
        }

        const val NIGHT_PASS_MIN = 15

        /** Where each browser keeps its address bar, so blocked sites can be spotted. */
        val URL_BAR_IDS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.chrome.beta" to "com.chrome.beta:id/url_bar",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "com.brave.browser_beta" to "com.brave.browser_beta:id/url_bar",
            "com.vivaldi.browser" to "com.vivaldi.browser:id/url_bar",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "com.opera.browser" to "com.opera.browser:id/url_field",
            "com.opera.gx" to "com.opera.gx:id/addressbarEdit",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/omnibarTextInput",
        )
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var s: Store
    private var currentPkg: String? = null
    private var imePkgs: Set<String> = emptySet()
    private var lastBlockLaunch = 0L
    private var lastGuard = 0L
    private var ticks = 0L
    private var continuousSec = 0L
    private var nextCheckInAt = Long.MAX_VALUE
    private var lastFlaggedAt = 0L
    private var warnedLow = false
    private var bgBusy = false
    private var pill: TextView? = null
    private var currentSite: String? = null
    private var lastUrlCheck = 0L
    private var spendAcc = 0.0
    private var pillParams: WindowManager.LayoutParams? = null

    private val ignoredPkgs = setOf("com.android.systemui", "android")
    private val guardPkgs = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.oplus.securitypermission",
        "com.coloros.securitypermission",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        s = Store.get(this)
        running = true
        imePkgs = runCatching {
            getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
        Notif.ensureChannels(this)
        goForeground()
        s.log("Unrot is running")
        h.removeCallbacks(tick)
        h.post(tick)
    }

    override fun onDestroy() {
        hidePillNow()
        running = false
        h.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun goForeground() {
        runCatching {
            val n = Notif.status(this, statusText())
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notif.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notif.ID_STATUS, n)
            }
        }
    }

    private fun statusText(): String {
        val bal = Ledger.balanceSec(s)
        return when {
            Ledger.focusActive(s) -> "🔒 Locked in · ${Ledger.fmt(Ledger.focusRemainingSec(s))} left"
            bal <= 0 -> "⏳ Out of scroll time · lock in to earn more"
            else -> "⏳ ${Ledger.fmt(bal)} of scroll time left"
        }
    }

    // ------------------------------------------------------------------ events

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || !::s.isInitialized) return
        val pkg = e.packageName?.toString() ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Only real app screens count as switching apps. Popups, menus, toasts and
            // Unrot's own countdown pill also send this event and must be ignored.
            if (pkg !in ignoredPkgs && pkg !in imePkgs && pkg != currentPkg && isActivity(pkg, e.className)) {
                onForeground(pkg)
            }
        }
        if (pkg == currentPkg && pkg in URL_BAR_IDS) {
            val now = System.currentTimeMillis()
            if (now - lastUrlCheck > 600) {
                lastUrlCheck = now
                refreshSite()
            }
        }
        if (pkg in guardPkgs) guard(pkg)
    }

    /** Called whenever a different app comes to the front. */
    private fun onForeground(pkg: String) {
        currentPkg = pkg
        currentSite = null
        if (pkg in URL_BAR_IDS) refreshSite(notify = false)
        onKeyChanged()
    }

    /**
     * What you're "in" right now: a blocked site inside a browser counts as its own app
     * ("site:youtube.com"), everything else is the app's package name.
     */
    private fun currentKey(): String? {
        val pkg = currentPkg ?: return null
        val site = currentSite
        return if (pkg in URL_BAR_IDS && site != null) "site:$site" else pkg
    }

    private fun isBlocked(key: String, st: Settings) = key.startsWith("site:") || key in st.flagged

    private fun onKeyChanged() {
        val key = currentKey() ?: return
        val st = Rules.current(s)
        if (isBlocked(key, st)) {
            val now = System.currentTimeMillis()
            if (now - (lastInApp[key] ?: 0L) > INTRO_SKIP_MS) introPending = key
            if (isScreenUsable()) {
                updatePill(st, key)
                evaluate(key, st)
            }
        } else {
            hidePill()
        }
    }

    /** Reads the browser's address bar and checks it against your blocked sites. */
    private fun refreshSite(notify: Boolean = true) {
        val pkg = currentPkg ?: return
        val st = Rules.current(s)
        val (found, site) = readSite(pkg, st)
        if (!found) return // Address bar hidden (e.g. fullscreen video): keep what we had.
        if (site != currentSite) {
            currentSite = site
            if (notify) onKeyChanged()
        }
    }

    private fun readSite(pkg: String, st: Settings): Pair<Boolean, String?> {
        val id = URL_BAR_IDS[pkg] ?: return false to null
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false to null
        if (root.packageName?.toString() != pkg) return false to null
        val node = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()?.firstOrNull()
            ?: return false to null
        if (node.isFocused) return false to null // You're typing in it, not on a page.
        val raw = node.text?.toString()?.trim()?.lowercase() ?: return true to null
        val host = raw.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
            .removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        return true to st.sites.firstOrNull { Settings.siteMatches(host, it) }
    }

    private val activityCache = HashMap<String, Boolean>()

    private fun isActivity(pkg: String, cls: CharSequence?): Boolean {
        val c = cls?.toString() ?: return false
        val key = "$pkg/$c"
        return activityCache.getOrPut(key) {
            runCatching { packageManager.getActivityInfo(android.content.ComponentName(pkg, c), 0); true }
                .getOrDefault(false)
        }
    }

    private fun isScreenUsable(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        return pm.isInteractive && !km.isKeyguardLocked
    }

    // ------------------------------------------------------------------ the 1-second tick

    private val tick = object : Runnable {
        override fun run() {
            try {
                step()
            } catch (_: Throwable) {
                // Never let one bad tick kill the service.
            }
            h.postDelayed(this, 1000)
        }
    }

    private fun step() {
        ticks++
        val now = System.currentTimeMillis()
        val st = Rules.current(s)
        Ledger.ensureDailyBase(s, st)
        Ledger.rollover(s, st)

        val earned = Ledger.settleFocus(s, st)
        if (earned >= 0) {
            Notif.alert(this, "Lock-in complete 🎉", "+${Ledger.fmtShort(earned)} of scroll time")
            syncSoon()
        }
        trackPhoneDown(st)

        // Re-check which app is really in front every 2s (some apps don't send events on resume).
        if (ticks % 2 == 0L) {
            val activePkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (activePkg != null && activePkg !in ignoredPkgs && activePkg !in imePkgs && activePkg != currentPkg) {
                onForeground(activePkg)
            } else if (activePkg != null && activePkg == currentPkg && activePkg in URL_BAR_IDS) {
                refreshSite()
            }
        }

        val key = currentKey()
        val usingFlagged = key != null && isBlocked(key, st) && isScreenUsable()

        if (usingFlagged) {
            // Games and movies cost less than doomscrolling.
            val half = key!! in st.halfRate
            spendAcc += if (half) st.n("engagingCostPct") / 100.0 else 1.0
            if (half) Ledger.addAppTime(key, 1) else Ledger.addDoom(s, 1, key)
            val whole = spendAcc.toLong()
            if (whole > 0) {
                Ledger.spend(s, whole)
                spendAcc -= whole
            }
            lastInApp[key] = now
            if (now - lastFlaggedAt > 60_000) {
                continuousSec = 0
                nextCheckInAt = if (st.checkInMin > 0) st.checkInMin * 60L else Long.MAX_VALUE
            }
            lastFlaggedAt = now
            continuousSec++
            if (checkInAck) {
                checkInAck = false
                nextCheckInAt = continuousSec + (if (st.checkInMin > 0) st.checkInMin * 60L else Long.MAX_VALUE / 2)
            }
            val bal = Ledger.availableSec(s, st, key)
            if (bal in 1..60 && !warnedLow) {
                warnedLow = true
                Notif.alert(this, "1 minute left", "Wrap it up, or lock in to earn more.")
            }
            if (bal > 120) warnedLow = false
            updatePill(st, key)
            evaluate(key, st)
        } else {
            hidePill()
        }

        if (ticks % 10 == 0L) {
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(Notif.ID_STATUS, Notif.status(this, statusText()))
            }
        }
        if (ticks % 30 == 0L) {
            syncSoon()
            Ledger.flushStats(s)
            weeklyRecapNotice()
        }
        if (ticks % 60 == 30L) background { Calls.sync(this) }
        if (ticks % 900 == 0L) background {
            Calls.sync(this)
            HealthSync.sync(this)
        }
    }

    /**
     * Phone-down bonus: if the screen stayed off (1 min grace) for a whole lock-in of 10+ min,
     * on this phone or the laptop, you get a bonus on top of what it paid.
     */
    private fun trackPhoneDown(st: Settings) {
        val now = System.currentTimeMillis()
        if (Ledger.focusActive(s)) {
            if (s.pdStart == 0L) {
                s.pdStart = now
                s.pdScreenSec = 0
                s.pdLocal = Ledger.localFocusActive(s)
                s.pdFree = if (s.pdLocal) s.focusFree else Ledger.remoteFocusFree(s)
                s.lastFocusPaid = 0
            }
            if (getSystemService(PowerManager::class.java).isInteractive) s.pdScreenSec = s.pdScreenSec + 1
            return
        }
        if (s.pdStart == 0L) return
        val dur = (now - s.pdStart) / 1000
        val pct = st.n("phoneDownPct")
        if (dur >= 10 * 60 && pct > 0) {
            if (s.pdScreenSec <= 60) {
                val base = if (s.pdLocal) s.lastFocusPaid
                else (dur / (if (s.pdFree) st.freeRatio else st.focusRatio)).toLong()
                val bonus = base * pct / 100
                if (bonus > 0) Ledger.credit(s, st, bonus, "phone-down bonus (+$pct%, screen stayed off)")
            } else {
                s.log("No phone-down bonus: screen was on for ${s.pdScreenSec / 60} min during the lock-in")
            }
        }
        s.pdStart = 0
    }

    /** Sunday evening: a nudge to look at your week. */
    private fun weeklyRecapNotice() {
        val now = java.time.LocalDateTime.now()
        if (now.dayOfWeek != java.time.DayOfWeek.SUNDAY || now.hour < 19) return
        val week = now.toLocalDate().toString()
        if (s.recapWeek == week) return
        s.recapWeek = week
        val r = Recap.week(this, s)
        Notif.alert(this, "Your week in Unrot", r.headline)
    }

    private fun syncSoon() {
        if (!Sync.configured(s)) return
        background { Sync.run(s) }
    }

    private fun background(work: () -> Unit) {
        if (bgBusy) return
        bgBusy = true
        Thread {
            try {
                work()
            } catch (_: Throwable) {
            } finally {
                bgBusy = false
            }
        }.start()
    }

    // ------------------------------------------------------------------ deciding to step in

    private fun evaluate(pkg: String, st: Settings) {
        val now = System.currentTimeMillis()
        val mode = when {
            Ledger.focusActive(s) -> BlockActivity.MODE_FOCUS
            Ledger.balanceSec(s) <= 0 -> BlockActivity.MODE_OUT
            pkg !in st.halfRate && Ledger.doomToday(s) >= st.n("dailyCapMin") * 60L -> BlockActivity.MODE_CAP
            st.inBedtime(LocalTime.now().let { it.hour * 60 + it.minute }) &&
                (nightPass[pkg] ?: 0L) < now -> BlockActivity.MODE_NIGHT
            introPending == pkg -> BlockActivity.MODE_INTRO
            continuousSec >= nextCheckInAt -> BlockActivity.MODE_CHECKIN
            else -> null
        } ?: return
        launchBlock(mode, pkg)
    }

    private fun launchBlock(mode: String, pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockLaunch < 2500) return
        lastBlockLaunch = now
        if (mode == BlockActivity.MODE_OUT || mode == BlockActivity.MODE_FOCUS) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        hidePillNow()
        if (mode == BlockActivity.MODE_CHECKIN) {
            // Next check-in only after they answer or after another full interval.
            nextCheckInAt = Long.MAX_VALUE
        }
        val i = Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(BlockActivity.EXTRA_MODE, mode)
            .putExtra(BlockActivity.EXTRA_PKG, pkg)
            .putExtra(BlockActivity.EXTRA_CONTINUOUS, continuousSec)
        runCatching { startActivity(i) }
    }

    // ------------------------------------------------------------------ the countdown pill

    /** A small always-on countdown over blocked apps. Drag it anywhere; it can't be hidden. */
    private fun updatePill(st: Settings, key: String) {
        val bal = Ledger.availableSec(s, st, key)
        val v = pill ?: createPill() ?: return
        val half = key in st.halfRate
        v.text = "⏳ " + Ledger.fmt(maxOf(0L, bal)) + if (half) " · ${st.n("engagingCostPct")}%" else ""
        val (bg, fg) = when {
            bal <= 30 -> 0xEEFF6B6B.toInt() to 0xFF1A0505.toInt()
            bal <= 120 -> 0xEEFFB454.toInt() to 0xFF1F1405.toInt()
            else -> 0xE61A1E26.toInt() to Col.ACCENT
        }
        cancelHide()
        v.setTextColor(fg)
        (v.background as? GradientDrawable)?.setColor(bg)
        v.visibility = View.VISIBLE
    }

    private var hideScheduled = false
    private val hideRunnable = Runnable {
        hideScheduled = false
        pill?.visibility = View.GONE
    }

    /** Hides the pill unless you're back within a moment, so brief blips don't make it flicker. */
    private fun hidePill() {
        // Schedule once; calling this again every second must not keep pushing the hide back.
        if (pill?.visibility == View.VISIBLE && !hideScheduled) {
            hideScheduled = true
            h.postDelayed(hideRunnable, 1500)
        }
    }

    private fun cancelHide() {
        h.removeCallbacks(hideRunnable)
        hideScheduled = false
    }

    private fun hidePillNow() {
        cancelHide()
        pill?.visibility = View.GONE
    }

    private fun createPill(): TextView? = runCatching {
        val wm = getSystemService(WindowManager::class.java)
        val tv = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xE61A1E26.toInt())
                setStroke(dp(1), 0x337CD992)
            }
            elevation = dp(4).toFloat()
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (s.pillX >= 0) s.pillX else resources.displayMetrics.widthPixels - dp(110)
            y = if (s.pillY >= 0) s.pillY else dp(90)
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        tv.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (ev.rawX - downX).toInt()
                    lp.y = startY + (ev.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(view, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    s.pillX = lp.x; s.pillY = lp.y
                }
            }
            true
        }
        wm.addView(tv, lp)
        pill = tv
        pillParams = lp
        tv
    }.getOrNull()

    // ------------------------------------------------------------------ uninstall protection

    private fun guard(pkg: String) {
        val st = Rules.current(s)
        if (!st.protection || Pin.unlocked(s) || !Setup.protectionArmed(this)) return
        val now = System.currentTimeMillis()
        if (now - lastGuard < 400) return
        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>()
        collectText(root, texts, 0)
        val mentionsUs = texts.any { it.trim().equals("Unrot", ignoreCase = true) || it.contains("“Unrot”") || it.contains("\"Unrot\"") }
        if (!mentionsUs) return
        val installer = pkg.contains("packageinstaller")
        val danger = listOf(
            "uninstall", "force stop", "deactivate", "use unrot", "clear data",
            "clear storage", "stop unrot",
        )
        val risky = installer || texts.any { t -> val l = t.lowercase(); danger.any { l.contains(it) } }
        if (!risky) return
        lastGuard = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        h.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 250)
        Toast.makeText(this, "Unrot is protected. Ask for the PIN to change this.", Toast.LENGTH_LONG).show()
        s.log("Blocked an attempt to turn Unrot off")
    }

    private fun collectText(n: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (n == null || depth > 25 || out.size > 400) return
        n.text?.let { out += it.toString() }
        n.contentDescription?.let { out += it.toString() }
        for (i in 0 until n.childCount) collectText(n.getChild(i), out, depth + 1)
    }
}
