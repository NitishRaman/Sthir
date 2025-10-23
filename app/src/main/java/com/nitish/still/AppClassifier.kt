package com.nitish.still

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.*
import kotlin.collections.LinkedHashMap

const val LABEL_LEISURE = "Leisure"
const val LABEL_IMPORTANT = "Important"
const val LABEL_UNLABELED = "Unlabeled"

fun isLeisureCategory(appInfo: ApplicationInfo, pm: PackageManager): Boolean {
    val pkg = (appInfo.packageName ?: "").lowercase(Locale.getDefault())

    val blacklistPrefixes = setOf(
        "com.android.", "android.", "com.google.android.gms", "com.google.android.gsf",
        "com.google.android.inputmethod", "com.microsoft.office", "com.microsoft.teams",
        "com.slack", "com.skype", "com.android.vending",
        "com.sec.android", "com.samsung.", "com.motorola.", "com.miui.", "com.coloros.",
        "com.huawei.", "com.oneplus.", "com.realme.", "com.paytm", "com.phonepe.app",
        "net.one97.paytm"
    )

    if (blacklistPrefixes.any { pkg.startsWith(it) }) return false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        when (appInfo.category) {
            ApplicationInfo.CATEGORY_GAME,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_SOCIAL,
            ApplicationInfo.CATEGORY_IMAGE,
            ApplicationInfo.CATEGORY_AUDIO -> return true
        }
    }

    val leisureKeywords = listOf(
        "youtube", "ytmusic", "netflix", "hotstar", "disney", "primevideo", "amazonvideo",
        "sonyliv", "zee5", "mxplayer", "twitch", "crunchyroll", "plex",
        "spotify", "gaana", "wynk", "soundcloud", "deezer", "audible", "podcast",
        "whatsapp", "instagram", "facebook", "snapchat", "telegram", "reddit", "tiktok",
        "twitter", "discord", "tinder", "bumble", "flipkart", "amazon", "myntra",
        "pubg", "freefire", "roblox", "minecraft", "fortnite", "clash",
        "reels", "shorts", "moj", "takatak", "josh", "9gag", "memes"
    )

    // Prefer matching against package name using keywords that are at least length 4 to reduce false positives.
    if (leisureKeywords.any { it.length >= 4 && pkg.contains(it) }) return true

    try {
        val label = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.getDefault())
        if (leisureKeywords.any { label.contains(it) }) return true
    } catch (_: Throwable) {
    }

    return false
}


fun getTop5LeisureApps(context: Context): List<WeeklyAppUsage> {
    val pm = context.packageManager
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return emptyList()

    val now = System.currentTimeMillis()
    val calStart = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -6)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val startTime = calStart.timeInMillis
    val endTime = now

    val totals = LinkedHashMap<String, Long>()

    try {
        val aggregated = try {
            @Suppress("UNCHECKED_CAST")
            usageStatsManager.queryAndAggregateUsageStats(startTime, endTime) as? Map<String, UsageStats>
        } catch (_: Throwable) {
            null
        }

        if (aggregated != null && aggregated.isNotEmpty()) {
            for ((pkg, us) in aggregated) {
                if (pkg.isNullOrBlank()) continue
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    val isSystemCore = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    if (isSystemCore) continue
                    if (isLeisureCategory(ai, pm)) {
                        totals[pkg] = (totals[pkg] ?: 0L) + (us?.totalTimeInForeground ?: 0L)
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
        } else {
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            for (us in stats) {
                val pkg = us.packageName ?: continue
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    val isSystemCore = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    if (isSystemCore) continue
                    if (isLeisureCategory(ai, pm)) {
                        totals[pkg] = (totals[pkg] ?: 0L) + us.totalTimeInForeground
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
        }
    } catch (_: SecurityException) {
        return emptyList()
    } catch (_: Throwable) {
        return emptyList()
    }

    return totals.entries
        .sortedByDescending { it.value }
        .take(5)
        .map { WeeklyAppUsage(it.key, it.value) }
}
