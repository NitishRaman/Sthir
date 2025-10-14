package com.nitish.still

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nitish.still.ui.theme.StillTheme

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }

    var workInterval by remember { mutableStateOf(prefs.getInt("work_interval", 60).toString()) }
    var breakInterval by remember { mutableStateOf(prefs.getInt("break_interval", 5).toString()) }
    val exemptApps by remember { mutableStateOf(prefs.getStringSet("exempt_apps", emptySet()) ?: emptySet()) }

    val packageManager = context.packageManager
    val installedApps = remember {
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { it.loadLabel(packageManager).toString() }
    }

    Scaffold(modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(title = { Text("Settings") })
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingTextField(label = "Work Interval (min)", value = workInterval, onValueChange = { 
                    workInterval = it
                    prefs.edit().putInt("work_interval", it.toIntOrNull() ?: 60).apply()
                })
                Spacer(modifier = Modifier.height(16.dp))
                SettingTextField(label = "Break Interval (min)", value = breakInterval, onValueChange = { 
                    breakInterval = it
                    prefs.edit().putInt("break_interval", it.toIntOrNull() ?: 5).apply()
                })
            }
            Divider()
            Text("Exempt Apps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            LazyColumn {
                items(installedApps) { app ->
                    var isChecked by remember { mutableStateOf(exemptApps.contains(app.packageName)) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(app.loadLabel(packageManager).toString(), modifier = Modifier.weight(1f))
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                isChecked = checked
                                val currentExemptApps = prefs.getStringSet("exempt_apps", emptySet()) ?: mutableSetOf()
                                val newExemptApps = if (checked) {
                                    currentExemptApps + app.packageName
                                } else {
                                    currentExemptApps - app.packageName
                                }
                                prefs.edit().putStringSet("exempt_apps", newExemptApps).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        TextField(
            value = value, 
            onValueChange = onValueChange, 
            modifier = Modifier.weight(0.5f),
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    StillTheme {
        SettingsScreen()
    }
}
