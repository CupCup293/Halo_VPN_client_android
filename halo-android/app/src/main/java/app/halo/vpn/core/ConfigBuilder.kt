package app.halo.vpn.core

/** Категория обхода блокировок — как CATEGORIES в версии для Windows (electron/config.cjs). */
data class Category(val id: String, val title: String, val matchers: List<String>)

/** Свой сайт: домен и что с ним делать. */
data class UserSite(val domain: String, val action: SiteAction)

enum class SiteAction(val title: String) { Vpn("Через VPN"), Direct("Напрямую"), Block("Блок") }

/** DNS для заблокированных доменов — запросы идут через туннель (DoH), провайдер не подменит ответ. */
enum class Doh(val title: String, val url: String) {
    Cloudflare("Cloudflare", "https://1.1.1.1/dns-query"),
    Google("Google", "https://8.8.8.8/dns-query"),
    Quad9("Quad9", "https://9.9.9.9/dns-query"),
    AdGuard("AdGuard", "https://94.140.14.14/dns-query"),
}

/**
 * Конфиг mihomo — те же правила, что у версии для Windows (electron/config.cjs).
 * Подписку ядро читает из файла, который скачало приложение (proxy-providers → path).
 */
object ConfigBuilder {
    const val VPN = "VPN"
    const val AUTO = "Авто · лучший"
    const val PROVIDER = "sub"

    /** Провайдер подписки в ядре: одна подписка — «sub», «Все VPN в одном» — sub0, sub1, … */
    fun providerName(index: Int, all: Boolean) = if (all) "$PROVIDER$index" else PROVIDER

    /** Подпись подписки перед именем сервера в «Все VPN в одном»: «Liberty · 🇫🇷 Франция». Без флагов — иначе спутается страна. */
    fun prefix(s: Subscription): String {
        val t = Countries.clean(s.title).take(18).trim()
        return "$t · "
    }

    /** Порты WebRTC/STUN: по ним браузер узнаёт настоящий IP — с защитой от утечек они идут через VPN. */
    private const val STUN_PORTS = "3478/5349/19302-19309"
    // Порты выбирает Engine: если 9097/7890 заняты другим приложением (Clash, FlClash, Hiddify), берёт свободные.
    val CONTROLLER_PORT get() = Engine.controllerPort
    val MIXED_PORT get() = Engine.mixedPort
    const val TEST_URL = "https://cp.cloudflare.com/generate_204"

    // Сервисы проверки IP: один всегда напрямую, другой всегда через VPN — как в версии для Windows.
    const val IP_CHECK_DIRECT = "ipwho.is"
    const val IP_CHECK_VPN = "ipinfo.io"

    // Узлы с выходом в России: пинг лучший, но заблокированное через них не откроется — в автовыбор не берём.
    private const val RU_EXIT = "(?i)🇷🇺|росси|(^|[^а-яё])рф([^а-яё]|\$)|russia|moscow|москв"

    val CATEGORIES = listOf(
        Category("rkn", "Реестр РКН", listOf("GEOSITE,ru-blocked", "GEOIP,ru-blocked,no-resolve")),
        Category("youtube", "YouTube", listOf("GEOSITE,youtube")),
        Category("meta", "Instagram · Facebook", listOf("GEOSITE,instagram", "GEOSITE,facebook")),
        Category("twitter", "X (Twitter)", listOf("GEOSITE,twitter")),
        Category("ai", "AI-сервисы", listOf("GEOSITE,category-ai-!cn")),
        Category("discord", "Discord", listOf("GEOSITE,discord")),
        Category("telegram", "Telegram", listOf("GEOSITE,telegram", "GEOIP,telegram,no-resolve")),
        Category(
            "geoblock", "Ушли из РФ / геоблок",
            listOf("netflix", "disney", "notion", "figma", "canva", "jetbrains", "docker", "intel", "nvidia", "autodesk", "soundcloud", "patreon", "medium", "tiktok")
                .map { "GEOSITE,$it" },
        ),
        Category("spotify", "Spotify", listOf("GEOSITE,spotify")),
        Category("torrents", "Торрент-трекеры", listOf("GEOSITE,category-public-tracker")),
    )
    val DEFAULT_CATEGORIES = CATEGORIES.map { it.id }.toSet() - "torrents"

    private val RU_SERVICES = listOf("2ip.io", "2ip.me", "gosuslugi.ru", "sberbank.com", "tbank-online.com", "vk.company", "ozone.ru", "wb.ru")
    private val LOCAL_NETS = listOf(
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10", "224.0.0.0/4", "255.255.255.255/32",
    )
    private val LOCAL_NETS6 = listOf("::1/128", "fc00::/7", "fe80::/10", "ff00::/8")

