
package com.routiva.app

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

private val RoutivaBg = Color(0xFF101820)
private val RoutivaCard = Color(0xFF202B35)
private val RoutivaAccent = Color(0xFFC7F36B)
private val RoutivaMuted = Color(0xFFB3C0CB)
private val RoutivaGreen = Color(0xFF27352B)

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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = RoutivaAccent,
                    onPrimary = Color(0xFF17251B),
                    secondary = Color(0xFF9DBB66),
                    background = RoutivaBg,
                    surface = RoutivaCard,
                    onSurface = Color.White,
                    onBackground = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = RoutivaBg
                ) {
                    RoutivaScreen()
                }
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
        var message by remember { mutableStateOf("Add addresses to plan your route") }
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
                        title = "Routiva route"
                        width = 10f
                        outlinePaint.color = android.graphics.Color.rgb(83, 139, 35)
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
                .background(RoutivaBg)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .background(RoutivaAccent, RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "R",
                        color = Color(0xFF17251B),
                        style = MaterialTheme.typography.headlineMedium
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Routiva",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Text(
                        "SMART DELIVERY PLANNER",
                        style = MaterialTheme.typography.labelSmall,
                        color = RoutivaMuted
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = RoutivaGreen
                ) {
                    Text(
                        "READY",
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 8.dp
                        ),
                        color = RoutivaAccent,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = RoutivaCard
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "DELIVERY OVERVIEW",
                            color = RoutivaMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "${places.size} stops",
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "COMPLETED",
                            color = RoutivaMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "${completed.count { it }} / ${places.size}",
                            color = RoutivaAccent,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search delivery address") },
                placeholder = { Text("Start typing an address...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RoutivaAccent,
                    focusedLabelColor = RoutivaAccent,
                    cursorColor = RoutivaAccent
                )
            )

            if (suggestions.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = RoutivaCard
                    )
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
                                Text(
                                    place.name,
                                    color = Color.White
                                )
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
                            message = "Address added successfully"
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RoutivaAccent,
                    contentColor = Color(0xFF17251B)
                )
            ) {
                Text("＋  Add delivery address")
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
                            message = "Optimizing driving route..."
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF35472C),
                        contentColor = RoutivaAccent
                    )
                ) {
                    Text(if (busy) "Please wait..." else "Optimize route")
                }

                OutlinedButton(
                    onClick = {
                        places.clear()
                        completed.clear()
                        routePoints = emptyList()
                        message = "All addresses cleared"
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
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

            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = RoutivaGreen
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 10.dp
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD8E7C4)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Delivery stops",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Text(
                    "${places.size} TOTAL",
                    style = MaterialTheme.typography.labelMedium,
                    color = RoutivaAccent
                )
            }

            Spacer(Modifier.height(5.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                itemsIndexed(places) { index, place ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = RoutivaCard
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            if (completed.getOrNull(index) == true)
                                                RoutivaAccent
                                            else Color(0xFF34434D),
                                            RoundedCornerShape(11.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "${index + 1}",
                                        color = if (completed.getOrNull(index) == true)
                                            Color(0xFF17251B)
                                        else Color.White,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        place.name,
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (completed.getOrNull(index) == true)
                                            "DELIVERED"
                                        else "Estimated stop · 10 min",
                                        color = if (completed.getOrNull(index) == true)
                                            RoutivaAccent
                                        else RoutivaMuted,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        completed[index] = true
                                        message = if (completed.all { it }) {
                                            "All deliveries completed"
                                        } else {
                                            "Delivery marked complete"
                                        }
                                    },
                                    enabled = completed.getOrNull(index) == false,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = RoutivaAccent,
                                        contentColor = Color(0xFF17251B)
                                    )
                                ) {
                                    Text("Delivered")
                                }

                                OutlinedButton(
                                    onClick = {
                                        places.removeAt(index)
                                        completed.removeAt(index)
                                        routePoints = emptyList()
                                        message = "Address removed"
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Route map",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Text(
                    "OPENSTREETMAP",
                    style = MaterialTheme.typography.labelSmall,
                    color = RoutivaAccent
                )
            }

            Spacer(Modifier.height(8.dp))

            AndroidView(
                factory = { mapView },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
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
                val coords = feature.getJSONObject("geometry")
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
                if (row.isNull(j)) Double.POSITIVE_INFINITY
                else row.getDouble(j)
            }
        }

        var bestOrder: List<Int>? = null
        var bestCost = Double.POSITIVE_INFINITY

        for (start in 0 until n) {
            val remaining = (0 until n).toMutableSet()
            val order = mutableListOf(start)
            remaining.remove(start)
            var current = start

            while (remaining.isNotEmpty()) {
                val next = remaining
                    .filter { durations[current][it].isFinite() }
                    .minByOrNull { durations[current][it] }
                    ?: break

                order.add(next)
                remaining.remove(next)
                current = next
            }

            if (order.size != n) continue

            var improved = true
            while (improved) {
                improved = false
                for (i in 0 until order.size - 1) {
                    for (j in i + 1 until order.size) {
                        val candidate = order.toMutableList()
                        candidate.subList(i, j + 1).reverse()
                        if (routeCost(candidate, durations) + 0.001 <
                            routeCost(order, durations)
                        ) {
                            order.clear()
                            order.addAll(candidate)
                            improved = true
                        }
                    }
                }
            }

            val cost = routeCost(order, durations)
            if (cost < bestCost) {
                bestCost = cost
                bestOrder = order.toList()
            }
        }

        val order = bestOrder
            ?: throw IllegalStateException("Some addresses are not connected by road")

        val orderedPlaces = order.map { input[it] }
        val orderedCoordinates = orderedPlaces.joinToString(";") {
            "${it.longitude},${it.latitude}"
        }

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
            connection.setRequestProperty("User-Agent", "RoutivaAndroid/1.0")
            connection.setRequestProperty("Accept", "application/json")

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status: ${body.take(120)}")
            }

            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
