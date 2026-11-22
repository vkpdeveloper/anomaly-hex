package ly.anoma.hex

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.colorResource
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.anoma.hex.relay.Computer
import ly.anoma.hex.relay.Discovery
import ly.anoma.hex.relay.Found
import ly.anoma.hex.relay.HexClient
import ly.anoma.hex.relay.HexError
import ly.anoma.hex.relay.Route
import ly.anoma.hex.relay.RouteKind
import ly.anoma.hex.relay.RoutePreference
import ly.anoma.hex.relay.Router
import ly.anoma.hex.relay.Store
import ly.anoma.hex.relay.displayCode
import ly.anoma.hex.relay.isCompleteCode
import ly.anoma.hex.relay.normalizeCode
import ly.anoma.hex.relay.parseAddress
import ly.anoma.hex.relay.routeKind
import ly.anoma.hex.typing.TypingPreferences

/** The computer the keyboard sends dictation to: pair one, or see, check and forget the one paired. */
@Composable
internal fun ComputerSection(store: Store, computer: Computer?, onChange: (Computer?) -> Unit) {
    // Settings' card is a plain background, not a Surface, so text here would default to black.
    CompositionLocalProvider(LocalContentColor provides colorResource(R.color.ink)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (computer == null) Pairing(store, onChange) else Paired(store, computer, onChange)
        }
    }
}

@Composable private fun Note(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)

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
            Modifier.fillMaxWidth()
                .background(if (chosen) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable { typingAddress = false; target = computer.host to computer.port }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(computer.name, modifier = Modifier.weight(1f)) }
    }
    if (typingAddress) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; target = parseAddress(it, HexClient.DEFAULT_PORT) },
            label = { Text("Address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        TextButton(onClick = { typingAddress = true; target = parseAddress(address, HexClient.DEFAULT_PORT) }) {
            Text("Enter an address")
        }
    }
    if (target != null) {
        OutlinedTextField(
            value = codeField,
            onValueChange = { typed ->
                // Adding a dash shifts the text under the cursor, so put the cursor after the code.
                val formatted = displayCode(typed.text)
                codeField = if (formatted == typed.text) typed else TextFieldValue(formatted, TextRange(formatted.length))
            },
            label = { Text("Pairing code") },
            placeholder = { Text("XXXX-XXXX-XXXX") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && isCompleteCode(code),
            onClick = {
                val (host, port) = target ?: return@Button
                busy = true; error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { HexClient.hello(host, port, code, timeoutMs = 3_000) } }
                    busy = false
                    result.fold(
                        onSuccess = { hello ->
                            val paired = Computer(
                                name = hello.name, port = hello.port, code = normalizeCode(code),
                                routes = (hello.routes + Route(routeKind(host), host)).distinct(), model = hello.model,
                            )
                            store.computer = paired
                            TypingPreferences(context).forgetReplacements()
                            onChange(paired)
                        },
                        onFailure = { error = (it as? HexError)?.message ?: "Can't reach Hex" },
                    )
                }
            },
        ) { Text(if (busy) "Connecting…" else "Connect") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
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
            Text(computer.name, style = MaterialTheme.typography.bodyLarge)
            if (computer.model.isNotEmpty()) Note(computer.model)
        }
        TextButton(onClick = { store.computer = null; TypingPreferences(context).forgetReplacements(); onChange(null) }) { Text("Forget") }
    }
    val choices = listOf(RoutePreference.AUTOMATIC to "Automatic", RoutePreference.TAILSCALE to "Tailscale", RoutePreference.LAN to "Wi-Fi")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (choice, name) ->
            SegmentedButton(
                selected = preference == choice,
                onClick = { preference = choice; store.preference = choice; check = null },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
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
                            (if (route.kind == RouteKind.TAILSCALE) "Tailscale" else "Wi-Fi") + " · $elapsed ms"
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
