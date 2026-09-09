package com.flowvpn.app.ui.screens.subscriptions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowvpn.app.FlowVpnApplication
import com.flowvpn.core.model.SubscriptionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SubscriptionsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as FlowVpnApplication).container.subscriptionRepository

    val subscriptions: StateFlow<List<SubscriptionInfo>> = repository.getAllSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun addSubscription(url: String, name: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                repository.addSubscription(url.trim(), name?.trim())
            } catch (e: Exception) {
                Timber.e(e, "Ошибка добавления подписки")
                _errorMessage.value = e.message ?: "Не удалось загрузить подписку"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateSubscription(id: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                repository.updateSubscription(id)
            } catch (e: Exception) {
                Timber.e(e, "Ошибка обновления подписки")
                _errorMessage.value = e.message ?: "Ошибка обновления"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateAll() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                repository.updateAllSubscriptions()
            } catch (e: Exception) {
                Timber.e(e, "Ошибка массового обновления")
                _errorMessage.value = e.message ?: "Ошибка обновления"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteSubscription(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteSubscription(id)
            } catch (e: Exception) {
                Timber.e(e, "Ошибка удаления подписки")
                _errorMessage.value = e.message ?: "Не удалось удалить"
            }
        }
    }

    fun importFromText(content: String, onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val count = repository.importFromText(content)
                onComplete(count)
                if (count == 0) {
                    _errorMessage.value = "Серверы не найдены в тексте"
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка импорта")
                _errorMessage.value = e.message ?: "Ошибка импорта"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    companion object {
        fun formatBytes(bytes: Long?): String {
            if (bytes == null || bytes <= 0L) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var size = bytes.toDouble()
            var unitIndex = 0
            while (size >= 1024 && unitIndex < units.size - 1) {
                size /= 1024
                unitIndex++
            }
            return String.format(Locale.US, "%.1f %s", size, units[unitIndex])
        }

        fun formatExpiry(timestampSec: Long?): String {
            if (timestampSec == null || timestampSec <= 0L) return "Безлимитно"
            val date = Date(timestampSec * 1000)
            val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
            return sdf.format(date)
        }
    }
}
