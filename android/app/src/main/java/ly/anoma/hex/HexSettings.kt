package ly.anoma.hex

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.textservice.TextServicesManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import ly.anoma.hex.speech.*
import ly.anoma.hex.button.ButtonPreferences
import ly.anoma.hex.fix.TinyfixFiles
import ly.anoma.hex.keyboard.FreeTimes
import ly.anoma.hex.keyboard.Snippet
import ly.anoma.hex.keyboard.Snippets
import ly.anoma.hex.keyboard.TextedCodes
import ly.anoma.hex.relay.Store
import ly.anoma.hex.typing.TypingPreferences

private enum class Page(val title: String) {
    Home("Hex"), Computer("Computer"), Keyboard("Keyboard"), Privacy("Privacy"),
    Layout("Keyboard layout"), Shortcuts("Text shortcuts"), Shortcut("Text shortcut"), Policy("Privacy policy"), Licences("Open-source licences"), Licence("Open-source licences");
    val parent get() = when (this) {
        Layout, Shortcuts -> Keyboard
        Shortcut -> Shortcuts
        Policy, Licences -> Privacy
        Licence -> Licences
        else -> Home
    }
}

/** Licence files the credits name, in the order they appear in assets/licenses. */
private fun licenceFiles(context: Context) =
    context.assets.list("licenses").orEmpty().filter { it != "credits.txt" }.sorted()

