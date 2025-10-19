package com.nitish.still

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.*

const val LABEL_LEISURE = "Leisure"
const val LABEL_IMPORTANT = "Important"
const val LABEL_UNLABELED = "Unlabeled"

/**
 * Returns true if the ApplicationInfo looks like a leisure app (audio/video/social/image/game/shopping/streaming).
 * This is conservative: prefer official Android category, then label-based checks.
 * Excludes known system/keyboard/banking/productivity apps.
 */
// AppClassifier.kt (replace existing isLeisureCategory function with this)
// Paste this implementation (replace the old function)
// --- Replace your existing isLeisureCategory(...) with this exact function ---
// Replace your existing isLeisureCategory(...) with this exact function
fun isLeisureCategory(appInfo: ApplicationInfo, pm: PackageManager): Boolean {
    val pkg = (appInfo.packageName ?: "").lowercase(Locale.getDefault())

    // PRECISE blacklist: prefixes or full package ids that we want to exclude.
    // Avoid generic substrings like "android" which match many valid packages.
    val blacklistPrefixes = listOf(
        "com.android.systemui",      // system UI
        "com.android.providers",    // providers
        "com.google.android.googlequicksearchbox", // search app if you want excluded
        "com.google.android.gms",   // google play services
        "com.google.android.inputmethod", // Gboard IME
        "com.sec.android",          // samsung system packages (example)
        "com.motorola",             // vendor/system OEM packages (optional)
        "android",                   // keep as a last-resort token? -> **we will not use this**
        "Main components"
    )

    // Only blacklist if pkg startsWith one of these prefixes (not contains)
    if (blacklistPrefixes.any { prefix -> prefix.isNotBlank() && pkg.startsWith(prefix) }) {
        android.util.Log.d("SettingsDebug", "isLeisureCategory: blacklisted pkg=$pkg")
        return false
    }

    // If Android category is set (API 26+), trust it for leisure groups
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        when (appInfo.category) {
            ApplicationInfo.CATEGORY_GAME,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_SOCIAL,
            ApplicationInfo.CATEGORY_IMAGE,
            ApplicationInfo.CATEGORY_AUDIO -> {
                android.util.Log.d("SettingsDebug", "isLeisureCategory: category match pkg=$pkg category=${appInfo.category}")
                return true
            }
            else -> { /* continue */ }
        }
    }

    // Conservative whitelist of keywords for leisure/shopping/video/music.
    // These are tested with contains() on package name or label.
    // --- Replace your pkgWhitelist block with this one ---
    val pkgWhitelist = listOf(
        // 🎬 Streaming / Video / Entertainment
        "youtube", "netflix", "primevideo", "prime", "disney", "hotstar",
        "hulu", "twitch", "sonyliv", "zee5", "mxplayer", "voot", "altbalaji",
        "crunchyroll", "animelab", "plex", "pluto", "sling", "apple.tv", "jiocinema",
        "ott", "player", "video", "mediaclient",

        // 🎵 Music / Audio / Podcasts
        "spotify", "gaana", "wynk", "saavn", "soundcloud", "music", "audiomack",
        "podcast", "deezer", "ytmusic", "radio", "audiobooks", "audible",

        // 💬 Social Media / Chat / Community
        "whatsapp", "instagram", "facebook", "messenger", "snapchat",
        "telegram", "twitter", "x.", "threads", "reddit", "discord",
        "tiktok", "wechat", "line", "signal", "skype", "clubhouse",
        "pinterest", "tumblr", "be.real", "mastodon",

        // 🛒 Shopping / E-commerce
        "amazon", "mshop", "flipkart", "myntra", "ajio", "snapdeal",
        "meesho", "shopee", "ebay", "etsy", "aliexpress", "bigbasket",
        "jiomart", "grofers", "blinkit", "nykaa", "tatacliq", "zara",
        "shein", "paytm.mall", "shopping", "shop",

        // 🎮 Games / Game platforms
        "game", "games", "playstation", "xbox", "steam", "epicgames",
        "pubg", "battlegrounds", "freefire", "callofduty", "clash", "subwaysurf",
        "candycrush", "pokemon", "roblox", "minecraft", "ludo", "8ball", "bgmi",

        // ❤️ Dating / Lifestyle
        "tinder", "bumble", "okcupid", "hinge", "coffeemeetsbagel", "grindr", "badoo",
        "match", "tan tan", "happn", "woo", "iris", "cupid", "jeevansathi", "shaadi",

        // 🗞️ News / Fun / Distraction
        "inshorts", "dailyhunt", "reddit", "buzzfeed", "9gag", "memes", "funny", "quora",
        "news", "feed", "shorts", "reels", "takatak"
    )

    if (pkgWhitelist.any { pkg.contains(it) }) {
        android.util.Log.d("SettingsDebug", "isLeisureCategory: pkgWhitelist matched pkg=$pkg")
        return true
    }

    // Try app label (human name) as final check
    try {
        val label = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.getDefault())
        if (pkgWhitelist.any { label.contains(it) }) {
            android.util.Log.d("SettingsDebug", "isLeisureCategory: label matched pkg=$pkg label=$label")
            return true
        }
    } catch (t: Throwable) {
        android.util.Log.w("SettingsDebug", "isLeisureCategory: label read failed for pkg=$pkg ${t.message}")
    }

    android.util.Log.d("SettingsDebug", "isLeisureCategory: default false for pkg=$pkg")
    return false
}

fun getTop5LeisureApps(context: android.content.Context): List<WeeklyAppUsage> {
    val weeklyUsage = (0..6).flatMap { dayIndex ->
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayIndex - 6) }
        getAppUsageForDay(context, cal).filter { isLeisureCategory(it.appInfo, context.packageManager) }
    }

    return weeklyUsage
        .groupBy { it.appInfo.packageName }
        .map { (packageName, usages) ->
            WeeklyAppUsage(
                packageName = packageName,
                totalUsage = usages.sumOf { it.usageTimeMillis } / 7
            )
        }
        .sortedByDescending { it.totalUsage }
        .take(5)
}

data class WeeklyAppUsage(val packageName: String, val totalUsage: Long)
