package com.flowvpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService
import com.hiddify.core.libbox.InterfaceUpdateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.NetworkInterface

/**
 * Монитор активного физического сетевого подключения устройства (Wi-Fi / Cellular / Ethernet).
 * Передает ядру Sing-box информацию о текущем исходящем интерфейсе
 * для корректной маршрутизации сокетов в обход VPN.
 *
 * КРИТИЧЕСКИ ВАЖНО:
 * Исключает виртуальные VPN-сети (tun0), предотвращая смертельную петлю маршрутизации (Routing Loop).
 */
object DefaultNetworkMonitor {

    @Volatile
    var defaultNetwork: Network? = null
        private set

    private var listener: InterfaceUpdateListener? = null
    private var connectivityManager: ConnectivityManager? = null
    private var isRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val cm = connectivityManager ?: return
            val caps = cm.getNetworkCapabilities(network)
            if (isVpnNetwork(caps, network)) {
                Timber.d("DefaultNetworkMonitor: Игнорируем VPN сеть $network")
                return
            }
            Timber.i("DefaultNetworkMonitor: Физическая сеть доступна: $network")
            defaultNetwork = network
            updateInterface(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (isVpnNetwork(networkCapabilities, network)) {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    notifyInterfaceLost()
                }
                return
            }
            if (defaultNetwork == network) {
                updateInterface(network)
            } else if (defaultNetwork == null && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                defaultNetwork = network
                updateInterface(network)
            }
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == network) {
                Timber.w("DefaultNetworkMonitor: Физическая сеть потеряна: $network")
                defaultNetwork = null
                notifyInterfaceLost()

                // Попытка переключиться на другую доступную физическую сеть (например, сотовую связь при потере Wi-Fi)
                val cm = connectivityManager
                if (cm != null) {
                    val fallback = findPhysicalNetwork(cm)
                    if (fallback != null) {
                        Timber.i("DefaultNetworkMonitor: Найдена альтернативная физическая сеть: $fallback")
                        defaultNetwork = fallback
                        updateInterface(fallback)
                    }
                }
            }
        }
    }

    fun isVpnNetwork(caps: NetworkCapabilities?, network: Network): Boolean {
        if (caps == null) return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return true
        val cm = connectivityManager ?: return false
        val lp = cm.getLinkProperties(network)
        val iface = lp?.interfaceName?.lowercase() ?: ""
        return iface == "lo" || iface.startsWith("tun") || iface.startsWith("ppp") || iface.startsWith("p2p") || iface.startsWith("dummy")
    }

    private fun findPhysicalNetwork(cm: ConnectivityManager): Network? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = cm.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                if (!isVpnNetwork(caps, active)) {
                    return active
                }
            }
        }
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !isVpnNetwork(caps, net)) {
                return net
            }
        }
        return null
    }

    private fun notifyInterfaceLost() {
        try {
            listener?.updateDefaultInterface("", -1, false, false)
        } catch (t: Throwable) {
            Timber.v(t, "onLost updateDefaultInterface failed")
        }
    }

    fun start(context: Context) {
        val cm = context.getSystemService<ConnectivityManager>() ?: return
        connectivityManager = cm

        // Первичная инициализация физической сетью
        val initialNet = findPhysicalNetwork(cm)
        if (initialNet != null) {
            defaultNetwork = initialNet
        }

        if (isRegistered) {
            defaultNetwork?.let { updateInterface(it) }
            return
        }

        // Запрос сети строго без VPN и без loopback (NET_CAPABILITY_NOT_VPN)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    cm.registerBestMatchingNetworkCallback(request, networkCallback, mainHandler)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    cm.requestNetwork(request, networkCallback, mainHandler)
                }
                else -> {
                    cm.requestNetwork(request, networkCallback)
                }
            }
            isRegistered = true
            Timber.i("DefaultNetworkMonitor: Сетевой коллбэк успешно зарегистрирован")
        } catch (e: Exception) {
            Timber.w(e, "DefaultNetworkMonitor: Ошибка регистрации коллбэка через requestNetwork, пробуем registerNetworkCallback")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    cm.registerNetworkCallback(request, networkCallback, mainHandler)
                } else {
                    cm.registerNetworkCallback(request, networkCallback)
                }
                isRegistered = true
                Timber.i("DefaultNetworkMonitor: Сетевой коллбэк зарегистрирован через fallback")
            } catch (e2: Exception) {
                Timber.e(e2, "DefaultNetworkMonitor: Не удалось зарегистрировать сетевой коллбэк")
            }
        }

        defaultNetwork?.let { updateInterface(it) }
    }

    fun stop() {
        if (!isRegistered) return
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            isRegistered = false
            Timber.i("DefaultNetworkMonitor: Сетевой коллбэк разрегистрирован")
        } catch (e: Exception) {
            Timber.w(e, "DefaultNetworkMonitor: Ошибка разрегистрации коллбэка")
        }
        listener = null
        defaultNetwork = null
    }

    fun setListener(updateListener: InterfaceUpdateListener?) {
        listener = updateListener
        val cm = connectivityManager
        if (defaultNetwork == null && cm != null) {
            findPhysicalNetwork(cm)?.let { defaultNetwork = it }
        }
        defaultNetwork?.let { updateInterface(it) }
    }

    /**
     * Получение текущей физической сети с ожиданием (до 3 сек),
     * предотвращает возврат NXDOMAIN при старте сервиса.
     */
    suspend fun require(): Network = withContext(Dispatchers.IO) {
        defaultNetwork?.let { return@withContext it }

        val cm = connectivityManager
        if (cm != null) {
            val physical = findPhysicalNetwork(cm)
            if (physical != null) {
                defaultNetwork = physical
                return@withContext physical
            }
        }

        // Ожидание появления физической сети
        for (i in 0 until 30) {
            delay(100)
            defaultNetwork?.let { return@withContext it }
            if (cm != null) {
                val physical = findPhysicalNetwork(cm)
                if (physical != null) {
                    defaultNetwork = physical
                    return@withContext physical
                }
            }
        }

        throw java.net.UnknownHostException("Физическая сеть недоступна")
    }

    private val monitorScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private fun updateInterface(network: Network) {
        monitorScope.launch {
            val l = listener ?: return@launch
            val cm = connectivityManager ?: return@launch
            try {
                val caps = cm.getNetworkCapabilities(network)
                if (isVpnNetwork(caps, network)) {
                    Timber.w("DefaultNetworkMonitor: updateInterface вызван для VPN сети, игнорируем")
                    return@launch
                }

                val linkProps = cm.getLinkProperties(network) ?: return@launch
                val ifaceName = linkProps.interfaceName ?: return@launch

                // Не передаем VPN/Loopback-интерфейс
                if (ifaceName.lowercase().let { it == "lo" || it.startsWith("tun") || it.startsWith("ppp") || it.startsWith("p2p") || it.startsWith("dummy") }) {
                    Timber.w("DefaultNetworkMonitor: Пропуск нефизического интерфейса $ifaceName")
                    return@launch
                }

                var ifaceIndex = -1
                for (times in 0 until 10) {
                    try {
                        val ni = NetworkInterface.getByName(ifaceName)
                            ?: NetworkInterface.getNetworkInterfaces()?.toList()?.find { it.name == ifaceName }
                        if (ni != null) {
                            ifaceIndex = ni.index
                            break
                        }
                    } catch (_: Exception) {}
                    delay(50)
                }

                val isExpensive = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                val isConstrained = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && caps != null) {
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
                } else {
                    false
                }

                if (ifaceIndex != -1) {
                    l.updateDefaultInterface(ifaceName, ifaceIndex, isExpensive, isConstrained)
                    Timber.d("DefaultNetworkMonitor: Физический интерфейс обновлен: $ifaceName (index $ifaceIndex, isExpensive=$isExpensive, isConstrained=$isConstrained)")
                } else {
                    Timber.w("DefaultNetworkMonitor: Не удалось определить index для $ifaceName, передаем -1")
                    l.updateDefaultInterface(ifaceName, -1, isExpensive, isConstrained)
                }
            } catch (t: Throwable) {
                Timber.v("DefaultNetworkMonitor: Не удалось обновить интерфейс: ${t.message}")
            }
        }
    }
}
