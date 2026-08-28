package com.agent.ai.data.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject

/**
 * Device settings the agent can control or shortcut to system panels.
 * WiFi/Bluetooth cannot be toggled silently on API 29+ — opens panels instead.
 */
class SettingsTool(private val context: Context) : AgentTool {

    override val name = "toggle_setting"
    override val description = "Toggle flashlight or open WiFi/Bluetooth/connectivity settings panels. Use volume_control for volume."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "setting_name": {
              "type": "string",
              "enum": ["FLASHLIGHT", "VOLUME_UP", "VOLUME_DOWN", "WIFI", "BLUETOOTH", "CONNECTIVITY", "DND"]
            },
            "setting_state": { "type": "boolean", "description": "Only for FLASHLIGHT: true=on, false=off" }
          },
          "required": ["setting_name"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        return when (params.optString("setting_name", "").uppercase()) {
            "FLASHLIGHT" -> toggleFlashlight(params.optBoolean("setting_state", true))
            "VOLUME_UP" -> adjustVolume(true)
            "VOLUME_DOWN" -> adjustVolume(false)
            "WIFI", "CONNECTIVITY" -> openConnectivityPanel()
            "BLUETOOTH" -> openBluetoothSettings()
            "DND" -> openDndSettings()
            "" -> AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "setting_name missing")
            else -> AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "Unsupported setting_name")
        }
    }

    private fun openConnectivityPanel(): AgentResult<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return openLegacyWifiSettings()
        }
        return try {
            val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            AgentResult.Success("Opened connectivity panel — tap WiFi or mobile data to toggle")
        } catch (e: ActivityNotFoundException) {
            openLegacyWifiSettings()
        } catch (e: Exception) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Could not open connectivity panel: ${e.message}", e)
        }
    }

    private fun openLegacyWifiSettings(): AgentResult<String> = try {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        AgentResult.Success("Opened WiFi settings")
    } catch (e: Exception) {
        AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "WiFi settings unavailable: ${e.message}", e)
    }

    private fun openBluetoothSettings(): AgentResult<String> = try {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        AgentResult.Success("Opened Bluetooth settings — toggle manually")
    } catch (e: Exception) {
        AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Bluetooth settings unavailable: ${e.message}", e)
    }

    private fun openDndSettings(): AgentResult<String> = try {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        } else {
            Intent(Settings.ACTION_SOUND_SETTINGS)
        }.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        context.startActivity(intent)
        AgentResult.Success("Opened Do Not Disturb / notification settings")
    } catch (e: Exception) {
        AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "DND settings unavailable: ${e.message}", e)
    }

    private fun toggleFlashlight(on: Boolean): AgentResult<String> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "CameraManager unavailable")
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return AgentResult.Error(ErrorCode.TOOL_TARGET_NOT_FOUND, "No camera with flash found")
            cameraManager.setTorchMode(cameraId, on)
            AgentResult.Success(if (on) "Flashlight on" else "Flashlight off")
        } catch (e: CameraAccessException) {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Camera busy or access denied: ${e.message}", e)
        }
    }

    private fun adjustVolume(up: Boolean): AgentResult<String> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "AudioManager unavailable")
        return try {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0
            )
            AgentResult.Success(if (up) "Volume up" else "Volume down")
        } catch (e: SecurityException) {
            AgentResult.Error(ErrorCode.TOOL_PERMISSION_DENIED, "Volume adjustment blocked", e)
        }
    }
}
