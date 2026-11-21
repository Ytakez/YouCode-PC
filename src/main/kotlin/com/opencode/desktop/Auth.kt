package com.opencode.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val name: String
)

data class TelegramLogin(val token: String, val url: String)

class AuthHttpException(val code: Int, message: String) : IllegalStateException(message)

/**
 * Telegram-only login. Confirmation happens in the bot on the phone;
 * this process only opens the link and polls until the session arrives.
 */
class AuthManager {
    private val base = "https://gzuzyagiqgvpeqkksaml.supabase.co"
    private val apiKey = "sb_publishable_gXGst8Hdc4LbcdGqMF4MbA_bwAodaTr"
    private val sessionFile = File(System.getProperty("user.home"), ".youcode-desktop/session.json")

    fun currentSession(): AuthSession? = runCatching {
        if (!sessionFile.isFile) return null
        val j = JSONObject(sessionFile.readText())
        val access = j.optString("access")
        val refresh = j.optString("refresh")
        val id = j.optString("id")
        if (access.isBlank() || refresh.isBlank() || id.isBlank()) return null
        AuthSession(access, refresh, id, j.optString("name").ifBlank { "Telegram" })
    }.getOrNull()

    fun signOut() {
        runCatching { sessionFile.delete() }
    }

    suspend fun startTelegramLogin(): TelegramLogin = withContext(Dispatchers.IO) {
        val (code, json) = functionRequest(JSONObject().put("action", "start"))
        val token = json.optString("token")
        val url = json.optString("url").ifBlank { json.optString("link") }
        if (code !in 200..299) {
            val detail = json.optString("error").ifBlank { json.optString("message") }
            throw AuthHttpException(code, "Не удалось начать вход ($code)${if (detail.isNotBlank()) ": $detail" else ""}")
        }
        if (token.isBlank() || url.isBlank()) {
            throw AuthHttpException(code, "В ответе нет token/link: ${json.toString().take(180)}")
        }
        TelegramLogin(token, url)
    }

    /**
     * null — ещё ждём телефон, или сеть на секунду пропала (опрос продолжится).
     * Кидает только если вход точно мёртв (ссылка истекла, сервер вернул ошибку).
     */
    suspend fun pollTelegramLogin(token: String): AuthSession? = withContext(Dispatchers.IO) {
        val (code, json) = try {
            functionRequest(JSONObject().put("action", "poll").put("token", token))
        } catch (_: java.io.IOException) {
            return@withContext null
        }
        val status = json.optString("status")
        when {
            code == 410 || status == "expired" ->
                throw AuthHttpException(410, "Ссылка для входа устарела. Нажми «Войти» ещё раз.")
            code !in 200..299 -> {
                val detail = json.optString("detail").ifBlank { json.optString("error") }.ifBlank { json.optString("message") }
                throw AuthHttpException(code, "Ошибка входа ($code)${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            json.has("error") && status != "ok" && status != "pending" ->
                throw AuthHttpException(code, "Ошибка входа: ${json.optString("error")}")
            status == "ok" || json.has("access_token") || json.optJSONObject("session") != null ->
                sessionFrom(json).also(::save)
            else -> null
        }
    }

    /** Обновляет токены. 400/401/403 — сессия мертва, выходим. Сеть/5xx — оставляем как есть. */
    suspend fun refreshSession(): AuthSession? = withContext(Dispatchers.IO) {
        val current = currentSession() ?: return@withContext null
        try {
            val json = authRequest(
                "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", current.refreshToken)
            )
            sessionFrom(json).also(::save)
        } catch (e: AuthHttpException) {
            if (e.code == 400 || e.code == 401 || e.code == 403) {
                signOut()
                null
            } else current
        } catch (_: Exception) {
            current
        }
    }

    private fun save(s: AuthSession) {
        sessionFile.parentFile?.mkdirs()
        val tmp = File(sessionFile.path + ".tmp")
        tmp.writeText(
            JSONObject()
                .put("access", s.accessToken)
                .put("refresh", s.refreshToken)
                .put("id", s.userId)
                .put("name", s.name)
                .toString()
        )
        if (!tmp.renameTo(sessionFile)) {
            sessionFile.delete()
            tmp.renameTo(sessionFile)
        }
        // Keep the desktop token file readable only by the current OS user
        // on filesystems that support POSIX permissions.
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                sessionFile.toPath(),
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                )
            )
        }
    }

    private fun sessionFrom(j: JSONObject): AuthSession {
        val session = j.optJSONObject("session") ?: j
        val access = session.optString("access_token").ifBlank { j.optString("access_token") }
        val refresh = session.optString("refresh_token").ifBlank { j.optString("refresh_token") }
        if (access.isBlank() || refresh.isBlank()) {
            throw IllegalStateException("Сервер не вернул токены: ${j.toString().take(220)}")
        }
        val user = session.optJSONObject("user") ?: j.optJSONObject("user") ?: JSONObject()
        var id = user.optString("id").ifBlank { user.optString("sub") }
        var email = user.optString("email")
        val meta = user.optJSONObject("user_metadata")
        var name = meta?.optString("first_name").orEmpty()
            .ifBlank { meta?.optString("full_name").orEmpty() }
            .ifBlank { meta?.optString("username").orEmpty() }
        if (id.isBlank() || email.isBlank() || name.isBlank()) {
            decodeJwtPayload(access)?.let { payload ->
                if (id.isBlank()) id = payload.optString("sub")
                if (email.isBlank()) email = payload.optString("email")
                if (name.isBlank()) {
                    val um = payload.optJSONObject("user_metadata")
                    name = um?.optString("first_name").orEmpty()
                        .ifBlank { um?.optString("username").orEmpty() }
                }
            }
        }
        if (id.isBlank()) {
            throw IllegalStateException("Сервер не вернул пользователя: ${j.toString().take(220)}")
        }
        if (name.isBlank()) name = email.substringBefore("@").ifBlank { "Telegram" }
        return AuthSession(access, refresh, id, name)
    }

    private fun decodeJwtPayload(jwt: String): JSONObject? = runCatching {
        val raw = jwt.split(".").getOrNull(1) ?: return null
        val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
        JSONObject(String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8))
    }.getOrNull()

    private fun functionRequest(body: JSONObject): Pair<Int, JSONObject> {
        val c = (URL("$base/functions/v1/telegram-auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Authorization", "Bearer $apiKey")
            // Иначе Java шлёт Expect: 100-continue и часть прокси рвёт POST.
            setRequestProperty("Expect", "")
        }
        return try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to (runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", text.take(200)) })
        } finally {
            c.disconnect()
        }
    }

    private fun authRequest(path: String, body: JSONObject): JSONObject {
        val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Expect", "")
        }
        return try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching {
                    val j = JSONObject(text)
                    j.optString("msg").ifBlank { j.optString("error_description") }.ifBlank { j.optString("error") }
                }.getOrDefault("")
                throw AuthHttpException(code, msg.ifBlank { "Ошибка Supabase ($code)" })
            }
            JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}
