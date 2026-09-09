package com.flowvpn.core.subscription

import com.flowvpn.core.model.ProxyProtocol
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.SubscriptionInfo
import com.flowvpn.core.model.TlsConfig
import com.flowvpn.core.model.TransportConfig
import com.flowvpn.core.parser.SubscriptionDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Файловая реализация [SubscriptionRepository].
 *
 * Хранит подписки и конфигурации серверов в локальном JSON-файле [storageFile].
 * Потокобезопасна благодаря [Mutex] и реактивно обновляет UI через [MutableStateFlow].
 */
class FileSubscriptionRepository(
    private val storageFile: File,
    private val subscriptionManager: SubscriptionManager = SubscriptionManager(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SubscriptionRepository {

    private val mutex = Mutex()
    private val _subscriptions = MutableStateFlow<List<SubscriptionInfo>>(emptyList())
    private var isInitialized = false

    override fun getAllSubscriptions(): Flow<List<SubscriptionInfo>> {
        ensureInitialized()
        return _subscriptions.asStateFlow()
    }

    override suspend fun getSubscription(id: String): SubscriptionInfo? = withContext(ioDispatcher) {
        ensureLoaded()
        mutex.withLock {
            _subscriptions.value.find { it.id == id }
        }
    }

    override suspend fun addSubscription(url: String, name: String?): SubscriptionInfo = withContext(ioDispatcher) {
        ensureLoaded()
        val loaded = subscriptionManager.fetchSubscription(url)
        val finalInfo = if (!name.isNullOrBlank()) {
            loaded.copy(name = name)
        } else {
            loaded
        }

        mutex.withLock {
            val current = _subscriptions.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.url == url }
            if (existingIndex >= 0) {
                current[existingIndex] = finalInfo.copy(id = current[existingIndex].id)
            } else {
                current.add(finalInfo)
            }
            _subscriptions.value = current
            saveToFileLocked(current)
        }
        finalInfo
    }

    override suspend fun updateSubscription(id: String): SubscriptionInfo = withContext(ioDispatcher) {
        ensureLoaded()
        val existing = mutex.withLock {
            _subscriptions.value.find { it.id == id }
        } ?: throw NoSuchElementException("Подписка с id=$id не найдена")

        val updated = subscriptionManager.fetchSubscription(existing.url, existing)
        mutex.withLock {
            val current = _subscriptions.value.toMutableList()
            val index = current.indexOfFirst { it.id == id }
            if (index >= 0) {
                current[index] = updated
                _subscriptions.value = current
                saveToFileLocked(current)
            }
        }
        updated
    }

    override suspend fun updateAllSubscriptions(): List<SubscriptionInfo> = withContext(ioDispatcher) {
        ensureLoaded()
        val toUpdate = mutex.withLock { _subscriptions.value }
        val updatedList = mutableListOf<SubscriptionInfo>()

        for (sub in toUpdate) {
            if (sub.url.isBlank()) continue // Виртуальные подписки (Imported) не обновляются по сети
            try {
                val updated = subscriptionManager.fetchSubscription(sub.url, sub)
                updatedList.add(updated)
            } catch (e: Exception) {
                Timber.w(e, "Не удалось обновить подписку ${sub.name}")
                updatedList.add(sub)
            }
        }

        mutex.withLock {
            val currentMap = _subscriptions.value.associateBy { it.id }.toMutableMap()
            updatedList.forEach { currentMap[it.id] = it }
            val newList = currentMap.values.toList()
            _subscriptions.value = newList
            saveToFileLocked(newList)
        }

        updatedList
    }

    override suspend fun deleteSubscription(id: String): Unit = withContext(ioDispatcher) {
        ensureLoaded()
        mutex.withLock {
            val current = _subscriptions.value.toMutableList()
            if (current.removeIf { it.id == id }) {
                _subscriptions.value = current
                saveToFileLocked(current)
            }
        }
    }

    override suspend fun importFromText(content: String): Int = withContext(ioDispatcher) {
        ensureLoaded()
        val servers = SubscriptionDecoder.decode(content)
        if (servers.isEmpty()) return@withContext 0

        mutex.withLock {
            val current = _subscriptions.value.toMutableList()
            val importedId = "imported_manual_profile"
            val existingIndex = current.indexOfFirst { it.id == importedId }

            if (existingIndex >= 0) {
                val existing = current[existingIndex]
                val combinedServers = (existing.servers + servers).distinctBy { "${it.protocol}://${it.address}:${it.port}${it.uuid}${it.password}" }
                current[existingIndex] = existing.copy(
                    servers = combinedServers,
                    lastUpdatedMs = System.currentTimeMillis()
                )
            } else {
                current.add(
                    SubscriptionInfo(
                        id = importedId,
                        name = "Ручной импорт",
                        url = "",
                        lastUpdatedMs = System.currentTimeMillis(),
                        servers = servers
                    )
                )
            }
            _subscriptions.value = current
            saveToFileLocked(current)
        }

        servers.size
    }

    private fun ensureInitialized() {
        if (!isInitialized) {
            try {
                if (storageFile.exists()) {
                    val jsonStr = storageFile.readText()
                    _subscriptions.value = parseSubscriptionsJson(jsonStr)
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка инициализации подписок из файла")
            }
            isInitialized = true
        }
    }

    private suspend fun ensureLoaded() = mutex.withLock {
        ensureInitialized()
    }

    private fun saveToFileLocked(list: List<SubscriptionInfo>) {
        try {
            storageFile.parentFile?.mkdirs()
            val jsonStr = serializeSubscriptionsJson(list)
            storageFile.writeText(jsonStr)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка сохранения подписок в файл: ${storageFile.absolutePath}")
        }
    }

    // ─── Ручная сериализация / десериализация без громоздких внешних зависимостей ───

    private fun serializeSubscriptionsJson(list: List<SubscriptionInfo>): String {
        val rootArray = JSONArray()
        for (sub in list) {
            val subObj = JSONObject().apply {
                put("id", sub.id)
                put("name", sub.name)
                put("url", sub.url)
                putOpt("lastUpdatedMs", sub.lastUpdatedMs)
                putOpt("uploadBytes", sub.uploadBytes)
                putOpt("downloadBytes", sub.downloadBytes)
                putOpt("totalBytes", sub.totalBytes)
                putOpt("expireTimestamp", sub.expireTimestamp)

                val serversArray = JSONArray()
                for (s in sub.servers) {
                    val sObj = JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("protocol", s.protocol.name)
                        put("address", s.address)
                        put("port", s.port)
                        putOpt("uuid", s.uuid)
                        putOpt("password", s.password)
                        putOpt("method", s.method)
                        putOpt("security", s.security)
                        put("alterId", s.alterId)
                        putOpt("flow", s.flow)
                        putOpt("upMbps", s.upMbps)
                        putOpt("downMbps", s.downMbps)
                        putOpt("obfsPassword", s.obfsPassword)
                        putOpt("congestionControl", s.congestionControl)
                        putOpt("privateKey", s.privateKey)
                        putOpt("peerPublicKey", s.peerPublicKey)
                        putOpt("preSharedKey", s.preSharedKey)
                        putOpt("subscriptionId", s.subscriptionId ?: sub.id)
                        putOpt("latencyMs", s.latencyMs)
                        putOpt("country", s.country)

                        s.tls?.let { tls ->
                            put("tls", JSONObject().apply {
                                put("enabled", tls.enabled)
                                putOpt("serverName", tls.serverName)
                                put("insecure", tls.insecure)
                                putOpt("utlsFingerprint", tls.utlsFingerprint)
                                putOpt("realityPublicKey", tls.realityPublicKey)
                                putOpt("realityShortId", tls.realityShortId)
                                tls.alpn?.let { put("alpn", JSONArray(it)) }
                            })
                        }

                        s.transport?.let { t ->
                            put("transport", JSONObject().apply {
                                put("type", t.type)
                                putOpt("path", t.path)
                                putOpt("host", t.host)
                                putOpt("serviceName", t.serviceName)
                            })
                        }
                    }
                    serversArray.put(sObj)
                }
                put("servers", serversArray)
            }
            rootArray.put(subObj)
        }
        return rootArray.toString(2)
    }

    private fun parseSubscriptionsJson(jsonStr: String): List<SubscriptionInfo> {
        if (jsonStr.isBlank()) return emptyList()
        val list = mutableListOf<SubscriptionInfo>()
        val rootArray = JSONArray(jsonStr)

        for (i in 0 until rootArray.length()) {
            val subObj = rootArray.getJSONObject(i)
            val servers = mutableListOf<ProxyServerConfig>()
            val serversArray = subObj.optJSONArray("servers") ?: JSONArray()

            for (j in 0 until serversArray.length()) {
                val sObj = serversArray.getJSONObject(j)
                val protocolStr = sObj.optString("protocol", "VLESS")
                val protocol = try { ProxyProtocol.valueOf(protocolStr) } catch (_: Exception) { ProxyProtocol.VLESS }

                val tlsObj = sObj.optJSONObject("tls")
                val tls = tlsObj?.let {
                    TlsConfig(
                        enabled = it.optBoolean("enabled", true),
                        serverName = it.optString("serverName").takeIf { s -> s.isNotEmpty() },
                        insecure = it.optBoolean("insecure", false),
                        utlsFingerprint = it.optString("utlsFingerprint").takeIf { s -> s.isNotEmpty() },
                        realityPublicKey = it.optString("realityPublicKey").takeIf { s -> s.isNotEmpty() },
                        realityShortId = it.optString("realityShortId").takeIf { s -> s.isNotEmpty() },
                    )
                }

                val tObj = sObj.optJSONObject("transport")
                val transport = tObj?.let {
                    TransportConfig(
                        type = it.optString("type", "tcp"),
                        path = it.optString("path").takeIf { s -> s.isNotEmpty() },
                        host = it.optString("host").takeIf { s -> s.isNotEmpty() },
                        serviceName = it.optString("serviceName").takeIf { s -> s.isNotEmpty() },
                    )
                }

                servers.add(
                    ProxyServerConfig(
                        id = sObj.optString("id", UUID.randomUUID().toString()),
                        name = sObj.optString("name", "Server"),
                        protocol = protocol,
                        address = sObj.optString("address", "127.0.0.1"),
                        port = sObj.optInt("port", 443),
                        uuid = sObj.optString("uuid").takeIf { it.isNotEmpty() },
                        password = sObj.optString("password").takeIf { it.isNotEmpty() },
                        method = sObj.optString("method").takeIf { it.isNotEmpty() },
                        security = sObj.optString("security").takeIf { it.isNotEmpty() },
                        alterId = sObj.optInt("alterId", 0),
                        flow = sObj.optString("flow").takeIf { it.isNotEmpty() },
                        transport = transport,
                        tls = tls,
                        upMbps = if (sObj.has("upMbps")) sObj.getInt("upMbps") else null,
                        downMbps = if (sObj.has("downMbps")) sObj.getInt("downMbps") else null,
                        obfsPassword = sObj.optString("obfsPassword").takeIf { it.isNotEmpty() },
                        congestionControl = sObj.optString("congestionControl").takeIf { it.isNotEmpty() },
                        privateKey = sObj.optString("privateKey").takeIf { it.isNotEmpty() },
                        peerPublicKey = sObj.optString("peerPublicKey").takeIf { it.isNotEmpty() },
                        preSharedKey = sObj.optString("preSharedKey").takeIf { it.isNotEmpty() },
                        subscriptionId = sObj.optString("subscriptionId").takeIf { it.isNotEmpty() },
                        latencyMs = if (sObj.has("latencyMs")) sObj.getInt("latencyMs") else null,
                        country = sObj.optString("country").takeIf { it.isNotEmpty() },
                    )
                )
            }

            list.add(
                SubscriptionInfo(
                    id = subObj.optString("id", UUID.randomUUID().toString()),
                    name = subObj.optString("name", "Subscription"),
                    url = subObj.optString("url", ""),
                    lastUpdatedMs = if (subObj.has("lastUpdatedMs")) subObj.getLong("lastUpdatedMs") else null,
                    servers = servers,
                    uploadBytes = if (subObj.has("uploadBytes")) subObj.getLong("uploadBytes") else null,
                    downloadBytes = if (subObj.has("downloadBytes")) subObj.getLong("downloadBytes") else null,
                    totalBytes = if (subObj.has("totalBytes")) subObj.getLong("totalBytes") else null,
                    expireTimestamp = if (subObj.has("expireTimestamp")) subObj.getLong("expireTimestamp") else null,
                )
            )
        }

        return list
    }
}
