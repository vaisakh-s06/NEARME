package com.thealarm.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thealarm.app.model.AppLanguage
import com.thealarm.app.model.EnglishStrings
import com.thealarm.app.model.MalayalamStrings
import com.thealarm.app.service.AlarmService
import com.thealarm.app.ui.theme.AlarmRed
import com.thealarm.app.ui.theme.BackgroundDark
import com.thealarm.app.ui.theme.TheAlarmTheme

class ArrivalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure this screen turns on display and overlays lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val destName = intent.getStringExtra(AlarmService.EXTRA_DEST_NAME) ?: "Your Destination"
        val langStr = intent.getStringExtra(AlarmService.EXTRA_LANG) ?: "en"
        val isMissedStop = intent.getBooleanExtra(AlarmService.EXTRA_IS_MISSED_STOP, false)
        val missedDistMeters = intent.getFloatExtra(AlarmService.EXTRA_MISSED_DIST, 0f)
        val strings = if (langStr == "ml") MalayalamStrings else EnglishStrings

        val distText = if (missedDistMeters >= 1000f) {
            String.format("%.1f %s", missedDistMeters / 1000f, strings.kilometers)
        } else {
            "${missedDistMeters.toInt()} ${strings.meters}"
        }

        val titleText = if (isMissedStop) strings.missedStopAlertTitle else strings.arrivedTitle
        val subtitleText = if (isMissedStop) {
            String.format(strings.missedStopAlertText, destName, distText)
        } else {
            "${strings.arrivedNear} $destName"
        }

        setContent {
            TheAlarmTheme {
                ArrivalAlertScreen(
                    destName = destName,
                    arrivedTitle = titleText,
                    arrivedNear = subtitleText,
                    isMissedStop = isMissedStop,
                    stopAlarmText = strings.stopAlarm,
                    onStopAlarm = {
                        val stopIntent = Intent(this, AlarmService::class.java).apply {
                            action = AlarmService.ACTION_STOP_ALARM
                        }
                        startService(stopIntent)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun ArrivalAlertScreen(
    destName: String,
    arrivedTitle: String,
    arrivedNear: String,
    isMissedStop: Boolean = false,
    stopAlarmText: String,
    onStopAlarm: () -> Unit
) {
    val themeRed = if (isMissedStop) Color(0xFFFF334B) else AlarmRed

    // Pulsing animation for the alarm icon
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Pulsing Alarm Icon
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(themeRed.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isMissedStop) Icons.Default.Warning else Icons.Default.Alarm,
                    contentDescription = "Alert Ringing",
                    tint = themeRed,
                    modifier = Modifier.size(72.dp)
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = arrivedTitle,
                color = themeRed,
                fontWeight = FontWeight.Black,
                fontSize = if (isMissedStop) 26.sp else 30.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = arrivedNear,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(60.dp))

            // Big Stop Alarm Button
            Button(
                onClick = onStopAlarm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = themeRed,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stopAlarmText,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp
                )
            }
        }
    }
}
