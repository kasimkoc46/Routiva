
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
                } catch (_: Exception) {
                    message = "Address search failed. Check internet."
                }
            }
        }

        LaunchedEffect(places.size, completed.toList(), routePoints) {
            mapView.overlays.clear()

            if (routePoints.isNotEmpty()) {
                val line = Polyline().apply {
                    setPoints(routePoints)
                    title = "Routiva route"
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

            if (places.isNotEmpty()) {
                val last = places.last()
                mapView.controller.animateTo(
                    GeoPoint(last.latitude, last.longitude)
                )
            }

            mapView.invalidate()
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
                        .heightIn(max = 170.dp)
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
                    if (place != null &&
                        places.none {
                            it.latitude == place.latitude &&
                            it.longitude == place.longitude
                        }
                    ) {
                        places.add(place)
                        completed.add(false)
                        query = ""
                        selectedPlace = null
                        suggestions = emptyList()
                        message = "Address added"
                    } else if (place == null) {
                        message = "Select an address from suggestions first"
                    } else {
                        message = "Address already added"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add address")
            }

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
                    modifier = Modifier.weight(1f),
                    enabled = !busy
                ) {
                    Text("Create route")
                }

                OutlinedButton(
                    onClick = {
                        places.clear()
                        completed.clear()
                        routePoints = emptyList()
                        message = "Addresses cleared"
                    },
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
                        places.addAll(result.first)
                        completed.clear()
                        completed.addAll(
                            result.first.map { false }
                        )
                        routePoints = result.second
                        message = "Route created"
                    } catch (_: Exception) {
                        message = "Route failed. Try again later."
                    } finally {
                        busy = false
                    }
                }
            }

            Text(message, style = MaterialTheme.typography.bodyMedium)

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
                                    message = "Delivery completed"
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
                    .height(240.dp)
            )
        }
    }

    private suspend fun searchPlaces(text: String): List<Place> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = URL(
                "https://photon.komoot.io/api/?q=$encoded&limit=5&lat=52.52&lon=13.405"
            )

            val connection =
                url.openConnection() as HttpURLConnection

            try {
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty(
                    "User-Agent",
                    "RoutivaAndroid/1.0"
                )

                val body = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                val features = JSONObject(body).getJSONArray("features")
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
                            it.isNotBlank() &&
                            it != "null"
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
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun createRoute(
        input: List<Place>
    ): Pair<List<Place>, List<GeoPoint>> =
        withContext(Dispatchers.IO) {
            val coordinates = input.joinToString(";") {
                "${it.longitude},${it.latitude}"
            }

            val url = URL(
                "https://router.project-osrm.org/trip/v1/driving/" +
                "$coordinates?source=first&destination=last" +
                "&roundtrip=false&overview=full&geometries=geojson"
            )

            val connection =
                url.openConnection() as HttpURLConnection

            try {
                connection.connectTimeout = 15000
                connection.readTimeout = 20000

                val body = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                val json = JSONObject(body)

                if (json.optString("code") != "Ok") {
                    throw IllegalStateException("OSRM route error")
                }

                val trip = json.getJSONArray("trips").getJSONObject(0)
                val waypoints = json.getJSONArray("waypoints")
                val ordered = input.mapIndexed { index, place ->
                    val waypoint = waypoints.getJSONObject(index)
                    waypoint.optInt("waypoint_index", index) to place
                }.sortedBy { it.first }.map { it.second }

                val coordinatesArray = trip
                    .getJSONObject("geometry")
                    .getJSONArray("coordinates")

                val line = (0 until coordinatesArray.length()).map { i ->
                    val point = coordinatesArray.getJSONArray(i)
                    GeoPoint(
                        point.getDouble(1),
                        point.getDouble(0)
                    )
                }

                ordered to line
            } finally {
                connection.disconnect()
            }
        }
}
