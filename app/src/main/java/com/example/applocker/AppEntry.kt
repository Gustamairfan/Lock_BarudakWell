package com.example.applocker

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Satu baris di daftar aplikasi yang bisa dikunci.
 * packageName boleh berisi lebih dari satu package, dipisah koma
 * (dipakai untuk TikTok yang package-nya beda-beda tiap negara).
 */
data class LockedApp(
    val packageName: String,
    val description: String,
    var checked: Boolean
) {
    fun packages(): List<String> =
        packageName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

object AppStore {
    private const val KEY = "apps_json"

    fun defaults(): MutableList<LockedApp> = mutableListOf(
        LockedApp("com.google.android.youtube", "YouTube", true),
        LockedApp("com.facebook.katana,com.facebook.lite", "Facebook", true),
        LockedApp(
            "com.zhiliaoapp.musically,com.ss.android.ugc.trill,com.ss.android.ugc.aweme",
            "TikTok",
            true
        )
    )

    fun load(prefs: SharedPreferences): MutableList<LockedApp> {
        val raw = prefs.getString(KEY, null) ?: return defaults()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<LockedApp>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    LockedApp(
                        o.getString("package"),
                        o.getString("description"),
                        o.optBoolean("checked", true)
                    )
                )
            }
            list
        } catch (e: Exception) {
            defaults()
        }
    }

    fun save(prefs: SharedPreferences, list: List<LockedApp>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("package", it.packageName)
                    .put("description", it.description)
                    .put("checked", it.checked)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** Semua package yang dicentang (sudah dipecah per koma). */
    fun lockedPackages(prefs: SharedPreferences): Set<String> =
        load(prefs).filter { it.checked }.flatMap { it.packages() }.toSet()
}
