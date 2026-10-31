package app.halo.vpn.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Snackbar
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.halo.vpn.BuildConfigInfo
import app.halo.vpn.core.AppsMode
import app.halo.vpn.core.ConfigBuilder
import app.halo.vpn.core.Core
import app.halo.vpn.core.Countries
import app.halo.vpn.core.Mode
import app.halo.vpn.core.Server
import app.halo.vpn.core.Subscription
import app.halo.vpn.core.ThemeMode
import app.halo.vpn.core.VpnState
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max

enum class Tab(val title: String, val icon: ImageVector) {
    Home("Главная", Icons.Outlined.Home),
    Servers("Серверы", Icons.Outlined.Public),
    Access("Доступ", Icons.Outlined.Cloud),
    More("Ещё", Icons.Outlined.Settings),
}

private val ScreenPad = Modifier.padding(start = 19.dp, end = 19.dp, top = 7.dp, bottom = 14.dp)

@Composable
fun HaloRoot(vm: MainViewModel, onConnect: () -> Unit, onScanQr: () -> Unit) {
    val c = LocalHalo.current
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    // Вложенная страница вкладки «Ещё»: apps, rules, checks, conns.
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val open: (String) -> Unit = {
        tab = Tab.More
        page = it
    }
    val snack = remember { SnackbarHostState() }
    val msg by vm.message.collectAsState()
    val vpn by vm.vpn.collectAsState()
    LaunchedEffect(msg) {
        msg?.let {
            snack.showSnackbar(it)
            vm.message.value = null
        }
    }
    LaunchedEffect(vpn) { (vpn as? VpnState.Failed)?.let { vm.message.value = it.message } }
    BackHandler(enabled = page != null || tab != Tab.Home) { if (page != null) page = null else tab = Tab.Home }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            // На раскрытом Z Fold и планшете — колонкой посередине, как на телефоне.
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
                // Смена вкладки — короткое появление с лёгким сдвигом, как в One UI.
                AnimatedContent(
                    targetState = if (tab == Tab.More && page != null) page!! else tab,
                    modifier = Modifier.widthIn(max = 520.dp).fillMaxSize(),
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(260)) { it / 24 }) togetherWith fadeOut(tween(120))
                    },
                    label = "tab",
                ) { target: Any ->
                    val back = { page = null }
                    when (target) {
                        "apps" -> AppsScreen(vm, back)
                        "rules" -> RulesScreen(vm, back)
                        "checks" -> ChecksScreen(vm, back)
                        "conns" -> ConnectionsScreen(vm, back)
                        "logs" -> LogsScreen(vm, back)
                        Tab.Home -> HomeScreen(vm, onConnect, go = { tab = it }, open = open)
                        Tab.Servers -> ServersScreen(vm, open)
                        Tab.Access -> AccessScreen(vm, onScanQr)
                        else -> SettingsScreen(vm, open)
                    }
                }
            }
            BottomNav(tab) {
                tab = it
                page = null
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 90.dp)) {
            Snackbar(
                it, Modifier.padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp),
                containerColor = if (c.dark) c.container else c.text, contentColor = if (c.dark) c.text else c.bg,
            )
        }
    }
}

@Composable
private fun BottomNav(tab: Tab, onSelect: (Tab) -> Unit) {
    val c = LocalHalo.current
    Row(Modifier.fillMaxWidth().background(c.nav).navigationBarsPadding().padding(top = 10.dp, bottom = 8.dp, start = 5.dp, end = 5.dp)) {
        Tab.entries.forEach { t ->
            val on = t == tab
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    Modifier.size(58.dp, 29.dp).clip(RoundedCornerShape(50)).background(if (on) c.container else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { Icon(t.icon, t.title, Modifier.size(22.dp), tint = if (on) c.text else c.muted) }
                T(t.title, weight = if (on) Bold else androidx.compose.ui.text.font.FontWeight.Normal, color = if (on) c.text else c.muted)
            }
        }
    }
}

// ---------- Главная (mob-1, первый телефон) ----------

