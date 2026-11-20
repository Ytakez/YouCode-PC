package com.opencode.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Durable local chat history. Stored per Windows user in ~/.youcode-desktop/chats.json. */
data class ChatThread(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: List<Msg> = emptyList(),
    val openCodeSessionId: String? = null,
    val modelId: String? = null,
    val providerId: String? = null,
    val modelName: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

class ChatStore {
    private val directory = File(System.getProperty("user.home"), ".youcode-desktop")
    private val file = File(directory, "chats.json")

    @Synchronized fun load(): List<ChatThread> = runCatching {
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val arr = root.optJSONArray("chats") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val messages = c.optJSONArray("messages") ?: JSONArray()
                val parsed = buildList {
                    for (j in 0 until messages.length()) {
                        val m = messages.optJSONObject(j) ?: continue
                        add(Msg(m.optString("id", UUID.randomUUID().toString()), m.optString("text"), m.optBoolean("user"), m.optString("model").takeIf { it.isNotBlank() }))
                    }
                }
                add(ChatThread(
                    id = c.optString("id", UUID.randomUUID().toString()),
                    title = c.optString("title", "Новый чат"),
                    messages = parsed,
                    openCodeSessionId = c.optString("openCodeSessionId").takeIf { it.isNotBlank() },
                    modelId = c.optString("modelId").takeIf { it.isNotBlank() },
                    providerId = c.optString("providerId").takeIf { it.isNotBlank() },
                    modelName = c.optString("modelName").takeIf { it.isNotBlank() },
                    updatedAt = c.optLong("updatedAt", System.currentTimeMillis())
                ))
            }
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    @Synchronized fun save(chats: List<ChatThread>) {
        directory.mkdirs()
        val arr = JSONArray()
        chats.forEach { chat ->
            val messages = JSONArray()
            chat.messages.forEach { m ->
                messages.put(JSONObject().put("id", m.id).put("text", m.text).put("user", m.isUser).put("model", m.model ?: ""))
            }
            arr.put(JSONObject()
                .put("id", chat.id).put("title", chat.title).put("messages", messages)
                .put("openCodeSessionId", chat.openCodeSessionId ?: "")
                .put("modelId", chat.modelId ?: "").put("providerId", chat.providerId ?: "")
                .put("modelName", chat.modelName ?: "").put("updatedAt", chat.updatedAt))
        }
        val temp = File(directory, "chats.json.tmp")
        temp.writeText(JSONObject().put("version", 1).put("chats", arr).toString(2), Charsets.UTF_8)
        runCatching { java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { temp.copyTo(file, overwrite = true); temp.delete() }
    }
}
