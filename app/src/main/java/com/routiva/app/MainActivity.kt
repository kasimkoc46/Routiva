
package com.routiva.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

data class RouteResult(
    val orderedPlaces: List<Place>,
    val points: List<GeoPoint>,
    val message: String
)

class MainActivity : ComponentActivity() {

    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName

        mapView = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(11.0)
            controller.setCenter(GeoPoint(52.52, 13.405))
        }

        setContent {
            MaterialTheme {
                RoutivaScreen()
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

    @Composable
    private fun RoutivaScreen() {
        var query by remember { mutableStateOf("") }
        var suggestions by remember { mutableStateOf(listOf<Place>()) }
        var selectedPlace by remember { mutableStateOf<Place?>(null) }
        var message by remember { mutableStateOf("Enter a delivery address") }
        var busy by remember { mutableStateOf(false) }
        var routePoints by remember { mutableStateOf(listOf<GeoPoint>()) }

        val places = remember { mutableStateListOf<Place>() }
        val completed = remember { mutableStateListOf<Boolean>() }

        LaunchedEffect(query) {
            suggestions = emptyList()
            selectedPlace = null

            if (query.trim().length >= 3) {
                delay(450)
                try {
                    suggestions = searchPlaces(query.trim())
                    if (suggestions.isEmpty()) {
                        message = "No addresses found"
                    }
                } catch (e: Exception) {
                    message = "Search error: ${e.message}".take(180)
                }
            }
        }

        LaunchedEffect(places.toList(), completed.toList(), routePoints) {
            mapView.overlays.clear()

            if (routePoints.size >= 2) {
                val line = Polyline().apply {
                    setPoints(routePoints)
                    title = "Optimized driving route"
                    width = 9f
                }
                mapView.overlays.add(line)
            }

            places.forEachIndexed { index, place ->
                val marker = Marker(mapView).apply {
                    position = GeoPoint(place.latitude, place.longitude)
                    title = "${index + 1}. ${place.name}"
                    snippet = if (completed.getOrNull(index) == true) {
                        "Delivered"
                    } else {
                        "Delivery stop"
                    }
                }
                mapView.overlays.add(marker)
            }

            val pointsToFit = if (routePoints.isNotEmpty()) {
                routePoints
            } else {
                places.map { GeoPoint(it.latitude, it.longitude) }
            }

            if (pointsToFit.isNotEmpty()) {
                val north = pointsToFit.maxOf { it.latitude }
                val south = pointsToFit.minOf { it.latitude }
                val east = pointsToFit.maxOf { it.longitude }
                val west = pointsToFit.minOf { it.longitude }

                mapView.post {
                    mapView.zoomToBoundingBox(
                        BoundingBox(north, east, south, west),
                        true,
                        60
                    )
                    mapView.invalidate()
                }
            } else {
                mapView.invalidate()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                "Routiva",
                style = MaterialTheme.typography.headlineLarge
            )
            Text("Smart delivery route planning")

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Enter delivery address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (suggestions.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                ) {
                    LazyColumn {
                        itemsIndexed(suggestions) { _, place ->
                            TextButton(
                                onClick = {
                                    selectedPlace = place
                                    query = place.name
                                    suggestions = emptyList()
                                    message = "Address selected"
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(place.name)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    val place = selectedPlace

                    when {
                        place == null -> {
                            message = "Choose an address from suggestions"
                        }

                        places.any {
                            it.latitude == place.latitude &&
                            it.longitude == place.longitude
                        } -> {
                            message = "Address already added"
                        }

                        else -> {
                            places.add(place)
                            completed.add(false)
                            query = ""
                            selectedPlace = null
                            suggestions = emptyList()
                            routePoints = emptyList()
                            message = "Address added"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add address")
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (places.size < 2) {
                            message = "Add at least 2 addresses"
                        } else {
                            busy = true
                            message = "Optimizing stop order..."
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (busy) "Please wait..." else "Create route")
                }

                OutlinedButton(
                    onClick = {
                        places.clear()
                        completed.clear()
                        routePoints = emptyList()
                        message = "Addresses cleared"
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear all")
                }
            }

            LaunchedEffect(busy) {
                if (busy) {
                    try {
                        val result = createRoute(places.toList())

                        places.clear()
                        places.addAll(result.orderedPlaces)

                        completed.clear()
                        completed.addAll(
                            result.orderedPlaces.map { false }
                        )

                        routePoints = result.points
                        message = result.message
                    } catch (e: Exception) {
                        message = (
                            "Route error: " +
                            (e.message ?: e.javaClass.simpleName)
                        ).take(220)
                    } finally {
                        busy = false
                    }
                }
            }

            Text(message, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(8.dp))

            Text(
                "Delivery addresses (${places.size})",
                style = MaterialTheme.typography.titleMedium
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                itemsIndexed(places) { index, place ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Text("${index + 1}. ${place.name}")

                        Text(
                            if (completed.getOrNull(index) == true) {
                                "Delivered"
                            } else {
                                "Estimated stop: 10 minutes"
                            }
                        )

                        Row {
                            Button(
                                onClick = {
                                    completed[index] = true
                                    message = if (completed.all { it }) {
                                        "All deliveries completed"
                                    } else {
                                        "Delivered. Continue to next stop."
                                    }
                                },
                                enabled = completed.getOrNull(index) == false
                            ) {
                                Text("Delivered")
                            }

                            Spacer(Modifier.width(8.dp))

                            OutlinedButton(
                                onClick = {
                                    places.removeAt(index)
                                    completed.removeAt(index)
                                    routePoints = emptyList()
                                    message = "Address removed"
                                }
                            ) {
                                Text("Remove")
                            }
                        }

                        HorizontalDivider()
                    }
                }
            }

            Text("Map • OpenStreetMap")

            AndroidView(
                factory = { mapView },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            )
        }
    }

    private suspend fun searchPlaces(text: String): List<Place> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = URL(
                "https://photon.komoot.io/api/" +
                "?q=$encoded&limit=6&lat=52.52&lon=13.405"
            )

            val json = requestJson(url, 12000)
            val features = json.getJSONArray("features")
            val results = mutableListOf<Place>()

            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val coords = feature
                    .getJSONObject("geometry")
                    .getJSONArray("coordinates")
                val props = feature.getJSONObject("properties")

                val name = listOf(
                    props.optString("name"),
                    props.optString("housenumber"),
                    props.optString("street"),
                    props.optString("postcode"),
                    props.optString("city")
                )
                    .filter { it.isNotBlank() && it != "null" }
                    .distinct()
                    .joinToString(" ")

                if (name.isNotBlank()) {
                    results.add(
                        Place(
                            name = name,
                            longitude = coords.getDouble(0),
                            latitude = coords.getDouble(1)
                        )
                    )
                }
            }

            results
        }

    private suspend fun createRoute(
        input: List<Place>
    ): RouteResult = withContext(Dispatchers.IO) {
        require(input.size >= 2) {
            "Add at least 2 addresses"
        }

        val coordinates = input.joinToString(";") {
            "${it.longitude},${it.latitude}"
        }

        // Try optimizing the start, end, and intermediate stops.
        try {
            val tripUrl = URL(
                "https://router.project-osrm.org/trip/v1/driving/" +
                "$coordinates?source=any&destination=any" +
                "&roundtrip=false&overview=full&geometries=geojson"
            )

            val json = requestJson(tripUrl, 20000)

            if (json.optString("code") != "Ok") {
                throw IllegalStateException(
                    json.optString("message", "Trip service failed")
                )
            }

            val trips = json.optJSONArray("trips")
                ?: throw IllegalStateException("No trip returned")

            if (trips.length() == 0) {
                throw IllegalStateException("No trip found")
            }

            val trip = trips.getJSONObject(0)
            val waypoints = json.getJSONArray("waypoints")

            val ordered = input.mapIndexed { index, place ->
                waypoints.getJSONObject(index)
                    .optInt("waypoint_index", index) to place
            }
                .sortedBy { it.first }
                .map { it.second }

            val points = parseGeometry(trip)

            if (points.size < 2) {
                throw IllegalStateException("Empty route geometry")
            }

            return@withContext RouteResult(
                ordered,
                points,
                "Optimized route created"
            )
        } catch (_: Exception) {
            // If Trip optimization is unavailable, try normal routing.
        }

        // Fallback: a road route through addresses in entered order.
        val routeUrl = URL(
            "https://router.project-osrm.org/route/v1/driving/" +
            "$coordinates?overview=full&geometries=geojson"
        )

        val json = requestJson(routeUrl, 20000)

        if (json.optString("code") != "Ok") {
            throw IllegalStateException(
                json.optString("message", "Road route failed")
            )
        }

        val routes = json.optJSONArray("routes")
            ?: throw IllegalStateException("No route returned")

        if (routes.length() == 0) {
            throw IllegalStateException("No road route found")
        }

        val points = parseGeometry(routes.getJSONObject(0))

        if (points.size < 2) {
            throw IllegalStateException("Empty route geometry")
        }

        RouteResult(
            input,
            points,
            "Route created in entered order (optimization unavailable)"
        )
    }

    private fun parseGeometry(route: JSONObject): List<GeoPoint> {
        val coords = route
            .getJSONObject("geometry")
            .getJSONArray("coordinates")

        return (0 until coords.length()).map { i ->
            val point = coords.getJSONArray(i)
            GeoPoint(point.getDouble(1), point.getDouble(0))
        }
    }

    private fun requestJson(
        url: URL,
        timeout: Int
    ): JSONObject {
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.setRequestProperty(
                "User-Agent",
                "RoutivaAndroid/1.0"
            )
            connection.setRequestProperty("Accept", "application/json")

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (status !in 200..299) {
                throw IllegalStateException(
                    "HTTP $status: ${body.take(120)}"
                )
            }

            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
