package app.halo.vpn.ui

import android.app.Application
import android.content.Intent
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.halo.vpn.HaloVpnService
import app.halo.vpn.WifiWatcher
import app.halo.vpn.core.AppsMode
import app.halo.vpn.core.AutoPicker
import app.halo.vpn.core.ConfigBuilder
import app.halo.vpn.core.Conn
import app.halo.vpn.core.Controller
import app.halo.vpn.core.Core
import app.halo.vpn.core.Doh
import app.halo.vpn.core.GeoUpdater
import app.halo.vpn.core.HaloLog
import app.halo.vpn.core.IpInfo
import app.halo.vpn.core.SiteAction
import app.halo.vpn.core.Updates
import app.halo.vpn.core.UserSite
import app.halo.vpn.core.Engine
import app.halo.vpn.core.Mode
import app.halo.vpn.core.ServerList
import app.halo.vpn.core.Store
import app.halo.vpn.core.Subscription
import app.halo.vpn.core.Subscriptions
import app.halo.vpn.core.ThemeMode
import app.halo.vpn.core.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class AppEntry(val pkg: String, val label: String)

data class Speed(val up: Long = 0, val down: Long = 0)

/** Последние 40 секунд скорости — для графика на главной. */
data class SpeedHistory(val down: List<Long> = emptyList(), val up: List<Long> = emptyList())

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val api get() = Controller(store.secret)
    private val subLock = Mutex()

    val vpn: StateFlow<VpnState> = Engine.state
    val mode = MutableStateFlow(store.mode)
    val theme = MutableStateFlow(store.theme)
    val subs = MutableStateFlow(store.subscriptions)
    val activeSub = MutableStateFlow(store.activeSub?.id)
    val servers = MutableStateFlow(ServerList(emptyList(), null, null))
    val speed = MutableStateFlow(Speed())
    val history = MutableStateFlow(SpeedHistory())
    val batterySaver = MutableStateFlow(store.batterySaver)
    val testing = MutableStateFlow(false)
    val updating = MutableStateFlow(false)
    /** Подписка, которая сейчас загружается после «Ссылка» / QR (адрес), — чтобы на экране было видно, что идёт работа. */
    val adding = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    val favorites = MutableStateFlow(store.favorites)
    val appsMode = MutableStateFlow(store.appsMode)
    val vpnApps = MutableStateFlow(store.vpnApps)
    val bypassApps = MutableStateFlow(store.bypassApps)
    val apps = MutableStateFlow<List<AppEntry>?>(null)
    val adblock = MutableStateFlow(store.adblock)
    val wifiAuto = MutableStateFlow(store.wifiAuto)
    val lockdown = MutableStateFlow(store.lockdown)

    /** Android не усыпляет приложение (исключение из оптимизации батареи). Проверяем при каждом возвращении на экран. */
    val unrestricted = MutableStateFlow(false)

    // Правила
    val categories = MutableStateFlow(store.categories)
    val sites = MutableStateFlow(store.sites)
    val blockedApps = MutableStateFlow(store.blockedApps)
    val doh = MutableStateFlow(store.doh)
    val autoConnect = MutableStateFlow(store.autoConnect)
    val subUpdateHours = MutableStateFlow(store.subUpdateHours)
    val geoAutoUpdate = MutableStateFlow(store.geoAutoUpdate)
    val geoUpdatedAt = MutableStateFlow(store.geoUpdatedAt)

    /** Подписи протоколов из файла подписки: имя сервера → «VLESS · Reality». */
    val labels = MutableStateFlow<Map<String, String>>(emptyMap())
    val allInOne = MutableStateFlow(store.allInOne)
    val leakProtection = MutableStateFlow(store.leakProtection)
    val failover = MutableStateFlow(store.failover)
    val checkSeconds = MutableStateFlow(store.checkSeconds)

    // Проверка
    val ipDirect = MutableStateFlow<Check<IpInfo>>(Check.Idle)
    val ipVpn = MutableStateFlow<Check<IpInfo>>(Check.Idle)
    val services = MutableStateFlow<Map<String, Check<Int>>>(emptyMap())
    val speedTest = MutableStateFlow<Check<Double>>(Check.Idle)
    val speedLive = MutableStateFlow(0.0)

    // Соединения
    val connections = MutableStateFlow<List<Conn>>(emptyList())
    val logs = MutableStateFlow<List<String>>(emptyList())
    private var connPoll: Job? = null

    /** Подписка есть, а серверов нет — почему (null, пока не ясно). */
    val problem = MutableStateFlow<String?>(null)
    private var emptyTicks = 0

    private var poll: Job? = null

    /** Экран на виду: ядро без туннеля (если VPN выключен) и опрос состояния раз в секунду. */
    fun onVisible() {
        lockdown.value = store.lockdown
        unrestricted.value = getApplication<Application>().getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
        if (poll?.isActive == true) return
        poll = viewModelScope.launch(Dispatchers.IO) {
            Engine.ensureIdle(getApplication())?.let { message.value = it }
            launch { refreshStaleSubs() }
            // Скорость — из потока ядра /traffic, пока VPN включён (одно соединение, без опроса всех соединений).
            launch {
                while (isActive) {
                    if (vpn.value is VpnState.On) runCatching {
                        val c = api.openTraffic()
                        val h = coroutineContext[Job]?.invokeOnCompletion { c.disconnect() }
                        try {
                            api.readTraffic(c) { u, d ->
                                if (vpn.value !is VpnState.On) throw java.io.IOException("VPN выключен")
                                speed.value = Speed(u, d)
                                history.value = history.value.let { x -> SpeedHistory((x.down + d).takeLast(40), (x.up + u).takeLast(40)) }
                            }
                        } finally {
                            h?.dispose()
                        }
                    }
                    if (vpn.value !is VpnState.On && (speed.value != Speed() || history.value.down.isNotEmpty())) {
                        speed.value = Speed()
                        history.value = SpeedHistory()
                    }
                    delay(1500)
                }
            }
            var tick = 0
            while (isActive) {
                // Список серверов меняется редко — раз в 4 с.
                if (tick % 4 == 0) {
                    runCatching { api.servers() }
                        .onSuccess {
                            if (it.servers.size != servers.value.servers.size) HaloLog.i("ядро", "серверов в ядре: ${it.servers.size}" + (it.current?.let { c -> " · сейчас «$c»" } ?: ""))
                            servers.value = it
                        }
                        .onFailure {
                            HaloLog.wOnce("ядро", "не удалось получить список серверов (порт ${Engine.controllerPort})", it)
                            servers.value = ServerList(emptyList(), null, null)
                        }
                    // Серверов нет дольше 6 секунд при подписке — выясняем причину.
                    if (store.activeSub != null && servers.value.servers.isEmpty()) {
                        if (++emptyTicks >= 2) {
                            val p = diagnose()
                            if (p != problem.value) HaloLog.w("серверы", "серверов нет: $p")
                            problem.value = p
                        }
                    } else {
                        emptyTicks = 0
                        problem.value = null
                    }
                    labels.value = currentLabels()
                }
                tick++
                delay(1000)
            }
        }
    }

    /** Подписи протоколов; в «Все VPN в одном» — с подписью подписки перед именем, как у ядра. */
    private fun currentLabels(): Map<String, String> {
        val ctx = getApplication<Application>()
        if (!store.isAll) return store.activeSub?.let { Subscriptions.labels(ctx, it.id) } ?: emptyMap()
        return store.subscriptions.flatMap { s ->
            val p = ConfigBuilder.prefix(s)
            Subscriptions.labels(ctx, s.id).map { (k, v) -> p + k to v }
        }.toMap()
    }

    fun setAllInOne(on: Boolean) {
        store.allInOne = on
        allInOne.value = on
        if (!on && store.activeSubId == Store.ALL_ID) {
            store.activeSubId = store.subscriptions.firstOrNull()?.id
            activeSub.value = store.activeSubId
        }
        HaloLog.i("настройки", "«Все VPN в одном»: ${if (on) "включено" else "выключено"}")
        reapply()
    }

    fun setLeakProtection(on: Boolean) {
        store.leakProtection = on
        leakProtection.value = on
        HaloLog.i("настройки", "защита от утечек: ${if (on) "включена" else "выключена"}")
        reapply()
    }

    fun setFailover(on: Boolean) {
        store.failover = on
        failover.value = on
        HaloLog.i("настройки", "автопереключение: ${if (on) "включено" else "выключено"}")
        reapply()
        // Сторож живёт в службе VPN — перезапускаем туннель, чтобы он подхватил настройку.
        if (vpn.value is VpnState.On) HaloVpnService.start(getApplication(), HaloVpnService.ACTION_RECONNECT)
    }

    /** Сторож читает настройку на каждом шаге — переподключаться не нужно. */
    fun setCheckSeconds(sec: Int) {
        store.checkSeconds = sec
        checkSeconds.value = sec
        HaloLog.i("настройки", "проверка сервера: раз в $sec с")
    }

    fun onHidden() {
        poll?.cancel()
        poll = null
        if (vpn.value !is VpnState.On && vpn.value !is VpnState.Connecting) Engine.stop()
    }

    fun connect() = HaloVpnService.start(getApplication(), HaloVpnService.ACTION_CONNECT)

    fun disconnect() = HaloVpnService.start(getApplication(), HaloVpnService.ACTION_DISCONNECT)

    fun setMode(m: Mode) {
        HaloLog.i("настройки", "режим «${m.title}»")
        store.mode = m
        mode.value = m
        reapply()
    }

    fun setTheme(t: ThemeMode) {
        store.theme = t
        theme.value = t
    }

    fun setAdblock(on: Boolean) {
        store.adblock = on
        adblock.value = on
        reapply()
    }

    fun setBatterySaver(on: Boolean) {
        store.batterySaver = on
        batterySaver.value = on
        reapply()
    }

    fun setWifiAuto(on: Boolean) {
        store.wifiAuto = on
        wifiAuto.value = on
        if (on) WifiWatcher.register(getApplication()) else WifiWatcher.unregister(getApplication())
    }

    fun select(name: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { api.select(name) }.onFailure { message.value = "Не удалось выбрать сервер: ${it.message}" }
        runCatching { servers.value = api.servers() }
    }

    fun toggleFavorite(name: String) {
        val f = store.favorites.toMutableSet()
        if (!f.add(name)) f.remove(name)
        store.favorites = f
        favorites.value = f
    }

    fun testAll() = viewModelScope.launch(Dispatchers.IO) {
        if (testing.value) return@launch
        testing.value = true
        runCatching { AutoPicker.rank(api, api.testAll()) }
        runCatching { servers.value = api.servers() }
        testing.value = false
    }

    // ---------- Подписки ----------

    /** Добавляет подписку: скачиваем сразу, чтобы ошибка в ссылке была видна тут же. */
    fun addSubscription(url: String) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            message.value = "Ссылка подписки должна начинаться с https://"
            return
        }
        if (adding.value == clean) {
            message.value = "Эта подписка уже загружается — подождите несколько секунд"
            return
        }
        adding.value = clean
        viewModelScope.launch(Dispatchers.IO) {
            // Ядро с уже добавленными серверами — чтобы подписку с заблокированного сайта можно было скачать через VPN.
            if (store.subscriptions.isNotEmpty()) Engine.ensureIdle(getApplication())
            subLock.withLock {
                updating.value = true
                val existing = store.subscriptions.firstOrNull { it.url == clean }
                val sub = existing ?: Subscription(
                    id = UUID.randomUUID().toString().take(8),
                    name = runCatching { android.net.Uri.parse(clean).host }.getOrNull() ?: "Подписка",
                    url = clean,
                    addedAt = System.currentTimeMillis(),
                )
                runCatching { Subscriptions.fetch(getApplication(), sub) }
                    .onSuccess { meta ->
                        if (existing == null) store.subscriptions = store.subscriptions + sub.copy(meta = meta) else store.updateMeta(sub.id, meta)
                        store.activeSubId = sub.id
                        subs.value = store.subscriptions
                        activeSub.value = sub.id
                        reapplyNow()
                        message.value = "Подписка «${meta.title ?: sub.name}» добавлена"
                    }
                    .onFailure {
                        HaloLog.e("подписка", "не добавлена", it)
                        message.value = "Не удалось загрузить подписку: ${it.message ?: it.javaClass.simpleName}"
                    }
                updating.value = false
            }
            adding.value = null
        }
    }

    fun useSubscription(id: String) {
        store.activeSubId = id
        activeSub.value = id
        reapply()
    }

    fun removeSubscription(id: String) {
        store.subscriptions = store.subscriptions.filterNot { it.id == id }
        runCatching { Subscriptions.providerFile(getApplication(), id).delete() }
        if (store.activeSubId == id) store.activeSubId = store.subscriptions.firstOrNull()?.id
        subs.value = store.subscriptions
        activeSub.value = store.activeSub?.id
        reapply()
    }

    /** «Обновить»: все подписки — остаток и срок; активная — ещё и список серверов. */
    fun updateSubscriptions() = viewModelScope.launch(Dispatchers.IO) {
        if (store.subscriptions.isEmpty()) return@launch
        subLock.withLock {
            updating.value = true
            var error: String? = null
            for (s in store.subscriptions) {
                runCatching { Subscriptions.fetch(getApplication(), s) }
                    .onSuccess { store.updateMeta(s.id, it) }
                    .onFailure { if (s.id in store.activeSubs.map { a -> a.id }) error = it.message }
            }
            subs.value = store.subscriptions
            reapplyNow()
            message.value = error?.let { "Не удалось обновить подписку: $it" } ?: "Подписки обновлены"
            updating.value = false
        }
    }

    private suspend fun refreshStaleSubs() {
        val stale = store.subscriptions.filter { System.currentTimeMillis() - (it.meta?.updatedAt ?: 0) > 12 * 3600_000L }
        if (stale.isEmpty()) return
        for (s in stale) subLock.withLock {
            runCatching { Subscriptions.fetch(getApplication(), s) }.onSuccess { store.updateMeta(s.id, it) }
            subs.value = store.subscriptions
        }
        if (stale.any { s -> s.id in store.activeSubs.map { it.id } } && Engine.coreRunning) reapplyNow()
    }

    // ---------- Приложения ----------

    fun setAppsMode(m: AppsMode) {
        store.appsMode = m
        appsMode.value = m
        reconnectIfOn()
    }

    /** Переключатель «идёт через VPN» у приложения — смысл зависит от режима. */
    fun toggleApp(pkg: String) {
        if (store.appsMode == AppsMode.Selected) {
            store.vpnApps = store.vpnApps.toMutableSet().apply { if (!add(pkg)) remove(pkg) }
            vpnApps.value = store.vpnApps
        } else {
            store.bypassApps = store.bypassApps.toMutableSet().apply { if (!add(pkg)) remove(pkg) }
            bypassApps.value = store.bypassApps
        }
        reconnectIfOn()
    }

    fun loadApps() {
        if (apps.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val pm = ctx.packageManager
            val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.toSet()
            apps.value = pm.getInstalledApplications(0)
                // Все приложения с иконкой на экране, в том числе предустановленные (YouTube, Chrome, Samsung Internet).
                .filter { it.packageName != ctx.packageName && it.packageName in launchable }
                .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        }
    }

    // Список приложений туннеля задаётся при его создании — пересоздаём туннель.
    private fun reconnectIfOn() {
        if (vpn.value is VpnState.On) HaloVpnService.start(getApplication(), HaloVpnService.ACTION_RECONNECT)
    }

    /** Новый конфиг — без разрыва туннеля (ядро не трогает tun, если его настройки не поменялись). */
    private fun reapply() = viewModelScope.launch(Dispatchers.IO) { reapplyNow() }

    private fun reapplyNow() {
        Engine.reapply(getApplication())?.let { message.value = it }
        runCatching { servers.value = api.servers() }
    }

    // ---------- Правила ----------

    fun toggleCategory(id: String) {
        store.categories = store.categories.toMutableSet().apply { if (!add(id)) remove(id) }
        categories.value = store.categories
        reapply()
    }

    /** Добавляет свой сайт; «https://www.site.com/путь» → «site.com». */
    fun addSite(raw: String, action: SiteAction) {
        val domain = raw.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
            .substringBefore(':').removePrefix("*.").removePrefix("www.")
        if (!Regex("^[a-z0-9а-яё-]+(\\.[a-z0-9а-яё-]+)+$").matches(domain)) {
            message.value = "Не похоже на адрес сайта: «$raw»"
            return
        }
        store.sites = store.sites.filterNot { it.domain == domain } + UserSite(domain, action)
        sites.value = store.sites
        reapply()
    }

    fun removeSite(domain: String) {
        store.sites = store.sites.filterNot { it.domain == domain }
        sites.value = store.sites
        reapply()
    }

    fun setDoh(d: Doh) {
        store.doh = d
        doh.value = d
        reapply()
    }

    // ---------- Приложения: блокировка сети ----------

    fun setAppBlocked(pkg: String, blocked: Boolean) {
        HaloLog.i("приложения", "$pkg: ${if (blocked) "сеть запрещена" else "сеть разрешена"}")
        store.blockedApps = store.blockedApps.toMutableSet().apply { if (blocked) add(pkg) else remove(pkg) }
        blockedApps.value = store.blockedApps
        reapply()
    }

    // ---------- Прочие настройки ----------

    fun setAutoConnect(on: Boolean) {
        store.autoConnect = on
        autoConnect.value = on
    }

    fun setSubUpdateHours(h: Int) {
        store.subUpdateHours = h
        subUpdateHours.value = h
        Updates.schedule(getApplication(), changed = true)
    }

    fun setGeoAutoUpdate(on: Boolean) {
        store.geoAutoUpdate = on
        geoAutoUpdate.value = on
        Updates.schedule(getApplication(), changed = true)
    }

    fun updateGeoNow() = viewModelScope.launch(Dispatchers.IO) {
        message.value = "Обновляю списки блокировок…"
        runCatching { GeoUpdater.update(getApplication()) }
            .onSuccess {
                store.geoUpdatedAt = System.currentTimeMillis()
                geoUpdatedAt.value = store.geoUpdatedAt
                if (Engine.coreRunning) reapplyNow()
                message.value = "Списки блокировок обновлены"
            }
            .onFailure { message.value = "Не удалось обновить списки: ${it.message}" }
    }

    fun renameSubscription(id: String, name: String) {
        store.rename(id, name)
        subs.value = store.subscriptions
    }

    // ---------- Проверка ----------

    fun runChecks() = viewModelScope.launch(Dispatchers.IO) {
        if (!Engine.coreRunning) Engine.ensureIdle(getApplication())
        ipDirect.value = Check.Running
        ipVpn.value = Check.Running
        services.value = SERVICES.associate { it.first to Check.Running }
        launch { ipDirect.value = runCatching { Check.Done(api.ipDirect()) }.getOrElse { Check.Failed } }
        launch { ipVpn.value = runCatching { Check.Done(api.ipVpn()) }.getOrElse { Check.Failed } }
        SERVICES.forEach { (name, url) ->
            launch {
                val r = api.checkSite(url)
                services.value = services.value + (name to (if (r != null) Check.Done(r) else Check.Failed))
            }
        }
    }

    fun runSpeedTest() = viewModelScope.launch(Dispatchers.IO) {
        if (speedTest.value is Check.Running) return@launch
        speedTest.value = Check.Running
        speedLive.value = 0.0
        speedTest.value = runCatching { Check.Done(api.speedTest { speedLive.value = it }) }.getOrElse { Check.Failed }
    }

    // ---------- Соединения ----------

    fun watchConnections(on: Boolean) {
        connPoll?.cancel()
        if (!on) return
        connPoll = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { connections.value = api.connections().sortedByDescending { it.start } }
                runCatching { logs.value = Core.nativeLogs().lines().filter { it.isNotBlank() && !it.contains("serve error: http: Server closed") }.reversed() }
                delay(1500)
            }
        }
    }

    fun closeConnection(id: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { api.closeConnection(id) }
        runCatching { connections.value = api.connections().sortedByDescending { it.start } }
    }

    fun closeAllConnections() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { api.closeAll() }
        runCatching { connections.value = api.connections() }
    }

    /** Почему нет серверов — человеческими словами, по ошибке ядра и его журналу. */
    private fun diagnose(): String {
        Engine.lastError?.let { return "Ядро не приняло настройки: ${mask(it)}" }
        if (!Engine.coreRunning) return "Ядро не запущено."
        val file = store.activeSub?.let { Subscriptions.providerFile(getApplication(), it.id) }
        if (file == null || !file.exists() || file.length() == 0L) return "Файл подписки пустой — сервис ничего не прислал. Нажмите «Обновить»."
        val inside = Subscriptions.describe(file.readText())
        if (inside.servers == 0) {
            return "В подписке нет серверов, которые понимает ядро (формат: ${inside.format}" +
                (if (inside.other > 0) ", других строк: ${inside.other}" else "") + "). Возможно, это подписка для другой программы — например, только Telegram-прокси."
        }
        val bad = Core.nativeLogs().lines().filter { (it.contains(" E ") || it.contains(" W ")) && (it.contains("provider", true) || it.contains("proxy", true) || it.contains("parse", true)) }
        bad.lastOrNull()?.let { return "Ядро не разобрало подписку: ${mask(it.substringAfter(' ').substringAfter(' '))}" }
        return "Ядро не нашло серверов в подписке. Нажмите «Журнал» — там видно, что пошло не так."
    }

    /** Убираем из текста ссылки — в них личные ключи подписок. */
    private fun mask(s: String) = s.replace(Regex("https?://\\S+"), "<ссылка>")

    val verboseLog = MutableStateFlow(false)

    fun setVerboseLog(on: Boolean) {
        verboseLog.value = on
        if (Core.available) Core.nativeSetVerbose(on)
        HaloLog.i("журнал", if (on) "подробный журнал включён" else "подробный журнал выключен")
    }

    fun clearLogs() {
        HaloLog.clear()
        HaloLog.i("журнал", "журнал очищен")
    }

    /** Журнал с шапкой (устройство, версии, состояние) — в буфер обмена. */
    fun copyLogs() = viewModelScope.launch(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val text = report() + "\n--- журнал ---\n" + HaloLog.entries.value.takeLast(400).joinToString("\n") { it.line() }
        withContext(Dispatchers.Main) {
            ctx.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Halo VPN — журнал", text))
            message.value = "Журнал скопирован — вставьте его в сообщение"
        }
    }

    /** Журнал файлом — в Telegram, почту и т. д. */
    fun shareLogs() = viewModelScope.launch(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val file = java.io.File(ctx.cacheDir, "logs/halo-vpn-log.txt").apply { parentFile?.mkdirs() }
        file.writeText(report() + "\n--- журнал ---\n" + HaloLog.fileText() + HaloLog.entries.value.filter { it.level == HaloLog.Level.Debug }.joinToString("\n") { it.line() })
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Halo VPN — журнал").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Отправить журнал").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Отчёт для разработчика в буфер обмена: версии, порты, ошибка, журнал — без адресов подписок. */
    fun copyReport() = copyLogs()

    private fun report(): String {
        val ctx = getApplication<Application>()
        return buildString {
            appendLine("Halo VPN ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Устройство: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Ядро: ${if (Core.available) "есть" else "нет"}, запущено: ${Engine.coreRunning}, порты ${Engine.controllerPort}/${Engine.mixedPort}")
            appendLine("VPN: ${vpn.value}, режим: ${store.mode.title}, подписок: ${store.subscriptions.size}, серверов: ${servers.value.servers.size}")
            store.activeSub?.let { s ->
                val f = Subscriptions.providerFile(ctx, s.id)
                val head = runCatching { f.readText().take(120).replace(Regex("[A-Za-z0-9+/=_-]{24,}"), "…") }.getOrDefault("")
                val d = runCatching { Subscriptions.describe(f.readText()) }.getOrNull()
                appendLine("Файл подписки: ${f.length()} байт, формат: ${d?.format}, серверов: ${d?.servers}, протоколы: ${d?.byType}, прочих строк: ${d?.other}")
                appendLine("Провайдер в ядре: " + runCatching {
                    val o = org.json.JSONObject(api.providerRaw())
                    "серверов ${o.optJSONArray("proxies")?.length() ?: 0}, обновлён ${o.optString("updatedAt")}"
                }.getOrElse { "нет ответа (${it.message})" })
                appendLine("Начало файла: ${mask(head).replace('\n', ' ')}")
            }
            Engine.lastError?.let { appendLine("Ошибка ядра: ${mask(it)}") }
            problem.value?.let { appendLine("Причина: $it") }
        }
    }

    companion object {
        /** Сервисы для проверки «открывается ли» — как в версии для Windows. */
        val SERVICES = listOf(
            "YouTube" to "https://www.youtube.com/",
            "Instagram" to "https://www.instagram.com/",
            "ChatGPT" to "https://chatgpt.com/",
            "X (Twitter)" to "https://x.com/",
            "Discord" to "https://discord.com/",
            "Telegram" to "https://web.telegram.org/",
            "Spotify" to "https://open.spotify.com/",
            "Яндекс" to "https://ya.ru/",
            "Госуслуги" to "https://www.gosuslugi.ru/",
        )
    }
}

/** Состояние одной проверки. */
sealed interface Check<out T> {
    data object Idle : Check<Nothing>
    data object Running : Check<Nothing>
    data object Failed : Check<Nothing>
    data class Done<T>(val value: T) : Check<T>
}
