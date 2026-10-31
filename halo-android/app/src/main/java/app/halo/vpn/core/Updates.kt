package app.halo.vpn.core

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit

/** Фоновые обновления: подписки (раз в N часов) и списки блокировок (раз в 3 дня, только по Wi-Fi и не на низком заряде). */
object Updates {
    private const val SUBS = "subs"
    private const val GEO = "geo"

    /**
     * [changed] — пользователь поменял интервал: пересоздаём расписание. Иначе (запуск приложения) оставляем как есть:
     * раньше UPDATE с отсрочкой на N часов при каждом запуске сдвигал обновление — и оно могло не наступить никогда.
     */
    fun schedule(ctx: Context, changed: Boolean = false) {
        val store = Store(ctx)
        val wm = WorkManager.getInstance(ctx)
        val hours = store.subUpdateHours
        if (hours > 0) {
            val req = PeriodicWorkRequestBuilder<SubWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(SUBS, if (changed) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(SUBS)
        }
        if (store.geoAutoUpdate) {
            val req = PeriodicWorkRequestBuilder<GeoWorker>(3, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(GEO, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(GEO)
        }
    }

    /** «Обновить сейчас» из настроек — без ограничения по Wi-Fi. */
    fun updateGeoNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<GeoWorker>().build())
    }
}

class SubWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        HaloLog.i("подписка", "фоновое обновление подписок")
        var activeChanged = false
        var failed = 0
        val active = store.activeSubs.map { it.id }.toSet()
        for (s in store.subscriptions) {
            runCatching { Subscriptions.fetch(applicationContext, s) }
                .onSuccess {
                    store.updateMeta(s.id, it)
                    if (s.id in active) activeChanged = true
                }
                .onFailure {
                    failed++
                    HaloLog.w("подписка", "фоновое обновление «${s.title}» не удалось", it)
                }
        }
        HaloLog.i("подписка", "фоновое обновление: готово, ошибок $failed из ${store.subscriptions.size}")
        // Ядро работает (VPN включён) — подхватываем новый список серверов без разрыва туннеля.
        if (activeChanged && Engine.coreRunning) Engine.reapply(applicationContext)
        // Все упали (нет сети, сервис недоступен) — попробуем ещё раз позже, не дожидаясь следующего периода.
        return if (failed > 0 && failed == store.subscriptions.size && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

class GeoWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        return try {
            GeoUpdater.update(applicationContext)
            HaloLog.i("гео", "списки блокировок обновлены из сети")
            store.geoUpdatedAt = System.currentTimeMillis()
            if (Engine.coreRunning) Engine.reapply(applicationContext)
            Result.success()
        } catch (e: Exception) {
            HaloLog.w("гео", "списки блокировок не обновились", e)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

/**
 * Свежие списки блокировок runetfreedom, урезанные на лету до нужных категорий (как tools/trim-geo.cjs):
 * качаем полный файл потоком, в память и на диск попадают только нужные записи — около 6 МБ вместо 90.
 */
object GeoUpdater {
    private const val BASE = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/"

    // Те же категории, что в tools/trim-geo.cjs. cn — ядро проверяет по ней, что база целая.
    private val KEEP = mapOf(
        "GeoIP.dat" to ("geoip.dat" to setOf("ru-blocked", "telegram", "ru", "cn")),
        "GeoSite.dat" to (
            "geosite.dat" to setOf(
                "ru-blocked", "ru-available-only-inside", "category-ru", "category-ads-all", "cn",
                "youtube", "instagram", "facebook", "twitter", "category-ai-!cn", "discord", "telegram", "spotify", "category-public-tracker",
                "netflix", "disney", "notion", "figma", "canva", "jetbrains", "docker", "intel", "nvidia", "autodesk", "soundcloud",
                "patreon", "medium", "tiktok",
            )
            ),
    )
    private val REQUIRED = mapOf("GeoIP.dat" to setOf("ru-blocked", "cn"), "GeoSite.dat" to setOf("ru-blocked", "ru-available-only-inside", "cn"))

    fun update(ctx: Context) {
        val home = Engine.home(ctx)
        val ready = mutableListOf<Pair<File, File>>()
        for ((name, src) in KEEP) {
            val tmp = File(home, "$name.new")
            val found = open(src.first).use { trim(it, tmp, src.second) }
            val missing = REQUIRED.getValue(name) - found
            if (missing.isNotEmpty()) {
                tmp.delete()
                throw IllegalStateException("в $name нет категорий: ${missing.joinToString()}")
            }
            ready += tmp to File(home, name)
        }
        // Заменяем только когда обе базы скачались целиком.
        for ((tmp, dst) in ready) if (!tmp.renameTo(dst)) throw IllegalStateException("не удалось заменить ${dst.name}")
    }

    /** Напрямую; не вышло — через ядро (если оно запущено). */
    private fun open(file: String): InputStream {
        fun via(proxy: Proxy): InputStream {
            val c = URL(BASE + file).openConnection(proxy) as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            if (c.responseCode >= 400) throw IllegalStateException("GitHub ответил ${c.responseCode}")
            return BufferedInputStream(c.inputStream, 1 shl 16)
        }
        return try {
            via(Proxy.NO_PROXY)
        } catch (e: Exception) {
            if (!Engine.coreRunning) throw e
            via(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ConfigBuilder.MIXED_PORT)))
        }
    }

    /** protobuf { repeated Entry entry = 1 { string code = 1; … } }: копируем целиком записи с нужным кодом. */
    private fun trim(input: InputStream, out: File, keep: Set<String>): Set<String> {
        val found = mutableSetOf<String>()
        out.outputStream().buffered(1 shl 16).use { w ->
            while (true) {
                val tag = varint(input) ?: break
                val len = varint(input) ?: break
                val body = ByteArray(len.toInt())
                readFully(input, body)
                if (tag.toInt() shr 3 == 1) {
                    val code = codeOf(body)
                    if (code != null && code in keep) {
                        w.write(encodeVarint(tag))
                        w.write(encodeVarint(len))
                        w.write(body)
                        found += code
                    }
                }
            }
        }
        return found
    }

    private fun codeOf(entry: ByteArray): String? {
        var i = 0
        fun next(): Long {
            var x = 0L
            var s = 0
            while (true) {
                val b = entry[i++].toInt() and 0xff
                x = x or ((b and 0x7f).toLong() shl s)
                if (b and 0x80 == 0) return x
                s += 7
            }
        }
        return runCatching {
            if (next().toInt() shr 3 != 1) return null
            val l = next().toInt()
            String(entry, i, l, Charsets.UTF_8).lowercase()
        }.getOrNull()
    }

    private fun varint(input: InputStream): Long? {
        var x = 0L
        var s = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (s == 0) null else throw IllegalStateException("файл оборвался")
            x = x or ((b and 0x7f).toLong() shl s)
            if (b and 0x80 == 0) return x
            s += 7
        }
    }

    private fun encodeVarint(v: Long): ByteArray {
        val o = ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x7f.inv().toLong() == 0L) {
                o.write(x.toInt())
                return o.toByteArray()
            }
            o.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IllegalStateException("файл оборвался")
            off += n
        }
    }
}
