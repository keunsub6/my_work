package com.anbudream.carecall.ui

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anbudream.carecall.BuildConfig
import com.anbudream.carecall.RegStatus
import com.anbudream.carecall.RegisterViewModel
import com.anbudream.carecall.TestPushStatus
import com.anbudream.carecall.data.PhoneNumberProvider
import com.anbudream.carecall.health.HealthFeature
import com.anbudream.carecall.health.HealthSensorCoordinator
import com.anbudream.carecall.screening.CallScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RegisterScreen(viewModel: RegisterViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val testPush by viewModel.testPush.collectAsStateWithLifecycle()

    var showConsent by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }
    var showHealthRationale by remember { mutableStateOf(false) }
    var screeningOn by remember { mutableStateOf(CallScreenState.isEnabled(context)) }

    // Tier 2 (permission) launcher is declared first so Tier 1 can fall through to it.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val read = if (granted) readPhoneSafely(context) else null
        if (read != null) viewModel.register(read) else showManualEntry = true
    }

    // Tier 1 (Phone Number Hint API) launcher.
    val hintLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val phone = PhoneNumberProvider.parseHintResult(context, result.data)
        when {
            phone != null -> viewModel.register(phone)
            hasPhonePermission(context) -> {
                val read = readPhoneSafely(context)
                if (read != null) viewModel.register(read) else showManualEntry = true
            }
            else -> permissionLauncher.launch(Manifest.permission.READ_PHONE_NUMBERS)
        }
    }

    // Runs after the user gives consent: tries each tier in order.
    fun startAcquisition() {
        scope.launch {
            val pendingIntent = PhoneNumberProvider.getHintPendingIntent(context)
            when {
                pendingIntent != null ->
                    hintLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                hasPhonePermission(context) -> {
                    val read = readPhoneSafely(context)
                    if (read != null) viewModel.register(read) else showManualEntry = true
                }
                else -> permissionLauncher.launch(Manifest.permission.READ_PHONE_NUMBERS)
            }
        }
    }

    // ───────── [추가] 건강지표 수집 권한 (등록 완료 뒤 체이닝) ─────────
    val activityPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        HealthSensorCoordinator.markActivityPermissionAsked(context)
        if (granted) {
            scope.launch { withContext(Dispatchers.IO) { HealthSensorCoordinator.initialize(context) } }
        }
        // 거부해도 등록 상태는 그대로 유지합니다. 사유는 서버 수집 트리거 시 보고됩니다.
    }

    // 등록이 끝난 뒤에만 동작합니다. 기존 전화번호 취득 3단계 플로우와 겹치지 않습니다.
    // 이미 등록을 마친 기존 사용자도 앱을 열면 이 지점에서 1회 안내를 받습니다.
    LaunchedEffect(status) {
        // [추가] 사용자가 시스템 설정에서 통화 스크리닝 앱을 바꿨을 수 있으므로 스위치를 실제 상태와 맞춥니다.
        if (CallScreenState.isSupported) {
            val held = runCatching {
                val rm = context.getSystemService(RoleManager::class.java)
                rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                    rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            }.getOrDefault(false)
            if (!held && CallScreenState.isEnabled(context)) CallScreenState.setEnabled(context, false)
            screeningOn = held && CallScreenState.isEnabled(context)
        }

        if (status !is RegStatus.Registered || !HealthFeature.isSupported) return@LaunchedEffect
        if (HealthSensorCoordinator.hasActivityPermission(context)) {
            withContext(Dispatchers.IO) { HealthSensorCoordinator.initialize(context) }
        } else if (HealthSensorCoordinator.shouldAskActivityPermission(context)) {
            showHealthRationale = true
        }
    }

    // ───────── [추가] 안부 통화 중 전화 차단 (사용자가 직접 켤 때만 역할 요청) ─────────
    val screeningRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        CallScreenState.setEnabled(context, granted)
        screeningOn = granted
    }

    fun onScreeningToggle(wantOn: Boolean) {
        if (!wantOn) {
            CallScreenState.setEnabled(context, false)
            screeningOn = false
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return
        if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            CallScreenState.setEnabled(context, true)
            screeningOn = true
            return
        }
        runCatching {
            screeningRoleLauncher.launch(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            )
        }
    }

    // Make sure notifications can actually be shown (Android 13+) before sending a test push.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.sendTestPush()
    }

    fun onTestPushClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission(context)
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.sendTestPush()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("전화번호 등록", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "푸시 알림을 받기 위해 해시된 전화번호와 기기 토큰을 서버에 등록합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(32.dp))

        when (val s = status) {
            RegStatus.Working -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("등록 중…")
            }
            RegStatus.Registered -> {
                Text("✅ 등록 완료")
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onTestPushClick() },
                    enabled = testPush != TestPushStatus.Sending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (testPush == TestPushStatus.Sending) "전송 중…" else "테스트 알림 받기")
                }
                when (val t = testPush) {
                    TestPushStatus.Sent -> {
                        Spacer(Modifier.height(8.dp))
                        Text("📨 알림을 보냈어요. 잠시 후 확인해 주세요.")
                    }
                    is TestPushStatus.Error -> {
                        Spacer(Modifier.height(8.dp))
                        Text("⚠ ${t.message}", color = MaterialTheme.colorScheme.error)
                    }
                    else -> {}
                }
                if (CallScreenState.isSupported) {
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("안부 통화 중 전화 받지 않기")
                        Switch(checked = screeningOn, onCheckedChange = { onScreeningToggle(it) })
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (screeningOn) {
                            "안부 통화가 진행되는 동안에만 걸려오는 전화를 받지 않습니다. " +
                                "통화가 끝나면 자동으로 원래대로 돌아가며, 받지 못한 전화는 " +
                                "통화기록과 부재중 알림에 남습니다."
                        } else {
                            "켜면 안부 통화가 끊기지 않도록, 통화 중에 걸려오는 전화를 잠시 받지 않습니다. " +
                                "이 기능을 쓰려면 이 앱을 통화 스크리닝 앱으로 지정해야 합니다."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // [추가] 담당자 설치 마무리 체크리스트.
                // 켜야 할 항목이 하나도 없으면 스스로 아무것도 그리지 않으므로,
                // 평소 어르신 화면에는 나타나지 않습니다.
                Spacer(Modifier.height(24.dp))
                SetupChecklist()

                Spacer(Modifier.height(24.dp))
                TextButton(onClick = { viewModel.unregister() }) {
                    Text("등록 해제 (데이터 삭제)")
                }
            }
            is RegStatus.Error -> {
                Text("⚠ ${s.message}", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.resetToRegister(); showConsent = true }) {
                    Text("다시 시도")
                }
            }
            RegStatus.NotRegistered -> {
                Button(onClick = { showConsent = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("등록하기")
                }
            }
        }
    }

    if (showConsent) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("전화번호 수집 동의") },
            text = {
                Column {
                    Text(
                        "안부 알림을 보낼 대상을 식별하기 위해 전화번호와 이 기기의 알림 토큰을 수집합니다.\n\n" +
                            "• 사용 목적: 푸시 알림 발송(대상 식별)에만 사용하며, 광고·분석에는 사용하지 않습니다.\n" +
                            "• 전송·저장: 번호는 암호화 통신(HTTPS)으로 전송되고, 서버에는 원문이 아닌 복원 불가능한 해시로만 저장됩니다. 기기에는 저장되지 않습니다.\n" +
                            "• 권한: 번호 자동 입력이 안 되면 전화번호 읽기 권한을 요청할 수 있으며, 직접 입력으로 진행할 수도 있습니다.\n" +
                            "• 건강지표: 등록 후 걸음 수와 활동/좌식 시간을 하루 단위로 집계해 안부 확인에 활용합니다. 별도 권한 동의가 필요하며, 동의하지 않아도 안부 알림은 그대로 이용할 수 있습니다.v8"
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        contentPadding = PaddingValues(0.dp),
                        onClick = { uriHandler.openUri(BuildConfig.PRIVACY_POLICY_URL) },
                    ) { Text("개인정보처리방침 보기") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showConsent = false
                    startAcquisition()
                }) { Text("동의하고 등록") }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) { Text("취소") }
            },
        )
    }

    if (showHealthRationale) {
        AlertDialog(
            onDismissRequest = {
                showHealthRationale = false
                HealthSensorCoordinator.markActivityPermissionAsked(context)
            },
            title = { Text("활동 정보 수집 동의") },
            text = {
                Text(
                    "평소와 다른 활동 변화를 살펴 안부를 확인하는 데 사용합니다.\n\n" +
                        "• 수집 항목: 걸음 수, 활동/좌식 시간 (하루 단위 요약)\n" +
                        "• 위치·심박·통화 내용은 수집하지 않습니다.\n" +
                        "• 동의하지 않아도 안부 알림 기능은 그대로 이용할 수 있습니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showHealthRationale = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        activityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                }) { Text("동의") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showHealthRationale = false
                    HealthSensorCoordinator.markActivityPermissionAsked(context)
                }) { Text("나중에") }
            },
        )
    }

    if (showManualEntry) {
        ManualEntryDialog(
            onConfirm = { phone ->
                showManualEntry = false
                viewModel.register(phone)
            },
            onDismiss = { showManualEntry = false },
        )
    }
}

@Composable
private fun ManualEntryDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val normalized = input.filter { it.isDigit() || it == '+' }
    val isValid = Regex("^\\+?[0-9]{7,15}$").matches(normalized)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("전화번호 직접 입력") },
        text = {
            Column {
                Text("자동으로 번호를 가져오지 못했습니다. 휴대폰 번호를 입력해 주세요. 예: 01012345678")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    isError = input.isNotEmpty() && !isValid,
                    label = { Text("전화번호") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = isValid, onClick = { onConfirm(normalized) }) { Text("등록") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

private fun hasPhonePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_PHONE_NUMBERS
    ) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED

private fun readPhoneSafely(context: Context): String? =
    if (hasPhonePermission(context)) PhoneNumberProvider.readFromTelephony(context) else null
