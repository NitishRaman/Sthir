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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    var selectedDayIndex by remember { mutableStateOf(6) } // 0-6, where 6 is today
    var refreshTrigger by remember { mutableStateOf(0) } // Used to refresh the app list

    val dailyUsage = remember(hasPermission) {
        if (hasPermission) getDailyUsage(context) else emptyList()
    }

    // This list is now filtered to show only Leisure apps
    val leisureAppUsage = remember(hasPermission, selectedDayIndex, refreshTrigger) {
        if (hasPermission) {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, selectedDayIndex - 6)
            }
            val allApps = getAppUsageForDay(context, cal)
            allApps.filter {
                val prefKey = "app_label_${it.appInfo.packageName}"
                val defaultLabel = inferLabel(it.appInfo)
                val label = prefs.getString(prefKey, defaultLabel) ?: defaultLabel
                label == LABEL_LEISURE
            }
        } else {
            emptyList()
        }
    }
    
    val totalUsageForSelectedDay = if(selectedDayIndex == 6) {
        dailyUsage.find { it.day == "Today" }?.usageMillis ?: 0L
    } else {
        dailyUsage.getOrNull(selectedDayIndex)?.usageMillis ?: 0L
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
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
                    text = formatUsageTime(totalUsageForSelectedDay, detail = true),
                    style = MaterialTheme.typography.headlineLarge
                )
                val selectedDayText = when (val day = dailyUsage.getOrNull(selectedDayIndex)?.day) {
                    null -> ""
                    "Today" -> "Today"
                    else -> {
                        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, selectedDayIndex - 6) }
                        SimpleDateFormat("E, d MMM", Locale.getDefault()).format(cal.time)
                    }
                }
                Text(selectedDayText, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(24.dp))
                if (dailyUsage.isNotEmpty()) {
                    BarChart(dailyUsage, selectedDayIndex) { index ->
                        selectedDayIndex = index
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // --- App Usage List (now filtered) ---
            items(leisureAppUsage) { app ->
                AppUsageRow(
                    appUsageInfo = app, 
                    packageManager = context.packageManager,
                    onLabelChanged = { refreshTrigger++ } // This triggers the list to refresh
                )
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
fun BarChart(
    dailyUsage: List<DailyUsage>,
    selectedDayIndex: Int,
    onDaySelected: (Int) -> Unit
) {
    val maxUsage = dailyUsage.maxOfOrNull { it.usageMillis } ?: 1L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.Bottom
    ) {
        dailyUsage.forEachIndexed { index, dailyUsage ->
            val isSelected = index == selectedDayIndex
            val color = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onDaySelected(index) }
            ) {
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .fillMaxHeight(dailyUsage.usageMillis.toFloat() / maxUsage)
                        .background(color)
                )
                Text(dailyUsage.day.take(3), style = MaterialTheme.typography.bodySmall)
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

    val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)

    // Create a map of day -> usage
    val dailyTotals = mutableMapOf<Int, Long>()
    stats.forEach {
        val cal = Calendar.getInstance().apply { timeInMillis = it.firstTimeStamp }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        dailyTotals[dayOfYear] = (dailyTotals[dayOfYear] ?: 0L) + it.totalTimeInForeground
    }
    
    val result = mutableListOf<DailyUsage>()
    val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
    val todayCalendar = Calendar.getInstance()

    val cal = Calendar.getInstance().apply { timeInMillis = startTime }

    for (i in 0..6) {
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val usage = dailyTotals[dayOfYear] ?: 0L
        val dayLabel = if (cal.get(Calendar.DAY_OF_YEAR) == todayCalendar.get(Calendar.DAY_OF_YEAR) && cal.get(Calendar.YEAR) == todayCalendar.get(Calendar.YEAR)) {
            "Today"
        } else {
            dayFormat.format(cal.time)
        }
        result.add(DailyUsage(dayLabel, usage))
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    return result
}

fun getAppUsageForDay(context: Context, calendar: Calendar): List<AppUsageInfo> {
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val pm = context.packageManager

    // Set calendar to the start of the selected day
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val startTime = calendar.timeInMillis

    // Set calendar to the end of the selected day
    calendar.set(Calendar.HOUR_OF_DAY, 23)
    calendar.set(Calendar.MINUTE, 59)
    calendar.set(Calendar.SECOND, 59)
    calendar.set(Calendar.MILLISECOND, 999)
    val endTime = calendar.timeInMillis

    // Query stats for the selected day
    val usageStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)

    // Map stats to AppUsageInfo
    return usageStats
        .filter { it.totalTimeInForeground > 0 }
        .mapNotNull {
            try {
                val appInfo = pm.getApplicationInfo(it.packageName, 0)
                AppUsageInfo(appInfo, it.totalTimeInForeground)
            } catch (e: PackageManager.NameNotFoundException) {
                // System packages or uninstalled apps might be in stats
                null
            }
        }
        .sortedByDescending { it.usageTimeMillis }
}

@SuppressLint("InlinedApi")
fun inferLabel(appInfo: ApplicationInfo): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return when (appInfo.category) {
            ApplicationInfo.CATEGORY_GAME,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_SOCIAL,
            ApplicationInfo.CATEGORY_IMAGE,
            ApplicationInfo.CATEGORY_AUDIO -> LABEL_LEISURE
            else -> LABEL_UNLABELED
        }
    }
    return LABEL_UNLABELED
}

@Composable
fun AppUsageRow(
    appUsageInfo: AppUsageInfo, 
    packageManager: PackageManager,
    onLabelChanged: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }
    val prefKey = "app_label_${appUsageInfo.appInfo.packageName}"

    val defaultLabel = remember(appUsageInfo.appInfo) { inferLabel(appUsageInfo.appInfo) }
    var currentLabel by remember { mutableStateOf(prefs.getString(prefKey, defaultLabel) ?: defaultLabel) }
    var expanded by remember { mutableStateOf(false) }
    val labels = listOf(LABEL_LEISURE, LABEL_IMPORTANT, LABEL_UNLABELED)

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
        
        Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = true }
            ) {
                Text(currentLabel)
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
                            onLabelChanged() // This line triggers the refresh
                        }
                    )
                }
            }
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
