package com.flowvpn.core.config

import com.flowvpn.core.model.AppSettings
import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigBuilderTest {

    private val moshi = Moshi.Builder().build()

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(json: String): Map<String, Any> {
        val adapter = moshi.adapter(Map::class.java)
        return adapter.fromJson(json) as Map<String, Any>
    }

    private val sampleConfigWithDomain = ProxyServerConfig(
        id = "test-1",
        name = "Test VLESS Server",
        address = "vpn.flowvpn.example.com",
        port = 443,
        protocol = ProxyProtocol.VLESS,
        uuid = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
        flow = "xtls-rprx-vision",
    )

    private val sampleConfigWithIp = ProxyServerConfig(
        id = "test-2",
        name = "Test Shadowsocks Server",
        address = "194.123.45.67",
        port = 8388,
        protocol = ProxyProtocol.SHADOWSOCKS,
        password = "secretpassword",
        method = "2022-blake3-aes-128-gcm",
    )

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testTunInboundContainsRequiredProperties() {
        val settings = AppSettings()
        val jsonString = SingBoxConfigBuilder.build(sampleConfigWithDomain, settings)

        val root = parseJson(jsonString)
        val inbounds = root["inbounds"] as List<Map<String, Any>>
        assertEquals(1, inbounds.size)

        val tun = inbounds[0]
        assertEquals("tun", tun["type"])
        assertEquals("gvisor", tun["stack"])
        assertEquals(true, tun["auto_route"])
        assertEquals(true, tun["strict_route"])
        assertEquals(true, tun["sniff"])
        assertEquals(true, tun["sniff_override_destination"])
        assertEquals(1500.0, (tun["mtu"] as Number).toDouble(), 0.001)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testPackageFilteringNeverEmptyAndMutuallyExclusive() {
        val settings = AppSettings()

        // 1. Both null: neither include_package nor exclude_package
        val jsonNoFilter = SingBoxConfigBuilder.build(
            sampleConfigWithDomain,
            settings,
            enabledApps = null,
            excludedApps = null,
        )
        val tunNoFilter = (parseJson(jsonNoFilter)["inbounds"] as List<Map<String, Any>>)[0]
        assertFalse(tunNoFilter.containsKey("include_package"))
        assertFalse(tunNoFilter.containsKey("exclude_package"))

        // 2. Empty lists: neither include_package nor exclude_package
        val jsonEmptyFilter = SingBoxConfigBuilder.build(
            sampleConfigWithDomain,
            settings,
            enabledApps = emptyList(),
            excludedApps = emptyList(),
        )
        val tunEmptyFilter = (parseJson(jsonEmptyFilter)["inbounds"] as List<Map<String, Any>>)[0]
        assertFalse(tunEmptyFilter.containsKey("include_package"))
        assertFalse(tunEmptyFilter.containsKey("exclude_package"))

        // 3. enabledApps provided: only include_package
        val jsonInclude = SingBoxConfigBuilder.build(
            sampleConfigWithDomain,
            settings,
            enabledApps = listOf("com.google.android.youtube", "org.telegram.messenger"),
            excludedApps = listOf("com.android.chrome"),
        )
        val tunInclude = (parseJson(jsonInclude)["inbounds"] as List<Map<String, Any>>)[0]
        assertTrue(tunInclude.containsKey("include_package"))
        assertFalse(tunInclude.containsKey("exclude_package"))
        assertEquals(2, (tunInclude["include_package"] as List<*>).size)

        // 4. excludedApps provided alone: only exclude_package
        val jsonExclude = SingBoxConfigBuilder.build(
            sampleConfigWithDomain,
            settings,
            enabledApps = null,
            excludedApps = listOf("com.bank.app"),
        )
        val tunExclude = (parseJson(jsonExclude)["inbounds"] as List<Map<String, Any>>)[0]
        assertFalse(tunExclude.containsKey("include_package"))
        assertTrue(tunExclude.containsKey("exclude_package"))
        assertEquals(1, (tunExclude["exclude_package"] as List<*>).size)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testDnsRulesProxyDomainDirectBeforeFakeDns() {
        val settings = AppSettings(fakeDns = true)
        val jsonString = SingBoxConfigBuilder.build(sampleConfigWithDomain, settings)

        val root = parseJson(jsonString)
        val dns = root["dns"] as Map<String, Any>
        val rules = dns["rules"] as List<Map<String, Any>>

        // First rule should resolve proxy domain via direct-dns
        val firstRule = rules[0]
        assertEquals("direct-dns", firstRule["server"])
        val domainList = firstRule["domain"] as List<String>
        assertEquals("vpn.flowvpn.example.com", domainList[0])

        // Ensure fakeip rule is present later in rules
        var foundFakeDns = false
        var fakeDnsIndex = -1
        for (i in rules.indices) {
            val rule = rules[i]
            if (rule["server"] == "fakeip-dns") {
                foundFakeDns = true
                fakeDnsIndex = i
                break
            }
        }
        assertTrue("fakeip-dns rule must be present", foundFakeDns)
        assertTrue("proxy domain rule must come BEFORE fakeip-dns", 0 < fakeDnsIndex)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testRouteRulesProxyDirectAndDnsOut() {
        val settings = AppSettings(bypassLan = true)
        val jsonDomain = SingBoxConfigBuilder.build(sampleConfigWithDomain, settings)
        val rootDomain = parseJson(jsonDomain)
        val rulesDomain = (rootDomain["route"] as Map<String, Any>)["rules"] as List<Map<String, Any>>

        var foundDnsProtoOut = false
        var foundDnsPortOut = false
        var foundProxyDomainDirect = false
        var foundBypassLan = false

        for (rule in rulesDomain) {
            if (rule["protocol"] == "dns" && rule["outbound"] == "dns-out") {
                foundDnsProtoOut = true
            }
            if ((rule["port"] as? List<*>)?.firstOrNull() == 53.0 && rule["outbound"] == "dns-out") {
                foundDnsPortOut = true
            }
            if ((rule["domain"] as? List<*>)?.firstOrNull() == "vpn.flowvpn.example.com" &&
                rule["outbound"] == "direct") {
                foundProxyDomainDirect = true
            }
            if (rule["ip_is_private"] == true && rule["outbound"] == "direct") {
                foundBypassLan = true
            }
        }

        assertTrue("DNS protocol routed to dns-out", foundDnsProtoOut)
        assertTrue("Port 53 routed to dns-out", foundDnsPortOut)
        assertTrue("Proxy domain routed to direct", foundProxyDomainDirect)
        assertTrue("Bypass LAN ip_is_private routed to direct", foundBypassLan)

        // Test with IP server
        val jsonIp = SingBoxConfigBuilder.build(sampleConfigWithIp, settings)
        val rootIp = parseJson(jsonIp)
        val rulesIp = (rootIp["route"] as Map<String, Any>)["rules"] as List<Map<String, Any>>
        var foundProxyIpDirect = false
        for (rule in rulesIp) {
            if ((rule["ip_cidr"] as? List<*>)?.firstOrNull() == "194.123.45.67/32" &&
                rule["outbound"] == "direct") {
                foundProxyIpDirect = true
            }
        }
        assertTrue("Proxy IP routed to direct", foundProxyIpDirect)
    }
}
