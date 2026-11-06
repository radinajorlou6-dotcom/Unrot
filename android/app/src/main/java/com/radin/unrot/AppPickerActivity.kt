package com.radin.unrot

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Choose which apps cost scroll time, and which ones are "engaging" (games, movies) and cost less.
 * Blocking or making something full price is instant; loosening waits 24h.
 */
class AppPickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Store.get(this)
        val st = Rules.current(s)
        val pct = st.n("engagingCostPct")
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        data class App(val pkg: String, val label: String, val game: Boolean)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map {
                val ai = it.activityInfo.applicationInfo
                App(ai.packageName, it.loadLabel(pm).toString(), ai.category == ApplicationInfo.CATEGORY_GAME)
            }
            .plus(App(Settings.SHORTS_KEY, Settings.SHORTS_LABEL, false))
            .distinctBy { it.pkg }
            .filter { it.pkg != packageName }
            .sortedWith(compareBy({ it.pkg !in st.flagged }, { it.label.lowercase() }))
        val chosen = st.flagged.toMutableSet()
        val half = st.halfRate.toMutableSet()

        setScreen { root ->
            root.add(title("Blocked apps", 30f), 8)
            root.add(
                text(
                    "Checked apps cost scroll time. Tap the price to switch between full price and $pct% " +
                        "(for games and movies: procrastinating, but at least it's engaging). " +
                        "Blocking or raising a price is instant; unblocking or lowering one takes 24 hours (or the PIN).",
                    15f, Col.MUTED,
                ), 16,
            )
            root.add(button("Save", Ui.Style.PRIMARY, R.drawable.ic_check_circle) {
                val r = Rules.propose(s, Rules.current(s).copy(flagged = chosen.toSet(), halfRate = half.filter { it.startsWith("site:") || it in chosen }.toSet())) { p ->
                    apps.firstOrNull { it.pkg == p }?.label ?: p
                }
                val msg = if (r.delayed > 0) "Saved. ${r.delayed} loosening change(s) will apply in 24h." else "Saved."
                Toast.makeText(this@AppPickerActivity, msg, Toast.LENGTH_LONG).show()
                finish()
            })
            for (app in apps) {
                val r = row()
                lateinit var price: Button
                fun refreshPrice() {
                    price.visibility = if (app.pkg in chosen) android.view.View.VISIBLE else android.view.View.INVISIBLE
                    price.text = if (app.pkg in half) "$pct%" else "Full"
                }
                val cb = CheckBox(this@AppPickerActivity).apply {
                    text = app.label + if (app.game) "  🎮" else ""
                    textSize = 17f
                    setTextColor(Col.TEXT)
                    typeface = Fonts.semi(this@AppPickerActivity)
                    buttonTintList = android.content.res.ColorStateList.valueOf(Col.VIOLET)
                    isChecked = app.pkg in chosen
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    setOnCheckedChangeListener { _, on ->
                        if (on) {
                            chosen += app.pkg
                            if (app.game || app.pkg in Settings.STREAMING) half += app.pkg
                        } else chosen -= app.pkg
                        refreshPrice()
                    }
                }
                r.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                price = button("", Ui.Style.GHOST) {
                    if (app.pkg in half) half -= app.pkg else half += app.pkg
                    refreshPrice()
                }
                price.minHeight = dp(40)
                r.addView(price)
                refreshPrice()
                root.add(r, 2)
            }
        }
    }
}
