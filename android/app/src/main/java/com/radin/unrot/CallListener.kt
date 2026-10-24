package com.radin.unrot

import android.app.Notification
import android.app.Person
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Spots WhatsApp / Telegram / imo / … calls from their "ongoing call" notification, matches the
 * name to your family list, and counts only the seconds your phone's audio is really in a call
 * (so ringing or a missed call earns nothing). Only call notifications from those apps are looked at,
 * and nothing is stored except who, how long, and when.
 */
class CallListener : NotificationListenerService() {
    private class Active(val who: Contact?, val app: CallApp, val seenName: String, val start: Long) {
        var connectedSec = 0L
    }

    private val active = HashMap<String, Active>()
    private val h = Handler(Looper.getMainLooper())

    private val sampler = object : Runnable {
        override fun run() {
            if (active.isEmpty()) return
            val mode = runCatching { getSystemService(AudioManager::class.java).mode }.getOrDefault(AudioManager.MODE_NORMAL)
            if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) {
                active.values.forEach { it.connectedSec++ }
            }
            h.postDelayed(this, 1000)
        }
    }

    override fun onListenerConnected() {
        // Pick up a call that was already going when Unrot (re)connected.
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = CallApps.forPackage(sbn.packageName) ?: return
        if (active.containsKey(sbn.key)) return
        val n = sbn.notification ?: return
        val ex = n.extras
        val parts = mutableListOf<String>()
        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_CONVERSATION_TITLE)
            .forEach { k -> ex.getCharSequence(k)?.toString()?.let { parts += it } }
        val person = if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) ex.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
                else @Suppress("DEPRECATION") (ex.getParcelable(Notification.EXTRA_CALL_PERSON) as? Person)
            }.getOrNull()?.name?.toString()
        } else null
        if (person != null) parts += person
        val hay = parts.joinToString("  ")

        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val isCall = n.category == Notification.CATEGORY_CALL ||
            (ongoing && (chrono || hay.contains("call", ignoreCase = true)))
        if (!isCall) return

        val fam = Store.get(this).family.sortedBy { if (it.via == app.id) 0 else 1 }
        val who = fam.firstOrNull { c -> matches(hay, c.matchName) }
        val seen = person ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "someone"
        active[sbn.key] = Active(who, app, seen, System.currentTimeMillis())
        h.removeCallbacks(sampler)
        h.post(sampler)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val a = active.remove(sbn.key) ?: return
        finish(a)
    }

    override fun onListenerDisconnected() {
        active.values.toList().forEach { finish(it) }
        active.clear()
    }

    private fun finish(a: Active) {
        val s = Store.get(this)
        if (a.who == null) {
            if (a.connectedSec >= 60) {
                s.log("${a.app.label} call with \"${a.seenName}\" (${a.connectedSec / 60} min) didn't count: nobody in Family has that name. Set their \"name in ${a.app.label}\" in Family.")
            }
            return
        }
        Thread {
            runCatching { Calls.recordCall(this, a.who, a.connectedSec, System.currentTimeMillis(), a.app.label) }
        }.start()
    }

    companion object {
        /** Whole-word, case-insensitive, so "Al" doesn't match the word "call". */
        fun matches(hay: String, name: String): Boolean {
            val n = name.trim()
            if (n.isEmpty()) return false
            return Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(n) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                .containsMatchIn(hay)
        }
    }
}
