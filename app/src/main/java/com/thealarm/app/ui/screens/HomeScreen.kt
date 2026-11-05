package com.thealarm.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thealarm.app.R
import com.thealarm.app.model.AlertStage
import com.thealarm.app.model.AppLanguage
import com.thealarm.app.model.DebugInfo
import com.thealarm.app.model.Destination
import com.thealarm.app.model.EnglishStrings
import com.thealarm.app.model.JourneyState
import com.thealarm.app.model.MalayalamStrings
import com.thealarm.app.network.NominatimSearch
import com.thealarm.app.network.OsrmRouting
import com.thealarm.app.network.RoadRouteInfo
import com.thealarm.app.service.AlarmService
import com.thealarm.app.ui.components.OsmMapView
import com.thealarm.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    journeyState: JourneyState,
    currentDest: Destination?,
    debugInfo: DebugInfo,
    userLat: Double?,
    userLon: Double?,
    onStartAlarm: (Destination, String) -> Unit,
    onCancelAlarm: () -> Unit,
    onSimulateJourney: () -> Unit,
    onSimulateMissedStop: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentLang by remember { mutableStateOf(AppLanguage.EN) }
    val strings = if (currentLang == AppLanguage.ML) MalayalamStrings else EnglishStrings

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Destination>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    var alertZones by remember { mutableStateOf(listOf(2000f, 500f, 100f)) }
    var enableEarlyWarning by remember { mutableStateOf(true) }
    var enableMissedStopAlert by remember { mutableStateOf(true) }

    var selectedDestination by remember {
        mutableStateOf(
            currentDest ?: Destination(
                name = "Sample Destination",
                address = "Select place on map or search",
                latitude = 9.9312,
                longitude = 76.2673,
                radiusMeters = 500f,
                alertZones = listOf(2000f, 500f, 100f),
                enableEarlyWarning = true,
                enableMissedStopAlert = true
            )
        )
    }
    var selectedRadius by remember { mutableFloatStateOf(selectedDestination.radiusMeters) }
    var showDebugPanel by remember { mutableStateOf(false) }
    var recenterTrigger by remember { mutableIntStateOf(0) }
    var showCustomRadiusDialog by remember { mutableStateOf(false) }
    var showZoneConfigDialog by remember { mutableStateOf(false) }

    var roadRouteInfo by remember { mutableStateOf<RoadRouteInfo?>(null) }
    var isLoadingRoadDistance by remember { mutableStateOf(false) }

    // Use live user location from MainActivity or tracking service
    val effectiveLat = if (debugInfo.currentLat != 0.0) debugInfo.currentLat else userLat
    val effectiveLon = if (debugInfo.currentLon != 0.0) debugInfo.currentLon else userLon

    // Calculate actual road driving/bus distance via OSRM when destination or location updates
    LaunchedEffect(selectedDestination.latitude, selectedDestination.longitude, effectiveLat, effectiveLon) {
        if (effectiveLat != null && effectiveLon != null && effectiveLat != 0.0 && effectiveLon != 0.0) {
            isLoadingRoadDistance = true
            val route = OsrmRouting.getRoadRoute(
                startLat = effectiveLat,
                startLon = effectiveLon,
                destLat = selectedDestination.latitude,
                destLon = selectedDestination.longitude
            )
            roadRouteInfo = route
            isLoadingRoadDistance = false
        } else {
            roadRouteInfo = null
        }
    }

    LaunchedEffect(currentDest) {
        if (currentDest != null) {
            selectedDestination = currentDest
            selectedRadius = currentDest.radiusMeters
            if (currentDest.alertZones.isNotEmpty()) {
                alertZones = currentDest.alertZones
            }
            enableEarlyWarning = currentDest.enableEarlyWarning
            enableMissedStopAlert = currentDest.enableMissedStopAlert
        }
    }

    // 1. DEFAULT ALERT RADIUS CUSTOM DIALOG (with Direct Number Typing & m ⇄ km Switcher)
    if (showCustomRadiusDialog) {
        var isKmUnit by remember { mutableStateOf(selectedRadius >= 1000f) }
        var textInput by remember {
            mutableStateOf(
                if (selectedRadius >= 1000f) {
                    val km = selectedRadius / 1000f
                    if (km % 1 == 0f) km.toInt().toString() else String.format("%.1f", km)
                } else {
                    selectedRadius.toInt().toString()
                }
            )
        }

        AlertDialog(
            onDismissRequest = { showCustomRadiusDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = CyanPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.customizeRadiusTitle, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = strings.customizeRadiusDesc,
                        color = TextSecondary,
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Direct Numeric / Decimal Input with clickable Unit Toggle (m ⇄ km)
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { input ->
                            textInput = if (isKmUnit) {
                                input.filter { it.isDigit() || it == '.' }.take(6)
                            } else {
                                input.filter { it.isDigit() }.take(5)
                            }
                        },
                        label = { Text(if (isKmUnit) "Enter distance (km)" else "Enter distance (meters)", color = TextSecondary) },
                        trailingIcon = {
                            Box(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CyanPrimary)
                                    .clickable {
                                        if (isKmUnit) {
                                            val km = textInput.toFloatOrNull() ?: 1f
                                            textInput = (km * 1000f).toInt().toString()
                                            isKmUnit = false
                                        } else {
                                            val m = textInput.toFloatOrNull() ?: 1000f
                                            val km = m / 1000f
                                            textInput = if (km % 1 == 0f) km.toInt().toString() else String.format("%.1f", km)
                                            isKmUnit = true
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (isKmUnit) strings.kilometers else strings.meters,
                                        color = BackgroundDark,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Icon(
                                        imageVector = Icons.Default.SwapVert,
                                        contentDescription = "Toggle unit",
                                        tint = BackgroundDark,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (isKmUnit) KeyboardType.Decimal else KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Quick Step Adjustment Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        if (isKmUnit) {
                            OutlinedButton(
                                onClick = {
                                    val currentVal = textInput.toFloatOrNull() ?: 1f
                                    textInput = (currentVal - 1f).coerceAtLeast(0.1f).let {
                                        if (it % 1 == 0f) it.toInt().toString() else String.format("%.1f", it)
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("-1 km", color = TextPrimary, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val currentVal = textInput.toFloatOrNull() ?: 1f
                                    textInput = (currentVal + 1f).coerceAtMost(50f).let {
                                        if (it % 1 == 0f) it.toInt().toString() else String.format("%.1f", it)
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("+1 km", color = TextPrimary, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val currentVal = textInput.toFloatOrNull() ?: 1f
                                    textInput = (currentVal + 5f).coerceAtMost(50f).let {
                                        if (it % 1 == 0f) it.toInt().toString() else String.format("%.1f", it)
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("+5 km", color = TextPrimary, fontSize = 12.sp)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    val currentVal = textInput.toIntOrNull() ?: 500
                                    textInput = (currentVal - 100).coerceAtLeast(50).toString()
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("-100m", color = TextPrimary, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val currentVal = textInput.toIntOrNull() ?: 500
                                    textInput = (currentVal + 100).coerceAtMost(10000).toString()
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("+100m", color = TextPrimary, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val currentVal = textInput.toIntOrNull() ?: 500
                                    textInput = (currentVal + 500).coerceAtMost(10000).toString()
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("+500m", color = TextPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = textInput.toFloatOrNull() ?: 1f
                        val meters = if (isKmUnit) (parsed * 1000f) else parsed
                        val clamped = meters.coerceIn(50f, 50000f)
                        selectedRadius = clamped
                        selectedDestination = selectedDestination.copy(radiusMeters = clamped)
                        showCustomRadiusDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = BackgroundDark)
                ) {
                    Text(strings.applyRadius, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomRadiusDialog = false }) {
                    Text(strings.cancel, color = TextSecondary)
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // 2. MULTIPLE ALERT ZONES & SAFETY CONFIGURATION DIALOG
    if (showZoneConfigDialog) {
        val zSorted = alertZones.sortedDescending()
        val initOuter = zSorted.getOrNull(0) ?: 2000f
        val initMid = zSorted.getOrNull(1) ?: 500f

        var outerIsKm by remember { mutableStateOf(initOuter >= 1000f) }
        var outerText by remember {
            mutableStateOf(
                if (initOuter >= 1000f) {
                    val km = initOuter / 1000f
                    if (km % 1 == 0f) km.toInt().toString() else String.format("%.1f", km)
                } else initOuter.toInt().toString()
            )
        }

        var midIsKm by remember { mutableStateOf(initMid >= 1000f) }
        var midText by remember {
            mutableStateOf(
                if (initMid >= 1000f) {
                    val km = initMid / 1000f
                    if (km % 1 == 0f) km.toInt().toString() else String.format("%.1f", km)
                } else initMid.toInt().toString()
            )
        }

        var tempEarlyWarning by remember { mutableStateOf(enableEarlyWarning) }
        var tempMissedStop by remember { mutableStateOf(enableMissedStopAlert) }

        AlertDialog(
            onDismissRequest = { showZoneConfigDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = CyanPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.alertZonesTitle, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Set multiple intermediate warnings to alert you before arrival, plus missed-stop detection.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    // Quick Preset Bundles for early warnings
                    Text("Quick Presets:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                outerText = "2"; outerIsKm = true
                                midText = "500"; midIsKm = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("2km & 500m", fontSize = 11.sp, color = CyanPrimary, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                outerText = "1"; outerIsKm = true
                                midText = "300"; midIsKm = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("1km & 300m", fontSize = 11.sp, color = CyanPrimary, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                outerText = "5"; outerIsKm = true
                                midText = "1"; midIsKm = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("5km & 1km", fontSize = 11.sp, color = CyanPrimary, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = BorderDark)

                    // Zone 1: Outer / Early Warning
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("1. Early Warning Zone", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("First heads-up alert & buzz", color = TextSecondary, fontSize = 11.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = outerText,
                                onValueChange = { outerText = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                                modifier = Modifier.width(80.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanPrimary,
                                    unfocusedBorderColor = BorderDark,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CyanPrimary)
                                    .clickable {
                                        if (outerIsKm) {
                                            val km = outerText.toFloatOrNull() ?: 2f
                                            outerText = (km * 1000f).toInt().toString()
                                            outerIsKm = false
                                        } else {
                                            val m = outerText.toFloatOrNull() ?: 2000f
                                            val km = m / 1000f
                                            outerText = if (km % 1 == 0f) km.toInt().toString() else String.format("%.1f", km)
                                            outerIsKm = true
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Text(if (outerIsKm) "km" else "m", color = BackgroundDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    // Zone 2: Middle / Approaching
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("2. Approaching Zone", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Get-ready notification", color = TextSecondary, fontSize = 11.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = midText,
                                onValueChange = { midText = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                                modifier = Modifier.width(80.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanPrimary,
                                    unfocusedBorderColor = BorderDark,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CyanPrimary)
                                    .clickable {
                                        if (midIsKm) {
                                            val km = midText.toFloatOrNull() ?: 0.5f
                                            midText = (km * 1000f).toInt().toString()
                                            midIsKm = false
                                        } else {
                                            val m = midText.toFloatOrNull() ?: 500f
                                            val km = m / 1000f
                                            midText = if (km % 1 == 0f) km.toInt().toString() else String.format("%.1f", km)
                                            midIsKm = true
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Text(if (midIsKm) "km" else "m", color = BackgroundDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    HorizontalDivider(color = BorderDark)

                    // Safety Toggles
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.earlyWarningTitle, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(strings.earlyWarningDesc, color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = tempEarlyWarning,
                            onCheckedChange = { tempEarlyWarning = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = BackgroundDark,
                                checkedTrackColor = CyanPrimary,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = SurfaceCard
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.missedStopTitle, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(strings.missedStopDesc, color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = tempMissedStop,
                            onCheckedChange = { tempMissedStop = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = BackgroundDark,
                                checkedTrackColor = CyanPrimary,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = SurfaceCard
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedOuter = (outerText.toFloatOrNull() ?: 2f).let { if (outerIsKm) it * 1000f else it }
                        val parsedMid = (midText.toFloatOrNull() ?: 500f).let { if (midIsKm) it * 1000f else it }

                        val newZones = listOf(parsedOuter, parsedMid).distinct().sortedDescending()
                        alertZones = newZones
                        enableEarlyWarning = tempEarlyWarning
                        enableMissedStopAlert = tempMissedStop

                        selectedDestination = selectedDestination.copy(
                            alertZones = newZones,
                            enableEarlyWarning = tempEarlyWarning,
                            enableMissedStopAlert = tempMissedStop
                        )
                        showZoneConfigDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = BackgroundDark)
                ) {
                    Text(strings.applyRadius, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showZoneConfigDialog = false }) {
                    Text(strings.cancel, color = TextSecondary)
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(20.dp)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_nearme_logo),
                            contentDescription = "NearMe Logo",
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Near",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 22.sp
                        )
                        Text(
                            text = "Me",
                            fontWeight = FontWeight.Black,
                            color = CyanPrimary,
                            fontSize = 22.sp
                        )
                    }
                },
                actions = {
                    // Language Toggle Pill (EN / മ)
                    Row(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceCard)
                            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .clickable { currentLang = AppLanguage.EN }
                                .background(if (currentLang == AppLanguage.EN) CyanPrimary else Color.Transparent)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "EN",
                                color = if (currentLang == AppLanguage.EN) BackgroundDark else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clickable { currentLang = AppLanguage.ML }
                                .background(if (currentLang == AppLanguage.ML) CyanPrimary else Color.Transparent)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "മ",
                                color = if (currentLang == AppLanguage.ML) BackgroundDark else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Journey Status Badge
                    val (badgeText, badgeColor) = when (journeyState) {
                        JourneyState.IDLE -> strings.statusIdle to TextSecondary
                        JourneyState.TRACKING -> strings.statusTracking to CyanPrimary
                        JourneyState.ARRIVED -> strings.statusArrived to AlarmRed
                        JourneyState.MISSED_STOP -> strings.statusMissedStop to Color(0xFFFF334B)
                    }
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(badgeColor.copy(alpha = 0.2f))
                            .border(1.dp, badgeColor, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        containerColor = BackgroundDark
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search Input & Suggestions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { query ->
                        searchQuery = query
                        if (query.trim().length >= 2) {
                            isSearching = true
                            coroutineScope.launch {
                                searchResults = NominatimSearch.searchPlaces(query, context)
                                isSearching = false
                            }
                        } else {
                            searchResults = emptyList()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(strings.searchPlaceholder, color = TextSecondary) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = CyanPrimary) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                searchQuery = ""
                                searchResults = emptyList()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = BorderDark,
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(14.dp)
                )

                // Search Results Dropdown List
                if (searchResults.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(searchResults) { result ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDestination = result.copy(
                                                radiusMeters = selectedRadius,
                                                alertZones = alertZones,
                                                enableEarlyWarning = enableEarlyWarning,
                                                enableMissedStopAlert = enableMissedStopAlert
                                            )
                                            searchQuery = ""
                                            searchResults = emptyList()
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Text(result.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(result.address, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                                }
                                HorizontalDivider(color = BorderDark.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }

            // Interactive OpenStreetMap (takes remaining space)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                OsmMapView(
                    modifier = Modifier.fillMaxSize(),
                    destination = selectedDestination,
                    currentLat = effectiveLat,
                    currentLon = effectiveLon,
                    routePoints = roadRouteInfo?.waypoints ?: emptyList(),
                    recenterTrigger = recenterTrigger,
                    onMapTapped = { geoPoint ->
                        selectedDestination = Destination(
                            name = strings.selectedPin,
                            address = String.format("%.4f, %.4f", geoPoint.latitude, geoPoint.longitude),
                            latitude = geoPoint.latitude,
                            longitude = geoPoint.longitude,
                            radiusMeters = selectedRadius,
                            alertZones = alertZones,
                            enableEarlyWarning = enableEarlyWarning,
                            enableMissedStopAlert = enableMissedStopAlert
                        )
                    }
                )

                // Map helper overlay hint
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundDark.copy(alpha = 0.75f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(strings.tapMapHint, color = TextSecondary, fontSize = 11.sp)
                }

                // Floating "Recenter on My Location" Button
                FloatingActionButton(
                    onClick = { recenterTrigger++ },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .size(48.dp),
                    containerColor = SurfaceDark,
                    contentColor = CyanPrimary,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Recenter on My Location",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Bottom Panel: Configuration & Actions
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Selected Destination Info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = "Place",
                            tint = CyanPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedDestination.name,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = selectedDestination.address,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1
                            )

                            // Road distance calculated by bus/driving route
                            if (roadRouteInfo != null) {
                                val roadDist = roadRouteInfo!!.roadDistanceMeters
                                val roadDistText = if (roadDist >= 1000f) {
                                    String.format("%.1f %s", roadDist / 1000f, strings.kilometers)
                                } else {
                                    "${roadDist.toInt()} ${strings.meters}"
                                }

                                val minutes = (roadRouteInfo!!.durationSeconds / 60f).toInt()
                                val timeText = if (minutes >= 60) {
                                    val hrs = minutes / 60
                                    val mins = minutes % 60
                                    "${hrs}h ${mins}m"
                                } else {
                                    "${minutes.coerceAtLeast(1)} min"
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "🛣️ $roadDistText ${if (currentLang == AppLanguage.ML) "റോഡ് ദൂരം" else "by road"}",
                                        color = CyanPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "⏱️ ~$timeText",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            } else if (isLoadingRoadDistance) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = if (currentLang == AppLanguage.ML) "റോഡ് റൂട്ട് പരിശോധിക്കുന്നു…" else "Calculating road route…",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            } else if (effectiveLat != null && effectiveLon != null && effectiveLat != 0.0) {
                                val r = FloatArray(1)
                                android.location.Location.distanceBetween(
                                    effectiveLat,
                                    effectiveLon,
                                    selectedDestination.latitude,
                                    selectedDestination.longitude,
                                    r
                                )
                                val distStr = if (r[0] >= 1000f) {
                                    String.format("%.1f %s", r[0] / 1000f, strings.kilometers)
                                } else {
                                    "${r[0].toInt()} ${strings.meters}"
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "📍 $distStr",
                                    color = CyanPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 1. DEFAULT ALERT RADIUS SELECTOR (Unchanged, clean & prominent as before)
                    if (journeyState == JourneyState.IDLE) {
                        Text(
                            text = "${strings.alertRadius}: ${if (selectedRadius >= 1000) String.format("%.1f %s", selectedRadius / 1000f, strings.kilometers) else "${selectedRadius.toInt()} ${strings.meters}"}",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val presetRadii = listOf(200f, 500f, 1000f, 2000f)
                            presetRadii.forEach { r ->
                                val isSelected = selectedRadius == r
                                Button(
                                    onClick = {
                                        selectedRadius = r
                                        selectedDestination = selectedDestination.copy(radiusMeters = r)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) CyanPrimary else SurfaceCard,
                                        contentColor = if (isSelected) BackgroundDark else TextPrimary
                                    ),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (r >= 1000) "${(r / 1000).toInt()}${strings.kilometers}" else "${r.toInt()}${strings.meters}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // The ONE and ONLY Custom Button with m ⇄ km typing
                            val isCustomSelected = presetRadii.none { it == selectedRadius }
                            Button(
                                onClick = { showCustomRadiusDialog = true },
                                modifier = Modifier.weight(1.3f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isCustomSelected) CyanPrimary else SurfaceCard,
                                    contentColor = if (isCustomSelected) BackgroundDark else CyanPrimary
                                ),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isCustomSelected) {
                                        if (selectedRadius >= 1000f) {
                                            val km = selectedRadius / 1000f
                                            if (km % 1 == 0f) "${km.toInt()}${strings.kilometers} ⚙️" else String.format("%.1f%s ⚙️", km, strings.kilometers)
                                        } else {
                                            "${selectedRadius.toInt()}${strings.meters} ⚙️"
                                        }
                                    } else {
                                        strings.customChip
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 2. ADDITIONAL MULTIPLE ALERTS & SAFETY (Early warning & Missed-stop notifications)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strings.alertZonesTitle,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = { showZoneConfigDialog = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Extra Alerts ⚙️", color = CyanPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Preview Bar for Early Warning & Missed-Stop
                        val outerVal = alertZones.getOrNull(0) ?: 2000f
                        val midVal = alertZones.getOrNull(1) ?: 500f
                        val outerStr = if (outerVal >= 1000f) "${(outerVal / 1000f).toInt()}${strings.kilometers}" else "${outerVal.toInt()}${strings.meters}"
                        val midStr = if (midVal >= 1000f) "${(midVal / 1000f).toInt()}${strings.kilometers}" else "${midVal.toInt()}${strings.meters}"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceCard)
                                .clickable { showZoneConfigDialog = true }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (enableEarlyWarning) "🔔 $outerStr Early" else "🔔 Early Off",
                                    color = if (enableEarlyWarning) CyanPrimary else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("•", color = TextSecondary, fontSize = 11.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "$midStr Approaching",
                                    color = CyanPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (enableMissedStopAlert) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFFFF334B).copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "🛡️ Missed-Stop",
                                        color = Color(0xFFFF6677),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else {
                        // Live Tracking Info Card with Stage Banner
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceCard)
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(strings.distanceRemaining, color = TextSecondary, fontSize = 12.sp)
                                    val distanceText = if (roadRouteInfo != null) {
                                        val roadDist = roadRouteInfo!!.roadDistanceMeters
                                        if (roadDist >= 1000f) String.format("%.1f %s (by road)", roadDist / 1000f, strings.kilometers) else "${roadDist.toInt()} ${strings.meters} (by road)"
                                    } else if (debugInfo.distanceRemainingMeters >= 1000) {
                                        String.format("%.1f %s", debugInfo.distanceRemainingMeters / 1000f, strings.kilometers)
                                    } else {
                                        "${debugInfo.distanceRemainingMeters.toInt()} ${strings.meters}"
                                    }
                                    Text(
                                        text = distanceText,
                                        color = if (journeyState == JourneyState.MISSED_STOP) Color(0xFFFF334B) else CyanPrimary,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 20.sp
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (journeyState == JourneyState.MISSED_STOP) Color(0xFFFF334B).copy(alpha = 0.2f) else CyanPrimary.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (journeyState == JourneyState.MISSED_STOP) Icons.Default.Warning else Icons.Default.Navigation,
                                        contentDescription = "Tracking",
                                        tint = if (journeyState == JourneyState.MISSED_STOP) Color(0xFFFF334B) else CyanPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Dynamic Stage Banner
                            if (journeyState == JourneyState.MISSED_STOP || debugInfo.currentStage == AlertStage.MISSED_STOP) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFFF334B).copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "⚠️ Missed-Stop Alert! You passed your destination.",
                                        color = Color(0xFFFF334B),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            } else if (debugInfo.currentStage == AlertStage.EARLY_WARNING) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CyanPrimary.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "⚠️ In Early Warning Zone — Prepare to alight!",
                                        color = CyanPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            } else if (debugInfo.currentStage == AlertStage.APPROACHING) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CyanPrimary.copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "🔔 Approaching Destination Stop (~${selectedRadius.toInt()}m left)",
                                        color = CyanPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Primary Action Button
                    if (journeyState == JourneyState.IDLE) {
                        Button(
                            onClick = {
                                onStartAlarm(
                                    selectedDestination.copy(
                                        radiusMeters = selectedRadius,
                                        alertZones = alertZones,
                                        enableEarlyWarning = enableEarlyWarning,
                                        enableMissedStopAlert = enableMissedStopAlert
                                    ),
                                    currentLang.name.lowercase()
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPrimary,
                                contentColor = BackgroundDark
                            )
                        ) {
                            Icon(Icons.Default.Alarm, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.startAlarm,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp
                            )
                        }
                    } else {
                        Button(
                            onClick = onCancelAlarm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AlarmRed,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.cancelAlarm,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }

                    // Mobile Debugging Toolbar Toggle
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDebugPanel = !showDebugPanel }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = "Debug",
                            tint = TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showDebugPanel) "Hide Mobile Debug Panel" else "Mobile Debug Options",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    // On-Screen Mobile Debug Info Panel
                    if (showDebugPanel) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BackgroundDark)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "Mobile Debug Info",
                                color = CyanPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text("GPS Provider: ${debugInfo.provider}", color = TextSecondary, fontSize = 11.sp)
                            Text("Accuracy: ±${debugInfo.accuracyMeters.toInt()}m", color = TextSecondary, fontSize = 11.sp)
                            Text("Current Lat/Lon: ${String.format("%.4f, %.4f", effectiveLat ?: 0.0, effectiveLon ?: 0.0)}", color = TextSecondary, fontSize = 11.sp)
                            Text("Stage: ${debugInfo.currentStage}", color = TextSecondary, fontSize = 11.sp)
                            Text("Min Dist Observed: ${if (debugInfo.minDistanceMeters < Float.MAX_VALUE) "${debugInfo.minDistanceMeters.toInt()}m" else "N/A"}", color = TextSecondary, fontSize = 11.sp)
                            Text("Simulated: ${debugInfo.isSimulated}", color = TextSecondary, fontSize = 11.sp)

                            Spacer(modifier = Modifier.height(8.dp))

                            // Normal Simulation
                            Button(
                                onClick = onSimulateJourney,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceCard,
                                    contentColor = CyanPrimary
                                )
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Simulate Journey (Desk Test)", fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Missed-Stop Simulation
                            Button(
                                onClick = onSimulateMissedStop,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceCard,
                                    contentColor = Color(0xFFFF334B)
                                )
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Missed-Stop Simulation (Desk Test)", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
