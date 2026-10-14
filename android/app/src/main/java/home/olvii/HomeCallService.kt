package home.olvii

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.telecom.*
import android.util.Log
import org.json.JSONObject

/** Owns local registration and calls independently of the visible activity. */
class HomeCallService : Service() {
    // Main-thread ownership and Android resources.
    private val handler = Handler(Looper.getMainLooper())
    private val gate = CallGate()
    private lateinit var settings: HomeSettings
    private lateinit var notifications: HomeNotifications
    private lateinit var wake: PowerManager.WakeLock

    // Transport and media have independent lifetimes.
    private var signaling: LocalSignaling? = null
    private var connection: HomeConnection? = null
    private var audio: AudioCall? = null
    private var state = HomeState()

    // Per-call negotiation state is cleared before resource teardown.
    private var deadline: Runnable? = null
    private var initiator: Boolean? = null
    private var systemMuted = false
    private var foregroundId = HomeNotifications.ONLINE_ID
    private val pendingMedia = mutableListOf<JSONObject>()

    /** Initializes resources without opening the microphone or server connection. */
    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = HomeSettings(this)
        notifications = HomeNotifications(this)

        // A bounded wake lock is used only while ringing or talking.
        val power = getSystemService(PowerManager::class.java)
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Olvii:call")
        wake.setReferenceCounted(false)
    }

    /** Routes notification and screen actions through the same call identity checks. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("callId")
        when (intent?.action) {
            // Availability can be restored independently of calls.
            null,
            "start" -> startAvailability()
            "stop" -> stopAvailability("Не на связи")
            // Notification actions require their original call identity.
            "answer" -> id?.let(::answer)
            "reject" -> id?.let(::reject)
            "hangup" -> id?.let { end(it, "local_hangup") }
            // Foreground controls share the same guarded service entry point.
            "call" -> dial(intent.getStringExtra("target"))
            "mute" -> if (gate.matches(id)) toggleMute()
            "route" -> if (gate.matches(id)) connection?.route(intent.getIntExtra("route", 0))
        }
        return if (settings.enabled) START_STICKY else START_NOT_STICKY
    }

    /** Screens use immutable snapshots rather than retaining a service binder. */
    override fun onBind(intent: Intent?) = null

    /** Releases native resources but preserves the user's availability preference. */
    override fun onDestroy() {
        signaling?.close()
        signaling = null
        state = state.copy(enabled = false, connected = false, devices = emptyList())
        clearCall(state.status)
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startAvailability() {
        if (state.enabled) return
        if (!settings.enabled) {
            stopSelf()
            return
        }
        state = state.copy(enabled = true, status = "Подключение…")

        // Enter foreground before any asynchronous registration work.
        runCatching {
                foreground(false)
                HomeTelecom.register(this)
                openSignaling()
            }
            .onFailure { stopAvailability("Не удалось включить домашнюю связь") }
    }

    private fun openSignaling() {
        // Keep transport callbacks on the service's main-thread handler.
        signaling =
            LocalSignaling(
                this,
                handler,
                settings,
                // Protocol events and registration status update separate state.
                ::receiveSafely,
                { connected, text ->
                    state = state.copy(connected = connected, status = text)
                    publish()
                },
                // Authentication failure disables retries until credentials change.
                ::networkLost,
                { stopAvailability("Неверный ключ сервера") },
            )
        signaling!!.start(settings.load())
    }

    private fun stopAvailability(text: String) {
        settings.enabled = false
        signaling?.close()
        signaling = null
        state = state.copy(enabled = false, connected = false, devices = emptyList())

        // Explicit stop removes both notifications and prevents sticky restoration.
        clearCall(text)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun dial(target: String?) {
        if (target == null || !state.connected || gate.id != null || state.dialing) return
        state = state.copy(dialing = true, status = "Вызов…")
        publish()
        signaling?.send(JSONObject().put("type", "call").put("target", target))
    }

    private fun receive(message: JSONObject) {
        when (message.optString("type")) {
            // Presence and reservation do not start recording.
            "devices" -> presence(message)
            "incoming",
            "ringing" -> begin(message)
            "accepted" -> accepted(message)
            // Peer media is processed only for the current call.
            "offer",
            "answer",
            "ice" -> media(message)
            // Terminal events release the reservation and restore controls.
            "ended" -> if (gate.matches(message.optString("callId"))) clearCall("Вызов завершён")
            "error" -> serverError(message)
        }
    }

    private fun receiveSafely(message: JSONObject) {
        // A malformed event cannot crash the foreground service.
        runCatching { receive(message) }
            .onFailure {
                gate.id?.let { end(it, "invalid_event") }
                state = state.copy(dialing = false, status = "Некорректный ответ сервера")
                publish()
            }
    }

    private fun presence(message: JSONObject) {
        val entries = message.optJSONArray("devices") ?: return
        val devices =
            (0 until entries.length()).map { index ->
                val item = entries.getJSONObject(index)
                HomeDevice(item.getString("id"), item.getString("name"), item.optBoolean("busy"))
            }
        // Never offer this endpoint as its own destination.
        state = state.copy(devices = devices.filter { it.id != settings.id })
        publish()
    }

    private fun begin(message: JSONObject) {
        val id = message.getString("callId")
        val incoming = message.getString("type") == "incoming"
        if (!gate.begin(id, incoming)) return
        val peer = state.devices.find { it.id == message.optString("peerId") }

        // Register with Telecom before exposing an answer action.
        state =
            state.copy(
                dialing = false,
                peerName = message.optString("name", peer?.name ?: "Дом"),
                status = if (incoming) "Входящий вызов" else "Ожидание ответа…",
            )
        // Registration and ringing both have bounded deadlines.
        wake.acquire(35000)
        deadline(id, 10000, "telecom_timeout")
        runCatching { HomeTelecom.add(this, id, incoming) }.onFailure { telecomFailed(id) }
        publish()
    }

    /** Creates the system connection only while the server call still exists. */
    fun createConnection(id: String, incoming: Boolean): HomeConnection? {
        if (!gate.matches(id) || connection != null) return null
        val result = HomeConnection(this, id)
        result.setAddress(
            Uri.fromParts("sip", "$id@olvii.local", null),
            TelecomManager.PRESENTATION_ALLOWED,
        )
        result.setCallerDisplayName(state.peerName, TelecomManager.PRESENTATION_ALLOWED)

        // Telecom callbacks finish registration before media is created.
        connection = result
        if (incoming) result.setRinging() else result.setDialing()
        deadline(id, 35000, "answer_timeout")
        foreground(false)
        handler.post { if (gate.matches(id)) startAudio(id) }
        return result
    }

    /** Exposes incoming-call actions once the system permits ringing. */
    fun showIncoming(id: String) {
        if (!gate.matches(id) || gate.phase != CallPhase.INCOMING) return
        state = state.copy(readyToAnswer = true)
        publish()
    }

    /** Answers once; recording still waits for server acceptance. */
    fun answer(id: String) {
        if (!state.readyToAnswer || !gate.answer(id)) return
        // This explicit user action enables microphone eligibility before signaling.
        runCatching { foreground(true) }
            .onFailure {
                end(id, "microphone_unavailable")
                return
            }
        // Acceptance alone does not yet construct WebRTC capture.
        state = state.copy(status = "Соединение…", readyToAnswer = false)
        send("accept", id)
        publish()
    }

    /** Rejects only the current ringing call. */
    fun reject(id: String) {
        if (!gate.matches(id) || gate.phase != CallPhase.INCOMING) return
        send("reject", id)
        clearCall("Вызов отклонён", DisconnectCause.REJECTED)
    }

    /** Ends a call from a screen, notification, system action or media failure. */
    fun end(id: String, reason: String) {
        if (!gate.matches(id)) return
        Log.i("OlviiCall", "End $id: $reason")
        send("hangup", id)
        clearCall("Вызов завершён", DisconnectCause.LOCAL)
    }

    /** Releases server state if Android refuses this call. */
    fun telecomFailed(id: String?) {
        if (!gate.matches(id)) return
        end(id!!, "telecom_refused")
    }

    private fun accepted(message: JSONObject) {
        val id = message.getString("callId")
        if (!gate.accepted(id) || initiator != null) return
        initiator = message.getBoolean("initiator")
        deadline(id, 30000, "media_timeout")
        startAudio(id)
    }

    private fun startAudio(id: String) {
        if (!gate.matches(id) || connection == null || initiator == null || audio != null) return
        runCatching {
                foreground(true)
                connection!!.setActive()
                createAudio(id)
            }
            .onFailure { end(id, "audio_start_failed") }
    }

    private fun createAudio(id: String) {
        // All callbacks carry the original identity, so old calls cannot mutate new ones.
        audio =
            AudioCall(
                this,
                { handler.post(it) },
                { if (gate.matches(id)) signaling?.send(it.put("callId", id)) },
                { if (gate.matches(id)) mediaStatus(id, it) },
                { end(id, "media_failed") },
            )

        // Capture begins after acceptance; retain any early signaling until this point.
        state = state.copy(status = "Соединение…")
        wake.acquire(2 * 60 * 60 * 1000L)
        if (initiator == true) audio!!.offer()
        val queued = pendingMedia.toList()
        pendingMedia.clear()
        queued.forEach(::media)
        publish()
    }

    private fun media(message: JSONObject) {
        if (!gate.matches(message.optString("callId"))) return
        if (audio == null) {
            if (pendingMedia.size < 128) pendingMedia.add(message)
            return
        }

        // Invalid peer media terminates only its own call.
        runCatching {
                when (val type = message.getString("type")) {
                    "ice" -> audio?.candidate(message.getJSONObject("candidate"))
                    else -> audio?.remote(type, message.getString("sdp"))
                }
            }
            .onFailure { gate.id?.let { end(it, "invalid_media") } }
    }

    private fun mediaStatus(id: String, text: String) {
        if (text == "Разговор") {
            gate.active(id)
            deadline?.let(handler::removeCallbacks)
        } else deadline(id, 15000, "media_disconnected")
        state = state.copy(status = if (gate.phase == CallPhase.HELD) "На удержании" else text)
        publish()
    }

    /** Suspends both audio directions while Telecom gives priority to another call. */
    fun hold(id: String, held: Boolean) {
        if (!gate.hold(id, held)) return
        if (held) connection?.setOnHold() else connection?.setActive()
        audio?.hold(held)
        applyMute()
        state = state.copy(status = if (held) "На удержании" else "Разговор")
        publish()
    }

    /** Mirrors Telecom routing and system mute without losing the app's mute preference. */
    @Suppress("DEPRECATION")
    fun audioState(id: String, value: CallAudioState) {
        if (!gate.matches(id)) return
        systemMuted = value.isMuted
        state = state.copy(route = value.route, supportedRoutes = value.supportedRouteMask)
        applyMute()
        publish()
    }

    private fun toggleMute() {
        state = state.copy(muted = !state.muted)
        applyMute()
        publish()
    }

    private fun applyMute() {
        audio?.mute(state.muted || systemMuted || gate.phase == CallPhase.HELD)
    }

    private fun send(type: String, id: String) {
        if (state.connected) signaling?.send(JSONObject().put("type", type).put("callId", id))
    }

    private fun serverError(message: JSONObject) {
        state =
            state.copy(dialing = false, status = message.optString("message", "Вызов недоступен"))
        publish()
    }

    private fun networkLost() {
        state = state.copy(connected = false, devices = emptyList())
        clearCall("Связь потеряна. Переподключение…")
    }

    private fun clearCall(text: String, cause: Int = DisconnectCause.REMOTE) {
        // Invalidate before native teardown can enqueue more callbacks.
        gate.clear()
        deadline?.let(handler::removeCallbacks)
        deadline = null
        pendingMedia.clear()
        initiator = null

        // Release capture and system call ownership in a deterministic order.
        audio?.close()
        audio = null
        connection?.finish(cause)
        connection = null
        if (wake.isHeld) wake.release()

        // Return to quiet availability without retaining stale notification actions.
        systemMuted = false
        // Forget both app controls and routes from the previous call.
        state =
            state.copy(
                status = text,
                muted = false,
                readyToAnswer = false,
                dialing = false,
                // The next call obtains its own display identity and output routes.
                peerName = "",
                route = 0,
                supportedRoutes = 0,
            )
        notifications.clearCall()
        publish()
    }

    private fun deadline(id: String, millis: Long, reason: String) {
        deadline?.let(handler::removeCallbacks)
        deadline = Runnable { end(id, reason) }
        handler.postDelayed(deadline!!, millis)
    }

    private fun publish() {
        state = state.copy(callId = gate.id, phase = gate.phase)
        CallUi.publish(state)
        if (!state.enabled) return
        foreground(audio != null || gate.phase == CallPhase.CONNECTING)
    }

    private fun foreground(microphone: Boolean) {
        // CallStyle must be the foreground service notification, not a separate post.
        val snapshot = state.copy(callId = gate.id, phase = gate.phase)
        val showCall = gate.id != null && (gate.phase != CallPhase.INCOMING || state.readyToAnswer)
        val id = if (showCall) HomeNotifications.CALL_ID else HomeNotifications.ONLINE_ID
        val notification =
            if (showCall) notifications.call(snapshot) else notifications.availability(snapshot)

        // Idle connection, system call and capture use distinct service types.
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (connection != null) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (microphone && Build.VERSION.SDK_INT >= 30)
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        // Replace the previous notification only after the new foreground ID is registered.
        if (Build.VERSION.SDK_INT >= 29) startForeground(id, notification, types)
        else startForeground(id, notification)
        if (foregroundId != id) notifications.clear(foregroundId)
        foregroundId = id
    }

    companion object {
        /** Main-thread bridge used only by the framework-bound ConnectionService. */
        var instance: HomeCallService? = null
            private set
    }
}
