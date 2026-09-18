package com.kingzcheung.xime.plugin

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 插件热安装入口（仅 debug 构建；`xipm dev` 的主通道）。
 *
 * ```
 * adb shell am start -n com.kingzcheung.xime/.plugin.DebugPluginInstallActivity \
 *   --es path /data/user/0/com.kingzcheung.xime/files/xipm-dev/<plugin>.xipk
 * ```
 *
 * 相比广播：`adb shell am start` 由 shell（特权）发起，**不受应用后台执行限制**，
 * 宿主在后台（如未使用键盘）时也能可靠触发；Activity 为透明无界面主题、
 * 无历史记录，安装完成后立即 finish，用户几乎无感。
 */
class DebugPluginInstallActivity : Activity() {

    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent?.getStringExtra(DebugPluginInstallReceiver.EXTRA_PATH)
        scope.launch {
            try {
                DebugPluginInstaller.install(applicationContext, path)
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}