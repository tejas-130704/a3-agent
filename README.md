# AI Agent — V2 (A3)

Wake word ("Hey A3" or "Ok A3") → STT → Groq tool-calling LLM → local Android tool execution → TTS.

> **New machine?** Follow **[SETUP_FROM_SCRATCH.md](SETUP_FROM_SCRATCH.md)** for requirements, API keys, build, and phone setup.  
> **Developers / AI agents:** **[AGENTS.md](AGENTS.md)** → **[docs/PROJECT_GUIDE.md](docs/PROJECT_GUIDE.md)**

## Tools (19)

| Tool | Voice example |
|------|---------------|
| `set_alarm` | "Set alarm for 7 AM" |
| `set_timer` | "Set a 5 minute timer" |
| `call_contact` | "Call Mom" (confirms before dial) |
| `add_calendar_event` | "Add meeting tomorrow at 3 PM" |
| `toggle_setting` | "Turn on flashlight" / "Open WiFi settings" |
| `send_whatsapp` | "WhatsApp John saying I'm on my way" |
| `send_telegram` | "Telegram Sarah hello" |
| `spotify_control` | "Pause Spotify" / "Play Arijit Singh on Spotify" |
| `open_app` | "Open Chrome" / "Open WhatsApp" |
| `ui_automation` | "Type hi and send" (needs accessibility) |
| `read_notifications` | "Tell me my top 5 notifications" |
| `manage_app` | "Install Instagram" / "Uninstall Telegram" |
| `save_note` | "Save in notes: buy milk" |
| `device_status` | "What's my battery?" / "How's my internet?" |
| `navigate_maps` | "Navigate to Mumbai airport" |
| `delete_memory` | "Forget Spotify memory about Arijit" |
| `volume_control` | "Set volume to 80%" / "What's my volume?" |
| `web_search` | "Search M.Tech process and save to notes" |
| `get_info` | "What alarms are set?" / "What events do I have today?" |

## Setup

1. **Groq API key** — Settings tab in app, or `gradle.properties` as `GROQ_API_KEY`
2. **Picovoice AccessKey** — Settings tab, or `gradle.properties` as `PICOVOICE_ACCESS_KEY`
3. **Wake word models** — see [WAKE_WORD_SETUP.md](WAKE_WORD_SETUP.md)

## Post-install (POCO / MIUI)

- Battery → **No restrictions** | Autostart → **ON**
- Accessibility → **AI Agent ON** (UI automation)
- Notification access → **AI Agent ON** (read notifications)
- Optional: Quick Settings → **Agent Network** tile

## Build

```bash
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio Koala+, min SDK 26, compileSdk 34.

## Architecture

```
Wake word / manual → STT → Groq (ToolRegistry, up to 8 tool rounds) → TTS
Agent Chat → Groq chatWithTools → same ToolRegistry
Recall Chat → Groq chat (memory only, no tools)
Memory → memory_sky.json + live SessionContext
```

**Add a tool:** implement `AgentTool` → register in **`ToolRegistryFactory.kt`** → update **`GroqApiClient.buildSystemPrompt()`** → **`ToolCatalog.kt`**.

## Documentation

| Doc | Purpose |
|-----|---------|
| **[SETUP_FROM_SCRATCH.md](SETUP_FROM_SCRATCH.md)** | **Requirements + full setup on a new PC/phone** |
| [AGENTS.md](AGENTS.md) | Quick start for coding agents |
| [docs/PROJECT_GUIDE.md](docs/PROJECT_GUIDE.md) | Full project guide + agent development prompt |
| [WAKE_WORD_SETUP.md](WAKE_WORD_SETUP.md) | Picovoice `.ppn` setup |
| [FEASIBILITY_REPORT.md](FEASIBILITY_REPORT.md) | Platform limits & deferred features |
| [gradle.properties.example](gradle.properties.example) | API key template |

## Deferred

See [FEASIBILITY_REPORT.md](FEASIBILITY_REPORT.md) — call-audio AI, headless WhatsApp/Telegram, silent app install.
"# a3-agent" 
