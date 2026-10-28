package com.radin.unrot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog

/** A way to call someone. Phone calls are checked in the call log; app calls by their call notification. */
data class CallApp(val id: String, val label: String, val packages: List<String>, val handleHint: String?)

object CallApps {
    val PHONE = CallApp("phone", "Phone call", emptyList(), "Phone number")
    val all = listOf(
        PHONE,
        CallApp("whatsapp", "WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b"), "Phone number (optional, opens their chat)"),
        CallApp("telegram", "Telegram", listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"), "Phone number or @username (optional, opens their chat)"),
        CallApp("imo", "imo", listOf("com.imo.android.imoim", "com.imo.android.imoimbeta", "com.imo.android.imoimlite"), null),
        CallApp("messenger", "Messenger", listOf("com.facebook.orca"), null),
        CallApp("signal", "Signal", listOf("org.thoughtcrime.securesms"), null),
        CallApp("viber", "Viber", listOf("com.viber.voip"), null),
        CallApp("discord", "Discord", listOf("com.discord"), null),
    )

    fun byId(id: String): CallApp = all.firstOrNull { it.id == id } ?: PHONE
    fun forPackage(pkg: String): CallApp? = all.firstOrNull { pkg in it.packages }
}

/** Calls with family earn time, and power the "last called" line on the block screen. */
object Calls {
    fun norm(n: String) = n.filter { it.isDigit() }.takeLast(10)

    fun canRead(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /**
     * One verified call with a family member: phone (from the call log) or an app (from its call
     * notification, counting only time the phone's audio was really in a call).
     * Pays a flat reward if long enough, at most once per person per cooldown. Returns seconds credited.
     */
    fun recordCall(ctx: Context, who: Contact, durSec: Long, date: Long, how: String): Long {
        val s = Store.get(ctx)
        synchronized(s.lock) {
            val st = Rules.current(s)
            if (durSec >= 60) {
                val talk = s.obj("lastTalk")
                if (date > talk.optLong(who.key, 0L)) talk.put(who.key, date)
                s.putObj("lastTalk", talk)
            }
            val minSec = st.n("callMinMin") * 60L
            if (durSec < minSec) {
                if (durSec >= 30) s.log("$how call with ${who.name} was ${durSec / 60} min. Needs ${minSec / 60}+ to earn time")
                return 0
            }
            Ledger.countStat("c")
            val cooldownMs = st.n("callCooldownH") * 3_600_000L
            val paid = s.obj("callPaid")
            if (date - paid.optLong(who.key, 0L) < cooldownMs) {
                s.log("$how call with ${who.name}: already paid in the last ${cooldownMs / 3_600_000}h")
                return 0
            }
            paid.put(who.key, date)
            s.putObj("callPaid", paid)
            return Ledger.credit(s, st, st.n("callRewardMin") * 60L, "$how call with ${who.name} (${durSec / 60} min)")
        }
    }

    /** Checks the phone's call log for new calls with family. Returns seconds credited. */
    fun sync(ctx: Context): Long {
        if (!canRead(ctx)) return 0
        val s = Store.get(ctx)
        val fam = s.family.filter { norm(it.phone).length >= 7 }.associateBy { norm(it.phone) }
        var last = s.lastCallTs
        var total = 0L
        runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(s.lastCallTs.toString()),
                "${CallLog.Calls.DATE} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0) ?: ""
                    val type = c.getInt(1)
                    val dur = c.getLong(2)
                    val date = c.getLong(3)
                    if (date > last) last = date
                    if (type != CallLog.Calls.OUTGOING_TYPE && type != CallLog.Calls.INCOMING_TYPE) continue
                    val who = fam[norm(number)] ?: continue
                    total += recordCall(ctx, who, dur, date, "Phone")
                }
            }
        }
        s.lastCallTs = last
        return total
    }

    /** When you last talked to this person (ms), by phone or app, or null if unknown. */
    fun lastCallWith(ctx: Context, who: Contact): Long? {
        val fromApps = Store.get(ctx).obj("lastTalk").optLong(who.key, 0L).takeIf { it > 0 }
        val fromLog = phoneLastCall(ctx, who.phone)
        return listOfNotNull(fromApps, fromLog).maxOrNull()
    }

    private fun phoneLastCall(ctx: Context, phone: String): Long? {
        if (!canRead(ctx)) return null
        val target = norm(phone)
        if (target.length < 7) return null
        return runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                var seen = 0
                while (c.moveToNext() && seen < 1000) {
                    seen++
                    if (c.getLong(2) > 0 && norm(c.getString(0) ?: "") == target) return@use c.getLong(1)
                }
                null
            }
        }.getOrNull()
    }

    /** Starts a call (phone) or opens the right app/chat. Returns false if the app isn't installed. */
    fun start(ctx: Context, who: Contact): Boolean {
        if (who.via == "phone") {
            call(ctx, who.phone)
            return true
        }
        return openChat(ctx, who)
    }

    fun call(ctx: Context, phone: String) {
        val uri = Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })
        val direct = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    private fun installed(ctx: Context, app: CallApp): String? = app.packages.firstOrNull { pkg ->
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    private fun waDigits(phone: String) = phone.filter { it.isDigit() }.let { if (it.length == 10) "1$it" else it }

    /** Opens their chat when we know how (WhatsApp/Telegram with a number or username), else just the app. */
    private fun openChat(ctx: Context, who: Contact): Boolean {
        val pkg = installed(ctx, who.app) ?: return false
        val handle = who.phone.trim()
        val digits = handle.filter { it.isDigit() }
        val deep: Intent? = when (who.via) {
            "whatsapp" -> if (digits.length >= 7) Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${waDigits(handle)}")) else null
            "telegram" -> when {
                handle.startsWith("@") || (handle.isNotEmpty() && digits.length < 7) ->
                    Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=${handle.removePrefix("@")}"))
                digits.length >= 7 -> Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=${waDigits(handle)}"))
                else -> null
            }
            else -> null
        }?.setPackage(pkg)
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
        for (i in listOfNotNull(deep, launch)) {
            val ok = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** Night option: a voice note in whatever app you use with them (WhatsApp or SMS for phone contacts). */
    fun voiceNote(ctx: Context, who: Contact) {
        if (who.via != "phone" && openChat(ctx, who)) return
        val digits = waDigits(who.phone)
        val hasWa = installed(ctx, CallApps.byId("whatsapp"))
        val i = if (hasWa != null && digits.length >= 7) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage(hasWa)
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$digits"))
        }
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
