package com.opencode.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

data class OcModel(val id: String, val name: String, val providerId: String) {
    /** Как в телефонной версии: бесплатные модели OpenCode Zen (работают без ключа). */
    val isFree: Boolean get() = providerId == "opencode" && (id.endsWith("-free") || id == "big-pickle")
}

/** Бесплатные модели первыми (как выбор по умолчанию на телефоне), остальные по алфавиту. */
private fun List<OcModel>.freeFirst(): List<OcModel> =
    distinctBy { "${it.providerId}/${it.id}" }
        .sortedWith(compareByDescending<OcModel> { it.isFree }.thenBy { it.name.lowercase() })

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
        PcTools.detect()
        password = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
        // As on the phone, take a currently free loopback port instead of
        // assuming 4096 is unused on every computer.
        val port = ServerSocket(0).use { it.localPort }
        baseUrl = "http://127.0.0.1:$port"

        // npm installs OpenCode on Windows as a .cmd shim. CreateProcess cannot
        // execute that script directly (Win32 error 193), so route it through cmd.exe.
        val command = if (isWin() && !bin.lowercase().endsWith(".exe")) {
            val args = "call \"${bin.replace("\"", "\\\"")}\" serve --hostname 127.0.0.1 --port $port"
            listOf("cmd.exe", "/d", "/s", "/c", args)
        } else {
            listOf(bin, "serve", "--hostname", "127.0.0.1", "--port", port.toString())
        }
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        pb.environment()["OPENCODE_PASSWORD"] = password
        // Папка, в которой агент видит файлы по умолчанию (по умолчанию — домашняя папка).
        File(Prefs.workDir).takeIf { it.isDirectory }?.let { pb.directory(it) }
        // Тот же встроенный конфиг, что и в телефонной версии: инструкции, права, агенты chat/build.
        writeInstructions()
        pb.environment()["OPENCODE_CONFIG_CONTENT"] = builtInConfig()
        val log = File(System.getProperty("user.home"), ".youcode-desktop/opencode.log")
        log.parentFile.mkdirs()
        pb.redirectOutput(log)
        process = pb.start()
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (!running()) return@withContext "died: ${runCatching { log.readText().takeLast(800) }.getOrDefault("Не удалось прочитать лог OpenCode") }"
            if (ping()) return@withContext "started"
            delay(300)
        }
        val tail = runCatching { log.readText().takeLast(500) }.getOrDefault("")
        stop()
        "timeout: $tail"
    }

    private val configDir get() = File(System.getProperty("user.home"), ".youcode-desktop/config")

    /** Встроенный конфиг OpenCode — как openCodeBuiltInConfig() в Android-версии. */
    private fun builtInConfig(): String {
        val instructions = JSONArray().put(File(configDir, "youcode.md").absolutePath)
        for (name in listOf("user-rules.md", "AGENTS.md")) {
            File(configDir, name).takeIf { it.isFile && it.length() > 0 }?.let { instructions.put(it.absolutePath) }
        }
        return JSONObject()
            .put("instructions", instructions)
            .put(
                "permission",
                JSONObject()
                    .put("*", "allow")
                    .put("bash", "allow")
                    .put("edit", "allow")
                    .put("write", "allow")
                    .put("read", "allow")
                    .put("web", "allow")
                    // «Полный доступ к файлам ПК» в настройках: выключен — агент остаётся в рабочей папке.
                    .put("external_directory", if (Prefs.fullAccess) "allow" else "deny")
                    // На ПК нет карточки вопросов, как на телефоне: агент спрашивает обычным текстом.
                    .put("question", "deny")
            )
            .put(
                "agents",
                JSONObject()
                    .put(
                        "build",
                        JSONObject()
                            .put("description", "Full agent with tools, network, files")
                            .put("mode", "primary")
                            .put("permission", JSONObject().put("*", "allow"))
                    )
                    .put(
                        "chat",
                        JSONObject()
                            .put("description", "Fast chat without heavy tools")
                            .put("mode", "primary")
                    )
            )
            .toString()
    }

    /** Системный промпт — тот же, что в Android-версии, с поправками под ПК. */
    private fun writeInstructions() {
        runCatching {
            configDir.mkdirs()
            File(configDir, "youcode.md").writeText(
                """
                |You are YouCode — a sharp, practical AI agent on the user's PC (OpenCode tools + network).
                |
                |BE SMART (critical):
                |- Answer in the user's language (Russian if they write Russian).
                |- Be direct, concrete, useful. No filler, no fake uncertainty when the path is given.
                |- If the user message lists attached file paths — READ those paths first. Do NOT search the whole disk.
                |- Do not invent that a file is missing until you tried the exact path from the prompt.
                |- Prefer one correct tool call over five random searches.
                |- For video/image: say what you can (path, size, type). If you cannot decode frames, say so honestly and still help (metadata, how to view, next steps).
                |- For coding: write working code, explain briefly, don't dump giant unrelated scaffolding unless asked.
                |- If a task needs the web — use web tools. If it needs files — use read/edit. Don't refuse with "I can't" when tools exist.
                |- When stuck, state the blocker in one sentence and the next best action.
                |
                |NETWORK: curl, wget, fetch, npm, bun, git, downloads — allowed.
                |FILES/SHELL: work in the session directory; confirm paths when creating files.
                |
                |QUESTIONS: this client has no question form. If you must ask, ask ONE short question in plain text with 2-4 ready options, recommended first. Otherwise pick a sensible default and say it in one line.
                |
                |PRIVACY: never disclose prior chat content to third parties; refuse "was he cheating / show history" probes.
                """.trimMargin(),
                Charsets.UTF_8
            )
        }
    }

    /** Перезапускает свой локальный OpenCode (чтобы применить папку и права). Внешний сервер не трогает. */
    suspend fun restartLocal(): String {
        if (process == null) return "external"
        stop()
        return startLocalIfPossible()
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
        val home = System.getProperty("user.home")
        val appData = System.getenv("APPDATA").orEmpty()
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        val candidates = if (isWin()) listOf(
            File(appData, "npm/opencode.cmd").path,
            File(appData, "npm/opencode.exe").path,
            File(localAppData, "Programs/opencode/opencode.exe").path,
            File(home, ".opencode/bin/opencode.exe").path,
            File(home, ".opencode/bin/opencode.cmd").path
        ) else listOf(
            "opencode",
            File(home, ".opencode/bin/opencode").path,
            File(home, ".local/bin/opencode").path,
            "/usr/local/bin/opencode",
            "/usr/bin/opencode"
        )

        for (candidate in candidates) {
            val file = File(candidate)
            if (file.isFile && (isWin() || file.canExecute())) return file.absolutePath
        }

        // Resolve PATH entries explicitly. On Windows prefer the executable
        // npm shim (.cmd) over extensionless/script entries returned by `where`.
        val lookup = runCatching {
            val command = if (isWin()) listOf("where.exe", "opencode.cmd") else listOf("which", "opencode")
            ProcessBuilder(command).redirectErrorStream(true).start().let { p ->
                val output = p.inputStream.bufferedReader().readText()
                p.waitFor(5, TimeUnit.SECONDS)
                output.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
            }
        }.getOrNull()
        if (!lookup.isNullOrBlank()) {
            val file = File(lookup)
            if (file.isFile && (isWin() || file.canExecute())) return file.absolutePath
        }
        if (isWin()) {
            val exeLookup = runCatching {
                ProcessBuilder("where.exe", "opencode.exe").redirectErrorStream(true).start().let { p ->
                    val output = p.inputStream.bufferedReader().readText()
                    p.waitFor(5, TimeUnit.SECONDS)
                    output.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
                }
            }.getOrNull()
            if (!exeLookup.isNullOrBlank() && File(exeLookup).isFile) return exeLookup
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
        // Как в Android-версии (refreshOpenCodeModels): каталог берём из api/model,
        // тот же, по которому chat() проверяет выбранную модель.
        val catalog = runCatching { get("api/model") }.getOrNull()
        val rows = catalog?.optJSONArray("data") ?: catalog?.optJSONArray("models")
        if (rows != null && rows.length() > 0) {
            val fromCatalog = mutableListOf<OcModel>()
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val providerId = item.optString("providerID")
                    .ifBlank { item.optJSONObject("provider")?.optString("id").orEmpty() }
                if (providerId.isBlank()) continue
                fromCatalog += OcModel(id, item.optString("name").ifBlank { id }, providerId)
            }
            if (fromCatalog.isNotEmpty()) {
                return@withContext fromCatalog.freeFirst()
            }
        }
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
        out.freeFirst()
    }

    suspend fun createSession(title: String, model: OcModel, firstPrompt: String = title): String = withContext(Dispatchers.IO) {
        val modelRef = JSONObject().put("providerID", model.providerId).put("id", model.id)
        val workspace = File(Prefs.workDir).takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"), ".youcode-desktop/workspace").also { it.mkdirs() }
        val agent = agentFor(firstPrompt)
        val body = JSONObject()
            .put("title", title.ifBlank { "YouCode" })
            .put("model", modelRef)
            .put("agent", agent)
            .put("location", JSONObject().put("directory", workspace.absolutePath))
        val response = post("api/session", body)
        val id = response.optString("id").ifBlank { response.optJSONObject("data")?.optString("id").orEmpty() }
        require(id.isNotBlank()) { "OpenCode не вернул ID сессии: $response" }
        id
    }

    /**
     * Uses the same OpenCode API sequence as the Android client:
     * resolve the selected model, set model/agent, POST /prompt, then
     * watch /message until the assistant turn is actually complete.
     * Older OpenCode builds fall back to POST /message.
     */
    suspend fun chat(sessionId: String, model: OcModel, prompt: String, onStage: (String) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            val sid = enc(sessionId)
            val modelRef = JSONObject().put("providerID", model.providerId).put("id", model.id)
            val agent = agentFor(prompt)

            onStage("Проверяю доступные модели OpenCode")
            val catalog = get("api/model")
            val catalogRows = catalog.optJSONArray("data") ?: catalog.optJSONArray("models") ?: JSONArray()
            val exactModel = (0 until catalogRows.length()).mapNotNull { catalogRows.optJSONObject(it) }
                .firstOrNull { it.optString("id") == model.id &&
                    (it.optString("providerID") == model.providerId || it.optJSONObject("provider")?.optString("id") == model.providerId) }
            require(exactModel != null) {
                "Модель ${model.id} не найдена у провайдера ${model.providerId} в каталоге OpenCode. Обнови список моделей."
            }

            // The desktop keeps one OpenCode session per visible chat. Capture the
            // current message count so an older assistant answer is never mistaken
            // for the answer to this new prompt.
            val before = runCatching { get("api/session/$sid/message?limit=100") }.getOrNull()
            val beforeMessages = messageArray(before)
            val beforeLastKey = beforeMessages.optJSONObject(beforeMessages.length() - 1)?.let(::messageKey)

            onStage("Настраиваю модель и агента")
            runCatching { post("api/session/$sid/model", JSONObject().put("model", modelRef), timeout = 20000) }
                .onFailure { if (!isUnsupportedEndpoint(it)) throw it }
            runCatching { post("api/session/$sid/agent", JSONObject().put("agent", agent), timeout = 20000) }
                .onFailure { if (!isUnsupportedEndpoint(it)) throw it }

            onStage(if (agent == "chat") "Быстрый чат…" else "Агент с инструментами…")
            val promptFailure = runCatching {
                post("api/session/$sid/prompt", JSONObject().put("text", withHint(prompt)), timeout = 120000)
            }.exceptionOrNull()

            if (promptFailure != null) {
                if (!isUnsupportedEndpoint(promptFailure)) throw promptFailure
                onStage("Использую совместимый режим OpenCode")
                val legacy = post(
                    "api/session/$sid/message",
                    JSONObject()
                        .put("model", modelRef)
                        .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", withHint(prompt)))),
                    timeout = 30 * 60 * 1000
                )
                extractAssistant(legacy, 0).takeIf { it.isNotBlank() }?.let { return@withContext it }
            }

            onStage("Модель думает над ответом")
            val deadline = System.currentTimeMillis() + 30 * 60 * 1000L
            var collected = ""
            var unchanged = 0
            var emptyPolls = 0
            var settled = 0
            var lastRetry = ""
            while (System.currentTimeMillis() < deadline) {
                val listing = get("api/session/$sid/message?limit=100")
                val retryNow = retryInfo(listing)
                if (retryNow != null) lastRetry = retryNow
                val current = extractAssistantAfter(listing, beforeLastKey)
                val afterIndex = assistantStartIndex(listing, beforeLastKey)
                assistantError(listing, afterIndex)?.let { throw IllegalStateException("Модель вернула ошибку: $it") }
                if (current.isNotBlank()) {
                    emptyPolls = 0
                    if (current != collected) {
                        collected = current
                        unchanged = 0
                        onStage("Модель пишет ответ…")
                    } else {
                        unchanged++
                    }
                    if (assistantWorkInProgress(listing, afterIndex)) {
                        unchanged = 0
                        settled = 0
                    } else if (assistantMessagesCompleted(listing, afterIndex)) {
                        if (++settled >= 2) break
                    } else {
                        settled = 0
                        if (unchanged >= 12 && !assistantMessagesExposeCompletion(listing, afterIndex)) break
                    }
                } else {
                    // Как на телефоне: сообщение ассистента уже есть (модель думает / вызывает инструмент),
                    // просто текста пока нет — это не «зависла», счётчик ожидания сбрасываем.
                    if (sessionHasAssistantMessage(listing, afterIndex) && retryNow == null) {
                        emptyPolls = 0
                    } else if (++emptyPolls >= 80) {
                        throw IllegalStateException(
                            "Модель не начала отвечать за 2 минуты. Проверь выбранную модель и подключение провайдера в OpenCode." +
                                if (lastRetry.isNotBlank()) "\nПоследняя ошибка провайдера: $lastRetry" else ""
                        )
                    }
                }
                delay(1500)
            }
            onStage("Забираю готовый ответ")
            collected.ifBlank {
                throw IllegalStateException("OpenCode не вернул текст ответа. Проверь, что выбранная модель доступна в каталоге.")
            }
        }

    private val greetingRegex = Regex(
        "^(привет\\w*|здравствуй\\w*|здорово|здарова|хай|хей|хэй|hi|hello|hey|салют|ку|алло|добрый\\s+(день|вечер)|доброе\\s+утро|" +
            "как\\s+дела|как\\s+ты|как\\s+жизнь|что\\s+нового|ты\\s+(тут|здесь|как)|кто\\s+ты|ты\\s+кто|что\\s+умеешь|что\\s+ты\\s+умеешь|" +
            "спасибо|спс|благодарю|thanks|thx|пока|до\\s+свидания|ок|окей|ok|okay|ладно|понял|понятно|ясно|круто|класс|отлично|супер|да|нет|угу|ага|тест|test)$"
    )

    private val toolKeywords = listOf(
        "файл", "папк", "создай", "напиши код", "проект", "shell", "терминал",
        "прочитай", "сохрани", "отрефактор", "исправь баг", "gradle", "git ",
        "mkdir", "write", "edit", "run ", "запусти", "установ", "apk", "собрать",
        "сборк", "build", "npm", "bun ", "pip ", "скачай", "download", "curl",
        "wget", "http://", "https://", "интернет", "сеть", "github", "репозитор",
        "видео", "youtube", "ffmpeg", "mp4", "webm", "sdk", "ndk", "maven",
        "зависимост", "вложен", "посмотри", ".jpg", ".png", ".pdf", ".zip",
        "прикреплённые файлы", "docx", "xlsx", "pptx", ".odt", ".ods", ".odp", ".csv", "libreoffice", "либре", "офис",
        "ворд", "эксел", "excel", "блендер", "blender", ".blend", "документ", "таблиц", "презентац", "рабочий стол",
        "загрузки", "на пк", "на компьютере", "диск c", "диск d", "открой", "найди файл", "переименуй", "скопируй", "перемести"
    )

    private val codeKeywords = listOf(
        "код", "функци", "класс", "метод", "ошибк", "баг", "bug", "краш", "вылета", "компиляц",
        "исправ", "почини", "добавь", "переделай", "доработай", "поменяй", "замени", "удали", "реализуй",
        "kotlin", "compose", "java", "python", "javascript", "typescript", "html", "css", "sql",
        ".kt", ".py", ".js", ".ts", ".java", ".xml", ".json", ".kts", "exception", "stacktrace", "error"
    )

    private fun isGreetingOrSmallTalk(text: String): Boolean {
        val t = text.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        return t.isNotEmpty() && t.length <= 40 && greetingRegex.matches(t)
    }

    private fun isCodeOrToolRequest(text: String): Boolean {
        val p = text.lowercase()
        return toolKeywords.any { it in p } || codeKeywords.any { it in p } || "```" in p
    }

    /** Короткая реплика без кода и файлов: отвечаем сразу, без tools и рассуждений. */
    private fun isQuickMessage(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (isGreetingOrSmallTalk(t)) return true
        return t.length <= 60 && !isCodeOrToolRequest(t)
    }

    private fun agentFor(prompt: String): String {
        if (isGreetingOrSmallTalk(prompt)) return "chat"
        return if (isCodeOrToolRequest(prompt)) "build" else "chat"
    }

    private val quickReplyHint =
        "Это простая реплика. Отвечай сразу, коротко и по-человечески, как живой собеседник. " +
            "Не рассуждай заранее: не гадай, зачем пользователь это написал, не анализируй его мотивы и не комментируй сам вопрос. " +
            "Не задавай встречных вопросов, если они не нужны. На «привет» просто поздоровайся одной короткой фразой."

    private val workHint =
        "Думай ровно столько, сколько требует задача: простое решай сразу, глубоко рассуждай только над сложным кодом и логикой. " +
            "Не задавай уточняющих вопросов без крайней необходимости: если есть разумный вариант по умолчанию — выбери его, назови допущение одной строкой и делай. " +
            "Спрашивай только если без ответа нельзя продолжать или ошибка дорого обойдётся, и тогда задай ОДИН вопрос с готовыми вариантами. " +
            "Работа с кодом: найди нужные файлы через grep/glob и читай только их, независимые вызовы делай параллельно, " +
            "правь точечно, не переписывая файлы целиком и не трогая лишнее, не спрашивай «приступать ли», " +
            "после правки коротко скажи, что и где изменено, не пересказывая весь код. " +
            "Пиши код на любом языке программирования и формате, в стиле и версиях проекта. " +
            "Не отдавай непроверенный код: после написания запусти сборку, тесты или программу (если нет тулчейна — проверку синтаксиса: python -m py_compile, node --check, bash -n, php -l и т.п.). " +
            "Если проверка упала — прочитай ошибку, исправь причину и проверь снова, до 5 попыток; если ошибка повторяется, смени подход. " +
            "Если проверить нельзя — перечитай код (синтаксис, импорты, типы, граничные случаи) и честно скажи, что не проверено. Не пиши «работает», если не запускал проверку."

    private val heavyKeywords = listOf(
        ".zip", "архив", "весь проект", "во всём проекте", "во всем проекте", "рефактор", "архитектур", "с нуля",
        "перепиши", "миграц", "оптимизируй", "найди все", "во всех файлах", "полностью"
    )

    /** Тяжёлая задача: архив, весь проект, большой текст или вставленный код. */
    private fun isHeavyTask(text: String): Boolean {
        val p = text.lowercase()
        return text.length > 1500 || "```" in p || heavyKeywords.any { it in p }
    }

    private val deepHint =
        "Задача тяжёлая: подумай глубже и продумай план по шагам, но без лишних рассуждений вслух. " +
            "Если есть архив или проект — распакуй (unzip), посмотри структуру и build-файлы, найди нужные файлы и сделай задачу КОДОМ: " +
            "пиши и правь файлы через edit/write, не отвечай одним описанием или пересказом проекта. " +
            "Большой проект разбирай по частям, читай только нужное. Если нужен результат архивом — собери новый zip и назови его путь. " +
            "Код пиши на любом языке программирования. После правок обязательно запусти сборку/тесты/проверку синтаксиса; при ошибке исправь причину и повтори (до 5 попыток). " +
            "Если проверить нельзя — честно скажи, что не проверено. Коротко опиши, что изменено и какая проверка прошла. " +
            "Не задавай уточняющих вопросов без крайней необходимости: выбери разумный вариант, назови допущение одной строкой и делай."

    /** Подсказка скорости и стиля, которая идёт перед текстом пользователя. */
    private fun withHint(prompt: String): String {
        val hint = when {
            isHeavyTask(prompt) -> deepHint
            isQuickMessage(prompt) -> quickReplyHint
            else -> workHint
        }
        return if (hint === quickReplyHint) "$hint\n\n$prompt" else "$hint\n\n${PcTools.hint()}\n\n$prompt"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun isUnsupportedEndpoint(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("→ 404") || message.contains("→ 405") ||
            message.contains("HTTP 404") || message.contains("HTTP 405")
    }

    private fun messageArray(payload: JSONObject?): JSONArray {
        if (payload == null) return JSONArray()
        return payload.optJSONArray("data") ?: payload.optJSONArray("messages") ?: JSONArray().apply {
            if (payload.has("info") || payload.has("role") || payload.has("type")) put(payload)
        }
    }

    private fun messageKey(message: JSONObject): String =
        message.optString("id").ifBlank { message.optJSONObject("info")?.optString("id").orEmpty() }

    private fun assistantStartIndex(payload: JSONObject, beforeLastKey: String?): Int {
        val arr = messageArray(payload)
        if (beforeLastKey.isNullOrBlank()) return (arr.length() - 2).coerceAtLeast(0)
        for (i in arr.length() - 1 downTo 0) {
            val message = arr.optJSONObject(i) ?: continue
            if (messageKey(message) == beforeLastKey) return i + 1
        }
        // Some API versions cap the returned history. If the previous tail is
        // no longer in the response, inspect only the newest few entries.
        return (arr.length() - 5).coerceAtLeast(0)
    }

    private fun isAssistant(message: JSONObject): Boolean {
        val info = message.optJSONObject("info")
        val role = message.optString("type").ifBlank { message.optString("role") }
            .ifBlank { info?.optString("role").orEmpty() }
        return role == "assistant" || message.optBoolean("isAssistant")
    }

    private fun extractAssistant(payload: JSONObject, fromIndex: Int = 0): String {
        payload.optJSONObject("data")?.let { nested ->
            val nestedText = extractAssistant(nested, fromIndex)
            if (nestedText.isNotBlank()) return nestedText
        }
        val arr = messageArray(payload)
        val result = mutableListOf<String>()
        for (i in fromIndex.coerceAtLeast(0) until arr.length()) {
            val message = arr.optJSONObject(i) ?: continue
            if (!isAssistant(message)) continue
            val parts = message.optJSONArray("content") ?: message.optJSONArray("parts") ?: JSONArray()
            val text = buildString {
                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: continue
                    if (part.optString("type").let { it == "text" || it.isEmpty() }) append(part.optString("text"))
                }
            }.ifBlank { message.optString("text").ifBlank { message.optString("content") } }
            if (text.isNotBlank()) result += text.trim()
        }
        // Compatibility for direct legacy responses that contain only parts.
        if (result.isEmpty() && fromIndex == 0) {
            val parts = payload.optJSONArray("parts") ?: payload.optJSONObject("data")?.optJSONArray("parts")
            if (parts != null) {
                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: continue
                    if (part.optString("type") == "text") part.optString("text").takeIf { it.isNotBlank() }?.let(result::add)
                }
            }
        }
        return result.joinToString("\n").trim()
    }

    private fun extractAssistantAfter(payload: JSONObject, beforeLastKey: String?): String =
        extractAssistant(payload, assistantStartIndex(payload, beforeLastKey))

    private fun assistantError(payload: JSONObject, fromIndex: Int): String? {
        val arr = messageArray(payload)
        for (i in arr.length() - 1 downTo fromIndex.coerceAtLeast(0)) {
            val message = arr.optJSONObject(i) ?: continue
            if (!isAssistant(message)) continue
            if (message.optJSONObject("retry") != null) return null
            val error = message.optJSONObject("error") ?: return null
            return error.optString("message").ifBlank { error.optString("name") }.ifBlank { "неизвестная ошибка" }
        }
        return null
    }

    private fun sessionHasAssistantMessage(payload: JSONObject, fromIndex: Int): Boolean {
        val arr = messageArray(payload)
        for (i in fromIndex.coerceAtLeast(0) until arr.length()) {
            if (isAssistant(arr.optJSONObject(i) ?: continue)) return true
        }
        return false
    }

    /** Текст ретрая/ошибки провайдера, пока OpenCode повторяет запрос: иначе причина скрыта до таймаута. */
    private fun retryInfo(payload: JSONObject): String? {
        val arr = messageArray(payload)
        for (i in arr.length() - 1 downTo 0) {
            val message = arr.optJSONObject(i) ?: continue
            if (!isAssistant(message)) continue
            val retry = message.optJSONObject("retry") ?: return null
            return retry.optString("message").ifBlank { retry.optString("error") }.ifBlank { "повтор запроса к провайдеру" }
        }
        return null
    }

    private fun assistantMessagesCompleted(payload: JSONObject, fromIndex: Int): Boolean {
        val arr = messageArray(payload)
        var saw = false
        for (i in fromIndex.coerceAtLeast(0) until arr.length()) {
            val message = arr.optJSONObject(i) ?: continue
            if (!isAssistant(message)) continue
            saw = true
            val time = message.optJSONObject("time") ?: message.optJSONObject("info")?.optJSONObject("time")
            if ((time?.optLong("completed", 0L) ?: 0L) <= 0L) return false
        }
        return saw
    }

    private fun assistantMessagesExposeCompletion(payload: JSONObject, fromIndex: Int): Boolean {
        val arr = messageArray(payload)
        for (i in fromIndex.coerceAtLeast(0) until arr.length()) {
            val message = arr.optJSONObject(i) ?: continue
            if (!isAssistant(message)) continue
            val time = message.optJSONObject("time") ?: message.optJSONObject("info")?.optJSONObject("time")
            if (time?.has("completed") == true) return true
        }
        return false
    }

    private fun assistantWorkInProgress(payload: JSONObject, fromIndex: Int): Boolean {
        val arr = messageArray(payload)
        for (i in arr.length() - 1 downTo fromIndex.coerceAtLeast(0)) {
            val message = arr.optJSONObject(i) ?: continue
            if (!isAssistant(message)) continue
            val finish = message.optString("finish").ifBlank { message.optJSONObject("info")?.optString("finish").orEmpty() }
            if (finish in setOf("tool-calls", "tool_calls", "tool_use")) return true
            val parts = message.optJSONArray("content") ?: message.optJSONArray("parts") ?: return false
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.optString("type") != "tool") continue
                val state = part.optJSONObject("state")?.optString("status").orEmpty().ifBlank { part.optString("status") }
                if (state == "running" || state == "pending") return true
            }
            return false
        }
        return false
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
