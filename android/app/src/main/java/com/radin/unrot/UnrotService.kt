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
import android.widget.Toast
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

        const val NIGHT_PASS_MIN = 15
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
            if (pkg !in ignoredPkgs && pkg !in imePkgs && pkg != currentPkg) {
                currentPkg = pkg
                val st = Rules.current(s)
                if (pkg in st.flagged && isScreenUsable()) evaluate(pkg, st)
            }
        }
        if (pkg in guardPkgs) guard(pkg)
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

        val earned = Ledger.settleFocus(s, st)
        if (earned >= 0) {
            Notif.alert(this, "Lock-in complete 🎉", "+${Ledger.fmtShort(earned)} of scroll time")
            syncSoon()
        }

        // Re-check which app is really in front every 2s (some apps don't send events on resume).
        if (ticks % 2 == 0L) {
            val activePkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (activePkg != null && activePkg !in ignoredPkgs && activePkg !in imePkgs) currentPkg = activePkg
        }

        val pkg = currentPkg
        val usingFlagged = pkg != null && pkg in st.flagged && isScreenUsable()

        if (usingFlagged) {
            Ledger.spend(s, 1)
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
            val bal = Ledger.balanceSec(s)
            if (bal in 1..60 && !warnedLow) {
                warnedLow = true
                Notif.alert(this, "1 minute of scroll time left", "Wrap it up, or lock in to earn more.")
            }
            if (bal > 120) warnedLow = false
            evaluate(pkg!!, st)
        }

        if (ticks % 10 == 0L) {
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(Notif.ID_STATUS, Notif.status(this, statusText()))
            }
        }
        if (ticks % 30 == 0L) syncSoon()
        if (ticks % 900 == 0L) background {
            Calls.sync(this)
            HealthSync.sync(this)
        }
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
            st.inBedtime(LocalTime.now().let { it.hour * 60 + it.minute }) &&
                (nightPass[pkg] ?: 0L) < now -> BlockActivity.MODE_NIGHT
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
