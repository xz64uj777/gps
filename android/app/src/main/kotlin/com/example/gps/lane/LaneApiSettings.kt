package com.example.gps.lane

import android.content.Context

class LaneApiSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBaseUrl(): String = prefs.getString(KEY_BASE_URL, "")?.trim().orEmpty().trimEnd('/')

    fun setBaseUrl(value: String) {
        prefs.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()
    }

    private companion object {
        const val PREFS_NAME = "lane_gps_api_settings"
        const val KEY_BASE_URL = "base_url"
    }
}
