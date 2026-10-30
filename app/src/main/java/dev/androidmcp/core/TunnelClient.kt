package dev.androidmcp.core

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

/** One poller, independent execution and upload pools, and a single ordered UI lane. */
class TunnelClient(
    private val baseUrl: HttpUrl,
    private val tunnelId: String,
    private val apiKey: String,
    private val instanceId: String,
    private val server: McpServer,
    private val status: (String) -> Unit,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val diagnostic: (String) -> Unit = {},
    private val workerCount: Int = 8,
    private val maxInFlight: Int = 32,
    private val uploadWorkers: Int = 8,
    private val responseAttemptMillis: Long = 10_000,
) {
    init { require(workerCount > 0 && maxInFlight > 0 && uploadWorkers > 0 && responseAttemptMillis > 0) }
    @Volatile private var stopped = false
    @Volatile private var pollThread: Thread? = null
    private val slots = Semaphore(maxInFlight)
    private val workers = pool(workerCount, "tools")
    private val uiWorker = pool(1, "screen")
    private val uploads = pool(uploadWorkers, "replies")
    private val receivedCount = AtomicLong()
    private val deliveredCount = AtomicLong()
    private val expiredCount = AtomicLong()
    private val retryCount = AtomicLong()
    private val errorCount = AtomicLong()
    private val lastPoll = AtomicLong()
    private val lastDelivery = AtomicLong()
    private val queueMillis = AtomicLong()
    private val executionMillis = AtomicLong()
    private val deliveryMillis = AtomicLong()

    private fun pool(count: Int, label: String) = ThreadPoolExecutor(count, count, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxInFlight), { task -> Thread(task, "PhoneLink-$label").apply { isDaemon = true } })

    fun stop() {
        stopped = true
        workers.shutdownNow(); uiWorker.shutdownNow(); uploads.shutdownNow()
        http.dispatcher.cancelAll()
        pollThread?.takeIf { it !== Thread.currentThread() }?.interrupt()
    }

    /** Numeric metadata only: no arguments, request IDs, tokens, or response contents. */
    fun diagnostics(): JSONObject {
        fun age(value: AtomicLong): Long? = value.get().takeIf { it != 0L }?.let { millis(System.nanoTime() - it) }
        return obj("running" to !stopped, "parallel_workers" to workerCount, "upload_workers" to uploadWorkers,
            "capacity" to maxInFlight, "active_tools" to workers.activeCount, "active_screen" to uiWorker.activeCount,
            "queued_tools" to workers.queue.size + uiWorker.queue.size, "active_uploads" to uploads.activeCount,
            "queued_uploads" to uploads.queue.size, "received" to receivedCount.get(), "delivered" to deliveredCount.get(),
            "expired" to expiredCount.get(), "retries" to retryCount.get(), "errors" to errorCount.get(),
            "last_poll_age_ms" to age(lastPoll), "last_delivery_age_ms" to age(lastDelivery),
            "last_queue_ms" to queueMillis.get(), "last_execution_ms" to executionMillis.get(),
            "last_delivery_ms" to deliveryMillis.get())
    }

    private fun event(name: String, elapsed: Long = 0, code: Int = 0) {
        // Only internal constant names and numbers are ever passed here.
        runCatching { diagnostic("$name elapsed_ms=$elapsed http=$code") }
    }
    private fun millis(nanos: Long) = TimeUnit.NANOSECONDS.toMillis(nanos).coerceAtLeast(0)
    private fun remaining(command: JSONObject, received: Long): Long =
        (TunnelProtocol.timeoutNanos(command.opt("response_timeout")) ?: TimeUnit.SECONDS.toNanos(120)) -
            (System.nanoTime() - received)
    private fun expired() { expiredCount.incrementAndGet(); event("expired") }
    private fun url(endpoint: String) = baseUrl.newBuilder().addPathSegments("v1/tunnels")
        .addPathSegment(tunnelId).addPathSegment(endpoint)
    private fun request(url: HttpUrl): Request.Builder = Request.Builder().url(url)
        .header("Authorization", "Bearer $apiKey")
        .header("X-Tunnel-Client-Name", "android-mcp-tunnel")
        .header("X-Tunnel-Client-Version", "0.3.1")
        .header("X-Tunnel-Client-Instance-Id", instanceId)
        .header("X-Tunnel-MCP-Server-Info", "{\"version\":1,\"channels\":[{\"name\":\"main\",\"proc_affinity\":true}]}")

    fun run() {
        pollThread = Thread.currentThread()
        var failures = 0
        try {
            while (!stopped && !Thread.currentThread().isInterrupted) {
                try {
                    // Backpressure before fetching more work. Every fetched command is retained until
                    // admitted or expired, including a batch exceeding the requested limit.
                    if (!slots.tryAcquire(250, TimeUnit.MILLISECONDS)) continue
                    var commands: JSONArray? = null
                    var received = 0L
                    var retryDelay: Long? = null
                    val start = System.nanoTime()
                    try {
                        val limit = min(25, slots.availablePermits() + 1)
                        val req = request(url("poll").addQueryParameter("limit", limit.toString())
                            .addQueryParameter("timeout_ms", "15000").build()).build()
                        http.newCall(req).execute().use { response ->
                            received = System.nanoTime()
                            when (response.code) {
                                200, 204 -> {
                                    if (response.code == 200) {
                                        val body = response.body ?: throw IOException("Empty poll")
                                        if (body.contentLength() > 2_000_000) throw IOException("Poll exceeds limit")
                                        val source = body.source()
                                        source.request(2_000_001)
                                        if (source.buffer.size > 2_000_000) throw IOException("Poll exceeds limit")
                                        commands = JSONObject(source.readUtf8()).getJSONArray("commands")
                                    }
                                    lastPoll.set(System.nanoTime())
                                    status("Connected"); failures = 0
                                    event("poll_ok", millis(System.nanoTime() - start), response.code)
                                }
                                401, 403 -> { status("Tunnel credential or permission rejected (${response.code})"); return }
                                408, 429, in 500..599 -> {
                                    errorCount.incrementAndGet(); event("poll_retry", millis(System.nanoTime() - start), response.code)
                                    status("Reconnecting · server unavailable")
                                    retryDelay = delay(++failures, response.header("Retry-After"))
                                }
                                else -> { status("Tunnel configuration error (${response.code})"); return }
                            }
                        }
                    } finally { slots.release() }
                    // Close the poll body before executing work, uploading, or waiting to retry.
                    commands?.let { batch ->
                        for (i in 0 until batch.length()) {
                            if (stopped) break
                            try { enqueue(batch.getJSONObject(i), received) }
                            catch (_: org.json.JSONException) { errorCount.incrementAndGet(); event("invalid_command") }
                        }
                    }
                    retryDelay?.let(pause)
                } catch (_: IOException) {
                    if (stopped) return
                    errorCount.incrementAndGet(); event("poll_network_error")
                    status("Reconnecting · check internet access")
                    pause(delay(++failures, null))
                } catch (_: org.json.JSONException) {
                    errorCount.incrementAndGet(); event("invalid_poll")
                    status("Invalid tunnel response")
                    pause(delay(++failures, null))
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally { stop(); pollThread = null }
    }

    private fun enqueue(command: JSONObject, received: Long) {
        receivedCount.incrementAndGet()
        while (!stopped) {
            val budget = remaining(command, received)
            if (budget <= 0) { expired(); return }
            if (slots.tryAcquire(min(budget, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS)) break
        }
        if (stopped) return
        val serial = command.optJSONObject("jsonrpc")?.let(server::requiresSerialExecution) ?: false
        try {
            (if (serial) uiWorker else workers).execute {
                var handedOff = false
                try {
                    val delivery = prepare(command, received) ?: return@execute
                    if (stopped) return@execute
                    uploads.execute {
                        try { deliver(delivery) }
                        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        finally { slots.release() }
                    }
                    handedOff = true
                } catch (_: org.json.JSONException) {
                    errorCount.incrementAndGet(); event("invalid_command")
                } catch (_: RejectedExecutionException) {
                    // Only shutdown can reject: the global permit bounds every queue.
                } finally { if (!handedOff) slots.release() }
            }
        } catch (_: RejectedExecutionException) { slots.release() }
    }

    private data class Delivery(val command: JSONObject, val received: Long, val token: String, val encoded: String)
    private fun prepare(command: JSONObject, received: Long): Delivery? {
        if (stopped) return null
        if (remaining(command, received) <= 0) { expired(); return null }
        val token = command.getString("shard_token")
        command.getString("request_id") // Validate correlation before any phone action.
        if (command.optString("channel", "main") != "main") return null
        val start = System.nanoTime()
        queueMillis.set(millis(start - received))
        val result = TunnelProtocol.response(command, server) ?: return null
        executionMillis.set(millis(System.nanoTime() - start))
        event("executed", executionMillis.get())
        return Delivery(command, received, token, result.toString())
    }

    internal fun process(command: JSONObject, received: Long) { prepare(command, received)?.let(::deliver) }

    private fun deliver(delivery: Delivery) {
        val encoded = delivery.encoded.toRequestBody("application/json".toMediaType())
        var failures = 0
        val started = System.nanoTime()
        fun remaining() = remaining(delivery.command, delivery.received)
        while (!stopped && remaining() > 0) {
            val req = request(url("response").build()).header("X-Tunnel-Shard-Token", delivery.token).post(encoded).build()
            var waitMillis: Long
            try {
                val call = http.newCall(req)
                call.timeout().timeout(min(remaining().coerceAtLeast(1), TimeUnit.MILLISECONDS.toNanos(responseAttemptMillis)), TimeUnit.NANOSECONDS)
                if (stopped) return
                call.execute().use { response ->
                    when (response.code) {
                        in 200..299 -> {
                            deliveredCount.incrementAndGet(); lastDelivery.set(System.nanoTime())
                            deliveryMillis.set(millis(System.nanoTime() - started))
                            event("delivered", deliveryMillis.get(), response.code); return
                        }
                        404 -> { expired(); return } // No longer awaiting this result; never replay the tool.
                        401, 403 -> { status("Tunnel credential or permission rejected (${response.code})"); stop(); return }
                        408, 429, 502, 503, 504 -> {
                            event("delivery_retry", millis(System.nanoTime() - started), response.code)
                            waitMillis = delay(++failures, response.header("Retry-After"))
                        }
                        else -> { errorCount.incrementAndGet(); event("delivery_rejected", code = response.code); return }
                    }
                }
            } catch (_: IOException) {
                if (stopped) return
                event("delivery_network_error", millis(System.nanoTime() - started))
                waitMillis = delay(++failures, null)
            }
            retryCount.incrementAndGet()
            // The result is frozen before delivery. Retrying it never repeats the action.
            if (TimeUnit.MILLISECONDS.toNanos(waitMillis) >= remaining()) { expired(); return }
            pause(waitMillis)
        }
        if (!stopped) expired()
    }

    private fun delay(attempt: Int, retryAfter: String?): Long {
        val seconds = retryAfter?.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(300)?.times(1000)
            ?: retryAfter?.let {
                runCatching { (ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
                    System.currentTimeMillis()).coerceIn(0, 300_000) }.getOrNull()
            } ?: 0L
        return maxOf(min(5_000L, 200L * (1L shl min(attempt, 5))) + Random.nextLong(100), seconds)
    }
}
