# consumer-rules.pro — правила ProGuard для потребителей модуля :vpn
# VPN-сервис и libbox bridge не обфусцируем
-keep class com.flowvpn.vpn.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
