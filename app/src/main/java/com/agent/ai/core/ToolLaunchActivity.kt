package com.agent.ai.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Trampoline activity so tools running in the foreground service can open
 * Play Store / uninstall dialogs (Android 10+ blocks background activity starts).
 */
class ToolLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = readForwardIntent()
        if (forward != null) {
            try {
                startActivity(forward)
            } catch (_: Exception) {
                // Ignore — caller gets failure via no UI shown
            }
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun readForwardIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_FORWARD_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_FORWARD_INTENT)
        }
    }

    companion object {
        const val EXTRA_FORWARD_INTENT = "forward_intent"

        fun launch(context: Context, forward: Intent): Boolean {
            return try {
                val trampoline = Intent(context, ToolLaunchActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(EXTRA_FORWARD_INTENT, forward.apply {
                        flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }
                context.startActivity(trampoline)
                true
            } catch (_: Exception) {
                try {
                    context.startActivity(forward.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }
}
