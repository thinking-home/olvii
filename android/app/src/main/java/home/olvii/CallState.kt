package home.olvii

/** Lifecycle shared by the service, notification and foreground screen. */
enum class CallPhase {
    IDLE,
    DIALING,
    INCOMING,
    CONNECTING,
    ACTIVE,
    HELD,
}

/** Immutable presence entry received from the local server. */
data class HomeDevice(val id: String, val name: String, val busy: Boolean)

/** Screen state contains neither the access key nor media payloads. */
data class HomeState(
    // Availability and presence are independent of the current call.
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Не на связи",
    val devices: List<HomeDevice> = emptyList(),
    // Call controls carry the server identity and explicit-answer state.
    val callId: String? = null,
    val peerName: String = "",
    val phase: CallPhase = CallPhase.IDLE,
    val muted: Boolean = false,
    val readyToAnswer: Boolean = false,
    // Telecom supplies routing; dialing blocks duplicate outgoing requests.
    val route: Int = 0,
    val supportedRoutes: Int = 0,
    val dialing: Boolean = false,
)

/** Main-thread state stream; activities subscribe only while visible. */
object CallUi {
    /** Latest service snapshot, also available before an activity subscribes. */
    var state = HomeState()
        private set

    private val listeners = mutableSetOf<(HomeState) -> Unit>()

    /** Adds a screen observer and immediately supplies the current snapshot. */
    fun observe(listener: (HomeState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    /** Removes a screen observer without stopping the calling service. */
    fun remove(listener: (HomeState) -> Unit) {
        listeners.remove(listener)
    }

    internal fun publish(value: HomeState) {
        state = value
        listeners.toList().forEach { it(value) }
    }
}

/** Bounded retry policy, reset only after a successful server registration. */
class RetryPolicy {
    private var attempt = 0

    /** Returns the next delay: 1, 2, 4, 8, 16, then 30 seconds. */
    fun nextDelay(): Long =
        (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30000L).also {
            attempt = (attempt + 1).coerceAtMost(5)
        }

    /** Starts a new retry sequence after successful registration. */
    fun reset() {
        attempt = 0
    }
}

/** One-call gate shared by UI and notification actions. */
class CallGate {
    /** Identity is cleared before resources belonging to a call are released. */
    var id: String? = null
        private set

    /** Current phase; only valid transitions are permitted. */
    var phase = CallPhase.IDLE
        private set

    /** Reserves an idle endpoint for a server-assigned call. */
    fun begin(callId: String, incoming: Boolean): Boolean {
        if (id != null) return false
        id = callId
        phase = if (incoming) CallPhase.INCOMING else CallPhase.DIALING
        return true
    }

    /** Rejects duplicate answers and actions from stale notifications. */
    fun answer(callId: String): Boolean {
        if (!matches(callId) || phase != CallPhase.INCOMING) return false
        phase = CallPhase.CONNECTING
        return true
    }

    /** Marks server acceptance for either participant. */
    fun accepted(callId: String): Boolean {
        if (!matches(callId) || phase !in listOf(CallPhase.DIALING, CallPhase.CONNECTING))
            return false
        phase = CallPhase.CONNECTING
        return true
    }

    /** Marks media establishment without overriding a system hold. */
    fun active(callId: String) {
        if (matches(callId) && phase == CallPhase.CONNECTING) phase = CallPhase.ACTIVE
    }

    /** Applies system hold only to an established conversation. */
    fun hold(callId: String, held: Boolean): Boolean {
        val expected = if (held) CallPhase.ACTIVE else CallPhase.HELD
        if (!matches(callId) || phase != expected) return false
        phase = if (held) CallPhase.HELD else CallPhase.ACTIVE
        return true
    }

    /** True only for the currently owned call ID. */
    fun matches(callId: String?) = id != null && id == callId

    /** Invalidates late callbacks and returns to idle. */
    fun clear() {
        id = null
        phase = CallPhase.IDLE
    }
}
