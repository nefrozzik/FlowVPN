package com.flowvpn.vpn

import com.flowvpn.core.logger.CoreLogManager
import com.flowvpn.core.logger.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Менеджер раздачи VPN через точку доступа Wi-Fi (Tethering / Hotspot) с помощью Root-прав.
 *
 * В Android по умолчанию сетевой стек перенаправляет трафик точки доступа в обход
 * виртуального TUN-интерфейса напрямую в сотовую сеть или Wi-Fi.
 *
 * [RootTetheringManager] применяет правила iptables (MASQUERADE, FORWARD) и policy routing
 * в ядре Linux через команду `su`, направляя трафик всех подключенных клиентов через VPN (`tun0`).
 */
object RootTetheringManager {

    private const val TAG = "RootTether"
    private const val TUN_INTERFACE = "tun0"
    private const val ROUTING_TABLE_ID = 60

    /**
     * Проверка доступности Root-прав (наличие бинарника su и ответ id uid=0).
     * Таймаут 10 секунд достаточен для отображения системного диалога запроса суперпользователя (Magisk / KernelSU / APatch).
     */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext false
            }
            val output = process.inputStream.bufferedReader().readText()
            if (process.exitValue() == 0) {
                output.contains("uid=0")
            } else {
                false
            }
        } catch (e: Exception) {
            Timber.d(e, "Root недоступен: ${e.message}")
            false
        }
    }

    /**
     * Активировать раздачу VPN-трафика через точку доступа.
     */
    suspend fun enableTethering(): Boolean = withContext(Dispatchers.IO) {
        CoreLogManager.log("Активация раздачи VPN через точку доступа (Root)...", LogLevel.INFO, tag = TAG)

        val commands = listOf(
            // 1. Включение IP forwarding в ядре Linux
            "sysctl -w net.ipv4.ip_forward=1",
            "echo 1 > /proc/sys/net/ipv4/ip_forward",

            // 2. Очистка предыдущих правил MASQUERADE
            "iptables -t nat -D POSTROUTING -o $TUN_INTERFACE -j MASQUERADE 2>/dev/null || true",
            // Добавление NAT для всех пакетов, уходящих в tun0
            "iptables -t nat -A POSTROUTING -o $TUN_INTERFACE -j MASQUERADE",

            // 3. Разрешение пересылки пакетов между интерфейсами hotspot и tun0
            "iptables -D FORWARD -o $TUN_INTERFACE -j ACCEPT 2>/dev/null || true",
            "iptables -I FORWARD 1 -o $TUN_INTERFACE -j ACCEPT",
            "iptables -D FORWARD -i $TUN_INTERFACE -j ACCEPT 2>/dev/null || true",
            "iptables -I FORWARD 2 -i $TUN_INTERFACE -j ACCEPT",

            // 4. Перенаправление DNS-запросов от клиентов hotspot (UDP/TCP 53) в VPN
            "iptables -t nat -D PREROUTING -p udp --dport 53 -j DNAT --to-destination 1.1.1.1:53 2>/dev/null || true",
            "iptables -t nat -D PREROUTING -p tcp --dport 53 -j DNAT --to-destination 1.1.1.1:53 2>/dev/null || true",
            "iptables -t nat -A PREROUTING -p udp --dport 53 -j DNAT --to-destination 1.1.1.1:53 2>/dev/null || true",
            "iptables -t nat -A PREROUTING -p tcp --dport 53 -j DNAT --to-destination 1.1.1.1:53 2>/dev/null || true",

            // 5. Policy routing для стандартных подсетей раздачи Android (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
            "ip rule del from 192.168.0.0/16 table $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip rule add from 192.168.0.0/16 table $ROUTING_TABLE_ID pref $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip rule del from 10.0.0.0/8 table $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip rule add from 10.0.0.0/8 table $ROUTING_TABLE_ID pref $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip rule del from 172.16.0.0/12 table $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip rule add from 172.16.0.0/12 table $ROUTING_TABLE_ID pref $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip route flush table $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip route add default dev $TUN_INTERFACE table $ROUTING_TABLE_ID 2>/dev/null || true"
        )

        val success = executeCommands(commands)
        if (success) {
            CoreLogManager.log("Раздача VPN через точку доступа успешно активирована", LogLevel.INFO, tag = TAG)
            Timber.i("RootTetheringManager: Tethering rules applied successfully")
        } else {
            CoreLogManager.log("Ошибка применения правил раздачи VPN через root", LogLevel.ERROR, tag = TAG)
            Timber.w("RootTetheringManager: Failed to apply some tethering rules")
        }
        return@withContext success
    }

    /**
     * Деактивировать раздачу VPN и очистить правила iptables / ip rule.
     */
    suspend fun disableTethering(): Boolean = withContext(Dispatchers.IO) {
        CoreLogManager.log("Отключение раздачи VPN через точку доступа...", LogLevel.INFO, tag = TAG)

        val commands = listOf(
            "iptables -t nat -D POSTROUTING -o $TUN_INTERFACE -j MASQUERADE 2>/dev/null || true",
            "iptables -D FORWARD -o $TUN_INTERFACE -j ACCEPT 2>/dev/null || true",
            "iptables -D FORWARD -i $TUN_INTERFACE -j ACCEPT 2>/dev/null || true",
            "iptables -t nat -D PREROUTING -p udp --dport 53 -j DNAT --to-destination 1.1.1.1:53 2>/dev/null || true",
            "iptables -t nat -D PREROUTING -p tcp --dport 53 -j DNAT --to-destination 1.1.1.1:53 2>/dev/null || true",
            "ip rule del from 192.168.0.0/16 table $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip rule del from 10.0.0.0/8 table $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip rule del from 172.16.0.0/12 table $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip route flush table $ROUTING_TABLE_ID 2>/dev/null || true"
        )

        val success = executeCommands(commands)
        CoreLogManager.log("Правила раздачи VPN очищены", LogLevel.INFO, tag = TAG)
        return@withContext success
    }

    private fun executeCommands(commands: List<String>): Boolean {
        return try {
            val script = commands.joinToString("\n")
            val process = ProcessBuilder("su").redirectErrorStream(true).start()

            process.outputStream.bufferedWriter().use { writer ->
                writer.write(script)
                writer.write("\nexit\n")
                writer.flush()
            }

            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                Timber.w("RootTetheringManager exitCode=$exitCode: $output")
            }
            exitCode == 0
        } catch (e: Exception) {
            Timber.e(e, "RootTetheringManager: Ошибка выполнения root-скрипта")
            false
        }
    }
}
