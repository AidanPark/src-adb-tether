package com.adbtether

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.adbtether.databinding.ActivityMainBinding
import com.adbtether.socks.ServerState
import com.adbtether.socks.SocksConfig
import com.adbtether.socks.formatBytes
import kotlinx.coroutines.launch

/**
 * 최소 UI: 시작/정지 토글 + 상태 표시.
 * 유일한 런타임 권한(POST_NOTIFICATIONS)은 시작 시 한 번 OS 다이얼로그로 요청한다.
 * 거부해도 서비스는 그대로 시작한다(알림만 숨겨질 뿐 프록시는 동작).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val config = SocksConfig()

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startProxy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.detailText.text = "${config.bindAddress}:${config.listenPort}"
        binding.toggleButton.setOnClickListener { onToggle() }
        binding.batteryButton.setOnClickListener { requestIgnoreBatteryOptimization() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProxyService.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 돌아왔을 때 예외 상태를 다시 반영(예외 완료 시 버튼 숨김).
        refreshBatteryButton()
    }

    /** 배터리 최적화 예외가 안 돼 있을 때만 안내 버튼을 노출한다. */
    private fun refreshBatteryButton() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        binding.batteryButton.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    /** OS 다이얼로그로 배터리 최적화 예외를 요청. 장시간(화면 꺼짐 포함) 실행 안정성 확보용. */
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(direct) }.onFailure {
            // 일부 기기에 위 액션이 없으면 목록 설정 화면으로 폴백.
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun onToggle() {
        if (ProxyService.state.value.running) {
            ProxyService.stop(this)
        } else {
            ensureNotificationPermission()
        }
    }

    private fun ensureNotificationPermission() {
        val needsRequest = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsRequest) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startProxy()
        }
    }

    private fun startProxy() = ProxyService.start(this)

    private fun render(state: ServerState) {
        binding.statusText.text = if (state.running) "실행중" else "정지됨"
        binding.statsText.text = if (state.running) {
            val cell = if (state.cellularAvailable) "셀룰러 OK" else "셀룰러 대기"
            "연결 ${state.activeConnections} · 누적 ${state.totalConnections}\n" +
                "↑ ${formatBytes(state.bytesUp)}   ↓ ${formatBytes(state.bytesDown)}\n$cell"
        } else {
            ""
        }
        binding.toggleButton.text = if (state.running) "정지" else "시작"
    }
}
