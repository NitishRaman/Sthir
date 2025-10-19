package com.nitish.still

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.nitish.still.ui.theme.StillTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StillTheme {
                MainNavigation()
            }
        }
    }
}

// --- Main Navigation --- //

@Composable
fun MainNavigation() {
    var showSplashScreen by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }
    var onboardingComplete by remember { mutableStateOf(prefs.getBoolean("onboarding_complete", false)) }

    if (showSplashScreen) {
        SplashScreen(onTimeout = { showSplashScreen = false })
    } else {
        if (onboardingComplete) {
            HomeScreenWithNavigation()
        } else {
            OnboardingFlow(onOnboardingFinished = {
                prefs.edit().putBoolean("onboarding_complete", true).apply()
                onboardingComplete = true
            })
        }
    }
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2000L)
        onTimeout()
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(painter = painterResource(id = R.drawable.logo), contentDescription = "Still Logo", modifier = Modifier.size(200.dp), contentScale = ContentScale.Fit)
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "STILL", style = MaterialTheme.typography.displayLarge)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "SHUT EYES. SEE WITHIN.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// --- Onboarding --- //

@Composable
fun OnboardingFlow(onOnboardingFinished: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }
    var onboardingStep by remember { mutableStateOf(prefs.getInt("onboarding_step", 0)) }

    fun updateStep(step: Int) {
        prefs.edit().putInt("onboarding_step", step).apply()
        onboardingStep = step
    }

    when (onboardingStep) {
        0 -> LocationPermissionScreen { updateStep(1) }
        1 -> SettingsScreenWrapper(onFinished = onOnboardingFinished, isOnboarding = true)
    }
}

@SuppressLint("InlinedApi", "MissingPermission")
@Composable
fun LocationPermissionScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val mapLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            setupGeofence(context)
            fusedLocationClient.lastLocation.addOnSuccessListener { currentLocation ->
                if (currentLocation != null) {
                    val homeLat = prefs.getString("home_latitude", "0.0")?.toDoubleOrNull() ?: 0.0
                    val homeLon = prefs.getString("home_longitude", "0.0")?.toDoubleOrNull() ?: 0.0
                    val homeLocation = Location("Home").apply {
                        latitude = homeLat
                        longitude = homeLon
                    }
                    val distance = currentLocation.distanceTo(homeLocation)
                    (context.applicationContext as StillApplication).updateInsideHomeStatus(distance < 500f)
                }
                onFinished()
            }.addOnFailureListener { onFinished() }
        }
    }

    val activityRecognitionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            mapLauncher.launch(Intent(context, MapActivity::class.java))
        } else {
            Toast.makeText(context, "Physical Activity permission is needed for efficient geofencing.", Toast.LENGTH_LONG).show()
        }
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                mapLauncher.launch(Intent(context, MapActivity::class.java))
            }
        } else {
            Toast.makeText(context, "'Allow all the time' is required for geofencing.", Toast.LENGTH_LONG).show()
        }
    }

    val fineLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                mapLauncher.launch(Intent(context, MapActivity::class.java))
            }
        } else {
            Toast.makeText(context, "Location permission is required to set a home zone.", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "Welcome to Still.", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "Let’s begin by granting permissions.", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Still requires 3 permissions to work correctly:\n1. Location (to set your home zone)\n2. Background Location ('Allow all the time')\n3. Physical Activity (for battery saving)",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            Button(onClick = {
                fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }) {
                Text("Grant Permissions")
            }
        }
    }
}

@Composable
fun SettingsScreenWrapper(onFinished: () -> Unit, isOnboarding: Boolean) {
    val context = LocalContext.current
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK && isOnboarding) {
            onFinished()
        }
    }
    LaunchedEffect(Unit) {
        val intent = Intent(context, SettingsActivity::class.java).apply {
            putExtra("is_onboarding", isOnboarding)
        }
        settingsLauncher.launch(intent)
    }
}

// --- Main App --- //

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenWithNavigation() {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as StillApplication
    val isInsideHome by app.isInsideHome.collectAsState()
    var totalDailyUsage by remember { mutableStateOf(0L) }

    LaunchedEffect(isInsideHome) {
        if (isInsideHome) {
            while (true) {
                totalDailyUsage = calculateTotalLeisureUsage(context)
                delay(1000L) // updates every second
            }
        }
    }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }

    val mapLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            setupGeofence(context)
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation.addOnSuccessListener { currentLocation ->
                    if (currentLocation != null) {
                        val homeLat = prefs.getString("home_latitude", "0.0")?.toDoubleOrNull() ?: 0.0
                        val homeLon = prefs.getString("home_longitude", "0.0")?.toDoubleOrNull() ?: 0.0
                        val homeLocation = Location("Home").apply { latitude = homeLat; longitude = homeLon }
                        val distance = currentLocation.distanceTo(homeLocation)
                        val isNowInside = distance < 500f
                        app.updateInsideHomeStatus(isNowInside)
                        Toast.makeText(context, "Home location updated.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Still") },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, "Menu")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Spacer(modifier = Modifier.height(60.dp))
                    NavigationDrawerItem(label = { Text("Set Home Location") }, selected = false, onClick = {
                        mapLauncher.launch(Intent(context, MapActivity::class.java))
                        scope.launch { drawerState.close() }
                    })
                    NavigationDrawerItem(label = { Text("Settings") }, selected = false, onClick = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                        scope.launch { drawerState.close() }
                    })
                }
            },
        ) {
            Column(modifier = Modifier.padding(paddingValues)) {
                if (isInsideHome) {
                    InsideZoneScreen(totalDailyUsage = totalDailyUsage)
                } else {
                    OutsideZoneScreen()
                }
            }
        }
    }
}

