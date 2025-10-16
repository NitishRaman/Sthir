
package com.nitish.still

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.nitish.still.ui.theme.StillTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        1 -> SettingsScreenWrapper { onOnboardingFinished() } // Last step
    }
}

@SuppressLint("InlinedApi")
@Composable
fun LocationPermissionScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val mapLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            setupGeofence(context)
            // Manually check location after setting geofence
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation.addOnSuccessListener { currentLocation ->
                    if (currentLocation != null) {
                        val homeLat = prefs.getString("home_latitude", "0.0")?.toDoubleOrNull() ?: 0.0
                        val homeLon = prefs.getString("home_longitude", "0.0")?.toDoubleOrNull() ?: 0.0
                        val homeLocation = Location("home").apply {
                            latitude = homeLat
                            longitude = homeLon
                        }
                        val distance = currentLocation.distanceTo(homeLocation)
                        val isInside = distance < 500f
                        prefs.edit().putBoolean(GeofenceBroadcastReceiver.KEY_IS_INSIDE_HOME, isInside).apply()
                    }
                    onFinished()
                }.addOnFailureListener {
                    onFinished() // Proceed even if location check fails
                }
            } else {
                 onFinished()
            }
        }
    }
    val activityRecognitionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
         mapLauncher.launch(Intent(context, MapActivity::class.java))
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
             } else {
                mapLauncher.launch(Intent(context, MapActivity::class.java))
             }
        } else {
            Toast.makeText(context, "Background location is required for geofencing.", Toast.LENGTH_LONG).show()
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
            Text(text = "Let’s start by setting your home location.", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Still uses it to track your screen activity only when you’re at home, ensuring it stays inactive when you’re out or need your phone.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            Button(onClick = {
                 fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }) {
                Text("Set Home Location")
            }
        }
    }
}


@Composable
fun SettingsScreenWrapper(onFinished: () -> Unit) {
    val context = LocalContext.current
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onFinished() }
    LaunchedEffect(Unit) {
        settingsLauncher.launch(Intent(context, SettingsActivity::class.java))
    }
}

// --- Main App --- //

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenWithNavigation() {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }
    var isInsideHome by remember { mutableStateOf(prefs.getBoolean(GeofenceBroadcastReceiver.KEY_IS_INSIDE_HOME, false)) }

    // Listen for broadcasts from the GeofenceBroadcastReceiver
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == GeofenceBroadcastReceiver.ACTION_GEOFENCE_UPDATE) {
                    isInsideHome = intent.getBooleanExtra(GeofenceBroadcastReceiver.EXTRA_IS_INSIDE_HOME, false)
                }
            }
        }
        val filter = IntentFilter(GeofenceBroadcastReceiver.ACTION_GEOFENCE_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            context.unregisterReceiver(receiver)
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
                        context.startActivity(Intent(context, MapActivity::class.java))
                        scope.launch { drawerState.close() }
                    })
                    NavigationDrawerItem(label = { Text("Settings") }, selected = false, onClick = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                        scope.launch { drawerState.close() }
                    })
                }
            }
        ) {
            if (isInsideHome) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Welcome Home!", style = MaterialTheme.typography.displayLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Your session will start automatically.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                OutsideZoneScreen { inside -> isInsideHome = inside }
            }
        }
    }
}
@SuppressLint("MissingPermission")
@Composable
fun OutsideZoneScreen(onRefresh: (Boolean) -> Unit) {
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "You’re currently outside the active area.",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = "The app features are temporarily paused.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
        )
        Button(onClick = { context.startActivity(Intent(context, MapActivity::class.java)) }) {
            Text("View Active Zone")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation.addOnSuccessListener { currentLocation ->
                    if (currentLocation != null) {
                        val homeLat = prefs.getString("home_latitude", "0.0")?.toDoubleOrNull() ?: 0.0
                        val homeLon = prefs.getString("home_longitude", "0.0")?.toDoubleOrNull() ?: 0.0

                        if (homeLat != 0.0 && homeLon != 0.0) {
                            val homeLocation = Location("home").apply {
                                latitude = homeLat
                                longitude = homeLon
                            }
                            val distance = currentLocation.distanceTo(homeLocation) // This is in meters
                            val isNowInside = distance < 500f

                            onRefresh(isNowInside)
                            prefs.edit().putBoolean(GeofenceBroadcastReceiver.KEY_IS_INSIDE_HOME, isNowInside).apply()

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
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { context.startActivity(Intent(context, MapActivity::class.java)) }) {
            Text("Change Zone")
        }
    }
}


@SuppressLint("MissingPermission")
private fun setupGeofence(context: Context) {
    val prefs = context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE)
    val lat = prefs.getString("home_latitude", "0.0")?.toDoubleOrNull() ?: 0.0
    val lon = prefs.getString("home_longitude", "0.0")?.toDoubleOrNull() ?: 0.0

    if (lat == 0.0 || lon == 0.0) {
        Log.w("Geofence", "Home location not set, cannot create geofence.")
        return
    }

    val geofencingClient = LocationServices.getGeofencingClient(context)

    val geofence = Geofence.Builder()
        .setRequestId("HOME_GEOFENCE")
        .setCircularRegion(lat, lon, 500f) // 500 meters radius
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
        .build()

    val geofencingRequest = GeofencingRequest.Builder()
        .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
        .addGeofence(geofence)
        .build()

    val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
        `package` = context.packageName
    }
    val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        Log.e("Geofence", "Fine location permission not granted, cannot add geofence.")
        return
    }
    geofencingClient.addGeofences(geofencingRequest, pendingIntent)?.run {
        addOnSuccessListener {
            Log.i("Geofence", "Geofence added successfully.")
        }
        addOnFailureListener {
            Log.e("Geofence", "Failed to add geofence: ${it.message}")
        }
    }
}


@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    StillTheme {
        HomeScreenWithNavigation()
    }
}

@Preview(showBackground = true)
@Composable
fun OutsideZoneScreenPreview() {
    StillTheme {
        OutsideZoneScreen {}
    }
}