@Composable
fun HomeScreen(vm: MainViewModel, onConnect: () -> Unit, go: (Tab) -> Unit, open: (String) -> Unit) {
    val c = LocalHalo.current
    val vpn by vm.vpn.collectAsState()
    val mode by vm.mode.collectAsState()
    val list by vm.servers.collectAsState()
    val speed by vm.speed.collectAsState()
    val history by vm.history.collectAsState()
    val labels by vm.labels.collectAsState()
    val subs by vm.subs.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vpn) {
        while (vpn is VpnState.On) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val on = vpn is VpnState.On
    val busy = vpn is VpnState.Connecting
    val current = list.current
    val currentServer = list.servers.firstOrNull { it.name == current }

    Column(Modifier.fillMaxSize().then(ScreenPad), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Halo", Modifier.weight(1f), size = Sz.title, weight = Bold)
            Icon(
                Icons.Outlined.Speed, "Проверка", Modifier.padding(end = 8.dp).size(36.dp).clip(CircleShape)
                    .clickable { open("checks") }.padding(6.dp),
                tint = c.text,
            )
            ModeChip(mode) { vm.setMode(it) }
        }

        // Большая кнопка на подсвеченной карточке.
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).drawBehind {
                val center = Offset(size.width * 0.5f, size.height * 0.42f)
                val far = max(hypot(center.x, center.y), hypot(size.width - center.x, size.height - center.y))
                drawRect(Brush.radialGradient(0f to c.hero, 0.7f to c.bg, center = center, radius = far))
            },
            contentAlignment = Alignment.Center,
        ) {
            val btn = minOf(190.dp, maxHeight - 64.dp).coerceAtLeast(96.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ConnectButton(btn, on, busy) { if (on || busy) vm.disconnect() else onConnect() }
                T(
                    when (val s = vpn) {
                        is VpnState.On -> "Подключено · ${duration(now - s.since)}"
                        VpnState.Connecting -> "Подключение…"
                        else -> "Не подключено"
                    },
                    weight = Bold, color = if (on || busy) c.accent else c.muted,
                )
            }
        }

        // Текущий сервер.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Sz.radius)).background(c.card)
                .clickable { go(if (subs.isEmpty()) Tab.Access else Tab.Servers) }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (subs.isEmpty()) {
                Flag(null)
                Column(Modifier.weight(1f)) {
                    T("Нет подписки", size = Sz.body, weight = Bold)
                    T("Добавьте во вкладке «Доступ»", color = c.muted)
                }
                T("›", size = Sz.body, color = c.accent)
            } else {
                Flag(current?.let { Countries.code(it) })
                Column(Modifier.weight(1f)) {
                    T(current?.let { Countries.clean(it) } ?: "Загрузка серверов…", size = Sz.body, weight = Bold)
                    T(
                        when {
                            list.selected == ConfigBuilder.AUTO -> "Авто · " + (currentServer?.let { labels[it.name] ?: Countries.protocol(it.type) } ?: "лучший")
                            currentServer != null -> labels[currentServer.name] ?: Countries.protocol(currentServer.type)
                            else -> ""
                        },
                        color = c.muted,
                    )
                }
                T((currentServer?.delay?.let { "$it мс" } ?: "— мс") + " ›", mono = true, color = pingColor(currentServer?.delay, c))
            }
        }

        // Скорость.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SpeedCard(Modifier.weight(1f), "↓ Загрузка", speed(speed.down), c.accent, history.down)
            SpeedCard(Modifier.weight(1f), "↑ Отдача", speed(speed.up), c.warn, history.up)
        }

        // Быстрый выбор: три сервера с лучшим пингом.
        val quick = list.servers.filter { it.delay != null }.sortedBy { it.delay }.take(3)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Sz.radius)).border(1.dp, c.outline, RoundedCornerShape(Sz.radius))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            T("Быстрый выбор", weight = Bold, color = c.muted)
            if (quick.isEmpty()) {
                T(if (subs.isEmpty()) "Появится после добавления подписки" else "Нажмите «Пинг» на вкладке «Серверы»", color = c.muted)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    quick.forEach { s ->
                        val sel = s.name == current
                        Row(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (sel) c.container else c.card)
                                .border(1.dp, c.divider, RoundedCornerShape(14.dp)).clickable { vm.select(s.name) }.padding(vertical = 9.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Flag(Countries.code(s.name), 31.dp)
                            Spacer(Modifier.size(7.dp))
                            T("${s.delay}", mono = true, color = pingColor(s.delay, c))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(mode: Mode, onSelect: (Mode) -> Unit) {
    val c = LocalHalo.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, c.outline, RoundedCornerShape(9.dp)).clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 5.dp),
        ) { T("${mode.chip} ▾", weight = Medium) }
        DropdownMenu(open, { open = false }, containerColor = c.card) {
            Mode.entries.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            T(m.title, size = Sz.body, weight = if (m == mode) Bold else Semi, color = if (m == mode) c.accent else c.text)
                            T(m.hint, color = c.muted)
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(m)
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectButton(size: androidx.compose.ui.unit.Dp, on: Boolean, busy: Boolean, onClick: () -> Unit) {
    val c = LocalHalo.current
    val haptic = LocalHapticFeedback.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val active = on || busy
    val t = rememberInfiniteTransition(label = "halo")
    // Подключение — кольцо бежит быстро; подключено — медленно «дышит».
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (busy) 900 else 2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val bg by animateColorAsState(if (on) c.accent else if (busy) c.accent.copy(alpha = 0.7f) else c.card, tween(400), label = "bg")
    val fg by animateColorAsState(if (active) c.onAccent else c.accent, tween(400), label = "fg")
    val ringAlpha by animateFloatAsState(if (active) 1f else 0f, tween(500), label = "ring")
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "scale")
    Box(
        Modifier.size(size + 32.dp).drawBehind {
            val r = size.toPx() / 2
            if (ringAlpha > 0f) {
                // Кольцо вокруг кнопки (в макете — тень 14 px с прозрачностью 14 %).
                val ring = 12.dp.toPx() * (if (busy) 0.3f + 0.7f * pulse else 0.75f + 0.25f * pulse)
                drawCircle(c.accent.copy(alpha = 0.14f * ringAlpha), radius = r + ring)
                drawCircle(
                    Brush.radialGradient(listOf(c.accent.copy(alpha = 0.30f * ringAlpha), Color.Transparent), center, r + 16.dp.toPx()),
                    r + 16.dp.toPx(),
                )
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
                .background(bg)
                .border(2.dp, c.outline.copy(alpha = 1f - ringAlpha), CircleShape)
                .clickable(interactionSource = press, indication = null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Crossfade(active, label = "icon") { a ->
                Icon(if (a) Icons.Filled.Lock else Icons.Filled.LockOpen, null, Modifier.size(size * 0.21f), tint = fg)
            }
            Spacer(Modifier.height(4.dp))
            T(if (on) "Вкл" else if (busy) "…" else "Выкл", size = Sz.body, weight = Bold, color = if (active) c.onAccent else c.text)
        }
    }
}

@Composable
private fun SpeedCard(modifier: Modifier, label: String, value: String, color: Color, points: List<Long>) {
    val c = LocalHalo.current
    Column(
        modifier.clip(RoundedCornerShape(Sz.radius)).background(c.card)
            .drawBehind {
                // График скорости за последние 40 секунд — по нижнему краю карточки.
                if (points.size < 2) return@drawBehind
                val max = (points.maxOrNull() ?: 0L).coerceAtLeast(16 * 1024L).toFloat()
                val h = size.height * 0.55f
                val step = size.width / 39f
                val x0 = size.width - step * (points.size - 1)
                val line = Path()
                points.forEachIndexed { i, v ->
                    val x = x0 + step * i
                    val y = size.height - 4.dp.toPx() - h * (v / max)
                    if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(size.width, size.height)
                    lineTo(x0, size.height)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent), startY = size.height - h, endY = size.height))
                drawPath(line, color.copy(alpha = 0.8f), style = Stroke(1.5.dp.toPx()))
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        T(label, color = color)
        T(value, size = Sz.big, weight = Bold, mono = true)
    }
}

// ---------- Серверы (mob-1, второй телефон) ----------

private enum class Filter(val title: String) { All("Все"), Favorites("Избранные"), Europe("Европа"), Asia("Азия") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServersScreen(vm: MainViewModel, open: (String) -> Unit) {
    val c = LocalHalo.current
    val list by vm.servers.collectAsState()
    val testing by vm.testing.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val labels by vm.labels.collectAsState()
    val subs by vm.subs.collectAsState()
    val problem by vm.problem.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var search by rememberSaveable { mutableStateOf<String?>(null) }

    val shown = list.servers.filter { s ->
        val code = Countries.code(s.name)
        when (filter) {
            Filter.All -> true
            Filter.Favorites -> s.name in favorites
            Filter.Europe -> Countries.isEurope(code)
            Filter.Asia -> Countries.isAsia(code)
        } && (search.isNullOrBlank() || s.name.contains(search!!, true))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().then(ScreenPad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                T("Серверы", Modifier.weight(1f), size = Sz.title, weight = Bold)
                Icon(
                    if (search == null) Icons.Outlined.Search else Icons.Outlined.Close, "Поиск",
                    Modifier.size(34.dp).clip(CircleShape).clickable { search = if (search == null) "" else null }.padding(4.dp), tint = c.text,
                )
            }
            if (search != null) Field(search!!, { search = it }, "Название или страна")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Filter.entries.forEach { f -> Chip(f.title, f == filter) { filter = f } }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 76.dp)) {
                if (list.servers.isEmpty()) {
                    item {
                        if (subs.isEmpty()) {
                            T("Серверов пока нет — добавьте подписку во вкладке «Доступ».", color = c.muted, lines = 3)
                        } else {
                            ProblemCard(problem, onLogs = { open("logs") }, onCopy = { vm.copyLogs() }, onRetry = { vm.updateSubscriptions() })
                        }
                    }
                } else if (filter == Filter.All && search.isNullOrBlank()) {
                    item(key = "auto") {
                        ServerRow(
                            Modifier.animateItem(), null, ConfigBuilder.AUTO, list.autoNow?.let { "сейчас: ${Countries.clean(it)}" } ?: "по наименьшему пингу",
                            list.servers.firstOrNull { it.name == list.autoNow }?.delay, list.selected == ConfigBuilder.AUTO, false,
                            onClick = { vm.select(ConfigBuilder.AUTO) }, onLong = {},
                        )
                    }
                }
                if (filter == Filter.Favorites && shown.isEmpty()) {
                    item { T("Удерживайте сервер, чтобы добавить его в избранные.", color = c.muted, lines = 3) }
                }
                items(shown, key = { it.name }) { s ->
                    ServerRow(
                        Modifier.animateItem(),
                        s, Countries.clean(s.name), labels[s.name] ?: Countries.protocol(s.type), s.delay, list.selected == s.name, s.name in favorites,
                        onClick = { vm.select(s.name) }, onLong = { vm.toggleFavorite(s.name) },
                    )
                }
            }
        }
        // «⚡ Пинг» — плавающая кнопка справа внизу.
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 19.dp, bottom = 22.dp)
                .shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.container)
                .clickable(enabled = !testing) { vm.testAll() }.padding(horizontal = 19.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(Icons.Filled.Bolt, null, Modifier.size(24.dp), tint = c.text)
            T(if (testing) "Проверяю…" else "Пинг", weight = Bold)
        }
    }
}

