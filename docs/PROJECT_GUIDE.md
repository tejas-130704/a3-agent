# A3 AI Agent — Complete Project Guide

> **Audience:** Human developers and AI coding agents working on this codebase.  
> **Goal:** Understand architecture end-to-end, make safe changes, and ship features that handle real-device edge cases (especially MIUI/POCO).

---

## Table of contents

1. [Product overview](#1-product-overview)
2. [Code structure](#2-code-structure)
3. [Runtime architecture](#3-runtime-architecture)
4. [Tool system (18 tools)](#4-tool-system-18-tools)
5. [UI layer](#5-ui-layer)
6. [Memory system](#6-memory-system)
7. [Groq LLM integration](#7-groq-llm-integration)
8. [Permissions & special access](#8-permissions--special-access)
9. [Error handling](#9-error-handling)
10. [Build, install, debug](#10-build-install-debug)
11. [How to add or change features](#11-how-to-add-or-change-features)
12. [Edge cases checklist](#12-edge-cases-checklist)
13. [Agent development prompt](#13-agent-development-prompt-mandatory-for-ai-agents)

---

## 1. Product overview

**A3 AI Agent** is a sideloaded Android app that acts as a voice-first personal assistant with:

- Optional wake words: **"Hey A3"** and **"Ok A3"** (Porcupine)
- Manual **"Talk to Agent"** button (no wake word required)
- **Groq** cloud LLM with OpenAI-compatible **function calling**
- **18 local tools** (alarms, calls, WhatsApp, maps, web search, volume, etc.)
- **Memory Sky** — persistent JSON memory + live 5-minute session context
- **6-tab Compose UI** (Home, Tools, Memory, Agent, Recall, Settings)

**Primary test device:** POCO M4 Pro 5G (MIUI/HyperOS). Background mic and activity launches are aggressively restricted — design for that.

---

## 2. Code structure

```
app/src/main/java/com/agent/ai/
├── AIAgentApp.kt                 # Application: init memory, start service
├── MainActivity.kt               # 6-tab shell, permission launcher, error dialog
│
├── core/                         # Voice pipeline & platform bridges
│   ├── AgentController.kt        # UI ↔ service (state, errors, pending call)
│   ├── AgentOrchestrator.kt      # STT → Groq tool loop → TTS
│   ├── AgentException.kt         # AgentResult, ErrorCode, AgentErrorEvent
│   ├── ToolLaunchActivity.kt     # Trampoline for starting activities from service
│   ├── accessibility/            # UI automation (AccessibilityService)
│   ├── notifications/            # Notification listener + read tool
│   ├── stt/SpeechToText.kt
│   ├── tts/TextToSpeechEngine.kt
│   └── wakeword/                   # Porcupine + NoOp fallback
│
├── data/
│   ├── GroqApiClient.kt          # LLM API, voice/chat tool loops, system prompt
│   ├── GroqKeyManager.kt         # Multi-key failover
│   ├── AgentChatResponse.kt      # Chat response + contact choice cards
│   ├── memory/                   # Session + long-term memory
│   ├── settings/                 # SharedPreferences API keys
│   └── tools/                    # All AgentTool implementations
│
├── presentation/                 # Jetpack Compose screens
│   ├── home/HomeScreen.kt
│   ├── chat/AgentChatScreen.kt   # Full tool chat
│   ├── chat/ChatScreen.kt        # Memory-only chat (Recall tab)
│   ├── memory/MemorySkyScreen.kt
│   ├── settings/SettingsScreen.kt
│   ├── tools/ToolsScreen.kt
│   ├── components/AgentErrorDialog.kt
│   └── theme/AgentTheme.kt
│
└── service/
    ├── AgentForegroundService.kt # Wake word + orchestrator host
    ├── AgentServiceStarter.kt
    ├── BootCompletedReceiver.kt
    └── ConnectivityTileService.kt
```

**Assets / config:**

```
app/src/main/assets/
  hey_a3_android.ppn          # Wake word (user must add)
  ok_a3_android.ppn

app/src/main/res/xml/
  agent_accessibility_service.xml

gradle.properties             # GROQ_API_KEY, PICOVOICE_ACCESS_KEY (gitignored)
```

**Persistence:**

| Data | Location |
|------|----------|
| Memory bubbles + summaries | `filesDir/memory_sky.json` |
| API keys | SharedPreferences `agent_settings` |
| Live session | In-memory `SessionContext` (5 min TTL) |

---

## 3. Runtime architecture

### 3.1 Voice turn (Home / background)

```
Wake word OR "Talk to Agent"
    → AgentForegroundService
    → AgentOrchestrator.runTurn()
        LISTENING  → SpeechToText.listenOnce()
        (optional) → tryConfirmPendingCall() — voice "one"/"two" for contact pick
        THINKING   → GroqApiClient.runVoiceWithTools()  [up to 8 tool rounds]
        ACTING     → tool.execute() per round
        SPEAKING   → TextToSpeechEngine.speak(final text)
        → MemoryExtractor.recordTurn()
    → Resume wake word
```

**State exposed to UI:** `AgentController.state` → `AgentState` enum.

### 3.2 Agent Chat (Agent tab)

```
User text → AgentChatScreen
    → GroqApiClient.chatWithTools() [up to 8 tool rounds]
    → Tool execution via ToolRegistry
    → AgentChatResponse (text + optional contact cards)
    → MemoryExtractor.recordChatTurn()
```

**Guard:** Blocks tool execution if `AgentController.isBusy` (voice turn active).

### 3.3 Recall Chat (Recall tab)

```
User text → ChatScreen
    → GroqApiClient.chat() — NO tools, memory context only
    → MemoryExtractor.recordChatTurn()
```

### 3.4 Multi-tool chaining

Both **voice** and **Agent Chat** support sequential tool calls in one user request (e.g. `web_search` → `save_note`).

- Loop limit: `MAX_TOOL_ROUNDS = 8` in `GroqApiClient.kt`
- System prompt instructs LLM to chain when user says "search and save to notes"
- Each tool result is appended to the Groq message array before the next LLM call

### 3.5 Contact calling (mandatory two-step)

```
Step 1: call_contact(confirmed=false) → list top 4 matches, set ContactCallSession pending
Step 2: User confirms via voice ("one") OR tap card in Home/Agent Chat
        → call_contact(confirmed=true) OR orchestrator tryConfirmPendingCall()
        → dial only if pending session exists
```

**Never** dial on step 1. `DialerTool` rejects `confirmed=true` without active pending session.

---

## 4. Tool system (18 tools)

**Registration:** Single source of truth — `ToolRegistryFactory.create(context)`.

| Tool name | Class | Purpose |
|-----------|-------|---------|
| `set_alarm` | AlarmTool | Set clock alarm |
| `set_timer` | TimerTool | Countdown timer |
| `call_contact` | DialerTool | Two-step contact call |
| `add_calendar_event` | CalendarTool | Insert calendar event |
| `toggle_setting` | SettingsTool | Flashlight; open WiFi/BT panels |
| `send_whatsapp` | WhatsAppTool | Pre-fill WhatsApp message |
| `send_telegram` | TelegramTool | Pre-fill Telegram message |
| `spotify_control` | SpotifyTool | Play/pause/skip/search |
| `open_app` | OpenAppTool | Launch installed app |
| `ui_automation` | UiAutomationTool | Tap/type/scroll via accessibility |
| `read_notifications` | ReadNotificationsTool | Read notification shade |
| `manage_app` | ManageAppTool | Install/uninstall/check apps |
| `save_note` | SaveNoteTool | Save to Notes app |
| `device_status` | DeviceStatusTool | Battery, network, storage, latency |
| `navigate_maps` | MapsNavigationTool | GPS navigation |
| `delete_memory` | DeleteMemoryTool | Delete memory nodes |
| `volume_control` | VolumeTool | Get/set volume % |
| `web_search` | WebSearchTool | DuckDuckGo + Wikipedia search |

### Tool interface contract

```kotlin
interface AgentTool {
    val name: String              // LLM function name (snake_case)
    val description: String       // Shown to LLM
    val parametersSchema: JSONObject  // JSON Schema
    suspend fun execute(params: JSONObject): AgentResult<String>
}
```

**Rules:**
- Never throw from `execute()` — return `AgentResult.Error`
- Use `ErrorCode` enum for categorization
- Activities from background: use `ToolLaunchActivity.launch(context, intent)`
- App discovery: use `AppLookup` (not raw `getInstalledApplications()`)

### Shared utilities

| Utility | Use |
|---------|-----|
| `AppLookup` | Resolve app names, open Play Store, uninstall dialog |
| `ContactLookup` | Fuzzy contact match, choice index parsing |
| `ContactCallSession` | Pending call state + UI sync |
| `WebSearchClient` | Free web search (no API key) |
| `NotificationReaderBridge` | Notification access check + recent list |

---

## 5. UI layer

### Bottom navigation (MainActivity)

| Index | Tab | Screen | Tools? |
|-------|-----|--------|--------|
| 0 | Home | HomeScreen | Voice trigger, setup checklist, pending call cards |
| 1 | Tools | ToolsScreen | Static catalog (`ToolCatalog`) |
| 2 | Memory | MemorySkyScreen | Browse/delete memory bubbles |
| 3 | Agent | AgentChatScreen | Full tool chat |
| 4 | Recall | ChatScreen | Memory Q&A only |
| 5 | Settings | SettingsScreen | Groq + Picovoice keys |

### Key UI patterns

- **AgentErrorDialog** — global error popup via `AgentController.lastError`
- **ChatScaffold** — shared chat layout (Agent + Recall)
- **ContactChoicesPanel** — tappable contact cards with session expiry
- **MemorySkyScreen** — delete button per bubble/summary with confirmation

---

## 6. Memory system

```
AgentMemoryHub (singleton)
├── repository: AgentMemoryRepository  → memory_sky.json
├── session: SessionContext            → 5-min live history (20 msgs max)
└── extractor: MemoryExtractor         → turns → bubbles + summaries
```

**Topics (`MemoryTopic`):** HEALTH, FRIENDS, CONTACTS, SPOTIFY, WHATSAPP, TELEGRAM, SESSION, GENERAL, etc.

**Prompt injection:** `repository.buildContextPrompt()` → Groq system prompt for ambiguous follow-ups ("play him again", "message her").

**Deletion:**
- UI: delete icon on Memory tab
- Tool: `delete_memory` with query match
- Repository: `deleteBubbleById`, `deleteBubblesMatching`, `deleteSummaryById`

---

## 7. Groq LLM integration

**File:** `GroqApiClient.kt`

| Method | Used by | Tools? |
|--------|---------|--------|
| `runVoiceWithTools()` | AgentOrchestrator (voice) | Yes, multi-round |
| `chatWithTools()` | AgentChatScreen | Yes, multi-round |
| `chat()` | ChatScreen (Recall) | No |
| `resolveIntent()` | Legacy single-tool (prefer runVoiceWithTools) | Single |

**Models (failover chain):**
1. `groq/compound-mini` (Fast lightweight native Groq model)
2. `groq/compound` (Native Groq agentic model)
3. `openai/gpt-oss-20b` (Small 20B fast fallback)

**Keys:** `GroqKeyManager` — multi-key from Settings, rotates on 401/429/5xx.

**System prompt:** `buildSystemPrompt()` — must be updated when adding tools or behavioral rules (call confirm, multi-tool chain, volume, web search, etc.).

---

## 8. Permissions & special access

### Runtime (MainActivity launcher)

`RECORD_AUDIO`, `CALL_PHONE`, `READ_CONTACTS`, `READ_CALENDAR`, `WRITE_CALENDAR`, `BLUETOOTH_CONNECT` (API 31+)

### Manifest-only

`INTERNET`, `MODIFY_AUDIO_SETTINGS`, `ACCESS_WIFI_STATE`, `REQUEST_INSTALL_PACKAGES`, `CAMERA`, foreground service permissions

### User must enable in system Settings

| Access | For | Check |
|--------|-----|-------|
| Accessibility | ui_automation | `AgentAccessibilityBridge.isEnabled()` |
| Notification access | read_notifications | `NotificationReaderBridge.isAccessGranted()` |
| Battery: No restrictions | Background mic (MIUI) | User manual |
| Autostart | Service survival (MIUI) | User manual |

### Package visibility (`<queries>` in manifest)

Required for Android 11+ to see/install/open: launcher apps, Play Store, Maps, WhatsApp, Telegram, Notes apps, geo/navigation intents.

---

## 9. Error handling

**Pattern:** `AgentResult<T>` — `Success(value)` or `Error(code, message, cause?)`.

**UI:** `AgentController.reportError(error, source)` → `AgentErrorDialog` with debug details.

**ErrorCode categories:** WAKEWORD_*, STT_*, LLM_*, TOOL_*, TTS_*, SERVICE_KILLED_BY_OS

**Tool failures** are fed back to Groq as tool message content so the LLM can recover or explain to the user.

---

## 10. Build, install, debug

### Prerequisites

```properties
# gradle.properties
GROQ_API_KEY=gsk_...
PICOVOICE_ACCESS_KEY=...
```

Wake word assets in `app/src/main/assets/` (see `WAKE_WORD_SETUP.md`).

### Build

```bash
export GRADLE_OPTS="-Xmx2048m"
gradle -p /path/to/AIAgent :app:assembleDebug --no-daemon
```

Or Android Studio Koala+ (compileSdk 34, minSdk 26, JVM 17).

### Install (POCO / MIUI)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If `INSTALL_FAILED_USER_RESTRICTED`: approve on phone or enable USB install in Developer options.

### Useful logcat tags

```
AgentOrchestrator, AgentForegroundService, SpeechToText, GroqApiClient,
PorcupineWakeWord, AgentAccessibility, AgentNotificationListener
```

---

## 11. How to add or change features

### Add a new tool (checklist)

1. Create `app/src/main/java/com/agent/ai/data/tools/YourTool.kt` implementing `AgentTool`
2. Register in `ToolRegistryFactory.kt`
3. Add entry to `ToolCatalog.kt` (UI docs)
4. Add rules to `GroqApiClient.buildSystemPrompt()` so LLM knows when/how to call it
5. If needs manifest permission → `AndroidManifest.xml`
6. If needs `<queries>` package → manifest
7. If starts Activity from service → `ToolLaunchActivity.launch()`
8. Build, install, test via **voice** and **Agent Chat**
9. Handle edge cases (see §12)

### Change voice behavior

- `AgentOrchestrator.kt` — turn flow, pending call shortcut
- `GroqApiClient.runVoiceWithTools()` — multi-tool loop

### Change chat behavior

- `AgentChatScreen.kt` — UI, contact cards
- `GroqApiClient.chatWithTools()` — tool loop, call_contact short-circuit

### Change memory

- `MemoryExtractor.kt` — what gets stored per turn
- `AgentMemoryRepository.kt` — persistence, delete APIs
- `MemorySkyScreen.kt` — UI

---

## 12. Edge cases checklist

Use this when implementing or reviewing any feature:

### Android platform

- [ ] **API 30+ package visibility** — use `<queries>` + launcher intent query (`AppLookup`)
- [ ] **Background activity start blocked** — use `ToolLaunchActivity` from service
- [ ] **Cannot silent install/uninstall** — open Play Store / system dialog; tell user to confirm
- [ ] **Accessibility optional** — `ui_automation` fails gracefully with clear message
- [ ] **Notification access optional** — `read_notifications` checks `NotificationReaderBridge`
- [ ] **Geocoder may fail offline** — maps tool passes raw address to Google Maps as fallback
- [ ] **STT NO_MATCH / timeout** — online-first, retry offline, partial results (`SpeechToText`)
- [ ] **Main thread** — STT/TTS on main; heavy work on `Dispatchers.IO`

### MIUI / POCO

- [ ] Service killed without battery whitelist → document in error message
- [ ] Play Store vs **GetApps** (`com.xiaomi.mipicks`) for install
- [ ] MIUI Notes (`com.miui.notes`) for save_note
- [ ] USB install restriction → user must approve

### Voice / concurrency

- [ ] Only one voice turn at a time (`turnInProgress`)
- [ ] Agent Chat blocked while voice busy (or tool errors clearly)
- [ ] Wake word skipped if not configured (NoOpWakeWordDetector)
- [ ] Pending call expires (5 min) — UI shows expired state

### Safety

- [ ] **call_contact** never auto-dials without confirmation
- [ ] Contact list shows top 4 max; voice + tap to confirm
- [ ] LLM deprecated models → model failover chain in GroqApiClient

### Multi-tool

- [ ] Chain completes within `MAX_TOOL_ROUNDS` (8)
- [ ] Tool errors appended to LLM context for recovery
- [ ] Final spoken/text response summarizes what was done

### Memory

- [ ] Delete by id vs fuzzy query — no accidental mass delete without match
- [ ] JSON persist after every mutation
- [ ] Empty memory → graceful prompts in UI and LLM context

---

## 13. Agent development prompt (mandatory for AI agents)

**Copy and apply this mindset for every task on this project:**

---

### AGENT TASK PROMPT

You are working on **A3 AI Agent**, an Android Kotlin/Compose voice assistant. Before and while implementing:

1. **Read context first**
   - Read `AGENTS.md` and this file (`docs/PROJECT_GUIDE.md`)
   - Read the files you will modify and their callers
   - Check `ToolRegistryFactory`, `GroqApiClient.buildSystemPrompt()`, and manifest if touching tools or permissions

2. **End-to-end delivery**
   - A feature is not done until it works on a **real device** through the full path: user speech/text → LLM → tool(s) → Android API → user feedback (TTS/UI/toast/error dialog)
   - Wire **voice AND Agent Chat** unless the feature is explicitly UI-only or Recall-only
   - Update **LLM system prompt** so the model knows the new capability
   - Update **ToolCatalog** for discoverability

3. **Flexibility**
   - Accept fuzzy user input (typos, "whatsapp" vs "WhatsApp", "80 percent" vs `80`)
   - Normalize parameters in tools (action aliases, optional fields, sensible defaults)
   - Support **multi-tool chains** when the user request implies multiple steps (search → save, open app → type message)
   - Prefer optional parameters over failing on missing non-critical fields

4. **Edge cases (required)**
   - Permission denied → clear `AgentResult.Error` + user message, not crash
   - Service/background context → `ToolLaunchActivity` for UI intents
   - MIUI restrictions → document in error text when relevant
   - Empty/null inputs → validate in tool, return `TOOL_INVALID_PARAMS`
   - Concurrent voice turn → guard in orchestrator and chat
   - Safety flows (calls, install, uninstall) → never bypass user confirmation
   - Network failures (Groq, web search) → fail with actionable message, key rotation where applicable

5. **Code conventions**
   - Match existing patterns: `AgentResult`, `AgentTool`, Compose Material3, `SkyCard` theme
   - Minimal diff — do not refactor unrelated code
   - Never throw from tools — return errors
   - Do not commit unless user asks

6. **Verification**
   - Run `:app:assembleDebug` after changes
   - Fix compile errors before finishing
   - Describe what to test on device (voice phrase + Agent Chat phrase)

---

### Example task interpretation

**User request:** *"Add brightness control"*

**Correct approach:**
- Create `BrightnessTool` or extend `SettingsTool` with GET/SET
- Register + prompt + ToolCatalog
- Handle auto-brightness edge case, permission if needed
- Test: "Set brightness to 50%" voice + chat
- Return spoken confirmation with actual level

**Incorrect approach:**
- Only add a stub tool without prompt wiring
- Only work in chat, not voice
- Crash if brightness permission missing
- Hardcode without checking current level

---

## Document history

| Date | Notes |
|------|-------|
| 2026-08-27 | Initial comprehensive guide — 18 tools, multi-tool voice loop, MIUI notes |

For quick agent entry, see **[AGENTS.md](../AGENTS.md)** in repo root.
