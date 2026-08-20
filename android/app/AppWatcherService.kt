package network.geodema.misetanibox

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.Process

/**
 * Отдельный foreground-сервис, который проверяет, какое приложение сейчас на
 * переднем плане (через UsageStatsManager — единственный способ без root узнать
 * это), и включает/выключает VPN, когда пользователь заходит в выбранное
 * приложение или выходит из него.
 *
 * Опрос идёт ТОЛЬКО пока экран включён и разблокирован: пока телефон лежит с
 * выключенным экраном, следить всё равно не за чем (приложение на переднем плане
 * не меняется), а каждое пробуждение процессора раз в секунду мешает системе уйти
 * в глубокий сон и экономит батарею хуже, чем кажется по весу самой операции.
 * При блокировке/выключении экрана опрос ставится на паузу, при разблокировке —
 * возобновляется сам, без участия пользователя.
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
    private var screenReceiver: BroadcastReceiver? = null
    // время, ДО которого события уже разобраны — следующий тик читает только
    // новый хвост журнала, а не пересматривает одно и то же окно заново
    private var lastQueriedUntil = 0L

    // Экономичный режим (общий тумблер настроек) реже опрашивает систему — это НЕ
    // влияет на мгновенность реакции при обычном режиме (по умолчанию выключен,
    // интервал остаётся 1000мс, как и было).
    private fun pollIntervalMs(): Long = if (VpnPrefs.isBatterySaver(this)) 2500L else 1000L

    private val poll = object : Runnable {
        override fun run() {
            checkForegroundApp()
            if (polling) handler.postDelayed(this, pollIntervalMs())
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundNotif()
        registerScreenReceiver()
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPolling()
        unregisterScreenReceiver()
        isRunning = false
        super.onDestroy()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(poll)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(poll)
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
                    // экран выключился/заблокирован — незачем опрашивать передний
                    // план, он не меняется, пока телефон лежит выключенным
                    Intent.ACTION_SCREEN_OFF -> {
                        stopPolling()
                        updateNotif(paused = true)
                    }
                    // именно факт разблокировки (а не просто зажёгся экран) —
                    // тот же принцип, что и у паузы VPN по блокировке
                    Intent.ACTION_USER_PRESENT -> {
                        startPolling()
                        updateNotif(paused = false)
                    }
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

    // UsageStatsManager отдаёт СОБЫТИЯ (кто вышел на передний план/ушёл с него), а не
    // текущее состояние напрямую. Раньше каждый тик пересматривал последние 10 секунд
    // журнала целиком — 9 из 10 секунд там уже разбирались на прошлом тике впустую.
    // Теперь читаем только то, что накопилось с прошлой успешной проверки — реакция
    // на открытие приложения та же самая (1 секунда по умолчанию), просто меньше
    // лишней работы на каждый тик.
    private fun checkForegroundApp() {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
            val end = System.currentTimeMillis()
            // небольшой нахлёст назад (2с) на случай, если предыдущий тик подвис
            // дольше обычного — не даёт пропустить событие на границе окна
            val begin = if (lastQueriedUntil > 0) (lastQueriedUntil - 2000).coerceAtLeast(end - 15_000) else end - 10_000
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
            lastQueriedUntil = end
            if (newest != null && newest != lastForegroundPkg) {
                onForegroundChanged(newest)
                lastForegroundPkg = newest
            }
        } catch (_: Exception) {}
    }

    private fun onForegroundChanged(pkg: String) {
        val triggers = VpnPrefs.appTriggerPackages(this)
        if (triggers.isEmpty()) return
        if (pkg == packageName) return // сама Misetanibox/её форк в подсчёт не идёт

        if (pkg in triggers) {
            // зашли в одно из целевых приложений — поднимаем VPN, если он ещё не работает
            if (!MihomoVpnService.isRunning) {
                val reason = VpnPrefs.startFromPrefs(this)
                if (reason == null) vpnWasStartedByWatcher = true
            }
        } else {
            // вышли на что-то, чего нет в списке триггеров — гасим VPN, но только
            // если это МЫ его включили; VPN, который пользователь поднял вручную сам,
            // трогать нельзя
            if (vpnWasStartedByWatcher && MihomoVpnService.isRunning) {
                VpnPrefs.stopVpn(this)
                vpnWasStartedByWatcher = false
            }
        }
    }

    private fun buildNotif(paused: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "Наблюдение за приложением", NotificationManager.IMPORTANCE_MIN)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val count = VpnPrefs.appTriggerPackages(this).size
        val label = if (count == 1) "1 приложением" else "$count приложениями"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Слежу за: $label")
            .setContentText(if (paused) "На паузе — экран выключен" else "VPN включится/выключится автоматически")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNotif() {
        val notif = buildNotif(paused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotif(paused: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(paused))
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