/** Подписка есть, а серверов нет: показываем причину и как её передать. */
@Composable
private fun ProblemCard(problem: String?, onLogs: () -> Unit, onCopy: () -> Unit, onRetry: () -> Unit) {
    val c = LocalHalo.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Sz.radius)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        T(if (problem == null) "Загружаю серверы…" else "Серверы не загрузились", size = Sz.body, weight = Bold)
        if (problem != null) {
            T(problem, color = c.warn, lines = 6)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Обновить", false, onRetry)
                Chip("Журнал", false, onLogs)
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.accent).clickable(onClick = onCopy).padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) { T("Скопировать журнал", weight = Bold, color = c.onAccent) }
            T("Отчёт без адресов подписок — его можно переслать разработчику.", color = c.muted, lines = 2)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(modifier: Modifier, s: Server?, name: String, sub: String, delay: Int?, selected: Boolean, favorite: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val c = LocalHalo.current
    val bg by animateColorAsState(if (selected) c.container else c.card, tween(300), label = "row")
    Row(
        modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(17.dp)).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s == null) {
            Box(Modifier.size(38.dp, 25.dp).clip(RoundedCornerShape(5.dp)).background(c.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Bolt, null, Modifier.size(18.dp), tint = c.accent)
            }
        } else {
            Flag(Countries.code(s.name))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(name, Modifier.weight(1f, fill = false), size = Sz.body, weight = Bold)
                if (favorite) Icon(Icons.Filled.Star, "Избранный", Modifier.padding(start = 6.dp).size(16.dp), tint = c.warn)
            }
            T(sub, color = c.muted)
        }
        AnimatedContent(delay, transitionSpec = { (fadeIn(tween(200)) + slideInVertically { -it / 2 }) togetherWith fadeOut(tween(150)) }, label = "ping") { d ->
            T(d?.toString() ?: "—", mono = true, color = pingColor(d, c))
        }
    }
}

