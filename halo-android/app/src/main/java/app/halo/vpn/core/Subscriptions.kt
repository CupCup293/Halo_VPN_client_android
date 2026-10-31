package app.halo.vpn.core

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Сведения о подписке из заголовков ответа сервера подписки. */
data class SubMeta(
    val title: String? = null,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0, // секунды Unix
    val renewUrl: String? = null,
    val updatedAt: Long = 0,
) {
    val used get() = upload + download
    val left get() = (total - used).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject().put("title", title).put("upload", upload).put("download", download)
        .put("total", total).put("expire", expire).put("renewUrl", renewUrl).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) null else SubMeta(
            o.optString("title").takeIf { it.isNotEmpty() && it != "null" }, o.optLong("upload"), o.optLong("download"),
            o.optLong("total"), o.optLong("expire"), o.optString("renewUrl").takeIf { it.isNotEmpty() && it != "null" }, o.optLong("updatedAt"),
        )
    }
}

/**
 * Скачивает подписку сам (а не только ядро): так видны название, остаток трафика и срок у всех подписок,
 * а не только у активной. Тело кладём туда, откуда его читает ядро (proxy-providers → path) — второй раз
 * ядро его не качает. Приложение исключено из туннеля, поэтому запрос идёт напрямую.
 */
object Subscriptions {
    const val USER_AGENT = "clash.meta"

    fun providerFile(ctx: Context, id: String) = File(Engine.home(ctx), "providers/$id.yaml")

