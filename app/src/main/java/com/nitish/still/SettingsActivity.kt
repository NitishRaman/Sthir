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
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nitish.still.ui.theme.StillTheme
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class AppUsageInfo(
    val appInfo: ApplicationInfo,
    val usageTimeMillis: Long,
)

data class DailyUsage(val day: String, val usageMillis: Long)

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

    val dailyUsage = remember(hasPermission) {
        if (hasPermission) getDailyUsage(context) else emptyList()
    }
    val appUsage = remember(hasPermission) {
        if (hasPermission) getAppUsage(context) else emptyList()
    }
    val totalTodayUsage = dailyUsage.find { it.day == "Today" }?.usageMillis ?: 0L

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
                title = { Text("App activity details") },
                navigationIcon = {
                    IconButton(onClick = { activity?.finish() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {

            // --- Timer Presets ---
            item {
                Text("Timer Presets", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(presets) { preset ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectPreset(preset) }
                        .padding(vertical = 8.dp)
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

            // --- App Activity Details ---
            item {
                Text("Screen time", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formatUsageTime(totalTodayUsage, detail = true),
                    style = MaterialTheme.typography.headlineLarge
                )
                Text("Today", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(24.dp))
                if (dailyUsage.isNotEmpty()) {
                    BarChart(dailyUsage)
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // --- App Usage List ---
            items(appUsage) { app ->
                AppUsageRow(appUsageInfo = app, packageManager = context.packageManager)
            }

            // --- Permission Card ---
            if (!hasPermission) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = "Enable Usage Access", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "To show screen time data, please grant access in system settings.")
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

            if (isOnboarding) {
                item {
                    Button(
                        onClick = {
                            prefs.edit().putBoolean("onboarding_complete", true).apply()
                            activity?.setResult(Activity.RESULT_OK)
                            activity?.finish()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text("Finish Setup")
                    }
                }
            }
        }
    }
}

@Composable
fun BarChart(dailyUsage: List<DailyUsage>) {
    val maxUsage = dailyUsage.maxOfOrNull { it.usageMillis } ?: 1L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.Bottom
    ) {
        dailyUsage.forEach {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .fillMaxHeight(it.usageMillis.toFloat() / maxUsage)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(it.day.take(3), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

fun getDailyUsage(context: Context): List<DailyUsage> {
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val calendar = Calendar.getInstance()
    val endTime = calendar.timeInMillis
    calendar.add(Calendar.DAY_OF_YEAR, -6)
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val startTime = calendar.timeInMillis

    val stats = usageStatsManager.queryUsageStats(
        UsageStatsManager.INTERVAL_DAILY,
        startTime,
        endTime
    )

    val dailyTotals = mutableMapOf<Int, Long>()
    stats.forEach {
        calendar.timeInMillis = it.firstTimeStamp
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        dailyTotals[dayOfYear] = (dailyTotals[dayOfYear] ?: 0L) + it.totalTimeInForeground
    }

    val result = mutableListOf<DailyUsage>()
    val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
    val todayCalendar = Calendar.getInstance()
    val todayDayOfYear = todayCalendar.get(Calendar.DAY_OF_YEAR)

    calendar.timeInMillis = startTime

    for (i in 0..6) {
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        val usage = dailyTotals[dayOfYear] ?: 0L

        val dayLabel = if (calendar.get(Calendar.YEAR) == todayCalendar.get(Calendar.YEAR) && dayOfYear == todayDayOfYear) {
            "Today"
        } else {
            dayFormat.format(calendar.time)
        }
        result.add(DailyUsage(dayLabel, usage))
        calendar.add(Calendar.DAY_OF_YEAR, 1)
    }
    return result
}

fun getAppUsage(context: Context): List<AppUsageInfo> {
    val pm = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
    val packageNames = resolveInfos.map { it.activityInfo.packageName }.toSet()

    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val time = System.currentTimeMillis()
    val usageStats = usageStatsManager.queryUsageStats(
        UsageStatsManager.INTERVAL_WEEKLY,
        time - TimeUnit.DAYS.toMillis(7),
        time
    )
    val usageMap = usageStats.associateBy({ it.packageName }, { it.totalTimeInForeground })

    return packageNames.mapNotNull { pkg ->
        try {
            val appInfo = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            val usage = usageMap[pkg] ?: 0
            if (usage > 0) {
                AppUsageInfo(appInfo, usage)
            } else {
                null
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }.sortedByDescending { it.usageTimeMillis }
}

@Composable
fun AppUsageRow(appUsageInfo: AppUsageInfo, packageManager: PackageManager) {
    val appIcon = remember(appUsageInfo.appInfo) {
        appUsageInfo.appInfo.loadIcon(packageManager)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
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
            Text(
                formatUsageTime(appUsageInfo.usageTimeMillis),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp
            )
        }
    }
}

fun formatUsageTime(timeMillis: Long, detail: Boolean = false): String {
    if (timeMillis <= 0) return if (detail) "0 mins" else "0m"
    val hours = TimeUnit.MILLISECONDS.toHours(timeMillis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(timeMillis) % 60

    val hourUnit = if (detail) "hr" else "h"
    val minuteUnit = if (detail) "min" else "m"

    val parts = mutableListOf<String>()
    if (hours > 0) parts.add("$hours $hourUnit${if (hours > 1 && detail) "s" else ""}")
    if (minutes > 0) parts.add("$minutes $minuteUnit${if (minutes > 1 && detail) "s" else ""}")

    if (parts.isEmpty()) return if (detail) "< 1 minute" else "< 1m"
    return parts.joinToString(if (detail) ", " else " ")
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    StillTheme {
        SettingsScreen(isOnboarding = true)
    }
}
