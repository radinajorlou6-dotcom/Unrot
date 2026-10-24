package com.radin.unrot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout

/** "Lock in": blocked apps (and laptop games, if synced) are off-limits until the timer ends. */
class FocusActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var s: Store
    private var loop: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Store.get(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        loop?.let { h.removeCallbacks(it) }
        super.onPause()
    }

    private fun render() {
        loop?.let { h.removeCallbacks(it) }
        val st = Rules.current(s)
        setScreen { root ->
            root.add(space(24))
            if (Ledger.focusActive(s)) {
                root.add(text("🔒 Locked in", 28f, bold = true))
                val clock = text("", 56f, Col.ACCENT, bold = true)
                root.add(clock, 4)
                val remote = !Ledger.localFocusActive(s)
                root.add(
                    text(
                        if (remote) "Started on your laptop. Your blocked apps are off-limits until it ends."
                        else "Finish to earn ${Ledger.fmtShort((s.focusPlannedSec / st.focusRatio).toLong())}. Your blocked apps (and laptop games) are off-limits until then.",
                        16f, Col.MUTED,
                    ), 28,
                )
                root.add(button("Done for now, close this", Ui.Style.SECONDARY) { finish() })
                if (!remote) {
                    root.add(button("End early (earn nothing)", Ui.Style.DANGER) {
                        dialog()
                            .setTitle("End early?")
                            .setMessage("You'll lose the time you would have earned from this lock-in.")
                            .setPositiveButton("End it") { _, _ ->
                                Ledger.cancelFocus(s)
                                syncNow()
                                render()
                            }
                            .setNegativeButton("Keep going", null)
                            .show()
                    })
                }
                val r = object : Runnable {
                    override fun run() {
                        if (!Ledger.focusActive(s)) {
                            render(); return
                        }
                        clock.text = Ledger.fmt(Ledger.focusRemainingSec(s))
                        h.postDelayed(this, 1000)
                    }
                }
                loop = r
                r.run()
            } else {
                root.add(text("Lock in", 30f, bold = true))
                root.add(
                    text(
                        "Study, work, clean, anything real. Every ${Rules.fmtRatio(st.focusRatio)} min earns 1 min of scroll time, paid when the timer finishes.",
                        16f, Col.MUTED,
                    ), 24,
                )
                for (pair in listOf(listOf(25, 45), listOf(60, 90))) {
                    val r = row()
                    for (m in pair) {
                        r.addWeighted(button("$m min\n+${Ledger.fmtShort((m * 60 / st.focusRatio).toLong())}", Ui.Style.SECONDARY) {
                            start(m)
                        })
                    }
                    root.add(r)
                }
                root.add(space(8))
                root.add(button("Back", Ui.Style.GHOST) { finish() })
            }
        }
    }

    private fun start(minutes: Int) {
        Ledger.startFocus(s, minutes)
        syncNow()
        render()
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun syncNow() {
        if (Sync.configured(s)) Thread { Sync.run(s) }.start()
    }
}
