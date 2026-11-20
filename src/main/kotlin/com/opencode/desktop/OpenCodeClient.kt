package com.opencode.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit

data class OcModel(val id: String, val name: String, val providerId: String)

/**
 * Talks to a local or remote `opencode serve` instance (same HTTP API as on Android).
 * On PC the binary is system-installed opencode, not libopencode.so.
 */
class OpenCodeClient {
    var baseUrl: String = "http://127.0.0.1:4096"
        private set
    var password: String = ""
        private set

    private var process: Process? = null

    fun running(): Boolean = process?.isAlive == true

    fun authHeader(): String =
        "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray())

    /**
     * Try to start `opencode serve` if found on PATH or in common locations.
     * User can also point the UI at an already running server.
     */
    suspend fun startLocalIfPossible(): String = withContext(Dispatchers.IO) {
        if (ping()) return@withContext "already"
        val bin = findBinary() ?: return@withContext "not_found"
        password = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
        val port = 4096
        baseUrl = "http://127.0.0.1:$port"
        val command = if (isWin() && bin.lowercase().endsWith(".cmd"))
            listOf("cmd", "/c", bin, "serve", "--hostname", "127.0.0.1", "--port", port.toString())
        else listOf(bin, "serve", "--hostname", "127.0.0.1", "--port", port.toString())
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        pb.environment()["OPENCODE_PASSWORD"] = password
        val log = File(System.getProperty("user.home"), ".youcode-desktop/opencode.log")
        log.parentFile.mkdirs()
        pb.redirectOutput(log)
        process = pb.start()
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (!running()) return@withContext "died: ${log.readText().takeLast(400)}"
            if (ping()) return@withContext "started"
            delay(300)
        }
        "timeout"
    }

    fun connectExternal(url: String, pass: String) {
        baseUrl = url.trimEnd('/')
        password = pass
        process = null
    }

    fun stop() {
        process?.destroy()
        runCatching { process?.waitFor(2, TimeUnit.SECONDS) }
        process?.destroyForcibly()
        process = null
    }

    private fun findBinary(): String? {
        val candidates = listOf(
            "opencode",
            File(System.getProperty("user.home"), ".opencode/bin/opencode").absolutePath,
            File(System.getProperty("user.home"), ".opencode/bin/opencode.exe").absolutePath,
            File(System.getProperty("user.home"), ".local/bin/opencode").absolutePath,
            File(System.getenv("APPDATA") ?: "", "npm/opencode.cmd").absolutePath,
            File(System.getenv("LOCALAPPDATA") ?: "", "Programs/opencode/opencode.exe").absolutePath,
            "/usr/local/bin/opencode",
            "/usr/bin/opencode"
        )
        for (c in candidates) {
            if (c == "opencode") {
                val which = runCatching {
                    ProcessBuilder(if (isWin()) listOf("where", "opencode") else listOf("which", "opencode"))
                        .start().inputStream.bufferedReader().readText().trim().lines().firstOrNull()
                }.getOrNull()
                if (!which.isNullOrBlank() && (File(which).canExecute() || (isWin() && File(which).exists()))) return which
            } else if (File(c).canExecute() || (isWin() && File(c).exists())) return c
        }
        return null
    }

    private fun isWin() = System.getProperty("os.name").lowercase().contains("win")

    fun ping(): Boolean = runCatching {
        val c = (URL("$baseUrl/api/info").openConnection() as HttpURLConnection).apply {
            connectTimeout = 800
            readTimeout = 2000
            if (password.isNotBlank()) setRequestProperty("Authorization", authHeader())
        }
        try {
            c.responseCode == 200
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    suspend fun listModels(): List<OcModel> = withContext(Dispatchers.IO) {
        val json = get("api/provider")
        val data = json.optJSONObject("data") ?: json
        val out = mutableListOf<OcModel>()
        val providerArray = data.optJSONArray("providers")
        if (providerArray != null) {
            for (i in 0 until providerArray.length()) {
                val provider = providerArray.optJSONObject(i) ?: continue
                val providerId = provider.optString("id")
                val models = provider.optJSONObject("models") ?: continue
                val keys = models.keys()
                while (keys.hasNext()) {
                    val modelId = keys.next()
                    val model = models.optJSONObject(modelId) ?: continue
                    out += OcModel(modelId, model.optString("name").ifBlank { modelId }, providerId)
                }
            }
        } else {
            // Compatibility with OpenCode versions returning providers as an ID-keyed object.
            val keys = data.keys()
            while (keys.hasNext()) {
                val providerId = keys.next()
                val provider = data.optJSONObject(providerId) ?: continue
                val models = provider.optJSONObject("models") ?: continue
                val modelKeys = models.keys()
                while (modelKeys.hasNext()) {
                    val modelId = modelKeys.next()
                    val model = models.optJSONObject(modelId) ?: continue
                    out += OcModel(modelId, model.optString("name").ifBlank { modelId }, providerId)
                }
            }
        }
        out.distinctBy { "${it.providerId}/${it.id}" }.sortedBy { it.name.lowercase() }
    }

    suspend fun createSession(title: String, model: OcModel): String = withContext(Dispatchers.IO) {
        val modelRef = JSONObject().put("providerID", model.providerId).put("id", model.id)
        val body = JSONObject().put("title", title.ifBlank { "YouCode" }).put("model", modelRef).put("agent", "chat")
        val response = post("api/session", body)
        val id = response.optString("id").ifBlank { response.optJSONObject("data")?.optString("id").orEmpty() }
        require(id.isNotBlank()) { "OpenCode не вернул ID сессии: $response" }
        id
    }

    suspend fun chat(sessionId: String, model: OcModel, prompt: String, onStage: (String) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            onStage("Отправляю запрос…")
            val modelRef = JSONObject().put("providerID", model.providerId).put("id", model.id)
            val body = JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", prompt)))
                .put("model", modelRef)
            // OpenCode's session message endpoint appends to the existing conversation.
            val submitted = post("api/session/$sessionId/message", body, timeout = 300_000)
            extractAssistant(submitted).takeIf { it.isNotBlank() }?.let { return@withContext it }

            onStage("Получаю ответ модели…")
            var previous = ""
            var stableCount = 0
            repeat(120) {
                delay(1500)
                val payload = get("api/session/$sessionId/message")
                val current = extractAssistant(payload)
                if (current.isNotBlank()) {
                    if (current == previous) stableCount++ else {
                        previous = current
                        stableCount = 0
                        onStage("Модель пишет…")
                    }
                    if (stableCount >= 2) return@withContext current
                }
            }
            previous.ifBlank { throw IllegalStateException("OpenCode не вернул ответ за 3 минуты") }
        }

    private fun extractAssistant(payload: JSONObject): String {
        payload.optJSONObject("data")?.let { nested ->
            val nestedText = extractAssistant(nested)
            if (nestedText.isNotBlank()) return nestedText
        }
        val arr = payload.optJSONArray("data") ?: payload.optJSONArray("messages") ?: JSONArray().apply {
            // Some OpenCode API responses return a single message object instead of a list.
            if (payload.has("info") || payload.has("role")) put(payload)
        }
        // Only return the newest assistant message; including older turns duplicates the transcript.
        for (i in arr.length() - 1 downTo 0) {
            val message = arr.optJSONObject(i) ?: continue
            val role = message.optString("role").ifBlank {
                message.optJSONObject("info")?.optString("role").orEmpty()
            }
            if (role != "assistant" && !message.optBoolean("isAssistant")) continue
            val text = StringBuilder()
            val parts = message.optJSONArray("parts") ?: JSONArray()
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.optString("type") == "text") text.append(part.optString("text"))
            }
            if (text.isBlank()) text.append(message.optString("content"))
            if (text.isNotBlank()) return text.toString().trim()
        }
        return ""
    }

    private fun get(path: String): JSONObject {
        val c = (URL("$baseUrl/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 60000
            if (password.isNotBlank()) setRequestProperty("Authorization", authHeader())
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            require(code in 200..299) { "GET $path → $code $text" }
            if (text.trimStart().startsWith("[")) JSONObject().put("data", JSONArray(text)) else JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun post(path: String, body: JSONObject, timeout: Int = 120000): JSONObject {
        val c = (URL("$baseUrl/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = timeout
            doOutput = true
            if (password.isNotBlank()) setRequestProperty("Authorization", authHeader())
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            require(code in 200..299) { "POST $path → $code $text" }
            if (text.isBlank()) JSONObject()
            else if (text.trimStart().startsWith("[")) JSONObject().put("data", JSONArray(text))
            else JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}
