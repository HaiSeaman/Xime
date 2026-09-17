package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.api.AsrPluginListener
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.crypto.CryptoHostApi
import com.kingzcheung.xime.plugin.core.js.ws.WsHostApi
import com.kingzcheung.xime.plugin.core.js.ws.WsHostListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 验证 tencent-asr JS 版：腾讯云实时语音识别 V2（WebSocket）的签名鉴权、握手状态机、
 * 音频直发与句子结果解析全部在 JS 承载，宿主仅提供 host.ws / host.crypto 原语。
 *
 * 测试与发布同源：载入 xipm build 产物 build/plugin-js/tencent-asr/main.js。
 */
class JsTencentAsrPluginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockWsHostApi : WsHostApi {
        val sentBinaries = mutableListOf<ByteArray>()
        val sentTexts = mutableListOf<String>()
        var connectedUrl: String? = null
        var hostListener: WsHostListener? = null
        var closed = false

        override fun connect(url: String, headers: Map<String, String>, listener: WsHostListener): Boolean {
            connectedUrl = url
            hostListener = listener
            return true
        }
        override fun sendText(message: String): Boolean { sentTexts.add(message); return true }
        override fun sendBinary(data: ByteArray): Boolean { sentBinaries.add(data); return true }
        override fun close() { closed = true }
        override fun getState(): Int = 2
        override fun lastError(): String? = null
    }

    /** 真实 HMAC-SHA1/Base64（验证签名端到端正确），时间固定便于断言 timestamp/expired。 */
    private class RealHmacCryptoHostApi : CryptoHostApi {
        override fun sha256(data: ByteArray): ByteArray = ByteArray(32)
        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(32)
        override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            return mac.doFinal(data)
        }
        override fun hex(data: ByteArray): String = ""
        override fun base64(data: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(data)
        override fun utcTime(format: String): String = ""
        override fun epochSeconds(): Long = 1767225600
    }

    private class ResultCollector : AsrPluginListener {
        var finalText = ""
        var partialText = ""
        var error: String? = null
        override fun onFinal(text: String) { finalText = text }
        override fun onPartial(text: String) { partialText = text }
        override fun onError(message: String) { error = message }
    }

    /** 载入真实插件产物（xipm build 输出），测试与发布同源。 */
    private fun writePlugin(): File {
        val dir = tmp.newFolder()
        pluginSourceFile().copyTo(File(dir, "main.js"))
        return dir
    }

    private fun pluginSourceFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "build/plugin-js/tencent-asr/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到 build/plugin-js/tencent-asr/main.js，请先运行 xipm build")
    }

    private fun awaitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("等待条件超时", condition())
    }

    private fun newRuntime(store: PluginConfigStore, ws: WsHostApi): JsScriptRuntime {
        val dir = writePlugin()
        return JsScriptRuntime(
            "com.kingzcheung.xime.plugin.tencent_asr",
            dir, "main.js", store,
            wsHostApi = ws,
            cryptoHostApi = RealHmacCryptoHostApi()
        )
    }

    /** 解析连接 URL 的 query 为（已百分号解码的）键值对。 */
    private fun parseQuery(url: String): Map<String, String> {
        val query = url.substringAfter("?", "")
        return query.split("&").filter { it.isNotEmpty() }.associate {
            val key = it.substringBefore("=")
            val value = URLDecoder.decode(it.substringAfter("="), "UTF-8")
            key to value
        }
    }

    @Test
    fun `tencent js plugin owns signature handshake and sentence protocol`() {
        val mock = MockWsHostApi()
        val store = InMemoryConfigStore()
        val runtime = newRuntime(store, mock)
        try {
            assertTrue("main.js 应能加载", runtime.load())

            assertTrue("未配置时不就绪", runtime.call("isConfigured") != true)

            // 设置 schema 非空且包含腾讯三要素
            val schema = (runtime.call("getSettingsSchema") as? List<*>) ?: emptyList<Any?>()
            val schemaMap = schema.map { it as Map<*, *> }
            assertTrue("schema 非空", schema.isNotEmpty())
            val schemaKeys = schemaMap.mapNotNull { it["key"]?.toString() }
            assertTrue(schemaKeys.containsAll(listOf("appId", "secretId", "secretKey", "engineModelType")))
            assertTrue("hotwordList 应可选",
                schemaMap.none { it["key"]?.toString() == "hotwordList" && it["required"] == true })

            val collector = ResultCollector()
            runtime.asrResultCallback = collector

            // 未配置 → start 失败并 emitError
            assertTrue("start 应失败（未配置）", runtime.call("start") != true)
            awaitUntil { collector.error != null }
            assertEquals("未配置 AppID / SecretId / SecretKey，请在插件设置中填写", collector.error)

            // 配置后 start → 连接腾讯域名，query 携带签名参数
            store.set("appId", "1234567890")
            store.set("secretId", "AKIDtest0000")
            store.set("secretKey", "test-secret")
            store.set("hotwordList", "语音|10,ASR|5")
            assertTrue("start 应成功", runtime.call("start") == true)
            val url = mock.connectedUrl!!
            assertTrue("连接地址应为腾讯 ASR 域名",
                url.startsWith("wss://asr.cloud.tencent.com/asr/v2/1234567890?"))

            val query = parseQuery(url)
            assertEquals("16k_zh_en_2.0", query["engine_model_type"])
            assertEquals("AKIDtest0000", query["secretid"])
            assertEquals("1", query["voice_format"])
            assertEquals("1", query["needvad"])
            assertEquals("1767225600", query["timestamp"])
            assertEquals("1767312000", query["expired"])
            assertEquals("语音|10,ASR|5", query["hotword_list"])
            assertTrue("应带 voice_id（UUID）", query["voice_id"]!!.contains("-"))
            val nonce = query["nonce"]!!.toLong()
            assertTrue("nonce 应为 1~1e9 的正整数", nonce in 1..999999999)

            // 签名端到端校验：按解码后参数重建签名原文，重算 HMAC-SHA1+Base64
            val signStr = "asr.cloud.tencent.com/asr/v2/1234567890?" +
                query.filterKeys { it != "signature" }.toSortedMap().entries.joinToString("&") {
                    "${it.key}=${it.value}"
                }
            val expectedSig = RealHmacCryptoHostApi().base64(
                RealHmacCryptoHostApi().hmacSha1("test-secret".toByteArray(), signStr.toByteArray(Charsets.UTF_8))
            )
            assertEquals("signature 应与签名原文重算一致", expectedSig, query["signature"])

            // onWsOpen 槽不发送任何数据（腾讯由服务端先发握手文本帧）
            mock.hostListener?.onOpen()
            Thread.sleep(200)
            assertEquals("onWsOpen 不应发送数据", 0, mock.sentBinaries.size)
            assertEquals(0, mock.sentTexts.size)

            // 握手前音频 → 缓冲
            runtime.call("processAudioChunk", byteArrayOf(1, 2, 3))
            assertEquals("握手前应缓冲音频", 0, mock.sentBinaries.size)

            // 握手成功文本帧 → 补发缓冲音频，之后音频原样直发（二进制 PCM）
            mock.hostListener?.onMessage("""{"code":0,"message":"success","voice_id":"abc"}""")
            awaitUntil { mock.sentBinaries.isNotEmpty() }
            assertEquals("握手成功应补发缓冲音频", 1, mock.sentBinaries.size)
            assertTrue(byteArrayOf(1, 2, 3).contentEquals(mock.sentBinaries[0]))
            runtime.call("processAudioChunk", byteArrayOf(4, 5))
            assertEquals(2, mock.sentBinaries.size)
            assertTrue(byteArrayOf(4, 5).contentEquals(mock.sentBinaries[1]))

            // 句子结果：sentence_type=0 → partial；=1 → final
            mock.hostListener?.onMessage(
                """{"code":0,"voice_id":"abc","sentences":[{"sentence":"实时语音","sentence_type":0,"sentence_id":0,"speaker_id":-1,"start_time":0,"end_time":800}]}"""
            )
            awaitUntil { collector.partialText == "实时语音" }
            assertEquals("不确定句应走 partial", "实时语音", collector.partialText)
            assertEquals("", collector.finalText)
            mock.hostListener?.onMessage(
                """{"code":0,"voice_id":"abc","sentences":[{"sentence":"实时语音识别。","sentence_type":1,"sentence_id":0,"speaker_id":0,"start_time":0,"end_time":2850}]}"""
            )
            awaitUntil { collector.finalText == "实时语音识别。" }
            assertEquals("确定句应走 final", "实时语音识别。", collector.finalText)

            // final=1 → 识别结束，关闭连接
            mock.hostListener?.onMessage("""{"code":0,"voice_id":"abc","final":1}""")
            awaitUntil { mock.closed }
            assertTrue("final 后应关闭连接", mock.closed)

            // 服务端错误帧 → emitError（含错误码）
            mock.hostListener?.onMessage("""{"code":4008,"message":"后台识别服务器音频分片等待超时","voice_id":"abc"}""")
            awaitUntil { (collector.error ?: "").contains("4008") }
            assertTrue("错误应上报", (collector.error ?: "").contains("4008"))
            assertTrue((collector.error ?: "").contains("音频分片等待超时"))

            // stop → 发送 {"type":"end"} 结束通知
            mock.closed = false
            assertTrue(runtime.call("start") == true)
            runtime.call("stop")
            awaitUntil { mock.sentTexts.isNotEmpty() }
            assertEquals("stop 应发送结束通知", 1, mock.sentTexts.size)
            assertEquals("""{"type":"end"}""", mock.sentTexts[0])

            // cancel → 直接关闭
            runtime.call("cancel")
            awaitUntil { mock.closed }
            assertTrue("cancel 应关闭连接", mock.closed)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `handshake failure code closes connection without audio`() {
        val mock = MockWsHostApi()
        val store = InMemoryConfigStore()
        store.set("appId", "1234567890")
        store.set("secretId", "AKIDtest0000")
        store.set("secretKey", "test-secret")
        val runtime = newRuntime(store, mock)
        try {
            assertTrue(runtime.load())
            val collector = ResultCollector()
            runtime.asrResultCallback = collector

            assertTrue(runtime.call("start") == true)
            mock.hostListener?.onOpen()
            Thread.sleep(200)
            // 鉴权失败握手帧（code 非 0）→ 上报错误并断开，音频不应外发
            mock.hostListener?.onMessage("""{"code":4004,"message":"签名过期","voice_id":"abc"}""")
            awaitUntil { mock.closed && (collector.error ?: "").contains("4004") }
            assertTrue((collector.error ?: "").contains("4004"))
            assertTrue("握手失败应断开", mock.closed)
            runtime.call("processAudioChunk", byteArrayOf(9, 9))
            awaitUntil { mock.sentBinaries.size == 0 }
            assertEquals("断开后音频不应外发", 0, mock.sentBinaries.size)
        } finally {
            runtime.close()
        }
    }
}