    /** Строка YAML в двойных кавычках. Не JSONObject.quote: тот пишет «\/», а YAML-разбор ядра такое не принимает. */
    private fun q(s: String): String {
        val b = StringBuilder("\"")
        for (ch in s) when {
            ch == '\\' -> b.append("\\\\")
            ch == '"' -> b.append("\\\"")
            ch == '\n' -> b.append("\\n")
            ch == '\t' -> b.append("\\t")
            ch < ' ' -> b.append(String.format("\\x%02x", ch.code))
            else -> b.append(ch)
        }
        return b.append('"').toString()
    }

    private fun list(items: List<String>) = items.joinToString(", ", "[", "]") { q(it) }

    private fun withAction(matcher: String, action: String): String =
        if (matcher.endsWith(",no-resolve")) "${matcher.removeSuffix(",no-resolve")},$action,no-resolve" else "$matcher,$action"

    /** Правила в порядке приоритета (первое совпадение выигрывает) — как buildRules в версии для Windows. */
    fun rules(store: Store): List<String> {
        val r = mutableListOf<String>()
        // Адреса самого туннеля: DNS на :53 ядро перехватывает раньше правил, а остальное (DoT на :853 от «Частного DNS»)
        // отклоняем сразу — иначе Android секунды ждёт ответа, прежде чем перейти на обычный DNS.
        r += "IP-CIDR,172.19.0.0/30,REJECT,no-resolve"
        // Приложения, которым запрещена сеть, — раньше всего остального: им нельзя ни через VPN, ни напрямую.
        store.blockedApps.sorted().forEach { r += "PROCESS-NAME,$it,REJECT" }
        LOCAL_NETS.forEach { r += "IP-CIDR,$it,DIRECT,no-resolve" }
        LOCAL_NETS6.forEach { r += "IP-CIDR6,$it,DIRECT,no-resolve" }
        r += "DOMAIN-SUFFIX,local,DIRECT"
        // Защита от утечки IP через WebRTC: STUN-запросы браузера — только через VPN.
        if (store.leakProtection && store.mode != Mode.Global) r += "AND,((NETWORK,UDP),(DST-PORT,$STUN_PORTS)),$VPN"
        r += "DOMAIN,$IP_CHECK_DIRECT,DIRECT"
        r += "DOMAIN,$IP_CHECK_VPN,$VPN"
        // Свои сайты — важнее режима и категорий.
        for (s in store.sites) {
            r += "DOMAIN-SUFFIX,${s.domain}," + when (s.action) {
                SiteAction.Vpn -> VPN
                SiteAction.Direct -> "DIRECT"
                SiteAction.Block -> "REJECT"
            }
        }
        if (store.adblock) r += "GEOSITE,category-ads-all,REJECT"
        val ru = { RU_SERVICES.forEach { r += "DOMAIN-SUFFIX,$it,DIRECT" } }
        when (store.mode) {
            Mode.Blocked -> {
                r += "GEOSITE,ru-available-only-inside,DIRECT"
                ru()
                val on = store.categories
                CATEGORIES.filter { it.id in on }.forEach { c -> c.matchers.forEach { r += withAction(it, VPN) } }
                r += "MATCH,DIRECT"
            }
            Mode.ExceptRu -> {
                r += "GEOSITE,ru-blocked,$VPN"
                r += "GEOSITE,ru-available-only-inside,DIRECT"
                ru()
                r += listOf("GEOSITE,category-ru,DIRECT", "DOMAIN-SUFFIX,ru,DIRECT", "DOMAIN-SUFFIX,su,DIRECT", "DOMAIN-SUFFIX,xn--p1ai,DIRECT", "GEOIP,RU,DIRECT")
                r += "MATCH,$VPN"
            }
            Mode.Global -> r += "MATCH,$VPN"
        }
        return r
    }

