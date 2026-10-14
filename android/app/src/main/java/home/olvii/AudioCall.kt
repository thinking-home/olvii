package home.olvii

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Owns the WebRTC resources for one accepted audio call.
 *
 * All public operations and dispatched callbacks run on the activity's UI thread.
 *
 * @param context Android context used to initialize native audio.
 * @param dispatch Schedules callbacks on the UI thread.
 * @param send Sends SDP or ICE through the signaling connection.
 * @param status Reports a user-visible connection state.
 * @param failed Reports a fatal negotiation error to the call owner.
 */
class AudioCall(
    context: Context,
    private val dispatch: (() -> Unit) -> Unit,
    private val send: (JSONObject) -> Unit,
    private val status: (String) -> Unit,
    private val failed: (String) -> Unit,
) {
    // Native resources are released in reverse ownership order.
    private val audioModule: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null

    // Candidates wait until the remote description has been applied.
    private var remoteReady = false
    private var closed = false
    private val candidates = mutableListOf<IceCandidate>()

    init {
        // Load native WebRTC before creating the audio module.
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
        PeerConnectionFactory.initialize(options.createInitializationOptions())
        audioModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()

        // Give the factory ownership of the configured audio backend.
        factory =
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .createPeerConnectionFactory()

        // Local-network calls do not require external ICE servers.
        val config = PeerConnection.RTCConfiguration(emptyList())
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        peer =
            factory.createPeerConnection(config, connectionObserver())
                ?: error("Cannot create WebRTC peer")

        // Capture starts only after the owner creates this accepted call.
        source = factory.createAudioSource(MediaConstraints())
        track = factory.createAudioTrack("microphone", source)
        peer!!.addTrack(track, listOf("voice"))
    }

    /** Creates the caller's initial SDP offer. */
    fun offer() = create(true)

    /** Applies remote SDP; an offer triggers an answer after queued ICE is drained. */
    fun remote(type: String, sdp: String) {
        // Convert the wire type without changing the SDP text.
        val description = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
        val callback = observer(set = { remoteApplied(type) })
        peer?.setRemoteDescription(callback, description)
    }

    /** Applies a remote ICE candidate, or queues it until remote SDP is ready. */
    fun candidate(json: JSONObject) {
        // Decode the three candidate fields shared by Android and browser clients.
        val mid = json.optString("sdpMid", "0")
        val index = json.getInt("sdpMLineIndex")
        val candidate = IceCandidate(mid, index, json.getString("candidate"))

        // Preserve arrival order while waiting for the remote description.
        if (remoteReady) peer?.addIceCandidate(candidate) else candidates.add(candidate)
    }

    /** Disables the outgoing audio track when [muted] is true. */
    fun mute(muted: Boolean) {
        track?.setEnabled(!muted)
    }

    /** Stops capture and releases native resources; repeated calls are harmless. */
    fun close() {
        // Ignore duplicate cleanup and suppress already queued callbacks.
        if (closed) return
        closed = true

        // Stop the connection before disposing its media resources.
        peer?.close()
        peer?.dispose()
        peer = null

        // Release capture, factory and audio backend in ownership order.
        track?.dispose()
        source?.dispose()
        factory.dispose()
        audioModule.release()
    }

    private fun create(offer: Boolean) {
        // Both SDP creation paths share the same local-description callback.
        val callback = observer(created = { setLocal(it, offer) })
        if (offer) peer?.createOffer(callback, MediaConstraints())
        else peer?.createAnswer(callback, MediaConstraints())
    }

    private fun setLocal(description: SessionDescription, offer: Boolean) {
        // Signal SDP only after WebRTC has successfully applied it locally.
        val type = if (offer) "offer" else "answer"
        val message = JSONObject().put("type", type).put("sdp", description.description)
        peer?.setLocalDescription(observer(set = { send(message) }), description)
    }

    private fun remoteApplied(type: String) {
        // Drain buffered candidates before generating an answer.
        remoteReady = true
        candidates.forEach { peer?.addIceCandidate(it) }
        candidates.clear()

        // Only the recipient of an offer creates an answer.
        if (type == "offer") create(false)
    }

    private fun sendCandidate(candidate: IceCandidate) {
        // Keep candidate field names identical to the browser wire format.
        val payload =
            JSONObject()
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("candidate", candidate.sdp)

        // The activity adds the current call ID before sending.
        send(JSONObject().put("type", "ice").put("candidate", payload))
    }

    private fun reportState(state: PeerConnection.PeerConnectionState) {
        // Report established audio separately from signaling acceptance.
        if (state == PeerConnection.PeerConnectionState.CONNECTED) status("Разговор")
        if (state == PeerConnection.PeerConnectionState.FAILED)
            failed("Не удалось установить аудиосвязь")
        if (state == PeerConnection.PeerConnectionState.DISCONNECTED) status("Связь прервана…")
    }

    private fun connectionObserver() =
        object : PeerConnection.Observer {
            // Native callbacks must return to the UI thread before touching call state.
            override fun onIceCandidate(candidate: IceCandidate) {
                dispatch { if (!closed) sendCandidate(candidate) }
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                dispatch { if (!closed) reportState(state) }
            }

            // These signaling and network notifications do not alter the MVP UI.
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

            // Audio playback is handled by WebRTC; no video or data channel is used.
            override fun onAddStream(stream: MediaStream) {}

            override fun onRemoveStream(stream: MediaStream) {}

            override fun onDataChannel(channel: DataChannel) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }

    private fun observer(created: (SessionDescription) -> Unit = {}, set: () -> Unit = {}) =
        object : SdpObserver {
            // Suppress callbacks belonging to a call that has already ended.
            override fun onCreateSuccess(sdp: SessionDescription) {
                dispatch { if (!closed) created(sdp) }
            }

            override fun onSetSuccess() {
                dispatch { if (!closed) set() }
            }

            // Send both failure paths through the owner's cleanup handler.
            override fun onCreateFailure(error: String) {
                dispatch { if (!closed) failed(error) }
            }

            override fun onSetFailure(error: String) {
                dispatch { if (!closed) failed(error) }
            }
        }
}
