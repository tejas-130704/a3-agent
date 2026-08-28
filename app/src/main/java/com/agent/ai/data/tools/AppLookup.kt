package com.agent.ai.data.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.agent.ai.core.ToolLaunchActivity

/**
 * Shared app name → package resolution for open/install/uninstall tools.
 * Uses launcher queries (Android 11+) plus known Play Store package aliases.
 */
object AppLookup {

    data class LauncherApp(val packageName: String, val label: String)

    data class ResolveResult(
        val packageName: String,
        val label: String
    )

    /** Normalized name → Play Store package id (works even when app not installed). */
    val KNOWN_PACKAGES: Map<String, String> = mapOf(
        "settings" to "com.android.settings",
        "chrome" to "com.android.chrome",
        "camera" to "com.android.camera",
        "gallery" to "com.miui.gallery",
        "photos" to "com.google.android.apps.photos",
        "youtube" to "com.google.android.youtube",
        "maps" to "com.google.android.apps.maps",
        "googlemaps" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "spotify" to "com.spotify.music",
        "whatsapp" to "com.whatsapp",
        "whatsappmessenger" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "snapchat" to "com.snapchat.android",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "netflix" to "com.netflix.mediaclient",
        "amazon" to "in.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "swiggy" to "in.swiggy.android",
        "zomato" to "com.application.zomato",
        "paytm" to "net.one97.paytm",
        "phonepe" to "com.phonepe.app",
        "googlepay" to "com.google.android.apps.nbu.paisa.user",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "zoom" to "us.zoom.videomeetings",
        "discord" to "com.discord",
        "reddit" to "com.reddit.frontpage",
        "linkedin" to "com.linkedin.android",
        "mi notes" to "com.miui.notes",
        "minotes" to "com.miui.notes",
        "notes" to "com.miui.notes",
        "googlekeep" to "com.google.android.keep",
        "keep" to "com.google.android.keep"
    )

    fun normalizeName(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun knownPackageFor(query: String): String? =
        KNOWN_PACKAGES[normalizeName(query)]

    fun isInstalled(context: Context, packageName: String): Boolean =
        canLaunch(context, packageName) || packageInstalled(context, packageName)

    fun canLaunch(context: Context, packageName: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(packageName) != null

    fun appLabel(context: Context, packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        null
    }

    /** Resolve an installed app by friendly name, package, or alias. */
    fun resolveInstalled(context: Context, query: String): ResolveResult? {
        val normalized = normalizeName(query)

        knownPackageFor(query)?.let { pkg ->
            if (canLaunch(context, pkg)) {
                return ResolveResult(pkg, appLabel(context, pkg) ?: query)
            }
        }

        if (query.contains('.') && canLaunch(context, query)) {
            return ResolveResult(query, appLabel(context, query) ?: query)
        }

        val launcherApps = loadLauncherApps(context)
        var bestPkg: String? = null
        var bestLabel = query
        var bestScore = Int.MIN_VALUE

        for (app in launcherApps) {
            val labelNorm = normalizeName(app.label)
            val pkgNorm = normalizeName(app.packageName.substringAfterLast('.'))
            val score = when {
                app.label.equals(query, ignoreCase = true) -> 100
                labelNorm == normalized -> 95
                app.packageName.equals(query, ignoreCase = true) -> 90
                app.label.startsWith(query, ignoreCase = true) -> 80
                labelNorm.startsWith(normalized) -> 75
                app.label.contains(query, ignoreCase = true) -> 60
                labelNorm.contains(normalized) -> 55
                pkgNorm.contains(normalized) -> 50
                app.packageName.contains(normalized, ignoreCase = true) -> 45
                fuzzySimilarity(labelNorm, normalized) >= 0.82 -> 40
                else -> Int.MIN_VALUE
            }
            if (score > bestScore) {
                bestScore = score
                bestPkg = app.packageName
                bestLabel = app.label
            }
        }
        return bestPkg?.let { ResolveResult(it, bestLabel) }
    }

    /** Best package id for Play Store — known alias or installed match. */
    fun resolveForStore(context: Context, appName: String): String? {
        knownPackageFor(appName)?.let { return it }
        return resolveInstalled(context, appName)?.packageName
    }

    fun openPlayStore(context: Context, appName: String, packageHint: String? = null): Boolean {
        val pkg = packageHint ?: resolveForStore(context, appName)
        val storePackages = listOf(
            "com.android.vending",
            "com.xiaomi.mipicks",
            null
        )
        if (!pkg.isNullOrBlank()) {
            val uris = listOf(
                "market://details?id=$pkg".toUri(),
                "https://play.google.com/store/apps/details?id=$pkg".toUri()
            )
            for (uri in uris) {
                for (storePkg in storePackages) {
                    if (startView(context, uri, storePkg)) return true
                }
            }
        }
        val searchUri = "https://play.google.com/store/search?q=${Uri.encode(appName)}&c=apps".toUri()
        for (storePkg in storePackages) {
            if (startView(context, searchUri, storePkg)) return true
        }
        return startView(context, "market://search?q=${Uri.encode(appName)}&c=apps".toUri(), null)
    }

    fun openApkInstaller(context: Context, apkUri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        return ToolLaunchActivity.launch(context, intent)
    }

    fun openUninstallDialog(context: Context, packageName: String): Boolean {
        val pkgUri = "package:$packageName".toUri()
        val attempts = listOf(
            Intent(Intent.ACTION_DELETE, pkgUri),
            @Suppress("DEPRECATION")
            Intent(Intent.ACTION_UNINSTALL_PACKAGE, pkgUri),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        )
        for (base in attempts) {
            if (ToolLaunchActivity.launch(context, base)) return true
        }
        return false
    }

    private fun startView(context: Context, uri: Uri, packageName: String?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            packageName?.let { setPackage(it) }
        }
        return ToolLaunchActivity.launch(context, intent)
    }

    private fun packageInstalled(context: Context, packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun loadLauncherApps(context: Context): List<LauncherApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_META_DATA
        }
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, flags)
            .mapNotNull { resolve ->
                val pkg = resolve.activityInfo?.packageName ?: return@mapNotNull null
                val label = resolve.loadLabel(pm)?.toString()?.trim().orEmpty()
                if (label.isEmpty()) null else LauncherApp(pkg, label)
            }
            .distinctBy { it.packageName }
    }

    private fun fuzzySimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }
}
