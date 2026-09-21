package com.example.gps

import android.content.Context

class NavigationAppearanceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("navigation_appearance", Context.MODE_PRIVATE)

    fun lightMode(): Boolean = prefs.getBoolean("light_mode", false)

    fun setLightMode(enabled: Boolean) {
        prefs.edit().putBoolean("light_mode", enabled).apply()
    }
}
