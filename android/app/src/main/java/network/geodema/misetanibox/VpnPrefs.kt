package network.geodema.misetanibox

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import org.json.JSONArray

/**
 * Нативное хранилище параметров запуска VPN + хелперы для плитки в шторке,
 * виджета на рабочем столе и автозапуска после перезагрузки.
 *
 * Смысл: основной UI хранит подписку/split в localStorage (JS), но плитке/виджету/
 * BootReceiver нужно поднять туннель БЕЗ открытия WebView — поэтому VpnPlugin.start
 * дублирует параметры сюда (saveLaunchState), а эти компоненты стартуют из prefs.
 */
object VpnPrefs {
    const val PREFS = "misetanibox"
    const val KEY_AUTOSTART = "autostart"
    const val KEY_SUB_URL = "sub_url"
    const val KEY_HWID = "hwid"
    /** User-Agent запроса подписки: по нему панель выбирает формат ответа */
    const val KEY_USER_AGENT = "user_agent"
    /** off | bypass | only (см. MihomoVpnService.applySplitTunnel) */
    const val KEY_SPLIT_MODE = "split_mode"
    /** JSON-массив имён пакетов */
    const val KEY_SPLIT_APPS = "split_apps"
    /** JSON-массив строк-правил mihomo */
    const val KEY_RULES = "rules"
    /** JSON-массив цепочек [{name, entry}] */
    const val KEY_CHAINS = "chains"
    /** JSON-массив имён select-групп сервисов (конфигуратор селекторов) */
    const val KEY_SERVICE_GROUPS = "service_groups"
    /** Разрывать туннель при блокировке экрана (аналог функции INCY) */
    const val KEY_LOCK_DISCONNECT = "lock_disconnect"
    /** Поднимать туннель заново при разблокировке — работает только вместе с KEY_LOCK_DISCONNECT */
    const val KEY_LOCK_RECONNECT = "lock_reconnect"
    /** JSON-массив имён пакетов приложений-триггеров для автовключения VPN (пусто = функция выключена) */
    const val KEY_APP_TRIGGER_PKGS = "app_trigger_pkgs"
    /** Экономичный режим: реже опрашивает ядро, меньше будит систему */
    const val KEY_BATTERY_SAVER = "battery_saver"
    /**
     * Включена ли функция «Автовключение по приложению» (тумблер, а не список приложений).
     * Раньше это состояние жило только в памяти как AppWatcherService.isRunning — после
     * перезагрузки устройства ничто не поднимало сервис заново, хотя список приложений
     * и сам факт «функция была включена» в prefs сохранялись. BootReceiver читает этот
     * ключ, чтобы восстановить сервис вместе с обычным автозапуском VPN.
     */
    const val KEY_APP_WATCHER_ENABLED = "app_watcher_enabled"
    /**
     * Watcher сам включил текущую VPN-сессию (а не пользователь вручную) — раньше это
     * жило только в памяти AppWatcherService (vpnWasStartedByWatcher). Сервис START_STICKY,
     * система может убить и перезапустить его под давлением памяти — при перезапуске флаг
     * терялся, хотя VPN, включённый watcher'ом, продолжал работать, и «выключить при выходе
     * из приложения» переставало срабатывать. Теперь переживает перезапуск сервиса.
     */
    const val KEY_WATCHER_STARTED_VPN = "watcher_started_vpn"

    /**
     * Режим маршрутизации (rule | global | direct) — зеркало JS localStorage 'mise_mode'.
     * Источник истины для UI остаётся в WebView; сюда дублируется, потому что при
     * запуске VPN из плитки/автовключения по приложению/автозапуска после перезагрузки
     * интерфейс не открыт, и applyModeToCore() (JS, PATCH /configs) никогда не
     * выполняется — режим «Глобально»/«Прямой» тихо откатывался на «Правила» при любом
     * фоновом подключении. См. MihomoVpnService.applyRoutingMode().
     */
    const val KEY_ROUTING_MODE = "routing_mode"
    fun routingMode(ctx: Context): String {
        val m = prefs(ctx).getString(KEY_ROUTING_MODE, "rule") ?: "rule"
        return if (m == "global" || m == "direct") m else "rule"
    }
    fun setRoutingMode(ctx: Context, mode: String) {
        val m = if (mode == "global" || mode == "direct") mode else "rule"
        prefs(ctx).edit().putString(KEY_ROUTING_MODE, m).apply()
    }

    /**
     * Выбранный пользователем «сервер по умолчанию» внутри основной группы (PROXY).
     * Раньше применялся ТОЛЬКО из открытого WebView (JS дёргал /proxies PUT после
     * подключения) — при автовключении по приложению, плитке или автозапуске после
     * перезагрузки интерфейс не открывается, и ядро оставалось на своём дефолтном
     * узле группы вместо реально выбранного — сайты грузились заметно медленнее.
     */
    const val KEY_PREFERRED_SERVER = "preferred_server"
    fun preferredServer(ctx: Context): String? {
        val v = prefs(ctx).getString(KEY_PREFERRED_SERVER, null)
        return if (v.isNullOrBlank()) null else v
    }
    fun setPreferredServer(ctx: Context, name: String?) {
        prefs(ctx).edit().putString(KEY_PREFERRED_SERVER, name ?: "").apply()
    }

