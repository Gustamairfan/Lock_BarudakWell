package com.example.applocker

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit

class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("locker_prefs", MODE_PRIVATE) }

    private var accumulatedMs = 0L      // total waktu pemakaian aplikasi target
    private var lockUntil = 0L
    private var lastTick = 0L

    // Pelacakan aplikasi yang sedang di layar depan
    private var foregroundPkg: String? = null
    private var lastEventTime = 0L

    private var lastLockLaunch = 0L
    private var anchorView: View? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!prefs.getBoolean("active", false)) {
                stopSelf()
                return
            }

            val now = System.currentTimeMillis()
            val tickNow = SystemClock.elapsedRealtime()
            val delta = (tickNow - lastTick).coerceIn(0L, 3000L)
            lastTick = tickNow

            try {
                updateForegroundPackage()
            } catch (e: Exception) {
                // abaikan, pakai data terakhir
            }

            val targets = AppStore.lockedPackages(prefs)
            val pkg = foregroundPkg
            val inTarget = isScreenOn() && pkg != null && targets.contains(pkg)

            if (now < lockUntil) {
                // ---- MASA KUNCI: timer tidak berjalan, target yang dibuka langsung ditutup layar kunci
                if (inTarget) showLock(now)
                prefs.edit().putBoolean("counting", false).apply()
            } else {
                // ---- MASA BOLEH PAKAI: timer hanya berjalan saat aplikasi target dibuka
                if (inTarget) {
                    accumulatedMs += delta
                }

                val offMinutes = prefs.getInt("off_minutes", 20)
                val limitMs = TimeUnit.MINUTES.toMillis(offMinutes.toLong())

                if (accumulatedMs >= limitMs) {
                    val waitMinutes = prefs.getInt("wait_minutes", 15)
                    lockUntil = now + TimeUnit.MINUTES.toMillis(waitMinutes.toLong())
                    accumulatedMs = 0L
                    prefs.edit()
                        .putLong("lock_until", lockUntil)
                        .putLong("accumulated", 0L)
                        .putBoolean("counting", false)
                        .apply()
                    showLock(now)
                } else {
                    prefs.edit()
                        .putLong("accumulated", accumulatedMs)
                        .putBoolean("counting", inTarget)
                        .apply()
                }
            }

            handler.postDelayed(this, 500)
        }
    }

    /**
     * Membaca event penggunaan sejak pengecekan terakhir:
     * - ACTIVITY_RESUMED  -> aplikasi itu sekarang di depan
     * - ACTIVITY_PAUSED   -> kalau itu aplikasi yang di depan, berarti tidak ada lagi (mis. layar mati)
     */
    private fun updateForegroundPackage() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = if (lastEventTime == 0L) now - TimeUnit.HOURS.toMillis(6) else lastEventTime
        val events = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.timeStamp > lastEventTime) lastEventTime = e.timeStamp
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> foregroundPkg = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED ->
                    if (e.packageName == foregroundPkg) foregroundPkg = null
            }
        }
    }

    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    private fun showLock(now: Long) {
        // Jangan menembakkan activity berkali-kali dalam waktu singkat
        if (now - lastLockLaunch < 1500) return
        lastLockLaunch = now
        try {
            val intent = Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Jendela overlay 1x1 piksel yang tidak terlihat. Dengan adanya jendela overlay yang aktif,
     * Android (terutama versi baru) lebih mengizinkan service membuka LockActivity dari latar belakang.
     */
    private fun addAnchorOverlay() {
        try {
            if (!Settings.canDrawOverlays(this) || anchorView != null) return
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.START or Gravity.TOP
            wm.addView(v, lp)
            anchorView = v
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun removeAnchorOverlay() {
        try {
            anchorView?.let {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(it)
            }
        } catch (e: Exception) {
            // ignore
        }
        anchorView = null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())

        lockUntil = prefs.getLong("lock_until", 0)
        accumulatedMs = prefs.getLong("accumulated", 0)
        lastTick = SystemClock.elapsedRealtime()

        addAnchorOverlay()
        handler.post(tickRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Dipanggil lagi saat MainActivity / BootReceiver menyalakan service; aman diulang.
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "monitor",
            "App Locker",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "monitor")
            .setContentTitle("App Locker aktif")
            .setContentText("Timer berjalan saat aplikasi target dibuka")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        removeAnchorOverlay()
        prefs.edit().putBoolean("counting", false).apply()
        super.onDestroy()
    }
}
