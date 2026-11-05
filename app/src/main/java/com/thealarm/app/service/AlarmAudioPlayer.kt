package com.thealarm.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class AlarmAudioPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var isPlaying = false

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Starts ringing the phone's basic default alarm tone continuously with vibration.
     */
    fun startAlarm() {
        if (isPlaying) return
        isPlaying = true

        try {
            var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // Ensure alarm stream volume is loud enough
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let { am ->
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val currentVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
                if (currentVol < (maxVol * 0.7f)) {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, (maxVol * 0.85f).toInt(), 0)
                }
            }

            var mediaSuccess = false
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, alarmUri!!)
                    setAudioAttributes(audioAttributes)
                    isLooping = true
                    prepare()
                    start()
                }
                mediaSuccess = true
                Log.d("TheAlarm", "MediaPlayer started playing default alarm sound")
            } catch (e: Exception) {
                Log.w("TheAlarm", "MediaPlayer failed, attempting fallback Ringtone: ${e.message}")
            }

            // Fallback to RingtoneManager if MediaPlayer had issues
            if (!mediaSuccess && alarmUri != null) {
                try {
                    val ringtone = RingtoneManager.getRingtone(context, alarmUri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ringtone?.isLooping = true
                    }
                    ringtone?.audioAttributes = audioAttributes
                    ringtone?.play()
                    fallbackRingtone = ringtone
                    Log.d("TheAlarm", "Fallback Ringtone started playing successfully")
                } catch (e: Exception) {
                    Log.e("TheAlarm", "Fallback Ringtone also failed", e)
                }
            }

        } catch (e: Exception) {
            Log.e("TheAlarm", "Error playing default alarm sound", e)
        }

        // Start repeating vibration pattern: wait 0ms, vibrate 800ms, pause 400ms...
        try {
            val pattern = longArrayOf(0, 800, 400, 800, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e("TheAlarm", "Error starting vibration", e)
        }
    }

    /**
     * Stops the alarm sound and vibration immediately.
     */
    fun stopAlarm() {
        if (!isPlaying) return
        isPlaying = false

        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("TheAlarm", "Error stopping MediaPlayer", e)
        }

        try {
            fallbackRingtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
            fallbackRingtone = null
        } catch (e: Exception) {
            Log.e("TheAlarm", "Error stopping Ringtone", e)
        }

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e("TheAlarm", "Error stopping vibration", e)
        }

        Log.d("TheAlarm", "Alarm stopped.")
    }

    fun isRinging(): Boolean = isPlaying
}