// ---------- Доступ (mob-2, первый телефон) ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccessScreen(vm: MainViewModel, onScanQr: () -> Unit) {
    val c = LocalHalo.current
    val ctx = LocalContext.current
    val subs by vm.subs.collectAsState()
    val activeId by vm.activeSub.collectAsState()
    val updating by vm.updating.collectAsState()
    val adding by vm.adding.collectAsState()
    var linkDialog by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Subscription?>(null) }
    var toDelete by remember { mutableStateOf<Subscription?>(null) }
    var toRename by remember { mutableStateOf<Subscription?>(null) }
    val allOn by vm.allInOne.collectAsState()
    val serverCount by vm.servers.collectAsState()
    val isAll = allOn && activeId == app.halo.vpn.core.Store.ALL_ID && subs.size > 1
    val active = subs.firstOrNull { it.id == activeId } ?: subs.firstOrNull()

    Column(Modifier.fillMaxSize().then(ScreenPad), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).animateContentSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            T("Мой доступ", size = Sz.title, weight = Bold)
            adding?.let { url ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Sz.radius)).background(c.card).padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                    Column(Modifier.weight(1f)) {
                        T("Загружаю подписку…", size = Sz.body, weight = Semi)
                        T((runCatching { Uri.parse(url).host }.getOrNull() ?: "") + " · до 30 с, если сайт подписки заблокирован", color = c.muted, lines = 2)
                    }
                }
            }
            if (isAll) {
                AllCard(subs, serverCount.servers.size)
                Pill(Modifier.fillMaxWidth(), if (updating) "Обновляю…" else "Обновить все", c.container, c.text) { vm.updateSubscriptions() }
                subs.forEach { s -> SubRow(s, false, onClick = { vm.useSubscription(s.id) }, onLong = { menuFor = s }) }
            } else if (active == null && adding == null) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    T("Подписок пока нет", size = Sz.big, weight = Bold)
                    T("Добавьте ссылку от вашего VPN-сервиса или отсканируйте QR-код — кнопки внизу.", color = c.muted, lines = 3)
                }
            } else if (active != null) {
                ActiveCard(active) { menuFor = active }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Pill(Modifier.weight(1f), "Продлить", c.accent, c.onAccent) {
                        val url = active.meta?.renewUrl
                        if (url != null) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        else vm.message.value = "Сервис не прислал ссылку на продление — откройте его сайт или бот"
                    }
                    Pill(Modifier.weight(1f), if (updating) "Обновляю…" else "Обновить", c.container, c.text) { vm.updateSubscriptions() }
                }
                subs.filter { it.id != active.id }.forEach { s -> SubRow(s, false, onClick = { vm.useSubscription(s.id) }, onLong = { menuFor = s }) }
            }
            // В самом низу — «Все VPN в одном» (включается в «Ещё → Подписки»).
            if (allOn && subs.size > 1 && !isAll) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Sz.radius)).background(c.card)
                        .border(1.dp, c.accent.copy(alpha = 0.5f), RoundedCornerShape(Sz.radius))
                        .clickable { vm.useSubscription(app.halo.vpn.core.Store.ALL_ID) }.padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Radio(false)
                    Column(Modifier.weight(1f)) {
                        T("Все VPN в одном", size = Sz.body, weight = Bold)
                        T("Серверы всех ${subs.size} подписок вместе", color = c.muted)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            DashedButton(Modifier.weight(1f), Icons.Outlined.Link, "Ссылка") { linkDialog = true }
            DashedButton(Modifier.weight(1f), Icons.Outlined.QrCodeScanner, "QR-код", onScanQr)
        }
    }

    if (linkDialog) LinkDialog(onDismiss = { linkDialog = false }) {
        linkDialog = false
        vm.addSubscription(it)
    }
    menuFor?.let { s ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = c.card,
            title = { T(s.title, size = Sz.big, weight = Bold) },
            text = { T("Удерживайте подписку, чтобы открыть это меню.", color = c.muted, lines = 2) },
            confirmButton = {
                Row {
                    TextButton({ toRename = s; menuFor = null }) { T("Переименовать", weight = Bold, color = c.accent) }
                    TextButton({ toDelete = s; menuFor = null }) { T("Удалить", weight = Bold, color = c.warn) }
                }
            },
            dismissButton = { TextButton({ menuFor = null }) { T("Отмена", color = c.muted) } },
        )
    }
    toRename?.let { s ->
        var name by remember(s.id) { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { toRename = null },
            containerColor = c.card,
            title = { T("Название подписки", size = Sz.big, weight = Bold) },
            text = { Field(name, { name = it }, "Например, «Основная»") },
            confirmButton = { TextButton({ vm.renameSubscription(s.id, name); toRename = null }) { T("Сохранить", weight = Bold, color = c.accent) } },
            dismissButton = { TextButton({ toRename = null }) { T("Отмена", color = c.muted) } },
        )
    }
    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = c.card,
            title = { T("Удалить подписку?", size = Sz.big, weight = Bold) },
            text = { T("«${s.title}» исчезнет с телефона вместе со списком серверов.", color = c.muted, lines = 3) },
            confirmButton = { TextButton({ vm.removeSubscription(s.id); toDelete = null }) { T("Удалить", weight = Bold, color = c.warn) } },
            dismissButton = { TextButton({ toDelete = null }) { T("Отмена", color = c.accent) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActiveCard(s: Subscription, onLong: () -> Unit) {
    val c = LocalHalo.current
    val m = s.meta
    Column(
        Modifier.fillMaxWidth().shadow(14.dp, RoundedCornerShape(24.dp), spotColor = Color(0x4D3A5AF9), ambientColor = Color(0x4D3A5AF9))
            .clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(c.accessFrom, c.accessTo)))
            .combinedClickable(onClick = {}, onLongClick = onLong).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(s.title, Modifier.weight(1f), size = Sz.body, weight = Bold, color = c.onAccess)
            T("● активна", weight = Bold, color = c.onAccess)
        }
        when {
            m == null -> T("—", size = 40.sp, weight = Bold, mono = true, color = c.onAccess)
            m.total > 0 -> {
                T(gb(m.left), size = 40.sp, weight = Bold, mono = true, color = c.onAccess)
                T("осталось из ${totalShort(m.total)}" + (if (m.expire > 0) " · до ${dayMonth(m.expire)}" else ""), color = c.onAccess.copy(alpha = 0.8f))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.35f))) {
                    Box(Modifier.fillMaxWidth((m.left.toFloat() / m.total).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(c.onAccess))
                }
            }
            else -> {
                T("Безлимит", size = 40.sp, weight = Bold, mono = true, color = c.onAccess)
                T(if (m.expire > 0) "до ${dayMonth(m.expire)}" else "без ограничений по трафику", color = c.onAccess.copy(alpha = 0.8f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubRow(s: Subscription, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val c = LocalHalo.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Sz.radius)).background(c.card)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Radio(selected)
        Column(Modifier.weight(1f)) {
            T(s.title, size = Sz.body, weight = Bold)
            T(subLine(s), color = c.muted)
        }
    }
}

