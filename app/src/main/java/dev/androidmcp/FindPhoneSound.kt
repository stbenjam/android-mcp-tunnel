package dev.androidmcp

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import dev.androidmcp.core.ToolFailure
import dev.androidmcp.core.obj

/** Main-thread owned, timed alarm playback with recoverable volume restoration. */
object FindPhoneSound {
    private var sound: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopTask: Runnable? = null
    @Volatile var ringing = false
        private set

    fun recoverVolume(context: Context) {
        if (ringing) return
        val prefs = context.getSharedPreferences("finder", Context.MODE_PRIVATE)
        if (!prefs.contains("previous_volume")) return
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == prefs.getInt("forced_volume", -1))
            audio.setStreamVolume(AudioManager.STREAM_ALARM, prefs.getInt("previous_volume", 0), 0)
        prefs.edit().clear().commit()
    }
    fun start(context: Context, seconds: Int) = run {
        stop(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val filter = manager.currentInterruptionFilter
        val alarmsBlocked = filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY &&
                manager.consolidatedNotificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS == 0)
        if (alarmsBlocked) throw ToolFailure("Do Not Disturb currently blocks alarms. Allow alarms in that mode before ringing this phone.")
        BridgeService.enablePlayback()
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val prefs = context.getSharedPreferences("finder", Context.MODE_PRIVATE)
        check(prefs.edit().putInt("previous_volume", audio.getStreamVolume(AudioManager.STREAM_ALARM))
            .putInt("forced_volume", max).commit())
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: throw ToolFailure("No alarm sound is available.")
            ringtone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            ringtone.isLooping = true; ringtone.volume = 1f
            sound = ringtone
            audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            ringtone.play(); ringing = true
            val stop = PendingIntent.getService(context, 2, Intent(context, BridgeService::class.java).setAction("STOP_RINGING"), PendingIntent.FLAG_IMMUTABLE)
            manager.notify(2, Notification.Builder(context, BridgeService.CHANNEL).setSmallIcon(R.drawable.ic_link_status)
                .setContentTitle("Find phone").setContentText("Ringing for $seconds seconds")
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(stop)
                .addAction(Notification.Action.Builder(null, "Stop sound", stop).build()).build())
            stopTask = Runnable { stop(context) }.also { handler.postDelayed(it, seconds * 1000L) }
            obj("status" to "completed", "message" to "Phone is ringing.", "duration_seconds" to seconds, "alarm_volume" to max)
        } catch (e: Exception) { stop(context); throw e }
    }
    fun stop(context: Context) {
        stopTask?.let(handler::removeCallbacks); stopTask = null
        sound?.stop(); sound = null; ringing = false
        recoverVolume(context)
        context.getSystemService(NotificationManager::class.java).cancel(2)
    }
}
