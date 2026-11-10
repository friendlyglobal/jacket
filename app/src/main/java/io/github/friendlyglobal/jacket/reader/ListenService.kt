package io.github.friendlyglobal.jacket.reader

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.friendlyglobal.jacket.MainActivity
import io.github.friendlyglobal.jacket.R

/**
 * Keeps read-aloud playing with the screen off and puts its controls on the lock screen, in the
 * notification shade and on headphones. The reader hands over its player while it's speaking;
 * Media3 posts the notification and keeps the service in the foreground during playback.
 */
class ListenService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        current?.let(::addSession)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Started with nothing to play: a headphone button or an old notification after Android
        // closed Jacket. A started foreground service must show a notification before stopping,
        // or Android ends the app; so show one briefly and stop (as Readium's own sample does).
        if (current == null) {
            val channel = "listen"
            NotificationManagerCompat.from(this).createNotificationChannel(
                NotificationChannelCompat.Builder(channel, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(getString(R.string.listen_notification_channel))
                    .build(),
            )
            val notification = NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.drawable.media3_notification_small_icon)
                .setContentTitle(getString(R.string.app_name))
                .setSilent(true)
                .build()
            runCatching {
                ServiceCompat.startForeground(
                    this, STALE_NOTIFICATION_ID, notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
                )
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = current

    /** Swiping Jacket away from Recents stops reading. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        current?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val STALE_NOTIFICATION_ID = 2001
        private var instance: ListenService? = null
        private var current: MediaSession? = null

        /** Publishes [player] as the book being read aloud; tapping the notification opens [bookId]. */
        fun attach(context: Context, player: Player, bookId: String): MediaSession {
            current?.let(::detach)
            val open = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_BOOK, bookId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val session = MediaSession.Builder(context, player)
                .setId("jacket-$bookId")
                .setSessionActivity(
                    PendingIntent.getActivity(
                        context, bookId.hashCode(), open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .build()
            current = session
            val service = instance
            if (service != null) service.addSession(session) else context.startService(Intent(context, ListenService::class.java))
            return session
        }

        /**
         * Ends [session] if it's still the one playing (switching books can briefly overlap two
         * readers; the outgoing one must not end the incoming one's session).
         */
        fun detach(session: MediaSession) {
            if (current !== session) {
                session.release()
                return
            }
            current = null
            instance?.removeSession(session)
            session.release()
            instance?.stopSelf()
        }
    }
}