    /** [tunFd] — дескриптор от VpnService; null — ядро без туннеля (чтобы загрузить серверы и замерить пинг). */
    fun build(store: Store, tunFd: Int?): String {
        val subs = store.activeSubs
        val all = store.isAll
        val leak = store.leakProtection
        // Автопереключение само следит за текущим сервером — общую проверку пинга можно делать реже.
        val testInterval = when {
            store.failover && store.batterySaver -> 3600
            store.failover -> 1800
            store.batterySaver -> 900
            else -> 300
        }
        // Экономия заряда: реже проверяем пинг и реже шлём keep-alive — радиомодуль телефона дольше спит.
        val saver = store.batterySaver
        val on = store.categories
        val blockedSites = (if ("rkn" in on) listOf("ru-blocked") else emptyList()) +
            CATEGORIES.filter { it.id != "rkn" && it.id in on }.flatMap { c -> c.matchers.filter { it.startsWith("GEOSITE,") }.map { it.removePrefix("GEOSITE,") } }
        // Приложение определяем только когда есть правила по приложениям: каждое определение — запрос к Android.
        val findProcess = if (store.blockedApps.isEmpty()) "off" else "strict"
        val y = StringBuilder()
        y.appendLine(
            """
            mixed-port: $MIXED_PORT
            allow-lan: false
            bind-address: "127.0.0.1"
            mode: rule
            log-level: warning
            ipv6: false
            unified-delay: true
            tcp-concurrent: ${!saver}
            keep-alive-idle: ${if (saver) 900 else 600}
            keep-alive-interval: ${if (saver) 60 else 30}
            find-process-mode: $findProcess
            external-controller: "127.0.0.1:$CONTROLLER_PORT"
            secret: ${q(store.secret)}
            geodata-mode: true
            geodata-loader: memconservative
            geo-auto-update: false
            profile: { store-selected: true, store-fake-ip: true }
            sniffer:
              enable: true
              parse-pure-ip: true
              sniff:
                HTTP: { ports: [80, "8080-8880"] }
                TLS: { ports: [443, 8443] }
                QUIC: { ports: [443, 8443] }
            dns:
              enable: true
              ipv6: false
              enhanced-mode: fake-ip
              fake-ip-range: "198.18.0.1/16"
              fake-ip-filter: ["*.lan", "*.local", "+.msftconnecttest.com", "+.msftncsi.com", "+.connectivitycheck.gstatic.com"]
              default-nameserver: ["77.88.8.8", "8.8.8.8"]
              nameserver: [${if (leak) q(store.doh.url + "#" + VPN) else q("https://77.88.8.8/dns-query")}]
              proxy-server-nameserver: ["https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query", "https://77.88.8.8/dns-query"]
            """.trimIndent(),
        )
        // Защита от утечек DNS: все запросы идут зашифрованно через VPN, российские сайты — через Яндекс напрямую
        // (иначе им виден не тот регион). Без защиты — наоборот: через VPN только заблокированное.
        val ruDirect = leak && store.mode != Mode.Global
        if (blockedSites.isNotEmpty() || ruDirect) {
            y.appendLine("  nameserver-policy:")
            if (blockedSites.isNotEmpty()) y.appendLine("    ${q("geosite:" + blockedSites.joinToString(","))}: [${q(store.doh.url + "#" + VPN)}]")
            if (ruDirect) y.appendLine("    ${q("geosite:category-ru,ru-available-only-inside")}: [${q("https://77.88.8.8/dns-query")}]")
        }
        if (subs.isNotEmpty()) {
            y.appendLine("proxy-providers:")
            val names = subs.mapIndexed { i, s ->
                val name = providerName(i, all)
                y.appendLine("  $name:")
                y.appendLine("    type: http")
                y.appendLine("    url: ${q(s.url)}")
                y.appendLine("    path: \"./providers/${s.id}.yaml\"")
                y.appendLine("    interval: 43200")
                y.appendLine("    header: { User-Agent: [\"clash.meta\"] }")
                y.appendLine("    health-check: { enable: true, url: ${q(TEST_URL)}, interval: 3600, lazy: true }")
                // В «Все VPN в одном» имена серверов разных подписок могут совпасть — подписываем подпиской.
                if (all) y.appendLine("    override: { additional-prefix: ${q(prefix(s))} }")
                name
            }
            val use = names.joinToString(", ", "[", "]")
            y.appendLine("proxy-groups:")
            y.appendLine("  - { name: ${q(VPN)}, type: select, proxies: [${q(AUTO)}], use: $use }")
            y.appendLine("  - { name: ${q(AUTO)}, type: url-test, use: $use, exclude-filter: ${q(RU_EXIT)}, url: ${q(TEST_URL)}, interval: $testInterval, lazy: true, tolerance: 50 }")
            // Всё, что пришло на этот порт, — через VPN, мимо правил: так скачивается подписка, сайт которой заблокирован.
            y.appendLine("listeners:")
            y.appendLine("  - { name: via-vpn, type: mixed, listen: \"127.0.0.1\", port: ${Engine.vpnPort}, proxy: ${q(VPN)} }")
        } else {
            y.appendLine("proxy-groups:\n  - { name: ${q(VPN)}, type: select, proxies: [DIRECT] }")
        }
        y.appendLine("rules: ${list(rules(store))}")
        if (tunFd != null) {
            y.appendLine(
                """
                tun:
                  enable: true
                  stack: gvisor
                  file-descriptor: $tunFd
                  mtu: ${HaloNet.MTU}
                  auto-route: false
                  auto-detect-interface: false
                  dns-hijack: ["any:53"]
                """.trimIndent(),
            )
        }
        return y.toString()
    }
}

/** Адреса туннеля VpnService. */
object HaloNet {
    const val MTU = 1500
    const val ADDRESS = "172.19.0.1"
    const val DNS = "172.19.0.2"
    const val ADDRESS6 = "fdfe:dcba:9876::1"
}
