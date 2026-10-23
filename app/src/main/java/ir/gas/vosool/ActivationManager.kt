
package ir.gas.vosool

import android.content.Context

object ActivationManager {

    const val OWNER_CODE = "QADER-KH-GAS-365"

    private const val PREFS = "vosool_activation"
    private const val KEY_START = "activation_start"
    private const val KEY_EXPIRY = "activation_expiry"

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val VALIDITY_DAYS = 365L

    fun isActivated(context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val start = prefs.getLong(KEY_START, 0L)
        val expiry = prefs.getLong(KEY_EXPIRY, 0L)
        val now = System.currentTimeMillis()

        return start > 0L &&
                expiry > start &&
                now >= start &&
                now < expiry
    }

    fun activate(context: Context, enteredCode: String): Boolean {
        if (enteredCode.trim() != OWNER_CODE) {
            return false
        }

        val now = System.currentTimeMillis()
        val expiry = now + VALIDITY_DAYS * DAY_MS

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putLong(KEY_START, now)
            .putLong(KEY_EXPIRY, expiry)
            .apply()

        return true
    }

    fun remainingDays(context: Context): Long {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val expiry = prefs.getLong(KEY_EXPIRY, 0L)
        val remaining = expiry - System.currentTimeMillis()

        if (remaining <= 0L) return 0L

        return (remaining + DAY_MS - 1L) / DAY_MS
    }
}
