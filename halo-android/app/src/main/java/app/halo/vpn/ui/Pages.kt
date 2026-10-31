package app.halo.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.halo.vpn.core.ConfigBuilder
import app.halo.vpn.core.Countries
import app.halo.vpn.core.SiteAction
import app.halo.vpn.core.VpnState

private val PagePad = Modifier.padding(start = 19.dp, end = 19.dp, top = 7.dp, bottom = 14.dp)

/** Заголовок вложенной страницы со стрелкой «назад». */
@Composable
fun PageTitle(title: String, subtitle: String? = null, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val c = LocalHalo.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack, "Назад",
            Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onBack).padding(6.dp), tint = c.text,
        )
        Column(Modifier.weight(1f)) {
            T(title, size = Sz.title, weight = Bold)
            if (subtitle != null) T(subtitle, color = c.muted)
        }
        trailing()
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(Sz.radius)).background(LocalHalo.current.card)) { content() }
}

// ---------- Правила ----------

@Composable
fun RulesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val c = LocalHalo.current
    val mode by vm.mode.collectAsState()
    val on by vm.categories.collectAsState()
    val sites by vm.sites.collectAsState()
    val doh by vm.doh.collectAsState()
    var domain by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf(SiteAction.Vpn) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(PagePad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTitle("Правила", "Что идёт через VPN", onBack)

        SectionLabel("Что обходить")
        if (mode != app.halo.vpn.core.Mode.Blocked) {
            T("Категории работают в режиме «Обход блокировок». Сейчас — «${mode.title}».", color = c.warn, lines = 3)
        }
        Card {
            ConfigBuilder.CATEGORIES.forEachIndexed { i, cat ->
                GroupRow(last = i == ConfigBuilder.CATEGORIES.lastIndex, padV = 9.dp, onClick = { vm.toggleCategory(cat.id) }) {
                    T(cat.title, Modifier.weight(1f), size = Sz.body, weight = Semi)
                    HaloSwitch(cat.id in on)
                }
            }
        }

        SectionLabel("Свои сайты")
        T("Важнее режима и категорий. Поддомены — тоже.", color = c.muted)
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    domain, { domain = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { T("site.com", color = c.muted) }, shape = RoundedCornerShape(16.dp),
                    textStyle = TextStyle(fontFamily = Roboto, fontSize = Sz.label, color = c.text),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, cursorColor = c.accent),
                )
                Segmented(SiteAction.entries, action, { it.title }) { action = it }
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(if (domain.isBlank()) c.divider else c.accent)
                        .clickable(enabled = domain.isNotBlank()) {
                            vm.addSite(domain, action)
                            domain = ""
                        }.padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { T("Добавить", weight = Bold, color = if (domain.isBlank()) c.muted else c.onAccent) }
            }
            sites.forEachIndexed { i, s ->
                GroupRow(last = i == sites.lastIndex, padV = 8.dp) {
                    Column(Modifier.weight(1f)) {
                        T(s.domain, size = Sz.body, weight = Semi)
                        T(
                            s.action.title, weight = Medium,
                            color = when (s.action) {
                                SiteAction.Vpn -> c.accent
                                SiteAction.Direct -> c.muted
                                SiteAction.Block -> c.warn
                            },
                        )
                    }
                    Icon(Icons.Outlined.Delete, "Удалить", Modifier.size(36.dp).clip(CircleShape).clickable { vm.removeSite(s.domain) }.padding(7.dp), tint = c.muted)
                }
            }
        }

        SectionLabel("DNS для заблокированных сайтов")
        T("Запросы идут зашифрованно через VPN — провайдер не подменит адрес.", color = c.muted, lines = 2)
        Segmented(app.halo.vpn.core.Doh.entries, doh, { it.title }) { vm.setDoh(it) }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------- Проверка ----------

