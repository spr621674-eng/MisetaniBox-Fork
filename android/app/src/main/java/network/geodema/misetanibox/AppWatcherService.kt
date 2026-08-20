package network.geodema.misetanibox

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process

/**
 * Отдельный foreground-сервис, который раз в секунду проверяет, какое приложение
 * сейчас на переднем плане (через UsageStatsManager — единственный способ без root
 * узнать это), и включает/выключает VPN, когда пользователь заходит в выбранное
 * приложение или выходит из него.
 *
 * Работает НЕЗАВИСИМО от MihomoVpnService: это отдельный foreground-сервис со своим
 * уведомлением (Android требует уведомление у каждого foreground-сервиса, объединить
 * с уведомлением VPN нельзя, так как это разные сервисы с разным жизненным циклом).
 *
 * Разрешение на использование UsageStatsManager особое — его нельзя запросить обычным
 * системным диалогом, только направить пользователя в Settings.ACTION_USAGE_ACCESS_SETTINGS.
 */
class AppWatcherService : Service() {

    companion object {
        const val CHANNEL_ID = "misetanibox_watcher"
        const val NOTIF_ID = 8
        @Volatile var isRunning = false
            private set
    }

    private var lastForegroundPkg: String? = null
    private var vpnWasStartedByWatcher = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var polling = false

    private val poll = object : Runnable {
        override fun run() {
            checkForegroundApp()
            if (polling) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundNotif()
        polling = true
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        polling = false
        handler.removeCallbacks(poll)
        isRunning = false
        super.onDestroy()
    }

    // UsageStatsManager отдаёт СОБЫТИЯ (кто вышел на передний план/ушёл с него), а не
    // текущее состояние напрямую — приходится пройти по журналу событий за последнюю
    // секунду и взять последнее MOVE_TO_FOREGROUND. Опрос раз в секунду — компромисс
    // между быстротой реакции и тем, чтобы не грузить систему чаще необходимого.
    private fun checkForegroundApp() {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
            val end = System.currentTimeMillis()
            val begin = end - 10_000 // с запасом на случай, если поллинг где-то подвис
            val events = usm.queryEvents(begin, end)
            val event = UsageEvents.Event()
            var newest: String? = null
            var newestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.timeStamp >= newestTime) {
                    newest = event.packageName
                    newestTime = event.timeStamp
                }
            }
            if (newest != null && newest != lastForegroundPkg) {
                onForegroundChanged(newest)
                lastForegroundPkg = newest
            }
        } catch (_: Exception) {}
    }

    private fun onForegroundChanged(pkg: String) {
        val trigger = VpnPrefs.appTriggerPackage(this)
        if (trigger.isEmpty()) return
        if (pkg == packageName) return // сама Misetanibox/её форк в подсчёт не идёт

        if (pkg == trigger) {
            // зашли в целевое приложение — поднимаем VPN, если он ещё не работает
            if (!MihomoVpnService.isRunning) {
                val reason = VpnPrefs.startFromPrefs(this)
                if (reason == null) vpnWasStartedByWatcher = true
            }
        } else {
            // вышли из целевого приложения на что-то другое — гасим VPN, но только
            // если это МЫ его включили; VPN, который пользователь поднял вручную сам,
            // трогать нельзя
            if (vpnWasStartedByWatcher && MihomoVpnService.isRunning) {
                VpnPrefs.stopVpn(this)
                vpnWasStartedByWatcher = false
            }
        }
    }

    private fun startForegroundNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "Наблюдение за приложением", NotificationManager.IMPORTANCE_MIN)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val trigger = VpnPrefs.appTriggerLabel(this).ifEmpty { "приложение" }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Слежу за: $trigger")
            .setContentText("VPN включится/выключится автоматически")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}

/** Проверка разрешения PACKAGE_USAGE_STATS — это не обычное runtime-разрешение,
 * ставится только вручную через системный экран, поэтому проверяем через AppOpsManager. */
fun hasUsageAccess(ctx: Context): Boolean {
    return try {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}
