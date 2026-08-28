package com.agent.ai.core.stt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class SpeechToText(private val context: Context) {

    companion object {
        private const val TAG = "SpeechToText"
        private const val LISTEN_TIMEOUT_MS = 20_000L
        private const val MIN_PARTIAL_LENGTH = 2
    }

    private var recognizer: SpeechRecognizer? = null

    suspend fun listenOnce(): AgentResult<String> = withContext(Dispatchers.Main) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext AgentResult.Error(ErrorCode.STT_NOT_AVAILABLE, "Microphone permission required")
        }
        val result = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
            listenOnceInternal(preferOffline = false).let { first ->
                if (first is AgentResult.Success) return@let first
                if (first is AgentResult.Error &&
                    (first.code == ErrorCode.STT_NO_MATCH || first.code == ErrorCode.STT_TIMEOUT)
                ) {
                    Log.i(TAG, "STT retrying with alternate settings after ${first.code}")
                    listenOnceInternal(preferOffline = true, extendedSilence = true)
                } else first
            }
        }
        result ?: AgentResult.Error(ErrorCode.STT_TIMEOUT, "Listening timed out — try again")
    }

    private suspend fun listenOnceInternal(
        preferOffline: Boolean,
        extendedSilence: Boolean = false
    ): AgentResult<String> = suspendCancellableCoroutine { cont ->
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            cont.resume(AgentResult.Error(ErrorCode.STT_NOT_AVAILABLE, "No STT service present on device"))
            return@suspendCancellableCoroutine
        }

        recognizer?.destroy()
        recognizer = null

        val r = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        recognizer = r
        var lastPartial = ""
        var finished = false

        fun finish(result: AgentResult<String>) {
            if (finished) return
            finished = true
            cont.resume(result)
            r.destroy()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                if (extendedSilence) 2500L else 1800L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                if (extendedSilence) 2000L else 1500L
            )
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = pickBestMatch(matches, lastPartial)
                if (best != null) {
                    finish(AgentResult.Success(best))
                } else {
                    finish(AgentResult.Error(ErrorCode.STT_NO_MATCH, "No speech recognized — try speaking closer to the mic"))
                }
            }

            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH && lastPartial.length >= MIN_PARTIAL_LENGTH) {
                    finish(AgentResult.Success(lastPartial.trim()))
                    return
                }
                val code = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ErrorCode.STT_TIMEOUT
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> ErrorCode.STT_NETWORK_ERROR
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ErrorCode.STT_NOT_AVAILABLE
                    else -> ErrorCode.STT_NOT_AVAILABLE
                }
                finish(AgentResult.Error(code, "SpeechRecognizer error code=$error"))
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { lastPartial = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        r.startListening(intent)
        cont.invokeOnCancellation {
            finished = true
            r.destroy()
        }
    }

    private fun pickBestMatch(matches: ArrayList<String>?, partialFallback: String): String? {
        val fromResults = matches?.firstOrNull { it.isNotBlank() }?.trim()
        if (!fromResults.isNullOrBlank()) return fromResults
        if (partialFallback.length >= MIN_PARTIAL_LENGTH) return partialFallback.trim()
        return null
    }

    suspend fun release() = withContext(Dispatchers.Main) {
        recognizer?.destroy()
        recognizer = null
    }
}
