# AI Agent — Instructions for Coding Agents

Read this file first, then **`docs/PROJECT_GUIDE.md`** for full architecture.

## Project summary

**A3 AI Agent** — Android voice-first assistant (Kotlin + Compose).  
Wake word or manual button → STT → Groq LLM with **18 tools** → TTS.  
Target device: **POCO M4 Pro 5G (MIUI/HyperOS)**, sideloaded.

```
Package: com.agent.ai
Root:    app/src/main/java/com/agent/ai/
```

## Before you change anything

1. Read **`docs/PROJECT_GUIDE.md`** (structure, flows, tools, permissions, edge cases).
2. Follow the **Agent Development Prompt** at the bottom of that doc for every feature/fix.
3. Register new tools in **`ToolRegistryFactory.kt`** only (not in the service directly).
4. Tools must return **`AgentResult`** — never throw from `execute()`.
5. Voice + Agent Chat share tools via `ToolRegistryFactory.create(context)`.
6. Update **`GroqApiClient.buildSystemPrompt()`** when adding tools or multi-step flows.
7. Update **`ToolCatalog.kt`** for UI discovery.
8. Build: `gradle :app:assembleDebug` — install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Critical paths

| Task | Primary files |
|------|----------------|
| Add a tool | `data/tools/*Tool.kt`, `ToolRegistryFactory.kt`, `GroqApiClient.kt`, `ToolCatalog.kt` |
| Voice loop | `AgentOrchestrator.kt`, `GroqApiClient.runVoiceWithTools()` |
| Agent chat | `AgentChatScreen.kt`, `GroqApiClient.chatWithTools()` |
| Memory | `AgentMemoryRepository.kt`, `MemoryExtractor.kt`, `MemorySkyScreen.kt` |
| Permissions / manifest | `AndroidManifest.xml`, `MainActivity.kt` |
| Background service | `AgentForegroundService.kt`, `AgentServiceStarter.kt` |
| UI automation | `AgentAccessibilityService.kt`, `UiAutomationTool.kt` |
| Launch Play Store / uninstall from service | `ToolLaunchActivity.kt`, `AppLookup.kt` |

## Do not

- Silently install/uninstall apps (Android requires user confirmation).
- Auto-dial on first `call_contact` (two-step confirmation is mandatory).
- Start activities directly from `AgentForegroundService` for store/uninstall — use **`ToolLaunchActivity`**.
- Use `getInstalledApplications()` on API 30+ without `<queries>` — use **`AppLookup`** launcher query.
- Skip error handling — use **`AgentController.reportError()`** for user-visible failures.
- Create git commits unless the user asks.

## Related docs

| File | Contents |
|------|----------|
| [docs/PROJECT_GUIDE.md](docs/PROJECT_GUIDE.md) | Full architecture, code map, tools, edge cases, agent prompt |
| [README.md](README.md) | Quick setup & build |
| [WAKE_WORD_SETUP.md](WAKE_WORD_SETUP.md) | Picovoice `.ppn` assets |
| [FEASIBILITY_REPORT.md](FEASIBILITY_REPORT.md) | Platform limits (WhatsApp headless, call audio, etc.) |
