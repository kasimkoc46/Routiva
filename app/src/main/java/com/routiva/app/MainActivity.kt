
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
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double
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

        val places = remember { mutableStateListOf<Place>() }
        val completed = remember { mutableStateListOf<Boolean>() }

        var routePoints by remember {
            mutableStateOf<List<GeoPoint>>(emptyList())
        }

        var message by remember {
            mutableStateOf("Enter a delivery address")
        }

        var busy by remember { mutableStateOf(false) }

        LaunchedEffect(query) {
            selectedPlace = null
            suggestions = emptyList()

            if (query.trim().length >= 3) {
                delay(450)

                try {
                    suggestions = searchPlaces(query.trim())

                    if (suggestions.isEmpty()) {
                        message = "No addresses found"
                    }
                } catch (e: Exception) {
                    message = "Address search failed: ${e.message}"
                        .take(180)
                }
            }
        }

        LaunchedEffect(
            places.toList(),
            completed.toList(),
            routePoints
        ) {
            mapView.overlays.clear()

            if (routePoints.isNotEmpty()) {
                val line = Polyline().apply {
                    setPoints(routePoints)
                    title = "Routiva route"
                    width = 9f
                }
                mapView.overlays.add(line)
            }

            places.forEachIndexed { index, place ->
                val marker = Marker(mapView).apply {
                    position = GeoPoint(
                        place.latitude,
                        place.longitude
                    )
                    title = "${index + 1}. ${place.name}"
                    snippet = if (completed.getOrNull(index) == true) {
                        "Delivered"
                    } else {
                        "Delivery stop"
                    }
                }

                mapView.overlays.add(marker)
            }

            if (routePoints.isNotEmpty()) {
                val points = routePoints
                val north = points.maxOf { it.latitude }
                val south = points.minOf { it.latitude }
                val east = points.maxOf { it.longitude }
                val west = points.minOf { it.longitude }

                mapView.post {
                    mapView.zoomToBoundingBox(
                        org.osmdroid.util.BoundingBox(
                            north, east, south, west
                        ),
                        true,
                        50
                    )
                    mapView.invalidate()
                }
            } else if (places.isNotEmpty()) {
                val last = places.last()
                mapView.controller.animateTo(
                    GeoPoint(last.latitude, last.longitude)
                )
                mapView.invalidate()
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
                text = "Routiva",
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

                    if (place == null) {
                        message = "Choose an address from suggestions"
                    } else if (
                        places.any {
                            it.latitude == place.latitude &&
                            it.longitude == place.longitude
                        }
                    ) {
                        message = "Address already added"
                    } else {
                        places.add(place)
                        completed.add(false)
                        query = ""
                        selectedPlace = null
                        suggestions = emptyList()
                        routePoints = emptyList()
                        message = "Address added"
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
                            message = "Creating route..."
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

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Delivery addresses (${places.size})",
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
                                    message = if (
                                        completed.all { it }
                                    ) {
                                        "All deliveries completed"
                                    } else {
                                        "Delivery completed. Continue to next stop."
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

    private suspend fun searchPlaces(
        text: String
    ): List<Place> = withContext(Dispatchers.IO) {

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
            val coordinates = feature
                .getJSONObject("geometry")
                .getJSONArray("coordinates")
            val properties = feature.getJSONObject("properties")

            val name = listOf(
                properties.optString("name"),
                properties.optString("housenumber"),
                properties.optString("street"),
                properties.optString("postcode"),
                properties.optString("city")
            )
                .filter {
                    it.isNotBlank() && it != "null"
                }
                .distinct()
                .joinToString(" ")

            if (name.isNotBlank()) {
                results.add(
                    Place(
                        name = name,
                        longitude = coordinates.getDouble(0),
                        latitude = coordinates.getDouble(1)
                    )
                )
            }
        }

        results
    }

    private data class RouteResult(
        val orderedPlaces: List<Place>,
        val points: List<GeoPoint>,
        val message: String
    )

    private suspend fun createRoute(
        input: List<Place>
    ): RouteResult = withContext(Dispatchers.IO) {

        require(input.size >= 2) {
            "Add at least 2 addresses"
        }

        val coordinates = input.joinToString(";") {
            "${it.longitude},${it.latitude}"
        }

        // First try to optimize the stop order with OSRM Trip.
        try {
            val tripUrl = URL(
                "https://router.project-osrm.org/trip/v1/driving/" +
                "$coordinates?source=first&destination=last" +
                "&roundtrip=false&overview=full&geometries=geojson"
            )

            val json = requestJson(tripUrl, 20000)

            if (json.optString("code") != "Ok") {
                throw IllegalStateException(
                    json.optString("message", "Trip service error")
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
                val waypoint = waypoints.getJSONObject(index)
                waypoint.optInt("waypoint_index", index) to place
            }.sortedBy { it.first }.map { it.second }

            val points = parseGeometry(trip)

            if (points.size < 2) {
                throw IllegalStateException("Route geometry is empty")
            }

            return@withContext RouteResult(
                ordered,
                points,
                "Optimized route created"
            )
        } catch (e: Exception) {
            // If optimization fails, try a route through addresses
            // in the order they were entered.
        }

        // Fallback: route through all addresses in their current order.
        val routeUrl = URL(
            "https://router.project-osrm.org/route/v1/driving/" +
            "$coordinates?overview=full&geometries=geojson"
        )

        val routeJson = requestJson(routeUrl, 20000)

        if (routeJson.optString("code") != "Ok") {
            throw IllegalStateException(
                routeJson.optString(
                    "message",
                    "OSRM could not calculate this route"
                )
            )
        }

        val routes = routeJson.optJSONArray("routes")
            ?: throw IllegalStateException("No route returned")

        if (routes.length() == 0) {
            throw IllegalStateException("No road route found")
        }

        val points = parseGeometry(routes.getJSONObject(0))

        if (points.size < 2) {
            throw IllegalStateException("Route geometry is empty")
        }

        RouteResult(
            input,
            points,
            "Route created in address order"
        )
    }

    private fun parseGeometry(
        route: JSONObject
    ): List<GeoPoint> {
        val coordinates = route
            .getJSONObject("geometry")
            .getJSONArray("coordinates")

        return (0 until coordinates.length()).map { i ->
            val point = coordinates.getJSONArray(i)
            GeoPoint(
                point.getDouble(1),
                point.getDouble(0)
            )
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
            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

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
                    "Server HTTP $status: ${body.take(120)}"
                )
            }

            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
