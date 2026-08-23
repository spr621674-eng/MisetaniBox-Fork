package network.geodema.misetanibox

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "Vpn")
class VpnPlugin : Plugin() {

    private var pendingSubUrl = ""
    private var pendingHwid = ""
    private var pendingUserAgent = Subscription.DEFAULT_USER_AGENT
    private var pendingSplitMode = "off"
    private var pendingSplitApps = arrayOf<String>()
    private var pendingRules = arrayOf<String>()
    private var pendingChains = "[]"
    private var pendingServiceGroups = arrayOf<String>()
    private var receiver: BroadcastReceiver? = null

    override fun load() {
        val filter = IntentFilter("network.geodema.misetanibox.VPN_STATE")
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val ret = JSObject()
                ret.put("state", i?.getStringExtra("state") ?: "")
                ret.put("message", i?.getStringExtra("message") ?: "")
                notifyListeners("vpnState", ret)
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    override fun handleOnDestroy() {
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
    }

    @PluginMethod
    fun start(call: PluginCall) {
        pendingSubUrl = call.getString("subUrl") ?: ""
        pendingHwid = call.getString("hwid") ?: ""
        pendingUserAgent = Subscription.userAgentOr(call.getString("userAgent"))
        pendingSplitMode = call.getString("splitMode") ?: "off"
        val appsArr = call.getArray("splitApps", com.getcapacitor.JSArray())
        val appsList = ArrayList<String>()
        for (i in 0 until (appsArr?.length() ?: 0)) {
            appsArr?.optString(i)?.let { if (it.isNotEmpty()) appsList.add(it) }
        }
        pendingSplitApps = appsList.toTypedArray()

        val rulesArr = call.getArray("rules", com.getcapacitor.JSArray())
        val rulesList = ArrayList<String>()
        for (i in 0 until (rulesArr?.length() ?: 0)) {
            rulesArr?.optString(i)?.let { if (it.isNotBlank()) rulesList.add(it) }
        }
        pendingRules = rulesList.toTypedArray()
        // цепочки приходят готовым JSON-массивом [{name, entry}]
        pendingChains = call.getArray("chains", com.getcapacitor.JSArray())?.toString() ?: "[]"
        // имена select-групп сервисов из конфигуратора селекторов
        val sgArr = call.getArray("serviceGroups", com.getcapacitor.JSArray())
        val sgList = ArrayList<String>()
        for (i in 0 until (sgArr?.length() ?: 0)) {
            sgArr?.optString(i)?.let { if (it.isNotBlank()) sgList.add(it) }
        }
        pendingServiceGroups = sgList.toTypedArray()
        if (pendingSubUrl.isEmpty()) {
            call.reject("нет URL подписки")
            return
        }
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            startActivityForResult(call, prepare, "vpnPermCallback")
        } else {
            launchService()
            call.resolve()
        }
    }

