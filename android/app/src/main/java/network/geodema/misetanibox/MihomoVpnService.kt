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

    // Раньше это поле не было volatile, хотя к нему обращались и воркер, и (в onDestroy)
    // вызывающий поток напрямую — классическая гонка видимости между потоками.
    @Volatile private var tunFd: ParcelFileDescriptor? = null
    // Сырой дескриптор TUN, пока ИМ ВЛАДЕЕМ МЫ. После успешного старта владение переходит
    // ядру (sing-tun закрывает его сам при Stop), и здесь снова -1 — чтобы не закрыть дважды.
    @Volatile private var ownedTunFd = -1
    // Запуск/остановка ядра блокирующие (парсинг конфига + загрузка подписки + shutdown),
    // на главном потоке они вешают интерфейс — тогда кнопка «отключить» не реагирует.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    @Volatile private var running = false
    // Защита stopTunnel() от повторного/параллельного входа — раньше двойной вызов
    // (например, из ACTION_STOP и следом из onDestroy) слал два broadcast("disconnected")
    // и дважды дёргал stopForeground/stopSelf.
    private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)

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
        const val ACTION_TEST_UA = "network.geodema.misetanibox.TEST_UA"
        const val EXTRA_TEST_URL = "test_url"
        const val EXTRA_TEST_HWID = "test_hwid"
        const val EXTRA_TEST_UAS = "test_uas"
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
            ACTION_TEST_UA -> {
                val url = intent.getStringExtra(EXTRA_TEST_URL) ?: ""
                val hwid = intent.getStringExtra(EXTRA_TEST_HWID) ?: ""
                val uas = intent.getStringArrayExtra(EXTRA_TEST_UAS) ?: arrayOf()
                // Отдельный поток, не worker: это короткая фоновая проверка при
                // добавлении подписки, ей не место в очереди реальных start/stop.
                Thread { testUserAgents(url, hwid, uas) }.start()
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
            applyPreferredServer()
            scheduleDiagBroadcast()
        } catch (e: Exception) {
            broadcast("error", e.message ?: "неизвестная ошибка запуска")
            stopTunnel()
        }
    }

    // ---------- авто-подбор User-Agent при добавлении подписки ----------
    // Проверка «список серверов не пустой» недостаточна: панель может вернуть непустой
    // список под одним UA, а сами узлы внутри окажутся нерабочими (сломанные параметры
    // протокола под этим форматом ответа). Поэтому проверяем по-настоящему: реально
    // поднимаем короткий тестовый туннель под каждым UA и смотрим, отвечает ли хоть
    // один узел через сам прокси-протокол (не просто открывается TCP до адреса).
    @Volatile private var testingUa = false

    private fun testUaBroadcast(kind: String, ua: String, extra: String = "") {
        val i = Intent("network.geodema.misetanibox.VPN_STATE")
        i.setPackage(packageName)
        i.putExtra("state", "uaTest")
        i.putExtra("message", "$kind|$ua|$extra")
        sendBroadcast(i)
    }

    private fun testUserAgents(url: String, hwid: String, uas: Array<String>) {
        // Нельзя поднимать тестовый туннель поверх уже активного — Android разрешает
        // только один установленный VpnService.Builder за раз, второй establish()
        // тихо оборвал бы реальное подключение пользователя.
        if (running) {
            testUaBroadcast("done", "", "already_connected")
            return
        }
        if (testingUa) return // проверка уже идёт — повторный запуск игнорируем
        testingUa = true
        var winner = ""
        try {
            for (ua in uas) {
                if (running) break // пользователь тем временем подключился вручную — прерываем тест
                testUaBroadcast("trying", ua)
                val fetched = Subscription.fetch(url, hwid, ua)
                if (fetched.body.isBlank()) {
                    testUaBroadcast("fail", ua, fetched.error ?: "пустой ответ")
                    continue
                }
                val config = try {
                    Subscription.convert(fetched.body).config
                } catch (e: Exception) {
                    testUaBroadcast("fail", ua, e.message ?: "формат не распознан")
                    continue
                }
                if (testOneConfig(config)) {
                    winner = ua
                    testUaBroadcast("ok", ua)
                    break
                } else {
                    testUaBroadcast("fail", ua, "сервера не отвечают")
                }
            }
        } finally {
            testingUa = false
            testUaBroadcast("done", winner)
        }
    }

    /**
     * Поднимает временный туннель с готовым конфигом, проверяет первый узел основной
     * группы через реальный прокси-протокол (delay-запрос ядра, а не просто открытие
     * TCP-порта), гасит туннель. Использует отдельную домашнюю директорию ядра
     * ("clash_test"), чтобы не задеть кэш/состояние основного подключения, и локальные
     * переменные fd/pfd — не трогает общие tunFd/ownedTunFd, чтобы не мешать реальному
     * подключению, если оно вдруг начнётся параллельно.
     */
    private fun testOneConfig(config: String): Boolean {
        var pfd: ParcelFileDescriptor? = null
        var fd = -1
        return try {
            val builder = Builder()
                .setSession("MisetaniboxTest")
                .setMtu(9000)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("172.19.0.2")
                .setBlocking(false)
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            pfd = builder.establish() ?: return false
            fd = pfd.detachFd()
            val homeDir = File(filesDir, "clash_test").apply { mkdirs() }.absolutePath
            Mobilecore.setProtect(object : SocketProtector {
                override fun protect(fd: Long): Boolean = protect(fd.toInt())
            })
            val err = Mobilecore.start(homeDir, config, fd.toLong())
            if (err.isNotEmpty()) return false

            var ok = false
            for (i in 0 until 8) {
                try {
                    val u = java.net.URL("http://127.0.0.1:9090/proxies")
                    val c = u.openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 1000; c.readTimeout = 1500
                    if (c.responseCode in 200..299) {
                        val json = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                        c.disconnect()
                        val group = json.optJSONObject("proxies")?.optJSONObject("PROXY")
                        val all = group?.optJSONArray("all")
                        val node = if (all != null && all.length() > 0) all.optString(0) else null
                        if (node != null) {
                            val testUrl = java.net.URLEncoder.encode("http://www.gstatic.com/generate_204", "UTF-8")
                            val du = java.net.URL(
                                "http://127.0.0.1:9090/proxies/" +
                                    java.net.URLEncoder.encode(node, "UTF-8") +
                                    "/delay?timeout=4000&url=$testUrl"
                            )
                            val dc = du.openConnection() as java.net.HttpURLConnection
                            dc.connectTimeout = 1000; dc.readTimeout = 5000
                            if (dc.responseCode in 200..299) {
                                val dj = org.json.JSONObject(dc.inputStream.bufferedReader().use { it.readText() })
                                ok = dj.optInt("delay", -1) > 0
                            }
                            dc.disconnect()
                        }
                        break
                    }
                    c.disconnect()
                } catch (_: Exception) {}
                Thread.sleep(700)
            }
            try { Mobilecore.stop() } catch (_: Exception) {}
            try { Mobilecore.setProtect(null) } catch (_: Exception) {}
            ok
        } catch (_: Exception) {
            false
        } finally {
            try { if (fd >= 0) ParcelFileDescriptor.adoptFd(fd).close() } catch (_: Exception) {}
        }
    }

    // Раньше диагностика запускалась через worker.execute{...} — тот же однопоточный
    // executor, на котором стоят в очереди start/stop/pause/resume. Пока эта задача
    // 6 секунд «спала», любая команда подключения/отключения, случившаяся в этом окне,
    // ждала своей очереди — то самое ощущение «стало долго подключаться/отключаться».
    // Отдельный поток не конкурирует с очередью воркера ни за что.
    private fun scheduleDiagBroadcast() {
        Thread {
            try {
                Thread.sleep(6000)
                if (running) broadcast("diag", Mobilecore.diagnose())
            } catch (_: Exception) {}
        }.start()
    }

    // Раньше «сервер по умолчанию» переприменялся ТОЛЬКО из открытого WebView — при
    // автовключении по приложению, плитке или автозапуске после перезагрузки интерфейс
    // не открыт, и ядро молча оставалось на своём дефолтном узле группы PROXY вместо
    // реально выбранного пользователем. Тоже на отдельном потоке — не мешает очереди
    // воркера и не блокирует последующие команды.
    private fun applyPreferredServer() {
        Thread {
            try {
                var proxiesJson: org.json.JSONObject? = null
                // локальному API ядра нужно время подняться сразу после Mobilecore.start()
                for (i in 0 until 15) {
                    if (!running) return@Thread
                    try {
                        val u = java.net.URL("http://127.0.0.1:9090/proxies")
                        val c = u.openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 1500
                        c.readTimeout = 2000
                        if (c.responseCode in 200..299) {
                            proxiesJson = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                            c.disconnect()
                            break
                        }
                        c.disconnect()
                    } catch (_: Exception) {}
                    Thread.sleep(1000)
                }
                val pref = VpnPrefs.preferredServer(this)
                val group = proxiesJson?.optJSONObject("proxies")?.optJSONObject("PROXY")
                val all = group?.optJSONArray("all")
                if (!pref.isNullOrBlank() && all != null) {
                    var has = false
                    for (i in 0 until all.length()) if (all.optString(i) == pref) { has = true; break }
                    if (has && group.optString("now") != pref) {
                        try {
                            val u = java.net.URL("http://127.0.0.1:9090/proxies/PROXY")
                            val c = u.openConnection() as java.net.HttpURLConnection
                            c.requestMethod = "PUT"
                            c.doOutput = true
                            c.connectTimeout = 2000
                            c.readTimeout = 3000
                            c.outputStream.use { it.write("{\"name\":${org.json.JSONObject.quote(pref)}}".toByteArray()) }
                            c.responseCode
                            c.disconnect()
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }.start()
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
        // Идемпотентность: если stopTunnel() уже выполняется (или только что завершился)
        // из-за повторного/параллельного вызова, второй вход просто выходит — раньше это
        // приводило к двойным broadcast("disconnected") и повторным stopForeground/stopSelf.
        if (!stopping.compareAndSet(false, true)) return
        try {
            unwatchNetworkChanges()
            try { Mobilecore.stop() } catch (_: Exception) {}
            try { Mobilecore.setProtect(null) } catch (_: Exception) {}
            closeOwnedTunFd()
            tunFd = null
            running = false
            isRunning = false
            pausedByLock = false
            lastParams = null
            // Любая ОСОЗНАННАЯ остановка (вручную из UI/плитки/виджета, ошибка, отзыв
            // разрешения) должна сбрасывать «это включил watcher» — иначе следующее
            // РУЧНОЕ подключение внутри того же приложения-триггера будет ошибочно
            // считаться сессией watcher'а и погашено само при выходе из приложения.
            // pauseTunnel() (пауза по блокировке экрана) сюда не входит и не должна —
            // это временное состояние, а не осознанная остановка.
            VpnPrefs.setVpnStartedByWatcher(this, false)
            broadcast("disconnected", "")
            unregisterScreenReceiver()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } finally {
            stopping.set(false)
        }
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
        lastNetworkHandle = -1
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

    // Раньше сброс всех активных соединений ядра (DELETE /connections) срабатывал на
    // КАЖДУЮ переоценку onCapabilitiesChanged — а это событие Android шлёт намного чаще,
    // чем реально меняется сеть (сила сигнала сотовой сети, оценка пропускной способности
    // переоцениваются постоянно). На нестабильном мобильном сигнале это могло срабатывать
    // почти непрерывно каждые ~1.5с, обнуляя все соединения внутри туннеля без всякой
    // реальной смены сети — сажало батарею и выглядело как «рвущийся» VPN. Теперь тяжёлая
    // часть (сброс соединений) идёт только если сеть реально другая (сравниваем handle).
    @Volatile private var lastNetworkHandle: Long = -1
    private fun onNetworkSwitched(network: android.net.Network) {
        if (!running) return
        try { setUnderlyingNetworks(arrayOf(network)) } catch (_: Exception) {}

        val handle = network.networkHandle
        if (handle == lastNetworkHandle) return // та же сеть — просто переоценка её характеристик
        lastNetworkHandle = handle

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
        // Раньше stopTunnel() вызывался здесь НАПРЯМУЮ, на вызывающем потоке (обычно
        // главном/binder), в обход воркера. Если в этот момент воркер ещё выполнял
        // startTunnel() (ядро долго стартует / подписка медленно грузится, а систем
        // убивает сервис прямо в этот момент — частый случай на слабых устройствах),
        // два потока одновременно трогали tunFd/ownedTunFd/running без синхронизации —
        // гонка данных, вплоть до двойного закрытия дескриптора туннеля.
        // submit()+get(timeout) сериализует его с воркером, но не даёт зависнуть
        // насовсем, если воркер почему-то не отвечает вовремя.
        try {
            worker.submit { stopTunnel() }.get(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            stopTunnel() // воркер не ответил вовремя — подчищаем сами; stopTunnel() идемпотентен
        }
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
