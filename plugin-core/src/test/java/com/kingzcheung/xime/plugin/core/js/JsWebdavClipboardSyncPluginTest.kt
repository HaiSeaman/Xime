package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.crypto.CryptoHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import com.kingzcheung.xime.plugin.core.js.sdk.JsHostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsWebdavClipboardSyncPluginTest {

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
        val requestBodies = mutableListOf<String>()
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
            requestBodies.add(body?.toString(Charsets.UTF_8) ?: "")
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
            val candidate = File(dir, "build/plugin-js/webdav-clipboard-sync/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 build/plugin-js/webdav-clipboard-sync/main.js，" +
                "请先运行：cd tools/xime-plugin && cargo run -- build ../../plugins/webdav-clipboard-sync --out ../../build/plugin-js"
        )
    }

    private fun newRuntime(store: PluginConfigStore, http: MockHttpHostApi): JsScriptRuntime {
        val runtime = JsScriptRuntime(
            "js-webdav-clipboard-sync",
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
        assertEquals(5, schema?.size)
    }

    @Test
    fun `push sends PUT to webdav clipboard json with basic auth`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("username", "alice")
        store.set("password", "secret")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(201))
        val runtime = newRuntime(store, http)

        val ok = runtime.call("push", profileMap("hello", "abc")) as? Boolean

        assertTrue("push 应成功", ok == true)
        assertEquals(1, http.requests.size)
        val (method, url, headers) = http.requests[0]
        assertEquals("PUT", method)
        assertEquals("https://192.168.1.50:8080/dav/clipboard/current.json", url)
        val expectedAuth = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("alice:secret".toByteArray())
        assertEquals(expectedAuth, headers["Authorization"])
    }

    @Test
    fun `push creates missing directories via MKCOL on 409 then retries`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("remotePath", "xime")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(409))
        http.responseQueue.addLast(HttpResponse(405))
        http.responseQueue.addLast(HttpResponse(201))
        http.responseQueue.addLast(HttpResponse(201))
        val runtime = newRuntime(store, http)

        val ok = runtime.call("push", profileMap("hello", "abc")) as? Boolean

        assertTrue("push 应成功", ok == true)
        assertEquals(4, http.requests.size)
        val methods = http.requests.map { it.first }
        assertEquals(listOf("PUT", "MKCOL", "MKCOL", "PUT"), methods)
        val mkcolUrls = http.requests.filter { it.first == "MKCOL" }.map { it.second }
        assertEquals(
            listOf(
                "https://192.168.1.50:8080/dav/xime",
                "https://192.168.1.50:8080/dav/xime/clipboard"
            ),
            mkcolUrls
        )
    }

    @Test
    fun `push creates missing directories via MKCOL on 404 then retries`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("remotePath", "xime")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(404))
        http.responseQueue.addLast(HttpResponse(405))
        http.responseQueue.addLast(HttpResponse(201))
        http.responseQueue.addLast(HttpResponse(201))
        val runtime = newRuntime(store, http)

        val ok = runtime.call("push", profileMap("hello", "abc")) as? Boolean

        assertTrue("push 应成功", ok == true)
        val methods = http.requests.map { it.first }
        assertEquals(listOf("PUT", "MKCOL", "MKCOL", "PUT"), methods)
    }

    @Test
    fun `push honors remotePath in url`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("remotePath", "xime")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(201))
        val runtime = newRuntime(store, http)

        val ok = runtime.call("push", profileMap("hello", "abc")) as? Boolean

        assertTrue("push 应成功", ok == true)
        assertEquals(1, http.requests.size)
        val (method, url) = http.requests[0]
        assertEquals("PUT", method)
        assertEquals("https://192.168.1.50:8080/dav/xime/clipboard/current.json", url)
    }

    @Test
    fun `push sends JSON profile body`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(201))
        val runtime = newRuntime(store, http)

        val ok = runtime.call("push", profileMap("hello", "abc")) as? Boolean

        assertTrue("push 应成功", ok == true)
        val body = http.requestBodies[0]
        assertTrue("body 应为 JSON", body.startsWith("{"))
        assertTrue("body 应含 text", body.contains("\"hello\""))
        assertTrue("body 应含 hash", body.contains("\"abc\""))
    }

    @Test
    fun `pull returns profile on 200 json and caches etag`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        val profileJson = """{"type":"text","hash":"abc123","text":"远端内容","has_data":false,"data_name":null,"size":12,"source":"desktop"}"""
        http.responseQueue.addLast(
            HttpResponse(200, mapOf("ETag" to "webdav-etag-1"), profileJson.toByteArray())
        )
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        val map = (result as? Map<*, *>)
        assertTrue("pull 应返回对象", map != null)
        assertEquals("远端内容", map?.get("text")?.toString())
        assertEquals("abc123", map?.get("hash")?.toString())
        assertEquals("desktop", map?.get("source")?.toString())
        assertEquals("webdav-etag-1", store.get("lastEtag"))
    }

    @Test
    fun `pull falls back to plain text for legacy file`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200, mapOf("ETag" to "etag-2"), "旧版纯文本".toByteArray()))
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        val map = (result as? Map<*, *>)
        assertTrue("pull 应返回对象", map != null)
        assertEquals("旧版纯文本", map?.get("text")?.toString())
    }

    @Test
    fun `pull returns null on 304`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        store.set("lastEtag", "webdav-etag-1")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(304))
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        assertNull("304 应返回 null", result)
        assertEquals(1, http.requests.size)
        val headers = http.requests[0].third
        assertEquals("webdav-etag-1", headers["If-None-Match"])
    }

    @Test
    fun `pull returns null on 404`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(404))
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        assertNull("404 应返回 null", result)
    }

    @Test
    fun `testConnection uses PROPFIND on clipboard directory`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(207))
        val runtime = newRuntime(store, http)

        val error = runtime.call("testConnection")?.toString()

        assertFalse("207 视为连接成功: $error", error.orEmpty().contains("失败"))
        assertEquals(1, http.requests.size)
        val (method, url, headers) = http.requests[0]
        assertEquals("PROPFIND", method)
        assertEquals("https://192.168.1.50:8080/dav/clipboard", url)
        assertEquals("0", headers["Depth"])
    }

    @Test
    fun `testConnection reports auth failure`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
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

    @Test
    fun `testConnection succeeds on 404 (server reachable, file not yet created)`() {
        val store = InMemoryConfigStore()
        store.set("davUrl", "https://192.168.1.50:8080/dav/")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(404))
        val runtime = newRuntime(store, http)

        val error = runtime.call("testConnection")?.toString()

        assertFalse("404 视为连接成功", error.orEmpty().contains("失败"))
    }

    @Test
    fun `push returns false when url not configured`() {
        val runtime = newRuntime(InMemoryConfigStore(), MockHttpHostApi())
        val ok = runtime.call("push", profileMap("hello", "abc")) as? Boolean
        assertFalse("未配置时应失败", ok == true)
    }

    @Test
    fun `pull returns null when url not configured`() {
        val runtime = newRuntime(InMemoryConfigStore(), MockHttpHostApi())
        val result = runtime.call("pull")
        assertNull("未配置时应返回 null", result)
    }
}