@Composable
fun ChecksScreen(vm: MainViewModel, onBack: () -> Unit) {
    val c = LocalHalo.current
    val vpn by vm.vpn.collectAsState()
    val ipDirect by vm.ipDirect.collectAsState()
    val ipVpn by vm.ipVpn.collectAsState()
    val services by vm.services.collectAsState()
    val speed by vm.speedTest.collectAsState()
    val live by vm.speedLive.collectAsState()
    val running = ipDirect is Check.Running || ipVpn is Check.Running || services.values.any { it is Check.Running }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(PagePad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTitle("Проверка", if (vpn is VpnState.On) "VPN включён" else "VPN выключен — проверка по правилам ядра", onBack)

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.accent).clickable(enabled = !running) { vm.runChecks() }.padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) { T(if (running) "Проверяю…" else "Проверить", weight = Bold, color = c.onAccent) }

        SectionLabel("Мой IP")
        Card {
            IpRow("Напрямую", ipDirect, false)
            IpRow("Через VPN", ipVpn, true)
        }

        SectionLabel("Открываются ли сайты")
        Card {
            MainViewModel.SERVICES.forEachIndexed { i, (name, _) ->
                GroupRow(last = i == MainViewModel.SERVICES.lastIndex, padV = 10.dp) {
                    T(name, Modifier.weight(1f), size = Sz.body)
                    when (val r = services[name]) {
                        null, Check.Idle -> T("—", color = c.muted)
                        Check.Running -> T("…", color = c.muted)
                        Check.Failed -> T("✕ не открылся", weight = Bold, color = c.warn)
                        is Check.Done -> T("✓ ${r.value} мс", weight = Medium, mono = true, color = c.accent)
                    }
                }
            }
        }

        SectionLabel("Скорость")
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val value = when (val s = speed) {
                    is Check.Done -> s.value
                    Check.Running -> live
                    else -> null
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    T(value?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—", size = 40.sp, weight = Bold, mono = true)
                    T(" Мбит/с", Modifier.padding(bottom = 7.dp), color = c.muted)
                }
                T(
                    when (speed) {
                        Check.Failed -> "Не удалось — сервер скорости не ответил"
                        Check.Running -> "Скачиваю тестовый файл… до 10 с"
                        is Check.Done -> "Загрузка через текущий маршрут (Cloudflare)"
                        else -> "Загрузка через текущий маршрут — до 10 с и 50 МБ"
                    },
                    color = if (speed is Check.Failed) c.warn else c.muted, lines = 2,
                )
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.container)
                        .clickable(enabled = speed !is Check.Running) { vm.runSpeedTest() }.padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { T(if (speed is Check.Running) "Измеряю…" else "Измерить скорость", weight = Bold) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun IpRow(label: String, check: Check<app.halo.vpn.core.IpInfo>, last: Boolean) {
    val c = LocalHalo.current
    GroupRow(last = last, padV = 11.dp) {
        when (check) {
            is Check.Done -> Flag(check.value.country)
            else -> Flag(null)
        }
        Column(Modifier.weight(1f)) {
            T(label, color = c.muted)
            T(
                when (check) {
                    is Check.Done -> check.value.ip + (if (check.value.city.isNotEmpty()) " · ${check.value.city}" else "")
                    Check.Running -> "…"
                    Check.Failed -> "не удалось узнать"
                    Check.Idle -> "—"
                },
                size = Sz.body, weight = Semi, mono = check is Check.Done,
            )
        }
    }
}

// ---------- Соединения ----------

@Composable
fun ConnectionsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val c = LocalHalo.current
    val conns by vm.connections.collectAsState()
    // Опрашиваем ядро, только пока страница открыта.
    DisposableEffect(Unit) {
        vm.watchConnections(true)
        onDispose { vm.watchConnections(false) }
    }

    Column(Modifier.fillMaxSize().then(PagePad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTitle("Соединения", "Сейчас открыто: ${conns.size}", onBack) {
            if (conns.isNotEmpty()) {
                Box(
                    Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, c.outline, RoundedCornerShape(9.dp))
                        .clickable { vm.closeAllConnections() }.padding(horizontal = 12.dp, vertical = 5.dp),
                ) { T("Закрыть все") }
            }
        }
        run {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (conns.isEmpty()) item { T("Соединений нет. Включите VPN и откройте что-нибудь в браузере.", color = c.muted, lines = 3) }
                items(conns, key = { it.id }) { x ->
                    val direct = x.via == "DIRECT"
                    val reject = x.via == "REJECT" || x.via == "REJECT-DROP"
                    Row(
                        Modifier.animateItem().fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(c.card).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            T("${x.host}:${x.port}", size = Sz.body, weight = Semi)
                            T(
                                when {
                                    reject -> "Заблокировано"
                                    direct -> "Напрямую"
                                    else -> "VPN · ${Countries.clean(x.via)}"
                                },
                                weight = Medium, color = if (reject) c.warn else if (direct) c.muted else c.accent,
                            )
                            T(
                                listOfNotNull(x.app, x.rule.takeIf { it.isNotBlank() }, x.network.uppercase(), "↓ ${gb(x.down)} ↑ ${gb(x.up)}").joinToString(" · "),
                                size = 12.sp, color = c.muted, lines = 2,
                            )
                        }
                        Icon(
                            Icons.Outlined.Close, "Закрыть",
                            Modifier.size(40.dp).clip(CircleShape).clickable { vm.closeConnection(x.id) }.padding(10.dp), tint = c.muted,
                        )
                    }
                }
            }
        }
    }
}

