package com.github.andreyasadchy.xtra.ui.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.chat.StvImageCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ScheduledChatMessageService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var sending: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val channelId = StvImageCache.channelId(intent?.getStringExtra("channelId"))
        val login = intent?.getStringExtra("login")?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,25}")) }
        val accountId = intent?.getStringExtra("accountId")
        val seconds = intent?.getLongExtra("seconds", 0) ?: 0
        val count = intent?.getIntExtra("count", 0)?.takeIf { it > 0 }
        val streamId = intent?.getStringExtra("streamId")
        val message = intent?.getStringExtra("message")?.takeIf(ScheduledChatCommand::validMessage)
        if (channelId == null || login == null || accountId.isNullOrBlank() || seconds <= 0 || message == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(NotificationChannel(
                NOTIFICATION_CHANNEL, getString(R.string.chat_auto_title), NotificationManager.IMPORTANCE_LOW,
            ))
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(login, seconds, count),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        sending?.cancel()
        sending = scope.launch {
            val module = (application as XtraApp).xtraModule
            var remaining = count
            var liveId = streamId
            val intervalMillis = seconds * 1_000
            var nextSend = SystemClock.elapsedRealtime() + intervalMillis
            val publicClient = prefs().getString(C.GQL_CLIENT_ID_WEB, "kimne78kx3ncx6brgo4mv6wki5h1ko")
                ?.takeIf { it.isNotBlank() }?.let { mapOf(C.HEADER_CLIENT_ID to it) }.orEmpty()
            while (isActive) {
                val wait = (nextSend - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                delay(if (count == null) minOf(wait, 30_000) else wait)
                if (tokenPrefs().getString(C.USER_ID, null) != accountId) break
                val gql = TwitchApiHelper.getGQLHeaders(this@ScheduledChatMessageService, true)
                val helix = TwitchApiHelper.getHelixHeaders(this@ScheduledChatMessageService)
                if (gql[C.HEADER_TOKEN].isNullOrBlank() && helix[C.HEADER_TOKEN].isNullOrBlank()) break
                if (count == null) {
                    val status = try {
                        withTimeoutOrNull(10_000) {
                            val id = module.graphQLRepository.scheduledMessageStream(
                                publicClient.ifEmpty { gql }, login, channelId, module.chatMediaNetwork.proxyClient(),
                            )
                            StreamStatus(id)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (status != null) {
                        if (status.id == null || liveId != null && liveId != status.id) break
                        liveId = status.id
                    }
                    if (nextSend > SystemClock.elapsedRealtime()) continue
                    if (status == null) {
                        nextSend = SystemClock.elapsedRealtime() + intervalMillis
                        notificationManager.notify(NOTIFICATION_ID, notification(login, seconds, null, true))
                        continue
                    }
                }
                val attemptedAt = SystemClock.elapsedRealtime()
                val ok = try {
                    val proxy = module.chatMediaNetwork.proxyClient()
                    when {
                        !helix[C.HEADER_TOKEN].isNullOrBlank() -> module.helixRepository
                            .sendMessage(C.OKHTTP, helix, accountId, channelId, message, null, proxy, verifyDelivery = true) == null
                        else -> module.graphQLRepository.sendMessage(C.OKHTTP, gql, channelId, message, null, proxy)
                            .errors.isNullOrEmpty()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                remaining = remaining?.minus(1)
                if (remaining == 0) break
                nextSend = maxOf(attemptedAt + intervalMillis, SystemClock.elapsedRealtime())
                notificationManager.notify(NOTIFICATION_ID, notification(login, seconds, remaining, !ok))
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private data class StreamStatus(val id: String?)

    private fun notification(login: String, seconds: Long, count: Int?, failed: Boolean = false): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(getString(R.string.chat_auto_title))
            .setContentText(when {
                failed -> getString(R.string.chat_auto_failed, login)
                count == null -> getString(R.string.chat_auto_running, login, seconds / 60)
                else -> getString(R.string.chat_auto_count, login, count, seconds)
            })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.chat_auto_stop), stop)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val STOP = "stop_scheduled_chat_message"
        private const val NOTIFICATION_CHANNEL = "scheduled_chat_messages"
        private const val NOTIFICATION_ID = 6276

        internal fun start(
            context: Context, channelId: String, login: String, accountId: String,
            streamId: String?, command: ScheduledChatCommand.Send,
        ) {
            ContextCompat.startForegroundService(context, Intent(context, ScheduledChatMessageService::class.java).apply {
                putExtra("channelId", channelId)
                putExtra("login", login)
                putExtra("accountId", accountId)
                putExtra("streamId", streamId)
                putExtra("seconds", command.intervalSeconds)
                putExtra("count", command.count ?: 0)
                putExtra("message", command.message)
            })
        }

        fun stop(context: Context) = context.stopService(Intent(context, ScheduledChatMessageService::class.java))
    }
}
