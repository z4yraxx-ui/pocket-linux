package com.pocketlinux.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano que mantém o processo do app (e o Debian dentro dele) vivo
 * com o app fechado. Mostra uma notificação fixa com o botão "Encerrar" e se desliga
 * sozinho quando o Debian para.
 */
class PocketService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PocketSession.init(applicationContext)

        if (intent?.action == ACTION_STOP) {
            PocketSession.stopShell()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        // Precisa ser a primeira coisa depois de startForegroundService (prazo de ~5 s).
        startForeground(NOTIFICATION_ID, buildNotification())

        if (watcher?.isActive != true) {
            watcher = scope.launch {
                // Espera acabar tudo: o Debian parar (botão, "exit" ou erro) e nenhuma atualização rodando.
                PocketSession.isBusy.first { !it }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // Se o Android matar o processo, o Debian morre junto; não adianta recriar o serviço vazio.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Debian em execução",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mostra que o Debian está rodando em segundo plano."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PocketService::class.java).setAction(ACTION_STOP),
            flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pocket)
            .setContentTitle("Debian em execução")
            .setContentText("Toque para abrir. O Debian só para quando você encerrar.")
            .setContentIntent(open)
            .addAction(0, "Encerrar", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pocket_debian"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.pocketlinux.app.action.STOP"
    }
}
