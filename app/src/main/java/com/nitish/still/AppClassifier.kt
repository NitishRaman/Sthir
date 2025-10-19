package com.nitish.still

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.*

const val LABEL_LEISURE = "Leisure"
const val LABEL_IMPORTANT = "Important"
const val LABEL_UNLABELED = "Unlabeled"

/**
 * Final leisure classifier for Sthir.
 * Marks apps as "leisure" if they belong to OTT, streaming, gaming, social media, shopping, or music.
 * Ignores banking, productivity, system, education, and utility apps.
 */
fun isLeisureCategory(appInfo: ApplicationInfo, pm: PackageManager): Boolean {
    val pkg = (appInfo.packageName ?: "").lowercase(Locale.getDefault())

    // 🚫 System / Productivity / Bank / Utility blacklist
    val blacklistPrefixes = listOf(
        "com.android.",
        "android.",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.inputmethod", // Gboard
        "com.google.android.apps.docs", // Drive
        "com.google.android.apps.work", // Workspace
        "com.google.android.calendar",
        "com.google.android.contacts",
        "com.google.android.deskclock",
        "com.google.android.keep",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.apps.maps",
        "com.google.android.apps.photos",
        "com.google.android.apps.translate",
        "com.google.android.apps.tachyon", // Duo
        "com.microsoft.office",
        "com.microsoft.teams",
        "com.slack",
        "com.skype",
        "com.google.android.gm", // Gmail
        "com.google.android.apps.meetings",
        "com.google.android.apps.classroom",
        "com.android.vending",
        "com.sec.android",
        "com.samsung.",
        "com.motorola.",
        "com.miui.",
        "com.coloros.",
        "com.huawei.",
        "com.oneplus.",
        "com.realme.",
        "com.paytm",
        "com.phonepe.app",
        "net.one97.paytm",
        "com.google.android.apps.walletnfcrel",
        "com.mobikwik_new",
        "com.axis.mobile",
        "com.hdfcbank.mobilebanking",
        "com.icicibank.imobile",
        "com.sbi.SBIFreedomPlus",
        "com.citi.citimobile",
        "com.kotak.neobank",
        "com.pnb.mobile",
        "com.unionbank.ecommerce",
        "in.co.bankofbaroda.mconnect",
        "com.boi.mpassbook",
        "com.dbs.in.digitalbank"
    )

    if (blacklistPrefixes.any { pkg.startsWith(it) }) {
        android.util.Log.d("SettingsDebug", "isLeisureCategory: blacklisted pkg=$pkg")
        return false
    }

    // 🎮 Trust Android system category if available
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        when (appInfo.category) {
            ApplicationInfo.CATEGORY_GAME,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_SOCIAL,
            ApplicationInfo.CATEGORY_IMAGE,
            ApplicationInfo.CATEGORY_AUDIO -> return true
        }
    }

    // 🎬🎧 Social + OTT + Gaming + Shopping + Music keywords
    val leisureKeywords = listOf(
        // 🎬 OTT / Video Streaming
        "youtube", "ytmusic", "netflix", "hotstar", "disney", "primevideo",
        "amazonvideo", "sonyliv", "zee5", "mxplayer", "voot", "altbalaji",
        "jiosaavn", "erosnow", "twitch", "crunchyroll", "animelab", "plex",
        "jiocinema", "sling", "hulu", "pluto", "ott", "watch", "player",

        // 🎵 Music / Audio / Podcasts
        "spotify", "gaana", "wynk", "saavn", "soundcloud", "music", "radio",
        "deezer", "audible", "audiobooks", "podcast", "pandora", "tunein",
        "stitcher", "fm", "mixcloud",

        // 💬 Social Media / Messaging
        "whatsapp", "instagram", "facebook", "messenger", "snapchat",
        "telegram", "twitter", "x.", "threads", "discord", "reddit",
        "tiktok", "wechat", "line", "signal", "clubhouse", "truthsocial",
        "pinterest", "tumblr", "bereal", "quora", "bluesky",

        // 🛍️ Shopping / Lifestyle
        "amazon", "flipkart", "myntra", "ajio", "meesho", "snapdeal",
        "nykaa", "tatacliq", "bigbasket", "jiomart", "blinkit", "grofers",
        "zara", "shein", "h&m", "shopping", "shopee", "ebay", "etsy",
        "lenskart", "bewakoof", "decathlon", "1mg", "pharmeasy",

        // 🎮 Gaming
        "game", "games", "pubg", "bgmi", "freefire", "callofduty",
        "clashofclans", "clashroyale", "subwaysurf", "templerun", "8ball",
        "roblox", "minecraft", "pokemon", "amongus", "fortnite", "ludo",
        "playstation", "xbox", "epicgames", "steam",

        // ❤️ Dating / Lifestyle / Fun
        "tinder", "bumble", "okcupid", "hinge", "badoo", "happn", "tan tan",
        "woo", "cupid", "iris", "coffeemeetsbagel", "grindr", "jeevansathi",
        "shaadi", "trulymadly",

        // 📺 Short Video / Entertainment
        "reels", "shorts", "moj", "takatak", "roposo", "chingari", "josh",
        "trell", "boloindya", "mx.takatak", "mxtakatak",

        // 📰 News / Fun
        "inshorts", "dailyhunt", "buzzfeed", "9gag", "funny", "memes"
    )

    if (leisureKeywords.any { pkg.contains(it) }) {
        android.util.Log.d("SettingsDebug", "isLeisureCategory: pkg match leisure pkg=$pkg")
        return true
    }

    // 🏷️ Last check — app label text
    try {
        val label = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.getDefault())
        if (leisureKeywords.any { label.contains(it) }) {
            android.util.Log.d("SettingsDebug", "isLeisureCategory: label matched leisure pkg=$pkg label=$label")
            return true
        }
    } catch (t: Throwable) {
        android.util.Log.w("SettingsDebug", "isLeisureCategory: label read failed for pkg=$pkg ${t.message}")
    }

    return false
}

/**
 * Returns top 5 leisure apps based on usage stats over the past 7 days.
 */
fun getTop5LeisureApps(context: android.content.Context): List<WeeklyAppUsage> {
    val weeklyUsage = (0..6).flatMap { dayIndex ->
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayIndex - 6) }
        getAppUsageForDay(context, cal).filter {
            isLeisureCategory(it.appInfo, context.packageManager)
        }
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
