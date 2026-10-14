package home.olvii

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Foreground-only calling screen shared by phones and stationary panels. */
class MainActivity : Activity() {
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()

    // Connection form fields.
    private lateinit var server: EditText
    private lateinit var key: EditText
    private lateinit var name: EditText
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout

    // Actions enabled according to the current call state.
    private lateinit var accept: Button
    private lateinit var reject: Button
    private lateinit var hangup: Button
    private lateinit var mute: Button

    // Resources owned by the connection and current call.
    private var socket: WebSocket? = null
    private var callId: String? = null
    private var audio: AudioCall? = null
    private var ringing: Ringtone? = null

    // UI flags are updated only on the main thread.
    private var muted = false
    private var connectingCall = false
    private var connected = false
    private var generation = 0

    // Negotiation timeout and cached presence.
    private val handler = Handler(Looper.getMainLooper())
    private var audioConnected = false
    private var lastDevices: JSONObject? = null

    // Persist identity and address, but never the shared key.
    private val prefs by lazy { getSharedPreferences("olvii", MODE_PRIVATE) }
    private val deviceId by lazy {
        prefs.getString("id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    /** Builds the calling UI and requests microphone access when needed. */
    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep the foreground MVP awake during a call.
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Create the single scrollable column.
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 48, 24, 24)
            }
        setContentView(ScrollView(this).apply { addView(layout) })

        // Use compact builders for the repeated UI elements.
        fun label(text: String) =
            TextView(this).apply {
                this.text = text
                textSize = 20f
                layout.addView(this)
            }

        // Add one editable field with its initial value.
        fun field(hint: String, value: String) =
            EditText(this).apply {
                this.hint = hint
                setText(value)
                isSingleLine = true
                layout.addView(this)
            }

        // Bind one UI action without duplicating view setup.
        fun button(text: String, action: () -> Unit) =
            Button(this).apply {
                this.text = text
                setOnClickListener { action() }
                layout.addView(this)
            }

        // Introduce the foreground-only workflow.
        label("Olvii · домашняя связь")
        label("Для MVP держите приложение открытым на обоих устройствах")

        // Restore the address independently from the display name.
        server =
            field(
                "http://192.168.1.10:5080",
                prefs.getString("server", "http://192.168.1.10:5080")!!,
            )

        // Keep credentials in memory for this app session.
        name = field("Имя устройства", prefs.getString("name", android.os.Build.MODEL)!!)
        key = field("Общий ключ сервера", "").apply { inputType = 129 }
        button("Подключиться") { connect() }
        status = label("Нет подключения")

        // Only an explicit answer may start the microphone.
        accept = button("Ответить") { answer() }

        // Expose the remaining call controls.
        reject = button("Отклонить") { command("reject") }
        hangup = button("Завершить") { command("hangup") }
        mute =
            button("Выключить микрофон") {
                muted = !muted
                audio?.mute(muted)
                mute.text = if (muted) "Включить микрофон" else "Выключить микрофон"
            }

