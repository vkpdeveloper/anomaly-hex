package anomaly.hex.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hex's screen is always dark, so the system bars keep light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = HexColors) {
                Surface(color = Ink, contentColor = Paper) { SetupScreen() }
            }
        }
    }
}

private val Ink = Color(0xFF0B0C0E)
private val Paper = Color(0xFFF2F2EF)
private val Card = Color(0xFF16171A)
private val Muted = Color(0xFF9A9A96)
private val Done = Color(0xFF4CC38A)

private val HexColors = darkColorScheme(
    primary = Paper,
    onPrimary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = Card,
    onSurface = Paper,
    onSurfaceVariant = Muted,
    outline = Color(0xFF2C2D31),
)

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val store = remember { Store(context) }
    var microphone by remember { mutableStateOf(micAllowed(context)) }
    var accessibility by remember { mutableStateOf(accessibilityOn(context)) }
    var background by remember { mutableStateOf(runsInBackground(context)) }
    var computer by remember { mutableStateOf(store.computer) }

    LifecycleResumeEffect(Unit) {
        microphone = micAllowed(context)
        accessibility = accessibilityOn(context)
        background = runsInBackground(context)
        computer = store.computer
        onPauseOrDispose {}
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            Icon(painterResource(R.drawable.ic_hex_mark), null, tint = Paper, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Hex", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        MicrophoneStep(microphone) { microphone = it }
        AccessibilityStep(accessibility)
        BackgroundStep(background)
        ComputerStep(store, computer) { computer = it }
        if (computer != null && accessibility) TryIt()
    }
}

@Composable
private fun Step(number: Int, title: String, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(if (done) Done else Color(0xFF2C2D31), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (done) "✓" else "$number", fontSize = 13.sp, color = if (done) Ink else Paper)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
        content()
    }
}

@Composable
private fun Note(text: String) = Text(text, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)

@Composable
private fun MicrophoneStep(allowed: Boolean, onResult: (Boolean) -> Unit) {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        onResult(it)
    }
    Step(1, "Microphone", allowed) {
        if (!allowed) {
            Note("Hex records only while you tap its button.")
            Button(onClick = {
                // Once Android stops asking, only App info can allow it.
                if (asked) openAppInfo(context) else request.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text("Allow") }
        }
    }
}

@Composable
private fun AccessibilityStep(on: Boolean) {
    val context = LocalContext.current
    Step(2, "Hex button", on) {
        if (on) {
            Note("Shows beside any text field you type in.")
            return@Step
        }
        Note("Turn on Hex dictation in Accessibility so the button can appear over your apps and type what you say.")
        Button(onClick = { openAccessibility(context) }) { Text("Open settings") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Note("Greyed out, or says restricted setting? Open App info, tap ⋮ in the corner, choose Allow restricted settings, then come back and try again.")
            OutlinedButton(onClick = { openAppInfo(context) }) { Text("App info") }
        }
    }
}

@Composable
private fun BackgroundStep(allowed: Boolean) {
    val context = LocalContext.current
    Step(3, "Run in background", allowed) {
        if (!allowed) {
            Note("Battery saving can stop the Hex button when you aren't looking. Let Hex run without limits.")
            Button(onClick = { allowBackground(context) }) { Text("Allow") }
        }
    }
}

@Composable
private fun ComputerStep(store: Store, computer: Computer?, onChange: (Computer?) -> Unit) {
    Step(4, "Computer", computer != null) {
        if (computer == null) Pairing(store, onChange) else Paired(store, computer, onChange)
    }
}

