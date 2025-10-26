package com.nitish.still

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
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
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.nitish.still.ui.theme.StillTheme
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.component.textComponent
import com.patrykandpatrick.vico.compose.dimensions.dimensionsOf
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.component.shape.LineComponent
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryModelOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import android.app.usage.UsageStatsManager
import java.util.Calendar
import android.content.SharedPreferences
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp



class MainActivity : ComponentActivity() {
    private val REQ_NOTIF = 1234

    // modern launcher to request POST_NOTIFICATIONS (compose-ready but defined in activity scope)
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d("MainActivity", "POST_NOTIFICATIONS granted")
        } else {
            Log.w("MainActivity", "POST_NOTIFICATIONS denied")
            // optional: show a small explanation or a settings link to enable notifications
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate() - starting; SDK=${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                if (MyNotificationListener.instance == null) {
                    try {
                        val intent =
                            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        Toast.makeText(
                            this,
                            "Please enable notification access for Sthir",
                            Toast.LENGTH_LONG
                        ).show()
                        startActivity(intent)
                    } catch (t: Throwable) {
                        Log.w(
                            "MainActivity",
                            "Failed to open Notification Access settings: ${t.message}"
                        )
                    }
                }
            }
        }
        setContent {
            StillTheme {
                // Wrap your main navigation with the background
                AppBackground {
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


        var onboardingComplete by remember {
            mutableStateOf(
                prefs.getBoolean(
                    "onboarding_complete",
                    false
                )
            )
        }

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

        val neonCyan = Color(0xFF3FF7FF)
        val neon = neonCyan

        Box(modifier = Modifier.fillMaxSize()) {
            // background
            Image(
                painter = painterResource(id = R.drawable.background_night),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // logo area (use Image if you already have a logo drawable)
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
                    // big soft glow (text is used only for glow; replace with image if needed)
                    Text(
                        text = " ", // empty visual; glow produced by TextStyle.shadow
                        style = TextStyle(
                            color = neon.copy(alpha = 0.18f),
                            fontSize = 72.sp,
                            shadow = Shadow(
                                color = neon.copy(alpha = 0.25f),
                                offset = Offset(0f, 0f),
                                blurRadius = 40f
                            )
                        )
                    )

                    // actual logo image
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = "logo",
                        modifier = Modifier.size(200.dp).offset(y = (-150).dp),
                        contentScale = ContentScale.Fit
                    )

                    // small inner glow overlay (optional)
                    Text(
                        text = " ",
                        style = TextStyle(
                            color = neon.copy(alpha = 0.45f),
                            fontSize = 48.sp,
                            shadow = Shadow(
                                color = neon.copy(alpha = 0.55f),
                                offset = Offset(0f, 0f),
                                blurRadius = 12f
                            )
                        )
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // neon title: stacked Texts for multi-layer glow
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "STHIR",
                        style = TextStyle(
                            color = neon.copy(alpha = 0.14f),
                            fontSize = 36.sp,
                            shadow = Shadow(color = neon.copy(alpha = 0.22f), offset = Offset(0f, 0f), blurRadius = 40f)
                        )
                    )
                    Text(
                        text = "STHIR",
                        style = TextStyle(
                            color = neon.copy(alpha = 0.28f),
                            fontSize = 36.sp,
                            shadow = Shadow(color = neon.copy(alpha = 0.40f), offset = Offset(0f, 0f), blurRadius = 18f)
                        )
                    )
                    Text(
                        text = "STHIR",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 36.sp,
                            shadow = Shadow(color = neon.copy(alpha = 0.95f), offset = Offset(0f, 0f), blurRadius = 6f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // tagline
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "SHUT EYES. SEE WITHIN.",
                        style = TextStyle(
                            color = neon.copy(alpha = 0.18f),
                            fontSize = 14.sp,
                            shadow = Shadow(color = neon.copy(alpha = 0.26f), offset = Offset(0f, 0f), blurRadius = 18f)
                        )
                    )
                    Text(
                        text = "SHUT EYES. SEE WITHIN.",
                        style = TextStyle(
                            color = neon.copy(alpha = 0.9f),
                            fontSize = 14.sp,
                            shadow = Shadow(color = neon.copy(alpha = 0.9f), offset = Offset(0f, 0f), blurRadius = 6f)
                        )
                    )
                }
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
        val fusedLocationClient =
            remember { LocationServices.getFusedLocationProviderClient(context) }

        val mapLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    setupGeofence(context)
                    fusedLocationClient.lastLocation.addOnSuccessListener { currentLocation ->
                        if (currentLocation != null) {
                            val homeLat =
                                prefs.getString("home_latitude", "0.0")?.toDoubleOrNull() ?: 0.0
                            val homeLon =
                                prefs.getString("home_longitude", "0.0")?.toDoubleOrNull() ?: 0.0
                            val homeLocation = Location("Home").apply {
                                latitude = homeLat
                                longitude = homeLon
                            }
                            val distance = currentLocation.distanceTo(homeLocation)
                            (context.applicationContext as StillApplication).updateInsideHomeStatus(
                                distance < 500f
                            )
                        }
                        onFinished()
                    }.addOnFailureListener { onFinished() }
                }
            }

        val activityRecognitionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    mapLauncher.launch(Intent(context, MapActivity::class.java))
                } else {
                    Toast.makeText(
                        context,
                        "Physical Activity permission is needed for efficient geofencing.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        val backgroundLocationLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    } else {
                        mapLauncher.launch(Intent(context, MapActivity::class.java))
                    }
                } else {
                    Toast.makeText(
                        context,
                        "\'Allow all the time\' is required for geofencing.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        val fineLocationLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        mapLauncher.launch(Intent(context, MapActivity::class.java))
                    }
                } else {
                    Toast.makeText(
                        context,
                        "Location permission is required to set a home zone.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Welcome to Sthir.",
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Let’s begin by granting permissions.",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Sthir requires 3 permissions to work correctly:\\n1. Location (to set your home zone)\\n2. Background Location (\'Allow all the time\')\\n3. Physical Activity (for battery saving)",
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
        val settingsLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
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
        val context = LocalContext.current               // keep exactly one context
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val app = context.applicationContext as StillApplication
        val isInsideHome by app.isInsideHome.collectAsState()

        // --- breaks today state & broadcast receiver ---
        val sdfForBreakKey =
            remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US) }
        val todayKey = remember { "break_count_${sdfForBreakKey.format(java.util.Date())}" }
        val breaksTodayState = remember { mutableStateOf(0) }

        // Compose state that mirrors prefs.paused_tracking
        val prefs = remember {
            context.getSharedPreferences(
                "still_prefs",
                Context.MODE_PRIVATE
            )
        } // keep exactly one prefs
        val pausedPrefState = remember {
            // start with the current persisted value
            mutableStateOf(prefs.getBoolean("paused_tracking", false))
        }

        // Keep the Compose state in sync if something else changes the pref (optional):
        LaunchedEffect(Unit) {
            // basic poll to pick up external updates quickly (cheap and simple)
            while (true) {
                val cur = prefs.getBoolean("paused_tracking", false)
                if (pausedPrefState.value != cur) pausedPrefState.value = cur
                kotlinx.coroutines.delay(1000L)
            }
        }


// load initial value once
        LaunchedEffect(Unit) {
            try {
                val cur = context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE)
                    .getInt(todayKey, 0)
                breaksTodayState.value = cur
                Log.d("HomeScreenWithNavigation", "breaksToday initial=$cur")
            } catch (t: Throwable) {
                Log.w("HomeScreenWithNavigation", "Failed to read breaksToday prefs: ${t.message}")
            }
        }

