package com.flowvpn.core.model

/**
 * Информация о подписке на VPN-серверы.
 *
 * Содержит URL, данные о трафике и сроке действия,
 * извлечённые из HTTP-заголовка `Subscription-Userinfo`:
 * ```
 * upload=455727941; download=6174315083; total=10737418240; expire=1671197839
 * ```
 */
data class SubscriptionInfo(
    /** Уникальный идентификатор подписки */
    val id: String,

    /** Отображаемое имя подписки */
    val name: String,

    /** URL для загрузки/обновления списка серверов */
    val url: String,

    /** Дата последнего обновления (epoch millis) */
    val lastUpdatedMs: Long? = null,

    /** Список серверов, полученных из подписки */
    val servers: List<ProxyServerConfig> = emptyList(),

    // ─── Subscription-Userinfo данные ───

    /** Загруженный трафик (байты) */
    val uploadBytes: Long? = null,

    /** Скачанный трафик (байты) */
    val downloadBytes: Long? = null,

    /** Общий лимит трафика (байты) */
    val totalBytes: Long? = null,

    /** Дата истечения подписки (epoch seconds) */
    val expireTimestamp: Long? = null,
) {
    /** Использованный трафик в процентах (0..100), null если нет данных */
    val usagePercent: Float?
        get() {
            val used = (uploadBytes ?: 0L) + (downloadBytes ?: 0L)
            val total = totalBytes ?: return null
            if (total <= 0L) return null
            return (used.toFloat() / total.toFloat() * 100f).coerceIn(0f, 100f)
        }

    /** Подписка истекла */
    val isExpired: Boolean
        get() {
            val expire = expireTimestamp ?: return false
            return System.currentTimeMillis() / 1000 > expire
        }
}
