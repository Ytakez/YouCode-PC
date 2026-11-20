package com.opencode.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

data class Msg(val id: String, val text: String, val isUser: Boolean, val model: String? = null)

fun main() = application {
    val auth = remember { AuthManager() }
    var session by remember { mutableStateOf(auth.currentSession()) }
    val oc = remember { OpenCodeClient() }

    LaunchedEffect(Unit) {
        if (session != null) {
            val refreshed = auth.refreshSession()
            if (refreshed == null) session = null
            else session = refreshed
        }
    }

    Window(
        onCloseRequest = {
            oc.stop()
            exitApplication()
        },
        title = "YouCode Desktop",
        state = rememberWindowState(width = 960.dp, height = 720.dp)
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF7EB6FF),
                background = Color(0xFF0B0C0E),
                surface = Color(0xFF141516),
                onBackground = Color(0xFFE2E2E6),
                onSurface = Color(0xFFE2E2E6)
            )
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (session == null) {
                    LoginPane(auth) { session = it }
                } else {
                    ChatPane(
                        session = session!!,
                        oc = oc,
                        onSignOut = {
                            auth.signOut()
                            session = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginPane(auth: AuthManager, onOk: (AuthSession) -> Unit) {
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("YouCode Desktop", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Вход только через Telegram. Подтверждение — на телефоне в боте.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(28.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Как подтвердить на телефоне", fontWeight = FontWeight.SemiBold)
                Text("1. Нажми «Войти через Telegram»")
                Text("2. Открой ссылку на телефоне (Telegram или браузер)")
                Text("3. В боте нажми подтверждение входа")
                Text("4. Вернись к этому окну — вход завершится сам")
                Text("Не закрывай приложение, пока идёт ожидание.")
            }
        }
        link?.let { url ->
            Spacer(Modifier.height(16.dp))
            Text("Ссылка для телефона", fontWeight = FontWeight.SemiBold)
            SelectionContainer {
                Text(url, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = {
                runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
                }
            }) { Text("Скопировать ссылку") }
        }
        Spacer(Modifier.height(16.dp))
        if (waiting) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Ждём подтверждение на телефоне…")
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                if (waiting) return@Button
                error = null
                scope.launch {
                    waiting = true
                    try {
                        val login = auth.startTelegramLogin()
                        link = login.url
                        openTelegramLink(login.url)
                        // TTL ссылки на сервере — 10 минут. Первый опрос сразу.
                        repeat(300) { attempt ->
                            if (attempt > 0) delay(2000)
                            val s = auth.pollTelegramLogin(login.token)
                            if (s != null) {
                                onOk(s)
                                return@launch
                            }
                        }
                        error = "Время вышло. Подтверди вход в боте на телефоне и нажми ещё раз."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Ошибка входа"
                    } finally {
                        waiting = false
                    }
                }
            },
            enabled = !waiting,
            modifier = Modifier.height(48.dp)
        ) {
            Text(if (waiting) "Ожидаем телефон…" else "Войти через Telegram")
        }
    }
}

