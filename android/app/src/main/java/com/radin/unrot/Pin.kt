package com.radin.unrot

import java.security.MessageDigest
import java.security.SecureRandom

/** The PIN your family member holds. Entering it unlocks Unrot for 10 minutes. */
object Pin {
    const val UNLOCK_MS = 10L * 60 * 1000

    fun isSet(s: Store) = s.pinHash.isNotEmpty()

    fun unlocked(s: Store) = s.adminUntil > System.currentTimeMillis()

    fun set(s: Store, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        s.pinSalt = salt
        s.pinHash = hash(salt, pin)
        s.rulesUpdated = System.currentTimeMillis() // shared with the laptop on the next sync
    }

    fun check(s: Store, pin: String): Boolean {
        if (!isSet(s)) return false
        val ok = hash(s.pinSalt, pin) == s.pinHash
        if (ok) s.adminUntil = System.currentTimeMillis() + UNLOCK_MS
        return ok
    }

    fun lockNow(s: Store) {
        s.adminUntil = 0
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
