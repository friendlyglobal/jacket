package io.github.friendlyglobal.jacket.reader

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.friendlyglobal.jacket.MainActivity

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
        private var instance: ListenService? = null
        private var current: MediaSession? = null

        /** Publishes [player] as the book being read aloud; tapping the notification opens [bookId]. */
        fun attach(context: Context, player: Player, bookId: String): MediaSession {
            detach()
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

        /** Ends the current read-aloud session and lets the service stop. */
        fun detach() {
            val session = current ?: return
            current = null
            instance?.removeSession(session)
            session.release()
            instance?.stopSelf()
        }
    }
}
