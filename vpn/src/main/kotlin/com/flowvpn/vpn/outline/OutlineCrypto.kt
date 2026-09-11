package com.flowvpn.vpn.outline

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Криптографические примитивы для OutlineBridge:
 * - Парсинг Outline Prefix из query-параметров URL / ссылок ss://
 * - Деривация мастер-ключа через EVP_BytesToKey (MD5) по спецификации Shadowsocks
 * - Деривация сессионных подключей через HKDF-SHA1 (SIP007 AEAD)
 * - Потоковое шифрование/дешифрование ChaCha20-Poly1305 и AES-GCM через BouncyCastle
 */
object OutlineCrypto {

    private val secureRandom = SecureRandom()
    private val SUBKEY_INFO = "ss-subkey".toByteArray(Charsets.UTF_8)

    /**
     * Парсинг префикса Outline в массив байтов.
     *
     * Поддерживает форматы:
     * 1. URL percent-encoded строки с префиксом TLS:
     *    `"%16%03%01%00%C2%A8%01%01"` -> 7 байт `[0x16, 0x03, 0x01, 0x00, 0xa8, 0x01, 0x01]`
     * 2. Raw hex percent-encoded: `"%16%03%01%00%a8%01%01"`
     * 3. Уже декодированные бинарные строки (Latin-1 / Unicode code points <= 255)
     * 4. Текстовые префиксы, например `"POST / HTTP/1.1\r\n"`
     */
    fun parsePrefix(prefixStr: String): ByteArray {
        if (prefixStr.isEmpty()) return ByteArray(0)

        // 1. Если содержит '%', обрабатываем percent-encoding
        if (prefixStr.contains("%")) {
            // Пробуем стандартный UTF-8 URLDecoder (для UTF-8 percent-encoded последовательностей, напр. %C2%A8)
            try {
                val decodedUtf8 = URLDecoder.decode(prefixStr, "UTF-8")
                if (decodedUtf8.isNotEmpty() && decodedUtf8.all { it.code <= 255 }) {
                    val bytes = ByteArray(decodedUtf8.length)
                    for (i in decodedUtf8.indices) {
                        bytes[i] = decodedUtf8[i].code.toByte()
                    }
                    return bytes
                }
            } catch (_: Exception) {}

            // Побайтовый парсинг %XX на случай нестандартного или сырого hex
            try {
                val out = ByteArrayOutputStream()
                var i = 0
                while (i < prefixStr.length) {
                    val c = prefixStr[i]
                    if (c == '%' && i + 2 < prefixStr.length) {
                        val hex = prefixStr.substring(i + 1, i + 3)
                        val b = hex.toIntOrNull(16)
                        if (b != null) {
                            out.write(b)
                            i += 3
                            continue
                        }
                    }
                    out.write(c.code and 0xFF)
                    i++
                }
                val result = out.toByteArray()
                if (result.isNotEmpty()) return result
            } catch (_: Exception) {}
        }

        // 2. Если строка уже была декодирована, и все символы в диапазоне байта (0..255),
        // сохраняем их посимвольные байтовые значения (Latin-1/Binary)
        if (prefixStr.all { it.code <= 255 }) {
            val bytes = ByteArray(prefixStr.length)
            for (i in prefixStr.indices) {
                bytes[i] = prefixStr[i].code.toByte()
            }
            return bytes
        }

        // 3. Fallback: UTF-8 байты строки
        return prefixStr.toByteArray(Charsets.UTF_8)
    }

    /**
     * Деривация мастер-ключа через EVP_BytesToKey (MD5).
     *
     * Shadowsocks использует OpenSSL EVP_BytesToKey с хешем MD5 и 1 итерацией,
     * без соли, для превращения строкового пароля в байтовый ключ заданной длины.
     */
    fun evpBytesToKey(password: String, keyLen: Int): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val result = ByteArray(keyLen)
        var prev: ByteArray? = null
        var written = 0

