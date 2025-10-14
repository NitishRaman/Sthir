package com.nitish.still

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.nitish.still.ui.theme.StillTheme
import kotlinx.coroutines.delay
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

@Composable
fun MainNavigation() {
    var showSplashScreen by remember { mutableStateOf(true) }

    if (showSplashScreen) {
        SplashScreen(onTimeout = { showSplashScreen = false })
    } else {
        LocationAwareScreen()
    }
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2000L) // Show splash for 2 seconds
        onTimeout()
    }

    Surface(modifier = Modifier.fillMaxSize()) { // Background color is now handled by the theme
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "Still Logo",
                modifier = Modifier.size(200.dp), // Use a large, fixed size for the logo
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "STILL",
                style = MaterialTheme.typography.displayLarge // Increased the font size
            )
            Spacer(modifier = Modifier.height(12.dp)) // Added space between title and tagline
            Text(
                text = "SHUT EYES. SEE WITHIN.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun LocationAwareScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }
    var isLocationSet by remember { mutableStateOf(prefs.contains("home_latitude")) }

    if (isLocationSet) {
        HomeScreen()
    } else {
        LocationPermissionScreen {
            isLocationSet = true
        }
    }
}

@Composable
fun LocationPermissionScreen(onPermissionGranted: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            prefs.edit()
                                .putFloat("home_latitude", location.latitude.toFloat())
                                .putFloat("home_longitude", location.longitude.toFloat())
                                .apply()
                            onPermissionGranted()
                        } else {
                            Log.d("Location", "Location is null")
                        }
                    }
                }
            }
        }
    )

    Scaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(it).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Welcome to Still.",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = "This app works best by knowing your home location to track your activity.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )
            Button(onClick = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                Text("Set Home Location")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    var timerService by remember { mutableStateOf<TimerService?>(null) }
    var isSessionActive by remember { mutableStateOf(false) }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                timerService = (service as TimerService.LocalBinder).getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                timerService = null
            }
        }
    }

    // Automatically bind/unbind from the service based on session state
    LaunchedEffect(isSessionActive) {
        val intent = Intent(context, TimerService::class.java)
        if (isSessionActive) {
            ContextCompat.startForegroundService(context, intent)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } else {
            if (timerService != null) {
                context.unbindService(connection)
            }
            context.stopService(intent)
            timerService = null
        }
    }

    // Clean up the connection when the screen is disposed
    DisposableEffect(Unit) {
        onDispose {
            if (timerService != null) {
                context.unbindService(connection)
            }
        }
    }

    val usageState by timerService?.usageState?.collectAsState(initial = UsageState()) ?: remember { mutableStateOf(UsageState()) }

    val formattedTime = remember(usageState.continuousUsageMs) {
        val hours = TimeUnit.MILLISECONDS.toHours(usageState.continuousUsageMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(usageState.continuousUsageMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(usageState.continuousUsageMs) % 60
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Still") },
                actions = {
                    IconButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(it),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(formattedTime, style = MaterialTheme.typography.displayLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { isSessionActive = !isSessionActive }) {
                Text(if (isSessionActive) "Stop Session" else "Start Session")
            }
        }
    }
}

@Preview(showBackground = true, name = "Splash Screen Preview")
@Composable
fun SplashScreenPreview() {
    StillTheme {
        SplashScreen(onTimeout = {})
    }
}

@Preview(showBackground = true, name = "Location Permission Preview")
@Composable
fun LocationPermissionScreenPreview() {
    StillTheme {
        LocationPermissionScreen {}
    }
}

@Preview(showBackground = true, name = "Home Screen Preview")
@Composable
fun HomeScreenPreview() {
    StillTheme {
        HomeScreen()
    }
}
