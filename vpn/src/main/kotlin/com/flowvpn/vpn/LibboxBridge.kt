package com.flowvpn.vpn

/**
 * Placeholder-интерфейс для sing-box libbox API.
 *
 * Абстрагирует API скомпилированной Go-библиотеки `libbox.aar`,
 * позволяя компилировать и тестировать Kotlin-код без физического
 * наличия нативной библиотеки.
 *
 * При подключении реального `libbox.aar`, реализация этого интерфейса
 * будет делегировать вызовы к `io.nekohasekai.libbox.Libbox.*` классам.
 *
 * ## Архитектура sing-box libbox
 *
 * `libbox` — Go-библиотека, скомпилированная через GoMobile (`gomobile bind`).
 * GoMobile генерирует Java/Kotlin-совместимые обёртки для экспортированных
 * Go-типов. Ключевые компоненты:
 *
 * - **BoxService** — главный объект ядра, управляет жизненным циклом
 *   (create → start → close). Один BoxService = один VPN-сеанс.
 *
 * - **PlatformInterface** — callback-интерфейс, который Android-код
 *   должен реализовать. Через него ядро запрашивает у платформы:
 *   - Создание TUN-интерфейса (openTun)
 *   - Логирование
 *   - Определение владельца сетевого соединения (для per-app routing)
 *
 * - **CommandServer/CommandClient** — IPC-механизм для обмена статусом
 *   и метриками между VPN-сервисом и UI (через Unix domain socket).
 */
interface LibboxBridge {

    /**
     * Создать новый экземпляр sing-box сервиса.
     *
     * @param configPath абсолютный путь к файлу config.json
     * @param platformInterface реализация платформенного интерфейса
     * @return handle для управления сервисом
     */
    fun newService(configPath: String, platformInterface: PlatformBridge): Long

    /**
     * Запустить sing-box сервис.
     * После вызова ядро начнёт слушать TUN и обрабатывать трафик.
     *
     * @param handle идентификатор сервиса от [newService]
     * @throws Exception при ошибке запуска (невалидный конфиг, порт занят и т.д.)
     */
    fun startService(handle: Long)

    /**
     * Остановить sing-box сервис и освободить ресурсы.
     * Закрывает все соединения и TUN-интерфейс.
     *
     * @param handle идентификатор сервиса
     */
    fun closeService(handle: Long)

    /**
     * Проверить валидность конфигурации без запуска.
     *
     * @param configContent JSON-строка конфигурации
     * @return null если валидна, сообщение об ошибке если нет
     */
    fun checkConfig(configContent: String): String?

    /**
     * Получить версию sing-box ядра.
     */
    fun version(): String
}

/**
 * Платформенный callback-интерфейс.
 *
 * Реализация ([FlowPlatformInterface]) передаётся в sing-box core
 * при создании BoxService. Ядро вызывает эти методы для взаимодействия
 * с Android-платформой.
 */
interface PlatformBridge {

    /**
     * Создать TUN-интерфейс через Android VpnService.
     *
     * Вызывается ядром sing-box когда TUN inbound готов принимать трафик.
     * Реализация должна:
     * 1. Вызвать VpnService.Builder с параметрами из [tunOptions]
     * 2. Вызвать builder.establish()
     * 3. Вернуть ParcelFileDescriptor.getFd()
     *
     * @param tunOptions параметры TUN (MTU, адреса, DNS, маршруты)
     * @return файловый дескриптор TUN-интерфейса
     */
    fun openTun(tunOptions: TunOptionsData): Int

    /**
     * Записать лог-сообщение от ядра.
     */
    fun writeLog(message: String)

    /**
     * Определить UID (Android User ID) владельца TCP/UDP соединения.
     * Используется для per-app routing.
     *
     * @param protocol 6 = TCP, 17 = UDP
     * @param sourceAddress IP источника
     * @param sourcePort порт источника
     * @param destinationAddress IP назначения
     * @param destinationPort порт назначения
     * @return UID приложения-владельца или -1
     */
    fun findConnectionOwner(
        protocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): Int

    /**
     * Получить имя пакета по Android UID.
     */
    fun packageNameByUid(uid: Int): String

    /**
     * Доступен ли /proc/net для определения владельца соединений.
     * На Android 10+ требует VPN или root.
     */
    fun useProcFS(): Boolean
}

/**
 * Параметры TUN-интерфейса, передаваемые из ядра в Android.
 *
 * Соответствует Go-типу `libbox.TunOptions`.
 * Эти параметры используются для настройки VpnService.Builder.
 */
data class TunOptionsData(
    val inet4Address: String = "172.19.0.1/30",
    val inet6Address: String = "fdfe:dcba:9876::1/126",
    val mtu: Int = 1500,
    val autoRoute: Boolean = true,
    val dnsServerAddress: String = "172.19.0.2",
    val strictRoute: Boolean = true,
)

/**
 * Placeholder-реализация [LibboxBridge] для разработки без libbox.aar.
 *
 * Все методы логируют вызовы через Timber.
 * Заменить на реальную реализацию через `io.nekohasekai.libbox.Libbox`
 * после подключения AAR.
 */
class PlaceholderLibboxBridge : LibboxBridge {
    private var serviceCounter = 0L
    private var platformInterface: PlatformBridge? = null
    private var openedFd: Int = -1

    override fun newService(configPath: String, platformInterface: PlatformBridge): Long {
        timber.log.Timber.d("PlaceholderLibbox: newService(config=$configPath)")
        this.platformInterface = platformInterface
        return ++serviceCounter
    }

    override fun startService(handle: Long) {
        timber.log.Timber.d("PlaceholderLibbox: startService(handle=$handle)")
        val fd = platformInterface?.openTun(TunOptionsData()) ?: -1
        if (fd < 0) {
            throw IllegalStateException("Не удалось создать виртуальный TUN интерфейс Android (establish returned null/fd=-1)")
        }
        openedFd = fd
        timber.log.Timber.i("PlaceholderLibbox: TUN интерфейс успешно запущен с дескриптором fd=$fd")
    }

    override fun closeService(handle: Long) {
        timber.log.Timber.d("PlaceholderLibbox: closeService(handle=$handle)")
        openedFd = -1
        platformInterface = null
    }

    override fun checkConfig(configContent: String): String? {
        timber.log.Timber.d("PlaceholderLibbox: checkConfig(${configContent.take(100)}...)")
        return null // null = валиден
    }

    override fun version(): String = "placeholder-1.14.0"
}
