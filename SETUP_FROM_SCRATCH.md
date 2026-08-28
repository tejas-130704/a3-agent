# A3 AI Agent — Setup From Scratch

Complete guide to build and run this project on a new machine.  
Estimated time: **30–60 minutes** (excluding wake-word training).

---

## 1. Requirements

### Hardware

| Item | Minimum |
|------|---------|
| **Development PC** | Linux, macOS, or Windows; 8 GB RAM (16 GB recommended) |
| **Android phone** | API 26+ (Android 8.0); tested on **POCO M4 Pro 5G** (MIUI/HyperOS) |
| **USB cable** | For sideload / debugging |

### Software

| Tool | Version | Purpose |
|------|---------|---------|
| **Android Studio** | Koala (2024.1+) or newer | IDE, SDK, emulator optional |
| **JDK** | 17 | Required by Android Gradle Plugin 8.5 |
| **Android SDK** | API **34** (compileSdk) | Build target |
| **Android SDK Platform** | API **26+** | minSdk on device |
| **Gradle** | 8.7 | Included via `./gradlew` after wrapper setup, or use Studio bundled Gradle |
| **adb** | Platform tools | Install APK on phone |

Install Android Studio → SDK Manager → install:
- Android SDK Platform 34
- Android SDK Build-Tools 34.x
- Android SDK Platform-Tools (adb)

### Accounts & API keys (required)

