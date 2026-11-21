# YouCode Desktop

Windows desktop AI chat client built with Kotlin, Compose Desktop and OpenCode CLI.

## Features

- Telegram sign-in.
- Local OpenCode CLI startup or connection to an existing OpenCode server.
- Windows npm OpenCode launch through `cmd.exe` to avoid Win32 CreateProcess error 193.
- Full-screen toggle in the chat header **and on the Telegram login screen**; F11 works everywhere.
- Access to PC files: attach files/folders to a message (＋ Файл / Папка), set the agent work folder and full-access mode in Settings.
- Detects LibreOffice, Blender, Python, FFmpeg, Git, Node.js and lets the agent use them; file paths in answers get "Открыть / В LibreOffice / В Blender / В папке" buttons.
- Model selection.
- Multiple conversations with local persistent history.
- Each YouCode conversation keeps its own OpenCode session ID so follow-up prompts stay in the same context.
- Chats are stored under `%USERPROFILE%\\.youcode-desktop\\chats.json` on Windows. Removing a chat in the UI removes it from this local history file.

## Build on Windows

The included GitHub Actions workflow installs Gradle 8.10.2 and Java 17, then runs `gradle createDistributable`. It packages the Windows app directory (including `YouCode.exe`) as `YouCode-Windows.zip`.

To build locally, install JDK 17 and Gradle 8.10.2, then run:

```powershell
gradle createDistributable --no-daemon
```

Install OpenCode CLI on the Windows PC, or use **Настройки сервера** to connect to an existing OpenCode server.
