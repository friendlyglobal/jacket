package io.github.friendlyglobal.jacket.reader

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.friendlyglobal.jacket.data.Book

/**
 * Read-aloud as the phone sees it: one Media3 player, there for as long as the app is, whichever
 * book the voice is on and whether or not a voice exists yet. The notification, the lock screen,
 * headphone buttons, the widget's play button and the car all talk to this.
 *
 * It plays nothing itself. It says what the voice is doing (see [PlayerFace]) and hands what it
 * is asked on to [Listening]: Play with nothing being read starts the book being read from the
 * place it was left at, with no reader open; a book chosen in the car is read from its own place.
 *
 * (Readium's read-aloud has a Media3 player of its own, which the session used to be given. It
 * only exists while a voice does, so it could not be asked to start one.)
 */
@OptIn(UnstableApi::class)
internal class BookPlayer(private val listening: Listening) : SimpleBasePlayer(Looper.getMainLooper()) {
    /** Read-aloud has changed: what the phone shows of it follows. */
    fun changed() = invalidateState()

    override fun getState(): State {
        val face = listening.face()
        val book = face.bookId?.let(listening.library.state.value::find)
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        )
        // Next and Previous (a sentence on, a sentence back), while there is a voice to send.
        if (book != null && face.skips) {
            commands.addAll(
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
        }
        val state = State.Builder()
            .setAvailableCommands(commands.build())
            .setAudioAttributes(SPEECH)
        // (A book that has left the library meanwhile is no book to play.)
        if (book == null) return state.setPlaybackState(Player.STATE_IDLE).build()
        return state
            .setPlaylist(listOf(MediaItemData.Builder(book.id).setMediaItem(bookItem(book, listening.artOf(book.id))).build()))
            .setPlaybackState(face.playbackState)
            .setPlayWhenReady(face.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) listening.play() else listening.pause()
        return Futures.immediateVoidFuture()
    }

    /** Nothing is made ready until Play: opening a book and loading a voice is the start of reading it. */
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        listening.stop()
        return Futures.immediateVoidFuture()
    }

    /** A book chosen (in the car), or the one Android was told Play resumes: the Play that follows reads it. */
    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        listening.choose(mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)?.mediaId)
        return Futures.immediateVoidFuture()
    }

    /**
     * Next and Previous, from a headset, the notification, the lock screen or the car: a sentence
     * on or back, as the player's own skip buttons and the widget's go. (The book is the player's
     * one item, so Media3 has nowhere to seek to and asks with no item: the command says which
     * way. Readium's own player, which the session had before, offered them and did nothing.)
     */
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> listening.skip(1)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> listening.skip(-1)
            else -> {}
        }
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val SPEECH: AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
    }
}

/**
 * [book] as Media3 names it, for the player and for the lists the car is offered: its title and
 * author as the library has them, and ([art], a JPEG) the library's cover. It is known by the
 * book's id.
 */
internal fun bookItem(book: Book, art: ByteArray? = null): MediaItem {
    val about = MediaMetadata.Builder()
        .setTitle(book.title)
        .setArtist(book.author.takeIf { it.isNotBlank() })
        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
        .setIsBrowsable(false)
        .setIsPlayable(true)
    if (art != null) about.setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    return MediaItem.Builder().setMediaId(book.id).setMediaMetadata(about.build()).build()
}
