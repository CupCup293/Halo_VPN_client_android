package app.halo.vpn.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Режимы — как в версии для Windows; названия — как в макете. */
enum class Mode(val id: String, val title: String, val hint: String, val chip: String) {
    Blocked("blocked", "Обход блокировок", "Через VPN — только закрытое", "Правила"),
    ExceptRu("except-ru", "Кроме РФ", "Российские сайты напрямую", "Кроме РФ"),
    Global("global", "Весь трафик", "Всё идёт через VPN", "Весь трафик");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Blocked
    }
}

enum class ThemeMode(val title: String) { Dark("Тёмная"), Light("Светлая"), System("Авто") }

/** Приложения: только выбранные через VPN или все, кроме выбранных. */
enum class AppsMode { Selected, Except }

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val addedAt: Long,
    val meta: SubMeta? = null,
    /** Своё название — важнее того, что прислал сервис. */
    val customName: String? = null,
) {
    val title get() = customName ?: meta?.title ?: name
}

/**
 * Настройки и подписки — только на телефоне, в личной папке приложения (резервное копирование выключено).
 * Ссылки подписок — личные ключи доступа: никуда, кроме сервера подписки, они не уходят.
 */
class Store(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("halo", Context.MODE_PRIVATE)

    var mode: Mode
        get() = Mode.of(p.getString("mode", null))
        set(v) = p.edit().putString("mode", v.id).apply()

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(p.getString("theme", null)!!) }.getOrDefault(ThemeMode.System)
        set(v) = p.edit().putString("theme", v.name).apply()

    var appsMode: AppsMode
        get() = runCatching { AppsMode.valueOf(p.getString("appsMode", null)!!) }.getOrDefault(AppsMode.Except)
        set(v) = p.edit().putString("appsMode", v.name).apply()

    /** Режим «Выбранные»: только эти приложения идут через VPN. */
    var vpnApps: Set<String>
        get() = p.getStringSet("vpnApps", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("vpnApps", v).apply()

    /** Режим «Кроме них»: эти приложения идут мимо VPN. */
    var bypassApps: Set<String>
        get() = p.getStringSet("bypassApps", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("bypassApps", v).apply()

    var favorites: Set<String>
        get() = p.getStringSet("favorites", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("favorites", v).apply()

    var adblock: Boolean
        get() = p.getBoolean("adblock", false)
        set(v) = p.edit().putBoolean("adblock", v).apply()

    /** Экономия заряда: реже проверки пинга и keep-alive, без обновления уведомления в фоне. */
    var batterySaver: Boolean
        get() = p.getBoolean("batterySaver", true)
        set(v) = p.edit().putBoolean("batterySaver", v).apply()

    /** Включать VPN при подключении к Wi-Fi. */
    var wifiAuto: Boolean
        get() = p.getBoolean("wifiAuto", false)
        set(v) = p.edit().putBoolean("wifiAuto", v).apply()

    var subscriptions: List<Subscription>
        get() {
            val a = JSONArray(p.getString("subs", "[]"))
            return (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Subscription(
                    o.getString("id"), o.getString("name"), o.getString("url"), o.optLong("addedAt"), SubMeta.fromJson(o.optJSONObject("meta")),
                    o.optString("customName").takeIf { it.isNotEmpty() },
                )
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach {
                a.put(
                    JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("addedAt", it.addedAt)
                        .put("meta", it.meta?.toJson()).put("customName", it.customName),
                )
            }
            p.edit().putString("subs", a.toString()).apply()
        }

    fun updateMeta(id: String, meta: SubMeta) {
        subscriptions = subscriptions.map { if (it.id == id) it.copy(meta = meta) else it }
    }

    fun rename(id: String, name: String?) {
        subscriptions = subscriptions.map { if (it.id == id) it.copy(customName = name?.trim()?.ifEmpty { null }) else it }
    }

    /** Включённые категории обхода (режим «Обход блокировок»). */
    var categories: Set<String>
        get() = p.getStringSet("categories", null)?.toSet() ?: ConfigBuilder.DEFAULT_CATEGORIES
        set(v) = p.edit().putStringSet("categories", v).apply()

    /** Свои сайты: домен → через VPN / напрямую / блок. */
    var sites: List<UserSite>
        get() {
            val a = JSONArray(p.getString("sites", "[]"))
            return (0 until a.length()).mapNotNull {
                val o = a.getJSONObject(it)
                val action = runCatching { SiteAction.valueOf(o.getString("action")) }.getOrNull() ?: return@mapNotNull null
                UserSite(o.getString("domain"), action)
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("domain", it.domain).put("action", it.action.name)) }
            p.edit().putString("sites", a.toString()).apply()
        }

    /** Приложения без сети, пока VPN включён (правила PROCESS-NAME → REJECT). */
    var blockedApps: Set<String>
        get() = p.getStringSet("blockedApps", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("blockedApps", v).apply()

    var doh: Doh
        get() = runCatching { Doh.valueOf(p.getString("doh", null)!!) }.getOrDefault(Doh.Cloudflare)
        set(v) = p.edit().putString("doh", v.name).apply()

    /** Подключаться сразу при открытии приложения. */
    var autoConnect: Boolean
        get() = p.getBoolean("autoConnect", false)
        set(v) = p.edit().putBoolean("autoConnect", v).apply()

    /** Обновлять подписки в фоне раз в N часов (0 — только вручную). */
    var subUpdateHours: Int
        get() = p.getInt("subUpdateHours", 12)
        set(v) = p.edit().putInt("subUpdateHours", v).apply()

    /** Обновлять списки блокировок в фоне (по Wi-Fi). */
    var geoAutoUpdate: Boolean
        get() = p.getBoolean("geoAutoUpdate", true)
        set(v) = p.edit().putBoolean("geoAutoUpdate", v).apply()

    /** Когда списки блокировок обновились из сети (0 — работают базы из APK). */
    var geoUpdatedAt: Long
        get() = p.getLong("geoUpdatedAt", 0)
        set(v) = p.edit().putLong("geoUpdatedAt", v).apply()

    var activeSubId: String?
        get() = p.getString("activeSub", null)
        set(v) = p.edit().putString("activeSub", v).apply()

    val activeSub: Subscription?
        get() = subscriptions.let { list -> list.firstOrNull { it.id == activeSubId } ?: list.firstOrNull() }

    /** «Все VPN в одном» — в самом низу списка подписок: серверы всех подписок сразу. */
    var allInOne: Boolean
        get() = p.getBoolean("allInOne", false)
        set(v) = p.edit().putBoolean("allInOne", v).apply()

    /** Выбрана подписка «Все VPN в одном» (и подписок больше одной — иначе это просто та одна). */
    val isAll: Boolean
        get() = allInOne && activeSubId == ALL_ID && subscriptions.size > 1

    /** Подписки, чьи серверы сейчас в ядре. */
    val activeSubs: List<Subscription>
        get() = if (isAll) subscriptions else listOfNotNull(activeSub)

    /** Защита от утечек: DNS через VPN (кроме российских сайтов), WebRTC/STUN — через VPN. */
    var leakProtection: Boolean
        get() = p.getBoolean("leakProtection", true)
        set(v) = p.edit().putBoolean("leakProtection", v).apply()

    /** Автопереключение: раз в 30 с проверять текущий сервер и уходить с него, если он не отвечает. */
    var failover: Boolean
        get() = p.getBoolean("failover", true)
        set(v) = p.edit().putBoolean("failover", v).apply()

    /** Как часто сторож проверяет текущий сервер: 10, 30 или 60 с (экономия с выключенным экраном — не чаще 60 с). */
    var checkSeconds: Int
        get() = p.getInt("checkSeconds", 30)
        set(v) = p.edit().putInt("checkSeconds", v).apply()

    companion object {
        const val ALL_ID = "__all__"
    }

    /** Пароль API ядра — случайный на каждом телефоне. */
    val secret: String
        get() = p.getString("secret", null) ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { p.edit().putString("secret", it).apply() }

    /** Пользователь хотел, чтобы VPN был включён. */
    var wantOn: Boolean
        get() = p.getBoolean("wantOn", false)
        set(v) = p.edit().putBoolean("wantOn", v).apply()

    /** Kill Switch Android («Блокировать соединения без VPN») — узнаём из службы VPN. */
    var lockdown: Boolean
        get() = p.getBoolean("lockdown", false)
        set(v) = p.edit().putBoolean("lockdown", v).apply()

    var geoVersion: Int
        get() = p.getInt("geoVersion", 0)
        set(v) = p.edit().putInt("geoVersion", v).apply()
}
