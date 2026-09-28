package com.anbudream.carecall.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 돌봄센터 담당자용 "설치 마무리" 체크리스트.
 *
 * 왜 필요한가:
 *   어르신은 설정 앱에 들어가 권한을 켜지 못하십니다. 설치는 담당자가 해주는데,
 *   무엇을 켜야 하는지 알 방법이 없어 빠뜨리면 현장에서 기능이 조용히 동작하지
 *   않습니다. 이 화면은 "무엇이 남았는지"를 눈에 보이게 하고 설정 화면으로
 *   바로 데려다 줍니다.
 *
 * 설계 원칙:
 *   · 전부 켜져 있으면 화면에서 통째로 사라집니다. 평소 어르신 눈에 띄지 않게.
 *   · 각 항목은 독립입니다. 하나를 건너뛰어도 나머지는 동작합니다.
 *   · 설정에서 돌아오면 자동으로 상태를 다시 읽습니다(ON_RESUME).
 *   · 기존 등록·통화·건강지표 로직을 일절 참조하지 않습니다.
 *
 * 항목:
 *   1) 카메라 — 지금 필요합니다. 이게 없으면 어르신이 잠금화면에서
 *      권한 요청 다이얼로그를 마주하게 되어 사실상 사용이 불가능합니다.
 *   2) 다른 앱 위에 표시 — 2단계용. 권한을 아직 선언하지 않았다면
 *      자동으로 숨겨지므로 지금 넣어 두어도 무해합니다.
 *   3) 배터리 최적화 제외 — 2단계용. 위와 같습니다.
 */
@Composable
fun SetupChecklist(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // LocalLifecycleOwner 는 Compose/lifecycle 버전에 따라 패키지가 달라
    // Activity 의 lifecycle 을 직접 씁니다(버전 무관).
    val activity = context as? ComponentActivity

    // 설정 화면에 다녀오면 값이 바뀌므로, 화면이 다시 보일 때마다 새로 읽습니다.
    var refreshTick by remember { mutableStateOf(0) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    var cameraAsked by remember { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { cameraAsked = true; refreshTick++ }

    // refreshTick 을 key 로 두어야 설정에서 돌아왔을 때 다시 계산됩니다.
    val state = remember(refreshTick) { readSetupState(context) }

    // 전부 끝났으면 아무것도 그리지 않습니다.
    if (state.allDone) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("설치 마무리", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "어르신이 직접 켜기 어려운 항목입니다. 설치하시는 분이 지금 켜 주세요.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            if (!state.hasCamera) {
                ChecklistRow(
                    title = "카메라 사용 허용",
                    detail = "글씨를 읽어드릴 때 씁니다. 미리 켜 두지 않으면 어르신이 " +
                        "잠금화면에서 허용 창을 마주하게 됩니다.",
                    buttonText = if (cameraAsked) "설정 열기" else "허용하기",
                ) {
                    // 한 번 거부당한 뒤에는 다이얼로그가 다시 뜨지 않으므로 설정으로 보냅니다.
                    if (cameraAsked) openAppSettings(context)
                    else cameraLauncher.launch(Manifest.permission.CAMERA)
                }
            }

            if (state.overlayDeclared && !state.hasOverlay) {
                ChecklistRow(
                    title = "다른 앱 위에 표시",
                    detail = "안부 통화 중에 카메라 화면이 바로 열리게 합니다. " +
                        "꺼져 있으면 어르신이 알림을 직접 눌러야 합니다.",
                    buttonText = "설정 열기",
                ) { openOverlaySettings(context) }
            }

            if (state.batteryDeclared && !state.batteryOk) {
                ChecklistRow(
                    title = "배터리 최적화 제외",
                    detail = "휴대폰을 오래 두었을 때도 안부 요청이 제때 도착하게 합니다.",
                    buttonText = "설정 열기",
                ) { openBatterySettings(context) }
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    title: String,
    detail: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = onClick) { Text(buttonText) }
        }
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

/* ───────────────────────── 상태 조회 ───────────────────────── */

private class SetupState(
    val hasCamera: Boolean,
    val overlayDeclared: Boolean,
    val hasOverlay: Boolean,
    val batteryDeclared: Boolean,
    val batteryOk: Boolean,
) {
    val allDone: Boolean get() = hasCamera && hasOverlay && batteryOk
}

/**
 * 아직 매니페스트에 선언하지 않은 권한은 '완료'로 취급합니다.
 * 덕분에 이 화면을 1단계에 넣어 두어도 2단계 항목이 나타나지 않고,
 * 2단계에서 권한을 선언하는 순간 자동으로 나타납니다.
 */
private fun readSetupState(context: Context): SetupState {
    val overlayDeclared = isPermissionDeclared(context, Manifest.permission.SYSTEM_ALERT_WINDOW)
    val batteryDeclared =
        isPermissionDeclared(context, Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    return SetupState(
        hasCamera = hasCameraPermission(context),
        overlayDeclared = overlayDeclared,
        hasOverlay = !overlayDeclared || canDrawOverlays(context),
        batteryDeclared = batteryDeclared,
        batteryOk = !batteryDeclared || isIgnoringBatteryOptimizations(context),
    )
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 매니페스트에 그 권한이 선언되어 있는지 확인합니다.
 *
 * 이 함수 덕분에 이 화면을 지금(1단계) 넣어 두어도 안전합니다. 아직 선언하지
 * 않은 2단계 권한은 항목 자체가 나타나지 않고, 2단계에서 매니페스트에
 * 추가하는 순간 자동으로 나타납니다. 화면 코드를 다시 고칠 필요가 없습니다.
 */
private fun isPermissionDeclared(context: Context, permission: String): Boolean = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    }
    info.requestedPermissions?.contains(permission) == true
}.getOrDefault(false)

private fun canDrawOverlays(context: Context) =
    runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

private fun isIgnoringBatteryOptimizations(context: Context) = runCatching {
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
}.getOrDefault(false)

/* ───────────────────────── 설정 화면 이동 ─────────────────────────
 * 기기마다 해당 설정 화면이 없을 수 있어 전부 실패 시 앱 정보 화면으로 폴백합니다.
 * 담당자가 "버튼을 눌렀는데 아무 일도 없다"를 겪지 않게 하기 위함입니다.
 */

private fun openAppSettings(context: Context) {
    startSafely(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
    )
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.fromParts("package", context.packageName, null)
    )
    if (!startSafely(context, intent)) openAppSettings(context)
}

private fun openBatterySettings(context: Context) {
    // 요청 다이얼로그가 막힌 기기가 있어 목록 화면을 먼저 시도합니다.
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    if (startSafely(context, list)) return
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.fromParts("package", context.packageName, null)
    )
    if (!startSafely(context, direct)) openAppSettings(context)
}

private fun startSafely(context: Context, intent: Intent): Boolean = runCatching {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)
