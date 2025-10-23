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
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalDensity
import com.nitish.still.isLeisureCategory
import com.nitish.still.LABEL_LEISURE
import com.nitish.still.LABEL_IMPORTANT
import com.nitish.still.LABEL_UNLABELED



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
        // --- DEBUG: Check what packages are visible to this app ---
        val pm = packageManager
        val visible = pm.getInstalledPackages(0)
        Log.d("SettingsDebug", "Visible installed packages count: ${visible.size}")
        visible.take(20).forEach { Log.d("SettingsDebug", "VisiblePkg: ${it.packageName}") }
        // ----------------------------------------------------------

        val isOnboarding = intent.getBooleanExtra("is_onboarding", false)
        setContent {
            StillTheme {
                SettingsScreen(isOnboarding = isOnboarding)
            }
        }
    }
}


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

// Replace existing ensureDefaultLeisureLabels(...) with this exact function
fun ensureDefaultLeisureLabels(context: Context) {
    val pm = context.packageManager
    val prefs = context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE)
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }

    // Get all launchable package names
    val launchable = pm.queryIntentActivities(mainIntent, 0)
        .mapNotNull { it.activityInfo?.packageName }
        .toSet()

    launchable.forEach { pkg ->
        try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            // ignore true system core apps and the host app itself
            val isSystemCore = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystemCore) return@forEach
            if (appInfo.packageName == context.packageName) return@forEach

            val key = "app_label_$pkg"
            if (!prefs.contains(key)) {
                // default all launchable user apps to LEISURE unless user set it
                prefs.edit().putString(key, LABEL_LEISURE).apply()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            // ignore unresolved package names
        }
    }
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

    var selectedPresetName by remember { mutableStateOf(prefs.getString("selected_preset", "Pomodoro") ?: "Pomodoro") }
    var customActivity by remember { mutableStateOf(prefs.getInt("work_interval", 45).toString()) }
    var customBreak by remember { mutableStateOf(prefs.getInt("break_interval", 300).toString()) }

    var selectedDayIndex by remember { mutableStateOf(6) } // 0-6, where 6 is today
    var refreshTrigger by remember { mutableStateOf(0) } // Used to refresh the app list

    val dailyUsage = remember(hasPermission) {
        if (hasPermission) getDailyUsage(context) else emptyList()
    }

    // --- Filtered app list: skip true system apps; use user's saved label if present;
    // otherwise use classifier isLeisureCategory(...) to decide -->
    val leisureAppUsage = remember(hasPermission, selectedDayIndex, refreshTrigger) {
        // selectedDayIndex uses the same indexing as getDailyUsage(): index 6 == Today (list is oldest->newest)
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, selectedDayIndex - 6) }
        val allApps = getAppUsageForDay(context, cal)
        Log.d("SettingsDebug", "leisureAppUsage: selectedDayIndex=$selectedDayIndex -> cal=${cal.time}")


        val filtered = allApps.filter { appUsage ->
            val appInfo = appUsage.appInfo
            val pkg = appInfo.packageName ?: return@filter false

            // Skip host app
            if (pkg == context.packageName) return@filter false

            // Skip true system core apps (allow updated system apps / user-installed ones)
            val isSystemCore = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystemCore) return@filter false

            // If user explicitly labeled the app, respect that (show only Leisure or Important)
            val prefKey = "app_label_$pkg"
            val savedLabel = prefs.getString(prefKey, null)
            if (savedLabel != null) {
                return@filter (savedLabel == LABEL_LEISURE || savedLabel == LABEL_IMPORTANT)
            }

            // Otherwise decide using the classifier (no heuristics here)
            isLeisureCategory(appInfo, context.packageManager)
        }

        Log.d("SettingsDebug", "After filtering leisure/important: ${filtered.size}")
        filtered
    }



    val totalUsageForSelectedDay = dailyUsage.getOrNull(selectedDayIndex)?.usageMillis ?: 0L

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
                                // work_interval is stored as minutes, keep that unchanged
                                prefs.edit().putInt("work_interval", it.toIntOrNull() ?: 45).apply()
                                // mark preset as custom in prefs
                                prefs.edit().putString("selected_preset", "Custom Rhythm").apply()
                                selectedPresetName = "Custom Rhythm"
                            },
                            label = { Text("Custom Activity (minutes)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TextField(
                            value = customBreak,
                            onValueChange = {
                                customBreak = it
                                // store break_interval directly in seconds (no *60)
                                prefs.edit().putInt("break_interval", it.toIntOrNull() ?: 300).apply()
                                // mark preset as custom in prefs
                                prefs.edit().putString("selected_preset", "Custom Rhythm").apply()
                                selectedPresetName = "Custom Rhythm"
                            },
                            label = { Text("Custom Break (seconds)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
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

                // selectedDayIndex: 0 == Today, 1 == Yesterday, etc.  (matches getDailyUsage().reversed())
                val selectedDayText = when (val day = dailyUsage.getOrNull(selectedDayIndex)?.day) {
                    null -> "" // safe fallback
                    "Today" -> "Today"
                    else -> {
                        // convert selectedDayIndex (0..6 where 6 == Today) into a calendar date
                        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, selectedDayIndex - 6) }
                        SimpleDateFormat("E, d MMM", Locale.getDefault()).format(cal.time)
                    }
                }



                if (selectedDayText.isNotEmpty()) {
                    Text(selectedDayText, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (dailyUsage.isNotEmpty()) {
                    BarChart(dailyUsage, selectedDayIndex) { index ->
                        selectedDayIndex = index
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }


            // --- App Usage List (filtered to Leisure & Important only) ---
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
                            Toast.makeText(context, "Open 'Usage access' and enable permission for Still (search for \"Still\")", Toast.LENGTH_LONG).show()
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
    if (dailyUsage.isEmpty()) return

    val maxUsage = dailyUsage.maxOfOrNull { it.usageMillis } ?: 1L

    // how tall the bar-area should be (above the label)
    val chartHeight = 80.dp
    val barWidth = 22.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight + 28.dp), // room for bar area + label + spacing
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        // dailyUsage is expected oldest -> newest (left -> right)
        dailyUsage.forEachIndexed { index, du ->
            val isSelected = index == selectedDayIndex
            val barColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onDaySelected(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom // keep bar anchored at bottom
            ) {
                // top spacer to push the bar to the bottom when bar height is less than chartHeight
                // (we rely on Column verticalArrangement=Bottom but still ensure predictable sizing)
                Spacer(modifier = Modifier.height(4.dp))

                // Compute fraction safely (0..1)
                val fraction = if (maxUsage > 0L) (du.usageMillis.toFloat() / maxUsage.toFloat()).coerceIn(0f, 1f) else 0f
                val barHeight = chartHeight * fraction

                // Bar box with explicit height (so bars align to the bottom baseline)
                Box(
                    modifier = Modifier
                        .width(barWidth)
                        .height(barHeight)
                        .background(barColor)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // label: show 3-letter (or "Today")
                Text(
                    text = if (du.day == "Today") "Today" else du.day.take(3),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
        }
    }
}

fun getAppUsageForDay(context: Context, calendar: Calendar): List<AppUsageInfo> {
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val pm = context.packageManager

    // day range (midnight -> 23:59:59.999)
    val calStart = Calendar.getInstance().apply {
        timeInMillis = calendar.timeInMillis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val calEnd = Calendar.getInstance().apply {
        timeInMillis = calendar.timeInMillis
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
    }
    val startTime = calStart.timeInMillis
    val endTime = calEnd.timeInMillis

    val usageStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
    val usageMap = usageStats.associateBy({ it.packageName }, { it.totalTimeInForeground })
    Log.d("SettingsDebug", "UsageMap size: ${usageMap.size}")

    val result = mutableListOf<AppUsageInfo>()

    // 1) Launchable user apps
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    val launchables = pm.queryIntentActivities(mainIntent, 0)
        .mapNotNull { it.activityInfo?.packageName }
        .toSortedSet()

    for (pkg in launchables) {
        try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            // Skip host app
            if (appInfo.packageName == context.packageName) continue
            // Skip true system core apps, allow updated system apps (installed updates)
            val isSystemCore = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystemCore) continue

            val usage = usageMap[pkg] ?: 0L
            result.add(AppUsageInfo(appInfo, usage))
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w("SettingsDebug", "Launchable pkg not resolvable: $pkg")
        } catch (t: Throwable) {
            Log.w("SettingsDebug", "Error reading launchable pkg $pkg: ${t.message}")
        }
    }

    // 2) Add installed user apps, but apply stricter filtering to avoid "Main components" / support packages
    try {
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val launchableSet = launchables

        for (ai in installed) {
            val pkg = ai.packageName ?: continue
            if (pkg == context.packageName) continue
            if (result.any { it.appInfo.packageName == pkg }) continue

            // Skip true system core apps (allow updated system apps)
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) continue

            val hasLauncherIntent = launchableSet.contains(pkg) || pm.getLaunchIntentForPackage(pkg) != null
            val usage = usageMap[pkg] ?: 0L

            // If not launchable and no recorded usage, skip (not user-visible)
            if (!hasLauncherIntent && usage <= 0L) continue

            // Try to get the user-visible label (fall back to package short name)
            val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Throwable) { pkg }
            val lowerLabel = label.lowercase(Locale.getDefault())

            // Skip noisy generic labels like "Main components" or "Support components"
            if (lowerLabel.contains("component") || lowerLabel.contains("components") ||
                lowerLabel.contains("support component") || lowerLabel.trim().isEmpty()) {
                Log.d("SettingsDebug", "Skipping noisy label pkg=$pkg label=$label")
                continue
            }

            // Skip packages that are obviously platform packages by prefix if not launchable
            val lowerPkg = pkg.lowercase(Locale.getDefault())
            if ((lowerPkg.startsWith("com.android") || lowerPkg.startsWith("android") || lowerPkg.startsWith("com.motorola") || lowerPkg.startsWith("com.google.android"))
                && !hasLauncherIntent && usage <= 0L) {
                continue
            }

            // OK — add it
            result.add(AppUsageInfo(ai, usage))
        }
    } catch (t: Throwable) {
        Log.w("SettingsDebug", "Failed to enumerate installed apps: ${t.message}")
    }



    // We DO NOT create placeholder ApplicationInfo() objects for unresolved packages.
    // That avoids package-name-only "fake" entries which caused label/icon problems.

    return result.sortedByDescending { it.usageTimeMillis }
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

    // default to saved pref if present, otherwise default to LABEL_LEISURE
    val defaultLabel = remember { prefs.getString(prefKey, LABEL_LEISURE) ?: LABEL_LEISURE }
    var currentLabel by remember { mutableStateOf(defaultLabel) }
    var expanded by remember { mutableStateOf(false) }
    val labels = listOf(LABEL_LEISURE, LABEL_IMPORTANT, LABEL_UNLABELED)

    // ---- Get display name safely ----
    val pkgName = appUsageInfo.appInfo.packageName ?: ""
    val displayName = remember(pkgName) {
        try {
            // try to resolve a fresh ApplicationInfo (safer)
            val ai = packageManager.getApplicationInfo(pkgName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (t: Throwable) {
            // fallback: pretty last segment of package name
            pkgName.substringAfterLast('.').replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
        }
    }

    // ---- Load icon safely ----
    val iconDrawable = remember(pkgName) {
        try {
            // prefer using appInfo -> may work if appInfo is full
            packageManager.getApplicationIcon(pkgName)
        } catch (t: Throwable) {
            try {
                packageManager.getDefaultActivityIcon()
            } catch (_: Throwable) {
                null
            }
        }
    }

    // convert drawable to bitmap for Compose Image
    val imageBitmap = remember(iconDrawable) {
        iconDrawable?.let { icon ->
            if (icon is BitmapDrawable && icon.bitmap != null) {
                icon.bitmap
            } else {
                val w = (icon.intrinsicWidth.takeIf { it > 0 } ?: 48)
                val h = (icon.intrinsicHeight.takeIf { it > 0 } ?: 48)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                icon.setBounds(0, 0, canvas.width, canvas.height)
                icon.draw(canvas)
                bmp
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        if (imageBitmap != null) {
            Image(bitmap = imageBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                val short = pkgName.substringAfterLast('.', "app").take(2).uppercase(Locale.getDefault())
                Text(short, color = Color.White, fontSize = 12.sp)
            }
        }

        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(displayName, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(formatUsageTime(appUsageInfo.usageTimeMillis), style = MaterialTheme.typography.bodySmall)
        }

        Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = true }.padding(start = 8.dp)
            ) {
                Text(currentLabel)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Change app label")
            }

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                labels.forEach { label ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        currentLabel = label
                        prefs.edit().putString(prefKey, label).apply()
                        expanded = false
                        onLabelChanged()
                    })
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
fun getDailyUsage(context: Context): List<DailyUsage> {
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val pm = context.packageManager

    // Start at midnight 6 days ago (oldest day)
    val today = Calendar.getInstance()
    val startCal = Calendar.getInstance().apply {
        timeInMillis = today.timeInMillis
        add(Calendar.DAY_OF_YEAR, -6)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val result = mutableListOf<DailyUsage>()
    val dayFormat = SimpleDateFormat("EEE", Locale.ENGLISH)

    val iterCal = Calendar.getInstance().apply { timeInMillis = startCal.timeInMillis }

    for (i in 0..6) {
        val dayStart = iterCal.timeInMillis

        // build day end at 23:59:59.999 for that day
        val endCal = Calendar.getInstance().apply {
            timeInMillis = dayStart
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val dayEnd = endCal.timeInMillis

        var sumForDay = 0L

        try {
            // Preferred: queryAndAggregateUsageStats(dayStart, dayEnd) if available
            // This returns Map<String, UsageStats> aggregated for the range
            val aggregated: Map<String, *>? = try {
                @Suppress("UNCHECKED_CAST")
                usageStatsManager.queryAndAggregateUsageStats(dayStart, dayEnd) as? Map<String, android.app.usage.UsageStats>
            } catch (_: Throwable) {
                null
            }

            if (aggregated != null && aggregated.isNotEmpty()) {
                aggregated.forEach { (pkg, usageObj) ->
                    if (usageObj !is android.app.usage.UsageStats) return@forEach
                    try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        val isSystemCore = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                        if (isSystemCore) return@forEach

                        if (isLeisureCategory(ai, pm)) {
                            sumForDay += usageObj.totalTimeInForeground
                        }
                    } catch (_: PackageManager.NameNotFoundException) {
                        // package not visible – skip
                    }
                }
            } else {
                // Fallback: queryUsageStats for the day range and sum returned entries
                val dailyStats =
                    usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, dayEnd)
                dailyStats.forEach { us ->
                    val pkg = us.packageName ?: return@forEach
                    try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        val isSystemCore = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                        if (isSystemCore) return@forEach

                        if (isLeisureCategory(ai, pm)) {
                            sumForDay += us.totalTimeInForeground
                        }
                    } catch (_: PackageManager.NameNotFoundException) {
                        // skip
                    } catch (t: Throwable) {
                        Log.w("SettingsDebug", "getDailyUsage (fallback) error for $pkg: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("SettingsDebug", "getDailyUsage: error querying day range ${Date(dayStart)} - ${Date(dayEnd)}: ${t.message}")
        }

        // label "Today" when it matches current day
        val dayLabel = if (iterCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
            && iterCal.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        ) {
            "Today"
        } else {
            dayFormat.format(iterCal.time).take(3)
        }

        Log.d("SettingsDebug", "getDailyUsage: day=${dayLabel} start=${Date(dayStart)} end=${Date(dayEnd)} leisureMillis=$sumForDay")

        result.add(DailyUsage(dayLabel, sumForDay))
        iterCal.add(Calendar.DAY_OF_YEAR, 1)
    }

    // result is oldest -> newest (index 6 == Today)
    Log.d("SettingsDebug", "getDailyUsage (final): ${result.map { "${it.day}:${it.usageMillis}" }}")
    return result
}