/** Карточка «Все VPN в одном»: сколько подписок и серверов, суммарный остаток. */
@Composable
private fun AllCard(subs: List<Subscription>, servers: Int) {
    val c = LocalHalo.current
    val left = subs.mapNotNull { it.meta }.filter { it.total > 0 }.sumOf { it.left }
    val unlimited = subs.any { it.meta != null && it.meta.total == 0L }
    Column(
        Modifier.fillMaxWidth().shadow(14.dp, RoundedCornerShape(24.dp), spotColor = Color(0x4D3A5AF9), ambientColor = Color(0x4D3A5AF9))
            .clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(c.accessFrom, c.accessTo))).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Все VPN в одном", Modifier.weight(1f), size = Sz.body, weight = Bold, color = c.onAccess)
            T("● активна", weight = Bold, color = c.onAccess)
        }
        T("$servers серверов", size = 40.sp, weight = Bold, mono = true, color = c.onAccess)
        T(
            "${subs.size} подписки" + when {
                unlimited -> " · есть безлимит"
                left > 0 -> " · осталось ${gb(left)}"
                else -> ""
            },
            color = c.onAccess.copy(alpha = 0.8f),
        )
    }
}

private fun subLine(s: Subscription): String {
    val m = s.meta ?: return "ещё не загружалась"
    val until = if (m.expire > 0) " · до ${dayMonth(m.expire)}" else ""
    return if (m.total > 0) "${gb(m.left)}$until" else "Безлимит$until"
}

