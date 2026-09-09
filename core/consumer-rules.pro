# consumer-rules.pro — правила ProGuard для потребителей модуля :core
# Модели данных не обфусцируем (используются через Moshi JSON)
-keep class com.flowvpn.core.model.** { *; }
