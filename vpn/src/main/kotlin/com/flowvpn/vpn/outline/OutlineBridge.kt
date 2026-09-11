package com.flowvpn.vpn.outline

import android.net.VpnService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Локальный in-process SOCKS5-мост для Outline с префиксами.
 *
 * Перехватывает соединения от sing-box через локальный порт (127.0.0.1),
 * упаковывает трафик в Shadowsocks AEAD с внедрением Outline Prefix в начало соли (salt),
 * защищает исходящие сокеты через [vpnService.protect], предотвращая сетевые петли,
 * и пересылает трафик на реальный Outline-сервер.
 *
 * Это обеспечивает 100% совместимость с DPI-обходом Outline (ТСПУ) без необходимости
 * модифицировать скомпилированное Go-ядро sing-box.
 */
class OutlineBridge(
    private val serverHost: String,
    private val serverPort: Int,
    private val method: String,
    private val password: String,
    private val prefix: String,
    private val vpnService: VpnService,
) {

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    private val cipher: OutlineAeadCipher = OutlineCrypto.createCipher(method)
    private val masterKey: ByteArray = OutlineCrypto.evpBytesToKey(password, cipher.keySize)
    private val parsedPrefix: ByteArray = OutlineCrypto.parsePrefix(prefix)

    @Volatile
    private var cachedAddress: InetAddress? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.w(throwable, "OutlineBridge: Исключение в корутине")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    val boundPort: Int
        get() = serverSocket?.localPort ?: 0

    /**
     * Запустить локальный SOCKS5 сервер на свободном порту 127.0.0.1.
     * @return номер выделенного порта
     */
    @Synchronized
    fun start(): Int {
        if (isRunning.get()) {
            return boundPort
        }

        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        ss.reuseAddress = true
        serverSocket = ss
        isRunning.set(true)

        val port = ss.localPort
        Timber.i("OutlineBridge: Запущен на 127.0.0.1:$port (prefixLen=${parsedPrefix.size}, cipher=$method)")

        scope.launch {
            acceptLoop(ss)
        }

        return port
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (isRunning.get() && !ss.isClosed) {
            try {
                val clientSocket = ss.accept()
                clientSocket.tcpNoDelay = true
                clientSocket.keepAlive = true
                activeSockets.add(clientSocket)

                scope.launch {
                    try {
                        handleSocksClient(clientSocket)
                    } catch (e: Exception) {
                        Timber.d("OutlineBridge: Ошибка сессии клиента: ${e.message}")
                    } finally {
                        activeSockets.remove(clientSocket)
                        runCatching { clientSocket.close() }
                    }
                }
            } catch (e: Exception) {
                if (!isRunning.get()) break
                Timber.w(e, "OutlineBridge: Ошибка accept")
            }
        }
    }

    /**
     * Обработка одного SOCKS5 подключения от sing-box.
     * Метод приостанавливает выполнение (suspend) до полного завершения двунаправленной ретрансляции данных,
     * предотвращая преждевременное закрытие клиентского сокета.
     */
    private suspend fun handleSocksClient(clientSocket: Socket) {
        var remoteSocket: Socket? = null
        try {
            clientSocket.soTimeout = 15000 // 15s на SOCKS5 рукопожатие
            val clientIn = DataInputStream(clientSocket.getInputStream())
            val clientOut = DataOutputStream(clientSocket.getOutputStream())

            // 1. SOCKS5 Greeting: VER (0x05) + NMETHODS + METHODS...
            val ver = clientIn.readUnsignedByte()
            if (ver != 0x05) {
                return
            }
            val nmethods = clientIn.readUnsignedByte()
            val methods = ByteArray(nmethods)
            clientIn.readFully(methods)

            // Ответ: VER 0x05, METHOD 0x00 (NO AUTH)
            clientOut.write(byteArrayOf(0x05, 0x00))
            clientOut.flush()

            // 2. SOCKS5 Request: VER (0x05) + CMD + RSV (0x00) + ATYP + DST.ADDR + DST.PORT
            val ver2 = clientIn.readUnsignedByte()
            val cmd = clientIn.readUnsignedByte()
            val rsv = clientIn.readUnsignedByte()

            if (cmd != 0x01) { // Поддерживаем только CONNECT (0x01)
                // 0x07 = Command not supported
                clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                return
            }

            val atyp = clientIn.readUnsignedByte()
            val targetAddrStream = ByteArrayOutputStream()
            targetAddrStream.write(atyp)

            when (atyp) {
                0x01 -> { // IPv4
                    val ip = ByteArray(4)
                    clientIn.readFully(ip)
                    targetAddrStream.write(ip)
                }
                0x03 -> { // Domain name: 1 байт длины + имя
                    val len = clientIn.readUnsignedByte()
                    val domainBytes = ByteArray(len)
                    clientIn.readFully(domainBytes)
                    targetAddrStream.write(len)
                    targetAddrStream.write(domainBytes)
                }
                0x04 -> { // IPv6
                    val ip6 = ByteArray(16)
                    clientIn.readFully(ip6)
                    targetAddrStream.write(ip6)
                }
                else -> {
                    // 0x08 = Address type not supported
                    clientOut.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    clientOut.flush()
                    return
                }
            }

            val portBytes = ByteArray(2)
            clientIn.readFully(portBytes)
            targetAddrStream.write(portBytes)
            val ssTargetAddress = targetAddrStream.toByteArray()

            // 3. Подключение к удаленному серверу Outline
            val remote = Socket()
            remote.tcpNoDelay = true
            remote.keepAlive = true
            // Защищаем сокет, чтобы трафик к серверу шел в обход VPN TUN
            vpnService.protect(remote)

            activeSockets.add(remote)
            remoteSocket = remote

            val targetAddr = resolveRemoteHost()
            remote.connect(InetSocketAddress(targetAddr, serverPort), 10000)

            // 4. Отправляем SOCKS5 Success клиенту
            clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()

            // Ожидаем первые байты полезной нагрузки клиента (например, TLS ClientHello или HTTP запрос).
            // В официальном Outline SDK (Jigsaw) адрес назначения и первый чанк клиента отправляются
            // вместе с солью в одном TCP-пакете (LazyWrite / Flush). Это критично для обхода ТСПУ/DPI,
            // так как DPI сверяет заявленный размер TLS Handshake в префиксе с размером пакета.
            clientSocket.soTimeout = 100 // 100ms на ожидание первых байт от sing-box
            val initialBuf = ByteArray(16383)
            var initialRead = 0
            try {
                initialRead = clientIn.read(initialBuf)
            } catch (_: Exception) {
                initialRead = 0
            }

            // Снимаем таймауты для фазы постоянной потоковой передачи
            clientSocket.soTimeout = 0
            remote.soTimeout = 0

            val remoteIn = DataInputStream(remote.getInputStream())
            val remoteOut = DataOutputStream(remote.getOutputStream())

            // 5. Инициализация исходящего Shadowsocks AEAD потока (Client -> Remote)
            val outboundSalt = OutlineCrypto.generateSalt(cipher.saltSize, parsedPrefix)
            val outboundSubkey = OutlineCrypto.hkdfSha1(masterKey, outboundSalt, length = cipher.keySize)

            var outboundCounter = 0L

            // Объединяем Shadowsocks адрес назначения и первые байты клиента в первый зашифрованный чанк
            val chunk0 = if (initialRead > 0) {
                val combined = ByteArray(ssTargetAddress.size + initialRead)
                System.arraycopy(ssTargetAddress, 0, combined, 0, ssTargetAddress.size)
                System.arraycopy(initialBuf, 0, combined, ssTargetAddress.size, initialRead)
                combined
            } else {
                ssTargetAddress
            }

            val chunk0LenBytes = byteArrayOf(
                (chunk0.size ushr 8).toByte(),
                (chunk0.size and 0xFF).toByte()
            )
            val encChunk0Len = cipher.encrypt(
                outboundSubkey,
                OutlineCrypto.createNonce(outboundCounter++),
                chunk0LenBytes
            )
            val encChunk0Payload = cipher.encrypt(
                outboundSubkey,
                OutlineCrypto.createNonce(outboundCounter++),
                chunk0
            )

            // Отправляем соль и первый чанк единым блоком (один TCP-пакет)
            val firstPacket = ByteArray(outboundSalt.size + encChunk0Len.size + encChunk0Payload.size)
            var off = 0
            System.arraycopy(outboundSalt, 0, firstPacket, off, outboundSalt.size)
            off += outboundSalt.size
            System.arraycopy(encChunk0Len, 0, firstPacket, off, encChunk0Len.size)
            off += encChunk0Len.size
            System.arraycopy(encChunk0Payload, 0, firstPacket, off, encChunk0Payload.size)

            remoteOut.write(firstPacket)
            remoteOut.flush()

            // 6. Двунаправленная потоковая ретрансляция
            try {
                coroutineScope {
                    // Поток A: Client -> Remote (чтение из SOCKS5, шифрование в SS-чанки, отправка)
                    val forwardJob = launch(Dispatchers.IO) {
                        val buf = ByteArray(16383) // Максимальный размер чанка Shadowsocks AEAD
                        try {
                            while (isRunning.get() && isActive) {
                                val read = clientIn.read(buf)
                                if (read == -1) break
                                if (read == 0) continue

                                val chunkLenBytes = byteArrayOf(
                                    (read ushr 8).toByte(),
                                    (read and 0xFF).toByte()
                                )
                                val encLen = cipher.encrypt(
                                    outboundSubkey,
                                    OutlineCrypto.createNonce(outboundCounter++),
                                    chunkLenBytes
                                )
                                val payloadBytes = if (read == buf.size) buf else buf.copyOf(read)
                                val encPayload = cipher.encrypt(
                                    outboundSubkey,
                                    OutlineCrypto.createNonce(outboundCounter++),
                                    payloadBytes
                                )

                                remoteOut.write(encLen)
                                remoteOut.write(encPayload)
                                remoteOut.flush()
                            }
                        } catch (_: Exception) {
                        } finally {
                            runCatching { remote.shutdownOutput() }
                        }
                    }

                    // Поток B: Remote -> Client (чтение SS-чанков от сервера, расшифровка, отправка в SOCKS5)
                    val backwardJob = launch(Dispatchers.IO) {
                        try {
                            // Читаем соль сервера
                            val inboundSalt = ByteArray(cipher.saltSize)
                            remoteIn.readFully(inboundSalt)
                            val inboundSubkey = OutlineCrypto.hkdfSha1(masterKey, inboundSalt, length = cipher.keySize)
                            var inboundCounter = 0L

                            val encLenBuf = ByteArray(2 + cipher.tagSize)
                            while (isRunning.get() && isActive) {
                                remoteIn.readFully(encLenBuf)
                                val lenBytes = cipher.decrypt(
                                    inboundSubkey,
                                    OutlineCrypto.createNonce(inboundCounter++),
                                    encLenBuf
                                )
                                val payloadLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
                                if (payloadLen <= 0 || payloadLen > 16383) {
                                    break
                                }

                                val encPayloadBuf = ByteArray(payloadLen + cipher.tagSize)
                                remoteIn.readFully(encPayloadBuf)
                                val plaintext = cipher.decrypt(
                                    inboundSubkey,
                                    OutlineCrypto.createNonce(inboundCounter++),
                                    encPayloadBuf
                                )

                                clientOut.write(plaintext)
                                clientOut.flush()
                            }
                        } catch (_: Exception) {
                        } finally {
                            runCatching { clientSocket.shutdownOutput() }
                        }
                    }

                    // Сервер завершил передачу или оборвался: входящих данных больше не будет.
                    // Закрываем клиентский сокет, чтобы немедленно разблокировать висящий clientIn.read().
                    backwardJob.invokeOnCompletion {
                        runCatching { clientSocket.close() }
                    }
                    // Если клиент завершился с ошибкой, закрываем remote, чтобы немедленно разблокировать remoteIn.readFully().
                    forwardJob.invokeOnCompletion { cause ->
                        if (cause != null) {
                            runCatching { remote.close() }
                        }
                    }

                    // Ожидаем завершения: закрытие противоположного сокета гарантирует немедленный выход из read()
                    backwardJob.join()
                    forwardJob.join()
                }
            } finally {
                runCatching { remote.close() }
                runCatching { clientSocket.close() }
            }

        } catch (e: Exception) {
            Timber.d("OutlineBridge: Ошибка сессии: ${e.message}")
            if (e is ConnectException || e is UnknownHostException) {
                cachedAddress = null // Сбрасываем кэш DNS только при невозможности подключиться к серверу
            }
        } finally {
            remoteSocket?.let {
                activeSockets.remove(it)
                runCatching { it.close() }
            }
        }
    }

    private fun resolveRemoteHost(): InetAddress {
        cachedAddress?.let { return it }

        // 1. Если это прямой IP-адрес
        if (isIpAddress(serverHost)) {
            val addr = InetAddress.getByName(serverHost)
            cachedAddress = addr
            return addr
        }

        // 2. Стандартный резолвер
        try {
            val addr = InetAddress.getByName(serverHost)
            cachedAddress = addr
            return addr
        } catch (e: Exception) {
            Timber.w(e, "OutlineBridge: Стандартный DNS не разрешил $serverHost, пробуем защищенный DNS")
        }

        // 3. Fallback: прямой запрос к доверенным DNS-серверам через защищенный сокет (обход блокировок ТСПУ)
        val protectedAddr = resolveViaProtectedDns(serverHost)
        if (protectedAddr != null) {
            cachedAddress = protectedAddr
            return protectedAddr
        }

        throw UnknownHostException("OutlineBridge: Не удалось разрешить адрес сервера: $serverHost")
    }

    private fun isIpAddress(host: String): Boolean {
        return host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")) || host.contains(":")
    }

    /**
     * Прямой DNS-запрос к публичным DNS-серверам через UDP-сокет, защищенный [vpnService.protect].
     * Это позволяет получить реальный IP сервера Outline даже при блокировках/подмене DNS у локального провайдера.
     */
    private fun resolveViaProtectedDns(host: String): InetAddress? {
        val dnsServers = listOf("8.8.8.8", "1.1.1.1", "77.88.8.8")
        for (dnsServer in dnsServers) {
            try {
                val socket = DatagramSocket()
                vpnService.protect(socket)
                socket.soTimeout = 3000

                val baos = ByteArrayOutputStream()
                val dos = DataOutputStream(baos)
                val tid = (Math.random() * 0xFFFF).toInt()
                dos.writeShort(tid)
                dos.writeShort(0x0100) // standard query, recursion desired
                dos.writeShort(1) // QDCOUNT = 1
                dos.writeShort(0)
                dos.writeShort(0)
                dos.writeShort(0)

                for (part in host.split('.')) {
                    val bytes = part.toByteArray(Charsets.US_ASCII)
                    dos.writeByte(bytes.size)
                    dos.write(bytes)
                }
                dos.writeByte(0)
                dos.writeShort(1) // Type A
                dos.writeShort(1) // Class IN

                val query = baos.toByteArray()
                val packet = DatagramPacket(query, query.size, InetAddress.getByName(dnsServer), 53)
                socket.send(packet)

                val responseBuf = ByteArray(512)
                val respPacket = DatagramPacket(responseBuf, responseBuf.size)
                socket.receive(respPacket)
                socket.close()

                val data = respPacket.data
                if (data.size < 12) continue
                val dis = DataInputStream(ByteArrayInputStream(data, 0, respPacket.length))
                val respTid = dis.readUnsignedShort()
                if (respTid != tid) continue
                dis.readUnsignedShort() // flags
                val qdCount = dis.readUnsignedShort()
                val anCount = dis.readUnsignedShort()
                dis.skipBytes(4) // NSCOUNT, ARCOUNT

                // Пропускаем Question section
                for (q in 0 until qdCount) {
                    while (true) {
                        val len = dis.readUnsignedByte()
                        if (len == 0) break
                        if ((len and 0xC0) == 0xC0) {
                            dis.skipBytes(1)
                            break
                        }
                        dis.skipBytes(len)
                    }
                    dis.skipBytes(4) // QTYPE, QCLASS
                }

                // Читаем Answer section
                for (a in 0 until anCount) {
                    val len = dis.readUnsignedByte()
                    if ((len and 0xC0) == 0xC0) {
                        dis.skipBytes(1)
                    } else {
                        var l = len
                        while (l != 0) {
                            dis.skipBytes(l)
                            l = dis.readUnsignedByte()
                        }
                    }
                    val aType = dis.readUnsignedShort()
                    val aClass = dis.readUnsignedShort()
                    dis.skipBytes(4) // TTL
                    val rdLength = dis.readUnsignedShort()
                    if (aType == 1 && rdLength == 4) { // IPv4 A-record
                        val ipBytes = ByteArray(4)
                        dis.readFully(ipBytes)
                        return InetAddress.getByAddress(ipBytes)
                    } else {
                        dis.skipBytes(rdLength)
                    }
                }
            } catch (e: Exception) {
                Timber.d("OutlineBridge: Не удалось разрешить $host через $dnsServer: ${e.message}")
            }
        }
        return null
    }

    /**
     * Остановка моста и освобождение всех сокетов.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Timber.i("OutlineBridge: Остановка моста")

        runCatching { serverSocket?.close() }
        serverSocket = null

        for (socket in activeSockets) {
            runCatching { socket.close() }
        }
        activeSockets.clear()
        scope.cancel()
    }
}
