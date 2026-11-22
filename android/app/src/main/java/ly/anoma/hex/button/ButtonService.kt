package ly.anoma.hex.button

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ly.anoma.hex.MainActivity
import ly.anoma.hex.R
import ly.anoma.hex.keyboard.SpeechEnd
import ly.anoma.hex.relay.HexClient
import ly.anoma.hex.relay.HexError
import ly.anoma.hex.relay.Recorder
import ly.anoma.hex.relay.Router
import ly.anoma.hex.relay.Store
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.TypingPreferences

/** The floating button's saved choices, shared by the service and the settings page. */
internal class ButtonPreferences(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("button", Context.MODE_PRIVATE)
    /** True while the button is wanted; turning it off makes the service switch itself off. */
    var on: Boolean
        get() = prefs.getBoolean(ON, false)
        set(value) = prefs.edit().putBoolean(ON, value).apply()
    /** Whether the button hides while the Hex keyboard is on screen; the keyboard has its own mic. */
    var hideWithKeyboard: Boolean
        get() = prefs.getBoolean("hide_with_keyboard", true)
        set(value) = prefs.edit().putBoolean("hide_with_keyboard", value).apply()
    /** The button's distance from the top of the screen, in pixels; -1 is unset. */
    var y: Int
        get() = prefs.getInt("y", -1)
        set(value) = prefs.edit().putInt("y", value).apply()

    companion object {
        const val ON = "on"

        /** Whether Android runs the button's service (turned on in Accessibility). */
        fun running(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val service = ComponentName(context, ButtonService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == service }
        }

        /** The button's own page in Accessibility where Android allows it (Android 15 refuses), else the list. */
        fun openAccessibility(context: Context) {
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, ButtonService::class.java).flattenToString())
            try {
                context.startActivity(details)
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: SecurityException) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }
}

/**
 * The floating button, for dictating into any app without the Hex keyboard. A tap records, a second
 * tap sends the audio to the paired computer, and the transcript goes into the focused field at the
 * cursor (or is copied when none has focus). Turned on in Accessibility; turning it off in Hex makes
 * the service switch itself off. A silent notification holds it in the foreground so battery savers
 * that kill background apps (OxygenOS) leave it running.
 */
