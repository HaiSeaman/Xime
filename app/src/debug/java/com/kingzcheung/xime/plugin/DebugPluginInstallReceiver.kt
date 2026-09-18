package com.kingzcheung.xime.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 插件热安装兼容通道（仅 debug 构建；`xipm dev` 主通道为 [DebugPluginInstallActivity]）。
 *
 * 广播在应用处于后台时可能被系统策略丢弃（"Background execution not allowed"），
 * 仅当前台/未被限制时可投递，故作为 Activity 通道的补充保留。
 */
class DebugPluginInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra(EXTRA_PATH)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                DebugPluginInstaller.install(context.applicationContext, path)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.kingzcheung.xime.action.DEBUG_INSTALL_PLUGIN"
        const val EXTRA_PATH = "path"
    }
}