@Composable
internal fun HexSettings(setup: Setup, typing: TypingPreferences, store: Store,
                         setupPage: (Int) -> Unit, systemSettings: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.Home) }
    var licence by rememberSaveable { mutableStateOf("") }
    var practice by remember { mutableStateOf("") }
    var computer by remember { mutableStateOf(store.computer) }
    val context = LocalContext.current
    // The activity and the IME write the same preferences.
    // Observe them even while trying the keyboard inside this page.
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val stores = listOf("typing", "button").map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        stores.forEach { it.registerOnSharedPreferenceChangeListener(changed) }
        onDispose { stores.forEach { it.unregisterOnSharedPreferenceChangeListener(changed) } }
    }
    val automatic = remember(LocalConfiguration.current) { Spoken.system() }
    val selectedLayout = remember(revision) { typing.layout }
    val autocorrect = remember(revision) { typing.autocorrect }
    val autoCapitalize = remember(revision) { typing.autoCapitalize }
    val swearWords = remember(revision) { typing.suggestSwearWords }
    val spatial = remember(revision) { typing.spatial }
    val numberRow = remember(revision) { typing.numberRow }
    val popups = remember(revision) { typing.popups }
    val haptics = remember(revision) { typing.haptics }
    val glide = remember(revision) { typing.glide }
    val glideTrail = remember(revision) { typing.glideTrail }
    val learnFromHistory = remember(revision) { typing.learnFromHistory }
    val sayToSend = remember(revision) { typing.sayToSend }
    val cleanLinks = remember(revision) { typing.cleanLinks }
    val expandLinks = remember(revision) { typing.expandLinks }
    val snippets = remember(revision) { Snippets.read(typing.snippets) }
    // The shortcut being edited: its place in the list, or -1 for a new one.
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    // Codes from texts is on only with the SMS permission too, asked as Contacts is.
    var smsAllowed by remember { mutableStateOf(TextedCodes.allowed(context)) }
    var smsRefused by rememberSaveable { mutableStateOf(false) }
    val textedCodes = remember(revision, smsAllowed) { typing.textedCodes && smsAllowed }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        smsAllowed = granted
        smsRefused = !granted && (context as? android.app.Activity)
            ?.shouldShowRequestPermissionRationale(TextedCodes.PERMISSION) != true
        if (granted) typing.textedCodes = true
    }
    // Free times reads the calendar only with its permission, asked as Contacts is.
    var calendarAllowed by remember { mutableStateOf(FreeTimes.allowed(context)) }
    var calendarRefused by rememberSaveable { mutableStateOf(false) }
    val freeTimes = remember(revision, calendarAllowed) { typing.freeTimes && calendarAllowed }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarAllowed = granted
        calendarRefused = !granted && (context as? android.app.Activity)
            ?.shouldShowRequestPermissionRationale(FreeTimes.PERMISSION) != true
        if (granted) typing.freeTimes = true
    }
    // On only with the Contacts permission too. Once Android stops asking (a second no), the switch opens
    // Hex's app info instead.
    var contactsAllowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    var contactsRefused by rememberSaveable { mutableStateOf(false) }
    val contactEmails = remember(revision, contactsAllowed) { typing.contactEmails && contactsAllowed }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        contactsAllowed = granted
        contactsRefused = !granted && (context as? android.app.Activity)
            ?.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) != true
        if (granted) typing.contactEmails = true
    }
    // Whether Android's spell checker is Hex's, read again each time you come back from its settings.
    var hexChecks by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        val services = context.getSystemService(TextServicesManager::class.java)
        hexChecks = services?.isSpellCheckerEnabled == true && services.currentSpellCheckerInfo?.packageName == context.packageName
        onPauseOrDispose {}
    }
    val fixState by TinyfixFiles.state.collectAsState()
    LaunchedEffect(revision) { TinyfixFiles.refresh(context, typing) }
    var confirmForget by remember { mutableStateOf(false) }
    val button = remember { ButtonPreferences(context) }
    val hideButton = remember(revision) { button.hideWithKeyboard }
    fun open(next: Page) { page = next }
    BackHandler(page != Page.Home) { open(page.parent) }

    Box(Modifier.fillMaxSize().background(colorResource(R.color.page)).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter) {
        key(page) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (page == Page.Home) Image(painterResource(R.drawable.hex_mark), null, Modifier.size(27.dp),
                            colorFilter = ColorFilter.tint(colorResource(R.color.ink)))
                        else Box(Modifier.size(44.dp).clickable(role = Role.Button) { open(page.parent) },
                            contentAlignment = Alignment.CenterStart) {
                            Image(painterResource(R.drawable.ic_back), "Back", Modifier.size(24.dp),
                                colorFilter = ColorFilter.tint(colorResource(R.color.ink)))
                        }
                        if (page == Page.Home) Spacer(Modifier.width(10.dp))
                        Label(if (page == Page.Licence) licence else page.title, size = 26, weight = FontWeight.SemiBold,
                            lines = 2, mod = Modifier.semantics { heading() })
                    }
                    if (page == Page.Home) {
                        val steps = listOf(setup.mic, setup.chosen, computer != null).count { !it }
                        val status = if (steps == 0) "Ready" else "Finish setup · $steps " + if (steps == 1) "step" else "steps"
                        Row((if (steps > 0) Modifier.clickable(role = Role.Button) { setupPage(1) } else Modifier)
                            .heightIn(min = if (steps > 0) 44.dp else 26.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (steps == 0) Image(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp),
                                colorFilter = ColorFilter.tint(colorResource(R.color.quiet)))
                            Spacer(Modifier.width(4.dp)); Label(status, muted = true, size = 14)
                            if (steps > 0) Label("  ›", muted = true)
                        }
                    }
                }
                when (page) {
                    Page.Home -> {
                        Card {
                            Entry("Computer", computer?.name ?: "Not connected", action = { open(Page.Computer) })
                            listOf(Page.Keyboard, Page.Privacy).forEach { dest ->
                                Rule(); Entry(dest.title, action = { open(dest) })
                            }
                        }
                        Card {
                            // Android only lets the person turn an accessibility service on; off, it switches itself off.
                            Toggle("Floating button", "Dictate into any app without the keyboard", setup.button) {
                                if (it) ButtonPreferences.openAccessibility(context) else button.on = false
                            }
                            if (setup.button) {
                                Rule(); Toggle("Hide with Hex keyboard", "The keyboard has its own mic", hideButton) { button.hideWithKeyboard = it }
                            }
                        }
                        Card {
                            Entry("Keyboard settings", value = "↗", action = systemSettings)
                            Rule(); Entry("Run setup again", action = { setupPage(0) })
                        }
                    }
                    Page.Computer -> Card {
                        Box(Modifier.padding(16.dp)) { ComputerSection(store, computer) { computer = it } }
                    }
                    Page.Keyboard -> {
                        val photos = rememberRecentPhotos(context)
                        Card {
                            Entry("Keyboard layout", if (selectedLayout == null) automatic.label else null,
                                selectedLayout?.label ?: "Automatic", { open(Page.Layout) })
                            Rule(); Toggle("Auto-capitalization", "Capitalize the first word of each sentence", autoCapitalize) { typing.autoCapitalize = it }
                            Rule(); Toggle("Autocorrect", "Fix typos when you press space", autocorrect) { typing.autocorrect = it }
                            // Shown under Autocorrect while it is on, as on iPhone.
                            if (autocorrect) { Rule(); Toggle("Spatial autocorrect", "Use where taps land to fix typos", spatial) { typing.spatial = it } }
                            // The fix button's files download only once it is turned on, and go when it is turned off.
                            Rule(); Toggle("Fix button", when (val state = fixState) {
                                is TinyfixFiles.State.Downloading -> "Downloading, ${state.percent}%"
                                TinyfixFiles.State.Failed -> "Couldn't download. Try again"
                                TinyfixFiles.State.Ready -> "Fixes English words like \u201Cform\u201D for \u201Cfrom\u201D"
                                TinyfixFiles.State.Off -> "Fixes English words like \u201Cform\u201D for \u201Cfrom\u201D. Downloads 3.5 MB"
                            }, fixState is TinyfixFiles.State.Ready || fixState is TinyfixFiles.State.Downloading) {
                                if (it) TinyfixFiles.enable(context, typing) else TinyfixFiles.disable(context, typing)
                            }
                            Rule(); Toggle("Suggest swear words", "Slurs are never suggested", swearWords) { typing.suggestSwearWords = it }
                            Rule(); Toggle("Suggest contacts' names and emails",
                                if (contactsRefused) "Allow Contacts in Hex's app info" else "Needs access to your contacts", contactEmails) {
                                contactsAllowed = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                                when {
                                    !it || contactsAllowed -> typing.contactEmails = it
                                    contactsRefused -> context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)))
                                    else -> askContacts.launch(Manifest.permission.READ_CONTACTS)
                                }
                            }
                            // The words the keyboard keeps and suggests are Android's own personal dictionary.
                            Rule(); Entry("Personal dictionary", "Words the keyboard always knows", "↗",
                                { context.startActivity(Intent(Settings.ACTION_USER_DICTIONARY_SETTINGS)) })
                            // Android's spell checker setting: with Hex, a misspelled word's fixes show on the strip.
                            Rule(); Entry("Spell checker", if (hexChecks) "Hex underlines words it doesn't know" else "Choose Hex to underline mistakes", "↗",
                                { openSpellCheckers(context) })
                            Rule(); Entry("Text shortcuts", snippets.firstOrNull()?.let { "Type ${it.shortcut} for \u201C${it.filled().first}\u201D" },
                                action = { open(Page.Shortcuts) })
                            Rule(); Toggle("Number row", "1 to 0 above the letters", numberRow) { typing.numberRow = it }
                            Rule(); Toggle("Key pop-ups", "Show the letter above your finger", popups) { typing.popups = it }
                            Rule(); Toggle("Haptics", "A light tap when you press a key", haptics) { typing.haptics = it }
                            Rule(); Toggle("Glide typing", "Slide across the letters to type a word", glide) { typing.glide = it }
                            Rule(); Toggle("Show gesture trail", if (glide) "A line follows your finger" else "Turn on Glide typing",
                                glideTrail, enabled = glide) { typing.glideTrail = it }
                            Rule(); Toggle("Recent screenshots", if (photos.partial) "Only the photos you chose" else "Offer a screenshot you just took",
                                photos.on) { photos.change(it) }
                            Rule(); Toggle("Codes from texts",
                                if (smsRefused) "Allow SMS in Hex's app info" else "Offer a code a text message brings", textedCodes) {
                                smsAllowed = TextedCodes.allowed(context)
                                when {
                                    !it || smsAllowed -> typing.textedCodes = it
                                    smsRefused -> context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)))
                                    else -> askSms.launch(TextedCodes.PERMISSION)
                                }
                            }
                            Rule(); Toggle("Free times",
                                if (calendarRefused) "Allow Calendar in Hex's app info" else "Type \u201Cfree\u201D and a space to offer when you're free", freeTimes) {
                                calendarAllowed = FreeTimes.allowed(context)
                                when {
                                    !it || calendarAllowed -> typing.freeTimes = it
                                    calendarRefused -> context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)))
                                    else -> askCalendar.launch(FreeTimes.PERMISSION)
                                }
                            }
                            Rule(); Toggle("Remove tracking from links", "When you copy a link", cleanLinks) {
                                typing.cleanLinks = it
                            }
                            Rule(); Toggle("Expand short links", "Show where bit.ly and amzn.to links go", expandLinks) {
                                typing.expandLinks = it
                            }
                            Rule(); Toggle("Learn from Hex history", "Start from the words in your computer's dictations", learnFromHistory) {
                                typing.learnFromHistory = it
                            }
                            Rule(); Toggle("Say \"send it\" to send", "End a dictation with it to press send", sayToSend) {
                                typing.sayToSend = it
                            }
                            Rule(); Entry("Forget learned words", if (confirmForget) "Tap again to forget everything the keyboard learned" else null, "", {
                                if (confirmForget) { typing.forgetLearned(context.filesDir); confirmForget = false } else confirmForget = true
                            })
                        }
                        val ink = colorResource(R.color.ink)
                        Card {
                            BasicTextField(value = practice, onValueChange = { practice = it }, maxLines = 4,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                textStyle = TextStyle(color = ink, fontFamily = sans, fontSize = 16.sp),
                                cursorBrush = SolidColor(ink), decorationBox = { field ->
                                    Box { if (practice.isEmpty()) Label("Test keyboard…", muted = true); field() }
                                })
                        }
                    }
                    Page.Privacy -> {
                        Card {
                            Entry("Your voice goes to your computer", "Hex on your computer transcribes it, never a cloud service")
                            Rule(); Entry("Typing history", "Hex stores no keystrokes or transcripts")
                        }
                        Card {
                            Entry("Privacy policy", action = { open(Page.Policy) })
                            Rule(); Entry("Open-source licences", action = { open(Page.Licences) })
                        }
                    }
                    Page.Layout -> Card {
                        (listOf(null) + Spoken.entries).forEachIndexed { i, it ->
                            if (i != 0) Rule()
                            Entry(it?.label ?: "Automatic", if (it == null) "Follow the phone's language" else null,
                                if (it == selectedLayout) "✓" else "", { typing.layout = it; open(page.parent) })
                        }
                    }
                    Page.Shortcuts -> {
                        if (snippets.isNotEmpty()) Card {
                            snippets.forEachIndexed { i, it ->
                                if (i != 0) Rule()
                                Entry(it.shortcut, it.text, action = { editing = i; open(Page.Shortcut) })
                            }
                        }
                        Card { Entry("Add shortcut", value = "+", action = { editing = -1; open(Page.Shortcut) }) }
                    }
                    Page.Shortcut -> ShortcutEditor(snippets.getOrNull(editing), snippets.filterIndexed { i, _ -> i != editing }) { changed ->
                        val list = snippets.toMutableList()
                        when {
                            changed == null -> if (editing in list.indices) list.removeAt(editing)
                            editing in list.indices -> list[editing] = changed
                            else -> list += changed
                        }
                        typing.snippets = Snippets.write(list)
                        open(Page.Shortcuts)
                    }
                    Page.Policy, Page.Licences, Page.Licence -> {
                        val asset = when (page) { Page.Policy -> "privacy-policy.txt"; Page.Licences -> "licenses/credits.txt"; else -> "licenses/$licence" }
                        val text = remember(asset) { context.assets.open(asset).bufferedReader().use { it.readText() } }
                        SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { Document(text) } }
                        // The credits name these files; each opens here.
                        if (page == Page.Licences) Card {
                            licenceFiles(context).forEachIndexed { i, file ->
                                if (i != 0) Rule()
                                Entry(file, action = { licence = file; open(Page.Licence) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A text shortcut's two fields, with Save, and Delete for one already kept; [done] gets null for Delete. */
@Composable private fun ShortcutEditor(snippet: Snippet?, others: List<Snippet>, done: (Snippet?) -> Unit) {
    var shortcut by rememberSaveable { mutableStateOf(snippet?.shortcut ?: ";") }
    var text by rememberSaveable { mutableStateOf(snippet?.text.orEmpty()) }
    val taken = others.any { it.shortcut == shortcut.trim() }
    val ready = shortcut.trim().length >= 2 && shortcut.trim().none { it.isWhitespace() } && text.isNotBlank() && !taken
    Card {
        Field("Shortcut", shortcut, single = true) { shortcut = it }
        Rule(); Field("Text", text, single = false) { text = it }
    }
    Label(if (taken) "You already have that shortcut" else "Put a part in { } to select it, like There in {10} min",
        size = 13, muted = true, lines = 2, mod = Modifier.padding(horizontal = 16.dp))
    Card {
        Entry("Save", value = if (ready) "✓" else "", action = if (ready) ({ done(Snippet(shortcut.trim(), text)) }) else null)
        if (snippet != null) { Rule(); Entry("Delete", value = "", action = { done(null) }) }
    }
}
@Composable private fun Field(title: String, value: String, single: Boolean, change: (String) -> Unit) {
    val ink = colorResource(R.color.ink)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Label(title, size = 13, muted = true)
        BasicTextField(value = value, onValueChange = change, singleLine = single, maxLines = if (single) 1 else 6,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textStyle = TextStyle(color = ink, fontFamily = sans, fontSize = 16.sp), cursorBrush = SolidColor(ink))
    }
}

@Composable private fun Label(text: String, size: Int = 16, weight: FontWeight = FontWeight.Normal,
                              muted: Boolean = false, inverted: Boolean = false, lines: Int = 1, mod: Modifier = Modifier) {
    BasicText(text, mod, style = TextStyle(fontFamily = sans, fontWeight = weight, fontSize = size.sp,
        lineHeight = (size + 5).sp, color = colorResource(if (inverted) R.color.page else if (muted) R.color.quiet else R.color.ink)),
        maxLines = lines, overflow = TextOverflow.Ellipsis)
}
/** Plain text as paragraphs: hard-wrapped lines joined so they wrap to the screen, and a short first line
 * with no closing punctuation (the policy's section names) set as a heading. */
@Composable private fun Document(text: String) {
    for (block in text.trim().split(Regex("\\n\\s*\\n"))) {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) continue
        val titled = lines.size > 1 && lines[0].length <= 40 && lines[0].last() !in ".,:;!?)"
        if (titled) Column { Label(lines[0], size = 15, weight = FontWeight.SemiBold); Label(reflow(lines.drop(1)), size = 14, lines = Int.MAX_VALUE) }
        else Label(reflow(lines), size = 14, lines = Int.MAX_VALUE)
    }
}
/** Joins a line to the next unless it ends a sentence or a list entry, or ends with an address. */
private fun reflow(lines: List<String>) = buildString {
    for ((i, line) in lines.withIndex()) {
        append(line)
        if (i == lines.lastIndex) break
        val ends = line.last() in ".:;!?)" || line.substringAfterLast(' ').startsWith("http")
        append(if (ends) "\n" else " ")
    }
}
@Composable private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colorResource(R.color.sheet)), content = content)
}
@Composable private fun Rule() {
    Spacer(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(colorResource(R.color.rule)))
}
@Composable private fun RowScope.RowTitle(title: String, note: String?) {
    Column(Modifier.weight(1f)) {
        Label(title, weight = FontWeight.Medium)
        if (note != null) Label(note, size = 13, muted = true, lines = 2)
    }
}
/** Android's spell checker page, which has no public action: Settings' own screen, else the keyboards'. */
private fun openSpellCheckers(context: Context) {
    val page = Intent().setClassName("com.android.settings", "com.android.settings.Settings\$SpellCheckersSettingsActivity")
    runCatching { context.startActivity(page) }.onFailure { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
}

@Composable private fun Entry(title: String, note: String? = null, value: String = "›", action: (() -> Unit)? = null) {
    val click = if (action != null) Modifier.clickable(role = Role.Button, onClick = action) else Modifier
    Row(Modifier.fillMaxWidth().then(click).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RowTitle(title, note)
        if (action != null && value.isNotEmpty()) Label(value, muted = true, size = if (value == "›") 22 else 15)
    }
}
@Composable private fun Toggle(title: String, note: String, on: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = change)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RowTitle(title, note)
        Box(Modifier.size(38.dp, 23.dp).background(colorResource(if (on) R.color.ink else R.color.modifier), RoundedCornerShape(50))
            .padding(3.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Spacer(Modifier.size(17.dp).background(colorResource(if (on) R.color.page else R.color.key), RoundedCornerShape(50)))
        }
    }
}
