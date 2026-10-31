package app.halo.vpn.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

data class Server(val name: String, val type: String, val delay: Int?)

data class ServerList(val servers: List<Server>, val selected: String?, val autoNow: String?) {
    /** Сервер, через который идёт трафик сейчас (для «Авто» — выбранный автоматически). */
    val current: String? get() = if (selected == ConfigBuilder.AUTO) autoNow else selected
}

/** Расход и срок подписки из заголовка subscription-userinfo (его разбирает ядро). */
data class SubInfo(val used: Long, val total: Long, val expire: Long, val updatedAt: String?)

data class Traffic(val up: Long, val down: Long)

/** country — двухбуквенный код страны строчными (для флага). */
data class IpInfo(val ip: String, val country: String, val city: String)

/** REST API ядра на 127.0.0.1 — те же запросы, что в версии для Windows. */
class Controller(private val secret: String) {
    private val base = "http://127.0.0.1:${ConfigBuilder.CONTROLLER_PORT}"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun call(method: String, path: String, body: String? = null, timeout: Int = 4000): String {
        val c = URL(base + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 1500
        c.readTimeout = timeout
        c.setRequestProperty("Authorization", "Bearer $secret")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) throw IllegalStateException(JSONObject(text.ifEmpty { "{}" }).optString("message", "HTTP $code"))
            return text
        } finally {
            c.disconnect()
        }
    }

    fun servers(): ServerList {
        val all = JSONObject(call("GET", "/proxies")).getJSONObject("proxies")
        val group = all.optJSONObject(ConfigBuilder.VPN) ?: return ServerList(emptyList(), null, null)
        val names = group.getJSONArray("all")
        // Серверы подписок (proxy-providers) в /proxies не попадают — тип и пинг берём у провайдеров.
        val provided = runCatching { providerProxies().associateBy { it.getString("name") } }.getOrDefault(emptyMap())
        val list = (0 until names.length()).map { names.getString(it) }
            .filter { it != ConfigBuilder.AUTO && it != "DIRECT" }
            .map { name ->
                val p = provided[name] ?: all.optJSONObject(name)
                Server(name, p?.optString("type").orEmpty(), p?.let { lastDelay(it) })
            }
        val autoNow = all.optJSONObject(ConfigBuilder.AUTO)?.optString("now")?.takeIf { it.isNotEmpty() }
        return ServerList(list, group.optString("now").takeIf { it.isNotEmpty() }, autoNow)
    }

    /** Последний замер: замеры по нашему адресу проверки лежат в extra[url], общие — в history; 0 — не ответил. */
    private fun lastDelay(o: JSONObject): Int? {
        fun last(h: org.json.JSONArray?) = if (h != null && h.length() > 0) h.getJSONObject(h.length() - 1).optInt("delay") else null
        val d = last(o.optJSONObject("extra")?.optJSONObject(ConfigBuilder.TEST_URL)?.optJSONArray("history")) ?: last(o.optJSONArray("history"))
        return d?.takeIf { it > 0 }
    }

    fun select(name: String) {
        call("PUT", "/proxies/${enc(ConfigBuilder.VPN)}", JSONObject().put("name", name).toString())
    }

    /** Наши провайдеры подписок (sub, sub0, sub1, …) — без служебных default/VPN. */
    private fun providerNames(): List<String> {
        val p = JSONObject(call("GET", "/providers/proxies")).optJSONObject("providers") ?: return emptyList()
        return p.keys().asSequence().filter { it.startsWith(ConfigBuilder.PROVIDER) }.toList()
    }

    private fun providerProxies(): List<JSONObject> {
        val p = JSONObject(call("GET", "/providers/proxies")).optJSONObject("providers") ?: return emptyList()
        return p.keys().asSequence().filter { it.startsWith(ConfigBuilder.PROVIDER) }.flatMap { name ->
            val a = p.getJSONObject(name).optJSONArray("proxies")
            if (a == null) emptySequence() else (0 until a.length()).asSequence().map { a.getJSONObject(it) }
        }.toList()
    }

    /** Пинг всех серверов всех подписок разом: имя → задержка (нет в ответе — сервер не ответил). */
    fun testAll(): Map<String, Int> {
        for (name in providerNames()) runCatching { call("GET", "/providers/proxies/${enc(name)}/healthcheck", timeout = 15000) }
        return providerProxies().mapNotNull { o -> lastDelay(o)?.let { o.getString("name") to it } }.toMap()
    }

    /** Пинг одного сервера сейчас (мс) или null — не ответил за [timeout]. */
    fun delay(name: String, timeout: Int = 5000): Int? = runCatching {
        val path = "/proxies/${enc(name)}/delay?url=${enc(ConfigBuilder.TEST_URL)}&timeout=$timeout"
        JSONObject(call("GET", path, timeout = timeout + 2000)).optInt("delay").takeIf { it > 0 }
    }.getOrNull()

    /** Открывается ли сервис через сервер: запрос идёт через ядро именно через [node], ответ 403 и 5xx — нет. */
    fun serviceOk(node: String, url: String): Boolean = runCatching {
        val path = "/proxies/${enc(node)}/delay?url=${enc(url)}&timeout=6000&expected=${enc(AutoPicker.SERVICE_OK_STATUS)}"
        JSONObject(call("GET", path, timeout = 8000)).optInt("delay") > 0
    }.getOrDefault(false)

    /** Закрепить сервер в «Авто · лучший» (url-test сам снимет закрепление, если сервер перестанет отвечать). */
    fun fixAuto(name: String) {
        call("PUT", "/proxies/${enc(ConfigBuilder.AUTO)}", JSONObject().put("name", name).toString())
    }

    /** Сведения о провайдерах подписок (для отчёта): сколько серверов и когда обновлены. */
    fun providerRaw(): String {
        val p = JSONObject(call("GET", "/providers/proxies")).optJSONObject("providers") ?: return "{}"
        val out = JSONObject()
        val total = org.json.JSONArray()
        p.keys().asSequence().filter { it.startsWith(ConfigBuilder.PROVIDER) }.forEach { name ->
            val o = p.getJSONObject(name)
            o.optJSONArray("proxies")?.let { a -> for (i in 0 until a.length()) total.put(a.get(i)) }
            out.put("updatedAt", o.optString("updatedAt"))
        }
        return out.put("proxies", total).toString()
    }

    fun updateSubscription() {
        for (name in providerNames()) call("PUT", "/providers/proxies/${enc(name)}", timeout = 30000)
    }

    /**
     * Скорость прямо сейчас — поток ядра /traffic (строка {"up":…,"down":…} раз в секунду).
     * Дешевле, чем раз в секунду забирать список всех соединений. Блокирует до ошибки или [conn].disconnect().
     */
    fun openTraffic(): HttpURLConnection {
        val c = URL("$base/traffic").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 5000
        c.setRequestProperty("Authorization", "Bearer $secret")
        return c
    }

    fun readTraffic(c: HttpURLConnection, onSample: (up: Long, down: Long) -> Unit) {
        c.inputStream.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                onSample(o.optLong("up"), o.optLong("down"))
            }
        }
    }

    fun subInfo(): SubInfo? {
        val o = runCatching { JSONObject(call("GET", "/providers/proxies/${ConfigBuilder.PROVIDER}")) }.getOrNull() ?: return null
        val s = o.optJSONObject("subscriptionInfo")
        return SubInfo(
            used = (s?.optLong("Upload") ?: 0) + (s?.optLong("Download") ?: 0),
            total = s?.optLong("Total") ?: 0,
            expire = s?.optLong("Expire") ?: 0,
            updatedAt = o.optString("updatedAt").takeIf { it.isNotEmpty() },
        )
    }

    /** Всего передано с запуска ядра. */
    fun traffic(): Traffic {
        val o = JSONObject(call("GET", "/connections"))
        return Traffic(o.optLong("uploadTotal"), o.optLong("downloadTotal"))
    }

    /** Активные соединения: откуда, куда, по какому правилу, через какой сервер. */
    fun connections(): List<Conn> {
        val a = JSONObject(call("GET", "/connections")).optJSONArray("connections") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val m = o.optJSONObject("metadata") ?: JSONObject()
            val chains = o.optJSONArray("chains")
            val host = m.optString("host").ifEmpty { m.optString("sniffHost") }.ifEmpty { m.optString("destinationIP") }
            Conn(
                id = o.optString("id"),
                host = host,
                port = m.optString("destinationPort"),
                network = m.optString("network"),
                app = m.optString("process").takeIf { it.isNotEmpty() },
                rule = o.optString("rule") + o.optString("rulePayload").let { if (it.isEmpty()) "" else " $it" },
                // chains — от сервера к группе: [сервер, «Авто · лучший», «VPN»] или [DIRECT]
                via = chains?.takeIf { it.length() > 0 }?.getString(0) ?: "",
                up = o.optLong("upload"),
                down = o.optLong("download"),
                start = o.optString("start"),
            )
        }
    }

    fun closeConnection(id: String) {
        call("DELETE", "/connections/${enc(id)}")
    }

    fun closeAll() {
        call("DELETE", "/connections")
    }

    /** Внешний адрес напрямую (ipwho.is всегда идёт мимо VPN) и через VPN (ipinfo.io — всегда через VPN). */
    fun ipDirect(): IpInfo {
        val o = JSONObject(viaCore("https://${ConfigBuilder.IP_CHECK_DIRECT}/"))
        return IpInfo(o.optString("ip"), o.optString("country_code").lowercase(), o.optString("city"))
    }

    fun ipVpn(): IpInfo {
        val o = JSONObject(viaCore("https://${ConfigBuilder.IP_CHECK_VPN}/json"))
        return IpInfo(o.optString("ip"), o.optString("country").lowercase(), o.optString("city"))
    }

    /** Открывается ли сайт — через ядро, по тем же правилам, что у остальных приложений. Время ответа или null. */
    fun checkSite(url: String): Int? {
        val t0 = System.nanoTime()
        return try {
            val c = URL(url).openConnection(coreProxy) as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) HaloVPN")
            c.responseCode
            c.disconnect()
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        } catch (_: Exception) {
            null
        }
    }

    /** Тест скорости загрузки через ядро: до 10 секунд или 50 МБ. Мегабиты в секунду. */
    fun speedTest(onProgress: (Double) -> Unit): Double {
        val c = URL("https://speed.cloudflare.com/__down?bytes=50000000").openConnection(coreProxy) as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val buf = ByteArray(64 * 1024)
        var total = 0L
        val t0 = System.nanoTime()
        var lastReport = t0
        c.inputStream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                val now = System.nanoTime()
                if (now - lastReport > 300_000_000) {
                    lastReport = now
                    onProgress(total * 8 / 1e6 / ((now - t0) / 1e9))
                }
                if (now - t0 > 10_000_000_000) break
            }
        }
        c.disconnect()
        val sec = (System.nanoTime() - t0) / 1e9
        return total * 8 / 1e6 / sec
    }

    private val coreProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ConfigBuilder.MIXED_PORT))

    private fun viaCore(url: String): String {
        val c = URL(url).openConnection(coreProxy) as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "HaloVPN")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}

data class Conn(
    val id: String,
    val host: String,
    val port: String,
    val network: String,
    val app: String?,
    val rule: String,
    val via: String,
    val up: Long,
    val down: Long,
    val start: String,
)
