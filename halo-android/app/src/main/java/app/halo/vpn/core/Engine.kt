package app.halo.vpn.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed interface VpnState {
    data object Off : VpnState
    data object Connecting : VpnState
    data class On(val since: Long) : VpnState
    data class Failed(val message: String) : VpnState
}

/** Ядро в процессе приложения: одно на всё приложение (экраны, служба VPN, плитка). */
object Engine {
    private val _state = MutableStateFlow<VpnState>(VpnState.Off)
    val state: StateFlow<VpnState> = _state

    @Volatile
    var coreRunning = false
        private set

    @Volatile
    var controllerPort = 9097
        private set

    @Volatile
    var mixedPort = 7890
        private set

    /** Вход ядра, который всегда ведёт через VPN-сервер (без правил): им качаем подписки, заблокированные у провайдера. */
    @Volatile
    var vpnPort = 7891
        private set

    /** Последняя ошибка ядра (для экрана «Серверы» и отчёта). */
    @Volatile
    var lastError: String? = null
        private set

    /** Сервер, через который идёт трафик (для плитки в шторке). */
    @Volatile
    var currentServer: String? = null

    /** Дескриптор туннеля, с которым работает ядро (null — VPN выключен). */
    @Volatile
    var tunFd: Int? = null

    /**
     * Новый конфиг без пересоздания туннеля: ядро сравнивает настройки tun и, если они те же, туннель не трогает.
     * Так смена режима или «Без рекламы» применяются мгновенно, без разрыва соединений.
     */
    fun reapply(ctx: Context): String? = apply(ctx, tunFd)

    fun setState(s: VpnState) {
        _state.value = s
    }

    fun home(ctx: Context) = File(ctx.filesDir, "core").apply { mkdirs() }

    /** Применяет конфиг: с туннелем ([tunFd]) или без. Возвращает текст ошибки или null. */
    @Synchronized
    fun apply(ctx: Context, tunFd: Int?): String? {
        if (!Core.available) return "Ядро не собрано: в APK нет libhalo.so (см. README)"
        val store = Store(ctx)
        val home = home(ctx)
        installGeo(ctx, store, home)
        // Порты выбираем при запуске ядра; у работающего ядра они уже его собственные.
        if (!coreRunning) {
            controllerPort = freePort(9097)
            mixedPort = freePort(7890)
            vpnPort = freePort(7891).takeIf { it != mixedPort } ?: freePort(0)
        }
        val t0 = System.currentTimeMillis()
        val wasRunning = coreRunning
        val err = Core.nativeApply(home.path, ConfigBuilder.build(store, tunFd))
        val what = (if (wasRunning) "новые настройки" else "запуск ядра") + (if (tunFd != null) " с туннелем" else " без туннеля")
        if (err.isEmpty()) {
            coreRunning = true
            lastError = null
            HaloLog.i("ядро", "$what: готово за ${System.currentTimeMillis() - t0} мс · порты $controllerPort/$mixedPort · режим «${store.mode.title}» · подписка ${if (store.isAll) "«Все VPN в одном» (${store.subscriptions.size})" else store.activeSub?.title ?: "нет"}")
        } else {
            lastError = err
            HaloLog.e("ядро", "$what: ошибка — $err")
        }
        return err.ifEmpty { null }
    }

    /** Предпочтительный порт, если свободен, иначе любой свободный на 127.0.0.1. */
    private fun freePort(preferred: Int): Int {
        fun tryBind(port: Int) = runCatching {
            java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }.getOrNull()
        return tryBind(preferred) ?: tryBind(0) ?: preferred
    }

    /** Ядро без туннеля, если ещё не запущено: для списка серверов и пинга при выключенном VPN. */
    fun ensureIdle(ctx: Context): String? = if (coreRunning) null else apply(ctx, null)

    @Synchronized
    fun stop() {
        if (coreRunning) {
            Core.nativeStop()
            coreRunning = false
            HaloLog.i("ядро", "остановлено")
        }
    }

    /**
     * Урезанные гео-базы из APK (tools/trim-geo.cjs): при первом запуске, после обновления приложения
     * и если файла нет (ядро удаляет базу, которую считает битой). Базы, скачанные в фоне (GeoUpdater), не трогаем.
     */
    private fun installGeo(ctx: Context, store: Store, home: File) {
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
        val appUpdated = store.geoVersion != version
        for (name in listOf("GeoIP.dat", "GeoSite.dat")) {
            val file = File(home, name)
            if (file.length() > 0 && !appUpdated) continue
            ctx.assets.open("geo/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        if (appUpdated) {
            HaloLog.i("гео", "списки блокировок из приложения (версия $version)")
            store.geoVersion = version
            store.geoUpdatedAt = 0
        }
    }
}
