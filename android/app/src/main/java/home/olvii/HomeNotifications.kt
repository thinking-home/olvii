package home.olvii

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build

/** Builds persistent availability and system-style call notifications. */
class HomeNotifications(private val service: Service) {
    private val manager = service.getSystemService(NotificationManager::class.java)

    init {
        // Idle presence is silent and does not repeatedly interrupt the user.
        val online =
            NotificationChannel("home-online", "На связи дома", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(online)

        // The incoming-call channel remains configurable in Android settings.
        val calls =
            NotificationChannel(
                "home-calls",
                "Входящие звонки",
                NotificationManager.IMPORTANCE_HIGH,
            )
        // Use the system ringtone with call-specific audio attributes.
        calls.enableVibration(true)
        calls.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        calls.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            ringtoneAttributes(),
        )
        manager.createNotificationChannel(calls)
    }

    /** Silent ongoing notification keeps availability visible and offers an explicit stop. */
    fun availability(state: HomeState): Notification {
        val builder = base("home-online", state.status)
        builder.setContentTitle("Olvii · на связи дома")
        builder.addAction(action("Отключиться", "stop", null))
        return builder.build()
    }

    /** Shows answer/reject while ringing and hangup during negotiation or conversation. */
    fun call(state: HomeState): Notification {
        val incoming = state.phase == CallPhase.INCOMING
        val channel = if (incoming) "home-calls" else "home-online"
        val builder = base(channel, state.status).setContentTitle(state.peerName)
        builder.setCategory(Notification.CATEGORY_CALL).setColor(Color.rgb(40, 100, 170))

        // CallStyle gives the platform standard lock-screen and headset affordances.
        if (Build.VERSION.SDK_INT >= 31) {
            val person =
                Person.Builder()
                    .setName(state.peerName.ifBlank { "Домашний вызов" })
                    .setImportant(true)
                    .build()
            // Incoming and ongoing calls expose different system actions.
            val style =
                if (incoming) incomingStyle(person, state.callId)
                else ongoingStyle(person, state.callId)
            builder.setStyle(style)
        } else legacyActions(builder, state)

        // Only the initial ringing update should play the channel ringtone.
        builder.setOnlyAlertOnce(true)
        if (incoming) builder.setTimeoutAfter(30000)
        return builder.build()
    }

    /** Removes an obsolete notification after the service switches foreground IDs. */
    fun clear(id: Int) {
        manager.cancel(id)
    }

    /** Removes all actions belonging to a completed call. */
    fun clearCall() {
        manager.cancel(CALL_ID)
    }

    private fun base(channel: String, text: String) =
        Notification.Builder(service, channel)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentText(text)
            .setContentIntent(openApp())
            // Keep availability visible without re-alerting on state updates.
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)

    private fun action(label: String, command: String, id: String?) =
        Notification.Action.Builder(null, label, pending(command, id)).build()

    private fun pending(command: String, id: String?): PendingIntent {
        // Identity in the URI prevents an old notification from mutating a new call.
        val intent = Intent(service, HomeCallService::class.java).setAction(command)
        intent.data = Uri.parse("olvii://action/$command/${id ?: "availability"}")
        intent.putExtra("callId", id)
        // Immutable intents retain the specific action and call identity.
        return PendingIntent.getService(
            service,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(service, MainActivity::class.java)
        return PendingIntent.getActivity(
            service,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @android.annotation.TargetApi(31)
    private fun incomingStyle(person: Person, id: String?) =
        Notification.CallStyle.forIncomingCall(person, pending("reject", id), pending("answer", id))

    @android.annotation.TargetApi(31)
    private fun ongoingStyle(person: Person, id: String?) =
        Notification.CallStyle.forOngoingCall(person, pending("hangup", id))

    private fun legacyActions(builder: Notification.Builder, state: HomeState) {
        if (state.phase == CallPhase.INCOMING) {
            builder.addAction(action("Ответить", "answer", state.callId))
            builder.addAction(action("Отклонить", "reject", state.callId))
        } else builder.addAction(action("Завершить", "hangup", state.callId))
    }

    private fun ringtoneAttributes() =
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()

    companion object {
        /** Stable ID for the availability foreground notification. */
        const val ONLINE_ID = 100
        /** Stable ID for the current call's notification. */
        const val CALL_ID = 101
    }
}
