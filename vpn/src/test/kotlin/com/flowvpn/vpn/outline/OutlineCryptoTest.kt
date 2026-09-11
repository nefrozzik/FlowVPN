package com.flowvpn.vpn.outline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineCryptoTest {

    @Test
    fun testParsePrefixTlsClientHello() {
        val rawPrefix = "%16%03%01%00%C2%A8%01%01"
        val parsed = OutlineCrypto.parsePrefix(rawPrefix)
        val expected = byteArrayOf(
            0x16.toByte(),
            0x03.toByte(),
            0x01.toByte(),
            0x00.toByte(),
            0xa8.toByte(),
            0x01.toByte(),
            0x01.toByte()
        )
        assertArrayEquals(expected, parsed)
    }

    @Test
    fun testParsePrefixHttp() {
        val httpPrefix = "POST / HTTP/1.1\r\n"
        val parsed = OutlineCrypto.parsePrefix(httpPrefix)
        assertArrayEquals(httpPrefix.toByteArray(Charsets.UTF_8), parsed)
    }

    @Test
    fun testEvpBytesToKey() {
        val password = "ThTTnNAqPrv5zdMXgNV1XtHVgMiJKBztKZQ6e7sRenasXidrrANcM1r7C1bfcqXQaezJuwB654dukd9cLvTT8NGptkA5kFxg"
        val key = OutlineCrypto.evpBytesToKey(password, 32)
        assertEquals(32, key.size)

        // EVP_BytesToKey with 1 iteration MD5:
        // MD5(password) = d4...
        val md = java.security.MessageDigest.getInstance("MD5")
        val d1 = md.digest(password.toByteArray(Charsets.UTF_8))
        md.reset()
        md.update(d1)
        md.update(password.toByteArray(Charsets.UTF_8))
        val d2 = md.digest()

        val expected = ByteArray(32)
        System.arraycopy(d1, 0, expected, 0, 16)
        System.arraycopy(d2, 0, expected, 16, 16)
        assertArrayEquals(expected, key)
    }

    @Test
    fun testChaCha20Poly1305Roundtrip() {
        val cipher = OutlineCrypto.createCipher("chacha20-ietf-poly1305")
        val key = ByteArray(32) { (it + 1).toByte() }
        val nonce = OutlineCrypto.createNonce(0)
        val plaintext = "FlowVPN Outline Bridge Test".toByteArray(Charsets.UTF_8)

        val encrypted = cipher.encrypt(key, nonce, plaintext)
        assertEquals(plaintext.size + cipher.tagSize, encrypted.size)

        val decrypted = cipher.decrypt(key, nonce, encrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testAesGcmRoundtrip() {
        val cipher = OutlineCrypto.createCipher("aes-256-gcm")
        val key = ByteArray(32) { (it + 2).toByte() }
        val nonce = OutlineCrypto.createNonce(42)
        val plaintext = "Testing AES-256-GCM Shadowsocks chunk".toByteArray(Charsets.UTF_8)

        val encrypted = cipher.encrypt(key, nonce, plaintext)
        assertEquals(plaintext.size + cipher.tagSize, encrypted.size)

        val decrypted = cipher.decrypt(key, nonce, encrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testSaltGenerationWithPrefix() {
        val prefix = byteArrayOf(0x16, 0x03, 0x01)
        val salt = OutlineCrypto.generateSalt(32, prefix)
        assertEquals(32, salt.size)
        assertEquals(0x16.toByte(), salt[0])
        assertEquals(0x03.toByte(), salt[1])
        assertEquals(0x01.toByte(), salt[2])
    }
}
