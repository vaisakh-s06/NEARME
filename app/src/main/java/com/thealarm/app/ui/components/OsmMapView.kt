package com.thealarm.app.ui.components

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.thealarm.app.R
import com.thealarm.app.model.Destination
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

@Composable
fun OsmMapView(
    modifier: Modifier = Modifier,
    destination: Destination?,
    currentLat: Double?,
    currentLon: Double?,
    routePoints: List<GeoPoint> = emptyList(),
    recenterTrigger: Int = 0,
    onMapTapped: (GeoPoint) -> Unit
) {
    val context = LocalContext.current

    // Set OSM User-Agent & Persistent Offline Cache configuration
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        val config = Configuration.getInstance()
        config.load(context, prefs)
        config.userAgentValue = context.packageName
        // Keep up to 600 MB of map tiles persistently in device storage for offline view
        config.tileFileSystemCacheMaxBytes = 600L * 1024 * 1024
        config.tileFileSystemCacheTrimBytes = 500L * 1024 * 1024
        // Extend cache validity so viewed tiles stay offline without expiring
        config.expirationExtendedDuration = 1000L * 60 * 60 * 24 * 60
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var destinationMarker by remember { mutableStateOf<Marker?>(null) }
    var userLocationMarker by remember { mutableStateOf<Marker?>(null) }
    var radiusCircles by remember { mutableStateOf<List<Polygon>>(emptyList()) }
    var roadPolyline by remember { mutableStateOf<Polyline?>(null) }
    var hasInitialCentered by remember { mutableStateOf(false) }

    var lastAnimatedDestLat by remember { mutableStateOf<Double?>(null) }
    var lastAnimatedDestLon by remember { mutableStateOf<Double?>(null) }

    // Respond to Recenter Button clicks
    LaunchedEffect(recenterTrigger) {
        if (recenterTrigger > 0 && currentLat != null && currentLon != null && currentLat != 0.0 && currentLon != 0.0) {
            mapViewRef?.let { mv ->
                val userPoint = GeoPoint(currentLat, currentLon)
                mv.controller.animateTo(userPoint)
                mv.controller.setZoom(16.5)
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setUseDataConnection(true)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

                // Initial default center
                val startPoint = if (currentLat != null && currentLon != null && currentLat != 0.0 && currentLon != 0.0) {
                    GeoPoint(currentLat, currentLon)
                } else {
                    GeoPoint(9.9312, 76.2673)
                }
                controller.setZoom(15.0)
                controller.setCenter(startPoint)

                // Tap to set destination pin
                val eventsReceiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        p?.let { onMapTapped(it) }
                        return true
                    }
                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                }
                overlays.add(MapEventsOverlay(eventsReceiver))
                mapViewRef = this
            }
        },
        update = { mapView ->
            mapViewRef = mapView

            // Auto-center once when user location first becomes available
            if (!hasInitialCentered && currentLat != null && currentLon != null && currentLat != 0.0 && currentLon != 0.0) {
                hasInitialCentered = true
                val userPoint = GeoPoint(currentLat, currentLon)
                mapView.controller.animateTo(userPoint)
                mapView.controller.setZoom(16.0)
            }

            // Draw Road Route Polyline if available
            roadPolyline?.let { mapView.overlays.remove(it) }
            if (routePoints.isNotEmpty()) {
                val line = Polyline(mapView).apply {
                    setPoints(routePoints)
                    outlinePaint.color = 0xFF00E5FF.toInt()
                    outlinePaint.strokeWidth = 9f
                }
                mapView.overlays.add(0, line)
                roadPolyline = line
            } else {
                roadPolyline = null
            }

            // Update Destination Marker & Radius Circle, and auto-move map to selected location
            if (destination != null) {
                val destPoint = GeoPoint(destination.latitude, destination.longitude)

                if (destinationMarker == null) {
                    destinationMarker = Marker(mapView).apply {
                        title = destination.name
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        mapView.overlays.add(this)
                    }
                }
                destinationMarker?.position = destPoint
                destinationMarker?.title = destination.name

                // Clear previous circles
                radiusCircles.forEach { mapView.overlays.remove(it) }
                val newCircles = mutableListOf<Polygon>()

                // 1. Draw outer early warning circles (if larger than destination.radiusMeters and early warning enabled)
                if (destination.enableEarlyWarning) {
                    destination.alertZones.filter { it > destination.radiusMeters }.sortedDescending().forEach { zoneRadius ->
                        val warningCircle = Polygon(mapView).apply {
                            points = Polygon.pointsAsCircle(destPoint, zoneRadius.toDouble())
                            fillColor = 0x0D00E5FF
                            strokeColor = 0x6600E5FF.toInt()
                            strokeWidth = 2f
                        }
                        mapView.overlays.add(warningCircle)
                        newCircles.add(warningCircle)
                    }
                }

                // 2. Draw the primary destination arrival radius circle (the default setting chosen by the user)
                val primaryCircle = Polygon(mapView).apply {
                    points = Polygon.pointsAsCircle(destPoint, destination.radiusMeters.toDouble())
                    fillColor = 0x3300E5FF
                    strokeColor = 0xFF00E5FF.toInt()
                    strokeWidth = 3.5f
                }
                mapView.overlays.add(primaryCircle)
                newCircles.add(primaryCircle)
                radiusCircles = newCircles

                // Automatically move map to selected destination if new or changed
                if (lastAnimatedDestLat != destination.latitude || lastAnimatedDestLon != destination.longitude) {
                    lastAnimatedDestLat = destination.latitude
                    lastAnimatedDestLon = destination.longitude
                    mapView.controller.animateTo(destPoint)
                    mapView.controller.setZoom(15.5)
                }
            } else {
                destinationMarker?.let { mapView.overlays.remove(it) }
                destinationMarker = null
                radiusCircles.forEach { mapView.overlays.remove(it) }
                radiusCircles = emptyList()
                lastAnimatedDestLat = null
                lastAnimatedDestLon = null
            }

            // Update Current Location Marker (Distinct pulsing blue dot)
            if (currentLat != null && currentLon != null && currentLat != 0.0 && currentLon != 0.0) {
                val userPoint = GeoPoint(currentLat, currentLon)
                if (userLocationMarker == null) {
                    userLocationMarker = Marker(mapView).apply {
                        title = "Your Current Location"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = ContextCompat.getDrawable(context, R.drawable.ic_my_location_dot)
                        mapView.overlays.add(this)
                    }
                }
                userLocationMarker?.position = userPoint
                userLocationMarker?.icon = ContextCompat.getDrawable(context, R.drawable.ic_my_location_dot)
            }

            mapView.invalidate()
        }
    )
}
