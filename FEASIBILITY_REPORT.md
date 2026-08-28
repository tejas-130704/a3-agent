# V2 Module Feasibility Report

Research date: **2026-08-27**. Target device: **POCO M4 Pro 5G (MIUI/HyperOS)**, sideloaded personal-use app, `targetSdk 34`.

Each module gets: **(a)** possible on stock non-root Android?, **(b)** API/permission, **(c)** Play Store policy risk, **(d)** effort/fragility, **verdict**.

Sources: official Android developer docs, Telegram/Meta/Spotify developer docs, Picovoice/GitHub issues, recent developer reports (2024–2026).

---

## 1. WhatsApp Integration

### (a) Technically possible?

| Approach | Stock Android? | Notes |
|---|---|---|
| **Deep link intent** (`api.whatsapp.com/send?phone=&text=`) | **Yes** | Opens WhatsApp compose UI; user still taps Send. Reliable, zero ban risk for occasional use. Already viable in V2 as "fast path". |
| **open-wa / whatsmeow / WPPConnect in Termux** | **Partial** | Can run on-device via Termux (Go/Node). Requires persistent background process, MongoDB or similar for some stacks, QR/session pairing. Works for hobby automation but is **reverse-engineered**, not official. |
| **Companion server (desktop/VPS)** | **Yes (with host)** | open-wa is designed as a gateway service. More stable than Termux on a phone (battery, MIUI kills). |
| **Official WhatsApp Cloud API** | **Yes** | Legitimate, but number must be **removed from consumer WhatsApp** first. One-way migration. |

### (b) API / permission

- Intent path: none beyond `INTERNET` (WhatsApp app handles send).
- Unofficial automation: session pairing via QR, no Android permission — runs as Termux user process.
- Cloud API: Meta Business Manager, WABA, phone verification, webhook server, `WHATSAPP_BUSINESS_MANAGEMENT` tokens.

### (c) Play Store policy risk

- **Intent/deep link:** Low — standard Android inter-app pattern.
- **Unofficial clients (open-wa, whatsmeow):** **High** — violates WhatsApp ToS; Meta actively detects automation. Not suitable for Play Store distribution. Account ban risk on the linked number.
- **Cloud API:** Low policy risk if used correctly; Meta-approved channel.

### (d) Effort / fragility

| Path | Effort | Fragility |
|---|---|---|
| Intent deep link | **Low (hours)** | Low — breaks only if WhatsApp changes URL scheme |
| Termux + whatsmeow | **Medium (days)** | High — session drops, MIUI kills Termux, protocol changes, ban risk |
| Cloud API | **High (weeks)** | Medium — approval, business verification, per-message costs for marketing templates; service replies free since Nov 2024 |

### Verdict: **GO-WITH-CAVEATS**

- **GO** for V2: Intent deep link + AccessibilityService fallback (see module 4) for "open chat, paste text, tap send".
- **GO-WITH-CAVEATS** for Termux/open-wa: viable for **personal sideload only**, dedicated secondary number, accept ban risk. Not for Play Store or primary WhatsApp number.
- **GO-WITH-CAVEATS** for Cloud API: only if you dedicate a business number and accept losing consumer WhatsApp on it. Overkill for a personal voice agent unless you need headless send without UI.

**Recommendation:** Start with intent deep link. Add UI automation fallback. Skip open-wa unless you want a separate Termux sidecar and accept ToS risk.

---

## 2. Telegram Integration

### (a) Technically possible?

| Approach | Stock Android? | Notes |
|---|---|---|
| **Intent deep link** (`tg://msg?to=&text=` or `https://t.me/share/url`) | **Yes** | Opens Telegram compose. Same limitation as WhatsApp — may need user tap. |
| **TDLib (user account, JNI)** | **Yes** | Official Telegram library. Full send/receive as **your** Telegram account. Used in production apps (e.g. community TDLib Android wrappers via JitPack). Requires phone login + optional 2FA. |
| **Bot API** | **Yes** | Simple HTTP. **Cannot** initiate chats with arbitrary users/contacts by phone number. Users must message the bot first. Not suitable for "text Mom on Telegram" unless Mom already started the bot. |

### (b) API / permission

