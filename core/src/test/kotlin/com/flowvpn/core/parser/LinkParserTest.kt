package com.flowvpn.core.parser

import com.flowvpn.core.config.SingBoxConfigBuilder
import com.flowvpn.core.model.ProxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LinkParserTest {

    @Test
    fun testParseVlessReality() {
        val link = "vless://11111111-2222-3333-4444-555555555555@speed.example.com:443?type=tcp&security=reality&pbk=AbCdEf123456&sid=12345678&sni=yahoo.com&fp=chrome&flow=xtls-rprx-vision#Germany%20Reality"
        val server = LinkParser.parse(link)

        assertNotNull(server)
        assertEquals("Germany Reality", server?.name)
        assertEquals(ProxyProtocol.VLESS, server?.protocol)
        assertEquals("speed.example.com", server?.address)
        assertEquals(443, server?.port)
        assertEquals("11111111-2222-3333-4444-555555555555", server?.uuid)
        assertEquals("xtls-rprx-vision", server?.flow)
        assertEquals("AbCdEf123456", server?.tls?.realityPublicKey)
        assertEquals("12345678", server?.tls?.realityShortId)
        assertEquals("yahoo.com", server?.tls?.serverName)
        assertEquals("chrome", server?.tls?.utlsFingerprint)
    }

    @Test
    fun testParseVmessBase64Json() {
        val json = """
            {"v":"2","ps":"USA Server","add":"us.example.com","port":"8443","id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/vmess-ws","tls":"tls","sni":"cdn.example.com"}
        """.trimIndent()
        val base64 = Base64.getEncoder().encodeToString(json.toByteArray())
        val link = "vmess://$base64"

        val server = LinkParser.parse(link)
        assertNotNull(server)
        assertEquals("USA Server", server?.name)
        assertEquals(ProxyProtocol.VMESS, server?.protocol)
        assertEquals("us.example.com", server?.address)
        assertEquals(8443, server?.port)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", server?.uuid)
        assertEquals("ws", server?.transport?.type)
        assertEquals("/vmess-ws", server?.transport?.path)
        assertEquals("cdn.example.com", server?.tls?.serverName)
    }

    @Test
    fun testParseShadowsocksSip002() {
        val link = "ss://chacha20-ietf-poly1305:secretpass@ss.example.com:8388#Japan%20SS"
        val server = LinkParser.parse(link)

        assertNotNull(server)
        assertEquals("Japan SS", server?.name)
        assertEquals(ProxyProtocol.SHADOWSOCKS, server?.protocol)
        assertEquals("ss.example.com", server?.address)
        assertEquals(8388, server?.port)
        assertEquals("chacha20-ietf-poly1305", server?.method)
        assertEquals("secretpass", server?.password)
    }

    @Test
    fun testParseTrojan() {
        val link = "trojan://mypassword@tr.example.com:443?security=tls&sni=tr.example.com&type=ws&path=%2Ftrojan-ws#Trojan%20Node"
        val server = LinkParser.parse(link)

        assertNotNull(server)
        assertEquals("Trojan Node", server?.name)
        assertEquals(ProxyProtocol.TROJAN, server?.protocol)
        assertEquals("tr.example.com", server?.address)
        assertEquals(443, server?.port)
        assertEquals("mypassword", server?.password)
        assertEquals("ws", server?.transport?.type)
        assertEquals("/trojan-ws", server?.transport?.path)
    }

    @Test
    fun testParseHysteria2() {
        val link = "hy2://password123@hy2.example.com:8443?obfs=salamander&obfs-password=obfspass&sni=hy2.example.com&insecure=1#Hysteria2%20UDP"
        val server = LinkParser.parse(link)

        assertNotNull(server)
        assertEquals("Hysteria2 UDP", server?.name)
        assertEquals(ProxyProtocol.HYSTERIA2, server?.protocol)
        assertEquals("hy2.example.com", server?.address)
        assertEquals(8443, server?.port)
        assertEquals("password123", server?.password)
        assertEquals("obfspass", server?.obfsPassword)
        assertTrue(server?.tls?.insecure == true)
    }

    @Test
    fun testParseTuic() {
        val link = "tuic://user-uuid:user-password@tuic.example.com:8443?congestion_control=bbr&alpn=h3&sni=tuic.example.com#TUIC%20Node"
        val server = LinkParser.parse(link)

        assertNotNull(server)
        assertEquals("TUIC Node", server?.name)
        assertEquals(ProxyProtocol.TUIC, server?.protocol)
        assertEquals("tuic.example.com", server?.address)
        assertEquals(8443, server?.port)
        assertEquals("user-uuid", server?.uuid)
        assertEquals("user-password", server?.password)
        assertEquals("bbr", server?.congestionControl)
    }

    @Test
    fun testParseWireGuard() {
        val link = "wireguard://YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=@wg.example.com:51820?publickey=YmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmI=&address=10.0.0.2/32&mtu=1420#WireGuard%20Node"
        val server = LinkParser.parse(link)

        assertNotNull(server)
        assertEquals("WireGuard Node", server?.name)
        assertEquals(ProxyProtocol.WIREGUARD, server?.protocol)
        assertEquals("wg.example.com", server?.address)
        assertEquals(51820, server?.port)
        assertEquals("YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=", server?.privateKey)
        assertEquals("YmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmI=", server?.peerPublicKey)
        assertEquals(listOf("10.0.0.2/32"), server?.localAddresses)
        assertEquals(1420, server?.wireguardMtu)
    }

    @Test
    fun testSubscriptionDecoderBase64() {
        val links = """
            vless://uuid1@server1.com:443#Server1
            trojan://pass2@server2.com:443#Server2
        """.trimIndent()
        val base64 = Base64.getEncoder().encodeToString(links.toByteArray())

        val servers = SubscriptionDecoder.decode(base64)
        assertEquals(2, servers.size)
        assertEquals("Server1", servers[0].name)
        assertEquals("Server2", servers[1].name)
    }

    @Test
    fun testClashProfileParser() {
        val yaml = """
            proxies:
              - name: "Clash VLESS Reality"
                type: vless
                server: 1.2.3.4
                port: 443
                uuid: "11111111-2222-3333-4444-555555555555"
                tls: true
                servername: yahoo.com
                flow: xtls-rprx-vision
                client-fingerprint: chrome
                reality-opts:
                  public-key: "AbCdEf123456"
                  short-id: "12345678"
              - name: "Clash Shadowsocks"
                type: ss
                server: 5.6.7.8
                port: 8388
                cipher: chacha20-ietf-poly1305
                password: "mypassword"
        """.trimIndent()

        val servers = ClashProfileParser.parse(yaml)
        assertEquals(2, servers.size)

        val vless = servers[0]
        assertEquals("Clash VLESS Reality", vless.name)
        assertEquals(ProxyProtocol.VLESS, vless.protocol)
        assertEquals("1.2.3.4", vless.address)
        assertEquals(443, vless.port)
        assertEquals("11111111-2222-3333-4444-555555555555", vless.uuid)
        assertEquals("AbCdEf123456", vless.tls?.realityPublicKey)
        assertEquals("yahoo.com", vless.tls?.serverName)

        val ss = servers[1]
        assertEquals("Clash Shadowsocks", ss.name)
        assertEquals(ProxyProtocol.SHADOWSOCKS, ss.protocol)
        assertEquals("chacha20-ietf-poly1305", ss.method)
        assertEquals("mypassword", ss.password)
    }

    @Test
    fun testSingBoxProfileParser() {
        val json = """
            {
              "outbounds": [
                {
                  "type": "vless",
                  "tag": "SB VLESS",
                  "server": "sb.example.com",
                  "server_port": 443,
                  "uuid": "22222222-3333-4444-5555-666666666666",
                  "tls": {
                    "enabled": true,
                    "server_name": "sb.example.com"
                  }
                },
                {
                  "type": "direct",
                  "tag": "direct"
                }
              ]
            }
        """.trimIndent()

        val servers = SingBoxProfileParser.parse(json)
        assertEquals(1, servers.size)
        assertEquals("SB VLESS", servers[0].name)
        assertEquals(ProxyProtocol.VLESS, servers[0].protocol)
        assertEquals("sb.example.com", servers[0].address)
        assertEquals(443, servers[0].port)
        assertEquals("22222222-3333-4444-5555-666666666666", servers[0].uuid)
    }

    @Test
    fun testSingBoxConfigGeneration() {
        val link = "vless://uuid-test@example.com:443?type=ws&path=/ws&security=tls&sni=example.com#TestNode"
        val server = LinkParser.parse(link)
        assertNotNull(server)

        val configJson = SingBoxConfigBuilder.build(server!!)
        assertTrue(configJson.contains("\"type\": \"vless\""))
        assertTrue(configJson.contains("\"server\": \"example.com\""))
        assertTrue(configJson.contains("\"type\": \"tun\""))
        assertTrue(configJson.contains("\"auto_route\": true"))
        assertTrue(configJson.contains("\"remote-dns\""))
    }

    @Test
    fun testParseVlessRawRealityUniversal() {
        val link = "vless://633f424e-2b11-48da-a6b3-a849dd71456f@45.12.75.242:26424?encryption=none&fp=chrome&pbk=l8AubqcxQO-HRFJy4pZL1vbLOo-eXit69s-XulSELE0&security=reality&sid=23103e1e&sni=ya.ru&type=raw#Beget-1"
        val server = LinkParser.parse(link)
        assertNotNull(server)
        assertEquals("45.12.75.242", server?.address)
        assertEquals(26424, server?.port)
        assertEquals(null, server?.transport) // type=raw must NOT create a transport block

        val configJson = SingBoxConfigBuilder.build(server!!)
        assertTrue(configJson.contains("\"reality\""))
        assertFalse(configJson.contains("\"type\": \"raw\""))
    }

    @Test
    fun testParseOpenFluxYandex() {
        val link = "openflux://yandex?docUrl=https%3A%2F%2Fdocs.yandex.ru%2Fdocs%2Fview%3Fid%3Dtest123&port=10808#YandexDocs"
        val server = LinkParser.parse(link)
        assertNotNull(server)
        assertEquals("YandexDocs", server?.name)
        assertEquals(ProxyProtocol.OPENFLUX, server?.protocol)
        assertEquals("127.0.0.1", server?.address)
        assertEquals(10808, server?.port)
        assertEquals("yandex", server?.openfluxTransport)
        assertEquals("https://docs.yandex.ru/docs/view?id=test123", server?.openfluxDocUrl)

        val configJson = SingBoxConfigBuilder.build(server!!)
        assertTrue(configJson.contains("\"type\": \"socks\""))
        assertTrue(configJson.contains("doc.yandex.ru"))
        assertTrue(configJson.contains("127.0.0.0/8"))
    }

    @Test
    fun testParseSocks5() {
        val link = "socks5://user:secret@127.0.0.1:10808#LocalSocks"
        val server = LinkParser.parse(link)
        assertNotNull(server)
        assertEquals("LocalSocks", server?.name)
        assertEquals(ProxyProtocol.SOCKS5, server?.protocol)
        assertEquals("127.0.0.1", server?.address)
        assertEquals(10808, server?.port)
        assertEquals("secret", server?.password)
    }
}