    fun appTriggerPackages(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString(KEY_APP_TRIGGER_PKGS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = HashSet<String>()
            for (i in 0 until arr.length()) {
                val p = arr.optString(i, "").trim()
                if (p.isNotEmpty()) out.add(p)
            }
            out
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun setAppTriggerPackages(ctx: Context, pkgs: Collection<String>) {
        prefs(ctx).edit()
            .putString(KEY_APP_TRIGGER_PKGS, JSONArray(pkgs.toList()).toString())
            .apply()
    }

    fun isAppWatcherEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_APP_WATCHER_ENABLED, false)
    fun setAppWatcherEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_APP_WATCHER_ENABLED, on).apply()
    }

    fun isVpnStartedByWatcher(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_WATCHER_STARTED_VPN, false)
    fun setVpnStartedByWatcher(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WATCHER_STARTED_VPN, on).apply()
    }

    fun isBatterySaver(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BATTERY_SAVER, false)
    fun setBatterySaver(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BATTERY_SAVER, on).apply()
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLockDisconnect(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOCK_DISCONNECT, false)

    fun setLockDisconnect(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LOCK_DISCONNECT, on).apply()
    }

    fun isLockReconnect(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOCK_RECONNECT, false)

    fun setLockReconnect(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LOCK_RECONNECT, on).apply()
    }

    fun hasSubscription(ctx: Context): Boolean =
        (prefs(ctx).getString(KEY_SUB_URL, "") ?: "").isNotBlank()

    fun splitMode(ctx: Context): String {
        val m = prefs(ctx).getString(KEY_SPLIT_MODE, "off") ?: "off"
        return if (m == "bypass" || m == "only") m else "off"
    }

    fun splitAppsArray(ctx: Context): Array<String> {
        val raw = prefs(ctx).getString(KEY_SPLIT_APPS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val p = arr.optString(i, "").trim()
                if (p.isNotEmpty()) out.add(p)
            }
            out.toTypedArray()
        } catch (_: Exception) {
            arrayOf()
        }
    }

    fun isAutostart(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTOSTART, false)

    fun setAutostart(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTOSTART, on).apply()
    }

    /** Сохранить то, чем стартует туннель, чтобы плитка/виджет/бут могли поднять его сами. */
    fun saveLaunchState(
        ctx: Context,
        subUrl: String,
        hwid: String,
        userAgent: String,
        splitMode: String,
        splitApps: Array<String>,
        rules: Array<String> = arrayOf(),
        chainsJson: String = "[]",
        serviceGroups: Array<String> = arrayOf(),
    ) {
        val sm = if (splitMode == "bypass" || splitMode == "only") splitMode else "off"
        prefs(ctx).edit()
            .putString(KEY_SUB_URL, subUrl)
            .putString(KEY_HWID, hwid)
            .putString(KEY_USER_AGENT, Subscription.userAgentOr(userAgent))
            .putString(KEY_SPLIT_MODE, sm)
            .putString(KEY_SPLIT_APPS, JSONArray(splitApps.toList()).toString())
            .putString(KEY_RULES, JSONArray(rules.toList()).toString())
            .putString(KEY_CHAINS, chainsJson)
            .putString(KEY_SERVICE_GROUPS, JSONArray(serviceGroups.toList()).toString())
            .apply()
    }

    private fun jsonStringArray(raw: String?): Array<String> {
        return try {
            val arr = JSONArray(raw ?: "[]")
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotEmpty()) out.add(v)
            }
            out.toTypedArray()
        } catch (_: Exception) {
            arrayOf()
        }
    }

    /**
     * Поднять VPN из сохранённых prefs.
     * @return null если запущено, иначе строка-причина.
     */
    fun startFromPrefs(ctx: Context): String? {
        if (!hasSubscription(ctx)) return "нет подписки"
        if (VpnService.prepare(ctx) != null) return "нужно разрешение VPN"
        val p = prefs(ctx)
        val i = Intent(ctx, MihomoVpnService::class.java).apply {
            action = MihomoVpnService.ACTION_START
            putExtra(MihomoVpnService.EXTRA_SUB_URL, p.getString(KEY_SUB_URL, "") ?: "")
            putExtra(MihomoVpnService.EXTRA_HWID, p.getString(KEY_HWID, "") ?: "")
            putExtra(MihomoVpnService.EXTRA_USER_AGENT, Subscription.userAgentOr(p.getString(KEY_USER_AGENT, "")))
            putExtra(MihomoVpnService.EXTRA_SPLIT_MODE, splitMode(ctx))
            putExtra(MihomoVpnService.EXTRA_SPLIT_APPS, splitAppsArray(ctx))
            putExtra(MihomoVpnService.EXTRA_RULES, jsonStringArray(p.getString(KEY_RULES, "[]")))
            putExtra(MihomoVpnService.EXTRA_CHAINS, p.getString(KEY_CHAINS, "[]") ?: "[]")
            putExtra(MihomoVpnService.EXTRA_SERVICE_GROUPS, jsonStringArray(p.getString(KEY_SERVICE_GROUPS, "[]")))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        return null
    }

    fun stopVpn(ctx: Context) {
        val i = Intent(ctx, MihomoVpnService::class.java).apply { action = MihomoVpnService.ACTION_STOP }
        ctx.startService(i)
    }
}

