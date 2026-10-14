package home.olvii

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Main-thread signaling owner with bounded retries and generation-checked callbacks. */
class LocalSignaling(
    // Android ownership and connection settings.
    context: Context,
    private val handler: Handler,
    private val settings: HomeSettings,
    // All callbacks are delivered on the supplied main-thread handler.
    private val event: (JSONObject) -> Unit,
    private val changed: (Boolean, String) -> Unit,
    private val lost: () -> Unit,
    private val rejected: () -> Unit,
) {
    // One HTTP client and one network observer per availability session.
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val networks = context.getSystemService(ConnectivityManager::class.java)
    private val retry = RetryPolicy()
    // A generation invalidates asynchronous callbacks from replaced sockets.
    private var socket: WebSocket? = null
    private var generation = 0
    private var running = false
    private var connected = false
    // Only one retry and handshake deadline may remain scheduled.
    private var config: HomeConfig? = null
    private var retryTask: Runnable? = null
    private var timeout: Runnable? = null

    private val networkObserver =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { if (running && !connected) reconnectSoon() }
            }

            override fun onLost(network: Network) {
                handler.post { if (running) disconnected("Сеть потеряна. Переподключение…") }
            }
        }

    /** Begins registration and watches network changes while enabled. */
    fun start(value: HomeConfig) {
        config = value
        running = true
        networks.registerDefaultNetworkCallback(networkObserver)
        connect()
    }

    /** Sends only on a registered connection; false means the command was not delivered. */
    fun send(message: JSONObject): Boolean {
        if (connected && socket?.send(message.toString()) == true) return true
        if (running) disconnected("Нет связи с сервером. Переподключение…")
        return false
    }

    /** Cancels all retries and socket callbacks before disposing the HTTP client. */
    fun close() {
        running = false
        invalidate()
        runCatching { networks.unregisterNetworkCallback(networkObserver) }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun connect() {
        if (!running) return
        invalidate()
        val value = config ?: return
        val current = generation

        // Registration has a deadline even if the handshake never completes.
        changed(false, "Подключение к домашнему серверу…")
        timeout = Runnable { if (current == generation) disconnected("Сервер не отвечает") }
        handler.postDelayed(timeout!!, 12000)

        // Keep credentials out of URLs and diagnostics.
        val url =
            value.address
                .toHttpUrl()
                .newBuilder()
                .addPathSegments("api/connect")
                // Presence identity is public; the key stays in the header below.
                .addQueryParameter("id", settings.id)
                .addQueryParameter("name", value.name)
                .build()
        // Authenticate through a header, never through URL query parameters.
        val request =
            Request.Builder().url(url).header("Authorization", "Bearer ${value.key}").build()
        socket = client.newWebSocket(request, listener(current))
    }

    private fun listener(current: Int) =
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post { if (current == generation && running) receive(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post {
                    if (current != generation || !running) return@post
                    if (response?.code == 401) rejected()
                    else disconnected("Связь потеряна. Переподключение…")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                handler.post {
                    if (current == generation && running)
                        disconnected("Соединение закрыто. Переподключение…")
                }
            }
        }

    private fun receive(text: String) {
        val message =
            runCatching { JSONObject(text) }
                .getOrElse {
                    disconnected("Некорректный ответ сервера")
                    return
                }

        // Reset backoff only after the server accepts our device identity.
        if (message.optString("type") == "welcome") {
            connected = true
            timeout?.let(handler::removeCallbacks)
            retry.reset()
            changed(true, "На связи дома")
        }
        event(message)
    }

    private fun disconnected(message: String) {
        invalidate()
        lost()
        changed(false, message)
        if (!running) return

        // A single pending retry avoids reconnection storms.
        retryTask = Runnable { connect() }
        handler.postDelayed(retryTask!!, retry.nextDelay())
    }

    private fun reconnectSoon() {
        retryTask?.let(handler::removeCallbacks)
        retryTask = Runnable { if (!connected) connect() }
        handler.postDelayed(retryTask!!, 300)
    }

    private fun invalidate() {
        // Drop ownership before canceling the old connection.
        generation++
        connected = false
        socket?.cancel()
        socket = null

        // Neither an obsolete handshake timeout nor retry may run later.
        retryTask?.let(handler::removeCallbacks)
        timeout?.let(handler::removeCallbacks)
        retryTask = null
        timeout = null
    }
}
