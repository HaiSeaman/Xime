package com.kingzcheung.xime.plugin.core.js

import android.util.Log
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import com.kingzcheung.xime.plugin.core.js.sdk.JsPluginContract
import com.kingzcheung.xime.plugin.core.model.PluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * clipboard_sync 类型 JS 插件的宿主侧适配器：实现 [ClipboardSyncPlugin] 接口。
 *
 * 协议逻辑（WebDAV / S3 / ximed HTTP）全部由插件 JS 用 `host.http` + `host.crypto`
 * 承载，本类只做接口桥接：
 * - push(profile)      → JS `clipboardSync.push(profile)`，profile 字段 camelCase
 * - pull()             → JS `clipboardSync.pull()`，返回 profile 对象（null → 无变更）
 * - testConnection()   → JS `clipboardSync.test()`，返回错误消息（null/空 → 成功）
 */
class JsClipboardSyncPluginAdapter(
    runtime: JsScriptRuntime,
    pluginContext: PluginContext
) : JsPluginAdapter(runtime, pluginContext), ClipboardSyncPlugin {

    override suspend fun push(profile: ClipboardProfile): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = runtime.callAsync(
                JsPluginContract.PATH_CLIPBOARD_PUSH,
                mapOf(
                    "type" to profile.type,
                    "hash" to profile.hash,
                    "text" to profile.text,
                    "hasData" to profile.hasData,
                    "dataName" to profile.dataName,
                    "size" to profile.size,
                    "source" to profile.source
                )
            )
            (JsScriptRuntime.jsToKotlin(result) as? Boolean) ?: false
        } catch (e: Exception) {
            Log.e("JsClipboardSync", "push failed", e)
            false
        }
    }

    override suspend fun pull(): ClipboardProfile? = withContext(Dispatchers.IO) {
        try {
            val result = runtime.callAsync(JsPluginContract.PATH_CLIPBOARD_PULL)
            val map = JsScriptRuntime.jsToKotlin(result) as? Map<*, *> ?: return@withContext null
            val text = map["text"]?.toString()?.takeIf { it.isNotEmpty() } ?: return@withContext null
            val hash = map["hash"]?.toString()?.takeIf { it.isNotEmpty() }
                ?: ClipboardProfile.sha256Hex(text.toByteArray(Charsets.UTF_8))
            ClipboardProfile(
                type = map["type"]?.toString() ?: "text",
                hash = hash,
                text = text,
                hasData = (map["hasData"] as? Boolean) ?: false,
                dataName = map["dataName"]?.toString(),
                size = (map["size"] as? Number)?.toLong() ?: 0,
                source = map["source"]?.toString()
            )
        } catch (e: Exception) {
            Log.e("JsClipboardSync", "pull failed", e)
            null
        }
    }

    override suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        try {
            val result = runtime.callAsync(JsPluginContract.PATH_CLIPBOARD_TEST)
            val v = JsScriptRuntime.jsToKotlin(result)
            when (v) {
                null -> null
                is Boolean -> if (v) null else "连接失败"
                else -> v.toString().takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            e.message ?: "connection test failed"
        }
    }
}