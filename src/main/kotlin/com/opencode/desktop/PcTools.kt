package com.opencode.desktop

import org.json.JSONObject
import java.awt.Desktop
import java.io.File
import java.util.concurrent.TimeUnit

data class PcTool(val name: String, val path: String)

/** Настройки доступа к файлам ПК. Хранятся в ~/.youcode-desktop/prefs.json. */
object Prefs {
    private val file = File(System.getProperty("user.home"), ".youcode-desktop/prefs.json")

    /** Папка, в которой стартует агент OpenCode. По умолчанию — домашняя папка пользователя. */
    @Volatile var workDir: String = System.getProperty("user.home")

    /** Агент может читать/писать файлы в любых папках и запускать программы без запроса разрешений. */
    @Volatile var fullAccess: Boolean = true

    init {
        runCatching {
            if (file.isFile) {
                val j = JSONObject(file.readText(Charsets.UTF_8))
                j.optString("workDir").takeIf { it.isNotBlank() && File(it).isDirectory }?.let { workDir = it }
                if (j.has("fullAccess")) fullAccess = j.optBoolean("fullAccess", true)
            }
        }
    }

    fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                JSONObject().put("workDir", workDir).put("fullAccess", fullAccess).toString(),
                Charsets.UTF_8
            )
        }
    }
}

/** Поиск установленных программ ПК и открытие файлов в них. */
object PcTools {
    @Volatile var tools: List<PcTool> = emptyList()
        private set

    private val isWin = System.getProperty("os.name").lowercase().contains("win")
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    val officeExt = setOf("doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp")
    private val knownExt = officeExt + setOf(
        "pdf", "txt", "md", "png", "jpg", "jpeg", "gif", "zip", "blend", "mp4", "mp3", "html", "json", "py", "kt"
    )

    /** Блокирующий вызов: запускай из IO-потока. */
    fun detect(): List<PcTool> {
        val found = mutableListOf<PcTool>()
        findLibreOffice()?.let { found += PcTool("LibreOffice", it) }
        findBlender()?.let { found += PcTool("Blender", it) }
        onPath("python", "python3", "py")?.let { found += PcTool("Python", it) }
        onPath("ffmpeg")?.let { found += PcTool("FFmpeg", it) }
        onPath("git")?.let { found += PcTool("Git", it) }
        onPath("node")?.let { found += PcTool("Node.js", it) }
        tools = found
        return found
    }

    fun tool(name: String): PcTool? = tools.firstOrNull { it.name == name }

    private fun env(name: String) = System.getenv(name).orEmpty()

    private fun findLibreOffice(): String? {
        val candidates = when {
            isWin -> listOf(env("ProgramFiles"), env("ProgramFiles(x86)"), env("LOCALAPPDATA") + "\\Programs")
                .filter { it.isNotBlank() }
                .map { File(it, "LibreOffice/program/soffice.exe") }
            isMac -> listOf(File("/Applications/LibreOffice.app/Contents/MacOS/soffice"))
            else -> listOf(File("/usr/bin/soffice"), File("/usr/local/bin/soffice"), File("/snap/bin/libreoffice"))
        }
        candidates.firstOrNull { it.isFile }?.let { return it.absolutePath }
        return onPath("soffice", "libreoffice")
    }

    private fun findBlender(): String? {
        if (isWin) {
            val roots = listOf(env("ProgramFiles"), env("ProgramFiles(x86)")).filter { it.isNotBlank() }
            for (root in roots) {
                val dirs = File(root, "Blender Foundation").listFiles { f -> f.isDirectory }
                    ?.sortedByDescending { it.name } ?: continue
                for (d in dirs) File(d, "blender.exe").takeIf { it.isFile }?.let { return it.absolutePath }
            }
        } else if (isMac) {
            File("/Applications/Blender.app/Contents/MacOS/Blender").takeIf { it.isFile }?.let { return it.absolutePath }
        }
        return onPath("blender")
    }