/** «из 200» — гигабайты без единиц, как в макете. */
private fun totalShort(total: Long): String {
    val g = total / (1024.0 * 1024 * 1024)
    return if (g >= 1) (if (g % 1.0 < 0.05) "${g.toLong()}" else String.format(java.util.Locale.US, "%.1f", g)) else gb(total)
}

@Composable
private fun Pill(modifier: Modifier, text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        T(text, weight = Bold, color = fg)
    }
}

@Composable
private fun DashedButton(modifier: Modifier, icon: ImageVector, text: String, onClick: () -> Unit) {
    val c = LocalHalo.current
    Column(
        modifier.dashed(c.outline, Sz.radius).clip(RoundedCornerShape(Sz.radius)).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = c.accent)
        T(text, weight = Semi)
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val c = LocalHalo.current
    val ctx = LocalContext.current
    var url by remember {
        val clip = runCatching {
            ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
        }.getOrNull()
        mutableStateOf(if (clip != null && clip.startsWith("http")) clip else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { T("Ссылка на подписку", size = Sz.big, weight = Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Её даёт ваш VPN-сервис: в личном кабинете или в боте.", color = c.muted, lines = 2)
                Field(url, { url = it }, "https://…")
            }
        },
        confirmButton = { TextButton({ onAdd(url) }, enabled = url.isNotBlank()) { T("Добавить", weight = Bold, color = c.accent) } },
        dismissButton = { TextButton(onDismiss) { T("Отмена", color = c.muted) } },
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String) {
    val c = LocalHalo.current
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = true, placeholder = { T(placeholder, color = c.muted) },
        shape = RoundedCornerShape(16.dp), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Roboto, fontSize = Sz.label, color = c.text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, cursorColor = c.accent,
            focusedContainerColor = c.card, unfocusedContainerColor = c.card,
        ),
    )
}

// ---------- Настройки (mob-2, второй телефон) ----------

