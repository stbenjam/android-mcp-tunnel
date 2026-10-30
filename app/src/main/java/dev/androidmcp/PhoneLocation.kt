package dev.androidmcp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import dev.androidmcp.core.ToolFailure
import dev.androidmcp.core.obj
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PhoneLocation(private val context: Context) {
    @android.annotation.SuppressLint("MissingPermission")
    fun current(timeoutSeconds: Int) = run {
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) throw ToolFailure("Turn on Location in Android settings first.")
        val providers = manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
        fun age(location: Location) = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000_000.0
        val recent = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { age(it) <= 30 }.maxByOrNull { it.elapsedRealtimeNanos }
        var source = "recent_fix"
        val fix = recent ?: run {
            source = "current_fix"
            val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in providers }
                ?: throw ToolFailure("No enabled location provider is available.")
            val done = CountDownLatch(1)
            val location = AtomicReference<Location?>()
            val cancel = CancellationSignal()
            try {
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { location.set(it); done.countDown() }
                if (!done.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)) throw ToolFailure("No location fix arrived in time. Try again with a clearer view of the sky or better network coverage.")
                location.get() ?: throw ToolFailure("Android could not obtain a current location.")
            } finally { cancel.cancel() }
        }
        if (age(fix) > 30) throw ToolFailure("Android returned an old location fix. Try again for a current location.")
        obj("latitude" to fix.latitude, "longitude" to fix.longitude, "accuracy_m" to fix.accuracy,
            "age_seconds" to age(fix), "timestamp" to Instant.ofEpochMilli(fix.time).toString(),
            "provider" to fix.provider, "source" to source, "precise_permission" to
                (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED))
    }
}