// update when break completes (CameraCaptureActivity writes the pref and broadcasts ACTION_BREAK_COMPLETED)
        DisposableEffect(Unit) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    try {
                        val newVal =
                            context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE)
                                .getInt(todayKey, 0)
                        breaksTodayState.value = newVal
                        Log.d(
                            "HomeScreenWithNavigation",
                            "Received ACTION_BREAK_COMPLETED -> breaksToday=$newVal"
                        )
                    } catch (t: Throwable) {
                        Log.w("HomeScreenWithNavigation", "breakReceiver error: ${t.message}")
                    }
                }
            }
            val filter = android.content.IntentFilter("com.nitish.still.ACTION_BREAK_COMPLETED")

            try {
                // On Android 13+ the registerReceiver overload requires an exported flag.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
            } catch (t: Throwable) {
                Log.w("HomeScreenWithNavigation", "registerReceiver failed: ${t.message}")
            }

            onDispose {
                try {
                    context.unregisterReceiver(receiver)
                } catch (t: Throwable) {
                    Log.w("HomeScreenWithNavigation", "unregisterReceiver failed: ${t.message}")
                }
            }
        }


        // UI state for usage values
        var totalDailyUsage by remember { mutableStateOf(0L) }
        var weeklyUsage by remember { mutableStateOf<List<DailyUsage>>(emptyList()) }
        var top5Apps by remember { mutableStateOf<List<WeeklyAppUsage>>(emptyList()) }

        // ---- Service start + binding to read continuousUsageMs (REPLACEMENT) ----
        val timerServiceRef = remember { mutableStateOf<TimerService?>(null) }
        val continuousMsState = remember { mutableStateOf(0L) }

        // Read as a snapshot state (this registers a Compose read so recomposition happens)
        val continuousMs by continuousMsState
        // 1) Ensure the service is started (with isInsideHome flag) whenever isInsideHome value changes
        LaunchedEffect(isInsideHome) {
            val startIntent = Intent(context, TimerService::class.java).apply {
                putExtra(GeofenceBroadcastReceiver.EXTRA_IS_INSIDE_HOME, isInsideHome)
            }

            // debug: log intent & flag
            Log.d(
                "MainActivity",
                "Starting TimerService with isInsideHome=$isInsideHome (startIntent=$startIntent)"
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d("MainActivity", "Calling startForegroundService for TimerService")
                    context.startForegroundService(startIntent)
                } else {
                    Log.d("MainActivity", "Calling startService for TimerService")
                    context.startService(startIntent)
                }
            } catch (t: Throwable) {
                Log.w(
                    "MainActivity",
                    "Failed to start TimerService via startForegroundService/startService: ${t.message}",
                    t
                )
                // fallback: attempt startService (some devices may restrict foreground start)
                try {
                    Log.d("MainActivity", "Attempting fallback startService(TimerService)")
                    context.startService(startIntent)
                } catch (ex: Throwable) {
                    Log.e("MainActivity", "Fallback startService also failed: ${ex.message}", ex)
                }
            }
        }


        // 2) ServiceConnection + safe collection of usageState
        val serviceConnection = remember {
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    Log.d("MainActivity", "onServiceConnected -> binding to TimerService")
                    val svc = (binder as? TimerService.LocalBinder)?.getService()
                    timerServiceRef.value = svc

                    // Immediately read last snapshot from the service so UI shows it right away
                    try {
                        val snapshot = svc?.getLastLeisureSnapshotMs() ?: 0L
                        val live = svc?.usageState?.value?.continuousUsageMs ?: 0L
                        val toSet = if (snapshot > 0L) snapshot else live
                        continuousMsState.value = toSet

                        Log.d(
                            "MainActivity",
                            "onServiceConnected: lastLeisureSnapshotMs=$snapshot live=$live -> uiSet=$toSet"
                        )
                    } catch (t: Throwable) {
                        Log.w(
                            "MainActivity",
                            "Failed to read lastLeisureSnapshot from service: ${t.message}",
                            t
                        )
                    }
                }


                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w("MainActivity", "ServiceConnection.onServiceDisconnected name=$name")
                    timerServiceRef.value = null
                }
            }
        }

        // Robust collector: collect from the bound service's usageState flow and update Compose state
        // This coroutine will restart collection whenever timerServiceRef.value changes.
        LaunchedEffect(timerServiceRef.value) {
            val svc = timerServiceRef.value
            if (svc != null) {
                Log.d("MainActivity", "Main: start collecting from TimerService instance")
                // use collectLatest so we cancel any previous collector as soon as a new value arrives
                svc.usageState.collectLatest { state ->
                    // update the Compose state holder used by the UI
                    continuousMsState.value = state.continuousUsageMs
                    Log.d(
                        "MainActivity",
                        "Collected usageState: continuousMs=${state.continuousUsageMs}"
                    )
                }
            } else {
                // no service bound yet — keep the UI at zero until bound
                continuousMsState.value = 0L
                // small delay prevents tight loop if this LaunchedEffect gets retriggered rapidly
                delay(250L)
            }
        }


        // 3) Bind when this composable is active, unbind onDispose
        DisposableEffect(Unit) {
            val bindIntent = Intent(context, TimerService::class.java)
            Log.d("MainActivity", "Binding to TimerService (bindIntent=$bindIntent)")
            val bound = try {
                context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                Log.w("MainActivity", "bindService threw: ${t.message}", t)
                false
            }
            Log.d("MainActivity", "bindService result = $bound")

            onDispose {
                try {
                    Log.d("MainActivity", "Unbinding from TimerService")
                    context.unbindService(serviceConnection)
                } catch (t: Throwable) {
                    Log.w("MainActivity", "unbindService failed: ${t.message}", t)
                }
                timerServiceRef.value = null
            }
        }

        // background updater for screen-time UI (only update while inside)
        // background updater for screen-time UI (only update while inside)
        // RUN heavy work on IO to avoid blocking compose/main thread
        LaunchedEffect(isInsideHome) {
            if (isInsideHome) {
                while (true) {
                    // do heavy computation off the main thread
                    val (daily, weekly, top5) = withContext(Dispatchers.IO) {
                        val d = calculateTotalLeisureUsage(context)
                        val w = getDailyUsage(context)           // must be safe on IO
                        val t = getTop5LeisureApps(context)     // must be safe on IO
                        Triple(d, w, t)
                    }
                    // update UI state on the main thread
                    totalDailyUsage = daily
                    weeklyUsage = weekly
                    top5Apps = top5

                    delay(1000L) // updates every second
                }
            } else {
                // reset UI when outside the zone (optional)
                totalDailyUsage = 0L
                weeklyUsage = emptyList()
                top5Apps = emptyList()
            }
        }


        val fusedLocationClient =
            remember { LocationServices.getFusedLocationProviderClient(context) }
        //val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }

        val mapLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    setupGeofence(context)
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        fusedLocationClient.lastLocation.addOnSuccessListener { currentLocation ->
                            if (currentLocation != null) {
                                val homeLat =
                                    prefs.getString("home_latitude", "0.0")?.toDoubleOrNull() ?: 0.0
                                val homeLon =
                                    prefs.getString("home_longitude", "0.0")?.toDoubleOrNull()
                                        ?: 0.0
                                val homeLocation = Location("Home").apply {
                                    latitude = homeLat; longitude = homeLon
                                }
                                val distance = currentLocation.distanceTo(homeLocation)
                                val isNowInside = distance < 500f
                                app.updateInsideHomeStatus(isNowInside)
                                Toast.makeText(
                                    context,
                                    "Home location updated.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            // Make the scaffold content container transparent (Material3)
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text("STHIR", color = Color.White)
                    },

                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    },

                    // add actions param:
                    actions = {
                        // Show Pause or Resume depending on state
                        val paused = pausedPrefState.value
                        TextButton(onClick = {
                            togglePause(context)
                            pausedPrefState.value = !paused // optimistic UI update
                            val toast = if (!paused) "Tracking paused" else "Tracking resumed"
                            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
                        }) {
                            Text(if (paused) "Resume" else "Pause")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
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
                        NavigationDrawerItem(
                            label = { Text("Set Home Location") },
                            selected = false,
                            onClick = {
                                mapLauncher.launch(Intent(context, MapActivity::class.java))
                                scope.launch { drawerState.close() }
                            })
                        NavigationDrawerItem(
                            label = { Text("Settings") },
                            selected = false,
                            onClick = {
                                context.startActivity(Intent(context, SettingsActivity::class.java))
                                scope.launch { drawerState.close() }
                            })
                    }
                },
            ) {
                Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .padding(top = 10.dp)) {
                    // ===== DEBUG: visible state snapshot =====
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isInsideHome) "You are inside home 🏠" else "You are outside home 🌍",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.95f)
                        )
                        Text(
                            "Continuous Usage =${continuousMs}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.95f)
                        )
                        Text(
                            "Total Daily Usage =${TimeUnit.MILLISECONDS.toSeconds(totalDailyUsage)}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.95f)
                        )
                    }

                    // ========================================
                    if (isInsideHome) {
                        Log.d(
                            "MainActivityUI",
                            "Rendering InsideZoneScreen — isInsideHome=$isInsideHome continuousMs=$continuousMs totalDailyUsage=$totalDailyUsage"
                        )
                        InsideZoneScreen(
                            totalDailyUsage = totalDailyUsage,
                            weeklyUsage = weeklyUsage,
                            top5Apps = top5Apps,
                            continuousMs = continuousMs,
                            breaksToday = breaksTodayState.value
                        )
                    } else {
                        OutsideZoneScreen()
                    }
                }

            }
        }
    }

    private fun ensureUsageAccess(context: Context): Boolean {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return false
        val now = System.currentTimeMillis()
        // Query for a tiny span to test whether access returns anything
        val test =
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 1000L, now)
        return test.isNotEmpty()
    }


    @Composable
    fun InsideZoneScreen(
        modifier: Modifier = Modifier,
        totalDailyUsage: Long,
        weeklyUsage: List<DailyUsage>,
        top5Apps: List<WeeklyAppUsage>,
        continuousMs: Long,
        breaksToday: Int
    ) {
        // debug: log whenever this composable recomposes because continuousMs changed
        LaunchedEffect(continuousMs) {
            Log.d(
                "InsideZoneUI",
                "recompose: continuousMs=$continuousMs totalDailyUsage=$totalDailyUsage breaksToday=$breaksToday"
            )
        }

        // compute formatted total daily time
        val hours = TimeUnit.MILLISECONDS.toHours(totalDailyUsage)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalDailyUsage) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(totalDailyUsage) % 60
        val formattedTime = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        // compute remaining time until break using prefs (work_interval stored in minutes)
        val ctx = LocalContext.current
        val prefs = ctx.getSharedPreferences("still_prefs", Context.MODE_PRIVATE)
        val workMinutes = prefs.getInt("work_interval", 60) // minutes
        val workIntervalMs = TimeUnit.MINUTES.toMillis(workMinutes.toLong())
        val remainingMs = (workIntervalMs - continuousMs).coerceAtLeast(0L)
        val remainingSeconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs)
        val neonCyan = Color(0xFF3FF7FF)

        // debug recomposition log — fires whenever continuousMs changes (value-based)
        LaunchedEffect(continuousMs) {
            Log.d(
                "InsideZoneUI",
                "recompose: continuousMs=$continuousMs remainingSeconds=$remainingSeconds"
            )
        }



        Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Screen Time Today", style = MaterialTheme.typography.headlineMedium, color = neonCyan)
                    Text(formattedTime, style = MaterialTheme.typography.displayLarge, color = neonCyan)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Breaks Today: $breaksToday", style = MaterialTheme.typography.bodyMedium, color = neonCyan)
                }
                WeeklyTrendChart(weeklyUsage = weeklyUsage)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Top5AppsList(top5Apps = top5Apps)

            Spacer(modifier = Modifier.height(16.dp))

            // continuous session / time to next break
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val contSec = TimeUnit.MILLISECONDS.toSeconds(continuousMs)
                val workMinutesLocal = prefs.getInt("work_interval", 60) // minutes
                val workIntervalMsLocal = TimeUnit.MINUTES.toMillis(workMinutesLocal.toLong())
                val remainingMsLocal = (workIntervalMsLocal - continuousMs).coerceAtLeast(0L)
                val remainingSecondsLocal = TimeUnit.MILLISECONDS.toSeconds(remainingMsLocal)

                Log.d(
                    "InsideZoneUI",
                    "renderingTexts: continuousMs=$continuousMs contSec=${contSec}s remainingSeconds=${remainingSecondsLocal}s workMinutes=$workMinutesLocal"
                )

                Text(
                    "Current continuous session: ${contSec}s",
                    style = MaterialTheme.typography.bodyLarge,
                    color = neonCyan
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Time until break: ${remainingSecondsLocal}s",
                    style = MaterialTheme.typography.headlineSmall,
                    color = neonCyan
                )
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.weight(0.35f))   // top empty space (35% of available)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Welcome Home!", style = MaterialTheme.typography.headlineSmall, color = neonCyan)
                    Text("Your session is active.", style = MaterialTheme.typography.bodyLarge, color = neonCyan)
                }
                Spacer(modifier = Modifier.weight(0.65f))   // remaining space pushes the rest down
            }
        }
    }

    @Composable
    fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
        Box(modifier = modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.background_night),
                contentDescription = null, // Background is decorative
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop // This is key for fitting all screen sizes
            )
            // This is where your actual screen content will be drawn on top
            content()
        }
    }

    @Composable
    fun Top5AppsList(top5Apps: List<WeeklyAppUsage>) {
        Column {
            Spacer(modifier = Modifier.height(200.dp))
            Text("Top 5 Weekly Apps", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF3FF7FF))
            Spacer(modifier = Modifier.height(30.dp))

            val maxUsage = top5Apps.maxOfOrNull { it.totalUsage }?.toFloat() ?: 1f

            top5Apps.forEach { appUsage ->
                val fraction = (appUsage.totalUsage.toFloat() / maxUsage).coerceIn(0f, 1f)
                val icon = getAppIcon(LocalContext.current, appUsage.packageName)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Image(
                            painter = rememberDrawablePainter(drawable = icon),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        Box(modifier = Modifier.size(40.dp).background(Color.Gray))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f).height(20.dp).background(Color.LightGray)) {
                        Box(
                            modifier = Modifier.fillMaxWidth(fraction).height(20.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }


    @Composable
    fun WeeklyTrendChart(weeklyUsage: List<DailyUsage>) {
        val chartModel = entryModelOf(weeklyUsage.mapIndexed { index, dailyUsage ->
            FloatEntry(
                index.toFloat(),
                (dailyUsage.usageMillis / (1000 * 60)).toFloat()
            )
        })
        val startAxisValueFormatter = AxisValueFormatter<AxisPosition.Vertical.Start> { value, _ ->
            val hours = (value / 60).toInt()
            val minutes = (value % 60).toInt()
            when {
                hours > 0 -> "${hours}h"
                minutes > 0 -> "${minutes}m"
                else -> "0m"
            }
        }

        val bottomAxisValueFormatter =
            AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
                weeklyUsage.getOrNull(value.toInt())?.day ?: ""
            }

        Chart(
            chart = lineChart(),
            model = chartModel,
            startAxis = rememberStartAxis(
                valueFormatter = startAxisValueFormatter,
                label = textComponent(
                    color = MaterialTheme.colorScheme.onSurface,
                    textSize = 10.sp,
                    typeface = Typeface.MONOSPACE
                ),
                axis = LineComponent(
                    color = MaterialTheme.colorScheme.onSurface.toArgb(),
                    thicknessDp = 1f,
                ),
                tick = LineComponent(
                    color = MaterialTheme.colorScheme.onSurface.toArgb(),
                    thicknessDp = 0.5f
                ),
                guideline = LineComponent(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f).toArgb(),
                    thicknessDp = 0.5f
                )
            ),
            bottomAxis = rememberBottomAxis(
                valueFormatter = bottomAxisValueFormatter,
                label = textComponent(
                    color = MaterialTheme.colorScheme.onSurface,
                    textSize = 10.sp,
                    typeface = Typeface.MONOSPACE,
                    lineCount = 1,
                    padding = dimensionsOf(horizontal = 4.dp)
                ),
                axis = LineComponent(
                    color = MaterialTheme.colorScheme.onSurface.toArgb(),
                    thicknessDp = 1f
                )
            ),
            modifier = Modifier.height(120.dp)
        )
    }

    private suspend fun calculateTotalLeisureUsage(context: Context): Long =
        withContext(Dispatchers.IO) {
            val usageStatsManager =
                context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    ?: return@withContext 0L
            val pm = context.packageManager
            val prefs = context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE)

            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startTime = cal.timeInMillis
            val endTime = System.currentTimeMillis()

            val usageStats = try {
                usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    startTime,
                    endTime
                )
            } catch (t: Throwable) {
                Log.w("MainActivity", "queryUsageStats failed: ${t.message}")
                emptyList()
            }

            var totalLeisureUsage = 0L
            usageStats.forEach { stat ->
                try {
                    val appInfo = pm.getApplicationInfo(stat.packageName, 0)
                    val prefKey = "app_label_${stat.packageName}"
                    val defaultLabel = inferLabel(appInfo)
                    val label = prefs.getString(prefKey, defaultLabel) ?: defaultLabel
                    if (label != LABEL_IMPORTANT) {
                        totalLeisureUsage += stat.totalTimeInForeground
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    // ignore
                } catch (t: Throwable) {
                    Log.w(
                        "MainActivity",
                        "Error processing UsageStats for ${stat.packageName}: ${t.message}"
                    )
                }
            }
            return@withContext totalLeisureUsage
        }


    @SuppressLint("MissingPermission")
    @Composable
    fun OutsideZoneScreen(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val fusedLocationClient =
            remember { LocationServices.getFusedLocationProviderClient(context) }
        val app = context.applicationContext as StillApplication

        Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "You’re currently outside the active area.",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Text(
                "App features are paused.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
            )
            Button(onClick = { context.startActivity(Intent(context, MapActivity::class.java)) }) {
                Text("View Active Zone")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    fusedLocationClient.lastLocation.addOnSuccessListener { currentLocation ->
                        if (currentLocation != null) {
                            val homeLat = (context.getSharedPreferences(
                                "still_prefs",
                                Context.MODE_PRIVATE
                            )).getString("home_latitude", "0.0")?.toDoubleOrNull() ?: 0.0
                            val homeLon = (context.getSharedPreferences(
                                "still_prefs",
                                Context.MODE_PRIVATE
                            )).getString("home_longitude", "0.0")?.toDoubleOrNull() ?: 0.0

                            if (homeLat != 0.0 && homeLon != 0.0) {
                                val homeLocation = Location("Home").apply {
                                    latitude = homeLat; longitude = homeLon
                                }
                                val distance = currentLocation.distanceTo(homeLocation)
                                val isNowInside = distance < 500f
                                app.updateInsideHomeStatus(isNowInside)
                                val message =
                                    if (isNowInside) "Location refreshed. You are inside the zone." else "Location refreshed. You are still outside the zone."
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Home location not set.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            Toast.makeText(
                                context,
                                "Could not get current location. Please try again.",
                                Toast.LENGTH_SHORT
                            ).show()
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
            .setRequestId(GeofenceConstants.GEOFENCE_ID)
            .setCircularRegion(homeLat, homeLon, GeofenceConstants.GEOFENCE_RADIUS_METERS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val pendingIntent = GeofenceConstants.createGeofencePendingIntent(context)

        geofencingClient.removeGeofences(pendingIntent).run {
            addOnSuccessListener {
                Log.i("Geofence", "Old geofence removed successfully before re-adding.")
                geofencingClient.addGeofences(geofencingRequest, pendingIntent).run {
                    addOnSuccessListener {
                        Log.i("Geofence", "New geofence added successfully.")
                        Toast.makeText(context, "Home zone activated.", Toast.LENGTH_SHORT).show()
                    }
                    addOnFailureListener {
                        Log.e("Geofence", "Failed to add geofence: ${it.message}")
                        Toast.makeText(context, "Failed to activate home zone.", Toast.LENGTH_LONG)
                            .show()
                    }
                }
            }
            addOnFailureListener {
                Log.w("Geofence", "No previous geofence removed: ${it.message}")
            }
        }
    }

    // helper: toggle pause flag, persist, and notify TimerService
    fun togglePause(context: Context) {
        val prefs = context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE)
        val currentlyPaused = prefs.getBoolean("paused_tracking", false)
        val newPaused = !currentlyPaused
        prefs.edit().putBoolean("paused_tracking", newPaused).apply()

        // Tell TimerService about the change via intent (service receives in onStartCommand)
        val intent = Intent(context, TimerService::class.java).apply {
            putExtra("extra_paused", newPaused)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (t: Throwable) {
            Log.w("HomeScreen", "Failed to send pause intent to TimerService: ${t.message}")
        }
    }


    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}