package com.example.applocker

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LockActivity : AppCompatActivity() {

    private lateinit var tv: TextView
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        tv = findViewById(R.id.tvCountdown)
        startCountdown()
    }

    // Dipanggil saat layar kunci dibuka lagi (singleInstance): hitung ulang dari data terbaru
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startCountdown()
    }

    private fun startCountdown() {
        timer?.cancel()
        val prefs = getSharedPreferences("locker_prefs", MODE_PRIVATE)
        val remaining = prefs.getLong("lock_until", 0) - System.currentTimeMillis()

        // Mode dinonaktifkan atau masa kunci sudah selesai -> tutup
        if (!prefs.getBoolean("active", false) || remaining <= 0) {
            finish()
            return
        }

        timer = object : CountDownTimer(remaining, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val min = millisUntilFinished / 60000
                val sec = (millisUntilFinished % 60000) / 1000
                tv.text = "Aplikasi terkunci\n\nSisa waktu: %02d:%02d".format(min, sec)
            }

            override fun onFinish() {
                finish()
            }
        }.start()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    // Cegah tombol back
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // tidak melakukan apa-apa
    }
}
