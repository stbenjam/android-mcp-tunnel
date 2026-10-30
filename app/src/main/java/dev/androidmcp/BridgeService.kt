package dev.androidmcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.androidmcp.core.McpServer
import dev.androidmcp.core.TunnelClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.Executors

class BridgeService : Service() {
    companion object {
        @Volatile var running = false
            private set
        @Volatile var status = "Disconnected"
            private set
        const val CHANNEL = "phone_link_connection"
        @Volatile private var activeTunnel: TunnelClient? = null
        fun diagnostics() = (activeTunnel?.diagnostics() ?: dev.androidmcp.core.obj("running" to false))
            .put("status", status)
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var tunnel: TunnelClient? = null
    private val watchdog = object : Runnable {
        override fun run() {
            if (PhoneAccessibilityService.instance == null) {
                status = "Accessibility disconnected"; stopSelf()
            } else handler.postDelayed(this, 1000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone connection", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { status = "Disconnected"; stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        status = "Connecting"
        startForeground(1, notification(status))
        val state = AppState(this)
        if (PhoneAccessibilityService.instance == null) {
            status = "Enable Accessibility first"; stopSelf(); return START_NOT_STICKY
        }
        val key = try { state.key() } catch (_: Exception) {
            status = "Saved credential unavailable. Enter it again."; stopSelf(); return START_NOT_STICKY
        }
        if (key.isBlank() || state.tunnelId.isBlank()) {
            status = "Configure your tunnel first"; stopSelf(); return START_NOT_STICKY
        }
        running = true
        tunnel = TunnelClient("https://api.openai.com/".toHttpUrl(), state.tunnelId, key, state.instanceId,
            McpServer(PhoneTools(this, state).all()), { message ->
                synchronized(this) {
                    if (message != status) {
                        status = message
                        getSystemService(NotificationManager::class.java).notify(1, notification(message))
                    }
                }
            }, diagnostic = { android.util.Log.i("PhoneLinkTunnel", it) })
        activeTunnel = tunnel
        handler.post(watchdog)
        executor.execute {
            try { tunnel?.run() } catch (_: Exception) { status = "Connection stopped. Check configuration and reconnect." }
            finally { handler.post { stopSelf() } }
        }
        return START_NOT_STICKY
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_link_status)
            .setContentTitle("Phone Link").setContentText(message).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
    }
    override fun onDestroy() {
        running = false
        handler.removeCallbacks(watchdog)
        tunnel?.stop()
        if (activeTunnel === tunnel) activeTunnel = null
        executor.shutdownNow()
        if (status == "Connected" || status == "Connecting" || status.startsWith("Reconnecting")) status = "Disconnected"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
