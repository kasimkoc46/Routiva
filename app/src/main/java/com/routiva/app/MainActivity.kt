
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
                mapView.overlays.add(
                    Polyline().apply {
                        setPoints(routePoints)
                        title = "Routiva optimized route"
                        width = 9f
                    }
                )
            }

            places.forEachIndexed { index, place ->
                mapView.overlays.add(
                    Marker(mapView).apply {
                        position = GeoPoint(place.latitude, place.longitude)
                        title = "${index + 1}. ${place.name}"
                        snippet = if (completed.getOrNull(index) == true) {
                            "Delivered"
                        } else {
                            "Delivery stop"
                        }
                    }
                )
            }

            val points = if (routePoints.isNotEmpty()) {
                routePoints
            } else {
                places.map { GeoPoint(it.latitude, it.longitude) }
            }

            if (points.isNotEmpty()) {
                val north = points.maxOf { it.latitude }
                val south = points.minOf { it.latitude }
                val east = points.maxOf { it.longitude }
                val west = points.minOf { it.longitude }

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
                        place == null ->
                            message = "Choose an address from suggestions"

                        places.any {
                            it.latitude == place.latitude &&
                            it.longitude == place.longitude
                        } -> message = "Address already added"

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
                            message = "Calculating driving times..."
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

        require(input.size >= 2) { "Add at least 2 addresses" }

        val coordinates = input.joinToString(";") {
            "${it.longitude},${it.latitude}"
        }

        // Get driving-time matrix for every pair of stops.
        val tableUrl = URL(
            "https://router.project-osrm.org/table/v1/driving/" +
            "$coordinates?annotations=duration"
        )

        val tableJson = requestJson(tableUrl, 25000)

        if (tableJson.optString("code") != "Ok") {
            throw IllegalStateException(
                tableJson.optString("message", "Driving-time matrix failed")
            )
        }

        val durationArray = tableJson.getJSONArray("durations")
        val n = input.size

        val durations = Array(n) { i ->
            DoubleArray(n) { j ->
                val row = durationArray.getJSONArray(i)
                if (row.isNull(j)) {
                    Double.POSITIVE_INFINITY
                } else {
                    row.getDouble(j)
                }
            }
        }

        // Try every possible starting stop, using nearest-neighbour.
        var bestOrder: List<Int>? = null
        var bestCost = Double.POSITIVE_INFINITY

        for (start in 0 until n) {
            val remaining = (0 until n).toMutableSet()
            val order = mutableListOf(start)
            remaining.remove(start)
            var current = start
            var cost = 0.0

            while (remaining.isNotEmpty()) {
                val next = remaining
                    .filter { durations[current][it].isFinite() }
                    .minByOrNull { durations[current][it] }
                    ?: break

                cost += durations[current][next]
                order.add(next)
                remaining.remove(next)
                current = next
            }

            if (order.size != n) continue

            // Improve the open route using 2-opt reversals.
            var improved = true
            while (improved) {
                improved = false

                for (i in 0 until order.size - 1) {
                    for (j in i + 1 until order.size) {
                        val candidate = order.toMutableList()
                        candidate.subList(i, j + 1).reverse()

                        val candidateCost = routeCost(candidate, durations)

                        if (candidateCost + 0.001 < routeCost(order, durations)) {
                            order.clear()
                            order.addAll(candidate)
                            improved = true
                        }
                    }
                }
            }

            cost = routeCost(order, durations)

            if (cost < bestCost) {
                bestCost = cost
                bestOrder = order.toList()
            }
        }

        val order = bestOrder
            ?: throw IllegalStateException(
                "Some addresses are not connected by road"
            )

        val orderedPlaces = order.map { input[it] }
        val orderedCoordinates = orderedPlaces.joinToString(";") {
            "${it.longitude},${it.latitude}"
        }

        // Draw the actual road route in optimized stop order.
        val routeUrl = URL(
            "https://router.project-osrm.org/route/v1/driving/" +
            "$orderedCoordinates?overview=full&geometries=geojson"
        )

        val routeJson = requestJson(routeUrl, 25000)

        if (routeJson.optString("code") != "Ok") {
            throw IllegalStateException(
                routeJson.optString("message", "Road route failed")
            )
        }

        val routes = routeJson.optJSONArray("routes")
            ?: throw IllegalStateException("No route returned")

        if (routes.length() == 0) {
            throw IllegalStateException("No road route found")
        }

        val geometry = routes.getJSONObject(0)
            .getJSONObject("geometry")
            .getJSONArray("coordinates")

        val points = (0 until geometry.length()).map { i ->
            val point = geometry.getJSONArray(i)
            GeoPoint(point.getDouble(1), point.getDouble(0))
        }

        if (points.size < 2) {
            throw IllegalStateException("Route geometry is empty")
        }

        RouteResult(
            orderedPlaces = orderedPlaces,
            points = points,
            message = "Route optimized by estimated driving time"
        )
    }

    private fun routeCost(
        order: List<Int>,
        durations: Array<DoubleArray>
    ): Double {
        var total = 0.0

        for (i in 0 until order.size - 1) {
            val duration = durations[order[i]][order[i + 1]]
            if (!duration.isFinite()) return Double.POSITIVE_INFINITY
            total += duration
        }

        return total
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