// ---------- Журнал ----------

private enum class LogFilter(val title: String) { All("Все"), Problems("Проблемы"), Core("Ядро"), App("Приложение") }

@Composable
fun LogsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val c = LocalHalo.current
    val entries by app.halo.vpn.core.HaloLog.entries.collectAsState()
    val verbose by vm.verboseLog.collectAsState()
    var filter by rememberSaveable { mutableStateOf(LogFilter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = entries.asReversed().filter { e ->
        when (filter) {
            LogFilter.All -> true
            LogFilter.Problems -> e.level >= app.halo.vpn.core.HaloLog.Level.Warn
            LogFilter.Core -> e.source == app.halo.vpn.core.HaloLog.Source.Core
            LogFilter.App -> e.source == app.halo.vpn.core.HaloLog.Source.App
        } && (query.isBlank() || e.text.contains(query, true) || e.tag.contains(query, true))
    }

    Column(Modifier.fillMaxSize().then(PagePad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTitle("Журнал", "Записей: ${entries.size} · ссылки и ключи скрыты", onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogButton(Modifier.weight(1f), "Скопировать", primary = true) { vm.copyLogs() }
            LogButton(Modifier.weight(1f), "Поделиться", primary = false) { vm.shareLogs() }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LogFilter.entries.forEach { f -> Chip(f.title, f == filter) { filter = f } }
        }
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { T("Поиск по журналу", color = c.muted) }, shape = RoundedCornerShape(16.dp),
            textStyle = TextStyle(fontFamily = Roboto, fontSize = Sz.label, color = c.text),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, cursorColor = c.accent),
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(Sz.radius)).background(c.card),
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (shown.isEmpty()) item { T("Записей нет.", color = c.muted) }
            items(shown) { e ->
                val color = when (e.level) {
                    app.halo.vpn.core.HaloLog.Level.Error -> c.warn
                    app.halo.vpn.core.HaloLog.Level.Warn -> c.warn.copy(alpha = 0.85f)
                    app.halo.vpn.core.HaloLog.Level.Debug -> c.muted
                    else -> c.text
                }
                Column {
                    T(
                        (if (e.time > 0) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(e.time)) + " · " else "") +
                            (if (e.source == app.halo.vpn.core.HaloLog.Source.Core) "mihomo" else e.tag),
                        size = 11.sp, mono = true, color = if (e.source == app.halo.vpn.core.HaloLog.Source.Core) c.accent else c.muted,
                    )
                    T(e.text, size = 12.sp, mono = true, color = color, lines = 8)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { vm.setVerboseLog(!verbose) }) {
                T("Подробно", weight = Semi)
                T("Каждое соединение ядра — для разбора, какой сайт куда идёт", size = 12.sp, color = c.muted, lines = 2)
            }
            HaloSwitch(verbose) { vm.setVerboseLog(!verbose) }
            Box(
                Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, c.outline, RoundedCornerShape(9.dp))
                    .clickable { vm.clearLogs() }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) { T("Очистить") }
        }
    }
}

@Composable
private fun LogButton(modifier: Modifier, text: String, primary: Boolean, onClick: () -> Unit) {
    val c = LocalHalo.current
    Box(
        modifier.clip(RoundedCornerShape(50)).background(if (primary) c.accent else c.container).clickable(onClick = onClick).padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, weight = Bold, color = if (primary) c.onAccent else c.text) }
}
