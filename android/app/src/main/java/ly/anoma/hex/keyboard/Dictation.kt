package ly.anoma.hex.keyboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ly.anoma.hex.R
import ly.anoma.hex.relay.HexClient
import ly.anoma.hex.relay.HexError
import ly.anoma.hex.relay.Recorder
import ly.anoma.hex.relay.Router
import ly.anoma.hex.relay.Store

internal enum class DictationPhase { Idle, Listening, Sending }

/** Voice typing through the paired computer: the phone records, Hex on the computer transcribes and
 * runs its Output steps, and the text comes back to the cursor. Every callback runs on the main thread. */
internal class Dictation(
    private val context: Context,
    private val show: (DictationPhase) -> Unit,
    private val level: (Float) -> Unit,
    private val note: (String) -> Unit,
    private val insert: (HexClient.Heard) -> Unit,
    /** Why dictation can't start in this field (a password), or null. */
    private val blocked: () -> String?,
    /** The app and keyboard language the dictation is spoken in, read as it starts. */
    private val origin: () -> HexClient.Origin = { HexClient.Origin() },
) {
    private val store = Store(context)
    /** The keyboard's one route to the computer, shared with the words fetch. */
    val router = Router(context, store)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: Recorder? = null
    private var speech: VoiceHeard? = null
    private var sending: Job? = null
    private var spokenIn = HexClient.Origin()
    var phase = DictationPhase.Idle
        private set(value) { if (field != value) { field = value; show(value) } }

    // A route picked on one network is no use on the next.
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = router.invalidate()
        override fun onLost(network: Network) = router.invalidate()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = router.invalidate()
    }
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    init { runCatching { connectivity.registerDefaultNetworkCallback(network) } }

    /** A field gained the keyboard: have the route ready before the mic is tapped. */
    fun prewarm() { if (store.computer != null) router.prewarm() }

    /** Starts recording. It never ends by itself on a pause: a tapped start ends at the next tap
     * ([toggle]), a held one when the finger lifts. */
    fun start(): Boolean {
        if (phase != DictationPhase.Idle) return false
        blocked()?.let { note(it); return false }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            note(context.getString(R.string.keyboard_hint_microphone)); return false
        }
        if (store.computer == null) { note(HexError.NotPaired.message.orEmpty()); return false }
        // Listens to every recording, so one of only the room isn't sent.
        val voice = VoiceHeard()
        lateinit var next: Recorder
        next = Recorder(
            onLevel = { peak ->
                main.post {
                    if (recorder !== next) return@post
                    level(peak)
                    voice.feed(peak)
                }
            },
            // At the computer's limit: send what there is rather than lose it.
            onFull = { main.post { if (recorder === next) finish() } },
        )
        if (!next.start()) { note(context.getString(R.string.keyboard_hint_mic_busy)); return false }
        recorder = next
        speech = voice
        spokenIn = origin()
        phase = DictationPhase.Listening
        router.prewarm()
        return true
    }

    /** Stops recording and sends it. One too short to send ends quietly; one where no voice came
     * through isn't sent, and says so. */
    fun finish() {
        val wav = recorder?.stop()
        val heard = speech?.heard == true
        recorder = null; speech = null
        if (wav == null) { phase = DictationPhase.Idle; return }
        if (!heard) { phase = DictationPhase.Idle; note(context.getString(R.string.keyboard_hint_no_speech)); return }
        phase = DictationPhase.Sending
        val from = spokenIn
        sending = scope.launch {
            val result = runCatching { router.send { host, computer -> HexClient.transcribe(host, computer.port, computer.code, wav, from) } }
            if (result.exceptionOrNull() is CancellationException) return@launch
            sending = null
            phase = DictationPhase.Idle
            result.fold(
                onSuccess = { heard -> if (heard.text.isBlank()) note(context.getString(R.string.keyboard_hint_no_speech)) else insert(heard) },
                onFailure = { error -> note((error as? HexError)?.message ?: HexError.Unreachable.message.orEmpty()) },
            )
        }
    }

    fun toggle() = when (phase) {
        DictationPhase.Idle -> { start(); Unit }
        DictationPhase.Listening -> finish()
        DictationPhase.Sending -> Unit
    }

    /** The field went away: a recording stops unsent, but a transcript already on its way still comes
     * back, and the keyboard decides where it goes. */
    fun leave() {
        if (phase != DictationPhase.Listening) return
        recorder?.cancel(); recorder = null; speech = null
        phase = DictationPhase.Idle
    }

    /** Drops the recording, or the transcript still on its way: nothing reaches the field later. */
    fun cancel() {
        recorder?.cancel(); recorder = null; speech = null
        sending?.cancel(); sending = null
        phase = DictationPhase.Idle
    }

    fun close() {
        cancel(); scope.cancel()
        runCatching { connectivity.unregisterNetworkCallback(network) }
    }
}
