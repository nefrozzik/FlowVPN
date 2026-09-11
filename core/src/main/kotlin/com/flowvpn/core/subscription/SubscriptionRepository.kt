package com.flowvpn.core.subscription

import com.flowvpn.core.model.ProxyServerConfig
import com.flowvpn.core.model.SubscriptionInfo
import kotlinx.coroutines.flow.Flow

/**
 * Интерфейс репозитория подписок (Clean Architecture boundary).
 *
 * Определяет контракт для хранения, загрузки и обновления подписок.
 * Реализация будет использовать Room/DataStore для персистентности.
 */
interface SubscriptionRepository {

    /**
     * Получить все подписки как реактивный поток.
     * Обновляется при любых изменениях.
     */
    fun getAllSubscriptions(): Flow<List<SubscriptionInfo>>

    /**
     * Получить текущие кэшированные подписки синхронно.
     */
    fun getCachedSubscriptions(): List<SubscriptionInfo> = emptyList()

    /**
     * Получить подписку по ID.
     */
    suspend fun getSubscription(id: String): SubscriptionInfo?

    /**
     * Добавить новую подписку.
     *
     * @param url URL подписки
     * @param name имя (опционально — будет извлечено из ответа)
     * @return созданная подписка с загруженными серверами
     */
    suspend fun addSubscription(url: String, name: String? = null): SubscriptionInfo

    /**
     * Обновить подписку (перезагрузить серверы по URL).
     *
     * @param id ID подписки
     * @return обновлённая подписка
     */
    suspend fun updateSubscription(id: String): SubscriptionInfo

    /**
     * Обновить все подписки.
     *
     * @return список обновлённых подписок
     */
    suspend fun updateAllSubscriptions(): List<SubscriptionInfo>

    /**
     * Удалить подписку и все связанные серверы.
     */
    suspend fun deleteSubscription(id: String)

    /**
     * Импортировать серверы из текста (clipboard, QR-код).
     * Создаёт виртуальную подписку "Imported" для группировки.
     *
     * @param content текст с ссылками/конфигурацией
     * @return количество импортированных серверов
     */
    suspend fun importFromText(content: String): Int

    /**
     * Добавить одиночный сервер (например, OpenFlux или кастомный профиль).
     * Добавляет сервер в группу "Ручной импорт".
     *
     * @param server конфигурация сервера
     * @return true при успехе
     */
    suspend fun addServer(server: ProxyServerConfig): Boolean

    /**
     * Удалить отдельный сервер по его ID из подписки (или ручного импорта).
     *
     * @param serverId ID сервера
     * @return true, если сервер был найден и удален
     */
    suspend fun deleteServer(serverId: String): Boolean

    /**
     * Обновить конфигурацию существующего сервера (редактирование параметров).
     *
     * @param server обновленная конфигурация сервера (с тем же ID)
     * @return true, если сервер был найден и обновлен
     */
    suspend fun updateServer(server: ProxyServerConfig): Boolean
}