@Composable
fun SettingsScreen(vm: MainViewModel, open: (String) -> Unit) {
    val c = LocalHalo.current
    val ctx = LocalContext.current
    val mode by vm.mode.collectAsState()
    val theme by vm.theme.collectAsState()
    val adblock by vm.adblock.collectAsState()
    val wifi by vm.wifiAuto.collectAsState()
    val lockdown by vm.lockdown.collectAsState()
    val appsMode by vm.appsMode.collectAsState()
    val vpnApps by vm.vpnApps.collectAsState()
    val bypass by vm.bypassApps.collectAsState()
    val saver by vm.batterySaver.collectAsState()
    val unrestricted by vm.unrestricted.collectAsState()
    val blocked by vm.blockedApps.collectAsState()
    val sites by vm.sites.collectAsState()
    val autoConnect by vm.autoConnect.collectAsState()
    val subHours by vm.subUpdateHours.collectAsState()
    val geoAuto by vm.geoAutoUpdate.collectAsState()
    val geoAt by vm.geoUpdatedAt.collectAsState()
    val leak by vm.leakProtection.collectAsState()
    val failover by vm.failover.collectAsState()
    val checkSec by vm.checkSeconds.collectAsState()
    val allInOne by vm.allInOne.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(ScreenPad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T("Настройки", size = Sz.title, weight = Bold)

        SectionLabel("Режим работы")
        Group {
            Mode.entries.forEachIndexed { i, m ->
                GroupRow(last = i == Mode.entries.lastIndex, onClick = { vm.setMode(m) }) {
                    Radio(m == mode)
                    Column(Modifier.weight(1f)) {
                        T(m.title, size = Sz.body, weight = Semi)
                        T(m.hint, color = c.muted)
                    }
                }
            }
        }

        SectionLabel("Подключение")
        Group {
            GroupRow(last = false, padV = 9.dp, onClick = {
                ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                vm.message.value = "Нажмите ⚙ у Halo VPN и включите «Блокировать соединения без VPN»"
            }) {
                T("Kill Switch", Modifier.weight(1f), size = Sz.body, weight = Semi)
                HaloSwitch(lockdown)
            }
            GroupRow(last = false, padV = 9.dp, onClick = { vm.setWifiAuto(!wifi) }) {
                T("VPN в чужом Wi-Fi", Modifier.weight(1f), size = Sz.body, weight = Semi)
                HaloSwitch(wifi)
            }
            GroupRow(last = false, padV = 9.dp, onClick = { vm.setAdblock(!adblock) }) {
                T("Без рекламы", Modifier.weight(1f), size = Sz.body, weight = Semi)
                HaloSwitch(adblock)
            }
            GroupRow(last = false, onClick = { vm.setLeakProtection(!leak) }) {
                Column(Modifier.weight(1f)) {
                    T("Защита от утечек", size = Sz.body, weight = Semi)
                    T("DNS и WebRTC — через VPN, IPv6 закрыт", color = c.muted, lines = 2)
                }
                HaloSwitch(leak)
            }
            GroupRow(last = !failover, onClick = { vm.setFailover(!failover) }) {
                Column(Modifier.weight(1f)) {
                    T("Автопереключение", size = Sz.body, weight = Semi)
                    T("Каждые $checkSec с проверяю сервер — не отвечает, переключаюсь. «Авто» не меняет сервер ради 10 мс и не уходит туда, где не открываются YouTube, ChatGPT или Claude", color = c.muted, lines = 4)
                }
                HaloSwitch(failover)
            }
            if (failover) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    T("Проверка сервера", size = Sz.body, weight = Semi)
                    Segmented(listOf(10, 30, 60), checkSec, { "$it с" }) { vm.setCheckSeconds(it) }
                }
            }
        }

        SectionLabel("Тема")
        Segmented(listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.System), theme, { it.title }) { vm.setTheme(it) }

        SectionLabel("Ещё")
        Group {
            NavRow("Правила", "Категории обхода, свои сайты" + if (sites.isNotEmpty()) " (${sites.size})" else "", last = false) { open("rules") }
            NavRow(
                "Приложения",
                when {
                    blocked.isNotEmpty() -> "Без сети: ${blocked.size}"
                    appsMode == AppsMode.Selected && vpnApps.isNotEmpty() -> "Через VPN — только выбранные (${vpnApps.size})"
                    appsMode == AppsMode.Except && bypass.isNotEmpty() -> "Мимо VPN: ${bypass.size}"
                    else -> "Все приложения — по режиму"
                },
                last = false,
            ) { open("apps") }
            NavRow("Проверка", "IP, открываются ли сайты, скорость", last = false) { open("checks") }
            NavRow("Соединения", "Что идёт через VPN и напрямую — сейчас", last = false) { open("conns") }
            NavRow("Журнал", "Что происходило: подписки, ядро, ошибки", last = true) { open("logs") }
        }

        SectionLabel("Обновления")
        Group {
            GroupRow(last = false, onClick = { vm.setAutoConnect(!autoConnect) }) {
                Column(Modifier.weight(1f)) {
                    T("Подключаться при запуске", size = Sz.body, weight = Semi)
                    T("VPN включается, как только вы открыли Halo", color = c.muted)
                }
                HaloSwitch(autoConnect)
            }
            GroupRow(last = false, onClick = { vm.setAllInOne(!allInOne) }) {
                Column(Modifier.weight(1f)) {
                    T("Все VPN в одном", size = Sz.body, weight = Semi)
                    T("Подписка внизу списка в «Доступе»: серверы всех подписок вместе", color = c.muted, lines = 2)
                }
                HaloSwitch(allInOne)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Обновлять подписки", size = Sz.body, weight = Semi)
                Segmented(listOf(0, 6, 12, 24), subHours, { if (it == 0) "Вручную" else "$it ч" }) { vm.setSubUpdateHours(it) }
            }
            GroupRow(last = true, onClick = { vm.setGeoAutoUpdate(!geoAuto) }) {
                Column(Modifier.weight(1f)) {
                    T("Списки блокировок", size = Sz.body, weight = Semi)
                    T(
                        (if (geoAt > 0) "Обновлены ${java.text.SimpleDateFormat("d MMMM, HH:mm", java.util.Locale.forLanguageTag("ru")).format(java.util.Date(geoAt))}" else "Из приложения") +
                            if (geoAuto) " · сами раз в 3 дня по Wi-Fi" else "",
                        color = c.muted, lines = 2,
                    )
                    T("Обновить сейчас", Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable { vm.updateGeoNow() }, weight = Bold, color = c.accent)
                }
                HaloSwitch(geoAuto)
            }
        }

        SectionLabel("Батарея")
        Group {
            GroupRow(last = false, onClick = { vm.setBatterySaver(!saver) }) {
                Column(Modifier.weight(1f)) {
                    T("Экономия заряда", size = Sz.body, weight = Semi)
                    T(
                        when {
                            saver && failover -> "Все серверы — раз в час, текущий — раз в 30–60 с, уведомление — раз в 5 с, без параллельных подключений"
                            saver -> "Все серверы — раз в 15 мин, уведомление — раз в 5 с, без параллельных подключений"
                            failover -> "Все серверы — раз в 30 мин, текущий — раз в 30 с, уведомление — раз в 2 с"
                            else -> "Все серверы — раз в 5 мин, уведомление — раз в 2 с"
                        },
                        color = c.muted, lines = 3,
                    )
                }
                HaloSwitch(saver)
            }
            GroupRow(last = false, onClick = {
                if (!unrestricted) ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                else vm.message.value = "Уже готово: Android не усыпляет Halo VPN"
            }) {
                Column(Modifier.weight(1f)) {
                    T("Не усыплять Halo VPN", size = Sz.body, weight = Semi)
                    T(if (unrestricted) "Готово — Android не остановит VPN" else "Иначе Android может отключить VPN в фоне", color = if (unrestricted) c.muted else c.warn, lines = 2)
                }
                T(if (unrestricted) "✓" else "›", size = Sz.big, weight = Bold, color = if (unrestricted) c.accent else c.muted)
            }
            GroupRow(last = false, onClick = { openSamsungBattery(ctx) }) {
                Column(Modifier.weight(1f)) {
                    T("Никогда не усыпляемые", size = Sz.body, weight = Semi)
                    T("Samsung: Батарея → Ограничения в фоне → добавьте Halo VPN", color = c.muted, lines = 2)
                }
                T("›", size = Sz.big, color = c.muted)
            }
            GroupRow(last = true, onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) {
                Column(Modifier.weight(1f)) {
                    T("Батарея приложения", size = Sz.body, weight = Semi)
                    T("Выберите «Без ограничений»", color = c.muted)
                }
                T("›", size = Sz.big, color = c.muted)
            }
        }
        T(
            "Halo VPN ${BuildConfigInfo.version(ctx)} · " + if (Core.available) "ядро mihomo" else "ядро не собрано",
            Modifier.fillMaxWidth().padding(top = 6.dp), color = c.muted, align = TextAlign.Center,
        )
    }
}