| Service | Required? | Get it from | Used for |
|---------|-----------|-------------|----------|
| **Groq API** | **Yes** | [console.groq.com](https://console.groq.com) → API Keys | LLM + tool calling |
| **Picovoice** | Optional* | [console.picovoice.ai](https://console.picovoice.ai) | Wake word "Hey A3" / "Ok A3" |
| **Google Maps** | Optional | Pre-installed on phone | Navigation tool |
| **Google Play / MIUI GetApps** | Optional | On device | Install apps tool |

\*Without Picovoice keys and `.ppn` files, the app still works — use **Talk to Agent** button only (no wake word).

### Wake word assets (optional)

Two custom Porcupine model files (you train/download from Picovoice):

```
app/src/main/assets/hey_a3_android.ppn
app/src/main/assets/ok_a3_android.ppn
```

See **[WAKE_WORD_SETUP.md](WAKE_WORD_SETUP.md)** for step-by-step training.

---

## 2. Get the project

### Option A — From portable ZIP

1. Unzip `AIAgent_portable.zip` to a folder, e.g. `~/Projects/AIAgent`
2. Open that folder in Android Studio

### Option B — From git (if available)

```bash
git clone <your-repo-url> AIAgent
cd AIAgent
```

---

## 3. Configure secrets

**Never commit real API keys.**

1. Copy the example file:

```bash
cp gradle.properties.example gradle.properties
```

2. Edit `gradle.properties`:

```properties
GROQ_API_KEY=gsk_your_real_groq_key_here
PICOVOICE_ACCESS_KEY=your_picovoice_access_key_here
```

3. **Alternative:** Leave `gradle.properties` empty and add Groq keys later in the app **Settings** tab on the phone (Picovoice key still needed in gradle or Settings for wake word).

4. If Android Studio asks for SDK path, it creates `local.properties` automatically (do not share this file — machine-specific).

---

## 4. Open in Android Studio

1. **File → Open** → select the `AIAgent` folder (contains `settings.gradle.kts`)
2. Wait for **Gradle Sync** to finish (downloads dependencies — first time may take 5–15 min)
3. If sync fails:
   - **File → Settings → Build → Gradle → Gradle JDK** → select **JDK 17**
   - **SDK Manager** → ensure API 34 installed

### Command-line build (optional)

```bash
cd AIAgent
chmod +x gradlew          # Linux/macOS, if gradlew present
export GRADLE_OPTS="-Xmx2048m"
./gradlew :app:assembleDebug
```

If `gradlew` is missing, use Android Studio’s **Terminal** tab after sync, or install Gradle 8.7+ and run `gradle :app:assembleDebug`.

Output APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. Install on phone

### Enable developer mode

1. **Settings → About phone** → tap **MIUI version** 7 times
2. **Settings → Additional settings → Developer options**
   - **USB debugging** → ON
   - **Install via USB** → ON (MIUI — required for adb install)

### Connect & install

```bash
adb devices                    # should show your device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If `INSTALL_FAILED_USER_RESTRICTED`: approve the install prompt on the phone.

Or in Android Studio: **Run ▶** with device selected.

---

## 6. First-run setup on phone

Open **AI Agent** app and complete:

| Step | Where | Why |
|------|-------|-----|
| **Grant permissions** | Popup on first launch | Mic, contacts, phone, calendar |
| **Groq API keys** | Settings tab | LLM (or pre-filled from gradle build) |
| **Picovoice key** | Settings tab | Wake word (optional) |
| **Battery: No restrictions** | Settings → Apps → AI Agent | MIUI kills background mic otherwise |
| **Autostart** | MIUI Security → Autostart | Keep foreground service alive |
| **Accessibility** | Settings → Accessibility → AI Agent | UI automation tool |
| **Notification access** | Settings → Notification access → AI Agent | Read notifications tool |

---

## 7. Verify it works

| Test | How |
|------|-----|
| Voice (no wake word) | Home → **Talk to Agent** → say *"What's my battery?"* |
| Wake word | Say *"Hey A3"* then *"Set volume to 50%"* (needs Picovoice + `.ppn`) |
| Agent Chat | Agent tab → type *"Open Chrome"* |
| Memory | Memory tab → should fill after a few commands |
| Web + Notes | *"Search IIT M.Tech admission and save summary to notes"* |

---

## 8. Troubleshooting

| Problem | Fix |
|---------|-----|
| Gradle sync OOM | `export GRADLE_OPTS="-Xmx2048m"` or Studio → heap 2048 MB |
| Groq HTTP 404 | Model updated in `GroqApiClient.DEFAULT_MODELS` — check [Groq docs](https://console.groq.com/docs/models) |
| Wake word silent fail | Missing `.ppn` files or invalid Picovoice key — use manual button |
| Service dies in background | MIUI battery + autostart (see §6) |
| Can't open WhatsApp / apps | Android 11+ `<queries>` in manifest — use latest source |
| adb not found | Install platform-tools; add to PATH |
| Install blocked | Enable **Install via USB** on MIUI |

**Logs:**

```bash
adb logcat -s AgentOrchestrator AgentForegroundService GroqApiClient SpeechToText
```

---

## 9. Project layout (quick reference)

```
AIAgent/
├── README.md                 # Overview
├── SETUP_FROM_SCRATCH.md     # This file
├── AGENTS.md                 # For AI coding agents
├── docs/PROJECT_GUIDE.md     # Full architecture
├── WAKE_WORD_SETUP.md        # Wake word .ppn files
├── FEASIBILITY_REPORT.md     # Platform limits
├── gradle.properties.example
├── app/src/main/java/com/agent/ai/   # All Kotlin source
└── app/src/main/assets/      # Put .ppn wake word models here
```

---

## 10. What’s not included in the ZIP

To keep the archive small and safe:

- `app/build/` — rebuild locally
- `.gradle/` — Gradle cache
- `local.properties` — your SDK path (auto-generated)
- `*.hprof` — JVM heap dumps
- Real API keys — use `gradle.properties.example`

---

## 11. Next steps for developers

- Read **[docs/PROJECT_GUIDE.md](docs/PROJECT_GUIDE.md)** before changing code
- Add tools via **`ToolRegistryFactory.kt`** (see project guide §11)
- Target device quirks: always test on real MIUI hardware for voice/background features

---

*Version: 0.1.0-v1 | Package: com.agent.ai | minSdk 26 | compileSdk 34*
