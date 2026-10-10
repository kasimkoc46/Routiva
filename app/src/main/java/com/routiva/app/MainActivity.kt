package com.routiva.app

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Bg = Color(0xFF101820)
private val CardBg = Color(0xFF202B35)
private val Accent = Color(0xFFC7F36B)
private val Muted = Color(0xFFB3C0CB)
private val DeliveredBg = Color(0xFF27352B)
private const val DEFAULT_SERVICE_MINUTES = 10

data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

data class RouteResult(
    val places: List<Place>,
    val points: List<GeoPoint>,
    val drivingSeconds: Double,
    val distanceMeters: Double
)

class MainActivity : ComponentActivity() {
    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName

        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(11.5)
            controller.setCenter(GeoPoint(52.52, 13.405))
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Bg,
                    surface = CardBg,
                    onPrimary = Bg,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                RoutivaScreen(mapView)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }
}

@Composable
private fun RoutivaScreen(mapView: MapView) {
    val places = remember { mutableStateListOf<Place>() }
    val delivered = remember { mutableStateListOf<Boolean>() }
    val serviceMinutes = remember { mutableStateListOf<Int>() }
    val deliveryTimes = remember { mutableStateListOf<String>() }

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(emptyList<Place>()) }
    var message by remember { mutableStateOf("Add addresses to plan your route.") }
    var busy by remember { mutableStateOf(false) }
    var optimizeAutomatically by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }

    var routePoints by remember { mutableStateOf(emptyList<GeoPoint>()) }
    var drivingMinutes by remember { mutableIntStateOf(0) }
    var distanceKm by remember { mutableDoubleStateOf(0.0) }
    var routeReady by remember { mutableStateOf(false) }
    var routeStarted by remember { mutableStateOf(false) }
    var activeIndex by remember { mutableIntStateOf(0) }

    val context = androidx.compose.ui.platform.LocalContext.current

    val locationOverlay = remember(mapView) {
        MyLocationNewOverlay(
            GpsMyLocationProvider(mapView.context),
            mapView
        )
    }

    var locationPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val serviceTotal = serviceMinutes.sum()
    val totalMinutes = drivingMinutes + serviceTotal

    fun samePlace(a: Place, b: Place): Boolean =
        abs(a.latitude - b.latitude) < 0.00001 &&
            abs(a.longitude - b.longitude) < 0.00001

    fun addPlace(place: Place) {
        if (places.any { samePlace(it, place) }) {
            message = "This address is already in the list."
        } else {
            places.add(place)
            delivered.add(false)
            serviceMinutes.add(DEFAULT_SERVICE_MINUTES)
            deliveryTimes.add("09:00")

            routePoints = emptyList()
            routeReady = false
            routeStarted = false
            drivingMinutes = 0
            distanceKm = 0.0
            message = "Address added."
        }

        query = ""
        suggestions = emptyList()
    }

    fun removePlace(index: Int) {
        if (index !in places.indices) return

        places.removeAt(index)
        delivered.removeAt(index)
        serviceMinutes.removeAt(index)
        deliveryTimes.removeAt(index)

        routePoints = emptyList()
        routeReady = false
        routeStarted = false
        drivingMinutes = 0
        distanceKm = 0.0
        activeIndex = 0
        message = "Stop removed. Create the route again."
    }

    fun clearAll() {
        places.clear()
        delivered.clear()
        serviceMinutes.clear()
        deliveryTimes.clear()
        routePoints = emptyList()
        routeReady = false
        routeStarted = false
        drivingMinutes = 0
        distanceKm = 0.0
        activeIndex = 0
        query = ""
        suggestions = emptyList()
        message = "All stops cleared."
    }

    fun fitRouteOnMap() {
        val points = if (routePoints.size >= 2) {
            routePoints
        } else {
            places.map { GeoPoint(it.latitude, it.longitude) }
        }

        if (points.size == 1) {
            mapView.controller.setCenter(points.first())
            mapView.controller.setZoom(15.0)
        } else if (points.size > 1) {
            runCatching {
                mapView.zoomToBoundingBox(
                    BoundingBox.fromGeoPoints(points),
                    true,
                    60
                )
            }
        }

        mapView.invalidate()
    }

    fun goToActiveStop() {
        if (activeIndex in places.indices) {
            val place = places[activeIndex]
            mapView.controller.animateTo(
                GeoPoint(place.latitude, place.longitude)
            )
            mapView.controller.setZoom(15.0)
            mapView.invalidate()
        }
    }

    fun goToMyLocation() {
        if (!locationPermissionGranted) {
            message = "Allow location permission to find your position."
            return
        }

        locationOverlay.enableMyLocation()

        val location = locationOverlay.myLocation

        if (location != null) {
            mapView.controller.animateTo(location)
            mapView.controller.setZoom(16.0)
            message = "Showing your current location."
        } else {
            message = "Waiting for GPS. Please try again in a few seconds."
        }

        mapView.invalidate()
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            locationPermissionGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (locationPermissionGranted) {
                locationOverlay.enableMyLocation()

                if (!mapView.overlays.contains(locationOverlay)) {
                    mapView.overlays.add(locationOverlay)
                }

                message = "Location permission granted. Tap My location."
                mapView.invalidate()
            } else {
                message = "Location permission denied."
            }
        }

    LaunchedEffect(Unit) {
        if (!locationPermissionGranted) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            locationOverlay.enableMyLocation()
        }
    }

    LaunchedEffect(query) {
        suggestions = emptyList()
        val search = query.trim()

        if (search.length >= 3) {
            delay(450)

            val results = withContext(Dispatchers.IO) {
                runCatching {
                    searchAddresses(search)
                }.getOrDefault(emptyList())
            }

            if (query.trim() == search) {
                suggestions = results
            }
        }
    }

    LaunchedEffect(
        places.toList(),
        delivered.toList(),
        routePoints,
        activeIndex,
        routeStarted,
        locationPermissionGranted
    ) {
        mapView.overlays.clear()

        if (routePoints.size >= 2) {
            val line = Polyline(mapView).apply {
                setPoints(routePoints)
                outlinePaint.color =
                    android.graphics.Color.rgb(199, 243, 107)
                outlinePaint.strokeWidth = 9f
            }
            mapView.overlays.add(line)
        }

        places.forEachIndexed { index, place ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(place.latitude, place.longitude)
                title = "${index + 1}. ${place.name}"
                snippet = when {
                    delivered.getOrNull(index) == true -> "Delivered"
                    routeStarted && index == activeIndex -> "ACTIVE STOP"
                    else -> "Pending delivery"
                }
                icon = numberedMarker(
                    mapView,
                    index + 1,
                    delivered.getOrNull(index) == true,
                    routeStarted && index == activeIndex
                )
                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_CENTER
                )
            }

            mapView.overlays.add(marker)
        }

        if (locationPermissionGranted) {
            locationOverlay.enableMyLocation()
            mapView.overlays.add(locationOverlay)
        }

        mapView.invalidate()
    }

    LaunchedEffect(busy) {
        if (!busy) return@LaunchedEffect

        val oldPlaces = places.toList()
        val oldDelivered = delivered.toList()
        val oldMinutes = serviceMinutes.toList()
        val oldTimes = deliveryTimes.toList()

        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (optimizeAutomatically) {
                    optimizeRoute(oldPlaces)
                } else {
                    getRouteInCurrentOrder(oldPlaces)
                }
            }
        }

        result.onSuccess { route ->
            fun key(place: Place) =
                "${place.latitude},${place.longitude}"

            val statusMap = oldPlaces.indices.associate {
                key(oldPlaces[it]) to oldDelivered[it]
            }
            val minutesMap = oldPlaces.indices.associate {
                key(oldPlaces[it]) to oldMinutes[it]
            }
            val timesMap = oldPlaces.indices.associate {
                key(oldPlaces[it]) to oldTimes[it]
            }

            places.clear()
            places.addAll(route.places)

            delivered.clear()
            delivered.addAll(
                route.places.map { statusMap[key(it)] ?: false }
            )

            serviceMinutes.clear()
            serviceMinutes.addAll(
                route.places.map {
                    minutesMap[key(it)] ?: DEFAULT_SERVICE_MINUTES
                }
            )

            deliveryTimes.clear()
            deliveryTimes.addAll(
                route.places.map { timesMap[key(it)] ?: "09:00" }
            )

            routePoints = route.points
            drivingMinutes =
                (route.drivingSeconds / 60.0).roundToInt()
            distanceKm = route.distanceMeters / 1000.0
            routeReady = true
            routeStarted = false
            activeIndex = delivered.indexOfFirst { !it }
                .let { if (it < 0) 0 else it }

            fitRouteOnMap()

            message = if (optimizeAutomatically) {
                "Optimized route ready."
            } else {
                "Route ready in the current stop order."
            }
        }.onFailure {
            message = "Could not create route. Check your internet connection."
        }

        busy = false
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            containerColor = CardBg,
            title = {
                Text("General settings", color = Color.White)
            },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Automatic route optimization",
                            color = Color.White
                        )
                        Text(
                            "Reorder stops by driving time.",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }

                    Switch(
                        checked = optimizeAutomatically,
                        onCheckedChange = {
                            optimizeAutomatically = it
                            routeReady = false
                            routeStarted = false
                            message = "Setting changed. Create the route again."
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("Done", color = Accent)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(39.dp)
                    .background(Accent, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "R",
                    color = Bg,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(Modifier.width(9.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    "ROUTIVA",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
                Text(
                    "DELIVERY ROUTE PLANNER",
                    color = Muted,
                    fontSize = 9.sp
                )
            }

            TextButton(onClick = { showSettings = true }) {
                Text("Settings", color = Accent)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = CardBg
            ),
            shape = RoundedCornerShape(13.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(9.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Summary("STOPS", "${places.size}")
                Summary("DELIVERED", "${delivered.count { it }}")
                Summary("DRIVING", "$drivingMinutes min")
                Summary("SERVICE", "$serviceTotal min")
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search delivery address") },
            placeholder = { Text("Type at least 3 letters") },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                cursorColor = Accent,
                focusedLabelColor = Accent,
                unfocusedBorderColor = Muted
            )
        )

        if (suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
                colors = CardDefaults.cardColors(
                    containerColor = CardBg
                )
            ) {
                Column {
                    suggestions.take(5).forEach { place ->
                        TextButton(
                            onClick = { addPlace(place) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(place.name, color = Color.White)
                                Text(
                                    "Tap to add",
                                    color = Accent,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Button(
                onClick = {
                    if (places.size < 2) {
                        message = "Add at least two addresses first."
                    } else {
                        busy = true
                    }
                },
                enabled = !busy && places.size >= 2,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Bg
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Bg
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(if (busy) "Creating..." else "Create route")
            }

            OutlinedButton(
                onClick = { clearAll() },
                enabled = !busy && places.isNotEmpty()
            ) {
                Text("Clear")
            }
        }

        Text(
            message,
            color = Muted,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        if (routeReady) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!routeStarted) {
                    Button(
                        onClick = {
                            if (places.isNotEmpty()) {
                                routeStarted = true
                                activeIndex = delivered.indexOfFirst { !it }
                                    .let { if (it < 0) 0 else it }
                                message = "Route started."
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Bg
                        )
                    ) {
                        Text(
                            "▶ Start route",
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            routeStarted = false
                            message = "Route paused."
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CardBg,
                            contentColor = Accent
                        )
                    ) {
                        Text("Pause route")
                    }

                    OutlinedButton(
                        onClick = {
                            routeStarted = false
                            routeReady = false
                            message = "Route finished."
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Finish")
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (routeStarted) "ACTIVE ROUTE MAP" else "ROUTE MAP",
                color = Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                onClick = { fitRouteOnMap() },
                contentPadding = PaddingValues(horizontal = 3.dp)
            ) {
                Text("Fit route", color = Accent, fontSize = 10.sp)
            }

            TextButton(
                onClick = {
                    if (!locationPermissionGranted) {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    } else {
                        goToMyLocation()
                    }
                },
                contentPadding = PaddingValues(horizontal = 3.dp)
            ) {
                Text("My location", color = Accent, fontSize = 10.sp)
            }

            if (routeStarted && activeIndex in places.indices) {
                TextButton(
                    onClick = { goToActiveStop() },
                    contentPadding = PaddingValues(horizontal = 3.dp)
                ) {
                    Text("Active stop", color = Accent, fontSize = 10.sp)
                }
            }
        }

        // MAP HEIGHT FIXED AT 220 DP
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "DELIVERY STOPS",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "TOTAL $totalMinutes min",
                color = Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }

        if (routeStarted && activeIndex in places.indices) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 5.dp),
                colors = CardDefaults.cardColors(
                    containerColor = DeliveredBg
                )
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        "ACTIVE STOP ${activeIndex + 1}",
                        color = Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Text(
                        places[activeIndex].name,
                        color = Color.White,
                        fontSize = 12.sp
                    )

                    val nextIndex = (activeIndex + 1 until places.size)
                        .firstOrNull { !delivered[it] }

                    if (nextIndex != null) {
                        Text(
                            "Next: ${places[nextIndex].name}",
                            color = Muted,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        if (places.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Search and tap an address to add a stop.",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                itemsIndexed(
                    places,
                    key = { _, p -> "${p.latitude},${p.longitude}" }
                ) { index, place ->
                    val isDelivered = delivered.getOrNull(index) == true
                    val service = serviceMinutes.getOrNull(index)
                        ?: DEFAULT_SERVICE_MINUTES
                    val time = deliveryTimes.getOrNull(index) ?: "09:00"
                    val isActive = routeStarted && index == activeIndex

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                isActive -> Color(0xFF35452C)
                                isDelivered -> DeliveredBg
                                else -> CardBg
                            }
                        ),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(
                                            if (isDelivered || isActive) Accent
                                            else Color(0xFF34434F),
                                            RoundedCornerShape(9.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "${index + 1}",
                                        color = Bg,
                                        fontWeight = FontWeight.Black
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        place.name,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        when {
                                            isDelivered -> "DELIVERED"
                                            isActive -> "ACTIVE STOP"
                                            else -> "PENDING"
                                        },
                                        color = if (isDelivered || isActive) Accent else Muted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                TextButton(
                                    onClick = { removePlace(index) },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        "Remove",
                                        color = Muted,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "DELIVERY TIME",
                                        color = Muted,
                                        fontSize = 9.sp
                                    )
                                    Text(
                                        time,
                                        color = Accent,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        val parts = time.split(":")
                                        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
                                        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

                                        TimePickerDialog(
                                            context,
                                            { _, h, m ->
                                                deliveryTimes[index] = String.format(
                                                    Locale.US,
                                                    "%02d:%02d",
                                                    h,
                                                    m
                                                )
                                            },
                                            hour,
                                            minute,
                                            true
                                        ).show()
                                    }
                                ) {
                                    Text(
                                        "Set time",
                                        color = Accent,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(Modifier.height(5.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "TIME AT CUSTOMER",
                                        color = Muted,
                                        fontSize = 9.sp
                                    )
                                    Text(
                                        "$service min",
                                        color = Accent,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        serviceMinutes[index] =
                                            (serviceMinutes[index] - 5).coerceAtLeast(0)
                                    },
                                    modifier = Modifier.size(38.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("−", fontSize = 20.sp)
                                }

                                Spacer(Modifier.width(6.dp))

                                OutlinedButton(
                                    onClick = {
                                        serviceMinutes[index] =
                                            (serviceMinutes[index] + 5).coerceAtMost(120)
                                    },
                                    modifier = Modifier.size(38.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("+", fontSize = 20.sp)
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            Button(
                                onClick = {
                                    delivered[index] = !delivered[index]

                                    if (delivered[index]) {
                                        message = "Stop ${index + 1} delivered."

                                        if (routeStarted) {
                                            val next = (index + 1 until places.size)
                                                .firstOrNull { !delivered[it] }

                                            if (next != null) {
                                                activeIndex = next
                                                message = "Delivered. Next stop: ${next + 1}."
                                            } else {
                                                routeStarted = false
                                                message = "All deliveries completed!"
                                            }
                                        }
                                    } else {
                                        activeIndex = index
                                        message = "Delivery status undone for stop ${index + 1}."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDelivered) {
                                        Color(0xFF35434A)
                                    } else {
                                        Accent
                                    },
                                    contentColor = if (isDelivered) {
                                        Color.White
                                    } else {
                                        Bg
                                    }
                                ),
                                shape = RoundedCornerShape(9.dp)
                            ) {
                                Text(
                                    if (isDelivered) "Undo delivery" else "Delivered",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(label: String, value: String) {
    Column {
        Text(
            label,
            color = Muted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            value,
            color = Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun numberedMarker(
    mapView: MapView,
    number: Int,
    delivered: Boolean,
    active: Boolean
): BitmapDrawable {
    val density = mapView.resources.displayMetrics.density
    val size = (46 * density).toInt().coerceAtLeast(46)
    val bitmap = Bitmap.createBitmap(
        size,
        size,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = when {
        active -> android.graphics.Color.WHITE
        delivered -> android.graphics.Color.rgb(83, 121, 72)
        else -> android.graphics.Color.rgb(199, 243, 107)
    }
    canvas.drawCircle(size / 2f, size / 2f, size * 0.43f, paint)

    paint.color = android.graphics.Color.rgb(16, 24, 32)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2.5f * density
    canvas.drawCircle(size / 2f, size / 2f, size * 0.43f, paint)

    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.rgb(16, 24, 32)
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    paint.textSize = if (number >= 10) size * 0.35f else size * 0.42f

    val baseline = size / 2f - (paint.ascent() + paint.descent()) / 2f
    canvas.drawText(number.toString(), size / 2f, baseline, paint)

    return BitmapDrawable(mapView.resources, bitmap)
}

private suspend fun searchAddresses(query: String): List<Place> {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val json = requestJson(
        "https://photon.komoot.io/api/?q=$encoded&limit=6&lat=52.52&lon=13.405"
    )
    val features = json.optJSONArray("features") ?: return emptyList()
    val results = mutableListOf<Place>()

    for (i in 0 until features.length()) {
        val feature = features.optJSONObject(i) ?: continue
        val geometry = feature.optJSONObject("geometry") ?: continue
        val coords = geometry.optJSONArray("coordinates") ?: continue
        if (coords.length() < 2) continue

        val lon = coords.optDouble(0, Double.NaN)
        val lat = coords.optDouble(1, Double.NaN)
        if (!lat.isFinite() || !lon.isFinite()) continue

        val props = feature.optJSONObject("properties") ?: JSONObject()
        val name = listOfNotNull(
            props.optString("name").takeIf { it.isNotBlank() },
            props.optString("housenumber").takeIf { it.isNotBlank() },
            props.optString("street").takeIf { it.isNotBlank() },
            props.optString("postcode").takeIf { it.isNotBlank() },
            props.optString("city").takeIf { it.isNotBlank() }
        ).distinct().joinToString(" ")

        if (name.isNotBlank()) {
            results.add(Place(name, lat, lon))
        }
    }

    return results.distinctBy {
        "${it.latitude},${it.longitude}"
    }
}

private suspend fun optimizeRoute(input: List<Place>): RouteResult {
    require(input.size >= 2)

    val coordinates = input.joinToString(";") {
        "${it.longitude},${it.latitude}"
    }

    val table = requestJson(
        "https://router.project-osrm.org/table/v1/driving/" +
            "$coordinates?annotations=duration"
    )
    val array = table.optJSONArray("durations")
        ?: error("Duration matrix missing.")

    val durations = Array(input.size) { i ->
        DoubleArray(input.size) { j ->
            array.optJSONArray(i)?.optDouble(j, Double.POSITIVE_INFINITY)
                ?: Double.POSITIVE_INFINITY
        }
    }

    var bestOrder = input.indices.toList()
    var bestCost = Double.POSITIVE_INFINITY

    for (start in input.indices) {
        val remaining = input.indices.toMutableSet()
        val order = mutableListOf(start)
        remaining.remove(start)

        while (remaining.isNotEmpty()) {
            val current = order.last()
            val next = remaining.minByOrNull {
                durations[current][it]
            } ?: break
            order.add(next)
            remaining.remove(next)
        }

        var improved = true

        while (improved) {
            improved = false

            for (i in 1 until order.size - 1) {
                for (j in i + 1 until order.size) {
                    val candidate = order.toMutableList()
                    candidate.subList(i, j + 1).reverse()

                    if (
                        openRouteCost(candidate, durations) <
                        openRouteCost(order, durations)
                    ) {
                        order.clear()
                        order.addAll(candidate)
                        improved = true
                    }
                }
            }
        }

        val cost = openRouteCost(order, durations)

        if (cost < bestCost) {
            bestCost = cost
            bestOrder = order
        }
    }

    return fetchRoute(bestOrder.map { input[it] })
}

private suspend fun getRouteInCurrentOrder(
    input: List<Place>
): RouteResult {
    require(input.size >= 2)
    return fetchRoute(input)
}

private suspend fun fetchRoute(input: List<Place>): RouteResult {
    val coordinates = input.joinToString(";") {
        "${it.longitude},${it.latitude}"
    }

    val json = requestJson(
        "https://router.project-osrm.org/route/v1/driving/" +
            "$coordinates?overview=full&geometries=geojson"
    )

    val routes = json.optJSONArray("routes")
        ?: error("No route returned.")
    val route = routes.optJSONObject(0)
        ?: error("Empty route response.")
    val geometry = route.optJSONObject("geometry")
        ?: error("Missing geometry.")
    val array = geometry.optJSONArray("coordinates")
        ?: error("Missing coordinates.")

    val points = mutableListOf<GeoPoint>()

    for (i in 0 until array.length()) {
        val coord = array.optJSONArray(i) ?: continue

        if (coord.length() >= 2) {
            points.add(
                GeoPoint(
                    coord.optDouble(1),
                    coord.optDouble(0)
                )
            )
        }
    }

    return RouteResult(
        places = input,
        points = points,
        drivingSeconds = route.optDouble("duration", 0.0),
        distanceMeters = route.optDouble("distance", 0.0)
    )
}

private fun openRouteCost(
    order: List<Int>,
    durations: Array<DoubleArray>
): Double {
    var cost = 0.0

    for (i in 0 until order.size - 1) {
        cost += durations[order[i]][order[i + 1]]
    }

    return cost
}

private suspend fun requestJson(urlString: String): JSONObject =
    withContext(Dispatchers.IO) {
        val connection =
            URL(urlString).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty(
                "User-Agent",
                "RoutivaAndroid/1.0"
            )
            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val code = connection.responseCode

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (code !in 200..299) {
                error("HTTP $code: $body")
            }

            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
