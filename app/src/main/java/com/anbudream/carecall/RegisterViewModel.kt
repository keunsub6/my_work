package com.anbudream.carecall

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anbudream.carecall.data.RegistrationRepository
import com.anbudream.carecall.data.RegistrationState
import com.anbudream.carecall.health.HealthSensorCoordinator
import com.anbudream.carecall.screening.CallScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RegStatus {
    data object NotRegistered : RegStatus
    data object Working : RegStatus
    data object Registered : RegStatus
    data class Error(val message: String) : RegStatus
}

sealed interface TestPushStatus {
    data object Idle : TestPushStatus
    data object Sending : TestPushStatus
    data object Sent : TestPushStatus
    data class Error(val message: String) : TestPushStatus
}

class RegisterViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Application = app
    private val repository = RegistrationRepository(app)

    // Restore the registered state on launch so testers can re-send a test push anytime.
    private val _status = MutableStateFlow<RegStatus>(
        if (RegistrationState.isRegistered(app)) RegStatus.Registered else RegStatus.NotRegistered
    )
    val status: StateFlow<RegStatus> = _status.asStateFlow()

    private val _testPush = MutableStateFlow<TestPushStatus>(TestPushStatus.Idle)
    val testPush: StateFlow<TestPushStatus> = _testPush.asStateFlow()

    /** phoneNumber must already be acquired (Hint API / permission / manual entry). */
    fun register(phoneNumber: String) {
        _status.value = RegStatus.Working
        viewModelScope.launch {
            repository.register(phoneNumber).fold(
                onSuccess = {
                    RegistrationState.setRegistered(appContext, true)
                    _status.value = RegStatus.Registered
                },
                onFailure = { _status.value = RegStatus.Error(it.message ?: "알 수 없는 오류") },
            )
        }
    }

    /** Ask the server to push a test notification back to this device. */
    fun sendTestPush() {
        _testPush.value = TestPushStatus.Sending
        viewModelScope.launch {
            repository.sendTestPush().fold(
                onSuccess = { _testPush.value = TestPushStatus.Sent },
                onFailure = { _testPush.value = TestPushStatus.Error(it.message ?: "전송 실패") },
            )
        }
    }

    /** Delete this device's registration (server + local flag). */
    fun unregister() {
        viewModelScope.launch {
            repository.unregister().fold(
                onSuccess = {
                    RegistrationState.setRegistered(appContext, false)
                    // [추가] 등록 해제 = 데이터 삭제이므로 건강지표 수집도 즉시 중단합니다.
                    runCatching { HealthSensorCoordinator.disable(appContext) }
                    // [추가] 등록 해제 시 전화 차단 기능도 끕니다(차단 상태로 남지 않도록).
                    runCatching { CallScreenState.setEnabled(appContext, false) }
                    _testPush.value = TestPushStatus.Idle
                    _status.value = RegStatus.NotRegistered
                },
                onFailure = { _status.value = RegStatus.Error(it.message ?: "해제 실패") },
            )
        }
    }

    /** Return to the registration screen (e.g. after an error). */
    fun resetToRegister() {
        _status.value = RegStatus.NotRegistered
    }
}
