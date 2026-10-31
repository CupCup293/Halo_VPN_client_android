package app.halo.vpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import app.halo.vpn.ui.DarkHalo
import app.halo.vpn.ui.HaloRoot
import app.halo.vpn.ui.HaloTheme
import app.halo.vpn.ui.LightHalo
import app.halo.vpn.ui.MainViewModel
import app.halo.vpn.ui.isDark
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val askVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) vm.connect() else vm.message.value = "Без разрешения Android не даст включить VPN"
    }
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** QR-код подписки: ссылка https://… или halo://install-config?url=… */
    private val scan = registerForActivityResult(ScanContract()) { r ->
        val text = r.contents?.trim() ?: return@registerForActivityResult
        val uri = runCatching { android.net.Uri.parse(text) }.getOrNull()
        when {
            uri?.scheme == "halo" -> uri.getQueryParameter("url")?.let { vm.addSubscription(it) }
            text.startsWith("http") -> vm.addSubscription(text)
            else -> vm.message.value = "В QR-коде нет ссылки на подписку"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handle(intent)
        // «Подключаться при запуске» — только при открытии приложения, не при повороте экрана.
        if (savedInstanceState == null && app.halo.vpn.core.Store(this).autoConnect && vm.vpn.value is app.halo.vpn.core.VpnState.Off) connect()
        setContent {
            val theme by vm.theme.collectAsState()
            val dark = isDark(theme)
            LaunchedEffect(dark) {
                val bar = (if (dark) DarkHalo.nav else LightHalo.nav).toArgb()
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(bar) else SystemBarStyle.light(bar, bar),
                )
            }
            HaloTheme(theme) { HaloRoot(vm, onConnect = ::connect, onScanQr = ::scanQr) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** halo://install-config?url=… — добавить подписку; из плитки или уведомления — сразу подключиться. */
    private fun handle(intent: Intent?) {
        val data = intent?.data
        if (data?.scheme == "halo") data.getQueryParameter("url")?.let { vm.addSubscription(it) }
        if (intent?.getBooleanExtra(EXTRA_CONNECT, false) == true) connect()
    }

    private fun connect() {
        val ask = VpnService.prepare(this)
        if (ask != null) askVpn.launch(ask) else vm.connect()
    }

    private fun scanQr() {
        scan.launch(ScanOptions().setPrompt("Наведите камеру на QR-код подписки").setBeepEnabled(false).setOrientationLocked(false))
    }

    override fun onStart() {
        super.onStart()
        visible = true
        vm.onVisible()
    }

    override fun onStop() {
        visible = false
        vm.onHidden()
        super.onStop()
    }

    companion object {
        const val EXTRA_CONNECT = "connect"

        @Volatile
        var visible = false
    }
}
