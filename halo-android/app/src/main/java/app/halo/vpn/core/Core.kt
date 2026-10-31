package app.halo.vpn.core

import android.content.Context
import android.net.ConnectivityManager
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** Ядро mihomo из libhalo.so (собирается из папки core/ скриптом build.ps1). */
object Core {
    @Volatile
    private var context: Context? = null
    private val packages = ConcurrentHashMap<Int, String>()

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    /** false — библиотеки нет в APK: интерфейс работает, подключиться нельзя. */
    val available: Boolean = try {
        System.loadLibrary("halo")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /** Запускает ядро или применяет новый конфиг. Возвращает текст ошибки или пустую строку. */
    @JvmStatic external fun nativeApply(home: String, config: String): String

    @JvmStatic external fun nativeStop()

    /** Последние строки журнала ядра. */
    @JvmStatic external fun nativeLogs(): String

    /** Подробный журнал: каждое соединение ядра. */
    @JvmStatic external fun nativeSetVerbose(on: Boolean)

    /** Строка журнала ядра (вызывает helpers.c): 1 — подробно, 2 — инфо, 3 — предупреждение, 4 — ошибка. */
    @JvmStatic
    fun onLog(level: Int, msg: ByteArray) {
        val text = String(msg, Charsets.UTF_8)
        // Загрузка каждой гео-категории при старте — шум, по десятку строк на запуск.
        if (level <= 2 && (text.contains("GeoSite rule") || text.contains("GeoIP rule") || text.startsWith("Geo"))) return
        HaloLog.core(
            when (level) {
                1 -> HaloLog.Level.Debug
                3 -> HaloLog.Level.Warn
                4 -> HaloLog.Level.Error
                else -> HaloLog.Level.Info
            },
            text,
        )
    }

    /**
     * Вызывает ядро (из helpers.c): чьё это соединение. Android отвечает на это только активному VPN-приложению.
     * Адреса — числовые, поэтому InetAddress.getByName не ходит в DNS.
     */
    @JvmStatic
    fun packageOf(proto: Int, src: String, srcPort: Int, dst: String, dstPort: Int): String? {
        val ctx = context ?: return null
        return runCatching {
            val uid = ctx.getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                proto,
                InetSocketAddress(InetAddress.getByName(src), srcPort),
                InetSocketAddress(InetAddress.getByName(dst), dstPort),
            )
            if (uid < 0) return null
            packages.getOrPut(uid) { ctx.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid" }
        }.getOrNull()
    }
}
