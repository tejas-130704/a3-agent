package com.agent.ai.data.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.agent.ai.core.AgentResult
import com.agent.ai.core.ErrorCode
import org.json.JSONObject

/**
 * Install / uninstall / check apps.
 * Android requires user confirmation for install & uninstall — this tool opens the right system UI.
 */
class ManageAppTool(private val context: Context) : AgentTool {

    override val name = "manage_app"
    override val description =
        "Check if an app is installed, open Play Store to install it, or open uninstall dialog. " +
        "Actions: CHECK_INSTALLED, INSTALL, UNINSTALL. User must tap Install/Uninstall on the system screen."
    override val parametersSchema = JSONObject("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["CHECK_INSTALLED", "INSTALL", "UNINSTALL"]
            },
            "app_name": {
              "type": "string",
              "description": "App name e.g. WhatsApp, Instagram, or package name com.example.app"
            },
            "apk_uri": {
              "type": "string",
              "description": "Optional file:// or content:// URI to sideload an APK (INSTALL only)"
            }
          },
          "required": ["action", "app_name"]
        }
    """.trimIndent())

    override suspend fun execute(params: JSONObject): AgentResult<String> {
        val action = normalizeAction(params.optString("action", ""))
        val appName = params.optString("app_name", "").trim()
        if (appName.isEmpty()) {
            return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "app_name was empty")
        }

        return when (action) {
            "CHECK_INSTALLED" -> checkInstalled(appName)
            "INSTALL" -> installApp(appName, params.optString("apk_uri", "").trim())
            "UNINSTALL" -> uninstallApp(appName)
            "" -> AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "action missing — use INSTALL, UNINSTALL, or CHECK_INSTALLED")
            else -> AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "Unknown action '$action'")
        }
    }

    private fun normalizeAction(raw: String): String = when (raw.trim().lowercase()) {
        "install", "download", "get", "add" -> "INSTALL"
        "uninstall", "remove", "delete" -> "UNINSTALL"
        "check", "check_installed", "is_installed", "status", "installed" -> "CHECK_INSTALLED"
        else -> raw.trim().uppercase()
    }

    private fun checkInstalled(appName: String): AgentResult<String> {
        val resolved = AppLookup.resolveInstalled(context, appName)
        val knownPkg = AppLookup.knownPackageFor(appName)
        val pkg = resolved?.packageName ?: knownPkg

        if (pkg != null && AppLookup.isInstalled(context, pkg)) {
            val label = resolved?.label ?: AppLookup.appLabel(context, pkg) ?: appName
            return AgentResult.Success("$label is installed ($pkg)")
        }
        return AgentResult.Success("$appName is not installed")
    }

    private fun installApp(appName: String, apkUri: String): AgentResult<String> {
        val installed = AppLookup.resolveInstalled(context, appName)
        if (installed != null) {
            return AgentResult.Success("${installed.label} is already installed — say open app to launch it")
        }

        val knownPkg = AppLookup.knownPackageFor(appName)
        if (knownPkg != null && AppLookup.isInstalled(context, knownPkg)) {
            val label = AppLookup.appLabel(context, knownPkg) ?: appName
            return AgentResult.Success("$label is already installed")
        }

        if (apkUri.isNotBlank()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                openInstallUnknownAppsSettings()
                return AgentResult.Success(
                    "Allow installs from this source in Settings, then try again. Opening settings now."
                )
            }
            val uri = parseUri(apkUri)
                ?: return AgentResult.Error(ErrorCode.TOOL_INVALID_PARAMS, "Invalid apk_uri: $apkUri")
            return if (AppLookup.openApkInstaller(context, uri)) {
                AgentResult.Success("Opened APK installer — tap Install to confirm")
            } else {
                AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Could not open APK installer")
            }
        }

        val storePkg = AppLookup.resolveForStore(context, appName) ?: knownPkg
        return if (AppLookup.openPlayStore(context, appName, storePkg)) {
            val hint = if (storePkg != null) "Play Store page opened" else "Play Store search opened"
            AgentResult.Success("$hint for $appName — tap Install to complete")
        } else {
            AgentResult.Error(
                ErrorCode.TOOL_EXECUTION_FAILED,
                "Could not open Play Store — install Google Play or try a exact app name"
            )
        }
    }

    private fun uninstallApp(appName: String): AgentResult<String> {
        val resolved = AppLookup.resolveInstalled(context, appName)
            ?: AppLookup.knownPackageFor(appName)?.let { pkg ->
                if (AppLookup.isInstalled(context, pkg)) {
                    AppLookup.ResolveResult(pkg, AppLookup.appLabel(context, pkg) ?: appName)
                } else null
            }
            ?: return AgentResult.Error(
                ErrorCode.TOOL_TARGET_NOT_FOUND,
                "$appName is not installed — nothing to uninstall"
            )

        return if (AppLookup.openUninstallDialog(context, resolved.packageName)) {
            AgentResult.Success("Opened uninstall for ${resolved.label} — tap Uninstall to confirm")
        } else {
            AgentResult.Error(ErrorCode.TOOL_EXECUTION_FAILED, "Could not open uninstall screen")
        }
    }

    private fun openInstallUnknownAppsSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${context.packageName}".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                // Best effort
            }
        }
    }

    private fun parseUri(raw: String): Uri? = try {
        raw.toUri()
    } catch (_: Exception) {
        null
    }
}
