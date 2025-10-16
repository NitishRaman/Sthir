package com.nitish.still

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import com.nitish.still.ui.theme.StillTheme
import kotlinx.coroutines.launch

class MapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StillTheme {
                SelectLocationScreen()
            }
        }
    }
}

@Composable
fun SelectLocationScreen() {
    val context = LocalContext.current
    val activity = (context as? Activity)
    val prefs = remember { context.getSharedPreferences("still_prefs", Context.MODE_PRIVATE) }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val geofencingClient = remember { LocationServices.getGeofencingClient(context) }
    val scope = rememberCoroutineScope()

    var savedLat: Double
    var savedLon: Double

    try {
        savedLat = prefs.getString("home_latitude", null)?.toDoubleOrNull() ?: 0.0
        savedLon = prefs.getString("home_longitude", null)?.toDoubleOrNull() ?: 0.0
    } catch (e: ClassCastException) {
        savedLat = prefs.getFloat("home_latitude", 0f).toDouble()
        savedLon = prefs.getFloat("home_longitude", 0f).toDouble()
    }

    val initialLocation = if (savedLat != 0.0 && savedLon != 0.0) {
        LatLng(savedLat, savedLon)
    } else {
        LatLng(37.422, -122.084) // Default to Googleplex
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialLocation, 15f)
    }

    fun locateMe() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val userLocation = LatLng(location.latitude, location.longitude)
                    scope.launch {
                        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(userLocation, 15f))
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (savedLat == 0.0 && savedLon == 0.0) {
            locateMe()
        }
    }

    Scaffold {
        Box(Modifier.fillMaxSize().padding(it)) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                uiSettings = MapUiSettings(zoomControlsEnabled = true),
                contentPadding = PaddingValues(bottom = 0.dp)
            )

            Icon(
                imageVector = Icons.Default.Place,
                contentDescription = "Map Pin",
                modifier = Modifier.align(Alignment.Center)
            )

            Column(modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { activity?.finish() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = "Select home location",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextField(
                    value = "",
                    onValueChange = {},
                    placeholder = { Text("Search for area, street name...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedButton(
                        onClick = { locateMe() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Use current location",
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val selectedLocation = cameraPositionState.position.target
                            prefs.edit()
                                .putString("home_latitude", selectedLocation.latitude.toString())
                                .putString("home_longitude", selectedLocation.longitude.toString())
                                .apply()

                            updateGeofence(context, geofencingClient, selectedLocation)

                            activity?.setResult(Activity.RESULT_OK)
                            activity?.finish()
                        }
                    ) {
                        Text("Confirm Location")
                    }
                }
            }
        }
    }
}

private fun updateGeofence(context: Context, geofencingClient: GeofencingClient, location: LatLng) {
    val geofenceId = "HOME_GEOFENCE"

    geofencingClient.removeGeofences(listOf(geofenceId)).run {
        addOnSuccessListener {
            Log.i("Geofence", "Old geofence removed successfully.")
            addGeofence(context, geofencingClient, location, geofenceId)
        }
        addOnFailureListener {
            Log.e("Geofence", "Failed to remove old geofence: ${it.message}")
            addGeofence(context, geofencingClient, location, geofenceId)
        }
    }
}

@SuppressLint("MissingPermission")
private fun addGeofence(context: Context, geofencingClient: GeofencingClient, location: LatLng, geofenceId: String) {
    val fineLocationPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val backgroundLocationPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    if (!fineLocationPermissionGranted || !backgroundLocationPermissionGranted) {
        var errorMessage = "Cannot add geofence. Missing permissions:"
        if (!fineLocationPermissionGranted) errorMessage += "\n- Fine Location"
        if (!backgroundLocationPermissionGranted) errorMessage += "\n- Background Location"
        Log.e("Geofence", errorMessage)
        Toast.makeText(context, "Cannot update location. Missing permissions.", Toast.LENGTH_LONG).show()
        return
    }

    val geofence = Geofence.Builder()
        .setRequestId(geofenceId)
        .setCircularRegion(location.latitude, location.longitude, 500f)
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
        .build()

    val geofencingRequest = GeofencingRequest.Builder()
        .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
        .addGeofence(geofence)
        .build()

    val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    geofencingClient.addGeofences(geofencingRequest, pendingIntent)?.run {
        addOnSuccessListener {
            Log.i("Geofence", "New geofence added successfully.")
            Toast.makeText(context, "Home location updated!", Toast.LENGTH_SHORT).show()
        }
        addOnFailureListener {
            Log.e("Geofence", "Failed to add new geofence: ${it.message}")
            Toast.makeText(context, "Error updating home location.", Toast.LENGTH_SHORT).show()
        }
    }
}
