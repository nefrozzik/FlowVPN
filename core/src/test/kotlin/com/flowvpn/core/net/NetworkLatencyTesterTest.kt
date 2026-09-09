package com.flowvpn.core.net

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.SplitTunnelConfig
import com.flowvpn.core.model.SplitTunnelMode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkLatencyTesterTest {

    private val tester = NetworkLatencyTester()

    @Test
    fun testTcpLatencyTimeoutOnInvalidHost() = runBlocking {
        // Заведомо недоступный адрес для проверки обработки таймаута без вылета
        val latency = tester.testTcpLatency("192.0.2.1", 12345, timeoutMs = 200)
        assertEquals(-1, latency)
    }

    @Test
    fun testParallelTestingConcurrency() = runBlocking {
        val dummyServers = listOf(
            ProxyServerConfig(id = "1", name = "S1", protocol = ProxyProtocol.VLESS, address = "192.0.2.1", port = 80),
            ProxyServerConfig(id = "2", name = "S2", protocol = ProxyProtocol.VLESS, address = "192.0.2.2", port = 80),
            ProxyServerConfig(id = "3", name = "S3", protocol = ProxyProtocol.VLESS, address = "192.0.2.3", port = 80),
        )

        val results = tester.testAllParallel(dummyServers, maxConcurrency = 2, timeoutMs = 150).toList()
        assertEquals(3, results.size)
        assertTrue(results.all { it.second == -1 })
    }

    @Test
    fun testSplitTunnelConfigDefaults() {
        val config = SplitTunnelConfig()
        assertEquals(SplitTunnelMode.DISABLED, config.mode)
        assertTrue(config.selectedPackages.isEmpty())

        val allowConfig = SplitTunnelConfig(
            mode = SplitTunnelMode.ALLOW_LIST,
            selectedPackages = setOf("org.telegram.messenger", "com.google.android.youtube")
        )
        assertEquals(2, allowConfig.selectedPackages.size)
        assertTrue(allowConfig.selectedPackages.contains("org.telegram.messenger"))
    }
}
