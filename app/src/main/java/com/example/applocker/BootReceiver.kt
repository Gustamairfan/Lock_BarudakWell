package com.example.applocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        // Jika mode masih aktif sebelum HP restart, hidupkan lagi service-nya.
        // Sisa waktu dan masa kunci tetap diambil dari data tersimpan (tidak direset).
        val prefs = context.getSharedPreferences("locker_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("active", false)) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }
    }
}
