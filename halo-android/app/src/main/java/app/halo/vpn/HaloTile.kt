package app.halo.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.halo.vpn.core.Engine
import app.halo.vpn.core.VpnState

/** Плитка «Halo VPN» в шторке быстрых настроек. */
class HaloTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val s = Engine.state.value
        tile.state = if (s is VpnState.On || s is VpnState.Connecting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when (s) {
            is VpnState.On -> Engine.currentServer?.let { app.halo.vpn.core.Countries.clean(it) } ?: "Подключено"
            VpnState.Connecting -> "Подключение…"
            else -> "Авто · лучший"
        }
        tile.updateTile()
    }

    override fun onClick() {
        val s = Engine.state.value
        if (s is VpnState.On || s is VpnState.Connecting) {
            HaloVpnService.start(this, HaloVpnService.ACTION_DISCONNECT)
        } else if (VpnService.prepare(this) != null) {
            // Первое подключение: разрешение VPN спрашивает только экран приложения.
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_CONNECT, true)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(i)
            }
        } else {
            // Плитка подключает к «Авто · лучший» — самый быстрый из рабочих серверов.
            HaloVpnService.start(this, HaloVpnService.ACTION_CONNECT_AUTO)
        }
        onStartListening()
    }
}
