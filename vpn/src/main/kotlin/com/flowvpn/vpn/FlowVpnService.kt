package com.flowvpn.vpn

import android.app.Notification
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import com.flowvpn.core.logger.CoreLogManager
import com.flowvpn.core.logger.LogLevel
import com.flowvpn.core.model.AppSettings
import com.flowvpn.core.model.DnsProvider
import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.state.VpnStateManager
import com.hiddify.core.libbox.TunOptions

/**
 * Основной VPN-сервис приложения FlowVPN.
 *
 * Интегрирован с нативным Go-ядром Sing-box (Hiddify Core) через [FlowPlatformInterface].
 * Поддерживает VLESS Reality, XTLS-Vision, Shadowsocks, VMess, Trojan, Hysteria2, TUIC.
 */
class FlowVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.flowvpn.vpn.START"
        const val ACTION_STOP = "com.flowvpn.vpn.STOP"

        const val EXTRA_CONFIG_PATH = "config_path"

        private const val VPN_INET4_ADDRESS = "172.19.0.1"
        private const val VPN_INET4_PREFIX = 30
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val coroutineExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "FlowVpnService: Необработанное исключение в корутине")
        CoreLogManager.log("Критическая ошибка сервиса: ${throwable.message}", LogLevel.ERROR, tag = "VPN")
        VpnStateManager.setError("Ошибка сервиса: ${throwable.message}")
        stopVpn()
    }
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + coroutineExceptionHandler)
    private var boxManager: BoxServiceManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Timber.w("FlowVpnService: onStartCommand с intent == null, завершаем")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (configPath != null) {
                    startVpn(configPath)
                } else {
                    Timber.e("FlowVpnService: ACTION_START без config_path")
                    stopSelf()
                }
            }

            ACTION_STOP -> {
                stopVpn()
            }

            else -> {
                Timber.w("FlowVpnService: Неизвестный action=${intent.action}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Запуск VPN-соединения с нативным ядром Sing-box.
     */
    private fun startVpn(configPath: String) {
        Timber.i("FlowVpnService: Запуск VPN с конфигом: $configPath")
        CoreLogManager.log("Инициализация VPN-сервиса (Sing-box Core)...", tag = "VPN")
        VpnStateManager.updateState(com.flowvpn.core.model.VpnState.Connecting)

        val notification = ServiceNotification.createNotification(
            context = this,
            state = ServiceNotification.State.CONNECTING,
        )
        startForegroundCompat(notification)

        val platformInterface = FlowPlatformInterface(this)
        boxManager = BoxServiceManager(this, platformInterface)

        serviceScope.launch(Dispatchers.IO) {
            try {
                boxManager?.start(configPath)
                Timber.i("FlowVpnService: Ядро sing-box запущено")

                val connectedNotification = ServiceNotification.createNotification(
                    context = this@FlowVpnService,
                    state = ServiceNotification.State.CONNECTED,
                )
                ServiceNotification.update(this@FlowVpnService, connectedNotification)

                val serverName = VpnStateManager.currentServerName ?: "FlowVPN Server"
                VpnStateManager.updateState(
                    com.flowvpn.core.model.VpnState.Connected(serverName = serverName)
                )
                CoreLogManager.log("VPN успешно подключен: $serverName", tag = "VPN")

                val settings = loadSettings()
                if (settings.rootTethering) {
                    RootTetheringManager.enableTethering()
                }
            } catch (t: Throwable) {
                Timber.e(t, "FlowVpnService: Ошибка запуска ядра")
                VpnStateManager.setError("Ошибка запуска: ${t.message}")
                CoreLogManager.log("Ошибка запуска ядра: ${t.message}", LogLevel.ERROR, tag = "SingBox")
                stopVpn()
            }
        }
    }

    /**
     * Остановка VPN-соединения.
     */
    fun stopVpn() {
        Timber.i("FlowVpnService: Остановка VPN")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val settings = loadSettings()
                if (settings.rootTethering) {
                    RootTetheringManager.disableTethering()
                }
                boxManager?.stop()
                boxManager = null
            } catch (e: Exception) {
                Timber.e(e, "FlowVpnService: Ошибка остановки ядра")
            } finally {
                closeTunInterface()

                VpnStateManager.setDisconnected()
                CoreLogManager.log("VPN отключен", tag = "VPN")

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Создание виртуального TUN-интерфейса через VpnService.Builder по запросу ядра Sing-box.
     */
    fun createTunInterface(options: TunOptions): Int {
        closeTunInterface()

        // Ожидание готовности разрешения VPN (до 1 сек, по аналогии с Hiddify)
        for (i in 0 until 20) {
            if (prepare(this) != null) {
                Timber.w("createTunInterface: ожидание готовности VPN разрешения...")
                Thread.sleep(50)
            } else {
                break
            }
        }

        val server = loadSelectedServer()
        val settings = loadSettings()

        val blockIpv6 = settings.blockIpv6
        val effectiveMtu = settings.mtu.takeIf { it in 1000..9000 } ?: options.mtu

        val (primaryDns, secondaryDns) = when (settings.dnsProvider) {
            DnsProvider.CLOUDFLARE -> "1.1.1.1" to "1.0.0.1"
            DnsProvider.GOOGLE -> "8.8.8.8" to "8.8.4.4"
            DnsProvider.ADGUARD -> "94.140.14.14" to "94.140.15.15"
            DnsProvider.QUAD9 -> "9.9.9.9" to "149.112.112.112"
            else -> "1.1.1.1" to "8.8.8.8"
        }

        try {
            val builder = Builder().apply {
                val inet4 = options.inet4Address
                var hasV4 = false
                while (inet4.hasNext()) {
                    val addr = inet4.next()
                    addAddress(addr.address(), addr.prefix())
                    hasV4 = true
                }
                if (!hasV4) {
                    addAddress(VPN_INET4_ADDRESS, VPN_INET4_PREFIX)
                }

                if (!blockIpv6) {
                    val inet6 = options.inet6Address
                    while (inet6.hasNext()) {
                        val addr = inet6.next()
                        try {
                            addAddress(addr.address(), addr.prefix())
                            addRoute("::", 0)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                allowFamily(OsConstants.AF_INET6)
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Не удалось назначить IPv6 адрес на TUN")
                        }
                    }
                }

                // Маршрутизация IPv4 и IPv6 из TunOptions
                if (options.autoRoute) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val inet4RouteAddress = options.inet4RouteAddress
                        if (inet4RouteAddress.hasNext()) {
                            while (inet4RouteAddress.hasNext()) {
                                try {
                                    val r = inet4RouteAddress.next()
                                    addRoute(android.net.IpPrefix(java.net.InetAddress.getByName(r.address()), r.prefix()))
                                } catch (e: Exception) { Timber.w(e, "addRoute v4 failed") }
                            }
                        } else {
                            addRoute("0.0.0.0", 0)
                        }

                        if (!blockIpv6) {
                            val inet6RouteAddress = options.inet6RouteAddress
                            if (inet6RouteAddress.hasNext()) {
                                while (inet6RouteAddress.hasNext()) {
                                    try {
                                        val r = inet6RouteAddress.next()
                                        addRoute(android.net.IpPrefix(java.net.InetAddress.getByName(r.address()), r.prefix()))
                                    } catch (e: Exception) { Timber.w(e, "addRoute v6 failed") }
                                }
                            } else {
                                addRoute("::", 0)
                            }
                        }

                        val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
                        while (inet4RouteExcludeAddress.hasNext()) {
                            try {
                                val r = inet4RouteExcludeAddress.next()
                                excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(r.address()), r.prefix()))
                            } catch (e: Exception) { Timber.w(e, "excludeRoute v4 failed") }
                        }

                        if (!blockIpv6) {
                            val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
                            while (inet6RouteExcludeAddress.hasNext()) {
                                try {
                                    val r = inet6RouteExcludeAddress.next()
                                    excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(r.address()), r.prefix()))
                                } catch (e: Exception) { Timber.w(e, "excludeRoute v6 failed") }
                            }
                        }
                    } else {
                        val inet4RouteRange = options.inet4RouteRange
                        var hasV4Range = false
                        while (inet4RouteRange.hasNext()) {
                            val r = inet4RouteRange.next()
                            try {
                                addRoute(r.address(), r.prefix())
                                hasV4Range = true
                            } catch (e: Exception) { Timber.w(e, "addRoute v4 range failed") }
                        }
                        if (!hasV4Range) {
                            addRoute("0.0.0.0", 0)
                        }

                        if (!blockIpv6) {
                            val inet6RouteRange = options.inet6RouteRange
                            var hasV6Range = false
                            while (inet6RouteRange.hasNext()) {
                                val r = inet6RouteRange.next()
                                try {
                                    addRoute(r.address(), r.prefix())
                                    hasV6Range = true
                                } catch (e: Exception) { Timber.w(e, "addRoute v6 range failed") }
                            }
                            if (!hasV6Range) {
                                addRoute("::", 0)
                            }
                        }
                    }
                } else {
                    addRoute("0.0.0.0", 0)
                    if (!blockIpv6) addRoute("::", 0)
                }

                var addedDns = false
                runCatching {
                    val coreDns = options.dnsServerAddress.value
                    if (coreDns.isNotBlank()) {
                        addDnsServer(coreDns)
                        addedDns = true
                    }
                }
                if (!addedDns) {
                    addDnsServer(primaryDns)
                    addDnsServer(secondaryDns)
                }

                // Samsung Android: явные маршруты для DNS
                val dnsList = listOf(primaryDns, secondaryDns, "8.8.8.8", "77.88.8.8", "1.1.1.1").distinct()
                if (Build.BRAND.equals("samsung", ignoreCase = true)) {
                    for (dns in dnsList) {
                        try { addRoute(dns, 32) } catch (_: Exception) {}
                    }
                }

                // Исключение прямого маршрута до хоста прокси (Android 13+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && server != null) {
                    try {
                        val host = server.address
                        if (host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
                            excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(host), 32))
                        }
                    } catch (_: Exception) {}
                }

                setMtu(effectiveMtu)
                setSession("FlowVPN")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setMetered(false)
                }

                applySplitTunneling(this, options)
            }

            val pfd = builder.establish() ?: error("builder.establish() вернул null (разрешение VPN не выдано)")
            vpnInterface = pfd

            CoreLogManager.log(
                "Android TUN интерфейс создан: fd=${pfd.fd}, MTU=$effectiveMtu, IPv6=${if (blockIpv6) "блок" else "активен"}",
                tag = "VPN"
            )

            if (server != null) {
                CoreLogManager.log(
                    "Подключение к серверу: ${server.name} [${server.protocol.displayName}] -> ${server.address}:${server.port}",
                    tag = "SingBox"
                )
            }

            return pfd.fd
        } catch (t: Throwable) {
            Timber.e(t, "Ошибка создания TUN интерфейса")
            CoreLogManager.log("Ошибка создания TUN интерфейса: ${t.message}", LogLevel.ERROR, tag = "VPN")
            throw t
        }
    }

    private fun loadSelectedServer(): ProxyServerConfig? {
        VpnStateManager.activeServerConfig?.let { return it }
        return try {
            val serverFile = java.io.File(filesDir, "selected_server.json")
            if (serverFile.exists()) {
                val json = org.json.JSONObject(serverFile.readText())
                val config = ProxyServerConfig.fromJson(json)
                VpnStateManager.activeServerConfig = config
                config
            } else null
        } catch (e: Exception) {
            Timber.w(e, "FlowVpnService: Не удалось загрузить selected_server.json")
            null
        }
    }

    private fun loadSettings(): AppSettings {
        return try {
            val settingsFile = java.io.File(filesDir, "settings.json")
            if (settingsFile.exists()) {
                val json = org.json.JSONObject(settingsFile.readText())
                val dnsProviderName = json.optString("dnsProvider", "CLOUDFLARE")
                val provider = try { DnsProvider.valueOf(dnsProviderName) } catch (_: Exception) { DnsProvider.CLOUDFLARE }
                AppSettings(
                    dnsProvider = provider,
                    customDnsUrl = json.optString("customDnsUrl", "https://dns.google/dns-query"),
                    bypassLan = json.optBoolean("bypassLan", true),
                    bypassRussianTraffic = json.optBoolean("bypassRussianTraffic", true),
                    killSwitch = json.optBoolean("killSwitch", false),
                    blockIpv6 = json.optBoolean("blockIpv6", true),
                    mtu = json.optInt("mtu", 1500),
                    fakeDns = json.optBoolean("fakeDns", true),
                    sniffing = json.optBoolean("sniffing", true),
                    autoConnect = json.optBoolean("autoConnect", false),
                    autoUpdateSubscriptions = json.optBoolean("autoUpdateSubscriptions", true),
                    autoUpdateIntervalHours = json.optInt("autoUpdateIntervalHours", 24),
                    rootTethering = json.optBoolean("rootTethering", false),
                )
            } else {
                AppSettings()
            }
        } catch (e: Exception) {
            AppSettings()
        }
    }

    private fun applySplitTunneling(builder: Builder, options: TunOptions) {
        try {
            val configFile = java.io.File(filesDir, "split_tunnel.json")
            var mode = "DISABLED"
            val packages = mutableSetOf<String>()
            if (configFile.exists()) {
                val json = org.json.JSONObject(configFile.readText())
                mode = json.optString("mode", "DISABLED")
                val pkgsArray = json.optJSONArray("packages")
                if (pkgsArray != null) {
                    for (i in 0 until pkgsArray.length()) {
                        packages.add(pkgsArray.getString(i))
                    }
                }
            }

            when (mode) {
                "ALLOW_LIST" -> {
                    // В режиме ALLOW_LIST добавляем ТОЛЬКО разрешенные приложения.
                    // FlowVPN не входит в список, поэтому его трафик автоматически идет мимо VPN напрямую в сокеты.
                    for (pkg in packages) {
                        if (pkg != packageName) {
                            try {
                                builder.addAllowedApplication(pkg)
                            } catch (e: Exception) {
                                Timber.w("Не удалось добавить allowed приложение: $pkg")
                            }
                        }
                    }
                }
                "DISALLOW_LIST" -> {
                    for (pkg in packages) {
                        if (pkg != packageName) {
                            try {
                                builder.addDisallowedApplication(pkg)
                            } catch (e: Exception) {
                                Timber.w("Не удалось добавить disallowed приложение: $pkg")
                            }
                        }
                    }
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Timber.w("Не удалось исключить packageName=$packageName")
                    }
                }
                else -> {
                    // Split Tunneling отключен пользователем: проверяем настройки самого sing-box (если переданы)
                    val includePackage = options.includePackage
                    if (includePackage.hasNext()) {
                        while (includePackage.hasNext()) {
                            val pkg = includePackage.next()
                            if (pkg != packageName) {
                                try { builder.addAllowedApplication(pkg) } catch (_: Exception) {}
                            }
                        }
                    } else {
                        val excludePackage = options.excludePackage
                        while (excludePackage.hasNext()) {
                            val pkg = excludePackage.next()
                            if (pkg != packageName) {
                                try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
                            }
                        }
                        try {
                            builder.addDisallowedApplication(packageName)
                        } catch (e: Exception) {
                            Timber.w("Не удалось исключить packageName=$packageName")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка применения Split Tunneling к VpnService.Builder")
        }
    }

    private fun closeTunInterface() {
        try {
            vpnInterface?.close()
            vpnInterface = null
            Timber.d("FlowVpnService: TUN-интерфейс закрыт")
        } catch (e: Exception) {
            Timber.w(e, "FlowVpnService: Ошибка при закрытии TUN")
        }
    }

    override fun onRevoke() {
        Timber.w("FlowVpnService: VPN разрешение отозвано (onRevoke)")
        VpnStateManager.setDisconnected()
        CoreLogManager.log("VPN разрешение отозвано операционной системой", LogLevel.WARN, tag = "VPN")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        Timber.i("FlowVpnService: onDestroy")
        serviceScope.cancel()
        closeTunInterface()
        if (VpnStateManager.vpnState.value is com.flowvpn.core.model.VpnState.Connected) {
            VpnStateManager.setDisconnected()
        }
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ServiceNotification.NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(ServiceNotification.NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            Timber.w(e, "startForegroundCompat failed with specialUse, fallback to standard startForeground")
            try {
                startForeground(ServiceNotification.NOTIFICATION_ID, notification)
            } catch (e2: Throwable) {
                Timber.e(e2, "startForeground completely failed")
            }
        }
    }

    fun getConnectionOwnerUid(
        protocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destAddress: String,
        destPort: Int,
    ): Int {
        return try {
            val cm = getSystemService<ConnectivityManager>() ?: return -1

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val inet4Source = java.net.InetSocketAddress(sourceAddress, sourcePort)
                val inet4Dest = java.net.InetSocketAddress(destAddress, destPort)
                cm.getConnectionOwnerUid(protocol, inet4Source, inet4Dest)
            } else {
                -1
            }
        } catch (e: Exception) {
            Timber.w(e, "Не удалось определить UID соединения")
            -1
        }
    }

    fun getPackageNameByUid(uid: Int): String {
        return try {
            packageManager.getPackagesForUid(uid)?.firstOrNull() ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
