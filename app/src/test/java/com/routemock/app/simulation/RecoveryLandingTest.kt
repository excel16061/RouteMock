package com.routemock.app.simulation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryLandingTest {
    @Test
    fun interruptedSessionOpensOnSimulation() {
        assertTrue(opensOnSimulation(SimulationState.Idle(SessionEnd.Interrupted(cleanupComplete = true))))
        assertTrue(opensOnSimulation(SimulationState.Idle(SessionEnd.Interrupted(cleanupComplete = false))))
    }

    @Test
    fun otherEndsOpenOnMap() {
        assertFalse(opensOnSimulation(SimulationState.Idle()))
        assertFalse(opensOnSimulation(SimulationState.Idle(SessionEnd.Stopped(SessionKind.INJECTION, true))))
        assertFalse(opensOnSimulation(SimulationState.Idle(SessionEnd.Failed("x", true))))
        assertFalse(opensOnSimulation(SimulationState.Idle(SessionEnd.NotAuthorized(true))))
    }
}
