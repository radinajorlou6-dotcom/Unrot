package com.radin.unrot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle

/** Receives a FitNotes export (shared from FitNotes, or picked in Unrot) and pays out new PRs. */
class ImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = fileFrom(intent)
        setScreen { root ->
            root.add(text("🏋️ Checking FitNotes…", 26f, bold = true))
            root.add(text("Looking for new PRs and matching them to your watch workouts.", 16f, Col.MUTED))
        }
        if (uri == null) {
            show(FitNotes.Result(0, emptyList(), emptyList(), emptyList(), 0, "No file came through. Try exporting again."))
            return
        }
        Thread {
            val r = runCatching { FitNotes.import(this, uri) }.getOrElse {
                FitNotes.Result(0, emptyList(), emptyList(), emptyList(), 0, "Something went wrong reading the file: ${it.message}")
            }
            runOnUiThread { if (!isFinishing) show(r) }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun fileFrom(i: Intent): Uri? = when (i.action) {
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> i.data
    }

    private fun show(r: FitNotes.Result) {
        val s = Store.get(this)
        setScreen { root ->
            root.add(space(12))
            when {
                r.error != null -> {
                    root.add(text("Couldn't read that", 26f, bold = true))
                    root.add(text(r.error, 16f, Col.MUTED), 20)
                }
                r.paid.isEmpty() && r.unverified.isEmpty() -> {
                    root.add(text("No new PRs", 26f, bold = true))
                    root.add(text("Read ${r.sets} sets. Nothing in the last 3 days beat your previous best, or it's already been paid. Next time 💪", 16f, Col.MUTED), 20)
                }
                else -> {
                    if (r.paid.isNotEmpty()) {
                        root.add(text("+${Ledger.fmtShort(r.earnedSec)} 🎉", 34f, Col.ACCENT, bold = true))
                        val c = card()
                        r.paid.forEach { c.add(text("🏆 ${it.exercise}: ${it.desc}", 16f), 6) }
                        root.add(c, 14)
                    }
                    if (r.unverified.isNotEmpty()) {
                        root.add(text("Not counted yet", 18f, Col.WARN, bold = true))
                        root.add(
                            text(
                                "These PRs don't have a watch workout on the same day, so they can't be verified. " +
                                    "If you wore your watch, wait for Samsung Health to sync, then share the export again.",
                                14f, Col.MUTED,
                            ), 8,
                        )
                        val c = card()
                        r.unverified.forEach { c.add(text("${it.exercise}: ${it.desc} (${it.day})", 15f), 4) }
                        root.add(c, 14)
                    }
                }
            }
            val bal = Ledger.balanceSec(s)
            root.add(text("Balance: ${if (bal > 0) Ledger.fmt(bal) else "0:00"}", 15f, Col.MUTED), 16)
            root.add(button("Done", Ui.Style.PRIMARY) {
                startActivity(Intent(this@ImportActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            })
        }
    }
}