    private fun onPath(vararg names: String): String? {
        for (n in names) {
            val line = runCatching {
                val cmd = if (isWin) listOf("where.exe", n) else listOf("which", n)
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(5, TimeUnit.SECONDS)
                out.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && File(it).isFile }
            }.getOrNull()
            if (!line.isNullOrBlank()) return line
        }
        return null
    }

    /** Подсказка агенту: где он находится, что ему разрешено и какие программы есть на этом ПК. */
    fun hint(): String {
        val sb = StringBuilder()
        sb.append("Ты работаешь на компьютере пользователя (${System.getProperty("os.name")}). ")
        sb.append("Рабочая папка: ${Prefs.workDir}. Домашняя папка: ${System.getProperty("user.home")}. ")
        if (Prefs.fullAccess) {
            sb.append("У тебя есть доступ ко всем файлам и папкам ПК: читай, создавай, копируй и правь файлы в любых местах (Рабочий стол, Документы, Загрузки, другие диски) и запускай установленные программы. ")
            sb.append("Не трогай системные папки и чужие файлы без прямой просьбы, удаляй и перезаписывай только то, о чём просили. ")
        }
        if (tools.isNotEmpty()) {
            sb.append("Установленные программы: ")
            sb.append(tools.joinToString("; ") { "${it.name} — \"${it.path}\"" })
            sb.append(". ")
        }
        sb.append(
            "Офисные файлы (.docx .xlsx .pptx .odt .ods .odp .doc .xls .ppt .rtf): не читай их как текст. " +
                "Конвертируй и правь через LibreOffice без окна: soffice --headless --convert-to <pdf|docx|xlsx|txt|csv> --outdir <папка> <файл>, " +
                "либо через Python-библиотеки (python-docx, openpyxl, python-pptx), если они установлены. "
        )
        if (tool("Blender") != null) {
            sb.append(
                "Blender: запускай без окна — blender -b файл.blend -P скрипт.py для скриптов (bpy) " +
                    "и blender -b файл.blend -o //кадр_#### -f 1 для рендера. "
            )
        }
        sb.append(
            "Когда создаёшь или меняешь файл, в конце ответа напиши его ПОЛНЫЙ путь отдельной строкой — " +
                "приложение покажет рядом кнопки «Открыть» и «В папке»."
        )
        return sb.toString()
    }

    /** Программы, которыми имеет смысл открыть файл помимо программы по умолчанию. */
    fun appsFor(f: File): List<PcTool> {
        val ext = f.extension.lowercase()
        return buildList {
            if (ext in officeExt) tool("LibreOffice")?.let { add(it) }
            if (ext == "blend") tool("Blender")?.let { add(it) }
        }
    }

    fun open(f: File) {
        Thread {
            runCatching {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(f)
                } else if (isWin) {
                    ProcessBuilder("cmd.exe", "/c", "start", "", f.absolutePath).start()
                } else if (isMac) {
                    ProcessBuilder("open", f.absolutePath).start()
                } else {
                    ProcessBuilder("xdg-open", f.absolutePath).start()
                }
            }
        }.start()
    }

    fun openWith(app: PcTool, f: File) {
        Thread { runCatching { ProcessBuilder(app.path, f.absolutePath).start() } }.start()
    }

    fun reveal(f: File) {
        Thread {
            runCatching {
                when {
                    isWin -> ProcessBuilder("explorer.exe", "/select,${f.absolutePath}").start()
                    isMac -> ProcessBuilder("open", "-R", f.absolutePath).start()
                    else -> {
                        val dir = if (f.isDirectory) f else f.parentFile
                        if (dir != null) Desktop.getDesktop().open(dir)
                    }
                }
            }
        }.start()
    }

    private val absWin = Regex("""[A-Za-z]:\\[^\r\n"'<>|*?`]+""")
    private val absUnix = Regex("""(?:^|[\s(`"'])((?:/|~/)[^\s"'<>|*?`]+)""")
    private val relName = Regex(
        """(?<![\w\\/.:-])[\w\-.]+\.(?:${knownExt.joinToString("|")})\b""",
        RegexOption.IGNORE_CASE
    )

    /** Находит в тексте ответа пути к реально существующим файлам и папкам (до 6 штук). */
    fun extractFiles(text: String): List<File> {
        val result = linkedMapOf<String, File>()
        fun add(f: File) {
            if (result.size >= 6 || !f.exists()) return
            result.putIfAbsent(runCatching { f.canonicalPath }.getOrDefault(f.absolutePath), f)
        }
        fun resolveLongest(raw: String): File? {
            var c = raw.trim().trimEnd('.', ',', ';', ':', ')', '*')
            while (c.isNotEmpty()) {
                val f = File(c)
                if (f.exists()) return f
                val cut = c.lastIndexOfAny(charArrayOf(' ', '\t'))
                if (cut <= 0) return null
                c = c.substring(0, cut).trimEnd('.', ',', ';', ':', ')', '*')
            }
            return null
        }
        absWin.findAll(text).forEach { m -> resolveLongest(m.value)?.let(::add) }
        if (!isWin) {
            val home = System.getProperty("user.home")
            absUnix.findAll(text).forEach { m ->
                val p = m.groupValues[1].let { if (it.startsWith("~/")) home + it.substring(1) else it }
                resolveLongest(p)?.let(::add)
            }
        }
        val base = File(Prefs.workDir)
        relName.findAll(text).forEach { m ->
            val f = File(base, m.value)
            if (f.isFile) add(f)
        }
        return result.values.toList()
    }
}
