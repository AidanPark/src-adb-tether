package com.adbtether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 부팅 완료 시, 사용자가 마지막에 "시작" 상태로 뒀다면 프록시 서비스를 자동 재시작한다.
 * 마지막이 "정지"였다면 켜지 않는다(사용자 의도 존중).
 *
 * 참고: 삼성 등 OEM 에서는 배터리 최적화 예외 + '자동 실행 허용'이 되어 있어야 리시버가 뜬다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(ProxyService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ProxyService.KEY_ENABLED, false)) return
        // BOOT_COMPLETED 는 백그라운드 FGS 시작 제한의 예외라 여기서 시작 가능.
        runCatching { ProxyService.start(context) }
    }
}
