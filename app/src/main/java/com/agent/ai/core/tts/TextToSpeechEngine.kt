package com.agent.ai.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

class TextToSpeechEngine(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ready = false
                }
            }
        }
    }

    suspend fun speak(text: String): AgentResult<Unit> = withContext(Dispatchers.Main) {
        val engine = tts ?: return@withContext AgentResult.Error(ErrorCode.TTS_INIT_FAILED, "TextToSpeech was never initialized")
        if (!ready) return@withContext AgentResult.Error(ErrorCode.TTS_INIT_FAILED, "TTS init did not complete successfully before speak() call")

        suspendCancellableCoroutine<AgentResult<Unit>> { cont ->
            val utteranceId = "agent_${System.currentTimeMillis()}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(AgentResult.Success(Unit))
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(AgentResult.Error(ErrorCode.TTS_INIT_FAILED, "TTS playback failed mid-utterance"))
                }
            })
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR && cont.isActive) {
                cont.resume(AgentResult.Error(ErrorCode.TTS_INIT_FAILED, "engine.speak() returned ERROR immediately"))
            }
        }
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
