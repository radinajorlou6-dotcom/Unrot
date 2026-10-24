package com.radin.unrot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog

/** Calls with family earn time, and power the "last called" line on the block screen. */
object Calls {
    fun norm(n: String) = n.filter { it.isDigit() }.takeLast(10)

    fun canRead(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** Credits calls of 1+ minute with anyone on your family list. Returns seconds credited. */
    fun sync(ctx: Context): Long {
        if (!canRead(ctx)) return 0
        val s = Store.get(ctx)
        val fam = s.family.associateBy { norm(it.phone) }.filterKeys { it.length >= 7 }
        val st = Rules.current(s)
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
                    if (dur < 60) continue
                    val who = fam[norm(number)] ?: continue
                    total += Ledger.credit(s, st, (dur / st.callRatio).toLong(), "call with ${who.name}, ${dur / 60} min")
                }
            }
        }
        s.lastCallTs = last
        return total
    }

    /** When you last talked to this person (ms), or null if not in the recent call log. */
    fun lastCallWith(ctx: Context, phone: String): Long? {
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

    fun call(ctx: Context, phone: String) {
        val uri = Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })
        val direct = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    /** Opens a WhatsApp chat (hold the mic to record a voice note); falls back to SMS. */
    fun voiceNote(ctx: Context, phone: String) {
        val digits = phone.filter { it.isDigit() }.let { if (it.length == 10) "1$it" else it }
        val hasWa = runCatching { ctx.packageManager.getPackageInfo("com.whatsapp", 0); true }.getOrDefault(false)
        val i = if (hasWa) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage("com.whatsapp")
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$digits"))
        }
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
