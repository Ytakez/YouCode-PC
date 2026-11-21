package com.opencode.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

data class Msg(val id: String, val text: String, val isUser: Boolean, val model: String? = null)

fun main() = application {
    val auth = remember { AuthManager() }
    var session by remember { mutableStateOf(auth.currentSession()) }
    val oc = remember { OpenCodeClient() }
    val windowState = rememberWindowState(width = 960.dp, height = 720.dp)
    val isFullscreen = windowState.placement == WindowPlacement.Fullscreen
    val toggleFullscreen = {
        windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
            WindowPlacement.Floating
        } else {
            WindowPlacement.Fullscreen
        }
    }

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
        state = windowState,
        // F11 — полный экран на любом экране приложения, в том числе на входе через Telegram.
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.F11) {
                toggleFullscreen()
                true
            } else false
        }
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
                    LoginPane(auth, isFullscreen, toggleFullscreen) { session = it }
                } else {
                    ChatPane(
                        session = session!!,
                        oc = oc,
                        onToggleFullscreen = toggleFullscreen,
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
private fun LoginPane(auth: AuthManager, fullscreen: Boolean, onToggleFullscreen: () -> Unit, onOk: (AuthSession) -> Unit) {
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().padding(if (link != null) 20.dp else 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("YouCode Desktop", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Вход только через Telegram. Подтверждение — на телефоне в боте.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(if (link != null) 14.dp else 28.dp))
        val qrLink = link
        if (qrLink == null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Как подтвердить на телефоне", fontWeight = FontWeight.SemiBold)
                    Text("1. Нажми «Войти через Telegram» — появится QR-код")
                    Text("2. Отсканируй его камерой телефона")
                    Text("3. В боте нажми подтверждение входа")
                    Text("4. Вернись к этому окну — вход завершится сам")
                    Text("Не закрывай приложение, пока идёт ожидание.")
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Сканируйте QR-код с телефона и войдите в аккаунт",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Наведите камеру телефона на код, откройте ссылку в Telegram и нажмите подтверждение входа в боте.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    QrCode(qrLink, Modifier.size(if (fullscreen) 300.dp else 196.dp))
                    Text(
                        "Код действует 5 минут",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            runCatching {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(qrLink), null)
                            }
                        }) { Text("Скопировать ссылку") }
                        TextButton(onClick = { openTelegramLink(qrLink) }) { Text("Открыть на этом компьютере") }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (waiting && link == null) {
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
                        link = null
                        val login = auth.startTelegramLogin()
                        link = login.url
                        // Тот же таймаут и ритм опроса, что и в телефонной версии: 5 минут, раз в 2 секунды.
                        val deadline = System.currentTimeMillis() + 5 * 60 * 1000L
                        var serverErrors = 0
                        while (System.currentTimeMillis() < deadline) {
                            delay(2000)
                            try {
                                val s = auth.pollTelegramLogin(login.token)
                                serverErrors = 0
                                if (s != null) {
                                    onOk(s)
                                    return@launch
                                }
                            } catch (e: AuthHttpException) {
                                // Повторяем временные 5xx, как в Android AuthScreen.
                                if (e.code in 500..599 && ++serverErrors < 5) continue
                                throw e
                            }
                        }
                        error = "Время ожидания вышло. Попробуйте ещё раз."
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
            Text(if (waiting) "Ожидаем телефон…" else if (link != null) "Создать новый QR-код" else "Войти через Telegram")
        }
    }
    OutlinedButton(
        onClick = onToggleFullscreen,
        shape = RoundedCornerShape(13.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
    ) { Text(if (fullscreen) "⛶  Выйти из полного экрана (F11)" else "⛶  На весь экран (F11)") }
    }
}

/** Выбор нескольких файлов системным диалогом. */
private fun pickFiles(startDir: String): List<File> {
    val dialog = FileDialog(null as Frame?, "Выбери файлы", FileDialog.LOAD).apply {
        isMultipleMode = true
        if (File(startDir).isDirectory) directory = startDir
        isVisible = true
    }
    return dialog.files?.toList().orEmpty()
}

/** Выбор папки системным диалогом. */
private fun pickFolder(startDir: String): File? {
    val chooser = JFileChooser(File(startDir).takeIf { it.isDirectory }).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Выбери папку"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

@Composable
private fun QrCode(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        runCatching {
            QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET to "UTF-8"
                )
            )
        }.getOrNull()
    }
    Box(modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(10.dp), contentAlignment = Alignment.Center) {
        if (matrix != null) {
            Canvas(Modifier.fillMaxSize()) {
                val n = matrix.width
                val cell = size.minDimension / n
                for (y in 0 until n) {
                    for (x in 0 until n) {
                        if (matrix[x, y]) {
                            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
                        }
                    }
                }
            }
        } else {
            Text("Не удалось создать QR-код. Скопируй ссылку.", color = Color.Black, textAlign = TextAlign.Center)
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
private fun ChatPane(session: AuthSession, oc: OpenCodeClient, onToggleFullscreen: () -> Unit, onSignOut: () -> Unit) {
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
    var showSettings by remember { mutableStateOf(false) }
    var attached by remember { mutableStateOf(listOf<File>()) }
    var pcTools by remember { mutableStateOf(PcTools.tools) }
    var workDir by remember { mutableStateOf(Prefs.workDir) }
    var fullAccess by remember { mutableStateOf(Prefs.fullAccess) }
    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }

    val page = Color(0xFF101216)
    val sidebar = Color(0xFF171A20)
    val panel = Color(0xFF1B1F27)
    val panelSoft = Color(0xFF222833)
    val accent = Color(0xFFB7C8FF)
    val textMain = Color(0xFFE4E7F0)
    val textMuted = Color(0xFF9AA3B2)

    fun updateChat(updated: ChatThread) {
        chats = (listOf(updated.copy(updatedAt = System.currentTimeMillis())) + chats.filterNot { it.id == updated.id })
        store.save(chats)
    }

    LaunchedEffect(Unit) {
        val start = oc.startLocalIfPossible()
        engineNote = when (start) {
            "started", "already" -> "OpenCode подключён · ${oc.baseUrl}"
            "not_found" -> "OpenCode не найден. Установи OpenCode CLI или подключи сервер."
            else -> "OpenCode: $start"
        }
        if (oc.ping()) {
            runCatching {
                models = oc.listModels()
                model = models.firstOrNull()
            }.onFailure { engineNote = "Ошибка моделей: ${it.message}" }
        }
    }

    LaunchedEffect(Unit) {
        pcTools = withContext(Dispatchers.IO) { PcTools.tools.ifEmpty { PcTools.detect() } }
    }

    LaunchedEffect(selectedId, models) {
        val saved = chats.firstOrNull { it.id == selectedId }
        model = models.firstOrNull { it.id == saved?.modelId && it.providerId == saved?.providerId }
            ?: models.firstOrNull()
    }

    LaunchedEffect(activeChat.messages.size, selectedId) {
        if (activeChat.messages.isNotEmpty()) listState.animateScrollToItem(activeChat.messages.lastIndex)
    }

    fun newChat() {
                    if (!busy) {
                        val chat = ChatThread(title = "Новый чат")
                        chats = listOf(chat) + chats
                        selectedId = chat.id
                        input = ""
                        store.save(chats)
                    }
    }

    fun sendMessage() {
                                    val files = attached
                                    val prompt = input.trim().ifEmpty {
                                        if (files.isNotEmpty()) "Посмотри прикреплённые файлы и расскажи, что в них." else ""
                                    }
                                    if (prompt.isEmpty() || busy) return
                                    val fullPrompt = if (files.isEmpty()) prompt else
                                        prompt + "\n\nПрикреплённые файлы на ПК (используй эти пути):\n" +
                                            files.joinToString("\n") { "- " + it.absolutePath }
                                    val shownText = if (files.isEmpty()) prompt else
                                        prompt + "\n\n" + files.joinToString("\n") { (if (it.isDirectory) "Папка: " else "Файл: ") + it.name }
                                    val selectedModel = model ?: models.firstOrNull()
                                    if (selectedModel == null) { status = "Нет доступных моделей. Проверь OpenCode."; return }
                                    model = selectedModel
                                    val userMessage = Msg(java.util.UUID.randomUUID().toString(), shownText, true)
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
                                    attached = emptyList()
                                    busy = true
                                    scope.launch {
                                        try {
                                            var sid = workingChat.openCodeSessionId
                                            if (sid.isNullOrBlank()) {
                                                status = "Создаю сессию…"
                                                sid = oc.createSession(workingChat.title, selectedModel, prompt)
                                                workingChat = workingChat.copy(openCodeSessionId = sid)
                                                updateChat(workingChat)
                                            }
                                            val answer = oc.chat(sid, selectedModel, fullPrompt) { status = it }
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
    }

    LaunchedEffect(busy, selectedId) {
        if (!busy) {
            delay(80)
            runCatching { inputFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Row(
        Modifier.fillMaxSize().background(page).onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) false
            else when {
                e.isCtrlPressed && e.key == Key.N -> { newChat(); true }
                e.key == Key.Escape && showSettings -> { showSettings = false; true }
                else -> false
            }
        }
    ) {
        // Navigation rail / chat history
        Column(
            Modifier.width(264.dp).fillMaxHeight().background(sidebar).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFF303B55)) {
                    Text("Y", Modifier.padding(horizontal = 13.dp, vertical = 8.dp), color = accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                }
                Column {
                    Text("YouCode", color = textMain, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("DESKTOP", color = textMuted, style = MaterialTheme.typography.labelSmall, letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified)
                }
            }
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = { newChat() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD5DEFF), contentColor = Color(0xFF202B48))
            ) {
                Text("＋", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text("Новый чат", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(25.dp))
            Text("БИБЛИОТЕКА", color = textMuted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(9.dp))
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF293143), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("◷", color = accent)
                Spacer(Modifier.width(10.dp))
                Text("История чатов", color = textMain, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("${chats.size}", color = textMuted, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(chats, key = { it.id }) { chat ->
                    val selected = chat.id == selectedId
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (selected) Color(0xFF293143) else Color.Transparent, RoundedCornerShape(12.dp))
                            .padding(start = 4.dp, end = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { if (!busy) { selectedId = chat.id; input = "" } },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 9.dp)
                        ) {
                            Text(
                                chat.title,
                                maxLines = 2,
                                color = if (selected) accent else textMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        TextButton(
                            onClick = {
                                if (!busy) {
                                    val remaining = chats.filterNot { it.id == chat.id }
                                    chats = if (remaining.isEmpty()) listOf(ChatThread(title = "Новый чат")) else remaining
                                    if (selectedId == chat.id) selectedId = chats.first().id
                                    store.save(chats)
                                }
                            },
                            enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("×", color = textMuted) }
                    }
                }
            }
            Divider(color = Color(0xFF303641), thickness = 1.dp)
            Spacer(Modifier.height(10.dp))
            Surface(
                color = panel,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().clickable { showSettings = true }
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(session.name, color = textMain, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("Аккаунт подключён", color = textMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth()) {
                Text("⚙  Настройки", color = textMuted)
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight().background(page)) {
            // App bar
            Row(
                Modifier.fillMaxWidth().background(page).padding(start = 28.dp, end = 24.dp, top = 17.dp, bottom = 17.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(activeChat.title, color = textMain, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = Color(0xFF244B3A), shape = RoundedCornerShape(50)) {
                            Box(Modifier.size(7.dp).padding(0.dp))
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(engineNote, color = textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                if (models.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            shape = RoundedCornerShape(13.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = textMain)
                        ) { Text(model?.name ?: "Модель", maxLines = 1) }
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
                    Spacer(Modifier.width(10.dp))
                }
                OutlinedButton(
                    onClick = onToggleFullscreen,
                    shape = RoundedCornerShape(13.dp),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = textMuted)
                ) { Text("⛶") }
            }
            Divider(color = Color(0xFF252B35), thickness = 1.dp)

            if (activeChat.messages.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(color = Color(0xFF202A3C), shape = RoundedCornerShape(24.dp)) {
                        Text("✦", Modifier.padding(horizontal = 22.dp, vertical = 15.dp), color = accent, style = MaterialTheme.typography.headlineLarge)
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("С чего начнём?", color = textMain, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Задай вопрос или выбери идею для начала", color = textMuted, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(25.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("Объясни сложную тему", "Помоги с кодом", "Придумай идею").forEach { suggestion ->
                            OutlinedButton(
                                onClick = { input = suggestion },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = textMain),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)
                            ) { Text(suggestion) }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(activeChat.messages, key = { it.id }) { msg ->
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start
                        ) {
                            if (!msg.isUser) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(color = Color(0xFF303B55), shape = RoundedCornerShape(8.dp)) {
                                        Text("✦", Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = accent, style = MaterialTheme.typography.labelMedium)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(msg.model ?: "YouCode", color = textMuted, style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.height(7.dp))
                            }
                            Surface(
                                shape = RoundedCornerShape(
                                    topStart = 18.dp, topEnd = 18.dp,
                                    bottomStart = if (msg.isUser) 18.dp else 5.dp,
                                    bottomEnd = if (msg.isUser) 5.dp else 18.dp
                                ),
                                color = if (msg.isUser) Color(0xFF34415D) else panel,
                                modifier = Modifier.widthIn(max = 780.dp)
                            ) {
                                SelectionContainer {
                                    Text(msg.text, Modifier.padding(horizontal = 18.dp, vertical = 14.dp), color = textMain, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            if (!msg.isUser) {
                                var copied by remember(msg.id) { mutableStateOf(false) }
                                TextButton(
                                    onClick = {
                                        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(msg.text), null) }
                                        copied = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) { Text(if (copied) "Скопировано" else "Копировать", color = textMuted, style = MaterialTheme.typography.labelSmall) }
                                val foundFiles = remember(msg.id) { PcTools.extractFiles(msg.text) }
                                if (foundFiles.isNotEmpty()) {
                                    Row(
                                        Modifier.widthIn(max = 780.dp).horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        foundFiles.forEach { f ->
                                            OutlinedButton(
                                                onClick = { PcTools.open(f) },
                                                shape = RoundedCornerShape(12.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
                                            ) { Text("Открыть: ${f.name}", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                                            PcTools.appsFor(f).forEach { app ->
                                                OutlinedButton(
                                                    onClick = { PcTools.openWith(app, f) },
                                                    shape = RoundedCornerShape(12.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
                                                ) { Text("В ${app.name}", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                                            }
                                            TextButton(
                                                onClick = { PcTools.reveal(f) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) { Text("В папке", color = textMuted, style = MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (busy) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                                Text(status.ifBlank { "Думает…" }, color = textMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // Composer
            Surface(color = page, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 10.dp, bottom = 18.dp)) {
                    if (attached.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            attached.forEach { f ->
                                Surface(color = panelSoft, shape = RoundedCornerShape(12.dp)) {
                                    Row(Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text((if (f.isDirectory) "Папка: " else "") + f.name, color = textMain, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                        TextButton(
                                            onClick = { attached = attached.filterNot { it == f } },
                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                        ) { Text("×", color = textMuted) }
                                    }
                                }
                            }
                        }
                    }
                    Surface(
                        color = panel,
                        shape = RoundedCornerShape(22.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF343C4A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { attached = (attached + pickFiles(workDir)).distinctBy { it.absolutePath } },
                                enabled = !busy,
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text("＋ Файл", color = textMuted) }
                            TextButton(
                                onClick = { pickFolder(workDir)?.let { d -> attached = (attached + d).distinctBy { it.absolutePath } } },
                                enabled = !busy,
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text("Папка", color = textMuted) }
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f).focusRequester(inputFocus).onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                                        sendMessage()
                                        true
                                    } else false
                                },
                                placeholder = { Text("Напиши сообщение…", color = textMuted) },
                                enabled = !busy,
                                maxLines = 5,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    disabledBorderColor = Color.Transparent,
                                    focusedTextColor = textMain,
                                    unfocusedTextColor = textMain,
                                    cursorColor = accent
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { sendMessage() },
                                enabled = !busy && (input.isNotBlank() || attached.isNotEmpty()),
                                shape = RoundedCornerShape(15.dp),
                                modifier = Modifier.height(46.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD5DEFF), contentColor = Color(0xFF202B48))
                            ) {
                                Text(if (busy) "…" else "Отправить")
                            }
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text("Enter — отправить · Shift+Enter — новая строка · YouCode может ошибаться", Modifier.align(Alignment.CenterHorizontally), color = Color(0xFF707987), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    if (showSettings) {
        Box(
            Modifier.fillMaxSize().background(Color(0xAA000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showSettings = false },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = panel,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(24.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Настройки", color = textMain, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showSettings = false }) { Text("✕", color = textMuted) }
                    }

                    Text("АККАУНТ", color = textMuted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Surface(color = panelSoft, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(session.name, color = textMain, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("Аккаунт подключён", color = textMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { showSettings = false; onSignOut() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) { Text("Выйти из аккаунта") }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("ПОДКЛЮЧЕНИЕ К OPENCODE", color = textMuted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(engineNote, color = textMuted, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(serverUrl, { serverUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("URL OpenCode") }, singleLine = true)
                    OutlinedTextField(serverPassword, { serverPassword = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Пароль сервера (если нужен)") }, singleLine = true)
                    Button(
                        onClick = {
                            runCatching { oc.connectExternal(serverUrl.trim(), serverPassword); if (oc.ping()) {
                                scope.launch { models = oc.listModels(); model = models.firstOrNull(); engineNote = "Подключено: ${oc.baseUrl}" }
                            } else engineNote = "Не удалось подключиться к ${oc.baseUrl}" }
                            showSettings = false
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD5DEFF), contentColor = Color(0xFF202B48))
                    ) { Text("Подключить") }

                    Spacer(Modifier.height(4.dp))
                    Text("ФАЙЛЫ И ПРОГРАММЫ ПК", color = textMuted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        workDir, { workDir = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Рабочая папка агента") },
                        singleLine = true,
                        trailingIcon = { TextButton(onClick = { pickFolder(workDir)?.let { workDir = it.absolutePath } }) { Text("Выбрать") } }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Полный доступ к файлам ПК", color = textMain)
                            Text(
                                "Включено — агент работает с файлами в любых папках ПК и запускает программы (LibreOffice, Blender и др.). Выключено — только в рабочей папке",
                                color = textMuted, style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(checked = fullAccess, onCheckedChange = { fullAccess = it })
                    }
                    Text(
                        "Найдено на ПК: " + if (pcTools.isEmpty()) "пока ничего" else pcTools.joinToString(", ") { it.name },
                        color = textMuted, style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = {
                            val dir = File(workDir.trim())
                            if (!dir.isDirectory) {
                                engineNote = "Папка не найдена: ${workDir.trim()}"
                            } else {
                                Prefs.workDir = dir.absolutePath
                                Prefs.fullAccess = fullAccess
                                Prefs.save()
                                showSettings = false
                                scope.launch {
                                    engineNote = "Перезапускаю OpenCode…"
                                    val r = oc.restartLocal()
                                    engineNote = when (r) {
                                        "started", "already" -> "OpenCode подключён · ${oc.baseUrl}"
                                        "external" -> "Сохранено. Для внешнего сервера папка и права задаются на самом сервере."
                                        else -> "OpenCode: $r"
                                    }
                                    if (oc.ping()) runCatching { models = oc.listModels() }
                                    pcTools = withContext(Dispatchers.IO) { PcTools.detect() }
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD5DEFF), contentColor = Color(0xFF202B48))
                    ) { Text("Применить и перезапустить OpenCode") }

                    Spacer(Modifier.height(4.dp))
                    Text("ОКНО", color = textMuted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = { showSettings = false; onToggleFullscreen() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textMain)
                    ) { Text("Полноэкранный режим") }
                    Text(
                        "Enter — отправить · Shift+Enter — новая строка · Ctrl+N — новый чат · F11 — полный экран · Esc — закрыть окно",
                        color = textMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
    }
}
