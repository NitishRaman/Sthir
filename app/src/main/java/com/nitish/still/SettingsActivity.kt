package com.nitish.still

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nitish.still.ui.theme.StillTheme

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
        setContent {
            StillTheme {
                SettingsScreen()
            }
        }
    }
}

// Define the labels consistently
const val LABEL_LEISURE = "Leisure"
const val LABEL_PRODUCTIVE = "Productive"
const val LABEL_EXEMPT = "Exempt"
const val LABEL_UNLABELED = "Unlabeled"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = (context as? Activity)
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }

    var selectedPresetName by remember { mutableStateOf(prefs.getString("selected_preset", "Pomodoro")) }
    var customActivity by remember { mutableStateOf(prefs.getInt("work_interval", 45).toString()) }
    var customBreak by remember { mutableStateOf((prefs.getInt("break_interval", 300) / 60).toString()) }

    val packageManager = context.packageManager
    val installedApps = remember {
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { it.loadLabel(packageManager).toString() }
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
        Column(modifier = Modifier.padding(innerPadding)){
            LazyColumn(modifier = Modifier.weight(1f)) {
                // Timer presets section
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

                // Custom rhythm settings
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

                // App labeling section
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("App Labels", style = MaterialTheme.typography.titleLarge)
                        Text("Select apps that count toward your eye-rest timer (social, video, games).", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                items(installedApps) { app ->
                    AppLabelRow(appInfo = app, packageManager = packageManager)
                }
            }
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

@Composable
fun AppLabelRow(appInfo: ApplicationInfo, packageManager: PackageManager) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }
    val prefKey = "app_label_${appInfo.packageName}"

    var currentLabel by remember { mutableStateOf(prefs.getString(prefKey, LABEL_UNLABELED) ?: LABEL_UNLABELED) }
    var expanded by remember { mutableStateOf(false) }
    val labels = listOf(LABEL_LEISURE, LABEL_PRODUCTIVE, LABEL_EXEMPT, LABEL_UNLABELED)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(appInfo.loadLabel(packageManager).toString(), modifier = Modifier.weight(1f))
        
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
        SettingsScreen()
    }
}
