package network.geodema.misetanibox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import mobilecore.Mobilecore
import mobilecore.SocketProtector
import java.io.File

class MihomoVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    // Сырой дескриптор TUN, пока ИМ ВЛАДЕЕМ МЫ. После успешного старта владение переходит
    // ядру (sing-tun закрывает его сам при Stop), и здесь снова -1 — чтобы не закрыть дважды.
    @Volatile private var ownedTunFd = -1
    // Запуск/остановка ядра блокирующие (парсинг конфига + загрузка подписки + shutdown),
    // на главном потоке они вешают интерфейс — тогда кнопка «отключить» не реагирует.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    @Volatile private var running = false

    // --- Отключение по блокировке экрана (аналог функции INCY) ---
    private var screenReceiver: BroadcastReceiver? = null
    @Volatile private var pausedByLock = false
    private data class LaunchParams(
        val subUrl: String,
        val hwid: String,
        val userAgent: String,
        val splitMode: String,
        val splitApps: Array<String>,
        val rules: Array<String>,
        val chains: List<Pair<String, String>>,
        val serviceGroups: Array<String>,
    )
    @Volatile private var lastParams: LaunchParams? = null

    companion object {
        const val ACTION_START = "network.geodema.misetanibox.START"
        const val ACTION_STOP = "network.geodema.misetanibox.STOP"
        const val EXTRA_SUB_URL = "sub_url"
        const val EXTRA_HWID = "hwid"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_SPLIT_MODE = "split_mode"
        const val EXTRA_SPLIT_APPS = "split_apps"
        const val EXTRA_RULES = "rules"
        const val EXTRA_CHAINS = "chains"
        const val EXTRA_SERVICE_GROUPS = "service_groups"
        const val CHAIN_PREFIX = "🔗 "
        const val CHANNEL_ID = "misetanibox_vpn"
        const val NOTIF_ID = 7

        @Volatile var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        registerScreenReceiver()
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenLocked()
                    Intent.ACTION_USER_PRESENT -> onScreenUnlocked()
                }
            }
        }
        try {
            registerReceiver(r, filter)
            screenReceiver = r
        } catch (_: Exception) {}
    }

    private fun unregisterScreenReceiver() {
        val r = screenReceiver ?: return
        screenReceiver = null
        try { unregisterReceiver(r) } catch (_: Exception) {}
    }

    private fun onScreenLocked() {
        if (!running) return
        if (!VpnPrefs.isLockDisconnect(this)) return
        pausedByLock = true
        worker.execute { pauseTunnel() }
    }

    private fun onScreenUnlocked() {
        if (!pausedByLock) return
        pausedByLock = false
        if (!VpnPrefs.isLockReconnect(this)) return
        worker.execute { resumeTunnel() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                worker.execute { stopTunnel() }
            }
            else -> {
                val subUrl = intent?.getStringExtra(EXTRA_SUB_URL) ?: ""
                val hwid = intent?.getStringExtra(EXTRA_HWID) ?: ""
                val userAgent = intent?.getStringExtra(EXTRA_USER_AGENT) ?: ""
                val splitMode = intent?.getStringExtra(EXTRA_SPLIT_MODE) ?: "off"
                val splitApps = intent?.getStringArrayExtra(EXTRA_SPLIT_APPS) ?: arrayOf()
                val rules = intent?.getStringArrayExtra(EXTRA_RULES) ?: arrayOf()
                val chains = parseChains(intent?.getStringExtra(EXTRA_CHAINS))
                val serviceGroups = intent?.getStringArrayExtra(EXTRA_SERVICE_GROUPS) ?: arrayOf()
                if (subUrl.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNotif()
                worker.execute { startTunnel(subUrl, hwid, userAgent, splitMode, splitApps, rules, chains, serviceGroups) }
            }
        }
        return START_NOT_STICKY
    }

    private fun parseChains(json: String?): List<Pair<String, String>> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            val out = ArrayList<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val entry = o.optString("entry").trim()
                if (name.isNotEmpty() && entry.isNotEmpty()) out.add(name to entry)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun startTunnel(
        subUrl: String,
        hwid: String,
        userAgent: String,
        splitMode: String,
        splitApps: Array<String>,
        rules: Array<String>,
        chains: List<Pair<String, String>>,
        serviceGroups: Array<String>,
    ) {
        if (running) return
        lastParams = LaunchParams(subUrl, hwid, userAgent, splitMode, splitApps, rules, chains, serviceGroups)
        try {
            var config: String? = null
            var usedCache = false
            val fetched = Subscription.fetch(subUrl, hwid, userAgent)
            if (fetched.body.isBlank()) {
                val reason = fetched.error ?: "пустой ответ"
                config = readCachedConfig()
                if (config == null) {
                    broadcast("error", "не удалось загрузить конфиг подписки ($reason) — проверьте ссылку и интернет")
                    return
                }
                usedCache = true
                android.util.Log.i("Misetanibox", "подписка недоступна ($reason) — использую последний закэшированный конфиг")
            } else {
                config = try {
                    Subscription.convert(fetched.body).config
                } catch (e: Exception) {
                    val cached = readCachedConfig()
                    if (cached == null) {
                        broadcast("error", "конфиг подписки не разобран: " + (e.message ?: "неизвестный формат"))
                        return
                    }
                    usedCache = true
                    android.util.Log.i("Misetanibox", "новый конфиг не разобрался (${e.message}) – использую последний закэшированный")
                    cached
                }
            }
            if (usedCache) {
                broadcast("cached", "подписка недоступна — работаю на последнем сохранённом конфиге")
            } else {
                writeCachedConfig(config!!)
            }
            val finalConfig = config!!
            val builder = Builder()
                .setSession("Misetanibox")
                .setMtu(9000)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("172.19.0.2")
                .setBlocking(false)
            applySplitTunnel(builder, splitMode, splitApps)

            val pfd = builder.establish() ?: run {
                broadcast("error", "establish() вернул null — нет разрешения VPN или активен другой VPN")
                return
            }
            tunFd = pfd
            val fd = pfd.detachFd()
            ownedTunFd = fd

            val homeDir = File(filesDir, "clash").apply { mkdirs() }.absolutePath

            Mobilecore.setProtect(object : SocketProtector {
                override fun protect(fd: Long): Boolean = protect(fd.toInt())
            })

            val err = Mobilecore.start(homeDir, finalConfig, fd.toLong())
            if (err.isNotEmpty()) {
                broadcast("error", err)
                stopTunnel()
                return
            }
            ownedTunFd = -1
            watchNetworkChanges()
            running = true
            isRunning = true
            updateNotif(connected = true)
            broadcast("connected", "")

            worker.execute {
                try {
                    Thread.sleep(6000)
                    if (running) broadcast("diag", Mobilecore.diagnose())
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            broadcast("error", e.message ?: "неизвестная ошибка запуска")
            stopTunnel()
        }
    }

    private fun applySplitTunnel(builder: Builder, mode: String, apps: Array<String>) {
        when (mode) {
            "only" -> {
                var added = 0
                for (p in apps) {
                    try { builder.addAllowedApplication(p); added++ } catch (_: Exception) {}
                }
                if (added == 0) {
                    try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                }
            }
            "bypass" -> {
                for (p in apps) {
                    try { builder.addDisallowedApplication(p) } catch (_: Exception) {}
                }
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
            else -> {
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
        }
    }

    private fun cacheFile(): File = File(filesDir, "last_config_cache.yaml")

    private fun readCachedConfig(): String? {
        val f = cacheFile()
        if (!f.exists()) return null
        return try {
            val text = f.readText()
            if (text.isBlank()) null else text
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCachedConfig(config: String) {
        try { cacheFile().writeText(config) } catch (_: Exception) {}
    }

    private fun stopTunnel() {
        unwatchNetworkChanges()
        try { Mobilecore.stop() } catch (_: Exception) {}
        try { Mobilecore.setProtect(null) } catch (_: Exception) {}
        closeOwnedTunFd()
        tunFd = null
        running = false
        isRunning = false
        pausedByLock = false
        lastParams = null
        broadcast("disconnected", "")
        unregisterScreenReceiver()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pauseTunnel() {
        if (!running) return
        unwatchNetworkChanges()
        try { Mobilecore.stop() } catch (_: Exception) {}
        try { Mobilecore.setProtect(null) } catch (_: Exception) {}
        closeOwnedTunFd()
        tunFd = null
        running = false
        isRunning = false
        broadcast("disconnected", "экран заблокирован")
        updateNotif(connected = false)
    }

    private fun resumeTunnel() {
        if (running) return
        val p = lastParams ?: return
        startTunnel(p.subUrl, p.hwid, p.userAgent, p.splitMode, p.splitApps, p.rules, p.chains, p.serviceGroups)
    }

    private fun watchNetworkChanges() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                onNetworkSwitched(network)
            }
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities,
            ) {
                if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    onNetworkSwitched(network)
                }
            }
            override fun onLost(network: android.net.Network) {}
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {}
    }

    private fun unwatchNetworkChanges() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    @Volatile private var lastNetSwitch = 0L
    private fun onNetworkSwitched(network: android.net.Network) {
        if (!running) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNetSwitch < 1500) return
        lastNetSwitch = now

        try { setUnderlyingNetworks(arrayOf(network)) } catch (_: Exception) {}

        worker.execute {
            try {
                val u = java.net.URL("http://127.0.0.1:9090/connections")
                val c = u.openConnection() as java.net.HttpURLConnection
                c.requestMethod = "DELETE"
                c.connectTimeout = 2000
                c.readTimeout = 3000
                c.responseCode
                c.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun closeOwnedTunFd() {
        val raw = ownedTunFd
        ownedTunFd = -1
        if (raw >= 0) {
            try { ParcelFileDescriptor.adoptFd(raw).close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        unregisterScreenReceiver()
        stopTunnel()
        worker.shutdown()
        super.onDestroy()
    }

    override fun onRevoke() {
        worker.execute { stopTunnel() }
        super.onRevoke()
    }

    private fun yamlStr(v: String): String =
        "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun buildConfig(
        subUrl: String,
        hwid: String,
        userAgent: String,
        rules: Array<String>,
        chains: List<Pair<String, String>>,
        serviceGroups: Array<String>,
    ): String {
        val header = buildString {
            append("    header:\n")
            append("      User-Agent: [${yamlStr(userAgent)}]")
            if (hwid.isNotEmpty()) {
                append("\n      x-hwid: [\"$hwid\"]")
                append("\n      x-device-os: [\"Android\"]")
                append("\n      x-ver-os: [\"${Build.VERSION.RELEASE}\"]")
                append("\n      x-device-model: [\"${Build.MODEL}\"]")
            }
        }

        val chainProviders = StringBuilder()
        val chainGroups = StringBuilder()
        val chainNames = mutableListOf<String>()
        chains.forEachIndexed { i, (name, entry) ->
            val groupName = "$CHAIN_PREFIX$name"
            chainNames += groupName
            chainProviders.append(
                """
                |  chain$i:
                |    type: http
                |    url: ${yamlStr(subUrl)}
                |    interval: 21600
                |    path: ./providers/chain$i.yaml
                |    override:
                |      dialer-proxy: ${yamlStr(entry)}
                |      additional-prefix: ${yamlStr("$name · ")}
                |$header
                """.trimMargin()
            ).append("\n")
            chainGroups.append(
                """
                |  - name: ${yamlStr(groupName)}
                |    type: select
                |    use:
                |      - chain$i
                """.trimMargin()
            ).append("\n")
        }

        val proxyGroupChains = if (chainNames.isEmpty()) "" else
            "\n    proxies:\n" + chainNames.joinToString("\n") { "      - ${yamlStr(it)}" }

        val serviceGroupsBlock = StringBuilder()
        for (g in serviceGroups) {
            if (g.isBlank()) continue
            serviceGroupsBlock.append(
                """
                |  - name: ${yamlStr(g)}
                |    type: select
                |    use:
                |      - main
                """.trimMargin()
            ).append("\n")
        }

        val rulesBlock = buildString {
            for (r in rules) {
                val line = r.trim()
                if (line.isNotEmpty()) append("  - ").append(yamlStr(line)).append("\n")
            }
            append("  - MATCH,PROXY")
        }

        return """
            |mixed-port: 7890
            |mode: rule
            |log-level: warning
            |ipv6: false
            |unified-delay: true
            |find-process-mode: "off"
            |profile:
            |  store-selected: true
            |external-controller: 127.0.0.1:9090
            |dns:
            |  enable: true
            |  listen: 0.0.0.0:1053
            |  ipv6: false
            |  enhanced-mode: fake-ip
            |  fake-ip-range: 198.18.0.1/16
            |  fake-ip-filter:
            |    - "*.lan"
            |    - "*.local"
            |    - "localhost.ptlogin2.qq.com"
            |  default-nameserver:
            |    - 77.88.8.8
            |    - 223.5.5.5
            |  nameserver:
            |    - 77.88.8.8
            |    - 223.5.5.5
            |  proxy-server-nameserver:
            |    - 77.88.8.8
            |    - 223.5.5.5
            |proxy-providers:
            |  main:
            |    type: http
            |    url: ${yamlStr(subUrl)}
            |    interval: 3600
            |    path: ./providers/main.yaml
            |$header
            |$chainProviders
            |proxy-groups:
            |  - name: PROXY
            |    type: select$proxyGroupChains
            |    use:
            |      - main
            |$chainGroups
            |$serviceGroupsBlock
            |rules:
            |$rulesBlock
        """.trimMargin()
    }

    private fun buildNotif(connected: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Misetanibox")
            .setContentText(if (connected) "Туннель активен" else "На паузе — экран заблокирован")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNotif() {
        val notif = buildNotif(connected = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotif(connected: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(connected))
    }

    private fun broadcast(state: String, message: String) {
        val i = Intent("network.geodema.misetanibox.VPN_STATE")
        i.setPackage(packageName)
        i.putExtra("state", state)
        i.putExtra("message", message)
        sendBroadcast(i)
        try { VpnAppWidget.requestUpdate(this) } catch (_: Exception) {}
        try { VpnTileService.requestUpdate(this) } catch (_: Exception) {}
    }
}