@Composable
private fun Pairing(store: Store, onChange: (Computer?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val found = remember { mutableStateListOf<Found>() }
    var target by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var typingAddress by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var codeField by remember { mutableStateOf(TextFieldValue("")) }
    val code = codeField.text
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val browsing = Discovery(context).browse(
            onFound = { computer -> if (found.none { it.name == computer.name }) found += computer },
            onLost = { name -> found.removeAll { it.name == name } },
        )
        onDispose { browsing.close() }
    }

    Note("On your computer, open Hex › Settings › Labs and turn on Phone dictation.")
    if (found.isEmpty() && !typingAddress) Note("Looking on this network…")
    found.forEach { computer ->
        val chosen = target == (computer.host to computer.port)
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (chosen) Color(0xFF24252A) else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable {
                    typingAddress = false
                    target = computer.host to computer.port
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(computer.name, modifier = Modifier.weight(1f))
            Text(computer.host, color = Muted, fontSize = 13.sp)
        }
    }
    if (typingAddress) {
        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
                target = parseAddress(it, HexClient.DEFAULT_PORT)
            },
            label = { Text("Address") },
            placeholder = { Text("100.101.102.103") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        TextButton(onClick = {
            typingAddress = true
            target = parseAddress(address, HexClient.DEFAULT_PORT)
        }) { Text("Enter an address") }
    }
    if (target != null) {
        OutlinedTextField(
            value = codeField,
            onValueChange = { typed ->
                // Adding a dash shifts the text under the cursor, so put the
                // cursor after the code rather than before the last letter.
                val formatted = displayCode(typed.text)
                codeField = if (formatted == typed.text) typed else TextFieldValue(formatted, TextRange(formatted.length))
            },
            label = { Text("Pairing code") },
            placeholder = { Text("XXXX-XXXX-XXXX") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && isCompleteCode(code),
            onClick = {
                val (host, port) = target ?: return@Button
                busy = true
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { HexClient.hello(host, port, code, timeoutMs = 3_000) }
                    }
                    busy = false
                    result.fold(
                        onSuccess = { hello ->
                            val paired = Computer(
                                name = hello.name,
                                port = hello.port,
                                code = normalizeCode(code),
                                routes = (hello.routes + Route(routeKind(host), host)).distinct(),
                                model = hello.model,
                            )
                            store.computer = paired
                            onChange(paired)
                        },
                        onFailure = { error = (it as? HexError)?.message ?: "Can't reach Hex" },
                    )
                }
            },
        ) { Text(if (busy) "Connecting…" else "Connect") }
    }
    error?.let { Text(it, color = Color(0xFFFF7A6B), fontSize = 14.sp) }
}

@Composable
private fun Paired(store: Store, computer: Computer, onChange: (Computer?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val router = remember { Router(context, store) }
    var preference by remember { mutableStateOf(store.preference) }
    var check by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(computer.name, fontSize = 16.sp)
            if (computer.model.isNotEmpty()) Note(computer.model)
        }
        TextButton(onClick = {
            store.computer = null
            onChange(null)
        }) { Text("Forget") }
    }
    Text("Route", fontSize = 14.sp, color = Muted)
    val choices = listOf(
        RoutePreference.AUTOMATIC to "Automatic",
        RoutePreference.TAILSCALE to "Tailscale",
        RoutePreference.LAN to "Wi-Fi",
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (choice, name) ->
            SegmentedButton(
                selected = preference == choice,
                onClick = {
                    preference = choice
                    store.preference = choice
                    check = null
                },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Paper,
                    activeContentColor = Ink,
                    inactiveContainerColor = Card,
                ),
                icon = {},
            ) { Text(name) }
        }
    }
    if (preference == RoutePreference.AUTOMATIC) Note("Tailscale first, then Wi-Fi.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            enabled = !checking,
            onClick = {
                checking = true
                scope.launch {
                    router.invalidate()
                    val started = SystemClock.elapsedRealtime()
                    val result = runCatching { router.resolve() }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    checking = false
                    check = result.fold(
                        onSuccess = { (updated, route) ->
                            onChange(updated)
                            val network = if (route.kind == RouteKind.TAILSCALE) "Tailscale" else "Wi-Fi"
                            "$network ${route.host} · $elapsed ms"
                        },
                        onFailure = { (it as? HexError)?.message ?: "Can't reach Hex" },
                    )
                }
            },
        ) { Text(if (checking) "Checking…" else "Check connection") }
        Spacer(Modifier.width(12.dp))
        check?.let { Note(it) }
    }
}

@Composable
private fun TryIt() {
    var text by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Try it", fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Note("Tap the field, then the Hex button on the right. Tap again when you're done.")
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun micAllowed(context: Context) =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun accessibilityOn(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val service = ComponentName(context, HexAccessibilityService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == service }
}

/**
 * Hex's own page in Accessibility where the system lets an app open it
 * (Android 15 refuses with a SecurityException), else the list.
 */
private fun openAccessibility(context: Context) {
    val service = ComponentName(context, HexAccessibilityService::class.java).flattenToString()
    val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, service)
    try {
        context.startActivity(details)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    } catch (_: SecurityException) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

private fun runsInBackground(context: Context) =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun allowBackground(context: Context) {
    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(ask)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}