@Composable
private fun NavRow(title: String, sub: String, last: Boolean, onClick: () -> Unit) {
    val c = LocalHalo.current
    GroupRow(last = last, onClick = onClick) {
        Column(Modifier.weight(1f)) {
            T(title, size = Sz.body, weight = Semi)
            T(sub, color = c.muted)
        }
        T("›", size = Sz.big, color = c.muted)
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Sz.radius)).background(LocalHalo.current.card)) { content() }
}

/** «Обслуживание устройства → Батарея» на Samsung (адрес экрана менялся между версиями One UI), иначе — экран приложения. */
private fun openSamsungBattery(ctx: Context) {
    val candidates = listOf(
        Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        Intent().setClassName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        Intent("com.samsung.android.sm.ACTION_BATTERY"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
    )
    for (i in candidates) {
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

// ---------- Приложения (mob-1, третий телефон) ----------

@Composable
fun AppsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val c = LocalHalo.current
    val apps by vm.apps.collectAsState()
    val mode by vm.appsMode.collectAsState()
    val vpnApps by vm.vpnApps.collectAsState()
    val bypass by vm.bypassApps.collectAsState()
    val blocked by vm.blockedApps.collectAsState()
    var search by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadApps() }

    fun through(pkg: String) = if (mode == AppsMode.Selected) pkg in vpnApps else pkg !in bypass
    // Порядок фиксируем при открытии и смене режима: включённые сверху, но строки не прыгают при переключении.
    val order = remember(apps, mode) { apps?.sortedWith(compareByDescending<AppEntry> { it.pkg in blocked }.thenByDescending { through(it.pkg) }) }

    Column(Modifier.fillMaxSize().then(ScreenPad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("Приложения", "Что идёт через VPN", onBack) {
            Icon(
                if (search == null) Icons.Outlined.Search else Icons.Outlined.Close, "Поиск",
                Modifier.size(34.dp).clip(CircleShape).clickable { search = if (search == null) "" else null }.padding(4.dp), tint = c.text,
            )
        }
        Segmented(listOf(AppsMode.Selected, AppsMode.Except), mode, { if (it == AppsMode.Selected) "Выбранные" else "Кроме них" }) { vm.setAppsMode(it) }
        if (search != null) Field(search!!, { search = it }, "Название приложения")
        if (mode == AppsMode.Selected && vpnApps.isEmpty()) {
            T("Ничего не выбрано — пока через VPN идут все приложения.", color = c.muted, lines = 2)
        }
        T("⛔ — запретить приложению сеть, пока VPN включён. Работает как файрвол.", color = c.muted, lines = 2)
        val list = order?.filter { search.isNullOrBlank() || it.label.contains(search!!, true) }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(Sz.radius)).background(c.card)) {
            if (list == null) {
                T("Загружаю список…", Modifier.padding(18.dp), color = c.muted)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.pkg }) { a ->
                        val isBlocked = a.pkg in blocked
                        GroupRow(last = a == list.last(), padV = 10.dp, onClick = { if (!isBlocked) vm.toggleApp(a.pkg) }) {
                            Column(Modifier.weight(1f)) {
                                T(a.label, size = Sz.body)
                                if (isBlocked) T("Сеть заблокирована", weight = Medium, color = c.warn)
                            }
                            Icon(
                                Icons.Outlined.Block, if (isBlocked) "Разрешить сеть" else "Запретить сеть",
                                Modifier.size(36.dp).clip(CircleShape)
                                    .background(if (isBlocked) c.warn.copy(alpha = 0.16f) else Color.Transparent)
                                    .clickable { vm.setAppBlocked(a.pkg, !isBlocked) }.padding(7.dp),
                                tint = if (isBlocked) c.warn else c.muted.copy(alpha = 0.7f),
                            )
                            if (!isBlocked) HaloSwitch(through(a.pkg))
                        }
                    }
                }
            }
        }
    }
}
