package com.stridetech.coreai.ui.settings

import androidx.lifecycle.ViewModel
import com.stridetech.coreai.security.ApiKeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SettingsUiState(
    val apiKeys: List<String> = emptyList(),
    val hfToken: String? = null,
    val hfTokenSaved: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val apiKeyManager: ApiKeyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun generateKey() {
        apiKeyManager.generateKey()
        reload()
    }

    fun revokeKey(key: String) {
        apiKeyManager.revokeKey(key)
        reload()
    }

    fun revokeAll() {
        apiKeyManager.revokeAll()
        reload()
    }

    fun saveHuggingFaceToken(token: String) {
        apiKeyManager.saveHuggingFaceToken(token.trim())
        _uiState.update { it.copy(hfToken = token.trim(), hfTokenSaved = true) }
    }

    fun clearHfTokenSavedFlag() {
        _uiState.update { it.copy(hfTokenSaved = false) }
    }

    private fun reload() {
        _uiState.update {
            it.copy(
                apiKeys = apiKeyManager.getExistingKeys(),
                hfToken = apiKeyManager.getHuggingFaceToken()
            )
        }
    }
}
