package com.nitish.still

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nitish.still.ui.theme.StillTheme
import java.util.concurrent.TimeUnit

data class AppUsageInfo(
    val appInfo: ApplicationInfo,
    val usageTimeMillis: Long,
)

data class TimerPreset(
    val name: String,
    val activityMinutes: Int,
    val breakSeconds: Int,
    val isCustom: Boolean = false
)

val presets = listOf(
    TimerPreset("Pomodoro", 25, 5 * 60),
    TimerPreset("Deep Learn (Work & Learn)", 60, 5 * 60),
    TimerPreset("EyeEase (Eye Health)", 20, 30),
    TimerPreset("Custom Rhythm", 45, 5 * 60, isCustom = true)
)

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isOnboarding = intent.getBooleanExtra("is_onboarding", false)
        setContent {
            StillTheme {
                SettingsScreen(isOnboarding = isOnboarding)
            }
        }
    }
}

// Define the labels consistently
const val LABEL_LEISURE = "Leisure"
const val LABEL_WORK = "Work"
const val LABEL_IMPORTANT = "Important"
const val LABEL_UNLABELED = "Unlabeled"

private fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, isOnboarding: Boolean) {
    val context = LocalContext.current
    val activity = (context as? Activity)
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }

    var hasPermission by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    val usageSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasPermission = hasUsageStatsPermission(context)
    }

    var selectedPresetName by remember { mutableStateOf(prefs.getString("selected_preset", "Pomodoro")) }
    var customActivity by remember { mutableStateOf(prefs.getInt("work_interval", 45).toString()) }
    var customBreak by remember { mutableStateOf((prefs.getInt("break_interval", 300) / 60).toString()) }

    val appUsageList = remember(hasPermission) {
        if (hasPermission) getLaunchableAppsWithUsage(context) else emptyList()
    }

    fun selectPreset(preset: TimerPreset) {
        selectedPresetName = preset.name
        prefs.edit().putString("selected_preset", preset.name).apply()
        if (!preset.isCustom) {
            prefs.edit()
                .putInt("work_interval", preset.activityMinutes)
                .putInt("break_interval", preset.breakSeconds)
                .apply()
        }
    }

    Scaffold(modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { activity?.finish() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Text("Timer Presets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                }
                items(presets) { preset ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectPreset(preset) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = selectedPresetName == preset.name,
                            onClick = { selectPreset(preset) }
                        )
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(preset.name, style = MaterialTheme.typography.titleMedium)
                            val breakText = if (preset.breakSeconds < 60) "${preset.breakSeconds} sec" else "${preset.breakSeconds / 60} min"
                            Text("Activity: ${preset.activityMinutes} min, Break: $breakText", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                if (selectedPresetName == "Custom Rhythm") {
                    item {
                        Column(modifier = Modifier.padding(16.dp)) {
                            TextField(
                                value = customActivity,
                                onValueChange = { 
                                    customActivity = it
                                    prefs.edit().putInt("work_interval", it.toIntOrNull() ?: 45).apply()
                                },
                                label = { Text("Custom Activity (minutes)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            TextField(
                                value = customBreak,
                                onValueChange = { 
                                    customBreak = it
                                    prefs.edit().putInt("break_interval", (it.toIntOrNull() ?: 5) * 60).apply()
                                },
                                label = { Text("Custom Break (minutes)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item { Divider(modifier = Modifier.padding(vertical = 16.dp)) }

                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("App Labels", style = MaterialTheme.typography.titleLarge)
                        Text("Categorize your apps. 'Important' apps will not be counted towards usage.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                
                if (!hasPermission) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            elevation = CardDefaults.cardElevation(4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = "Enable Usage Access", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = "To sort apps by usage and show usage time, please grant access in system settings.")
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    usageSettingsLauncher.launch(intent)
                                }) {
                                    Text("Open Settings")
                                }
                            }
                        }
                    }
                }

                items(appUsageList) { appUsage ->
                    AppLabelRow(appUsageInfo = appUsage, packageManager = context.packageManager)
                }
            }
            if (isOnboarding) {
                Button(
                    onClick = {
                        prefs.edit().putBoolean("onboarding_complete", true).apply()
                        activity?.setResult(Activity.RESULT_OK)
                        activity?.finish()
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Text("Finish Setup")
                }
            }
        }
    }
}

fun getLaunchableAppsWithUsage(context: Context): List<AppUsageInfo> {
    val pm = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
    val packageNames = resolveInfos.map { it.activityInfo.packageName }.toSet()

    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val time = System.currentTimeMillis()
    val usageStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, time - TimeUnit.DAYS.toMillis(7), time)
    val usageMap = usageStats.associate { it.packageName to it.totalTimeInForeground }

    return packageNames.mapNotNull { pkg ->
        try {
            val appInfo = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            AppUsageInfo(appInfo, usageMap[pkg] ?: 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }.sortedByDescending { it.usageTimeMillis }.take(20)
}

@SuppressLint("InlinedApi")
fun inferLabel(appInfo: ApplicationInfo, pm: PackageManager): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val categoryLabel = when (appInfo.category) {
            ApplicationInfo.CATEGORY_GAME,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_SOCIAL,
            ApplicationInfo.CATEGORY_IMAGE -> LABEL_LEISURE

            ApplicationInfo.CATEGORY_PRODUCTIVITY,
            ApplicationInfo.CATEGORY_NEWS -> LABEL_WORK

            ApplicationInfo.CATEGORY_MAPS,
            ApplicationInfo.CATEGORY_AUDIO -> LABEL_IMPORTANT

            else -> null
        }
        if (categoryLabel != null) return categoryLabel
    }

    val pkg = appInfo.packageName.lowercase()
    val appLabel = pm.getApplicationLabel(appInfo).toString().lowercase()

    val importantKeywords = listOf("bank", "pay", "wallet", "payments", "upi", "card", "netbank")
    val workKeywords = listOf("docs", "office", "slack", "teams", "drive", "calendar", "email", "outlook")
    val leisureKeywords = listOf("game", "music", "youtube", "netflix", "spotify", "video", "tiktok", "instagram", "social")

    if (importantKeywords.any { pkg.contains(it) || appLabel.contains(it) }) return LABEL_IMPORTANT
    if (workKeywords.any { pkg.contains(it) || appLabel.contains(it) }) return LABEL_WORK
    if (leisureKeywords.any { pkg.contains(it) || appLabel.contains(it) }) return LABEL_LEISURE

    return LABEL_UNLABELED
}

@Composable
fun AppLabelRow(appUsageInfo: AppUsageInfo, packageManager: PackageManager) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }
    val prefKey = "app_label_${appUsageInfo.appInfo.packageName}"

    val defaultLabel = inferLabel(appUsageInfo.appInfo, packageManager)

    var currentLabel by remember { mutableStateOf(prefs.getString(prefKey, defaultLabel) ?: defaultLabel) }
    var expanded by remember { mutableStateOf(false) }
    val labels = listOf(LABEL_LEISURE, LABEL_WORK, LABEL_IMPORTANT, LABEL_UNLABELED)
    
    val appIcon = remember(appUsageInfo.appInfo) {
        appUsageInfo.appInfo.loadIcon(packageManager)
    }

    fun formatUsageTime(timeMillis: Long): String {
        if (timeMillis <= 0) return ""
        val hours = TimeUnit.MILLISECONDS.toHours(timeMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(timeMillis) % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "< 1m"
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        val imageBitmap = remember(appIcon) {
            val bmp = if (appIcon is BitmapDrawable) {
                appIcon.bitmap
            } else {
                val bmp = Bitmap.createBitmap(
                    appIcon.intrinsicWidth.coerceAtLeast(1),
                    appIcon.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bmp)
                appIcon.setBounds(0, 0, canvas.width, canvas.height)
                appIcon.draw(canvas)
                bmp
            }
            bmp.asImageBitmap()
        }
        Image(bitmap = imageBitmap, contentDescription = null, modifier = Modifier.size(40.dp))
        
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(appUsageInfo.appInfo.loadLabel(packageManager).toString(), maxLines = 1)
            val usageText = formatUsageTime(appUsageInfo.usageTimeMillis)
            if (usageText.isNotEmpty()) {
                Text(usageText, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
            }
        }
        
        Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = true }
            ) {
                Text(currentLabel, fontWeight = FontWeight.Bold)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Change app label")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                labels.forEach { label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { 
                            currentLabel = label
                            prefs.edit().putString(prefKey, label).apply()
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    StillTheme {
        SettingsScreen(isOnboarding = true)
    }
}
