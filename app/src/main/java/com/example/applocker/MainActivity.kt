package com.example.applocker

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerOff: Spinner
    private lateinit var spinnerWait: Spinner
    private lateinit var btnToggle: Button
    private lateinit var btnAddApp: Button
    private lateinit var llAppList: LinearLayout
    private lateinit var tvUsage: TextView

    private val prefs by lazy { getSharedPreferences("locker_prefs", MODE_PRIVATE) }
    private val PIN = "1234"
    private val handler = Handler(Looper.getMainLooper())

    private var apps = mutableListOf<LockedApp>()

    private val packageRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateUsageText()
            handler.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerOff = findViewById(R.id.spinnerOff)
        spinnerWait = findViewById(R.id.spinnerWait)
        btnToggle = findViewById(R.id.btnToggle)
        btnAddApp = findViewById(R.id.btnAddApp)
        llAppList = findViewById(R.id.llAppList)
        tvUsage = findViewById(R.id.tvUsage)

        val offOptions = arrayOf("20 menit", "30 menit")
        val waitOptions = arrayOf("15 menit", "20 menit")
        spinnerOff.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, offOptions)
        spinnerWait.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, waitOptions)

        spinnerOff.setSelection(prefs.getInt("off_timer", 0))
        spinnerWait.setSelection(prefs.getInt("wait_timer", 0))

        apps = AppStore.load(prefs)

        updateButton()
        updateControlsEnabled()
        updateUsageText()

        btnAddApp.setOnClickListener { showAddAppDialog() }

        btnToggle.setOnClickListener {
            if (isActive()) {
                showPinDialog()
            } else {
                if (apps.none { it.checked }) {
                    Toast.makeText(
                        this,
                        "Centang minimal satu aplikasi yang akan dikunci",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                if (!hasUsageAccess()) {
                    requestUsageAccess()
                    return@setOnClickListener
                }
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                    return@setOnClickListener
                }

                saveSettings()
                AppStore.save(prefs, apps)

                // Setiap kali diaktifkan, timer SELALU mulai dari awal
                prefs.edit()
                    .putLong("accumulated", 0L)
                    .putLong("lock_until", 0L)
                    .putBoolean("active", true)
                    .apply()

                startMonitorService()
                updateButton()
                updateControlsEnabled()
                Toast.makeText(this, "Mode penguncian AKTIF - Timer mulai berjalan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isActive() = prefs.getBoolean("active", false)

    private fun updateButton() {
        if (isActive()) {
            btnToggle.text = "NONAKTIFKAN (Merah = Aktif)"
            btnToggle.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        } else {
            btnToggle.text = "AKTIFKAN"
            btnToggle.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        }
    }

    /** Saat mode aktif, pengaturan & daftar aplikasi dikunci supaya tidak bisa diubah. */
    private fun updateControlsEnabled() {
        val enabled = !isActive()
        spinnerOff.isEnabled = enabled
        spinnerWait.isEnabled = enabled
        spinnerOff.alpha = if (enabled) 1f else 0.5f
        spinnerWait.alpha = if (enabled) 1f else 0.5f
        renderAppList()
    }

    // ------------------------------------------------------------------
    // Daftar aplikasi
    // ------------------------------------------------------------------

    private fun renderAppList() {
        val editable = !isActive()
        llAppList.removeAllViews()

        if (apps.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Belum ada aplikasi di daftar."
                setTextColor(0xFF888888.toInt())
                setPadding(0, 12, 0, 12)
            }
            llAppList.addView(empty)
        }

        for (app in apps) {
            val row = layoutInflater.inflate(R.layout.item_app, llAppList, false)
            val cb = row.findViewById<CheckBox>(R.id.cbApp)
            val tvDesc = row.findViewById<TextView>(R.id.tvDesc)
            val tvPackage = row.findViewById<TextView>(R.id.tvPackage)
            val btnDelete = row.findViewById<View>(R.id.btnDelete)

            tvDesc.text = app.description
            tvPackage.text = app.packageName

            cb.isChecked = app.checked
            cb.isEnabled = editable
            cb.setOnCheckedChangeListener { _, isChecked ->
                app.checked = isChecked
                AppStore.save(prefs, apps)
            }

            btnDelete.isEnabled = editable
            btnDelete.alpha = if (editable) 1f else 0.3f
            btnDelete.setOnClickListener { confirmDelete(app) }

            llAppList.addView(row)
        }

        btnAddApp.isEnabled = editable
        btnAddApp.alpha = if (editable) 1f else 0.5f
    }

    private fun confirmDelete(app: LockedApp) {
        AlertDialog.Builder(this)
            .setTitle("Hapus dari daftar?")
            .setMessage("${app.description}\n${app.packageName}")
            .setPositiveButton("Hapus") { _, _ ->
                apps.remove(app)
                AppStore.save(prefs, apps)
                renderAppList()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAddAppDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_app, null)
        val etPackage = view.findViewById<EditText>(R.id.etPackage)
        val etDescription = view.findViewById<EditText>(R.id.etDescription)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Tambahkan Aplikasi")
            .setView(view)
            .setPositiveButton("Simpan", null) // di-override di bawah supaya dialog tidak menutup saat input salah
            .setNegativeButton("Batal", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pkgs = etPackage.text.toString()
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val desc = etDescription.text.toString().trim()

                when {
                    pkgs.isEmpty() -> etPackage.error = "Package wajib diisi"
                    pkgs.any { !packageRegex.matches(it) } ->
                        etPackage.error = "Format tidak valid (contoh: com.instagram.android)"
                    desc.isEmpty() -> etDescription.error = "Description wajib diisi"
                    apps.any { existing -> existing.packages().any { it in pkgs } } ->
                        etPackage.error = "Package sudah ada di daftar"
                    else -> {
                        apps.add(LockedApp(pkgs.joinToString(","), desc, true))
                        AppStore.save(prefs, apps)
                        renderAppList()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------
    // Timer & status
    // ------------------------------------------------------------------

    private fun updateUsageText() {
        if (!isActive()) {
            tvUsage.text = "Status: Nonaktif"
            return
        }

        val offMinutes = prefs.getInt("off_minutes", 20)
        val limitMs = offMinutes * 60 * 1000L
        val accumulated = prefs.getLong("accumulated", 0)
        val remainingMs = (limitMs - accumulated).coerceAtLeast(0)

        val lockUntil = prefs.getLong("lock_until", 0)
        val now = System.currentTimeMillis()

        if (now < lockUntil) {
            val sisa = lockUntil - now
            val m = (sisa / 60000).toInt()
            val s = ((sisa % 60000) / 1000).toInt()
            val ms = (sisa % 1000).toInt()
            tvUsage.text = "SEDANG DIKUNCI\n%02d:%02d:%03d".format(m, s, ms)
        } else {
            val m = (remainingMs / 60000).toInt()
            val s = ((remainingMs % 60000) / 1000).toInt()
            val ms = (remainingMs % 1000).toInt()
            tvUsage.text = "Waktu tersisa %02d:%02d:%03d dari %d menit".format(m, s, ms, offMinutes)
        }
    }

    private fun saveSettings() {
        val offMin = if (spinnerOff.selectedItemPosition == 0) 20 else 30
        val waitMin = if (spinnerWait.selectedItemPosition == 0) 15 else 20
        prefs.edit()
            .putInt("off_timer", spinnerOff.selectedItemPosition)
            .putInt("wait_timer", spinnerWait.selectedItemPosition)
            .putInt("off_minutes", offMin)
            .putInt("wait_minutes", waitMin)
            .apply()
    }

    private fun showPinDialog() {
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        AlertDialog.Builder(this)
            .setTitle("Masukkan Kode PIN")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == PIN) {
                    // Nonaktifkan + reset semua timer
                    prefs.edit()
                        .putBoolean("active", false)
                        .putLong("accumulated", 0L)
                        .putLong("lock_until", 0L)
                        .apply()
                    stopService(Intent(this, MonitorService::class.java))
                    updateButton()
                    updateControlsEnabled()
                    updateUsageText()
                    Toast.makeText(this, "Mode penguncian DINONAKTIFKAN", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PIN salah!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Izin
    // ------------------------------------------------------------------

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsageAccess() {
        Toast.makeText(this, "Aktifkan izin Usage Access", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, "Izinkan tampil di atas aplikasi lain", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onResume() {
        super.onResume()
        updateButton()
        updateControlsEnabled()
        updateUsageText()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }
}