private fun openTelegramLink(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

@Composable
private fun ChatPane(session: AuthSession, oc: OpenCodeClient, onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember { ChatStore() }
    var chats by remember {
        mutableStateOf(store.load().ifEmpty {
            listOf(ChatThread(title = "Новый чат")).also(store::save)
        })
    }
    var selectedId by remember { mutableStateOf(chats.first().id) }
    val activeChat = chats.firstOrNull { it.id == selectedId } ?: chats.first()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(listOf<OcModel>()) }
    var model by remember { mutableStateOf<OcModel?>(null) }
    var engineNote by remember { mutableStateOf("Подключение к OpenCode…") }
    var serverUrl by remember { mutableStateOf(oc.baseUrl) }
    var serverPassword by remember { mutableStateOf("") }
    var showServerSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun updateChat(updated: ChatThread) {
        chats = (listOf(updated.copy(updatedAt = System.currentTimeMillis())) + chats.filterNot { it.id == updated.id })
        store.save(chats)
    }

    LaunchedEffect(Unit) {
        val start = oc.startLocalIfPossible()
        engineNote = when (start) {
            "started", "already" -> "OpenCode: ${oc.baseUrl}"
            "not_found" -> "opencode не найден. Установи OpenCode CLI или подключи сервер."
            else -> "OpenCode: $start"
        }
        if (oc.ping()) {
            runCatching {
                models = oc.listModels()
                model = models.firstOrNull()
            }.onFailure { engineNote = "Ошибка моделей: ${it.message}" }
        }
    }

    LaunchedEffect(selectedId, models) {
        val saved = chats.firstOrNull { it.id == selectedId }
        model = models.firstOrNull { it.id == saved?.modelId && it.providerId == saved?.providerId }
            ?: models.firstOrNull()
    }

    LaunchedEffect(activeChat.messages.size, selectedId) {
        if (activeChat.messages.isNotEmpty()) listState.animateScrollToItem(activeChat.messages.lastIndex)
    }

    Row(Modifier.fillMaxSize()) {
        // Persistent conversation navigation
        Column(
            Modifier.width(245.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(10.dp)
        ) {
            Text("YouCode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                if (!busy) {
                    val chat = ChatThread(title = "Новый чат")
                    chats = listOf(chat) + chats
                    selectedId = chat.id
                    input = ""
                    store.save(chats)
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("＋ Новый чат") }
            Spacer(Modifier.height(12.dp))
            Text("История", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(chats, key = { it.id }) { chat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { if (!busy) { selectedId = chat.id; input = "" } },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
                        ) {
                            Text(
                                (if (chat.id == selectedId) "● " else "") + chat.title,
                                maxLines = 2,
                                color = if (chat.id == selectedId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(onClick = {
                            if (!busy) {
                                val remaining = chats.filterNot { it.id == chat.id }
                                chats = if (remaining.isEmpty()) listOf(ChatThread(title = "Новый чат")) else remaining
                                if (selectedId == chat.id) selectedId = chats.first().id
                                store.save(chats)
                            }
                        }, enabled = !busy) { Text("×") }
                    }
                }
            }
            Divider()
            TextButton(onClick = { showServerSettings = !showServerSettings }) { Text("Настройки сервера") }
            TextButton(onClick = onSignOut) { Text("Выйти из аккаунта") }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(activeChat.title, fontWeight = FontWeight.SemiBold)
                    Text("${session.name} · $engineNote", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                if (models.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) { Text(model?.name ?: "Модель") }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                            models.take(100).forEach { m ->
                                DropdownMenuItem(text = { Text(m.name) }, onClick = {
                                    model = m
                                    updateChat(activeChat.copy(modelId = m.id, providerId = m.providerId, modelName = m.name))
                                    expanded = false
                                })
                            }
                        }
                    }
                }
            }

            if (showServerSettings) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(serverUrl, { serverUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("URL OpenCode") }, singleLine = true)
                    OutlinedTextField(serverPassword, { serverPassword = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Пароль сервера (если нужен)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            runCatching { oc.connectExternal(serverUrl.trim(), serverPassword); if (oc.ping()) {
                                scope.launch { models = oc.listModels(); model = models.firstOrNull(); engineNote = "Подключено: ${oc.baseUrl}" }
                            } else engineNote = "Не удалось подключиться к ${oc.baseUrl}" }
                            showServerSettings = false
                        }) { Text("Подключить") }
                        TextButton(onClick = { showServerSettings = false }) { Text("Закрыть") }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(activeChat.messages, key = { it.id }) { msg ->
                    val bg = if (msg.isUser) Color(0xFF1E3A5F) else MaterialTheme.colorScheme.surface
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start) {
                        if (!msg.isUser && !msg.model.isNullOrBlank()) Text(msg.model!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Surface(shape = RoundedCornerShape(12.dp), color = bg) {
                            SelectionContainer { Text(msg.text, Modifier.padding(12.dp)) }
                        }
                    }
                }
                if (busy) item { Text(status.ifBlank { "Думает…" }, color = MaterialTheme.colorScheme.primary) }
            }

            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение…") }, enabled = !busy,
                    maxLines = 5
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val prompt = input.trim()
                    if (prompt.isEmpty() || busy) return@Button
                    val selectedModel = model ?: models.firstOrNull()
                    if (selectedModel == null) { status = "Нет доступных моделей. Проверь OpenCode."; return@Button }
                    model = selectedModel
                    val userMessage = Msg(java.util.UUID.randomUUID().toString(), prompt, true)
                    val newTitle = if (activeChat.messages.isEmpty()) prompt.take(42).let { if (prompt.length > 42) "$it…" else it } else activeChat.title
                    var workingChat = activeChat.copy(
                        title = newTitle,
                        messages = activeChat.messages + userMessage,
                        modelId = selectedModel.id,
                        providerId = selectedModel.providerId,
                        modelName = selectedModel.name
                    )
                    updateChat(workingChat)
                    input = ""
                    busy = true
                    scope.launch {
                        try {
                            var sid = workingChat.openCodeSessionId
                            if (sid.isNullOrBlank()) {
                                status = "Создаю сессию…"
                                sid = oc.createSession(workingChat.title, selectedModel)
                                workingChat = workingChat.copy(openCodeSessionId = sid)
                                updateChat(workingChat)
                            }
                            val answer = oc.chat(sid, selectedModel, prompt) { status = it }
                            workingChat = workingChat.copy(messages = workingChat.messages + Msg(java.util.UUID.randomUUID().toString(), answer, false, selectedModel.name))
                            updateChat(workingChat)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            workingChat = workingChat.copy(messages = workingChat.messages + Msg(java.util.UUID.randomUUID().toString(), "Ошибка: ${e.message ?: "неизвестная ошибка"}", false))
                            updateChat(workingChat)
                        } finally {
                            busy = false
                            status = ""
                        }
                    }
                }, enabled = !busy) { Text("Отправить") }
            }
        }
    }
}