        // Attach the device list after the call controls.
        devices =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layout.addView(this)
            }

        // Initialize controls before requesting microphone permission.
        resetCall()
        if (!hasMicrophonePermission())
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun connect() {
        // Invalidate callbacks from the previous connection.
        disconnect()
        try {
            // Build the same signaling URL used by the browser.
            val address = server.text.toString().trim().toHttpUrl()

            // Append registration parameters to the parsed server address.
            val url =
                address
                    .newBuilder()
                    .addPathSegments("api/connect")
                    .addQueryParameter("id", deviceId)
                    .addQueryParameter("name", name.text.toString().trim())
                    .build()

            // Authenticate native WebSocket through its HTTP header.
            val request =
                Request.Builder().url(url).header("Authorization", "Bearer ${key.text}").build()

            // Save editable identity fields without persisting the key.
            prefs
                .edit()
                .putString("server", server.text.toString())
                .putString("name", name.text.toString())
                .apply()

            // Tag callbacks so stale connections cannot change this UI.
            val current = generation
            status.text = "Подключение…"
            socket = client.newWebSocket(request, socketListener(current))
        } catch (e: Exception) {
            status.text = "Проверьте адрес сервера: ${e.message}"
        }
    }

    private fun socketListener(current: Int) =
        object : WebSocketListener() {
            // Discard events from a socket that has already been replaced.
            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread { if (current == generation) parseMessage(text) }
            }

            // Restore disconnected controls when the transport fails.
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    if (current == generation) {
                        disconnect()
                        status.text = "Нет связи: " + (response?.code ?: t.message)
                    }
                }
            }

            // Complete the close handshake before updating the screen.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                runOnUiThread { socketClosed(current, reason) }
            }
        }

    private fun parseMessage(text: String) {
        // A malformed message ends only the current call, not the activity.
        try {
            receive(JSONObject(text))
        } catch (_: Exception) {
            fail("Ошибка протокола")
        }
    }

    private fun socketClosed(current: Int, reason: String) {
        // Ignore a close callback from an obsolete connection.
        if (current != generation) return
        disconnect()
        status.text = "Соединение закрыто: $reason"
    }

    private fun hasMicrophonePermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun answer() {
        // Leave the call ringing while Android asks for microphone access.
        if (!hasMicrophonePermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }

        // Disable duplicate answers before sending the acceptance command.
        accept.isEnabled = false
        reject.isEnabled = false
        ringing?.stop()
        command("accept")
    }

    private fun send(json: JSONObject) {
        // Treat a rejected write as a lost signaling connection.
        if (socket?.send(json.toString()) != true) {
            disconnect()
            status.text = "Нет подключения"
        }
    }

    private fun command(type: String) {
        // Scope commands to the currently displayed call.
        callId?.let { send(JSONObject().put("type", type).put("callId", it)) }
    }

    private fun receive(json: JSONObject) {
        // Ignore delayed messages belonging to a previous call.
        val type = json.getString("type")
        if (
            type in listOf("accepted", "offer", "answer", "ice", "ended") &&
                json.optString("callId") != callId
        )
            return

        // Route each server event to one small UI operation.
        when (type) {
            // Registration confirms that commands can now be sent.
            "welcome" -> {
                connected = true
                status.text = "Готов к звонку"
            }

            // Presence and ringing update controls without starting capture.
            "devices" -> showDevices(json)
            "ringing",
            "incoming" -> showRinging(json, type)

            // Media signaling is allowed only for the accepted current call.
            "accepted" -> startAudio(json)
            "offer",
            "answer" -> audio?.remote(type, json.getString("sdp"))
            "ice" -> audio?.candidate(json.getJSONObject("candidate"))

            // Release media before displaying the terminal reason.
            "ended" -> {
                resetCall()
                status.text = "Звонок завершён: ${json.getString("reason")}"
            }

            // Restore cached device controls after a rejected action.
            "error" -> {
                connectingCall = false
                status.text = json.getString("message")
                lastDevices?.let { receive(it) }
            }
        }
    }

    private fun showDevices(json: JSONObject) {
        // Cache presence so an error can restore the device buttons.
        lastDevices = json
        devices.removeAllViews()
        val list = json.getJSONArray("devices")

        // Skip this endpoint in the list of available destinations.
        for (i in 0 until list.length()) {
            val device = list.getJSONObject(i)
            if (device.getString("id") == deviceId) continue
            devices.addView(
                Button(this).apply {
                    // Show identity and busy state without changing either.
                    text =
                        "Позвонить: ${device.getString("name")}" +
                            if (device.getBoolean("busy")) " (занят)" else ""

                    // Disable dialing while either endpoint is occupied.
                    isEnabled =
                        connected && callId == null && !connectingCall && !device.getBoolean("busy")

                    // Validate permission before reserving an outgoing call.
                    setOnClickListener {
                        if (!hasMicrophonePermission()) {
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                            return@setOnClickListener
                        }

                        // Prevent duplicate clicks while the server processes the call.
                        if (callId != null || connectingCall) return@setOnClickListener
                        connectingCall = true
                        isEnabled = false
                        status.text = "Вызов…"

                        // The server assigns the new call ID.
                        send(JSONObject().put("type", "call").put("target", device.getString("id")))
                    }
                }
            )
        }
    }

    private fun showRinging(json: JSONObject, type: String) {
        // Replace the pending request with the server-assigned call.
        callId = json.getString("callId")
        connectingCall = false
        val incoming = type == "incoming"
        status.text = if (incoming) "Входящий: ${json.getString("name")}" else "Ожидаем ответа…"

        // Enable only actions available during ringing.
        accept.isEnabled = incoming
        reject.isEnabled = incoming
        hangup.isEnabled = true

        // Play the system ringtone only for an incoming call.
        if (incoming) {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringing = RingtoneManager.getRingtone(this, ringtoneUri)
            ringing?.play()
        }
    }

    private fun startAudio(json: JSONObject) {
        // Stop ringing before entering media negotiation.
        ringing?.stop()
        accept.isEnabled = false
        reject.isEnabled = false
        status.text = "Соединяем звук…"

        // Route conversation audio through the speaker.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true

        // Bind signaling callbacks to this call identity.
        val currentCall = callId
        val sendMedia: (JSONObject) -> Unit = { message ->
            if (callId == currentCall) send(message.put("callId", currentCall))
        }

        // Report media readiness independently from signaling state.
        val reportMedia: (String) -> Unit = {
            status.text = it
            audioConnected = it == "Разговор"
        }

        // Create media only after the server confirms explicit acceptance.
        audio = AudioCall(this, { runOnUiThread(it) }, sendMedia, reportMedia, { fail(it) })

        // Only the caller creates the initial offer.
        mute.isEnabled = true
        if (json.getBoolean("initiator")) audio?.offer()

        // Bound connection setup even after SDP has been exchanged.
        val checkConnection = Runnable {
            if (callId == currentCall && !audioConnected)
                fail("Не удалось соединить устройства за 25 секунд")
        }
        handler.postDelayed(checkConnection, 25000)
    }

    private fun fail(message: String) {
        // Notify the other participant before releasing local media.
        command("hangup")
        resetCall()
        status.text = message
    }

    private fun resetCall() {
        // Stop resources that can produce or capture audio.
        ringing?.stop()
        ringing = null
        audio?.close()
        audio = null

        // Clear state before any delayed callback can run.
        callId = null
        connectingCall = false
        muted = false
        audioConnected = false

        // Cancel setup timeout and restore normal audio routing.
        handler.removeCallbacksAndMessages(null)
        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false

        // Disable call controls until another call begins.
        accept.isEnabled = false
        reject.isEnabled = false
        hangup.isEnabled = false
        mute.isEnabled = false
        mute.text = "Выключить микрофон"
    }

    private fun disconnect() {
        // Invalidate callbacks before closing the old socket.
        generation++
        connected = false
        socket?.close(1000, "Leaving")
        socket = null

        // Drop presence and call state tied to this connection.
        lastDevices = null
        resetCall()
        devices.removeAllViews()
    }

    /** Disconnects when the MVP leaves the foreground. */
    override fun onStop() {
        // Background operation is outside the MVP lifecycle.
        super.onStop()
        if (!isChangingConfigurations) {
            disconnect()
            status.text = "Отключено. Нажмите «Подключиться»"
        }
    }

    /** Releases signaling resources owned by this activity. */
    override fun onDestroy() {
        // Stop callbacks before shutting down the HTTP client.
        disconnect()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        super.onDestroy()
    }
}
