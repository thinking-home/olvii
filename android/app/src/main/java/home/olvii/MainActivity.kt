package home.olvii

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.telecom.CallAudioState
import android.widget.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Small control screen; leaving it does not interrupt home availability or calls. */
class MainActivity : Activity() {
    // Connection form and its encrypted settings owner.
    private lateinit var settings: HomeSettings
    private lateinit var layout: LinearLayout
    private lateinit var server: EditText
    private lateinit var name: EditText
    private lateinit var key: EditText
    // Availability and background-operation controls.
    private lateinit var status: TextView
    private lateinit var battery: Button
    private lateinit var connect: Button
    private lateinit var disconnect: Button
    // Call actions follow the latest service snapshot.
    private lateinit var accept: Button
    private lateinit var reject: Button
    private lateinit var hangup: Button
    private lateinit var mute: Button
    private lateinit var route: Button
    // Visible observers do not retain the service after leaving this screen.
    private lateinit var devices: LinearLayout
    private val observer: (HomeState) -> Unit = { render(it) }
    private var awaitingPermissions = false

    /** Builds controls and restores the saved connection form. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = HomeSettings(this)
        // A scrollable column also works on a stationary Android panel.
        layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 48, 24, 24)
            }
        setContentView(ScrollView(this).apply { addView(layout) })

        // Credentials are restored from encrypted preferences.
        val config = settings.load()
        label("Olvii · домашняя связь")
        server = field("Адрес сервера", config.address)
        name = field("Имя устройства", config.name)
        key = field("Ключ сервера", config.key).apply { inputType = 129 }

        // Availability is an explicit mode with a persistent notification.
        connect = button("На связи дома") { enable() }
        disconnect = button("Отключиться") { command("stop") }
        status = label("Не на связи")
        battery = button("Разрешить работу в режиме сна") { batterySettings() }
        button("Настройки уведомлений") { notificationSettings() }

        // Every action carries the displayed call identity.
        accept = button("Ответить") { command("answer") }
        reject = button("Отклонить") { command("reject") }
        hangup = button("Завершить") { command("hangup") }
        mute = button("Выключить микрофон") { command("mute") }
        route = button("Выбрать динамик") { chooseRoute() }

        // Device buttons are rebuilt from service presence snapshots.
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(devices)
        render(CallUi.state)
    }

    /** Observes service state only while this screen is visible. */
    override fun onStart() {
        super.onStart()
        CallUi.observe(observer)
    }

    /** Refreshes the battery exemption after returning from Android settings. */
    override fun onResume() {
        super.onResume()
        val allowed =
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        battery.text =
            if (allowed) "Работа в режиме сна разрешена" else "Разрешить работу в режиме сна"
        battery.isEnabled = !allowed
    }

    /** Drops only the screen observer, leaving the service and audio untouched. */
    override fun onStop() {
        CallUi.remove(observer)
        super.onStop()
    }

    private fun enable() {
        if (!permissions()) return
        val config =
            HomeConfig(
                server.text.toString().trim(),
                name.text.toString().trim(),
                key.text.toString(),
            )
        // Reject incomplete configuration before saving or starting the service.
        val valid = runCatching { config.address.toHttpUrl() }.isSuccess
        if (!valid || config.name.isBlank() || config.name.length > 64 || config.key.isBlank()) {
            status.text = "Проверьте адрес, имя (до 64 символов) и ключ"
            return
        }

        // Save before asking Android to create or restore the foreground service.
        runCatching {
                settings.save(config)
                settings.enabled = true
                startForegroundService(Intent(this, HomeCallService::class.java).setAction("start"))
            }
            .onFailure {
                settings.enabled = false
                status.text = "Не удалось включить домашнюю связь"
            }
    }

    private fun permissions(): Boolean {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        // Continue this enable request only after Android returns its decision.
        if (missing.isNotEmpty()) {
            awaitingPermissions = true
            requestPermissions(missing.toTypedArray(), 1)
            return false
        }

        // Hidden notifications cannot provide a reliable answer action.
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) {
            status.text = "Разрешите уведомления в настройках Android"
            return false
        }
        return true
    }

    /** Continues the explicit enable action only if all requested permissions were granted. */
    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        // Ignore callbacks unrelated to the user's current enable request.
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 1 || !awaitingPermissions) return
        awaitingPermissions = false
        // Denial keeps availability disabled and leaves the form editable.
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED })
            enable()
        else status.text = "Для связи нужны микрофон и уведомления"
    }

    private fun command(action: String, target: String? = null, output: Int = 0) {
        if (!CallUi.state.enabled) return
        val intent = Intent(this, HomeCallService::class.java).setAction(action)
        // Attach snapshot identity so stale actions cannot affect a later call.
        intent
            .putExtra("callId", CallUi.state.callId)
            .putExtra("target", target)
            .putExtra("route", output)
        startService(intent)
    }

    private fun render(state: HomeState) {
        status.text =
            listOf(state.peerName, state.status).filter { it.isNotBlank() }.joinToString(" · ")
        // Freeze configuration while the service owns registration.
        server.isEnabled = !state.enabled
        name.isEnabled = !state.enabled
        key.isEnabled = !state.enabled
        connect.isEnabled = !state.enabled
        disconnect.isEnabled = state.enabled

        // Enable only actions valid for the current server-assigned call.
        accept.isEnabled = state.phase == CallPhase.INCOMING && state.readyToAnswer
        reject.isEnabled = state.phase == CallPhase.INCOMING
        hangup.isEnabled = state.callId != null
        mute.isEnabled = state.phase in listOf(CallPhase.ACTIVE, CallPhase.HELD)
        mute.text = if (state.muted) "Включить микрофон" else "Выключить микрофон"
        route.isEnabled = state.callId != null && state.supportedRoutes != 0

        // Presence is discarded on disconnect, so unavailable devices disappear.
        devices.removeAllViews()
        state.devices.forEach { device ->
            val button = Button(this)
            button.text = "Позвонить: ${device.name}" + if (device.busy) " (занят)" else ""
            button.isEnabled =
                state.connected && state.callId == null && !state.dialing && !device.busy
            // Revalidate availability in the service when the user taps.
            button.setOnClickListener { command("call", device.id) }
            devices.addView(button)
        }
    }

    @Suppress("DEPRECATION")
    private fun chooseRoute() {
        // Labels are mapped to the platform's supported audio routes.
        val routes =
            listOf(
                CallAudioState.ROUTE_SPEAKER to "Громкая связь",
                CallAudioState.ROUTE_EARPIECE to "Телефон",
                CallAudioState.ROUTE_WIRED_HEADSET to "Проводная гарнитура",
                CallAudioState.ROUTE_BLUETOOTH to "Bluetooth",
            )
        // Expose only routes currently reported by Telecom.
        val available = routes.filter { it.first and CallUi.state.supportedRoutes != 0 }
        AlertDialog.Builder(this)
            .setTitle("Вывод звука")
            .setItems(available.map { it.second }.toTypedArray()) { _, index ->
                command("route", output = available[index].first)
            }
            .show()
    }

    private fun batterySettings() {
        // LAN-only incoming calls cannot rely on cloud push during Doze.
        // Android asks the user; no battery setting is changed silently.
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
    }

    private fun notificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        startActivity(intent)
    }

    private fun label(value: String) =
        TextView(this).apply {
            text = value
            textSize = 20f
            this@MainActivity.layout.addView(this)
        }

    private fun field(hintText: String, value: String) =
        EditText(this).apply {
            hint = hintText
            setText(value)
            isSingleLine = true
            this@MainActivity.layout.addView(this)
        }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
            this@MainActivity.layout.addView(this)
        }
}
