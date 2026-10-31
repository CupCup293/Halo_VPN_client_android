package app.halo.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import app.halo.vpn.core.Engine
import app.halo.vpn.core.Store
import app.halo.vpn.core.VpnState

/**
 * «VPN в чужом Wi-Fi»: система будит приложение при подключении к Wi-Fi, и VPN включается.
 * Если Android не разрешает запуск из фона (Android 12+), показываем уведомление «Включить VPN».
 */
class WifiWatcher : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val store = Store(ctx)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (store.wifiAuto) register(ctx)
            return
        }
        if (!store.wifiAuto) return
        val s = Engine.state.value
        if (s is VpnState.On || s is VpnState.Connecting) return
        if (VpnService.prepare(ctx) != null) return // VPN ещё ни разу не разрешали
        try {
            HaloVpnService.start(ctx, HaloVpnService.ACTION_CONNECT)
        } catch (_: Exception) {
            HaloVpnService.ensureChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx, 3, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONNECT, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(ctx, HaloVpnService.CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Подключён Wi-Fi")
                .setContentText("Нажмите, чтобы включить Halo VPN")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(2, n)
        }
    }

    companion object {
        private fun pending(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, WifiWatcher::class.java).setAction("app.halo.vpn.WIFI"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        fun register(ctx: Context) {
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            runCatching { ctx.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(req, pending(ctx)) }
        }

        fun unregister(ctx: Context) {
            runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(pending(ctx)) }
        }
    }
}