- TDLib: `INTERNET`, foreground service for persistent connection, ~15–30 MB native `.so` per ABI. Build via [tdlib/td example/android](https://github.com/tdlib/td/tree/master/example/android) or prebuilt JitPack artifacts.
- Bot API: `INTERNET` only; bot token from @BotFather.

### (c) Play Store policy risk

- TDLib user client: **Medium** — allowed if disclosed; Telegram ToS permits third-party clients using TDLib. Must not misrepresent as official Telegram.
- Bot API: **Low**.

### (d) Effort / fragility

| Path | Effort | Fragility |
|---|---|---|
| Intent deep link | **Low** | Low |
| TDLib JNI integration | **High (1–2 weeks)** | Medium — native build/ABI matrix, auth flow UI, session persistence, FCM-less background on MIUI |
| Bot API | **Low** | Low scope but **wrong product fit** for contact messaging |

### Verdict: **GO-WITH-CAVEATS**

- **GO** for intent deep link (immediate).
- **GO-WITH-CAVEATS** for TDLib if you need headless send/receive as user account — significant native integration effort, but officially supported and sideload-friendly.
- **NO-GO** for Bot API as primary "message contact X" tool — wrong abstraction.

**Recommendation:** Intent first; TDLib as V2.5 if WhatsApp-style headless messaging is a hard requirement for Telegram specifically.

---

## 3. Spotify Integration

### (a) Technically possible?

**Yes**, via `SpotifyAppRemote` SDK (playback control) + optional Web API (URI/search resolution). SDK is still published on [Spotify for Developers](https://developer.spotify.com/documentation/android) and GitHub [spotify/android-sdk](https://github.com/spotify/android-sdk). Requires Spotify app installed on device.

### (b) API / permission

- App Remote: Developer Dashboard app registration, `app-remote-control` scope (built into App Remote auth). No special Android permission.
- Web API: OAuth token with scopes like `playlist-read-private`, `user-read-playback-state` for search/URI resolution.

### (c) Play Store policy risk

**Low** — official SDK, standard OAuth. Must comply with Spotify Developer Terms (no storing/streaming content outside Spotify app except via permitted APIs).

### (d) Effort / fragility

| Concern | Detail |
|---|---|
| Effort | **Medium (2–4 days)** — Dashboard setup, connection lifecycle, PlayerApi wrappers |
| Android 14 fragility | **Known issue** — App Remote connection may hang if neither your app nor Spotify is foreground ([android-sdk#361](https://github.com/spotify/android-sdk/issues/361)). Workaround: ensure user-facing trigger brings app to foreground before connect. SDK 0.8.0+ recommended. |
| MIUI | No special block beyond background activity restrictions — same as Android 14 issue. |

### Verdict: **GO-WITH-CAVEATS**

Implement with foreground-aware connection (trigger playback commands only when agent UI/service is active or after explicit "open Spotify" step). Web API for "play song X" search is stable.

---

## 4. AccessibilityService UI Automation

### (a) Technically possible?

**Yes** on stock Android. `AccessibilityService` + BFS node crawl + `ACTION_CLICK` / `ACTION_SET_TEXT` / `GestureDescription` works for WhatsApp, Telegram, and generic apps. This is how automation apps (Tasker plugins, auto-clickers) operate.

### (b) API / permission

- `BIND_ACCESSIBILITY_SERVICE` + user manually enabling in Settings → Accessibility.
- No root. Optional `SYSTEM_ALERT_WINDOW` for overlay HUD (separate from a11y).

### (c) Play Store policy risk

**Medium–High for Play Store; Low for sideload.**

Current Google Play policy ([AccessibilityService API policy](https://support.google.com/googleplay/android-developer/answer/10964491), updated 2024–2025):

- Automation/assistant apps are **explicitly listed as NOT accessibility tools** — cannot set `isAccessibilityTool=true`.
- Must complete **Accessibility Declaration Form** in Play Console.
- Must show **prominent in-app disclosure + affirmative consent** before enabling.
- Policy states: *"Don't use the API to autonomously initiate, plan and execute actions or decisions"* — gray area for a voice agent that clicks Send after user command. User-initiated actions are safer than fully autonomous scraping.
- **Android APM (Advanced Protection Mode, 2026):** On devices with APM enabled, non-accessibility-tool apps may be **blocked from using Accessibility API entirely** ([Malwarebytes report, Mar 2026](https://www.malwarebytes.com/blog/mobile/2026/03/google-cracks-down-on-android-apps-abusing-accessibility)).

**Sideload / personal use:** Play review does not apply. User manually grants accessibility — fully viable for your POCO dev build.

### (d) Effort / fragility

| Concern | Detail |
|---|---|
| Effort | **High (1–2 weeks for robust crawler)** |
| Fragility | **Very high** — WhatsApp/Telegram UI changes break selectors; MIUI floating windows interfere; multi-language layouts; timing races |

### Verdict: **GO-WITH-CAVEATS**

- **GO** for sideloaded personal agent as WhatsApp/Telegram fallback.
- **GO-WITH-CAVEATS** for Play Store — declaration burden + policy language against autonomous action + APM users blocked.
- Prefer intent deep links first; use a11y only when intent path insufficient.

---

## 5. Call Audio Interception (Call Agent Module)

### (a) Technically possible on stock non-root?

**NO** for the spec's goal (capture both call sides → STT → LLM → TTS back into call uplink) on a normal third-party app.

Android 10+ ([Sharing audio input](https://developer.android.com/guide/topics/media/sharing-audio-input)):

- `VOICE_UPLINK`, `VOICE_DOWNLINK`, `VOICE_CALL` sources require **privileged app** with `CAPTURE_AUDIO_OUTPUT` (system/pre-installed) **or** accessibility service in limited cases — community testing shows even a11y services don't get reliable duplex call audio on Pixel/Android 10+.
- `InCallService` gives call state/metadata, not raw duplex PCM to unprivileged apps.
- **Shizuku + shell UID** workarounds exist ([ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder)) — not stock, requires user to install Shizuku and grant ADB/shell privileges. Fragile across OEMs/Android versions.

POCO M4 Pro 5G / MIUI: no known exemption. Same restrictions as AOSP + possible MIUI telecom hooks.

**Partial alternative:** Agent answers call, puts on speaker, records **mic only** (hears remote party via speaker bleed) — poor quality, not true duplex injection.

### (b) API / permission

Would need: `READ_PHONE_STATE`, `MANAGE_OWN_CALLS`, `InCallService`, `RECORD_AUDIO`, `CAPTURE_AUDIO_OUTPUT` (blocked for normal apps), `MODIFY_AUDIO_ROUTING` (signature).

### (c) Play Store policy risk

**Very High** — call recording laws vary by jurisdiction; Google Play restricts call recording apps in many regions. Duplex AI call agent would face scrutiny.

### (d) Effort / fragility

**Very high** even with Shizuku; **impossible** per spec on stock non-root without degraded UX.

### Verdict: **NO-GO** (as specified)

Drop full duplex call agent from V2 roadmap unless:
1. User accepts Shizuku dependency + legal risk, or
2. Scope reduces to "announce caller / answer-hangup / open speakerphone" without AI in the call path.

---

## 6. WiFi / Bluetooth Toggles

### (a) Still blocked for targetSdk 34?

**Yes.**

- `WifiManager.setWifiEnabled()` — deprecated API 29; returns `false` for apps targeting Q+ ([WifiManager docs](https://developer.android.com/reference/android/net/wifi/WifiManager#setWifiEnabled(boolean))).
- `BluetoothAdapter.enable()` / `disable()` — deprecated API 33; returns `false` for apps targeting Tiramisu+ ([BluetoothAdapter docs](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#enable())).

Confirmed still current for `targetSdk 34`. Your `SettingsTool.kt` comment is correct.

### (b) Alternatives

| Method | Works? | User step? |
|---|---|---|
| `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` | **Yes** | Opens system connectivity panel — user taps WiFi |
| `Settings.ACTION_WIFI_SETTINGS` / `ACTION_BLUETOOTH_SETTINGS` | **Yes** | Opens full settings |
| `BluetoothAdapter.ACTION_REQUEST_ENABLE` | **Yes (enable only)** | System dialog; cannot disable programmatically |
| **`TileService` (Quick Settings tile)** | **Partial** | Tile can call `startActivityAndCollapse(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)` — same as opening panel. **Cannot** silently toggle WiFi/BT; SystemUI's internal `WifiTile` uses privileged APIs your app cannot access ([AOSP WifiTile source](https://github.com/StatiXOS/android_frameworks_base/blob/master/packages/SystemUI/src/com/android/systemui/qs/tiles/WifiTile.java)). |

### (c) Play Store policy risk

**Low** — opening settings panels is standard.

### (d) Effort / fragility

- Settings panel intent: **Low (hours)** — already noted in SettingsTool V2 comment.
- Quick Settings Tile: **Low–Medium** — nice UX ("Agent WiFi panel" tile), still requires user tap. Must declare `BIND_QUICK_SETTINGS_TILE`.

### Verdict: **GO-WITH-CAVEATS**

Direct toggle: **NO-GO**. Panel/tile shortcut: **GO**. Agent should say *"Opening connectivity settings — tap WiFi to toggle"* rather than claim it toggled WiFi.

---

## Summary Table

| Module | Verdict | Best V2 path |
|---|---|---|
| WhatsApp | **GO-WITH-CAVEATS** | Intent deep link → a11y fallback; skip open-wa for primary number |
| Telegram | **GO-WITH-CAVEATS** | Intent deep link; TDLib only if headless required |
| Spotify | **GO-WITH-CAVEATS** | App Remote + Web API; foreground-aware connect |
| AccessibilityService | **GO-WITH-CAVEATS** | Sideload OK; Play Store needs disclosure + fragile selectors |
| Call audio agent | **NO-GO** | Drop duplex AI-in-call unless Shizuku + legal acceptance |
| WiFi / Bluetooth | **GO-WITH-CAVEATS** | Settings Panel / QS Tile; no silent toggle |

---

## Suggested V2 Build Order (after this report)

1. **Dual wake word** (done in code — you add `.ppn` files + device tune)
2. **Intent-based messaging** (WhatsApp + Telegram deep links) — low risk, fast
3. **Spotify App Remote tool** — official, moderate effort
4. **AccessibilityService crawler** — high effort, sideload only initially
5. **Settings panel / QS tile** for WiFi/BT — honest UX
6. **Defer:** call agent, open-wa/Termux, TDLib until core agent is stable
