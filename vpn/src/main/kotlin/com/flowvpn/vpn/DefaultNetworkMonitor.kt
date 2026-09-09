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
            }
        }
    }

    private fun isVpnNetwork(caps: NetworkCapabilities?, network: Network): Boolean {
        if (caps == null) return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        val cm = connectivityManager ?: return false
        val lp = cm.getLinkProperties(network)
        val iface = lp?.interfaceName?.lowercase() ?: ""
        return iface.startsWith("tun") || iface.startsWith("ppp") || iface.startsWith("p2p")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = cm.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                if (!isVpnNetwork(caps, active)) {
                    defaultNetwork = active
                }
            }
        }

        if (isRegistered) {
            defaultNetwork?.let { updateInterface(it) }
            return
        }

        // Запрос сети строго без VPN (NET_CAPABILITY_NOT_VPN гарантируется NetworkRequest по умолчанию)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
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
            Timber.w(e, "DefaultNetworkMonitor: Ошибка регистрации сетевого коллбэка")
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
        defaultNetwork?.let { updateInterface(it) }
    }

    /**
     * Получение текущей физической сети с ожиданием (до 3 сек),
     * предотвращает возврат NXDOMAIN при старте сервиса.
     */
    suspend fun require(): Network = withContext(Dispatchers.IO) {
        defaultNetwork?.let { return@withContext it }

        val cm = connectivityManager
        if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = cm.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                if (!isVpnNetwork(caps, active)) {
                    defaultNetwork = active
                    return@withContext active
                }
            }
        }

        // Ожидание появления физической сети
        for (i in 0 until 30) {
            delay(100)
            defaultNetwork?.let { return@withContext it }
        }

        throw java.net.UnknownHostException("Физическая сеть недоступна")
    }

    private val monitorScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private fun updateInterface(network: Network) {
        monitorScope.launch {
            val l = listener ?: return@launch
            val cm = connectivityManager ?: return@launch
            try {
                val linkProps = cm.getLinkProperties(network) ?: return@launch
                val ifaceName = linkProps.interfaceName ?: return@launch

                // Не передаем VPN-интерфейс
                if (ifaceName.lowercase().startsWith("tun")) {
                    Timber.w("DefaultNetworkMonitor: Пропуск VPN интерфейса $ifaceName")
                    return@launch
                }

                var ifaceIndex = -1
                for (times in 0 until 10) {
                    try {
                        val ni = NetworkInterface.getByName(ifaceName)
                        if (ni != null) {
                            ifaceIndex = ni.index
                            break
                        }
                    } catch (_: Exception) {}
                    delay(50)
                }

                if (ifaceIndex != -1) {
                    l.updateDefaultInterface(ifaceName, ifaceIndex, false, false)
                    Timber.d("DefaultNetworkMonitor: Интерфейс обновлен: $ifaceName (index $ifaceIndex)")
                } else {
                    l.updateDefaultInterface(ifaceName, -1, false, false)
                }
            } catch (t: Throwable) {
                Timber.v("DefaultNetworkMonitor: Не удалось обновить интерфейс: ${t.message}")
            }
        }
    }
}
