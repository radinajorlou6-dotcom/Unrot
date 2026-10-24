package com.radin.unrot

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.health.connect.client.PermissionController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var s: Store
    private var balanceView: TextView? = null
    private var hcAvailable = false
    private var hcGranted = false
    private var refreshing = false

    private val REQ_PERMS = 10
    private val REQ_HC = 11

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Store.get(this)
        Notif.ensureChannels(this)
    }

    override fun onResume() {
        super.onResume()
        render()
        refreshInBackground()
        h.post(liveBalance)
    }

    override fun onPause() {
        h.removeCallbacks(liveBalance)
        super.onPause()
    }

    private val liveBalance = object : Runnable {
        override fun run() {
            balanceView?.text = balanceText()
            h.postDelayed(this, 1000)
        }
    }

    private fun balanceText(): String {
        val b = Ledger.balanceSec(s)
        return if (b > 0) Ledger.fmt(b) else "0:00"
    }

    /** Pulls in new workouts, family calls and laptop totals, then redraws. */
    private fun refreshInBackground(toast: Boolean = false) {
        if (refreshing) return
        refreshing = true
        Thread {
            var earned = 0L
            try {
                hcAvailable = HealthSync.available(this)
                hcGranted = hcAvailable && HealthSync.hasRead(this)
                if (hcGranted) earned += maxOf(0L, HealthSync.sync(this))
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

    // ================================================================== screen

    private fun render() {
        val st = Rules.current(s)
        Ledger.ensureDailyBase(s, st)
        setScreen { root ->
            header(root)
            setupCard(root)
            tomorrowCard(root)
            earnCard(root, st)
            activityCard(root)
            appsCard(root, st)
            familyCard(root)
            rulesCard(root, st)
            pinCard(root)
            syncCard(root)
            notesCard(root)
            helpCard(root)
        }
    }

    private fun Ui.section(root: LinearLayout, title: String): LinearLayout {
        root.add(space(10), 0)
        root.add(text(title, 20f, bold = true), 8)
        val c = card()
        root.add(c, 6)
        return c
    }

    private fun Ui.header(root: LinearLayout) {
        root.add(text("Unrot", 16f, Col.MUTED, bold = true), 0)
        val bal = text(balanceText(), 64f, Col.ACCENT, bold = true)
        balanceView = bal
        root.add(bal, 0)
        val sub = when {
            Ledger.focusActive(s) -> "🔒 Locked in · ${Ledger.fmt(Ledger.focusRemainingSec(s))} left"
            Ledger.balanceSec(s) <= 0 -> "Out of scroll time. Lock in to earn more."
            else -> "of scroll time available"
        }
        root.add(text(sub, 16f, Col.MUTED), 4)
        if (!UnrotService.running && Setup.accessibilityOn(this@MainActivity)) {
            root.add(text("⚠️ The blocker isn't running right now. Turn Unrot off and on again in Accessibility settings.", 14f, Col.WARN))
        }
    }

    // ------------------------------------------------------------------ setup checklist

    private data class Step(val done: Boolean, val title: String, val why: String, val action: String, val run: () -> Unit)

    private fun Ui.setupCard(root: LinearLayout) {
        val ctx = this@MainActivity
        val steps = mutableListOf(
            Step(
                Setup.accessibilityOn(ctx), "1. Turn on the blocker",
                "Accessibility → Installed apps → Unrot → On. If it's greyed out: go back, tap \"App info\" below, ⋮ (top right) → \"Allow restricted settings\", then try again.",
                "Open Accessibility",
            ) { open(Setup.accessibilityIntent()) },
            Step(
                Setup.overlayOn(ctx), "2. Allow showing over other apps",
                "Lets Unrot put its screen on top of TikTok and co.", "Allow",
            ) { open(Setup.overlayIntent(ctx)) },
            Step(
                Setup.notificationsOn(ctx), "3. Notifications",
                "For your live balance and the 1-minute warning.", "Allow",
            ) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS) },
            Step(
                Setup.batteryOn(ctx), "4. Stop OnePlus from killing Unrot",
                "Choose \"Allow\". Then also: App info → Battery → Allow background activity, and in Recents, pull down on Unrot's card to lock it.",
                "Allow",
            ) { open(Setup.batteryIntent(ctx)) },
            Step(
                Setup.callsOn(ctx), "5. Calls",
                "So family calls earn time and you can call straight from the block screen.", "Allow",
            ) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE), REQ_PERMS)
            },
        )
        if (hcAvailable) {
            steps += Step(
                hcGranted, "6. Workouts from your watch",
                "Allow \"Exercise\" (and background access if asked). Then in Samsung Health: ⋮ → Settings → Health Connect, and turn syncing on.",
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
            Setup.adminOn(ctx), "7. Uninstall protection",
            "Makes Unrot much harder to delete in a weak moment.", "Turn on",
        ) { open(Setup.adminIntent(ctx)) }

        val missing = steps.filter { !it.done }
        if (s.onboarded && missing.isEmpty()) return

        val c = section(root, if (missing.isEmpty()) "Setup ✓" else "Setup · ${steps.size - missing.size}/${steps.size}")
        for (st in steps) {
            c.add(text((if (st.done) "✅ " else "⬜ ") + st.title, 17f, if (st.done) Col.MUTED else Col.TEXT, bold = !st.done), 2)
            if (!st.done) {
                c.add(text(st.why, 14f, Col.MUTED), 6)
                c.add(button(st.action, Ui.Style.PRIMARY) { st.run() }, 14)
            }
        }
        c.add(button("App info (restricted settings, battery)", Ui.Style.GHOST) { open(Setup.appInfoIntent(ctx)) }, 8)
        if (!s.onboarded) {
            c.add(
                text(
                    "When you're done, finish setup. After that, Unrot guards its own settings and only your family PIN (or a 24-hour wait) can loosen things.",
                    14f, Col.MUTED,
                ), 8,
            )
            c.add(button("Finish setup", Ui.Style.SECONDARY) {
                if (!Setup.accessibilityOn(ctx)) {
                    Toast.makeText(ctx, "Turn on the blocker first (step 1).", Toast.LENGTH_LONG).show()
                } else {
                    s.onboarded = true
                    s.log("Setup finished, protection armed")
                    render()
                }
            }, 0)
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
        refreshInBackground()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ------------------------------------------------------------------ morning note

    private fun Ui.tomorrowCard(root: LinearLayout) {
        val cutoff = System.currentTimeMillis() - 20L * 3600 * 1000
        val recent = s.notes.filter { it.type == NoteActivity.TYPE_TOMORROW && it.ts > cutoff }
        if (recent.isEmpty()) return
        val c = section(root, "☀️ Your first thing today")
        recent.takeLast(3).forEach { c.add(text("• " + it.text, 17f), 4) }
    }

    // ------------------------------------------------------------------ earn

    private fun Ui.earnCard(root: LinearLayout, st: Settings) {
        val c = section(root, "Earn time")
        c.add(
            text(
                "Lock in, work out, or call family. Every ${Rules.fmtRatio(st.focusRatio)} min locked in, " +
                    "${Rules.fmtRatio(st.workoutRatio)} min of workout, or ${Rules.fmtRatio(st.callRatio)} min on the phone with family earns 1 min.",
                14f, Col.MUTED,
            ), 12,
        )
        c.add(button(if (Ledger.focusActive(s)) "🔒 See lock-in" else "🔒 Lock in", Ui.Style.PRIMARY) {
            startActivity(Intent(this@MainActivity, FocusActivity::class.java))
        })
        c.add(button("Check for new workouts & calls", Ui.Style.SECONDARY) { refreshInBackground(toast = true) }, 0)
    }

    private fun Ui.activityCard(root: LinearLayout) {
        val lines = s.log.take(8)
        if (lines.isEmpty()) return
        val c = section(root, "Recent")
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        for (l in lines) {
            c.add(text(l.text, 15f), 0)
            c.add(text(fmt.format(Date(l.ts)), 12f, Col.MUTED), 8)
        }
    }

    // ------------------------------------------------------------------ apps and family

    private fun Ui.appsCard(root: LinearLayout, st: Settings) {
        val c = section(root, "Blocked apps")
        val pm = packageManager
        val installed = st.flagged.mapNotNull { p ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrNull()
        }.sorted()
        c.add(text(if (installed.isEmpty()) "None of your blocked apps are installed yet." else installed.joinToString(" · "), 15f), 10)
        c.add(button("Edit blocked apps", Ui.Style.SECONDARY) {
            startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
        }, 0)
    }

    private fun Ui.familyCard(root: LinearLayout) {
        val c = section(root, "Family")
        c.add(text("These are who the block screen suggests calling, and calls with them earn time.", 14f, Col.MUTED), 10)
        val fam = s.family
        for (f in fam) {
            val r = row()
            val t = text("${f.name}\n${f.phone}", 16f)
            r.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(button("Remove", Ui.Style.GHOST) {
                s.family = s.family.filter { it != f }
                render()
            })
            c.add(r, 8)
        }
        c.add(button("+ Add family member", Ui.Style.SECONDARY) { addFamilyDialog() }, 0)
    }

    private fun addFamilyDialog() {
        val ui = Ui(this)
        val box = ui.col(16)
        val name = ui.input("Name (e.g. Mom)")
        val phone = ui.input("Phone number").apply { inputType = InputType.TYPE_CLASS_PHONE }
        box.add(name)
        box.add(phone)
        dialog().setTitle("Add family member").setView(box)
            .setPositiveButton("Add") { _, _ ->
                val n = name.text.toString().trim()
                val p = phone.text.toString().trim()
                if (n.isNotEmpty() && Calls.norm(p).length >= 7) {
                    s.family = s.family + Contact(n, p)
                    render()
                } else {
                    Toast.makeText(this, "Need a name and a full phone number.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ rules

    private fun Ui.rulesCard(root: LinearLayout, st: Settings) {
        val c = section(root, "Rules")
        c.add(
            text(
                if (Pin.unlocked(s)) "🔓 PIN unlocked: every change applies right away."
                else "Stricter changes apply now. Looser ones wait 24 hours, so a 1am you can't undo a daytime you.",
                14f, if (Pin.unlocked(s)) Col.ACCENT else Col.MUTED,
            ), 12,
        )

        fun rule(label: String, value: String, edit: () -> Unit) {
            val r = row()
            val t = col()
            t.add(text(label, 15f, Col.MUTED), 0)
            t.add(text(value, 17f, bold = true), 0)
            r.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(button("Edit", Ui.Style.GHOST) { edit() })
            c.add(r, 10)
        }

        rule("Lock-in ratio", "${Rules.fmtRatio(st.focusRatio)} min → 1 min") {
            numberDialog("Minutes locked in per 1 min earned", st.focusRatio.toString(), decimal = true) { v ->
                propose(st.copy(focusRatio = v.coerceIn(0.1, 100.0)))
            }
        }
        rule("Workout ratio", "${Rules.fmtRatio(st.workoutRatio)} min → 1 min") {
            numberDialog("Workout minutes per 1 min earned", st.workoutRatio.toString(), decimal = true) { v ->
                propose(st.copy(workoutRatio = v.coerceIn(0.1, 100.0)))
            }
        }
        rule("Family call ratio", "${Rules.fmtRatio(st.callRatio)} min → 1 min") {
            numberDialog("Call minutes per 1 min earned", st.callRatio.toString(), decimal = true) { v ->
                propose(st.copy(callRatio = v.coerceIn(0.1, 100.0)))
            }
        }
        rule("Free minutes every day", "${st.dailyBaseMin} min") {
            numberDialog("Free minutes per day", st.dailyBaseMin.toString()) { v ->
                propose(st.copy(dailyBaseMin = v.toInt().coerceIn(0, 600)))
            }
        }
        rule("Most time you can save up", "${st.maxBankMin} min") {
            numberDialog("Max saved minutes", st.maxBankMin.toString()) { v ->
                propose(st.copy(maxBankMin = v.toInt().coerceIn(5, 1440)))
            }
        }
        rule("\"Still glad you're here?\" check-in", if (st.checkInMin == 0) "Off" else "Every ${st.checkInMin} min of scrolling") {
            numberDialog("Minutes of non-stop scrolling before a check-in (0 = off)", st.checkInMin.toString()) { v ->
                propose(st.copy(checkInMin = v.toInt().coerceIn(0, 240)))
            }
        }
        rule("Bedtime", if (st.bedtimeOn) "${Rules.fmtClock(st.bedStart)} – ${Rules.fmtClock(st.bedEnd)}" else "Off") {
            bedtimeDialog(st)
        }
        rule("Bedtime wait before \"continue\"", "${st.bedWaitSec}s") {
            numberDialog("Seconds to wait", st.bedWaitSec.toString()) { v ->
                propose(st.copy(bedWaitSec = v.toInt().coerceIn(0, 600)))
            }
        }
        rule("Count walks as workouts", if (st.countWalks) "Yes" else "No (gym, soccer, etc. only)") {
            propose(st.copy(countWalks = !st.countWalks))
        }
        rule("Protect Unrot's settings", if (st.protection) "On" else "Off") {
            propose(st.copy(protection = !st.protection))
        }

        val pend = Rules.pending(s)
        if (pend.isNotEmpty()) {
            c.add(space(6))
            c.add(text("Waiting to kick in", 16f, Col.WARN, bold = true), 6)
            for (p in pend.sortedBy { it.applyAt }) {
                val left = maxOf(0L, p.applyAt - System.currentTimeMillis()) / 60000
                val r = row()
                r.addView(
                    text("${p.label}\nin ${left / 60}h ${left % 60}m", 14f),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                r.addView(button("Cancel", Ui.Style.GHOST) { Rules.cancel(s, p.key); render() })
                c.add(r, 8)
            }
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
                        14f, Col.MUTED,
                    ), 10,
                )
                c.add(button("Set family PIN", Ui.Style.SECONDARY) { setPinDialog() }, 0)
            }
            Pin.unlocked(s) -> {
                val left = (s.adminUntil - System.currentTimeMillis()) / 60000 + 1
                c.add(text("🔓 Unlocked for about $left more min.", 15f, Col.ACCENT), 10)
                c.add(button("Change PIN", Ui.Style.SECONDARY) { setPinDialog() })
                c.add(button("Lock now", Ui.Style.GHOST) { Pin.lockNow(s); render() }, 0)
            }
            else -> {
                c.add(text("🔒 Locked. Your family member can unlock it for 10 minutes.", 15f), 10)
                c.add(button("Enter PIN", Ui.Style.SECONDARY) { enterPinDialog() }, 0)
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
                14f, Col.MUTED,
            ), 10,
        )
        val url = input("Firebase database URL (https://…firebasedatabase.app)")
        url.setText(s.dbUrl)
        c.add(url, 8)
        c.add(text(if (s.syncCode.isEmpty()) "No sync code yet" else "Sync code: ${s.syncCode}", 16f, bold = true), 8)
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
        c.add(r, 8)
        c.add(button("Save URL & sync now", Ui.Style.SECONDARY) {
            s.dbUrl = url.text.toString().trim()
            refreshInBackground(toast = false)
            Toast.makeText(this@MainActivity, "Syncing…", Toast.LENGTH_SHORT).show()
        }, 8)
        val status = when {
            !Sync.configured(s) -> "Not set up"
            s.lastSyncError.isNotEmpty() -> "⚠️ Last sync failed: ${s.lastSyncError}"
            s.lastSyncTs == 0L -> "Not synced yet"
            else -> "Last synced ${SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(s.lastSyncTs))}"
        }
        c.add(text(status, 13f, Col.MUTED), 4)
        for (d in Ledger.remotes(s)) {
            c.add(text("• ${d.name}: earned ${Ledger.fmtShort(d.earned)}, spent ${Ledger.fmtShort(d.spent)}", 13f, Col.MUTED), 2)
        }
    }

    // ------------------------------------------------------------------ notes and help

    private fun Ui.notesCard(root: LinearLayout) {
        val notes = s.notes
        if (notes.isEmpty()) return
        val c = section(root, "Night notes")
        val fmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault())
        for (n in notes.takeLast(6).reversed()) {
            c.add(text((if (n.type == NoteActivity.TYPE_DUMP) "🧠 " else "📝 ") + fmt.format(Date(n.ts)), 12f, Col.MUTED), 0)
            c.add(text(n.text, 15f), 10)
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
            ), 10,
        )
        c.add(button("Open App info", Ui.Style.GHOST) { open(Setup.appInfoIntent(this@MainActivity)) }, 0)
        root.add(space(30))
        root.add(text("Unrot ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""}", 12f, Col.MUTED))
    }

    @Suppress("unused")
    private val sdk = Build.VERSION.SDK_INT
}
