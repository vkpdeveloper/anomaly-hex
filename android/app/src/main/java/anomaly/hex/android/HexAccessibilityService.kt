package anomaly.hex.android

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows the Hex button while a text field has focus. A tap records, a second
 * tap sends the audio to the paired computer, and the transcript goes into
 * the field at the cursor.
 */
class HexAccessibilityService : AccessibilityService() {
    private lateinit var store: Store
    private lateinit var router: Router
    private lateinit var windows: WindowManager
    private lateinit var bubble: BubbleView
    private lateinit var params: WindowManager.LayoutParams
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var shown = false
    private var recorder: Recorder? = null
    private var settling = false
    private val settle = Runnable {
        settling = false
        refresh()
    }
    private val clearMessage = Runnable { if (bubble.mode == BubbleView.Mode.MESSAGE) idle() }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = router.invalidate()
        override fun onLost(network: Network) = router.invalidate()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = router.invalidate()
    }

    override fun onServiceConnected() {
        store = Store(this)
        router = Router(this, store)
        windows = getSystemService(WindowManager::class.java)
        bubble = BubbleView(this, onTap = ::tap, onDrag = ::drag, onDragEnd = ::saveY)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = store.bubbleY.takeIf { it >= 0 } ?: (resources.displayMetrics.heightPixels * 0.45f).toInt()
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network, main)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Events arrive in bursts (content changes stream while typing), so
        // look at most once per 60 ms rather than waiting for a quiet moment
        // that may never come.
        if (settling) return
        settling = true
        main.postDelayed(settle, 60)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        main.removeCallbacks(settle)
        recorder?.cancel()
        scope.cancel()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        if (shown) runCatching { windows.removeView(bubble) }
        super.onDestroy()
    }

    /**
     * The focused editable field. Compose reports input focus on its host
     * view, so when the focused node isn't editable, look inside it.
     */
    private fun focusedField(): AccessibilityNodeInfo? {
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (focus.isEditable) return focus
        val pending = ArrayDeque(listOf(focus))
        var visited = 0
        while (visited < MAX_SEARCH) {
            val node = pending.removeFirstOrNull() ?: return null
            visited += 1
            if (node.isEditable && node.isFocused) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        return null
    }

    /** Shows the button over a focused field; keeps it while it's working. */
    private fun refresh() {
        val busy = bubble.mode != BubbleView.Mode.IDLE
        val want = busy || focusedField() != null
        if (want == shown) return
        if (want) {
            windows.addView(bubble, params)
            router.prewarm()
        } else {
            windows.removeView(bubble)
        }
        shown = want
    }

    private fun tap() {
        when (bubble.mode) {
            BubbleView.Mode.IDLE, BubbleView.Mode.MESSAGE -> startRecording()
            BubbleView.Mode.RECORDING -> stopAndSend()
            BubbleView.Mode.THINKING -> Unit
        }
    }

    private fun startRecording() {
        main.removeCallbacks(clearMessage)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return message("Allow the microphone in Hex")
        }
        if (store.computer == null) return message("Connect a computer in Hex")
        val next = Recorder { peak -> main.post { bubble.level(peak) } }
        if (!next.start()) return message("Microphone is busy")
        recorder = next
        bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        bubble.show(BubbleView.Mode.RECORDING)
        // Have the route ready by the time speaking ends.
        router.prewarm()
    }

    private fun stopAndSend() {
        val wav = recorder?.stop()
        recorder = null
        bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (wav == null) return idle()
        bubble.show(BubbleView.Mode.THINKING)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { send(wav) } }
            result.fold(
                onSuccess = { transcript ->
                    if (transcript.isEmpty()) {
                        message("No speech")
                    } else {
                        idle()
                        insert(transcript)
                    }
                },
                onFailure = { error -> message((error as? HexError)?.message ?: "Can't reach Hex") },
            )
        }
    }

    private suspend fun send(wav: ByteArray): String {
        val (computer, route) = router.resolve()
        return try {
            HexClient.transcribe(route.host, computer.port, computer.code, wav)
        } catch (_: HexError.Unreachable) {
            // The cached route went away (Wi-Fi switch, Tailscale down): pick again once.
            router.invalidate()
            val (again, next) = router.resolve()
            HexClient.transcribe(next.host, again.port, again.code, wav)
        }
    }

    /** Puts the text at the cursor, replacing a selection; else pastes it. */
    private fun insert(transcript: String) {
        val field = focusedField() ?: return copyInstead(transcript)
        val current = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val a = field.textSelectionStart
        val b = field.textSelectionEnd
        val known = a in 0..current.length && b in 0..current.length
        val start = if (known) minOf(a, b) else current.length
        val end = if (known) maxOf(a, b) else current.length
        val text = joinAtCursor(current.substring(0, start), transcript)
        val updated = current.substring(0, start) + text + current.substring(end)
        val set = field.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            },
        )
        if (set) {
            val cursor = start + text.length
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Hex", text))
        if (!field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) message("Copied")
    }

    private fun copyInstead(transcript: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hex", transcript))
        message("Copied")
    }

    private fun idle() {
        bubble.show(BubbleView.Mode.IDLE)
        refresh()
    }

    private fun message(text: String) {
        bubble.show(BubbleView.Mode.MESSAGE, text)
        if (!shown) refresh()
        main.removeCallbacks(clearMessage)
        main.postDelayed(clearMessage, 2_200)
    }

    private fun drag(dy: Float) {
        val height = resources.displayMetrics.heightPixels
        params.y = (params.y + dy.toInt()).coerceIn(0, (height - bubble.height).coerceAtLeast(0))
        if (shown) windows.updateViewLayout(bubble, params)
    }

    private fun saveY() {
        store.bubbleY = params.y
    }

    private companion object {
        /** Bounds the search for a focused field inside a Compose screen. */
        const val MAX_SEARCH = 400
    }
}
