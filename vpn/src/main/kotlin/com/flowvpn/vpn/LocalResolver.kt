package com.flowvpn.vpn

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import com.hiddify.core.libbox.ExchangeContext
import com.hiddify.core.libbox.LocalDNSTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Реализация [LocalDNSTransport] для прямого разрешения локальных DNS-запросов через
 * физический сетевой стек устройства (минуя VPN-туннель).
 * Защищена от дедлоков и утечек потоков таймаутом и атомарным контролем возобновления корутин.
 */
object LocalResolver : LocalDNSTransport {
    private const val RCODE_NXDOMAIN = 3
    private const val QUERY_TIMEOUT_MS = 5000L

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = try {
            runBlocking { DefaultNetworkMonitor.require() }
        } catch (t: Throwable) {
            Timber.w("LocalResolver.exchange: сеть недоступна (${t.message})")
            runCatching { ctx.errorCode(RCODE_NXDOMAIN) }
            return
        }

        try {
            runBlocking {
                withTimeoutOrNull(QUERY_TIMEOUT_MS) {
                    suspendCoroutine<Unit> { continuation ->
                        val resumed = AtomicBoolean(false)
                        val signal = CancellationSignal()
                        ctx.onCancel {
                            signal.cancel()
                            if (resumed.compareAndSet(false, true)) {
                                runCatching { ctx.errorCode(RCODE_NXDOMAIN) }
                                runCatching { continuation.resume(Unit) }
                            }
                        }

                        val callback = object : DnsResolver.Callback<ByteArray> {
                            override fun onAnswer(answer: ByteArray, rcode: Int) {
                                if (resumed.compareAndSet(false, true)) {
                                    if (rcode == 0) {
                                        ctx.rawSuccess(answer)
                                    } else {
                                        ctx.errorCode(rcode)
                                    }
                                    runCatching { continuation.resume(Unit) }
                                }
                            }

                            override fun onError(error: DnsResolver.DnsException) {
                                if (resumed.compareAndSet(false, true)) {
                                    when (val cause = error.cause) {
                                        is ErrnoException -> ctx.errnoCode(cause.errno)
                                        else -> ctx.errorCode(RCODE_NXDOMAIN)
                                    }
                                    runCatching { continuation.resume(Unit) }
                                }
                            }
                        }

                        DnsResolver.getInstance().rawQuery(
                            network,
                            message,
                            DnsResolver.FLAG_NO_RETRY,
                            Dispatchers.IO.asExecutor(),
                            signal,
                            callback,
                        )
                    }
                } ?: run {
                    runCatching { ctx.errorCode(RCODE_NXDOMAIN) }
                }
            }
        } catch (t: Throwable) {
            runCatching { ctx.errorCode(RCODE_NXDOMAIN) }
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val defaultNet = try {
            runBlocking { DefaultNetworkMonitor.require() }
        } catch (t: Throwable) {
            Timber.w("LocalResolver.lookup: сеть недоступна (${t.message})")
            null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && defaultNet != null) {
            try {
                runBlocking {
                    withTimeoutOrNull(QUERY_TIMEOUT_MS) {
                        suspendCoroutine<Unit> { continuation ->
                            val resumed = AtomicBoolean(false)
                            val signal = CancellationSignal()
                            ctx.onCancel {
                                signal.cancel()
                                if (resumed.compareAndSet(false, true)) {
                                    runCatching { ctx.errorCode(RCODE_NXDOMAIN) }
                                    runCatching { continuation.resume(Unit) }
                                }
                            }

                            val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                                override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                                    if (resumed.compareAndSet(false, true)) {
                                        if (rcode == 0) {
                                            ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                                        } else {
                                            ctx.errorCode(rcode)
                                        }
                                        runCatching { continuation.resume(Unit) }
                                    }
                                }

                                override fun onError(error: DnsResolver.DnsException) {
                                    if (resumed.compareAndSet(false, true)) {
                                        when (val cause = error.cause) {
                                            is ErrnoException -> ctx.errnoCode(cause.errno)
                                            else -> ctx.errorCode(RCODE_NXDOMAIN)
                                        }
                                        runCatching { continuation.resume(Unit) }
                                    }
                                }
                            }

                            val type = when {
                                network.endsWith("4") -> DnsResolver.TYPE_A
                                network.endsWith("6") -> DnsResolver.TYPE_AAAA
                                else -> null
                            }

                            if (type != null) {
                                DnsResolver.getInstance().query(
                                    defaultNet,
                                    domain,
                                    type,
                                    DnsResolver.FLAG_NO_RETRY,
                                    Dispatchers.IO.asExecutor(),
                                    signal,
                                    callback,
                                )
                            } else {
                                DnsResolver.getInstance().query(
                                    defaultNet,
                                    domain,
                                    DnsResolver.FLAG_NO_RETRY,
                                    Dispatchers.IO.asExecutor(),
                                    signal,
                                    callback,
                                )
                            }
                        }
                    } ?: run {
                        runCatching { ctx.errorCode(RCODE_NXDOMAIN) }
                    }
                }
            } catch (_: Throwable) {
                runCatching { ctx.errorCode(RCODE_NXDOMAIN) }
            }
        } else {
            try {
                val addresses = if (defaultNet != null) {
                    defaultNet.getAllByName(domain)
                } else {
                    InetAddress.getAllByName(domain)
                }
                ctx.success(addresses.mapNotNull { it.hostAddress }.joinToString("\n"))
            } catch (e: Exception) {
                runCatching { ctx.errorCode(RCODE_NXDOMAIN) }
            }
        }
    }
}
