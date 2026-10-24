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
            root.add(space(28))
            when (mode) {
                MODE_FOCUS -> {
                    root.add(text("🔒", 44f))
                    root.add(text("You're locked in", 28f, bold = true))
                    val left = text("", 18f, Col.ACCENT)
                    root.add(left, 24)
                    tick(1000) { left.text = "${Ledger.fmt(Ledger.focusRemainingSec(s))} left · $name can wait" ; Ledger.focusActive(s) }
                    root.add(button("Back to it", Ui.Style.PRIMARY) { goHome() })
                }
                MODE_OUT -> {
                    root.add(text("⏳", 44f))
                    root.add(text("You're out of scroll time", 28f, bold = true))
                    root.add(text("$name is locked until you earn more. Every ${Rules.fmtRatio(st.focusRatio)} min locked in earns 1 min.", 16f, Col.MUTED), 24)
                    if (night) nightOptions(root) else dayOptions(root)
                    root.add(space(8))
                    root.add(button("Lock in and earn time", Ui.Style.PRIMARY) {
                        startActivity(MainActivity.lockInIntent(this@BlockActivity)); finish()
                    })
                    root.add(button("Leave", Ui.Style.GHOST) { goHome() })
                }
                MODE_NIGHT -> {
                    root.add(text("🌙", 44f))
                    root.add(text("It's late", 28f, bold = true))
                    root.add(text("Scrolling now just steals from tomorrow. Do one of these instead:", 16f, Col.MUTED), 24)
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
                    var wait = st.bedWaitSec
                    tick(1000) {
                        if (wait > 0) {
                            cont.text = "Continue to $name in ${wait}s"
                            wait--
                            true
                        } else {
                            cont.isEnabled = true
                            cont.text = "Continue to $name for ${UnrotService.NIGHT_PASS_MIN} min"
                            false
                        }
                    }
                }
                MODE_INTRO -> {
                    root.add(text(name, 18f, Col.MUTED, bold = true), 0)
                    root.add(text("You have", 22f), 0)
                    val bigBal = text(Ledger.fmt(maxOf(0L, Ledger.balanceSec(s))), 60f, Col.ACCENT, bold = true)
                    root.add(bigBal, 0)
                    root.add(text("of scroll time. It counts down while you're in.", 15f, Col.MUTED), 22)
                    if (night) nightOptions(root) else dayOptions(root)
                    root.add(space(6))
                    root.add(button("🔒 Lock in instead", Ui.Style.SECONDARY) {
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
                    root.add(text("👀", 44f))
                    root.add(text("${continuous / 60} minutes on $name", 28f, bold = true))
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
                root.add(text("Balance: ${if (bal > 0) Ledger.fmt(bal) else "0:00"}", 14f, Col.MUTED))
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
        root.add(text("Call someone instead", 18f, bold = true))
        val ordered = fam.map { it to Calls.lastCallWith(this@BlockActivity, it) }
            .sortedBy { it.second ?: 0L }
        for ((c, last) in ordered.take(3)) {
            val card = card()
            val r = row()
            val info = col()
            info.add(text(c.name, 18f, bold = true), 2)
            info.add(text(lastCalledText(last), 14f, Col.MUTED), 0)
            r.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(button(if (c.via == "phone") "📞 Call" else "📞 ${c.app.label}", Ui.Style.PRIMARY) {
                if (Calls.start(this@BlockActivity, c)) {
                    s.log("Called ${c.name} instead of scrolling")
                    finish()
                } else {
                    android.widget.Toast.makeText(this@BlockActivity, "${c.app.label} isn't installed.", android.widget.Toast.LENGTH_LONG).show()
                }
            })
            card.add(r, 0)
            root.add(card)
        }
        val st = Rules.current(s)
        root.add(text("A ${st.n("callMinMin")}+ min call with family (phone or app) earns ${st.n("callRewardMin")} min.", 13f, Col.MUTED))
    }

    /** Night fork: async kindness and getting thoughts out of your head. */
    private fun Ui.nightOptions(root: LinearLayout) {
        val fam = s.family
        if (fam.isNotEmpty()) {
            val c = fam.map { it to Calls.lastCallWith(this@BlockActivity, it) }
                .minByOrNull { it.second ?: 0L }!!.first
            root.add(button("🎙️ Leave ${c.name} a voice note for the morning", Ui.Style.SECONDARY) {
                s.log("Sent ${c.name} a voice note instead of scrolling")
                Calls.voiceNote(this@BlockActivity, c)
                finish()
            })
        }
        root.add(button("🧠 Brain dump: what's keeping you up?", Ui.Style.SECONDARY) {
            startActivity(Intent(this@BlockActivity, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_TYPE, NoteActivity.TYPE_DUMP))
            finish()
        })
        root.add(button("📝 One thing for tomorrow", Ui.Style.SECONDARY) {
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
