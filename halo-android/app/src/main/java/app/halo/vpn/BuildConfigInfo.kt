package app.halo.vpn

import android.content.Context

object BuildConfigInfo {
    fun version(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
}
