package com.radin.unrot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.Toast

/** Choose which apps cost scroll time. Adding is instant; removing waits 24h. */
class AppPickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Store.get(this)
        val current = Rules.current(s).flagged
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedWith(compareBy({ it.first !in current }, { it.second.lowercase() }))
        val chosen = current.toMutableSet()

        setScreen { root ->
            root.add(text("Blocked apps", 28f, bold = true))
            root.add(
                text(
                    "Time in these apps comes out of your balance. Blocking an app is instant; unblocking one takes 24 hours (or the PIN).",
                    15f, Col.MUTED,
                ), 16,
            )
            root.add(button("Save", Ui.Style.PRIMARY) {
                val r = Rules.propose(s, Rules.current(s).copy(flagged = chosen.toSet())) { p ->
                    apps.firstOrNull { it.first == p }?.second ?: p
                }
                val msg = if (r.delayed > 0) "Saved. ${r.delayed} unblock(s) will apply in 24h." else "Saved."
                Toast.makeText(this@AppPickerActivity, msg, Toast.LENGTH_LONG).show()
                finish()
            })
            for ((pkg, label) in apps) {
                val cb = CheckBox(this@AppPickerActivity).apply {
                    text = label
                    textSize = 17f
                    setTextColor(Col.TEXT)
                    isChecked = pkg in chosen
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    setOnCheckedChangeListener { _, on -> if (on) chosen += pkg else chosen -= pkg }
                }
                root.add(cb, 2)
            }
        }
    }
}
