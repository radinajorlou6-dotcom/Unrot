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
        /** How fast your balance is going down right now on this phone (shared with the laptop). */
        @Volatile var currentRate = 0.0

        @Volatile var running = false
            private set

        @Volatile private var checkInAck = false

        /** Bedtime "continue anyway": [NIGHT_PASS_MIN] minutes for every blocked app, saved so a restart can't lose it. */
        fun grantNightPass(ctx: android.content.Context) {
            Store.get(ctx).nightPassUntil = System.currentTimeMillis() + NIGHT_PASS_MIN * 60_000L
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
        /** [readSiteFrom]'s answer for youtube.com/shorts. */
        const val SHORTS_MARK = "\u0000shorts"
        /** Views that only exist while the YouTube app's Shorts player is open. */
        val SHORTS_VIEW_IDS = listOf("reel_watch_player", "reel_player_page_container", "reel_recycler", "reel_watch_fragment_root")

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
    /** Last site each browser was on (null = not a blocked site). Survives fullscreen and picture-in-picture. */
    private val siteMemory = HashMap<String, String?>()
    private var lastPipDismiss = 0L
    private var lastSyncedRate = 0.0
    private var lastUrlCheck = 0L
    private var spendAcc = 0.0
    private var pillParams: WindowManager.LayoutParams? = null

    private val ignoredPkgs = setOf("com.android.systemui", "android")
    /** Home screen apps: being on the home screen during a lock-in isn't "doing" anything. */
    private var launcherPkgs: Set<String> = emptySet()
    /** The last website each browser showed (any site, not just blocked ones), for lock-in activity. */
    private val hostMemory = HashMap<String, String>()
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
        launcherPkgs = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        Notif.ensureChannels(this)
        goForeground()
        s.log("Unrot is running")
        // If Android restarted the blocker mid-show, remember you were already in it (no opening card again).
        if (s.lastBlockedKey.isNotEmpty() && s.lastBlockedSeen > 0) lastInApp[s.lastBlockedKey] = s.lastBlockedSeen
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
            Ledger.focusActive(s) -> Ledger.session(s)!!.let {
                when {
                    it.paused -> "⏸ Lock-in paused · apps stay blocked"
                    it.free -> "🔒 Free lock-in · ${Ledger.fmt(it.elapsedSec())} so far"
                    else -> "🔒 Locked in · ${Ledger.fmt(it.remainingSec())} left"
                }
            }
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
        if (pkg == Settings.YOUTUBE_PKG && pkg == currentPkg) refreshShorts()
        if (pkg in guardPkgs) guard(pkg)
    }

    /** Called whenever a different app comes to the front. */
    private fun onForeground(pkg: String) {
        currentPkg = pkg
        inShorts = pkg == Settings.YOUTUBE_PKG && showsShorts(runCatching { rootInActiveWindow }.getOrNull())
        // Fullscreen video and picture-in-picture hide the address bar, so start from the last site
        // this browser was on; reading the address bar (when visible) corrects it.
        currentSite = siteMemory[pkg]
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
        if (pkg == Settings.YOUTUBE_PKG && inShorts && Settings.SHORTS_KEY in Rules.current(s).flagged) return Settings.SHORTS_KEY
        return if (pkg in URL_BAR_IDS && site != null) siteKey(site) else pkg
    }

    private val labelCache = HashMap<String, String>()

    /** What you're doing right now, for lock-in activity: an app's name, or the website in a browser. */
    private fun focusKey(): String? {
        val pkg = currentPkg ?: return null
        if (pkg == packageName || pkg in ignoredPkgs || pkg in imePkgs || pkg in launcherPkgs) return null
        if (pkg in URL_BAR_IDS) hostMemory[pkg]?.let { return it }
        return labelCache.getOrPut(pkg) { Recap.label(this, pkg) }
    }

    /** A site from [readSiteFrom] as a key ("site:host", or YouTube Shorts). */
    private fun siteKey(site: String) = if (site == SHORTS_MARK) Settings.SHORTS_KEY else "site:$site"

    /** Are you watching Shorts in the YouTube app right now (its Shorts player is on screen)? */
    private var inShorts = false
    private var lastShortsCheck = 0L

    private fun showsShorts(root: AccessibilityNodeInfo?): Boolean {
        if (root == null || root.packageName?.toString() != Settings.YOUTUBE_PKG) return false
        return SHORTS_VIEW_IDS.any { id ->
            runCatching { root.findAccessibilityNodeInfosByViewId("${Settings.YOUTUBE_PKG}:id/$id") }.getOrNull()
                ?.isNotEmpty() == true
        }
    }

    private fun refreshShorts() {
        val now = System.currentTimeMillis()
        if (now - lastShortsCheck < 600) return
        lastShortsCheck = now
        val on = showsShorts(runCatching { rootInActiveWindow }.getOrNull())
        if (on != inShorts) {
            inShorts = on
            onKeyChanged()
        }
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
        siteMemory[pkg] = site
        if (site != currentSite) {
            currentSite = site
            if (notify) onKeyChanged()
        }
    }

    private fun readSite(pkg: String, st: Settings): Pair<Boolean, String?> {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false to null
        if (root.packageName?.toString() != pkg) return false to null
        return readSiteFrom(root, pkg, st)
    }

    /** Reads a browser's address bar inside a given window. Returns (could read it, blocked site or null). */
    private fun readSiteFrom(root: android.view.accessibility.AccessibilityNodeInfo, pkg: String, st: Settings): Pair<Boolean, String?> {
        val id = URL_BAR_IDS[pkg] ?: return false to null
        val node = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()?.firstOrNull()
            ?: return false to null
        if (node.isFocused) return false to null // You're typing in it, not on a page.
        val raw = node.text?.toString()?.trim()?.lowercase() ?: return true to null
        val host = raw.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
            .removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        if (host.contains('.') && !host.contains(' ')) hostMemory[pkg] = host
        val path = raw.substringAfter("://").substringAfter('/', "")
        if (Settings.siteMatches(host, "youtube.com") && path.startsWith("shorts") && Settings.SHORTS_KEY in st.flagged)
            return true to SHORTS_MARK
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
        if (earned == Ledger.NEEDS_REVIEW) {
            Notif.alert(
                this, "Free lock-in hit ${Ledger.FREE_MAX_MS / 3_600_000} hours",
                "Forgot it was on? Open Unrot and say how long you really worked to get paid.",
            )
            syncSoon()
        } else if (earned >= 0) {
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
            } else if (activePkg == Settings.YOUTUBE_PKG && activePkg == currentPkg) {
                refreshShorts()
            }
        }

        // Everything blocked that's actually visible: the app you're in, the other half of a split screen,
        // and picture-in-picture. Doomscrolling costs 1x however many windows; games/movies add the engaging rate.
        val usable = isScreenUsable()
        val primary = currentKey()?.takeIf { usable && isBlocked(it, st) }
        val others = if (usable) otherVisibleBlocked(st, primary) else emptyList()
        val visible = listOfNotNull(primary) + others.map { it.key }
        val pct = st.n("engagingCostPct") / 100.0

        // During a lock-in (here or on the laptop), note what you're actually doing, for "What you locked in on".
        // Screen off or the home screen = nothing noted (it shows up as "undescribed").
        if (usable && Ledger.session(s)?.paused == false) focusKey()?.let { Ledger.addFocusTime(it, 1) }

        if (visible.isNotEmpty()) {
            val doomKey = visible.firstOrNull { it !in st.halfRate }
            val halfKey = visible.firstOrNull { it in st.halfRate }
            currentRate = (if (doomKey != null) 1.0 else 0.0) + (if (halfKey != null) pct else 0.0)
            spendAcc += currentRate
            if (doomKey != null) Ledger.addDoom(s, 1, doomKey)
            if (halfKey != null) Ledger.addAppTime(halfKey, 1)
            val whole = spendAcc.toLong()
            if (whole > 0) {
                Ledger.spend(s, whole)
                spendAcc -= whole
            }
            visible.forEach { lastInApp[it] = now }
            if (ticks % 3 == 0L) {
                s.lastBlockedSeen = now
                s.lastBlockedKey = primary ?: visible.first()
            }
            val shown = primary ?: visible.first()
            if (primary != null) {
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
            }
            val bal = Ledger.availableSec(s, st, shown)
            if (bal in 1..60 && !warnedLow) {
                warnedLow = true
                Notif.alert(this, "1 minute left", "Wrap it up, or lock in to earn more.")
            }
            if (bal > 120) warnedLow = false
            updatePill(st, shown)
            if (primary != null) evaluate(primary, st)
            others.forEach { evaluateOther(it, st) }
        } else {
            currentRate = 0.0
            hidePill()
        }
        // Let the laptop know quickly when what you're doing here changes (for the shared 1.5x cap).
        if (currentRate != lastSyncedRate) {
            lastSyncedRate = currentRate
            syncSoon()
        }

        if (ticks % 10 == 0L) {
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(Notif.ID_STATUS, Notif.status(this, statusText()))
            }
        }
        // Every 10s during a lock-in (so pause/finish from the laptop lands fast), else every 30s.
        if (ticks % 30 == 0L || (ticks % 10 == 0L && Ledger.focusActive(s))) {
            syncSoon()
            Ledger.flushStats(s)
            weeklyRecapNotice()
        }
        if (ticks % 60 == 30L) background { Calls.sync(this) }
        // Your installed apps, so the laptop can pick blocked apps too (sent only when it changes).
        if (ticks % 21600 == 5L) background { s.phoneAppsJson = installedApps() }
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
            if (s.pdScreenSec <= 60 && s.pdLocal && s.pdFree && s.freeReviewSec > 0) {
                // A free lock-in that hit the max isn't paid yet: the bonus goes on whatever you keep.
                s.freeReviewBonus = true
            } else if (s.pdScreenSec <= 60) {
                val base = if (s.pdLocal) s.lastFocusPaid
                else (dur / (if (s.pdFree) st.freeRatio else st.focusRatio)).toLong()
                val bonus = base * pct / 100
                if (bonus > 0) {
                    Ledger.credit(s, st, bonus, "phone-down bonus (+$pct%, screen stayed off)")
                    s.lifePhoneDown = s.lifePhoneDown + 1
                }
            } else {
                s.log("No phone-down bonus: screen was on for ${s.pdScreenSec / 60} min during the lock-in")
            }
        }
        s.pdStart = 0
    }

    /** Sunday evening: a nudge to look at your week. */
    /** {"package": {"label": "TikTok", "game": false}, …} for every app with an icon, plus YouTube Shorts. */
    private fun installedApps(): String {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val o = org.json.JSONObject()
        pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo to it.loadLabel(pm).toString() }
            .filter { it.first.packageName != packageName }
            .sortedBy { it.first.packageName }
            .forEach { (ai, label) ->
                o.put(ai.packageName.replace('.', ','), org.json.JSONObject()
                    .put("label", label)
                    .put("game", ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME))
            }
        o.put(Settings.SHORTS_KEY.replace('.', ','), org.json.JSONObject().put("label", Settings.SHORTS_LABEL).put("game", false))
        return o.toString()
    }

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
                s.nightPassUntil < now -> BlockActivity.MODE_NIGHT
            introPending == pkg -> BlockActivity.MODE_INTRO
            continuousSec >= nextCheckInAt && pkg !in st.halfRate -> BlockActivity.MODE_CHECKIN
            else -> null
        } ?: return
        launchBlock(mode, pkg)
    }

    data class Other(val key: String, val bounds: android.graphics.Rect, val pip: Boolean)

    /**
     * Blocked apps/sites visible outside the window you're using: the other half of a split screen,
     * or a picture-in-picture video.
     */
    private fun otherVisibleBlocked(st: Settings, primary: String?): List<Other> {
        val ws = runCatching { windows }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Other>()
        for (w in ws) {
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (w.isActive && !w.isInPictureInPictureMode) continue // the window you're using: handled as "primary"
            val root = runCatching { w.root }.getOrNull() ?: continue
            val p = root.packageName?.toString() ?: continue
            if (p == packageName) continue
            val key = if (p in URL_BAR_IDS) {
                val (found, site) = readSiteFrom(root, p, st)
                if (found) siteMemory[p] = site
                (if (found) site else siteMemory[p])?.let { siteKey(it) } ?: continue
            } else if (p == Settings.YOUTUBE_PKG && Settings.SHORTS_KEY in st.flagged && showsShorts(root)) {
                Settings.SHORTS_KEY
            } else p
            if (key == primary || !isBlocked(key, st) || out.any { it.key == key }) continue
            val r = android.graphics.Rect()
            w.getBoundsInScreen(r)
            out += Other(key, r, w.isInPictureInPictureMode)
        }
        return out
    }

    /**
     * Split screen and picture-in-picture only get the hard stops (out of time, locked in, daily limit),
     * not the nudges. Picture-in-picture is swiped away; split screen is closed by going home.
     */
    private fun evaluateOther(o: Other, st: Settings) {
        val mode = when {
            Ledger.focusActive(s) -> BlockActivity.MODE_FOCUS
            Ledger.balanceSec(s) <= 0 -> BlockActivity.MODE_OUT
            o.key !in st.halfRate && Ledger.doomToday(s) >= st.n("dailyCapMin") * 60L -> BlockActivity.MODE_CAP
            else -> null
        } ?: return
        val now = System.currentTimeMillis()
        if (now - lastPipDismiss < 4000) return
        lastPipDismiss = now
        val what = if (o.key.startsWith("site:") || o.key == Settings.SHORTS_KEY) Settings.labelOf(this, o.key) else "app"
        if (o.pip) {
            runCatching {
                val dm = resources.displayMetrics
                val path = android.graphics.Path().apply {
                    moveTo(o.bounds.exactCenterX(), o.bounds.exactCenterY())
                    lineTo(dm.widthPixels / 2f, dm.heightPixels - dp(48).toFloat())
                }
                val g = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 650))
                    .build()
                dispatchGesture(g, null, null)
            }
            s.log("Closed a picture-in-picture $what")
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
            s.log("Closed a split-screen $what")
        }
        h.postDelayed({ launchBlock(mode, o.key) }, 900)
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
            bal <= 30 -> 0xF0FF5C7A.toInt() to 0xFF2A0610.toInt()
            bal <= 120 -> 0xF0FFB454.toInt() to 0xFF2A1A05.toInt()
            else -> 0xEB1B1836.toInt() to Col.GOLD
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
            typeface = Fonts.display(this@UnrotService)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xEB1B1836.toInt())
                setStroke(dp(1), 0x668B5CF6)
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
