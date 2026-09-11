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
        assertEquals(false, tun["strict_route"])
        assertFalse(tun.containsKey("sniff"))
        assertFalse(tun.containsKey("sniff_override_destination"))
        assertEquals(1400.0, (tun["mtu"] as Number).toDouble(), 0.001)

        val outbounds = root["outbounds"] as List<Map<String, Any>>
        val vlessOutbound = outbounds.find { it["tag"] == "proxy" }
        assertEquals("xudp", vlessOutbound?.get("packet_encoding"))
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

        var foundSniffAction = false
        var foundDnsProtoOut = false
        var foundDnsPortOut = false
        var foundProxyDomainDirect = false
        var foundBypassLan = false

        var foundPort853Block = false
        var foundQuicBlock = false

        for (rule in rulesDomain) {
            if (rule["action"] == "sniff") {
                foundSniffAction = true
            }
            if (rule["protocol"] == "dns" && rule["action"] == "hijack-dns") {
                foundDnsProtoOut = true
            }
            if ((rule["port"] as? List<*>)?.firstOrNull() == 53.0 && rule["action"] == "hijack-dns") {
                foundDnsPortOut = true
            }
            if ((rule["port"] as? List<*>)?.firstOrNull() == 853.0 && rule["action"] == "reject") {
                foundPort853Block = true
            }
            if ((rule["port"] as? List<*>)?.firstOrNull() == 443.0 && rule["network"] == "udp" && rule["action"] == "reject") {
                foundQuicBlock = true
            }
            if ((rule["domain"] as? List<*>)?.firstOrNull() == "vpn.flowvpn.example.com" &&
                rule["outbound"] == "direct") {
                foundProxyDomainDirect = true
            }
            if (rule["ip_is_private"] == true && rule["outbound"] == "direct") {
                foundBypassLan = true
            }
        }

        assertTrue("Sniff action rule must be present", foundSniffAction)
        assertTrue("DNS protocol routed to hijack-dns", foundDnsProtoOut)
        assertTrue("Port 53 routed to hijack-dns", foundDnsPortOut)
        assertTrue("Port 853 rejected", foundPort853Block)
        assertTrue("QUIC UDP 443 rejected", foundQuicBlock)
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

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testWarpChainingOutboundAndRoute() {
        val warp = com.flowvpn.core.model.WarpConfig(
            accountId = "test-warp-acc",
            accessToken = "token123",
            accountType = "warp_plus",
            privateKey = "priv123",
            peerPublicKey = "pub123",
            warpMode = com.flowvpn.core.model.WarpMode.WIREGUARD
        )
        val settings = AppSettings(
            enableWarpChaining = true,
            warpConfigJson = warp.toJson(),
            warpMode = com.flowvpn.core.model.WarpMode.WIREGUARD
        )

        val jsonString = SingBoxConfigBuilder.build(sampleConfigWithDomain, settings)
        val root = parseJson(jsonString)

        val endpoints = root["endpoints"] as? List<Map<String, Any>>
        val outbounds = root["outbounds"] as List<Map<String, Any>>
        val warpItem = endpoints?.find { it["tag"] == "warp" } ?: outbounds.find { it["tag"] == "warp" }
        assertTrue("Warp outbound/endpoint must be present", warpItem != null)
        assertEquals("wireguard", warpItem!!["type"])
        assertEquals("proxy", warpItem["detour"])

        val route = root["route"] as Map<String, Any>
        assertEquals("warp", route["final"])

        val dns = root["dns"] as Map<String, Any>
        val dnsServers = dns["servers"] as List<Map<String, Any>>
        val remoteDns = dnsServers.find { it["tag"] == "remote-dns" }
        assertEquals("warp", remoteDns!!["detour"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testWarpStandaloneWhenChainingDisabled() {
        val warpServer = ProxyServerConfig(
            id = "warp-test-acc",
            name = "Cloudflare WARP",
            address = "162.159.192.1",
            port = 2408,
            protocol = ProxyProtocol.WIREGUARD,
            privateKey = "privKey123",
            peerPublicKey = "pubKey123",
            localAddresses = listOf("172.16.0.2/32", "2606:4700:110:8::1/128"),
            reserved = listOf(1, 2, 3),
        )
        val settings = AppSettings(enableWarpChaining = false)

        val jsonString = SingBoxConfigBuilder.build(warpServer, settings, underlyingProxy = null)
        val root = parseJson(jsonString)

        val outbounds = root["outbounds"] as List<Map<String, Any>>
        val proxyOutbound = outbounds.find { it["tag"] == "proxy" }
        assertTrue("Proxy outbound must be present", proxyOutbound != null)
        assertEquals("wireguard", proxyOutbound!!["type"])
        assertEquals("162.159.192.1", proxyOutbound["server"])
        assertFalse("Standalone WARP must NOT have detour", proxyOutbound.containsKey("detour"))

        val warpOutbound = outbounds.find { it["tag"] == "warp" }
        assertTrue("Standalone WARP must NOT have separate warp outbound", warpOutbound == null)

        val route = root["route"] as Map<String, Any>
        assertEquals("proxy", route["final"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testWarpChainingWithUnderlyingProxyWhenEnabled() {
        val warpServer = ProxyServerConfig(
            id = "warp-test-acc",
            name = "Cloudflare WARP",
            address = "162.159.192.1",
            port = 2408,
            protocol = ProxyProtocol.WIREGUARD,
            privateKey = "privKey123",
            peerPublicKey = "pubKey123",
        )
        val settings = AppSettings(enableWarpChaining = true, warpMode = com.flowvpn.core.model.WarpMode.WIREGUARD)

        val jsonString = SingBoxConfigBuilder.build(
            config = warpServer,
            settings = settings,
            underlyingProxy = sampleConfigWithDomain
        )
        val root = parseJson(jsonString)

        val outbounds = root["outbounds"] as List<Map<String, Any>>
        val primaryOutbound = outbounds.find { it["tag"] == "proxy" }
        assertTrue("Primary outbound must be underlying proxy", primaryOutbound != null)
        assertEquals("vless", primaryOutbound!!["type"])

        val endpoints = root["endpoints"] as? List<Map<String, Any>>
        val warpOutbound = endpoints?.find { it["tag"] == "warp" } ?: outbounds.find { it["tag"] == "warp" }
        assertTrue("Warp outbound must be present when chained", warpOutbound != null)
        assertEquals("wireguard", warpOutbound!!["type"])
        assertEquals("proxy", warpOutbound["detour"])

        val route = root["route"] as Map<String, Any>
        assertEquals("warp", route["final"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testWarpChainingMasqueH2() {
        val warp = com.flowvpn.core.model.WarpConfig(
            accountId = "test-warp-acc",
            accessToken = "token123",
            accountType = "warp_plus",
            privateKey = "priv123",
            peerPublicKey = "pub123",
            endpointHost = "162.159.192.1",
            endpointPort = 443,
            warpMode = com.flowvpn.core.model.WarpMode.MASQUE_H2
        )
        val settings = AppSettings(
            enableWarpChaining = true,
            warpConfigJson = warp.toJson(),
            warpMode = com.flowvpn.core.model.WarpMode.MASQUE_H2
        )

        val jsonString = SingBoxConfigBuilder.build(sampleConfigWithDomain, settings)
        val root = parseJson(jsonString)

        val outbounds = root["outbounds"] as List<Map<String, Any>>
        val warpOutbound = outbounds.find { it["tag"] == "warp" }
        assertTrue("Warp outbound must be present", warpOutbound != null)
        assertEquals("masque", warpOutbound!!["type"])
        assertEquals(true, warpOutbound["use_http2"])
        assertEquals("proxy", warpOutbound["detour"])
        assertEquals("162.159.198.2", warpOutbound["address"])

        val profile = warpOutbound["profile"] as Map<String, Any>
        assertEquals("proxy", profile["detour"])
        assertFalse(profile.containsKey("id"))
        assertFalse(profile.containsKey("auth_token"))

        val tls = warpOutbound["tls"] as Map<String, Any>
        assertEquals("consumer-masque.cloudflareclient.com", tls["server_name"])
        assertEquals(true, tls["insecure"])
        assertEquals(true, tls["fragment"])
        assertEquals(true, tls["record_fragment"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun testStandaloneMasqueServer() {
        val masqueServer = ProxyServerConfig(
            id = "masque-test-node",
            name = "Cloudflare MASQUE",
            address = "162.159.192.1",
            port = 443,
            protocol = ProxyProtocol.MASQUE,
        )
        val settings = AppSettings(
            enableWarpChaining = false,
            warpMode = com.flowvpn.core.model.WarpMode.MASQUE_H2
        )

        val jsonString = SingBoxConfigBuilder.build(masqueServer, settings, underlyingProxy = null)
        val root = parseJson(jsonString)

        val outbounds = root["outbounds"] as List<Map<String, Any>>
        val proxyOutbound = outbounds.find { it["tag"] == "proxy" }
        assertTrue("Proxy outbound must be present", proxyOutbound != null)
        assertEquals("masque", proxyOutbound!!["type"])
        assertEquals("162.159.198.2", proxyOutbound["address"])
        assertEquals(443, (proxyOutbound["port"] as Number).toInt())
        assertEquals(true, proxyOutbound["use_http2"])

        val tls = proxyOutbound["tls"] as Map<String, Any>
        assertEquals("consumer-masque.cloudflareclient.com", tls["server_name"])
        assertEquals(true, tls["insecure"])
        assertEquals(true, tls["fragment"])
        assertEquals(true, tls["record_fragment"])
    }
}