    @ActivityCallback
    private fun vpnPermCallback(call: PluginCall, result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK) {
            launchService()
            call.resolve()
        } else {
            call.reject("пользователь отклонил разрешение VPN")
        }
    }

    private fun launchService() {
        // дублируем параметры в prefs, чтобы плитка/виджет/автозапуск могли поднять туннель без WebView
        VpnPrefs.saveLaunchState(
            context, pendingSubUrl, pendingHwid, pendingUserAgent, pendingSplitMode, pendingSplitApps,
            pendingRules, pendingChains, pendingServiceGroups,
        )
        val i = Intent(context, MihomoVpnService::class.java)
        i.action = MihomoVpnService.ACTION_START
        i.putExtra(MihomoVpnService.EXTRA_SUB_URL, pendingSubUrl)
        i.putExtra(MihomoVpnService.EXTRA_HWID, pendingHwid)
        i.putExtra(MihomoVpnService.EXTRA_USER_AGENT, pendingUserAgent)
        i.putExtra(MihomoVpnService.EXTRA_SPLIT_MODE, pendingSplitMode)
        i.putExtra(MihomoVpnService.EXTRA_SPLIT_APPS, pendingSplitApps)
        i.putExtra(MihomoVpnService.EXTRA_RULES, pendingRules)
        i.putExtra(MihomoVpnService.EXTRA_CHAINS, pendingChains)
        i.putExtra(MihomoVpnService.EXTRA_SERVICE_GROUPS, pendingServiceGroups)
        context.startForegroundService(i)
    }

    @PluginMethod
    fun setAutostart(call: PluginCall) {
        VpnPrefs.setAutostart(context, call.getBoolean("on", false) ?: false)
        call.resolve()
    }

    @PluginMethod
    fun getAutostart(call: PluginCall) {
        val ret = JSObject()
        ret.put("on", VpnPrefs.isAutostart(context))
        call.resolve(ret)
    }

    // Отключать при блокировке / подключать при разблокировке — как у INCY.
    @PluginMethod
    fun setLockBehavior(call: PluginCall) {
        VpnPrefs.setLockDisconnect(context, call.getBoolean("disconnectOnLock", false) ?: false)
        VpnPrefs.setLockReconnect(context, call.getBoolean("reconnectOnUnlock", false) ?: false)
        call.resolve()
    }

    @PluginMethod
    fun getLockBehavior(call: PluginCall) {
        val ret = JSObject()
        ret.put("disconnectOnLock", VpnPrefs.isLockDisconnect(context))
        ret.put("reconnectOnUnlock", VpnPrefs.isLockReconnect(context))
        call.resolve(ret)
    }

    // Открыть системный диалог «не ограничивать батарею для этого приложения».
    // Работает одинаково на любой марке телефона (это стандартный API Android,
    // а не что-то специфичное для MIUI/One UI/EMUI) — но на некоторых прошивках
    // (особенно MIUI) производитель может проигнорировать выданное разрешение
    // и всё равно душить фон своими средствами, поэтому кнопку стоит дополнять
    // подсказкой проверить ручные настройки бренда.
    @PluginMethod
    fun requestIgnoreBatteryOptimizations(call: PluginCall) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
                call.resolve(JSObject().put("alreadyIgnored", true))
                return
            }
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = android.net.Uri.parse("package:" + context.packageName)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            call.resolve(JSObject().put("alreadyIgnored", false))
        } catch (e: Exception) {
            // на части прошивок (особенно MIUI) системный диалог может отсутствовать —
            // тогда сразу открываем общий экран настроек приложения как запасной вариант
            try {
                val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                i.data = android.net.Uri.parse("package:" + context.packageName)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            } catch (_: Exception) {}
            call.reject(e.message ?: "не удалось открыть настройки")
        }
    }

    @PluginMethod
    fun isIgnoringBatteryOptimizations(call: PluginCall) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        call.resolve(JSObject().put("on", pm.isIgnoringBatteryOptimizations(context.packageName)))
    }

    // ---------- экономичный режим ----------
    @PluginMethod
    fun getBatterySaver(call: PluginCall) {
        call.resolve(JSObject().put("on", VpnPrefs.isBatterySaver(context)))
    }

    @PluginMethod
    fun setBatterySaver(call: PluginCall) {
        VpnPrefs.setBatterySaver(context, call.getBoolean("on", false) ?: false)
        call.resolve()
    }

    // ---------- автовключение VPN по приложению (можно выбрать несколько) ----------
    // пустой список отключает функцию (AppWatcherService сам ничего не делает без
    // триггеров, но сам сервис лучше не гонять в фоне, когда список пуст — см. stopAppWatcher).
    @PluginMethod
    fun setAppTriggers(call: PluginCall) {
        val arr = call.getArray("pkgs", com.getcapacitor.JSArray())
        val list = ArrayList<String>()
        for (i in 0 until (arr?.length() ?: 0)) {
            arr?.optString(i)?.let { if (it.isNotBlank()) list.add(it) }
        }
        VpnPrefs.setAppTriggerPackages(context, list)
        call.resolve()
    }

    @PluginMethod
    fun getAppTriggers(call: PluginCall) {
        val ret = JSObject()
        val arr = com.getcapacitor.JSArray()
        for (p in VpnPrefs.appTriggerPackages(context)) arr.put(p)
        ret.put("pkgs", arr)
        ret.put("watcherRunning", AppWatcherService.isRunning)
        call.resolve(ret)
    }

    @PluginMethod
    fun hasUsageAccess(call: PluginCall) {
        call.resolve(JSObject().put("on", hasUsageAccess(context)))
    }

    // Разрешение PACKAGE_USAGE_STATS особое — единственный способ его выдать —
    // системный список приложений с доступом к статистике использования. Открываем
    // сразу список; попасть на конкретно нашу карточку программно нельзя.
    @PluginMethod
    fun requestUsageAccess(call: PluginCall) {
        try {
            val i = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "не удалось открыть настройки")
        }
    }

    @PluginMethod
    fun startAppWatcher(call: PluginCall) {
        if (!hasUsageAccess(context)) {
            call.reject("нет доступа к статистике использования")
            return
        }
        val i = Intent(context, AppWatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        // Персистентный флаг — без него BootReceiver не может узнать после
        // перезагрузки телефона, нужно ли поднимать AppWatcherService заново,
        // потому что сам сервис к тому моменту уже мёртв.
        VpnPrefs.setAppWatcherEnabled(context, true)
        call.resolve()
    }

    @PluginMethod
    fun stopAppWatcher(call: PluginCall) {
        context.stopService(Intent(context, AppWatcherService::class.java))
        VpnPrefs.setAppWatcherEnabled(context, false)
        call.resolve()
    }

    @PluginMethod
    fun isAppWatcherRunning(call: PluginCall) {
        call.resolve(JSObject().put("on", AppWatcherService.isRunning))
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        val i = Intent(context, MihomoVpnService::class.java)
        i.action = MihomoVpnService.ACTION_STOP
        context.startService(i)
        call.resolve()
    }

    @PluginMethod
    fun status(call: PluginCall) {
        val ret = JSObject()
        ret.put("running", MihomoVpnService.isRunning)
        call.resolve(ret)
    }

    // Список установленных приложений с иконкой запуска (для раздельного туннелирования).
    // Берём только приложения с LAUNCHER-активностью (пользовательские), своё исключаем.
    @PluginMethod
    fun listApps(call: PluginCall) {
        Thread {
            val ret = JSObject()
            val arr = com.getcapacitor.JSArray()
            try {
                val pm = context.packageManager
                val self = context.packageName
                val q = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolved = pm.queryIntentActivities(q, 0)
                val seen = HashSet<String>()
                for (ri in resolved) {
                    val pkg = ri.activityInfo?.packageName ?: continue
                    if (pkg == self) continue
                    if (!seen.add(pkg)) continue
                    val label = ri.loadLabel(pm)?.toString() ?: pkg
                    val o = JSObject()
                    o.put("package", pkg)
                    o.put("label", label)
                    arr.put(o)
                }
            } catch (_: Exception) {}
            ret.put("apps", arr)
            call.resolve(ret)
        }.start()
    }

    // Скачать подписку (для превью серверов до подключения) через нативный HTTP,
    // с настраиваемым UA (панели отдают формат конфига по UA) и HWID-заголовками.
    //
    // Наружу отдаём УЖЕ сконвертированный YAML: интерфейсу не нужно знать, что
    // панель прислала — Xray JSON, список ссылок или готовый mihomo-конфиг. Формат
    // и счётчики уходят рядом, чтобы их было видно в подписках и в диагностике.
    @PluginMethod
    fun fetchSub(call: PluginCall) {
        val url = call.getString("url") ?: ""
        val hwid = call.getString("hwid") ?: ""
        val userAgent = Subscription.userAgentOr(call.getString("userAgent"))
        Thread {
            val ret = JSObject()
            val fetched = Subscription.fetch(url, hwid, userAgent)
            ret.put("status", fetched.status)
            if (fetched.body.isBlank()) {
                ret.put("body", "")
                ret.put("error", fetched.error ?: "пустой ответ")
                call.resolve(ret)
                return@Thread
            }
            try {
                val converted = Subscription.convert(fetched.body)
                ret.put("body", converted.config)
                ret.put("format", converted.format)
                ret.put("formatLabel", Subscription.formatLabel(converted.format))
                ret.put("proxies", converted.proxies)
                ret.put("groups", converted.groups)
                ret.put("notes", converted.notes)
                val names = com.getcapacitor.JSArray()
                for (n in converted.names) names.put(n)
                ret.put("names", names)
            } catch (e: Exception) {
                // Формат не разобрался — отдаём тело как есть, чтобы превью могло
                // хотя бы попробовать вытащить имена, и говорим почему.
                ret.put("body", fetched.body)
                ret.put("error", e.message ?: "формат подписки не распознан")
            }
            call.resolve(ret)
        }.start()
    }

    // Прокси к API ядра mihomo (external-controller) через нативный HTTP,
    // чтобы обойти CORS/mixed-content ограничения WebView.
    @PluginMethod
    fun coreRequest(call: PluginCall) {
        val method = (call.getString("method") ?: "GET").uppercase()
        val path = call.getString("path") ?: "/"
        val body = call.getString("body")
        Thread {
            try {
                val url = java.net.URL("http://127.0.0.1:9090$path")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                val ret = JSObject()
                ret.put("status", code)
                ret.put("body", text)
                call.resolve(ret)
            } catch (e: Exception) {
                val ret = JSObject()
                ret.put("status", 0)
                ret.put("body", "")
                ret.put("error", e.message ?: "core unreachable")
                call.resolve(ret)
            }
        }.start()
    }

    // ---------- обновления через релизы GitHub ----------
    // Через нативный HTTP, а не WebView-fetch: у api.github.com CORS не всегда
    // предсказуем на всех связках Android WebView, а GitHub требует свой
    // User-Agent на КАЖДЫЙ запрос — без него отдаёт 403 всем подряд.
    @PluginMethod
    fun checkLatestRelease(call: PluginCall) {
        val repo = call.getString("repo", "") ?: ""
        if (repo.isBlank()) { call.reject("не указан репозиторий"); return }
        Thread {
            try {
                val url = java.net.URL("https://api.github.com/repos/$repo/releases/latest")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "Misetanibox-Updater")
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                val ret = JSObject()
                ret.put("status", code)
                ret.put("body", text)
                call.resolve(ret)
            } catch (e: Exception) {
                val ret = JSObject()
                ret.put("status", 0)
                ret.put("body", "")
                ret.put("error", e.message ?: "network error")
                call.resolve(ret)
            }
        }.start()
    }

    // Качаем APK в кэш приложения (уже описан в file_paths.xml как cache-path
    // "." — покрывает весь cacheDir) и по ходу отдаём проценты через
    // notifyListeners, чтобы интерфейс мог показать прогресс.
    @PluginMethod
    fun downloadUpdate(call: PluginCall) {
        val url = call.getString("url", "") ?: ""
        if (url.isBlank()) { call.reject("нет ссылки на файл"); return }
        Thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Misetanibox-Updater")
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.connect()
                val total = conn.contentLength
                val dir = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
                val outFile = java.io.File(dir, "update.apk")
                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    val ev = JSObject()
                                    ev.put("percent", pct)
                                    notifyListeners("updateProgress", ev)
                                }
                            }
                        }
                    }
                }
                val ret = JSObject()
                ret.put("path", outFile.absolutePath)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject(e.message ?: "ошибка скачивания")
            }
        }.start()
    }

    // Открывает системный установщик APK через FileProvider (тот же провайдер,
    // что уже настроен в манифесте для других нужд).
    @PluginMethod
    fun installUpdate(call: PluginCall) {
        val path = call.getString("path", "") ?: ""
        val file = java.io.File(path)
        if (!file.exists()) { call.reject("файл не найден"); return }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "не удалось запустить установку")
        }
    }

    // На Android 8+ установка APK не из Play Store требует отдельного
    // разрешения "Install unknown apps" — выдаётся только вручную, системный
    // диалог с кнопкой "Разрешить" для него не существует.
    @PluginMethod
    fun canInstallUnknownApps(call: PluginCall) {
        val ret = JSObject()
        ret.put(
            "on",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true
        )
        call.resolve(ret)
    }

    @PluginMethod
    fun requestInstallUnknownApps(call: PluginCall) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                i.data = android.net.Uri.parse("package:" + context.packageName)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "не удалось открыть настройки")
        }
    }
}
