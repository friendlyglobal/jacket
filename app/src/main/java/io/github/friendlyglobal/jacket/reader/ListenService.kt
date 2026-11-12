package io.github.friendlyglobal.jacket.reader

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaButtonReceiver
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.friendlyglobal.jacket.MainActivity
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.listening
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps read-aloud playing with the screen off and puts its controls on the lock screen, in the
 * notification shade and on headphones: Media3 posts the notification and keeps the service in
 * the foreground during playback.
 *
 * The session's player is the app's own ([BookPlayer]), which is there whether or not a voice
 * is, so the service can be started with nothing playing and asked to play: by a headphone button
 * after Android has closed Jacket ([ResumeReceiver]), by the Play of a notification left in the
 * shade, by the widget. Media3 then asks which book to resume ([Shelf.onPlaybackResumption]) and
 * the voice starts in it, at the place it was left at, with no reader open.
 *
 * It is a library service as well, so that what can be listened to can be browsed from outside
 * the app: "Continue listening" and the recent books, which is what a car shows (see [Shelf]).
 */
class ListenService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var watching: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        val listening = listening
        val session = MediaLibrarySession.Builder(this, listening.player, Shelf(this)).setId(SESSION).setSessionActivity(opening(null)).build()
        this.session = session
        // Media3 posts the notification, and keeps the service in the foreground, for the sessions
        // it has been told of. It learns of one by itself when a button press or another app asks
        // for it; started by the reader, there is no such asking.
        addSession(session)
        // Tapping the notification opens the book being read aloud.
        watching = listening.scope.launch { snapshotFlow { listening.bookId }.collect { session.setSessionActivity(opening(it)) } }
    }

    private fun opening(bookId: String?): PendingIntent {
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (bookId != null) open.putExtra(MainActivity.EXTRA_OPEN_BOOK, bookId)
        return PendingIntent.getActivity(this, bookId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // A button press (a headphone's, the notification's, the widget's) is passed on to the
        // player by Media3, in a moment. Android may have started the service for it on the
        // promise that it is about to play, and ends the app if no notification follows: a Play
        // shows one as soon as a book is being opened. With nothing to play (no book that can be
        // read aloud), or a press that turns out not to be a Play, one is shown briefly instead,
        // and the service stops (as Readium's own sample does).
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            if (!listening.canPlay()) leave(startId) else handler.postDelayed({ if (!listening.busy) leave(startId) }, PLAY_BY_MS)
        }
        return START_NOT_STICKY
    }

    /** Started with nothing to play: shows a notification for an instant, as a started foreground service must, and stops. */
    private fun leave(startId: Int) {
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Swiping Jacket away from Recents stops reading. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        listening.stop()
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        watching?.cancel()
        // (The player is the app's, and stays: the next service is given the same one.)
        session?.release()
        session = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * What can be listened to, for whoever asks from outside the app, and which book Play
     * resumes. A car (Android Auto) shows the first as two lists: "Continue listening", the book
     * being read, and "Recent books". Choosing a book there reads it from the place it was left at.
     *
     * The lists are only given to the system, to a car, and to apps the listener has let see
     * notifications (which can see what is playing anyway): any other app that asks is told
     * there is nothing here, so the books on this phone stay nobody else's business.
     */
    @OptIn(UnstableApi::class)
    private class Shelf(private val service: ListenService) : MediaLibrarySession.Callback {
        private val listening get() = service.listening
        private val library get() = service.library

        private fun books(): List<Book> = booksToListenTo(library.state.value, library.unreadable)

        private fun mayBrowse(session: MediaSession, browser: MediaSession.ControllerInfo): Boolean =
            browser.isTrusted || browser.packageName == service.packageName ||
                session.isAutoCompanionController(browser) || session.isAutomotiveController(browser) || session.isMediaNotificationController(browser)

        private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS).build(),
            )
            .build()

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            if (mayBrowse(session, browser)) {
                Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, service.getString(R.string.app_name)), params))
            } else {
                Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
            }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (!mayBrowse(session, browser)) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
            val children = when (parentId) {
                ROOT -> listOf(
                    folder(CONTINUE, service.getString(R.string.listen_browse_continue)),
                    folder(RECENT, service.getString(R.string.listen_browse_recent)),
                )
                CONTINUE -> listOfNotNull(listening.toResume()).map { bookItem(it) }
                RECENT -> books().map { bookItem(it) }
                else -> return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
            val paged = children.drop(page.coerceAtLeast(0) * pageSize.coerceAtLeast(1)).take(pageSize.coerceAtLeast(1))
            return Futures.immediateFuture(LibraryResult.ofItemList(paged, params))
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            if (!mayBrowse(session, browser)) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
            val item = when (mediaId) {
                ROOT -> folder(ROOT, service.getString(R.string.app_name))
                CONTINUE -> folder(CONTINUE, service.getString(R.string.listen_browse_continue))
                RECENT -> folder(RECENT, service.getString(R.string.listen_browse_recent))
                else -> books().firstOrNull { it.id == mediaId }?.let { bookItem(it) }
            }
            return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }

        /**
         * A book asked for: by its id (chosen from a list), or by name ("play Dracula"; with no
         * name, the book being read). What comes back is what the player is then told to play.
         */
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            val books = books()
            val asked = mediaItems.firstNotNullOfOrNull { item ->
                books.firstOrNull { it.id == item.mediaId }
                    ?: item.requestMetadata.searchQuery?.let { said -> bookSaid(said, books) ?: listening.toResume() }
            }
            return if (asked != null) {
                Futures.immediateFuture(mutableListOf(bookItem(asked)))
            } else {
                Futures.immediateFailedFuture(UnsupportedOperationException("No such book to read aloud"))
            }
        }

        /**
         * Play, with nothing being read aloud: after Android has closed Jacket, from a headphone
         * button or the notification Android keeps for the last thing played. The book being read
         * is the one; where in it the voice starts is the voice's own business (the place the
         * book was left at: see [ReadAloud]).
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val book = listening.toResume() ?: return Futures.immediateFailedFuture(UnsupportedOperationException("No book to read aloud"))
            // To be played, the player shows the cover itself. To be shown only (the card Android
            // keeps for the last thing played), the book comes with it.
            if (isForPlayback) return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(bookItem(book)), 0, C.TIME_UNSET))
            val shown = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            listening.scope.launch { shown.set(MediaSession.MediaItemsWithStartPosition(listOf(bookItem(book, listening.cover(book))), 0, C.TIME_UNSET)) }
            return shown
        }
    }

    companion object {
        private const val SESSION = "jacket"
        private const val STALE_NOTIFICATION_ID = 2001

        /** A service started for a Play shows that it is playing within this long, or steps down. */
        private const val PLAY_BY_MS = 3_000L

        /** The browse tree: its root, and the two lists under it. A book is known by its own id. */
        private const val ROOT = "jacket:root"
        private const val CONTINUE = "jacket:continue"
        private const val RECENT = "jacket:recent"

        /** The id Media3 posts the read-aloud notification under (its DefaultMediaNotificationProvider's own). */
        private const val MEDIA_NOTIFICATION_ID = 1001
        private var instance: ListenService? = null

        /**
         * Takes away the read-aloud notification an earlier run of Jacket left behind. Paused for
         * ten minutes, the service leaves the foreground and its notification stays up; if Android
         * then closes Jacket, it shows the book as it was then. For when Jacket starts, by
         * whatever means (that notification's own Play included, which starts the voice again and
         * brings a fresh one): a notification from before then is that one.
         */
        fun clearLeftover(context: Context) {
            if (instance == null) runCatching { NotificationManagerCompat.from(context).cancel(MEDIA_NOTIFICATION_ID) }
        }

        /** A voice is about to read: the service is what shows it to the phone, and keeps it going with the screen off. */
        internal fun start(context: Context) {
            // Asked for each time, running or not: one that has just been told it has no more to
            // do ([rest]) is still there for a moment, and would be gone under the voice. (From
            // the reader, Jacket is in front and may start it. Started any other way, the service
            // is what asked, and Android refusing this second asking changes nothing.)
            runCatching { context.startService(Intent(context, ListenService::class.java)) }
        }

        /** Nothing is being read aloud any more: the service has no more to do. */
        internal fun rest() {
            instance?.stopSelf()
        }
    }
}

/**
 * A headphone button pressed after Android has closed Jacket (or after the voice was stopped):
 * Android sends it here, and Media3 starts [ListenService] for it, which resumes the book being
 * read. Only when there is a book that can be read aloud: the service is started on the promise
 * that it will play.
 */
@OptIn(UnstableApi::class)
class ResumeReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        val listening = context.listening
        // (Media3 only asks for a press of Play. With nothing to read aloud, the listener is told
        // so instead of the press doing nothing.)
        return listening.canPlay().also { if (!it) listening.nothingToRead() }
    }
}
