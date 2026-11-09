package io.github.friendlyglobal.jacket.outer

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _available = MutableStateFlow(false)
    /** True when the device can present on its outer display right now (i.e. it's unfolded). */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

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
        session.setContentView(
            ComposeView(session.context).apply {
                setViewTreeLifecycleOwner(activity)
                setViewTreeSavedStateRegistryOwner(activity)
                setViewTreeViewModelStoreOwner(activity)
                setContent { content() }
            },
        )
    }

    override fun onSessionEnded(t: Throwable?) {
        session = null
        _active.value = false
        if (t != null) Log.w(TAG, "Outer display session ended with an error", t)
    }

    override fun onContainerVisibilityChanged(isVisible: Boolean) {}

    private companion object {
        const val TAG = "JacketOuter"
        val PRESENT = WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA
    }
}