class ButtonService : AccessibilityService() {
    private lateinit var prefs: ButtonPreferences
    private lateinit var store: Store
    private lateinit var router: Router
    private lateinit var windowManager: WindowManager
    private lateinit var bubble: BubbleView
    private lateinit var params: WindowManager.LayoutParams
    private var shown = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: Recorder? = null
    private var speech: SpeechEnd? = null
    private var settling = false
    /** Whether a text field had focus at the last look. */
    private var typing = false
    private val settle = Runnable {
        settling = false
        // Have the route ready when a field gains focus, before any tap.
        val now = focusedField() != null
        if (now && !typing) router.prewarm()
        typing = now
        updateVisibility()
    }
    private val clearMessage = Runnable {
        if (bubble.mode == BubbleView.Mode.MESSAGE || bubble.mode == BubbleView.Mode.DONE) idle()
    }
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ButtonPreferences.ON && !prefs.on) disableSelf() else updateVisibility()
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = router.invalidate()
        override fun onLost(network: Network) = router.invalidate()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = router.invalidate()
    }

    override fun onServiceConnected() {
        prefs = ButtonPreferences(this)
        // Turned on in Accessibility counts as turned on in Hex.
        prefs.on = true
        prefs.prefs.registerOnSharedPreferenceChangeListener(changed)
        store = Store(this)
        router = Router(this, store)
        windowManager = getSystemService(WindowManager::class.java)
        bubble = BubbleView(this, onTap = ::tap, onDrag = ::drag, onDragEnd = { prefs.y = params.y })
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
            y = prefs.y.takeIf { it >= 0 } ?: (resources.displayMetrics.heightPixels * 0.45f).toInt()
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network, main)
        startForeground()
        windowManager.addView(bubble, params)
        shown = true
        bubble.appear()
        updateVisibility()
        router.prewarm()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Events arrive in bursts (content changes stream while typing), so look at most once per 60 ms
        // rather than waiting for a quiet moment that may never come.
        if (settling || !shown) return
        settling = true
        main.postDelayed(settle, 60)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // Turned off in Accessibility counts as turned off in Hex.
        if (::prefs.isInitialized) {
            prefs.prefs.unregisterOnSharedPreferenceChangeListener(changed)
            prefs.on = false
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        main.removeCallbacks(settle)
        main.removeCallbacks(clearMessage)
        recorder?.cancel()
        scope.cancel()
        if (::prefs.isInitialized) {
            prefs.prefs.unregisterOnSharedPreferenceChangeListener(changed)
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        }
        if (shown) runCatching { windowManager.removeViewImmediate(bubble) }
        shown = false
        super.onDestroy()
    }

    /** The silent notification: minimum importance, so no sound, vibration or status bar icon. */
    private fun startForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Floating button", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.hex_mark)
            .setContentTitle("Hex button is on")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        // A failure here only costs the extra protection, never the button.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION, notification)
            }
        }
    }

    /** Hidden while the Hex keyboard is up, when that's chosen; never mid-dictation. */
    private fun updateVisibility() {
        if (!shown) return
        val hide = bubble.mode == BubbleView.Mode.IDLE && prefs.hideWithKeyboard && hexKeyboardShown()
        bubble.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun hexKeyboardShown(): Boolean {
        val keyboard = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        if (!keyboard.startsWith("$packageName/")) return false
        return getWindows().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    /** The focused editable field. Compose reports input focus on its host view, so when the focused
     * node isn't editable, look inside it. */
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

    private fun tap() {
        when (bubble.mode) {
            BubbleView.Mode.IDLE, BubbleView.Mode.MESSAGE, BubbleView.Mode.DONE -> startRecording()
            BubbleView.Mode.RECORDING -> stopAndSend()
            BubbleView.Mode.THINKING -> Unit
        }
    }

    private fun startRecording() {
        main.removeCallbacks(clearMessage)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return message("Allow the microphone in Hex")
        }
        if (store.computer == null) return message(HexError.NotPaired.message.orEmpty())
        lateinit var next: Recorder
        val end = SpeechEnd()
        next = Recorder(
            onLevel = { peak -> main.post { if (recorder === next) { end.feed(peak); bubble.level(peak) } } },
            // At the computer's limit: send what there is rather than lose it.
            onFull = { main.post { if (recorder === next) stopAndSend() } },
        )
        if (!next.start()) return message("Microphone is busy")
        recorder = next
        speech = end
        bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        bubble.show(BubbleView.Mode.RECORDING)
        // Have the route ready by the time speaking ends.
        router.prewarm()
    }

    private fun stopAndSend() {
        val wav = recorder?.stop()
        val heard = speech?.heard == true
        recorder = null; speech = null
        bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (wav == null) return idle()
        // Only the room came through: nothing to send.
        if (!heard) return message("No speech")
        bubble.show(BubbleView.Mode.THINKING)
        // The app the text goes to picks the computer's mode; the keyboard's layout hints the language.
        val layout = TypingPreferences(this).layout ?: Spoken.system()
        val origin = HexClient.Origin.of(this, focusedField()?.packageName?.toString(), HexClient.Origin.language(layout))
        scope.launch {
            val result = runCatching { router.send { host, computer -> HexClient.transcribe(host, computer.port, computer.code, wav, origin).text } }
            if (result.exceptionOrNull() is CancellationException) return@launch
            result.fold(
                onSuccess = { transcript ->
                    if (transcript.isEmpty()) message("No speech") else if (insert(transcript)) done()
                },
                onFailure = { error -> message((error as? HexError)?.message ?: HexError.Unreachable.message.orEmpty()) },
            )
        }
    }

    /** Puts the text at the cursor, replacing a selection; else pastes it. False when it could only be
     * copied (and says so). */
    private fun insert(transcript: String): Boolean {
        val field = focusedField() ?: return copyInstead(transcript)
        val a = field.textSelectionStart
        val b = field.textSelectionEnd
        val current = fieldText(field.text, field.hintText, field.isShowingHintText, maxOf(a, b))
        val known = a in 0..current.length && b in 0..current.length
        // Without a cursor, rebuilding the text is a guess (it may still be a placeholder Hex can't see
        // as one), so let the app paste it where its own cursor is.
        if (!known && paste(field, transcript)) return true
        val start = if (known) minOf(a, b) else current.length
        val end = if (known) maxOf(a, b) else current.length
        val text = joinAtCursor(current.substring(0, start), transcript)
        val updated = current.substring(0, start) + text + current.substring(end)
        val set = field.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated) },
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
            return true
        }
        if (known && paste(field, text)) return true
        message("Copied")
        return false
    }

    /** Copies the text and pastes it into the field; the copy stays either way. */
    private fun paste(field: AccessibilityNodeInfo, text: String): Boolean {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hex", text))
        return field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun copyInstead(transcript: String): Boolean {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hex", transcript))
        message("Copied")
        return false
    }

    /** A check for a moment, then back to the mark. */
    private fun done() {
        bubble.show(BubbleView.Mode.DONE)
        val feel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        bubble.performHapticFeedback(feel)
        main.removeCallbacks(clearMessage)
        main.postDelayed(clearMessage, 900)
    }

    private fun idle() {
        bubble.show(BubbleView.Mode.IDLE)
        updateVisibility()
    }

    private fun message(text: String) {
        bubble.show(BubbleView.Mode.MESSAGE, text)
        main.removeCallbacks(clearMessage)
        main.postDelayed(clearMessage, 2_200)
    }

    private fun drag(dy: Float) {
        val height = resources.displayMetrics.heightPixels
        params.y = (params.y + dy.toInt()).coerceIn(0, (height - bubble.height).coerceAtLeast(0))
        if (shown) windowManager.updateViewLayout(bubble, params)
    }

    private companion object {
        /** Bounds the search for a focused field inside a Compose screen. */
        const val MAX_SEARCH = 400
        const val CHANNEL = "button"
        const val NOTIFICATION = 1
    }
}
