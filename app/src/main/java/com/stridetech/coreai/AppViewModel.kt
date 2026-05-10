package com.stridetech.coreai

import androidx.lifecycle.ViewModel
import com.stridetech.coreai.ui.theme.ThemeMode
import com.stridetech.coreai.ui.theme.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val themePreference: ThemePreference
) : ViewModel() {

    private val _themeMode = MutableStateFlow(themePreference.get())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        themePreference.set(mode)
        _themeMode.value = mode
    }
}
