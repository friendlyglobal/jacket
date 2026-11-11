package io.github.friendlyglobal.jacket.outer

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnAttach
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import io.github.friendlyglobal.jacket.reader.ReaderHeldBrightness
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Shows our own content on the outer (rear) display of a book-style foldable while the user
 * keeps reading on the inner display: Jetpack WindowManager's dual-screen mode.
 *
 * Devices without it (phones, flips, older foldables) simply report [available] = false.
 */
class OuterDisplay(
    private val activity: ComponentActivity,
    private val content: @Composable () -> Unit,
) : WindowAreaPresentationSessionCallback {

    private val controller: WindowAreaController? =
        runCatching { WindowAreaController.getOrCreate() }.getOrNull()
    private var rearArea: WindowAreaInfo? = null
    private var session: WindowAreaSessionPresenter? = null
    private var wanted = false
    private var brightness: Job? = null

    private val _available = MutableStateFlow(false)
    /** True when the device can present on its outer display right now (i.e. it's unfolded). */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** The book open in the reader, whose cover this display wears (null: the library's current one). */
    var bookId: String? by mutableStateOf(null)

    init {
        controller?.let { c ->
            activity.lifecycleScope.launch {
                activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    c.windowAreaInfos
                        .map { infos -> infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING } }
                        .collect { area ->
                            rearArea = area
                            val status = area?.getCapability(PRESENT)?.status
                            _available.value = status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE ||
                                status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE
                            if (wanted && session == null && status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE) {
                                present()
                            }
                        }
                }
            }
        }
    }

    /** Requests the outer display; it starts now if possible, or as soon as the device is unfolded. */
    fun start() {
        wanted = true
        if (session == null) present()
    }

    fun stop() {
        wanted = false
        session?.close()
    }

    fun toggle() = if (_active.value) stop() else start()

    private fun present() {
        val token = rearArea?.token ?: return
        if (!_available.value) return
        runCatching {
            controller?.presentContentOnWindowArea(token, activity, ContextCompat.getMainExecutor(activity), this)
        }.onFailure { Log.w(TAG, "Couldn't present on outer display", it) }
    }

    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
        // The reader may have closed while the session was starting.
        if (!wanted) {
            session.close()
            return
        }
        this.session = session
        _active.value = true
        val view = ComposeView(session.context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setContent { content() }
        }
        session.setContentView(view)
        // Lit like the inner screen, as it goes: at the reader's own brightness while it holds one,
        // otherwise at the phone's (which follows the room, with adaptive brightness on). Left to
        // itself, the outer screen keeps whatever brightness it last had folded.
        brightness?.cancel()
        brightness = activity.lifecycleScope.launch {
            combine(phoneBrightness(), ReaderHeldBrightness) { phone, held -> if (held >= 0f) held else phone }
                .distinctUntilChanged()
                .collect { level -> view.holdBrightness(level) }
        }
    }

    /** The phone's brightness (0..1), as it changes. */
    private fun phoneBrightness(): Flow<Float> = callbackFlow {
        val resolver = activity.contentResolver
        fun read() = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, DEFAULT_BRIGHTNESS) / 255f
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(read())
            }
        }
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer)
        trySend(read())
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    /** Holds the window this view is in at [level] of its screen's brightness. */
    private fun View.holdBrightness(level: Float) {
        if (!isAttachedToWindow) {
            doOnAttach { it.holdBrightness(level) }
            return
        }
        val root = rootView
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.screenBrightness == level) return
        params.screenBrightness = level
        runCatching { context.getSystemService(WindowManager::class.java)?.updateViewLayout(root, params) }
            .onFailure { Log.w(TAG, "Couldn't match the outer display's brightness", it) }
    }

    override fun onSessionEnded(t: Throwable?) {
        brightness?.cancel()
        brightness = null
        session = null
        _active.value = false
        if (t != null) Log.w(TAG, "Outer display session ended with an error", t)
    }

    override fun onContainerVisibilityChanged(isVisible: Boolean) {}

    private companion object {
        const val TAG = "JacketOuter"

        /** The phone's brightness when it can't be read: about half. */
        const val DEFAULT_BRIGHTNESS = 128
        val PRESENT = WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA
    }
}
