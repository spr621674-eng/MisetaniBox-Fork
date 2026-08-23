package network.geodema.misetanibox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Автозапуск после перезагрузки:
 *  - обычный VPN-туннель, если включён автозапуск (VpnPrefs.KEY_AUTOSTART);
 *  - сервис-наблюдатель за приложением (AppWatcherService), если пользователь
 *    включал автовключение VPN по приложению. Сам AppWatcherService НЕ переживает
 *    перезагрузку устройства (это обычный сервис, а не что-то, что операционка
 *    поднимает сама) — поэтому его тоже нужно перезапускать здесь, точно так же,
 *    как основной туннель. Раньше это не делалось, и после любого ребута телефона
 *    фича «Автовключение по приложению» молча переставала работать.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        if (VpnPrefs.isAutostart(context)) {
            VpnPrefs.startFromPrefs(context)
        }

        if (VpnPrefs.isAppWatcherEnabled(context) &&
            VpnPrefs.appTriggerPackages(context).isNotEmpty() &&
            hasUsageAccess(context)
        ) {
            val i = Intent(context, AppWatcherService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