    /**
     * По очереди, пока не загрузится: напрямую (дважды — первая попытка часто не успевает, пока телефон будит сеть),
     * через VPN-сервер уже добавленной подписки (сайт подписки бывает заблокирован у провайдера) и через ядро по правилам.
     * Раньше вторая попытка была только через правила — а они отправляли сайт подписки напрямую, и снова неудача.
     */
    fun fetch(ctx: Context, sub: Subscription): SubMeta {
        val ways = buildList {
            add("напрямую" to Proxy.NO_PROXY)
            add("напрямую, ещё раз" to Proxy.NO_PROXY)
            if (Engine.coreRunning && Store(ctx).subscriptions.any { it.id != sub.id }) {
                add("через VPN" to Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", Engine.vpnPort)))
            }
            if (Engine.coreRunning) add("через ядро" to Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ConfigBuilder.MIXED_PORT)))
        }
        var error: Exception? = null
        for ((how, proxy) in ways) {
            try {
                return fetchAnyFormat(ctx, sub, proxy)
            } catch (e: Exception) {
                HaloLog.w("подписка", "«${sub.title}» $how не загрузилась", e)
                // Ответ сервера («отказал в доступе», «не подписка») понятнее, чем таймаут, — его и покажем.
                // Через VPN он может быть другим: часть сервисов не отдаёт подписку на российский IP.
                if (error == null || (e is BadSubscription && error !is BadSubscription)) error = e
            }
        }
        throw error ?: IllegalStateException("нет сети")
    }

    /** Сервер подписки ответил, но не подпиской: 401/403/404 или формат, который Halo не понимает. */
    class BadSubscription(message: String) : Exception(message)

    /**
     * Сервисы отдают подписку по-разному в зависимости от User-Agent. Сначала просим формат Clash (clash.meta);
     * если пришло то, что ядро не разберёт (sing-box JSON, страница-заглушка), — просим список ссылок (v2rayN).
     */
    private fun fetchAnyFormat(ctx: Context, sub: Subscription, proxy: Proxy): SubMeta {
        var refused: BadSubscription? = null
        for (ua in listOf(USER_AGENT, "v2rayN/7.4.2", "mihomo/1.19.32")) {
            // Часть сервисов отказывает «чужому» приложению (403 на clash.meta) — тогда пробуем другой User-Agent.
            val meta = try {
                fetchVia(ctx, sub, proxy, ua)
            } catch (e: BadSubscription) {
                refused = e
                continue
            }
            if (looksLikeSubscription(providerFile(ctx, sub.id).readText())) return meta
            HaloLog.w("подписка", "«${sub.title}» с User-Agent $ua — нет серверов для ядра, пробую другой")
        }
        throw refused ?: BadSubscription("сервис прислал подписку в формате, который Halo не понимает")
    }

    /** Clash YAML с proxies или список ссылок (как есть или в base64). */
    fun looksLikeSubscription(text: String): Boolean = describe(text).servers > 0

    /** Что лежит в файле подписки — без самих ключей: формат и сколько серверов каких протоколов. */
    data class Contents(val format: String, val servers: Int, val byType: Map<String, Int>, val other: Int)

    private val PROXY_SCHEMES = setOf("vless", "vmess", "trojan", "ss", "ssr", "hysteria2", "hy2", "hysteria", "tuic", "wireguard", "anytls", "socks", "socks5")

    fun describe(text: String): Contents {
        val t = text.trimStart()
        if (Regex("(?m)^proxies:").containsMatchIn(text)) {
            val types = Regex("""(?<![\w-])type:\s*"?([\w-]+)""").findAll(text.substringAfter("proxies:").let { rest ->
                Regex("(?m)^[A-Za-z][\\w-]*:").find(rest)?.let { rest.substring(0, it.range.first) } ?: rest
            }).map { it.groupValues[1].lowercase() }.toList()
            return Contents("Clash YAML", types.size, types.groupingBy { it }.eachCount(), 0)
        }
        if (t.startsWith("{") || t.startsWith("[")) return Contents(if (t.contains("\"outbounds\"")) "sing-box / Xray JSON" else "JSON", 0, emptyMap(), 0)
        if (t.startsWith("<")) return Contents("HTML-страница (не подписка)", 0, emptyMap(), 0)
        val lines = decodeLinks(text).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val schemes = lines.map { it.substringBefore("://", "?").lowercase() }
        val proxies = schemes.filter { it in PROXY_SCHEMES }
        return Contents("список ссылок", proxies.size, proxies.groupingBy { it }.eachCount(), lines.size - proxies.size)
    }

    private fun fetchVia(ctx: Context, sub: Subscription, proxy: Proxy, ua: String): SubMeta {
        val c = URL(sub.url).openConnection(proxy) as HttpURLConnection
        c.connectTimeout = 7000
        c.readTimeout = 12000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        try {
            val code = c.responseCode
            if (code >= 400) HaloLog.w("подписка", "«${sub.title}» · UA $ua · HTTP $code")
            if (code in 400..499) throw BadSubscription(
                when (code) {
                    401, 403 -> "сервер подписки отказал в доступе ($code) — проверьте ссылку"
                    404 -> "подписка не найдена (404) — ссылка устарела?"
                    else -> "сервер подписки ответил $code"
                },
            )
            if (code >= 500) throw IllegalStateException("сервер подписки ответил $code — попробуйте позже")
            val body = c.inputStream.use { it.readBytes() }
            val inside = describe(String(body, Charsets.UTF_8))
            HaloLog.i(
                "подписка",
                "«${sub.title}» · UA $ua · ${if (proxy == Proxy.NO_PROXY) "напрямую" else "через ядро"} · HTTP $code · " +
                    "${c.contentType ?: "без типа"} · ${body.size} байт · ${inside.format} · серверов ${inside.servers}" +
                    (if (inside.byType.isNotEmpty()) " ${inside.byType}" else "") + (if (inside.other > 0) " · других строк ${inside.other}" else ""),
            )
            if (body.isEmpty()) throw IllegalStateException("сервер подписки вернул пустой ответ")
            val file = providerFile(ctx, sub.id)
            file.parentFile?.mkdirs()
            file.writeBytes(body)
            return parse(c)
        } finally {
            c.disconnect()
        }
    }

    private fun parse(c: HttpURLConnection): SubMeta {
        val info = c.getHeaderField("subscription-userinfo").orEmpty()
        val fields = info.split(';').mapNotNull {
            val kv = it.trim().split('=', limit = 2)
            if (kv.size == 2) kv[0].trim().lowercase() to (kv[1].trim().toDoubleOrNull()?.toLong() ?: 0L) else null
        }.toMap()
        return SubMeta(
            title = title(c.getHeaderField("profile-title")),
            upload = fields["upload"] ?: 0,
            download = fields["download"] ?: 0,
            total = fields["total"] ?: 0,
            expire = fields["expire"] ?: 0,
            renewUrl = (c.getHeaderField("profile-web-page-url") ?: c.getHeaderField("support-url"))?.trim()?.takeIf { it.startsWith("http") },
            updatedAt = System.currentTimeMillis(),
        )
    }

    private var labelsCache: Triple<String, Long, Map<String, String>>? = null

    /**
     * Подпись протокола каждого сервера: «VLESS · Reality», «VMess · WS». В API ядра этого нет —
     * читаем из файла подписки (Clash YAML или список ссылок, в том числе в base64).
     */
    fun labels(ctx: Context, id: String): Map<String, String> {
        val file = providerFile(ctx, id)
        val stamp = file.lastModified()
        labelsCache?.let { if (it.first == id && it.second == stamp) return it.third }
        val text = runCatching { file.readText() }.getOrDefault("")
        val out = if (Regex("(?m)^proxies:").containsMatchIn(text)) yamlLabels(text) else linkLabels(decodeLinks(text))
        labelsCache = Triple(id, stamp, out)
        return out
    }

    private fun label(type: String, reality: Boolean, network: String?): String {
        val base = Countries.protocol(type)
        val extra = when {
            reality -> "Reality"
            network.isNullOrEmpty() || network == "tcp" || network == "raw" -> null
            network == "grpc" -> "gRPC"
            network == "xhttp" || network == "splithttp" -> "XHTTP"
            else -> network.uppercase()
        }
        return if (extra != null) "$base · $extra" else base
    }

    private fun decodeLinks(text: String): String {
        val t = text.trim()
        if (t.contains("://")) return t
        val clean = t.replace("\\s".toRegex(), "")
        return runCatching { String(Base64.decode(clean, Base64.DEFAULT)) }
            .recoverCatching { String(Base64.decode(clean, Base64.URL_SAFE)) }
            .getOrDefault(t)
    }

    private fun linkLabels(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val l = line.trim()
            val scheme = l.substringBefore("://", "").lowercase()
            if (scheme.isEmpty() || !l.contains('#')) continue
            val name = runCatching { java.net.URLDecoder.decode(l.substringAfterLast('#'), "UTF-8") }.getOrDefault(l.substringAfterLast('#')).trim()
            val query = l.substringAfter('?', "").substringBefore('#')
            val q = query.split('&').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val type = when (scheme) {
                "ss" -> "shadowsocks"
                "hy2" -> "hysteria2"
                else -> scheme
            }
            out[name] = label(type, q["security"] == "reality", q["type"])
        }
        return out
    }

    private fun yamlLabels(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val section = text.substringAfter("proxies:").let { rest ->
            // Секция proxies — до следующего ключа верхнего уровня.
            Regex("(?m)^[A-Za-z][\\w-]*:").find(rest)?.let { rest.substring(0, it.range.first) } ?: rest
        }
        val items = section.split(Regex("(?m)^\\s*- ")).drop(1)
        val nameRe = Regex("""name:\s*(?:"([^"]*)"|'([^']*)'|([^,}\n]+))""")
        val typeRe = Regex("""(?<![\w-])type:\s*"?([\w-]+)""")
        val netRe = Regex("""network:\s*"?([\w-]+)""")
        for (item in items) {
            val m = nameRe.find(item) ?: continue
            val name = (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).trim()
            val type = typeRe.find(item)?.groupValues?.get(1) ?: continue
            out[name] = label(type, item.contains("reality-opts"), netRe.find(item)?.groupValues?.get(1))
        }
        return out
    }

    /** profile-title бывает как есть или «base64:…». */
    private fun title(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.startsWith("base64:")) {
            return runCatching { String(Base64.decode(t.removePrefix("base64:"), Base64.DEFAULT)).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        return runCatching { java.net.URLDecoder.decode(t, "UTF-8") }.getOrDefault(t)
    }
}
