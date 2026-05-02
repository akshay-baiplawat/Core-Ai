package com.stridetech.coreai.ui.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Singleton
class ThemePreference @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): ThemeMode = ThemeMode.entries.getOrElse(
        prefs.getInt(KEY_THEME, ThemeMode.SYSTEM.ordinal)
    ) { ThemeMode.SYSTEM }

    fun set(mode: ThemeMode) {
        prefs.edit().putInt(KEY_THEME, mode.ordinal).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_THEME = "theme_mode"
    }
}
