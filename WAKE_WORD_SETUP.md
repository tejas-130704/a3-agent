# Wake Word Setup — "Hey A3" + "Ok A3"

## 1. Picovoice account & AccessKey

1. Go to [console.picovoice.ai](https://console.picovoice.ai) and sign up / log in.
2. Copy your **AccessKey** from the dashboard.
3. Paste it into `gradle.properties` as `PICOVOICE_ACCESS_KEY=...`

### Licensing / device limits

| Topic | Detail |
|---|---|
| **Free tier (historical)** | Was 1 active device per 30-day rolling window (Picovoice GitHub issues #613, #1433). Fine for solo dev on one phone. |
| **Aug 2026 status** | Picovoice discontinued the free tier on **2026-06-30**. Existing keys may still work on a paid/legacy plan — verify in your console. If init fails with `PorcupineActivationException`, you need a paid key or must swap to openWakeWord. |
| **Shipping to others** | Any public release beyond your personal device needs a commercial Picovoice license **or** a self-hosted wakeword engine. Plan for this before V2. |

---

## 2. Train both keywords in Picovoice Console

For **each** keyword:

1. Console → **Porcupine** → **Create Custom Keyword**
2. Enter the phrase and select platform **Android**
3. Review the **phonetic pronunciation preview** Picovoice generates
4. Download the `.ppn` file

| Keyword | Rename to | Asset path |
|---|---|---|
| Hey A3 | `hey_a3_android.ppn` | `app/src/main/assets/hey_a3_android.ppn` |
| Ok A3 | `ok_a3_android.ppn` | `app/src/main/assets/ok_a3_android.ppn` |

### Phonetic collision check (do this in Console before downloading)

When you create each keyword, note what Picovoice shows in the pronunciation preview and watch for:

- **"Hey A3"** — unlikely to collide with common English words; "A3" is distinctive. Watch for false triggers on "hey" + words starting with hard consonants in noisy environments.
- **"Ok A3"** — higher false-trigger risk: "ok" / "okay" are extremely common. If the preview phonetics look close to everyday speech, start sensitivity at **0.5** for `ok_a3_android.ppn` (index 1) instead of 0.6.

Record what the Console shows for each keyword in your test notes — I cannot see your Console session from here.

---

## 3. Code (already updated)

`PorcupineWakeWordDetector.kt` loads both keywords via:

```kotlin
.setKeywordPaths(arrayOf("hey_a3_android.ppn", "ok_a3_android.ppn"))
.setSensitivities(floatArrayOf(0.6f, 0.6f))  // tune in companion object
```

- `keywordIndex 0` = "Hey A3"
- `keywordIndex 1` = "Ok A3"
- Both call the same `onWake()` — behavior is identical
- Logcat tag `PorcupineWakeWord` logs which phrase fired

To tune sensitivity, edit `DEFAULT_SENSITIVITIES` in `PorcupineWakeWordDetector.kt` companion object.

---

## 4. On-device validation checklist

**Before testing:** apply MIUI settings from README (battery → No restrictions, Autostart ON).

### Build & install

```bash
./gradlew :app:installDebug
# or Run from Android Studio on POCO M4 Pro 5G
```

### ADB logcat (while testing)

```bash
adb logcat -s PorcupineWakeWord AgentOrchestrator AgentForegroundService
```

### Test matrix

| Test | Pass criteria |
|---|---|
| Say "Hey A3" | Log: `Wake word detected: "Hey A3"` → orchestrator goes LISTENING → THINKING |
| Say "Ok A3" | Log: `Wake word detected: "Ok A3"` → same flow |
| 5 unrelated sentences | No `PorcupineWakeWord` log lines |
| Service survives 10 min idle | Still responds to wake word (if not → MIUI kill, not wakeword bug) |

### Sensitivity tuning guide

| Symptom | Fix |
|---|---|
| False triggers on random speech | Lower sensitivity for that keyword by 0.1 (e.g. 0.6 → 0.5) |
| Misses when you say the phrase clearly | Raise sensitivity by 0.1 (e.g. 0.6 → 0.7) |
| "Ok A3" false-triggers but "Hey A3" is fine | Lower only index 1: `floatArrayOf(0.6f, 0.5f)` |

Rebuild and retest after each change. Document final values here:

```
Final sensitivities: Hey A3 = ___, Ok A3 = ___
False trigger notes: ___
Miss notes: ___
```

---

## 5. ADB permission fix (Linux host)

If `adb devices` shows "insufficient permissions", add a udev rule or run:

```bash
sudo adb kill-server && sudo adb start-server
adb devices
```

Accept the USB debugging prompt on the phone.
