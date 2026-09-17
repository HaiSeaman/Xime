package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.crypto.CryptoHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import com.kingzcheung.xime.plugin.core.js.sdk.JsHostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsClipboardSyncPluginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockHttpHostApi : HttpHostApi {
        val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
        val responseQueue = ArrayDeque<HttpResponse>()
        var lastErrorMsg: String? = null

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMillis: Int?
        ): HttpResponse? {
            requests.add(Triple(method, url, headers))
            return responseQueue.removeFirstOrNull()
        }

        override fun lastError(): String? = lastErrorMsg
    }

    private class MockCryptoHostApi : CryptoHostApi {
        override fun sha256(data: ByteArray): ByteArray = ByteArray(0)
        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(0)
        override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = ByteArray(20)
        override fun hex(data: ByteArray): String = ""
        override fun base64(data: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(data)
        override fun utcTime(format: String): String = ""
        override fun epochSeconds(): Long = 1767225600
    }

    private class DebugHostApi(private val store: PluginConfigStore) : JsHostApi {
        override val sdkVersion = "0.1.0"
        override fun log(message: String) { System.out.println("JS_LOG: $message") }
        override fun logError(message: String) { System.err.println("JS_ERROR: $message") }
        override fun configGet(key: String) = store.get(key)
        override fun configSet(key: String, value: String) { store.set(key, value) }
        override fun configRemove(key: String) { store.remove(key) }
        override fun configKeys(): Set<String> = store.keys()
        override fun resourcePath(name: String) = null
        override fun resourceList(dir: String) = emptyList<String>()
        override fun uuid() = "uuid"
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
            val candidate = File(dir, "build/plugin-js/ximed-clipboard-sync/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/ximed-clipboard-sync/main.js，" +
                "请先运行：cd tools/xime-plugin && cargo run -- build ../../plugins/ximed-clipboard-sync --out ../../build/plugin-js"
        )
    }

    private fun newRuntime(store: PluginConfigStore, http: MockHttpHostApi): JsScriptRuntime {
        val runtime = JsScriptRuntime(
            "js-ximed-clipboard-sync",
            writePlugin(),
            "main.js",
            store,
            hostApi = DebugHostApi(store),
            httpHostApi = http,
            cryptoHostApi = MockCryptoHostApi()
        )
        assertTrue("main.js 应能加载", runtime.load())
        return runtime
    }

    private fun profileMap(text: String, hash: String): Map<String, Any?> = mapOf(
        "type" to "text",
        "hash" to hash,
        "text" to text,
        "has_data" to false,
        "size" to text.length.toDouble()
    )

    @Test
    fun `main js loads and exposes sync contract`() {
        val runtime = newRuntime(InMemoryConfigStore(), MockHttpHostApi())
        val schema = runtime.call("getSettingsSchema") as? List<*>
        assertTrue("应导出 getSettingsSchema", schema != null)
        assertEquals(4, schema?.size)
    }

    @Test
    fun `push sends PUT to ximed endpoint with basic auth`() {
        val store = InMemoryConfigStore()
        store.set("serverUrl", "https://192.168.1.50:8080")
        store.set("username", "alice")
        store.set("password", "secret")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200))
        val runtime = newRuntime(store, http)

        val ok = runtime.call("push", profileMap("hello", "abc")) as? Boolean

        assertTrue("push 应成功", ok == true)
        assertEquals(1, http.requests.size)
        val (method, url, headers) = http.requests[0]
        assertEquals("PUT", method)
        assertEquals("https://192.168.1.50:8080/api/clipboard", url)
        val expectedAuth = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("alice:secret".toByteArray())
        assertEquals(expectedAuth, headers["Authorization"])
    }

    @Test
    fun `pull returns profile on 200 and caches etag`() {
        val store = InMemoryConfigStore()
        store.set("serverUrl", "https://192.168.1.50:8080")
        val http = MockHttpHostApi()
        val profileJson = """{"type":"text","hash":"abc123","text":"远端内容","has_data":false,"data_name":null,"size":12,"source":"desktop"}"""
        http.responseQueue.addLast(HttpResponse(200, mapOf("ETag" to "etag-1"), profileJson.toByteArray()))
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        val map = (result as? Map<*, *>)
        assertTrue("pull 应返回对象", map != null)
        assertEquals("远端内容", map?.get("text")?.toString())
        assertEquals("abc123", map?.get("hash")?.toString())
        assertEquals("etag-1", store.get("lastEtag"))
    }

    @Test
    fun `pull returns null on 304`() {
        val store = InMemoryConfigStore()
        store.set("serverUrl", "https://192.168.1.50:8080")
        store.set("lastEtag", "etag-1")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(304))
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        assertNull("304 应返回 null", result)
        assertEquals(1, http.requests.size)
        val headers = http.requests[0].third
        assertEquals("etag-1", headers["If-None-Match"])
    }

    @Test
    fun `testConnection reports auth failure`() {
        val store = InMemoryConfigStore()
        store.set("serverUrl", "https://192.168.1.50:8080")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(401))
        val runtime = newRuntime(store, http)

        val error = runtime.call("testConnection")?.toString()

        assertTrue("应报告认证失败: $error", error.orEmpty().contains("认证失败"))
    }

    @Test
    fun `testConnection reports missing config`() {
        val runtime = newRuntime(InMemoryConfigStore(), MockHttpHostApi())
        val error = runtime.call("testConnection")?.toString()
        assertTrue("未配置时应报告错误: $error", error.orEmpty().contains("未配置"))
    }

}