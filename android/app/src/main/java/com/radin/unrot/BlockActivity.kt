package com.radin.unrot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalTime

/**
 * The fork in the road. Instead of a wall, it puts something better right in front of you:
 * a call during the day, a voice note or brain dump at night, or a lock-in to earn time.
 */
class BlockActivity : Activity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_CONTINUOUS = "continuous"
        const val MODE_OUT = "out"
        const val MODE_FOCUS = "focus"
        const val MODE_NIGHT = "night"
        const val MODE_CHECKIN = "checkin"
        const val MODE_INTRO = "intro"
        const val MODE_CAP = "cap"
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var s: Store
    private var mode = MODE_OUT
    private var pkg = ""
    private var ticker: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Store.get(this)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    override fun onDestroy() {
        ticker?.let { h.removeCallbacks(it) }
        super.onDestroy()
    }

    @Deprecated("Back always means 'leave the app', never 'back into it'")
    override fun onBackPressed() {
        goHome()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun appName(p: String): String = if (p.startsWith("site:")) p.removePrefix("site:") else runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
    }.getOrDefault("that app")

    private fun render(i: Intent) {
        ticker?.let { h.removeCallbacks(it) }
        mode = i.getStringExtra(EXTRA_MODE) ?: MODE_OUT
        pkg = i.getStringExtra(EXTRA_PKG) ?: ""
        val continuous = i.getLongExtra(EXTRA_CONTINUOUS, 0)
        val st = Rules.current(s)
        val name = appName(pkg)
        val minuteNow = LocalTime.now().let { it.hour * 60 + it.minute }
        val night = st.inBedtime(minuteNow)

        setScreen { root ->
            root.add(space(20))
            when (mode) {
                MODE_FOCUS -> {
                    root.addWrap(heroIcon(R.drawable.ic_lock, Col.VIOLET), 14)
                    root.add(title("You're locked in", 30f), 8)
                    val left = text("", 18f, Col.VIOLET, bold = true)
                    root.add(left, 24)
                    tick(1000) { left.text = "${Ledger.fmt(Ledger.focusRemainingSec(s))} left · $name can wait" ; Ledger.focusActive(s) }
                    root.add(button("Back to it", Ui.Style.PRIMARY) { goHome() })
                }
                MODE_OUT -> {
                    root.addWrap(heroIcon(R.drawable.ic_hourglass_top, Col.GOLD), 14)
                    root.add(title("Out of scroll time", 30f), 8)
                    root.add(text("$name is locked until you earn more. Every ${Rules.fmtRatio(st.focusRatio)} min locked in earns 1 min.", 16f, Col.MUTED), 24)
                    if (night) nightOptions(root) else dayOptions(root)
                    root.add(space(8))
                    root.add(button("Lock in and earn time", Ui.Style.PRIMARY, R.drawable.ic_lock) {
                        startActivity(MainActivity.lockInIntent(this@BlockActivity)); finish()
                    })
                    root.add(button("Leave", Ui.Style.GHOST) { goHome() })
                }
                MODE_NIGHT -> {
                    // Your 15 minutes just ran out while you were still in it: a quick "still up?", not the full wait.
                    // (Only if you were still in it when it ran out; leaving and coming back gets the full wait.)
                    val lastPass = UnrotService.nightPass[pkg] ?: 0L
                    val stayed = (UnrotService.lastInApp[pkg] ?: 0L) >= lastPass - 5_000L
                    val renewing = lastPass > 0 && stayed && System.currentTimeMillis() - lastPass < 2 * 60_000L
                    root.addWrap(heroIcon(R.drawable.ic_bedtime, Col.INDIGO), 14)
                    root.add(text(if (renewing) "Still up?" else "It's late", 28f, bold = true))
                    root.add(
                        text(
                            if (renewing) "That's another ${UnrotService.NIGHT_PASS_MIN} min on $name past bedtime. Good place to stop:"
                            else "Scrolling now just steals from tomorrow. Do one of these instead:",
                            16f, Col.MUTED,
                        ), 24,
                    )
                    nightOptions(root)
                    root.add(space(8))
                    root.add(button("Go to sleep", Ui.Style.PRIMARY) { goHome() })
                    val cont = button("", Ui.Style.GHOST) {
                        UnrotService.grantNightPass(pkg)
                        UnrotService.introDone(pkg)
                        s.log("Kept scrolling $name past bedtime")
                        finish()
                    }
                    cont.isEnabled = false
                    root.add(cont)
                    var wait = if (renewing) 0 else st.bedWaitSec
                    tick(1000) {
                        if (wait > 0) {
                            cont.text = "Continue to $name in ${wait}s"
                            wait--
                            true
                        } else {
                            cont.isEnabled = true
                            cont.text = if (renewing) "Keep going, ${UnrotService.NIGHT_PASS_MIN} more min"
                            else "Continue to $name for ${UnrotService.NIGHT_PASS_MIN} min"
                            false
                        }
                    }
                }
                MODE_CAP -> {
                    root.addWrap(heroIcon(R.drawable.ic_mobile_off, Col.DANGER), 14)
                    root.add(title("That's ${st.n("dailyCapMin") / 60}h ${st.n("dailyCapMin") % 60}m of doomscrolling today".replace(" 0m", ""), 28f), 8)
                    root.add(
                        text(
                            "$name and your other scroll apps are done until midnight, whatever your balance. " +
                                "Games and movies still work.",
                            16f, Col.MUTED,
                        ), 24,
                    )
                    if (night) nightOptions(root) else dayOptions(root)
                    root.add(space(8))
                    root.add(button("Leave", Ui.Style.PRIMARY) { goHome() })
                }
                MODE_INTRO -> {
                    val avail = maxOf(0L, Ledger.availableSec(s, st, pkg))
                    root.addWrap(chip(name, Col.MUTED), 14, android.view.Gravity.CENTER_HORIZONTAL)
                    val frame = android.widget.FrameLayout(this@BlockActivity)
                    frame.addView(RingView(this@BlockActivity).apply {
                        fraction = (avail / (st.maxBankMin * 60f)).coerceIn(0.02f, 1f)
                        color = if (avail <= 120) Col.WARN else Col.GOLD
                        strokeDp = 14
                    }, android.widget.FrameLayout.LayoutParams(dp(200), dp(200), android.view.Gravity.CENTER))
                    val center = col().apply { gravity = android.view.Gravity.CENTER }
                    center.addView(text("You have", 14f, Col.MUTED).apply { gravity = android.view.Gravity.CENTER })
                    center.addView(title(Ledger.fmt(avail), 44f, if (avail <= 120) Col.WARN else Col.GOLD).apply { gravity = android.view.Gravity.CENTER })
                    frame.addView(center, android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
                    root.addWrap(frame, 12, android.view.Gravity.CENTER_HORIZONTAL)
                    root.add(text("It counts down while you're in. Or do something better:", 15f, Col.MUTED).apply { gravity = android.view.Gravity.CENTER }, 18)
                    if (night) nightOptions(root) else dayOptions(root)
                    root.add(space(6))
                    root.add(button("Lock in instead", Ui.Style.SECONDARY, R.drawable.ic_lock) {
                        startActivity(MainActivity.lockInIntent(this@BlockActivity)); finish()
                    })
                    root.add(button("Leave", Ui.Style.GHOST) { goHome() })
                    val cont = button("", Ui.Style.PRIMARY) {
                        UnrotService.introDone(pkg)
                        finish()
                    }
                    cont.isEnabled = false
                    cont.alpha = 0.5f
                    root.add(cont)
                    var wait = st.n("introWaitSec")
                    tick(1000) {
                        if (wait > 0) {
                            cont.text = "Continue to $name in ${wait}s"
                            wait--
                            true
                        } else {
                            cont.isEnabled = true
                            cont.alpha = 1f
                            cont.text = "Continue to $name"
                            false
                        }
                    }
                }
                MODE_CHECKIN -> {
                    root.addWrap(heroIcon(R.drawable.ic_timer, Col.FIRE), 14)
                    root.add(title("${continuous / 60} minutes on $name", 30f), 8)
                    root.add(text("Still glad you're here?", 18f, Col.MUTED), 24)
                    root.add(button("I'm done", Ui.Style.PRIMARY) { goHome() })
                    if (night) nightOptions(root) else dayOptions(root)
                    val keep = button("", Ui.Style.GHOST) {
                        UnrotService.ackCheckIn()
                        finish()
                    }
                    keep.isEnabled = false
                    root.add(keep)
                    var wait = 10
                    tick(1000) {
                        if (wait > 0) {
                            keep.text = "Keep going in ${wait}s"
                            wait--
                            true
                        } else {
                            keep.isEnabled = true
                            keep.text = "Keep going"
                            false
                        }
                    }
                }
            }
            if (mode != MODE_INTRO) {
                root.add(space(12))
                val bal = Ledger.balanceSec(s)
                root.addWrap(chip("Balance ${if (bal > 0) Ledger.fmt(bal) else "0:00"}", Col.GOLD), 0, android.view.Gravity.CENTER_HORIZONTAL)
            }
        }
    }

    /** Daytime fork: call someone. */
    private fun Ui.dayOptions(root: LinearLayout) {
        val fam = s.family
        if (fam.isEmpty()) {
            root.add(text("Add family in Unrot and they'll show up here, one tap to call.", 15f, Col.MUTED))
            return
        }
        root.add(text("Call someone instead", 17f, bold = true), 10)
        val ordered = fam.map { it to Calls.lastCallWith(this@BlockActivity, it) }
            .sortedBy { it.second ?: 0L }
        for ((c, last) in ordered.take(3)) {
            val card = card()
            val callBtn = button(if (c.via == "phone") "Call" else c.app.label, Ui.Style.SUCCESS, R.drawable.ic_call) {
                if (Calls.start(this@BlockActivity, c)) {
                    s.log("Called ${c.name} instead of scrolling")
                    finish()
                } else {
                    android.widget.Toast.makeText(this@BlockActivity, "${c.app.label} isn't installed.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            card.add(listRow(R.drawable.ic_favorite, Col.ACCENT, c.name, lastCalledText(last), callBtn), 0)
            root.add(card)
        }
        val st = Rules.current(s)
        root.add(text("A ${st.n("callMinMin")}+ min call with family earns +${st.n("callRewardMin")} min.", 13f, Col.ACCENT))
    }

    /** Night fork: async kindness and getting thoughts out of your head. */
    private fun Ui.nightOptions(root: LinearLayout) {
        val fam = s.family
        if (fam.isNotEmpty()) {
            val c = fam.map { it to Calls.lastCallWith(this@BlockActivity, it) }
                .minByOrNull { it.second ?: 0L }!!.first
            root.add(button("Voice note for ${c.name} in the morning", Ui.Style.SECONDARY, R.drawable.ic_mic) {
                s.log("Sent ${c.name} a voice note instead of scrolling")
                Calls.voiceNote(this@BlockActivity, c)
                finish()
            })
        }
        root.add(button("Brain dump: what's keeping you up?", Ui.Style.SECONDARY, R.drawable.ic_psychology) {
            startActivity(Intent(this@BlockActivity, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_TYPE, NoteActivity.TYPE_DUMP))
            finish()
        })
        root.add(button("One thing for tomorrow", Ui.Style.SECONDARY, R.drawable.ic_edit_note) {
            startActivity(Intent(this@BlockActivity, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_TYPE, NoteActivity.TYPE_TOMORROW))
            finish()
        })
    }

    private fun lastCalledText(ts: Long?): String {
        if (ts == null) return "No recent calls"
        val days = (System.currentTimeMillis() - ts) / 86_400_000L
        return when (days) {
            0L -> "Talked today"
            1L -> "Last called yesterday"
            else -> "Last called $days days ago"
        }
    }

    /** Runs [step] every [ms] while it returns true. */
    private fun tick(ms: Long, step: () -> Boolean) {
        val r = object : Runnable {
            override fun run() {
                if (step()) h.postDelayed(this, ms)
            }
        }
        ticker = r
        r.run()
    }
}