        while (written < keyLen) {
            md.reset()
            if (prev != null) {
                md.update(prev)
            }
            md.update(passwordBytes)
            val digest = md.digest()
            val toCopy = minOf(digest.size, keyLen - written)
            System.arraycopy(digest, 0, result, written, toCopy)
            written += toCopy
            prev = digest
        }
        return result
    }

    /**
     * Деривация сессионного подключа через HKDF-SHA1 (Shadowsocks SIP007).
     *
     * prk = HMAC-SHA1(salt, masterKey)
     * subkey = HMAC-SHA1(prk, "ss-subkey" || 0x01)
     */
    fun hkdfSha1(ikm: ByteArray, salt: ByteArray, info: ByteArray = SUBKEY_INFO, length: Int): ByteArray {
        val hmacSha1 = "HmacSHA1"
        val prkMac = Mac.getInstance(hmacSha1)
        prkMac.init(SecretKeySpec(salt, hmacSha1))
        val prk = prkMac.doFinal(ikm)

        val okm = ByteArray(length)
        val tMac = Mac.getInstance(hmacSha1)
        tMac.init(SecretKeySpec(prk, hmacSha1))
        var t = ByteArray(0)
        var counter = 1
        var written = 0

        while (written < length) {
            tMac.reset()
            tMac.update(t)
            tMac.update(info)
            tMac.update(counter.toByte())
            t = tMac.doFinal()
            val toCopy = minOf(t.size, length - written)
            System.arraycopy(t, 0, okm, written, toCopy)
            written += toCopy
            counter++
        }
        return okm
    }

    /**
     * Создает 12-байтный Shadowsocks AEAD nonce из 64-битного счетчика.
     * Счетчик записывается в формате little-endian (8 байт), дополненный 4 нулевыми байтами.
     */
    fun createNonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        var c = counter
        for (i in 0..7) {
            nonce[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        return nonce
    }

    /**
     * Генерирует криптографическую соль Shadowsocks заданной длины,
     * внедряя в ее начало Outline Prefix (если задан).
     */
    fun generateSalt(saltSize: Int, prefix: ByteArray?): ByteArray {
        val salt = ByteArray(saltSize)
        val prefixLen = prefix?.size ?: 0
        if (prefix != null && prefixLen > 0) {
            val copyLen = minOf(prefixLen, saltSize)
            System.arraycopy(prefix, 0, salt, 0, copyLen)
            if (copyLen < saltSize) {
                val remaining = ByteArray(saltSize - copyLen)
                secureRandom.nextBytes(remaining)
                System.arraycopy(remaining, 0, salt, copyLen, remaining.size)
            }
        } else {
            secureRandom.nextBytes(salt)
        }
        return salt
    }

    /**
     * Создает AEAD-шифр для указанного метода Shadowsocks.
     */
    fun createCipher(methodName: String): OutlineAeadCipher {
        return when (methodName.trim().lowercase()) {
            "chacha20-ietf-poly1305", "chacha20-poly1305" -> ChaCha20Poly1305Cipher()
            "aes-256-gcm" -> AesGcmCipher(keySize = 32, saltSize = 32)
            "aes-128-gcm" -> AesGcmCipher(keySize = 16, saltSize = 16)
            else -> ChaCha20Poly1305Cipher() // Default fallback
        }
    }
}

/**
 * Интерфейс для AEAD-шифров Shadowsocks.
 */
interface OutlineAeadCipher {
    val keySize: Int
    val saltSize: Int
    val tagSize: Int get() = 16

    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray
}

/**
 * Реализация ChaCha20-Poly1305 через BouncyCastle.
 */
class ChaCha20Poly1305Cipher : OutlineAeadCipher {
    override val keySize: Int = 32
    override val saltSize: Int = 32

    override fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        val params = ParametersWithIV(KeyParameter(key), nonce)
        cipher.init(true, params)
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    override fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        val params = ParametersWithIV(KeyParameter(key), nonce)
        cipher.init(false, params)
        val out = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        val len = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }
}

/**
 * Реализация AES-GCM через BouncyCastle GCMBlockCipher(AESEngine()).
 */
class AesGcmCipher(
    override val keySize: Int,
    override val saltSize: Int,
) : OutlineAeadCipher {

    override fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        val params = AEADParameters(KeyParameter(key), 128, nonce)
        cipher.init(true, params)
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    override fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        val params = AEADParameters(KeyParameter(key), 128, nonce)
        cipher.init(false, params)
        val out = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        val len = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }
}
