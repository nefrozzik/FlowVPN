package com.flowvpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.system.OsConstants
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.hiddify.core.libbox.ConnectionOwner
import com.hiddify.core.libbox.InterfaceUpdateListener
import com.hiddify.core.libbox.Libbox
import com.hiddify.core.libbox.LocalDNSTransport
import com.hiddify.core.libbox.NetworkInterfaceIterator
import com.hiddify.core.libbox.Notification
import com.hiddify.core.libbox.PlatformInterface
import com.hiddify.core.libbox.StringIterator
import com.hiddify.core.libbox.TunOptions
import com.hiddify.core.libbox.WIFIState
import com.hiddify.core.libbox.NetworkInterface as LibboxNetworkInterface
import timber.log.Timber
import com.flowvpn.core.logger.CoreLogManager
import com.flowvpn.core.logger.LogLevel
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.KeyStore

/**
 * Полноценная реализация платформенного интерфейса [PlatformInterface] ядра Sing-box.
 * Мост между нативным Go-ядром и Android OS.
 */
class FlowPlatformInterface(
    private val vpnService: FlowVpnService,
) : PlatformInterface {

    private val connectivityManager: ConnectivityManager? =
        vpnService.getSystemService()

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    /**
     * Защита исходящих сокетов ядра от попадания в VPN-туннель.
     * Критически важно: без этого исходящие пакеты прокси зацикливаются в TUN.
     */
    override fun autoDetectInterfaceControl(fd: Int) {
        var success = false
        for (attempt in 0 until 3) {
            try {
                if (vpnService.protect(fd)) {
                    success = true
                    break
                }
            } catch (t: Throwable) {
                Timber.w(t, "autoDetectInterfaceControl: попытка $attempt защиты fd=$fd не удалась")
            }
            try { Thread.sleep(10) } catch (_: InterruptedException) {}
        }
        if (!success) {
            Timber.e("FlowPlatformInterface: КРИТИЧЕСКАЯ ОШИБКА: Не удалось защитить сокет fd=$fd через VpnService.protect()")
            throw java.io.IOException("VpnService.protect(fd=$fd) failed")
        }
    }

    /**
     * Создание TUN-интерфейса через Android VpnService.
     */
    override fun openTun(options: TunOptions): Int {
        return try {
            Timber.i("FlowPlatformInterface: Запрос openTun(mtu=${options.mtu}, autoRoute=${options.autoRoute})")
            vpnService.createTunInterface(options)
        } catch (t: Throwable) {
            Timber.e(t, "FlowPlatformInterface: openTun failed")
            CoreLogManager.log("Ошибка создания TUN: ${t.message}", LogLevel.ERROR, tag = "VPN")
            throw t
        }
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        val owner = ConnectionOwner()
        owner.userId = Process.INVALID_UID
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return owner
        }
        val cm = connectivityManager ?: return owner

        try {
            val uid = cm.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort),
            )
            owner.userId = uid
            if (uid != Process.INVALID_UID) {
                val packages = vpnService.packageManager.getPackagesForUid(uid)
                val packageName = packages?.firstOrNull() ?: ""
                owner.userName = packageName
                owner.androidPackageName = packageName
            }
        } catch (t: Throwable) {
            Timber.v("findConnectionOwner error: ${t.message}")
        }
        return owner
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        try {
            DefaultNetworkMonitor.start(vpnService)
            DefaultNetworkMonitor.setListener(listener)
        } catch (t: Throwable) {
            Timber.w(t, "startDefaultInterfaceMonitor error")
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        try {
            DefaultNetworkMonitor.setListener(null)
        } catch (t: Throwable) {
            Timber.w(t, "closeDefaultInterfaceMonitor error")
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = mutableListOf<LibboxNetworkInterface>()
        try {
            val cm = connectivityManager
            if (cm != null) {
                val networks = cm.allNetworks
                val netIfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
                for (network in networks) {
                    val linkProps = cm.getLinkProperties(network) ?: continue
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    val ifaceName = linkProps.interfaceName ?: continue

                    // КРИТИЧЕСКИ ВАЖНО: Исключаем VPN и виртуальные интерфейсы из списка физических интерфейсов
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
                    val lowerName = ifaceName.lowercase()
                    if (lowerName == "lo" || lowerName.startsWith("tun") || lowerName.startsWith("ppp") || lowerName.startsWith("p2p") || lowerName.startsWith("dummy")) continue

                    val ni = netIfaces.find { it.name == ifaceName }
                        ?: runCatching { NetworkInterface.getByName(ifaceName) }.getOrNull()
                        ?: continue

                    val boxIf = LibboxNetworkInterface().apply {
                        name = ifaceName
                        index = ni.index
                        mtu = runCatching { ni.mtu }.getOrDefault(1500)
                        dnsServer = StringArray(linkProps.dnsServers.mapNotNull { it.hostAddress })
                        type = when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                            else -> Libbox.InterfaceTypeOther
                        }
                        addresses = StringArray(ni.interfaceAddresses.mapNotNull { addr ->
                            runCatching { addr.toSafePrefix() }.getOrNull()
                        })

                        var flags = 0
                        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                        }
                        if (ni.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                        if (ni.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
                        if (runCatching { ni.supportsMulticast() }.getOrDefault(false)) {
                            flags = flags or OsConstants.IFF_MULTICAST
                        }
                        this.flags = flags
                        metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    }
                    list.add(boxIf)
                }
            }
        } catch (t: Throwable) {
            Timber.e(t, "getInterfaces error")
        }
        return InterfaceArray(list.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {}

    override fun readWIFIState(): WIFIState? {
        return try {
            val wm = vpnService.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val wifiInfo = wm?.connectionInfo ?: return WIFIState("", "")
            var ssid = wifiInfo.ssid ?: ""
            if (ssid == "<unknown ssid>") ssid = ""
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length - 1)
            }
            WIFIState(ssid, wifiInfo.bssid ?: "")
        } catch (_: Throwable) {
            WIFIState("", "")
        }
    }

    override fun sendNotification(notification: Notification) {}

    override fun localDNSTransport(): LocalDNSTransport? = LocalResolver

    override fun systemCertificates(): StringIterator {
        val certs = mutableListOf<String>()
        try {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks?.load(null, null)
            val aliases = ks?.aliases()
            while (aliases?.hasMoreElements() == true) {
                val cert = ks.getCertificate(aliases.nextElement())
                val base64 = android.util.Base64.encodeToString(cert.encoded, android.util.Base64.NO_WRAP)
                certs.add("-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----")
            }
        } catch (t: Throwable) {
            Timber.w(t, "systemCertificates: Ошибка чтения AndroidCAStore")
        }
        return StringArray(certs)
    }

    private fun java.net.InterfaceAddress.toSafePrefix(): String = if (address is java.net.Inet6Address) {
        "${java.net.Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
    } else {
        "${address.hostAddress}/$networkPrefixLength"
    }

    private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }

    class StringArray(private val items: List<String>) : StringIterator {
        private var currentIndex = 0
        override fun len(): Int = items.size
        override fun hasNext(): Boolean = currentIndex < items.size
        override fun next(): String = if (hasNext()) items[currentIndex++] else ""
    }
}
