package app.halo.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.IntentFilter
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import app.halo.vpn.core.AppsMode
import app.halo.vpn.core.AutoPicker
import app.halo.vpn.core.ConfigBuilder
import app.halo.vpn.core.Controller
import app.halo.vpn.core.Countries
import app.halo.vpn.core.Engine
import app.halo.vpn.core.HaloLog
import app.halo.vpn.core.HaloNet
import app.halo.vpn.core.Store
import app.halo.vpn.core.VpnState
import app.halo.vpn.ui.Flags
import app.halo.vpn.ui.speed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * VPN: Android отдаёт дескриптор туннеля, ядро mihomo читает из него весь трафик и решает по правилам,
 * что идёт через сервер, а что напрямую. Само приложение исключено из туннеля — так ядро выходит в сеть
 * мимо VPN и не зацикливается.
 */
class HaloVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stats: Job? = null
    private var watchdog: Job? = null

    /**
     * Экран выключен — уведомление никто не видит: не опрашиваем ядро и не перерисовываем уведомление,
     * чтобы телефон спал. Включили экран — продолжаем.
     */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stats?.cancel()
                Intent.ACTION_SCREEN_ON -> if (Engine.state.value is VpnState.On) startStats()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                Store(this).wantOn = false
                scope.launch { disconnect() }
                return START_NOT_STICKY
            }
            ACTION_NEXT_SERVER -> {
                scope.launch { nextServer() }
                return START_STICKY
            }
            // CONNECT, CONNECT_AUTO (плитка), RECONNECT или запуск системой (постоянный VPN в настройках Samsung).
            else -> {
                startForegroundCompat(notification("Подключение…", null, null))
                Store(this).wantOn = true
                val auto = intent?.action == ACTION_CONNECT_AUTO
                scope.launch { connect(auto) }
            }
        }
        return START_STICKY
    }

    @Synchronized
    private fun connect(auto: Boolean = false) {
        Engine.setState(VpnState.Connecting)
        refreshTile()
        val pfd = try {
            builder().establish()
        } catch (e: Exception) {
            null
        }
        if (pfd == null) {
            HaloLog.e("vpn", "Android не дал создать туннель (нет разрешения или включён другой VPN)")
            fail("Android не дал создать VPN. Откройте Halo VPN и разрешите подключение.")
            return
        }
        // Дескриптор отдаём ядру насовсем: оно закроет его само, когда туннель больше не нужен.
        val fd = pfd.detachFd()
        val err = Engine.apply(this, fd)
        if (err == null) Engine.tunFd = fd
        if (err != null) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            fail(err)
            return
        }
        Store(this).lockdown = Build.VERSION.SDK_INT >= 29 && isLockdownEnabled
        HaloLog.i("vpn", "подключено · постоянный VPN: ${Build.VERSION.SDK_INT >= 29 && isAlwaysOn} · Kill Switch: ${Store(this).lockdown}")
        val since = (Engine.state.value as? VpnState.On)?.since ?: System.currentTimeMillis()
        Engine.setState(VpnState.On(since))
        // С плитки в шторке — всегда «Авто · лучший».
        if (auto) runCatching { Controller(Store(this).secret).select(ConfigBuilder.AUTO) }.onSuccess { HaloLog.i("vpn", "плитка: выбран «${ConfigBuilder.AUTO}»") }
        refreshTile()
        startStats()
        startWatchdog()
        // Ядро перезапустилось с туннелем — замеры пинга сброшены, проверяем серверы заново и выбираем основной.
        scope.launch { rankAll() }
    }

    private fun builder(): Builder {
        val store = Store(this)
        val b = Builder()
            .setSession("Halo VPN")
            .setMtu(HaloNet.MTU)
            .addAddress(HaloNet.ADDRESS, 30)
            .addRoute("0.0.0.0", 0)
            // IPv6 тоже в туннель: ядро его отклоняет и приложения уходят на IPv4 — без утечек мимо VPN.
            .addAddress(HaloNet.ADDRESS6, 126)
            .addRoute("::", 0)
            .addDnsServer(HaloNet.DNS)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
        b.setMetered(false)
        val selected = store.vpnApps - packageName
        if (store.appsMode == AppsMode.Selected && selected.isNotEmpty()) {
            var added = 0
            for (app in selected) if (runCatching { b.addAllowedApplication(app) }.isSuccess) added++
            if (added == 0) b.addDisallowedApplication(packageName)
        } else {
            b.addDisallowedApplication(packageName)
            if (store.appsMode == AppsMode.Except) for (app in store.bypassApps - packageName) runCatching { b.addDisallowedApplication(app) }
        }
        return b
    }

    /**
     * Уведомление как в макете: сервер и пинг, скорость, «Отключить» и «Сменить сервер».
     * Скорость — из потока ядра /traffic (одно соединение вместо опроса всех соединений), сервер — раз в 10 с.
     */
    private fun startStats() {
        stats?.cancel()
        if (!getSystemService(PowerManager::class.java).isInteractive) return
        stats = scope.launch {
            val api = Controller(Store(this@HaloVpnService).secret)
            var up = 0L
            var down = 0L
            launch {
                while (isActive) {
                    runCatching {
                        val c = api.openTraffic()
                        val h = coroutineContext[Job]?.invokeOnCompletion { c.disconnect() }
                        try {
                            api.readTraffic(c) { u, d -> up = u; down = d }
                        } finally {
                            h?.dispose()
                        }
                    }
                    delay(3000)
                }
            }
            var shown: String? = null
            var title = "VPN активен"
            var country: String? = null
            var tick = 0
            val period = if (Store(this@HaloVpnService).batterySaver) 5000L else 2000L
            while (isActive && Engine.state.value is VpnState.On) {
                if (tick++ % (10_000 / period).toInt().coerceAtLeast(1) == 0) runCatching {
                    val list = api.servers()
                    val name = list.current
                    val d = list.servers.firstOrNull { it.name == name }?.delay
                    title = name?.let { Countries.clean(it) + (d?.let { x -> " · $x мс" } ?: "") } ?: "VPN активен"
                    country = name?.let { Countries.code(it) }
                    if (Engine.currentServer != name) {
                        Engine.currentServer = name
                        refreshTile()
                    }
                }
                val text = "↓ ${speed(down)} · ↑ ${speed(up)}"
                // Перерисовываем уведомление, только если в нём что-то поменялось.
                if (shown != title + text) {
                    shown = title + text
                    notify(notification(title, text, country))
                }
                delay(period)
            }
        }
    }

    /** Пинг всех серверов и (в «Авто») выбор основного по правилам AutoPicker. */
    private suspend fun rankAll() {
        val api = Controller(Store(this).secret)
        runCatching { AutoPicker.rank(api, api.testAll()) }.onFailure { HaloLog.wOnce("авто", "проверка серверов не удалась", it) }
        lastRank = System.currentTimeMillis()
    }

    private var lastRank = 0L

    /**
     * Автопереключение: раз в 30 с (настройка: 10/30/60) пингуем текущий сервер. Не ответил дважды подряд (и интернет вообще есть) —
     * переключаемся на лучший из отвечающих. Для «Авто · лучший» закрепляем найденный сервер в url-test,
     * для выбранного вручную — выбираем другой. Экран выключен и включена экономия — раз в 60 с.
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        if (!Store(this).failover) return
        watchdog = scope.launch {
            val api = Controller(Store(this@HaloVpnService).secret)
            val store = Store(this@HaloVpnService)
            delay(store.checkSeconds * 1000L)
            while (isActive && Engine.state.value is VpnState.On) {
                runCatching { checkServer(api) }.onFailure { HaloLog.wOnce("авто", "проверка сервера не удалась", it) }
                val screenOn = getSystemService(PowerManager::class.java).isInteractive
                // Раз в 15 мин (с экономией — раз в час) заново сравниваем все серверы: вдруг появился заметно лучше.
                val rankEvery = if (store.batterySaver) 3600_000L else 900_000L
                if (screenOn && System.currentTimeMillis() - lastRank > rankEvery) rankAll()
                val sec = store.checkSeconds.coerceIn(10, 60)
                delay(if (!screenOn && store.batterySaver) maxOf(60, sec) * 1000L else sec * 1000L)
            }
        }
    }

    private suspend fun checkServer(api: Controller) {
        if (!hasInternet()) return // нет сети вообще — сервер тут ни при чём
        val list = api.servers()
        val cur = list.current ?: return
        if (api.delay(cur) != null) return
        delay(3000)
        if (!hasInternet() || api.delay(cur) != null) return
        val auto = list.selected == ConfigBuilder.AUTO
        // Кандидаты: лучшие по последнему пингу, для «Авто» — без российских узлов. Проверяем пятерых сразу.
        val candidates = list.servers.filter { it.name != cur && (!auto || !isRuExit(it.name)) }
            .sortedBy { it.delay ?: Int.MAX_VALUE }.take(5)
        val best = coroutineScope {
            candidates.map { s -> async { s.name to api.delay(s.name, 3000) } }.awaitAll()
        }.filter { it.second != null }.minByOrNull { it.second!! }
        if (best == null) {
            HaloLog.w("авто", "«${Countries.clean(cur)}» не отвечает, и другие серверы тоже — жду")
            return
        }
        if (auto) runCatching { api.fixAuto(best.first) }.onFailure { api.select(best.first) } else api.select(best.first)
        HaloLog.w("авто", "«${Countries.clean(cur)}» не отвечает — переключился на «${Countries.clean(best.first)}» (${best.second} мс)")
        Engine.currentServer = best.first
        refreshTile()
        startStats()
    }

    private fun isRuExit(name: String) = Regex("(?i)\uD83C\uDDF7\uD83C\uDDFA|росси|(^|[^а-яё])рф([^а-яё]|$)|russia|moscow|москв").containsMatchIn(name)

    private fun hasInternet(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        // Приложение исключено из VPN — activeNetwork у него настоящая сеть (Wi-Fi или мобильная).
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun nextServer() {
        val api = Controller(Store(this).secret)
        runCatching {
            val list = api.servers()
            val next = list.servers.filter { it.name != list.current }
                .sortedBy { it.delay ?: Int.MAX_VALUE }.firstOrNull() ?: return
            api.select(next.name)
        }
    }

    private fun disconnect() {
        HaloLog.i("vpn", "отключено")
        stats?.cancel()
        watchdog?.cancel()
        Engine.currentServer = null
        Engine.tunFd = null
        // Ядро остаётся без туннеля, пока открыт интерфейс; иначе останавливаем совсем.
        if (MainActivity.visible) Engine.apply(this, null) else Engine.stop()
        Engine.setState(VpnState.Off)
        refreshTile()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        HaloLog.e("vpn", "не удалось подключиться: $message")
        watchdog?.cancel()
        stats?.cancel()
        Engine.tunFd = null
        Store(this).wantOn = false
        if (MainActivity.visible) Engine.apply(this, null) else Engine.stop()
        Engine.setState(VpnState.Failed(message))
        refreshTile()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Туннель отобрали: включили другой VPN или отключили в настройках телефона. */
    override fun onRevoke() {
        HaloLog.w("vpn", "Android отозвал VPN: включили другой VPN или выключили в настройках")
        Store(this).wantOn = false
        scope.launch { disconnect() }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshTile() {
        TileService.requestListeningState(this, ComponentName(this, HaloTile::class.java))
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun notification(title: String, text: String?, country: String?): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HaloVpnService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        val next = PendingIntent.getService(
            this, 2, Intent(this, HaloVpnService::class.java).setAction(ACTION_NEXT_SERVER), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setSubText("VPN активен")
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null as Icon?, "Отключить", stop).build())
            .addAction(Notification.Action.Builder(null as Icon?, "Сменить сервер", next).build())
        text?.let { b.setContentText(it) }
        Flags.bitmap(this, country)?.let { b.setLargeIcon(it) }
        return b.build()
    }

    companion object {
        const val ACTION_CONNECT = "app.halo.vpn.CONNECT"
        const val ACTION_CONNECT_AUTO = "app.halo.vpn.CONNECT_AUTO"
        const val ACTION_RECONNECT = "app.halo.vpn.RECONNECT"
        const val ACTION_DISCONNECT = "app.halo.vpn.DISCONNECT"
        const val ACTION_NEXT_SERVER = "app.halo.vpn.NEXT_SERVER"
        const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Состояние VPN", NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    },
                )
            }
        }

        fun start(ctx: Context, action: String) {
            val i = Intent(ctx, HaloVpnService::class.java).setAction(action)
            if (action == ACTION_DISCONNECT || action == ACTION_NEXT_SERVER) ctx.startService(i) else ctx.startForegroundService(i)
        }
    }
}
