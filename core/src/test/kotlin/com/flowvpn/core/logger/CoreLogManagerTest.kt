package com.flowvpn.core.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CoreLogManagerTest {

    @Before
    fun setup() {
        CoreLogManager.clear()
    }

    @Test
    fun testLogLevelsDetection() {
        CoreLogManager.log("sing-box initialized successfully", tag = "Core")
        CoreLogManager.log("connection error: connection refused", tag = "sing-box")
        CoreLogManager.log("warning: slow handshake detected", tag = "sing-box")
        CoreLogManager.log("debug trace packet received", tag = "sing-box")

        val logs = CoreLogManager.logs.value
        assertEquals(4, logs.size)

        assertEquals(LogLevel.INFO, logs[0].level)
        assertEquals(LogLevel.ERROR, logs[1].level)
        assertEquals(LogLevel.WARN, logs[2].level)
        assertEquals(LogLevel.DEBUG, logs[3].level)
    }

    @Test
    fun testClearLogs() {
        CoreLogManager.log("test 1")
        CoreLogManager.log("test 2")
        assertEquals(2, CoreLogManager.logs.value.size)

        CoreLogManager.clear()
        assertTrue(CoreLogManager.logs.value.isEmpty())
    }
}
