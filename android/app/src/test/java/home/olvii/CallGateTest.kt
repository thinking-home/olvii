package home.olvii

import org.junit.Assert.*
import org.junit.Test

/** Exercises races shared by screen, notification and system callbacks. */
class CallGateTest {
    /** A second tap and an old notification cannot answer again. */
    @Test
    fun answerIsExplicitAndIdempotent() {
        val gate = CallGate()
        assertTrue(gate.begin("first", true))
        assertFalse(gate.accepted("first"))
        assertFalse(gate.answer("old"))
        assertTrue(gate.answer("first"))
        assertFalse(gate.answer("first"))
        assertTrue(gate.accepted("first"))
    }

    /** A delayed callback cannot alter the next call. */
    @Test
    fun staleEventsCannotRevivePreviousCall() {
        val gate = CallGate()
        gate.begin("first", false)
        gate.clear()
        assertFalse(gate.matches("first"))
        assertTrue(gate.begin("second", true))
        assertFalse(gate.accepted("first"))
        assertEquals(CallPhase.INCOMING, gate.phase)
    }

    /** A media reconnect must not silently take a held call off hold. */
    @Test
    fun mediaReadinessPreservesSystemHold() {
        val gate = CallGate()
        gate.begin("call", false)
        assertFalse(gate.hold("call", true))
        gate.accepted("call")
        gate.active("call")

        assertTrue(gate.hold("call", true))
        gate.active("call")
        assertEquals(CallPhase.HELD, gate.phase)
        assertFalse(gate.hold("old", false))
        assertTrue(gate.hold("call", false))
        assertEquals(CallPhase.ACTIVE, gate.phase)
    }

    /** Competing incoming calls cannot replace the current identity. */
    @Test
    fun onlyOneCallOwnsTheEndpoint() {
        val gate = CallGate()
        assertTrue(gate.begin("first", false))
        assertFalse(gate.begin("second", true))
        assertEquals("first", gate.id)
        assertEquals(CallPhase.DIALING, gate.phase)
    }

    /** Retry delays grow, stay bounded and reset after successful registration. */
    @Test
    fun retryBackoffIsBoundedAndResettable() {
        val retry = RetryPolicy()
        assertEquals(
            listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L),
            (1..6).map { retry.nextDelay() },
        )
        repeat(50) { assertEquals(30000L, retry.nextDelay()) }
        retry.reset()
        assertEquals(1000L, retry.nextDelay())
    }
}
