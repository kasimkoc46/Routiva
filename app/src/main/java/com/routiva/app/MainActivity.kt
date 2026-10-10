
package com.routiva.app

import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
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
    var message by remember { mutableStateOf("Add delivery addresses to get started.") }
    var busy by remember { mutableStateOf(false) }
    var optimizeAutomatically by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var routePoints by remember { mutableStateOf(emptyList<GeoPoint>()) }
    var drivingMinutes by remember { mutableIntStateOf(0) }
    var distanceKm by remember { mutableDoubleStateOf(0.0) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val serviceTotal = serviceMinutes.sum()
    val totalMinutes = drivingMinutes + serviceTotal

    fun addPlace(place: Place) {
        val duplicate = places.any {
            kotlin.math.abs(it.latitude - place.latitude) < 0.00001 &&
                kotlin.math.abs(it.longitude - place.longitude) < 0.00001
        }

        if (duplicate) {
            message = "This address is already in the list."
        } else {
            places.add(place)
            delivered.add(false)
            serviceMinutes.add(DEFAULT_SERVICE_MINUTES)
            deliveryTimes.add("09:00")
            routePoints = emptyList()
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
        drivingMinutes = 0
        distanceKm = 0.0
        message = "Delivery stop removed."
    }

    fun clearAll() {
        places.clear()
        delivered.clear()
        serviceMinutes.clear()
        deliveryTimes.clear()
        routePoints = emptyList()
        drivingMinutes = 0
        distanceKm = 0.0
        query = ""
        suggestions = emptyList()
        message = "All stops cleared."
    }

    LaunchedEffect(query) {
        suggestions = emptyList()
        val search = query.trim()
        if (search.length >= 3) {
            delay(450)
            val result = withContext(Dispatchers.IO) {
                runCatching { searchAddresses(search) }
                    .getOrDefault(emptyList())
            }
            if (query.trim() == search) suggestions = result
        }
    }

    LaunchedEffect(
        places.toList(),
        delivered.toList(),
        routePoints
    ) {
        mapView.overlays.clear()

        if (routePoints.size >= 2) {
            val line = Polyline(mapView).apply {
                setPoints(routePoints)
                outlinePaint.color = android.graphics.Color.rgb(199, 243, 107)
                outlinePaint.strokeWidth = 9f
            }
            mapView.overlays.add(line)
        }

        places.forEachIndexed { index, place ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(place.latitude, place.longitude)
                title = "${index + 1}. ${place.name}"
                snippet = if (delivered.getOrNull(index) == true) {
                    "Delivered"
                } else {
                    "Pending delivery"
                }
                icon = numberedMarker(
                    mapView,
                    index + 1,
                    delivered.getOrNull(index) == true
                )
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(marker)
        }

        if (routePoints.isNotEmpty()) {
            runCatching {
                mapView.zoomToBoundingBox(
                    BoundingBox.fromGeoPoints(routePoints),
                    true,
                    55
                )
            }
        } else if (places.isNotEmpty()) {
            mapView.controller.setCenter(
                GeoPoint(places[0].latitude, places[0].longitude)
            )
            mapView.controller.setZoom(13.5)
        } else {
            mapView.controller.setCenter(GeoPoint(52.52, 13.405))
            mapView.controller.setZoom(11.5)
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
            runCatching { optimizeRoute(oldPlaces) }
        }

        result.onSuccess { route ->
            fun key(p: Place) = "${p.latitude},${p.longitude}"

            val oldStatus = oldPlaces.indices.associate {
                key(oldPlaces[it]) to oldDelivered[it]
            }
            val oldService = oldPlaces.indices.associate {
                key(oldPlaces[it]) to oldMinutes[it]
            }
            val oldDeliveryTime = oldPlaces.indices.associate {
                key(oldPlaces[it]) to oldTimes[it]
            }

            places.clear()
            places.addAll(route.places)

            delivered.clear()
            delivered.addAll(route.places.map {
                oldStatus[key(it)] ?: false
            })

            serviceMinutes.clear()
            serviceMinutes.addAll(route.places.map {
                oldService[key(it)] ?: DEFAULT_SERVICE_MINUTES
            })

            deliveryTimes.clear()
            deliveryTimes.addAll(route.places.map {
                oldDeliveryTime[key(it)] ?: "09:00"
            })

            routePoints = route.points
            drivingMinutes = (route.drivingSeconds / 60.0).roundToInt()
            distanceKm = route.distanceMeters / 1000.0
            message = "Route optimized: ${"%.1f".format(route.distanceMeters / 1000.0)} km."
        }.onFailure {
            message = "Route optimization failed. Check your internet connection."
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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic route optimization", color = Color.White)
                            Text(
                                "Choose the stop order automatically.",
                                color = Muted,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = optimizeAutomatically,
                            onCheckedChange = {
                                optimizeAutomatically = it
                            }
                        )
                    }
                    Text(
                        "Each delivery's time and service duration can be set on its own card.",
                        color = Muted,
                        fontSize = 12.sp
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
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
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

            Column(modifier = Modifier.weight(1f)) {
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
                Text("Settings", color = Accent, fontSize = 12.sp)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 7.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(13.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
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
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column {
                    suggestions.take(5).forEach { place ->
                        TextButton(
                            onClick = { addPlace(place) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(
                                horizontal = 10.dp,
                                vertical = 4.dp
                            )
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(place.name, color = Color.White, fontSize = 12.sp)
                                Text("Tap to add", color = Accent, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Button(
                onClick = {
                    if (places.size < 2) {
                        message = "Add at least two addresses first."
                    } else if (!busy && optimizeAutomatically) {
                        busy = true
                    } else if (!optimizeAutomatically) {
                        message = "Automatic optimization is off. Current order is preserved."
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
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (busy) "Optimizing..." else "Optimize route",
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = { clearAll() },
                enabled = !busy && places.isNotEmpty(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Clear")
            }
        }

        Text(
            message,
            color = Muted,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 5.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "ROUTE MAP",
                color = Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${"%.1f".format(distanceKm)} km",
                color = Muted,
                fontSize = 10.sp
            )
        }

        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxWidth()
                .height(155.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp, bottom = 4.dp),
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
                "TOTAL ${totalMinutes} min",
                color = Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
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

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDelivered) DeliveredBg else CardBg
                        ),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(
                                            if (isDelivered) Accent else Color(0xFF34434F),
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

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        place.name,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        if (isDelivered) "DELIVERED" else "PENDING",
                                        color = if (isDelivered) Accent else Muted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                TextButton(
                                    onClick = { removePlace(index) },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Remove", color = Muted, fontSize = 10.sp)
                                }
                            }

                            Spacer(Modifier.height(7.dp))

                            // Manual delivery time for this stop.
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "DELIVERY TIME",
                                        color = Muted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
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
                                            { _, selectedHour, selectedMinute ->
                                                deliveryTimes[index] = String.format(
                                                    Locale.US,
                                                    "%02d:%02d",
                                                    selectedHour,
                                                    selectedMinute
                                                )
                                            },
                                            hour,
                                            minute,
                                            true
                                        ).show()
                                    },
                                    shape = RoundedCornerShape(9.dp)
                                ) {
                                    Text("Set time", color = Accent, fontSize = 11.sp)
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "TIME AT CUSTOMER",
                                        color = Muted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "$service min",
                                        color = Accent,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        serviceMinutes[index] =
                                            (serviceMinutes[index] - 5).coerceAtLeast(0)
                                    },
                                    modifier = Modifier.size(38.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    shape = RoundedCornerShape(9.dp)
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
                                    contentPadding = PaddingValues(0.dp),
                                    shape = RoundedCornerShape(9.dp)
                                ) {
                                    Text("+", fontSize = 20.sp)
                                }
                            }

                            Spacer(Modifier.height(7.dp))

                            Button(
                                onClick = {
                                    delivered[index] = !delivered[index]
                                    message = if (delivered[index]) {
                                        "Stop ${index + 1} marked as delivered."
                                    } else {
                                        "Delivery status undone for stop ${index + 1}."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDelivered) {
                                        Color(0xFF35434A)
                                    } else {
                                        Accent
                                    },
                                    contentColor = if (isDelivered) Color.White else Bg
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
        Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

private fun numberedMarker(
    mapView: MapView,
    number: Int,
    delivered: Boolean
): BitmapDrawable {
    val density = mapView.resources.displayMetrics.density
    val size = (46 * density).toInt().coerceAtLeast(46)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = if (delivered) {
        android.graphics.Color.rgb(83, 121, 72)
    } else {
        android.graphics.Color.rgb(199, 243, 107)
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
    val url =
        "https://photon.komoot.io/api/?q=$encoded&limit=6&lat=52.52&lon=13.405"

    val json = requestJson(url)
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

    return results.distinctBy { "${it.latitude},${it.longitude}" }
}

private suspend fun optimizeRoute(input: List<Place>): RouteResult {
    require(input.size >= 2) { "At least two addresses are required." }

    val places = input.toList()
    val coordinates = places.joinToString(";") {
        "${it.longitude},${it.latitude}"
    }

    val table = requestJson(
        "https://router.project-osrm.org/table/v1/driving/" +
            "$coordinates?annotations=duration"
    )
    val array = table.optJSONArray("durations")
        ?: error("No duration matrix returned.")

    val durations = Array(places.size) { i ->
        DoubleArray(places.size) { j ->
            array.optJSONArray(i)?.optDouble(j, Double.POSITIVE_INFINITY)
                ?: Double.POSITIVE_INFINITY
        }
    }

    var bestOrder = (places.indices).toList()
    var bestCost = Double.POSITIVE_INFINITY

    // Try each starting stop and use nearest-neighbour.
    for (start in places.indices) {
        val remaining = places.indices.toMutableSet()
        val order = mutableListOf<Int>()
        var current = start
        order.add(current)
        remaining.remove(current)

        while (remaining.isNotEmpty()) {
            val next = remaining.minByOrNull {
                durations[current][it]
            } ?: break

            order.add(next)
            remaining.remove(next)
            current = next
        }

        var improved = true
        while (improved) {
            improved = false
            for (i in 1 until order.size - 1) {
                for (j in i + 1 until order.size) {
                    val candidate = order.toMutableList()
                    candidate.subList(i, j + 1).reverse()

                    val candidateCost = openRouteCost(candidate, durations)
                    val currentCost = openRouteCost(order, durations)

                    if (candidateCost < currentCost) {
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

    val ordered = bestOrder.map { places[it] }
    val orderedCoordinates = ordered.joinToString(";") {
        "${it.longitude},${it.latitude}"
    }

    val routeJson = requestJson(
        "https://router.project-osrm.org/route/v1/driving/" +
            "$orderedCoordinates?overview=full&geometries=geojson"
    )

    val routes = routeJson.optJSONArray("routes")
        ?: error("No route returned.")
    val route = routes.optJSONObject(0)
        ?: error("Route response was empty.")

    val geometry = route.optJSONObject("geometry")
        ?: error("Route geometry is missing.")
    val coordinatesArray = geometry.optJSONArray("coordinates")
        ?: error("Route coordinates are missing.")

    val points = mutableListOf<GeoPoint>()
    for (i in 0 until coordinatesArray.length()) {
        val coordinate = coordinatesArray.optJSONArray(i) ?: continue
        if (coordinate.length() >= 2) {
            points.add(
                GeoPoint(
                    coordinate.optDouble(1),
                    coordinate.optDouble(0)
                )
            )
        }
    }

    return RouteResult(
        places = ordered,
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
        val connection = (URL(urlString).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "RoutivaAndroid/1.0")
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code: $body")
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
