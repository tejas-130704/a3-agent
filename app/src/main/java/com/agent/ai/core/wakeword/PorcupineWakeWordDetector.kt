package com.agent.ai.core.wakeword

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineActivationException
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.content.Context
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode

/** Labels for log output; index matches [KEYWORD_PATHS] passed to Porcupine. */
private val KEYWORD_LABELS = arrayOf("Hey A3", "Ok A3")

/**
 * Wraps Porcupine's PorcupineManager (handles its own mic capture thread).
 * Custom keywords "Hey A3" and "Ok A3" — trained .ppn files must be placed at
 * app/src/main/assets/hey_a3_android.ppn and ok_a3_android.ppn
 * (exported from Picovoice Console, Android platform).
 *
 * Swap point for V2: replace this class with an openWakeWord/TFLite
 * implementation behind the same onWake callback contract if licensing
 * becomes a blocker.
 */
class PorcupineWakeWordDetector(
    private val context: Context,
    private val accessKey: String,
    private val onWake: () -> Unit,
    private val onFatalError: (AgentResult.Error) -> Unit
) : WakeWordDetector {

    override val isConfigured: Boolean = true
    companion object {
        private const val TAG = "PorcupineWakeWord"

        /** Asset paths relative to src/main/assets/. Order defines keywordIndex in callbacks. */
        val KEYWORD_PATHS = arrayOf("hey_a3_android.ppn", "ok_a3_android.ppn")

        /**
         * One sensitivity per keyword. 0.0 = fewer false triggers, 1.0 = fewer misses.
         * Tune on-device; start at 0.6 for both and adjust per WAKE_WORD_SETUP.md.
         */
        val DEFAULT_SENSITIVITIES = floatArrayOf(0.6f, 0.6f)
    }

    private var manager: PorcupineManager? = null

    override fun start(): AgentResult<Unit> {
        return try {
            manager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPaths(KEYWORD_PATHS)
                .setSensitivities(DEFAULT_SENSITIVITIES)
                .build(context, object : PorcupineManagerCallback {
                    override fun invoke(keywordIndex: Int) {
                        val label = KEYWORD_LABELS.getOrElse(keywordIndex) { "index=$keywordIndex" }
                        android.util.Log.i(TAG, "Wake word detected: \"$label\" (index=$keywordIndex)")
                        onWake()
                    }
                })
            manager?.start()
            AgentResult.Success(Unit)
        } catch (e: PorcupineActivationException) {
            AgentResult.Error(ErrorCode.WAKEWORD_INIT_FAILED, "AccessKey invalid or activation limit reached: ${e.message}", e)
        } catch (e: PorcupineException) {
            AgentResult.Error(
                ErrorCode.WAKEWORD_INIT_FAILED,
                "Porcupine failed to load models ${KEYWORD_PATHS.joinToString()}: ${e.message}",
                e
            )
        } catch (e: SecurityException) {
            AgentResult.Error(ErrorCode.WAKEWORD_MIC_PERMISSION_DENIED, "RECORD_AUDIO permission missing at start() time", e)
        } catch (e: Exception) {
            onFatalError(AgentResult.Error(ErrorCode.WAKEWORD_AUDIO_ENGINE_ERROR, "Unexpected wake word failure: ${e.message}", e))
            AgentResult.Error(ErrorCode.WAKEWORD_AUDIO_ENGINE_ERROR, "Unexpected wake word failure: ${e.message}", e)
        }
    }

    override fun stop(): AgentResult<Unit> {
        return try {
            manager?.stop()
            manager?.delete()
            manager = null
            AgentResult.Success(Unit)
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.WAKEWORD_AUDIO_ENGINE_ERROR, "Failed to cleanly stop Porcupine: ${e.message}", e)
        }
    }
}
