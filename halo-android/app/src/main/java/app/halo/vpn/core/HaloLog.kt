package app.halo.vpn.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Единый журнал Halo: события приложения и предупреждения/ошибки ядра.
 * Хранится в памяти (для экрана) и в файле filesDir/logs/halo.log (переживает перезапуск, до ~2 × 512 КБ).
 * Ссылки и длинные ключи вырезаются ещё при записи — журнал можно пересылать как есть.
 */
object HaloLog {
    enum class Level(val mark: Char) { Debug('D'), Info('I'), Warn('W'), Error('E') }
    enum class Source(val title: String) { App("Приложение"), Core("Ядро") }

    data class Entry(val time: Long, val level: Level, val source: Source, val tag: String, val text: String) {
        fun line(): String {
            if (time == 0L) return "$tag: $text" // строка из файла прошлого запуска — время уже внутри
            val t = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(Date(time))
            return "$t ${level.mark} ${if (source == Source.Core) "mihomo" else tag}: $text"
        }
    }

    private const val MAX = 800
    private const val FILE_LIMIT = 512 * 1024
    private val buffer = ArrayDeque<Entry>(MAX)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    @Volatile
    private var dir: File? = null
    private val recent = HashMap<String, Long>()

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        // Прошлый запуск — в память, чтобы было видно, что случилось до перезапуска.
        runCatching {
            val f = File(dir, "halo.log")
            if (f.exists()) synchronized(buffer) {
                f.readLines().takeLast(200).forEach { buffer.addLast(Entry(0, Level.Info, Source.App, "прошлый запуск", it)) }
            }
        }
        i("halo", "— запуск приложения —")
    }

    fun d(tag: String, msg: String) = add(Level.Debug, Source.App, tag, msg)
    fun i(tag: String, msg: String) = add(Level.Info, Source.App, tag, msg)
    fun w(tag: String, msg: String, e: Throwable? = null) = add(Level.Warn, Source.App, tag, msg + reason(e))
    fun e(tag: String, msg: String, e: Throwable? = null) = add(Level.Error, Source.App, tag, msg + reason(e))

    /** Строка из ядра (helpers.c → Core.onLog). */
    fun core(level: Level, msg: String) = add(level, Source.Core, "core", msg)

    /** Одно и то же предупреждение — не чаще раза в минуту (опрос ядра идёт каждую секунду). */
    fun wOnce(tag: String, msg: String, e: Throwable? = null) {
        val key = tag + msg + (e?.javaClass?.name ?: "")
        val now = System.currentTimeMillis()
        synchronized(recent) {
            if ((recent[key] ?: 0) > now - 60_000) return
            recent[key] = now
        }
        w(tag, msg, e)
    }

    private fun reason(e: Throwable?): String = if (e == null) "" else ": ${e.javaClass.simpleName}${e.message?.let { " — $it" } ?: ""}"

    /** Ссылки, base64 и длинные токены (ключи VLESS, пароли) — прочь из журнала. */
    fun mask(s: String): String = s
        .replace(Regex("""(?i)\b(?:https?|vless|vmess|trojan|ss|ssr|hy2|hysteria2?|tuic|wireguard|anytls|socks5?)://\S+"""), "<ссылка>")
        .replace(Regex("""[A-Za-z0-9+/=_-]{32,}"""), "<ключ>")
        .replace(Regex("""\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""", RegexOption.IGNORE_CASE), "<uuid>")

    private fun add(level: Level, source: Source, tag: String, msg: String) {
        val e = Entry(System.currentTimeMillis(), level, source, tag, mask(msg))
        val prio = when (level) {
            Level.Debug -> Log.DEBUG
            Level.Info -> Log.INFO
            Level.Warn -> Log.WARN
            Level.Error -> Log.ERROR
        }
        Log.println(prio, if (source == Source.Core) "HaloCore" else "Halo", "$tag: ${e.text}")
        synchronized(buffer) {
            if (buffer.size >= MAX) buffer.removeFirst()
            buffer.addLast(e)
            _entries.value = buffer.toList()
        }
        // В файл — всё, кроме подробностей (каждого соединения ядра), чтобы файл не разрастался.
        if (level != Level.Debug) writeFile(e.line())
    }

    @Synchronized
    private fun writeFile(line: String) {
        val d = dir ?: return
        runCatching {
            val f = File(d, "halo.log")
            if (f.length() > FILE_LIMIT) f.renameTo(File(d, "halo.1.log"))
            f.appendText(line + "\n")
        }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _entries.value = emptyList()
        }
        dir?.let { d -> runCatching { File(d, "halo.log").delete(); File(d, "halo.1.log").delete() } }
    }

    /** Весь журнал текстом: предыдущий файл + текущий (для «Поделиться»). */
    fun fileText(): String {
        val d = dir ?: return ""
        return listOf("halo.1.log", "halo.log").mapNotNull { n -> File(d, n).takeIf { it.exists() }?.readText() }.joinToString("")
    }
}
