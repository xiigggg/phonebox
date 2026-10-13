package com.example.guardianprobe

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log

/** 打开即尝试启动受 DUMP 保护的目标服务，把结果打到 logcat（tag GuardianProbe） */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent().setComponent(ComponentName("top.xingzhenxi.phonebox", "top.xingzhenxi.phonebox.shizuku.GuardianTargetService"))
        try {
            startForegroundService(intent)
            Log.e(TAG, "RESULT: started (protection FAILED)")
        } catch (e: SecurityException) {
            Log.i(TAG, "RESULT: SecurityException: ${e.message}")
        }
        finish()
    }

    private companion object { const val TAG = "GuardianProbe" }
}