@Composable
fun InsideZoneScreen(modifier: Modifier = Modifier, totalDailyUsage: Long) {
    val hours = TimeUnit.MILLISECONDS.toHours(totalDailyUsage)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(totalDailyUsage) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(totalDailyUsage) % 60
    val formattedTime = String.format("%02d:%02d:%02d", hours, minutes, seconds)

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Screen Time Today", style = MaterialTheme.typography.headlineMedium)
        Text(formattedTime, style = MaterialTheme.typography.displayLarge, fontSize = 80.sp)
        Spacer(modifier = Modifier.height(32.dp))
        Text("Welcome Home!", style = MaterialTheme.typography.headlineSmall)
        Text("Your session is active.", style = MaterialTheme.typography.bodyLarge)
    }
}

private fun calculateTotalLeisureUsage(context: Context): Long {
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val pm = context.packageManager
    val prefs = context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE)

    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val startTime = cal.timeInMillis
    val endTime = System.currentTimeMillis()

    val usageStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)

    var totalLeisureUsage = 0L
    usageStats.forEach { stat ->
        try {
            val appInfo = pm.getApplicationInfo(stat.packageName, 0)
            val prefKey = "app_label_${stat.packageName}"
            val defaultLabel = com.nitish.still.inferLabel(appInfo)
            val label = prefs.getString(prefKey, defaultLabel) ?: defaultLabel
            if (label != LABEL_IMPORTANT) {
                totalLeisureUsage += stat.totalTimeInForeground
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // App might have been uninstalled, ignore.
        }
    }
    return totalLeisureUsage
}


@SuppressLint("MissingPermission")
@Composable
fun OutsideZoneScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val app = context.applicationContext as StillApplication

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("You’re currently outside the active area.", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text("App features are paused.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 32.dp))
        Button(onClick = { context.startActivity(Intent(context, MapActivity::class.java)) }) {
            Text("View Active Zone")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation.addOnSuccessListener { currentLocation ->
                    if (currentLocation != null) {
                        val homeLat = (context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE)).getString("home_latitude", "0.0")?.toDoubleOrNull() ?: 0.0
                        val homeLon = (context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE)).getString("home_longitude", "0.0")?.toDoubleOrNull() ?: 0.0

                        if (homeLat != 0.0 && homeLon != 0.0) {
                            val homeLocation = Location("Home").apply { latitude = homeLat; longitude = homeLon }
                            val distance = currentLocation.distanceTo(homeLocation)
                            val isNowInside = distance < 500f
                            app.updateInsideHomeStatus(isNowInside)
                            val message = if (isNowInside) "Location refreshed. You are inside the zone." else "Location refreshed. You are still outside the zone."
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Home location not set.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Could not get current location. Please try again.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }) {
            Text("Refresh Location")
        }
    }
}

@SuppressLint("MissingPermission")
private fun setupGeofence(context: Context) {
    val prefs = context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE)
    val geofencingClient = LocationServices.getGeofencingClient(context)

    val homeLat = prefs.getString("home_latitude", "0.0")?.toDoubleOrNull()
    val homeLon = prefs.getString("home_longitude", "0.0")?.toDoubleOrNull()

    if (homeLat == null || homeLon == null || homeLat == 0.0 || homeLon == 0.0) {
        Log.w("Geofence", "Home location not set, cannot setup geofence.")
        return
    }

    val geofence = Geofence.Builder()
        .setRequestId("home_geofence")
        .setCircularRegion(homeLat, homeLon, 500f)
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
        .build()

    val geofencingRequest = GeofencingRequest.Builder()
        .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
        .addGeofence(geofence)
        .build()

    val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
    val geofencePendingIntent = PendingIntent.getBroadcast(
        context, 
        0, 
        intent, 
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    )

    geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
        addOnSuccessListener {
            Log.d("Geofence", "Geofence added successfully.")
            Toast.makeText(context, "Home zone activated.", Toast.LENGTH_SHORT).show()
        }
        addOnFailureListener {
            Log.e("Geofence", "Failed to add geofence: ${it.message}")
            Toast.makeText(context, "Failed to activate home zone. Please ensure location is enabled.", Toast.LENGTH_LONG).show()
        }
    }
}
