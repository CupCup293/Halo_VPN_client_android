package app.halo.vpn

import android.app.Application
import app.halo.vpn.core.Core
import app.halo.vpn.core.HaloLog
import app.halo.vpn.core.Updates

class HaloApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HaloLog.init(this)
        Core.init(this)
        Updates.schedule(this)
    }
}
