package home.olvii

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.*

/** Registers this app's own calls without becoming the default phone dialer. */
object HomeTelecom {
    private fun handle(context: Context) =
        PhoneAccountHandle(ComponentName(context, HomeConnectionService::class.java), "olvii-home")

    /** Registers the self-managed account once the user enables home availability. */
    fun register(context: Context) {
        val account =
            PhoneAccount.builder(handle(context), "Olvii · домашняя связь")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                .build()
        context.getSystemService(TelecomManager::class.java).registerPhoneAccount(account)
    }

    /** Announces a server-assigned call to Telecom; media is not started here. */
    fun add(context: Context, id: String, incoming: Boolean) {
        val manager = context.getSystemService(TelecomManager::class.java)
        val extras = Bundle().apply { putString("olvii.callId", id) }
        val address = Uri.fromParts("sip", "$id@olvii.local", null)

        // Incoming and outgoing calls use different framework entry points.
        if (incoming) {
            extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address)
            manager.addNewIncomingCall(handle(context), extras)
            return
        }

        // Outgoing app metadata is nested by Telecom into the connection request.
        val options =
            Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
                putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, extras)
            }
        // Self-managed calls use MANAGE_OWN_CALLS instead of access to cellular calls.
        check(
            context.checkSelfPermission(Manifest.permission.MANAGE_OWN_CALLS) ==
                PackageManager.PERMISSION_GRANTED
        )
        manager.placeCall(address, options)
    }
}

/** System-bound bridge for Bluetooth, lock-screen and cellular-call coordination. */
class HomeConnectionService : ConnectionService() {
    /** Creates a ringing connection only for a call still owned by the local service. */
    override fun onCreateIncomingConnection(
        account: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection = create(request, true)

    /** Creates a dialing connection for an outgoing server-assigned call. */
    override fun onCreateOutgoingConnection(
        account: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection = create(request, false)

    /** Rejects the server call if Telecom cannot accept another incoming call. */
    override fun onCreateIncomingConnectionFailed(
        account: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) {
        HomeCallService.instance?.telecomFailed(request.extras?.getString("olvii.callId"))
    }

    /** Releases the server reservation when outgoing call creation is refused. */
    override fun onCreateOutgoingConnectionFailed(
        account: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) {
        HomeCallService.instance?.telecomFailed(request.extras?.getString("olvii.callId"))
    }

    private fun create(request: ConnectionRequest, incoming: Boolean): Connection {
        // Late framework callbacks must never recreate an ended call.
        val id = request.extras?.getString("olvii.callId")
        val service = HomeCallService.instance
        if (id == null || service == null) return failed()
        return service.createConnection(id, incoming) ?: failed()
    }

    private fun failed() =
        Connection.createFailedConnection(DisconnectCause(DisconnectCause.CANCELED))
}

/** Telecom representation of one call; every action carries its immutable call ID. */
class HomeConnection(private val owner: HomeCallService, val id: String) : Connection() {
    init {
        // Telecom owns audio focus and routing for this self-managed VoIP call.
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD
        audioModeIsVoip = true
    }

    /** Displays ringing only when Telecom permits this incoming call UI. */
    override fun onShowIncomingCallUi() {
        owner.showIncoming(id)
    }

    /** Answers an audio call through the same guarded path as the app notification. */
    override fun onAnswer() {
        owner.answer(id)
    }

    /** Video is not supported; system answer requests still create audio only. */
    override fun onAnswer(videoState: Int) {
        owner.answer(id)
    }

    /** Rejects this ringing call. */
    override fun onReject() {
        owner.reject(id)
    }

    /** Handles a system or headset hangup without affecting a newer call. */
    override fun onDisconnect() {
        owner.end(id, "system_disconnect")
    }

    /** Cancels an outgoing call before establishment. */
    override fun onAbort() {
        owner.end(id, "system_abort")
    }

    /** Silences both media directions while another system call owns audio. */
    override fun onHold() {
        owner.hold(id, true)
    }

    /** Restores media when Telecom resumes this call. */
    override fun onUnhold() {
        owner.hold(id, false)
    }

    /** Mirrors system mute and available output routes to the app. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        owner.audioState(id, state)
    }

    /** Updates the established output through Telecom, not AudioManager. */
    @Suppress("DEPRECATION")
    fun route(value: Int) {
        setAudioRoute(value)
    }

    /** Tears down the framework connection without calling the user action handler. */
    fun finish(cause: Int) {
        setDisconnected(DisconnectCause(cause))
        destroy()
    }
}
