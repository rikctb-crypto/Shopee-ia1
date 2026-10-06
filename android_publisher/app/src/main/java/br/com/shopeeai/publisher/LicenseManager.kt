package br.com.shopeeai.publisher

import android.content.Context
import java.util.concurrent.TimeUnit

class LicenseManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("license", Context.MODE_PRIVATE)
    private val trialDays = 30L

    fun ensureStarted() {
        val now = System.currentTimeMillis()
        if (!prefs.contains("started_at")) {
            prefs.edit().putLong("started_at", now).putLong("last_seen", now).apply()
        } else {
            val last = prefs.getLong("last_seen", now)
            if (now >= last - TimeUnit.HOURS.toMillis(6)) {
                prefs.edit().putLong("last_seen", maxOf(now, last)).apply()
            }
        }
    }

    fun isClockValid(): Boolean {
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_seen", now)
        return now >= last - TimeUnit.HOURS.toMillis(6)
    }

    fun daysRemaining(): Long {
        ensureStarted()
        val started = prefs.getLong("started_at", System.currentTimeMillis())
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
        val usedDays = TimeUnit.MILLISECONDS.toDays(elapsed)
        return (trialDays - usedDays).coerceAtLeast(0L)
    }

    fun isValid(): Boolean = isClockValid() && daysRemaining() > 0

    fun statusText(): String {
        if (!isClockValid()) return "Licença: relógio do aparelho foi alterado. Corrija data/hora."
        val d = daysRemaining()
        return if (d > 0) "Licença de teste: $d dia(s) restante(s)" else "Licença de teste expirada"
    }
}
