# Phone dictation

Labs › Phone dictation lets Hex for Android (`android/`) dictate with this
computer's model. The phone records and the computer transcribes; no model
runs on the phone. Server: [`src/local_api/phone.rs`](../src/local_api/phone.rs).

## Finding the computer

```ts
Phone pairs once
├── LAN -> DNS-SD browse for `_hex-phone._tcp` (instance name: the computer's
│   name), then the code
└── Tailscale or anywhere else -> "Enter an address" (host or host:port), then the code
Hello -> the computer's routes, saved on the phone
Each dictation -> the route picked when a text field gained focus (cached 2 min,
    dropped on any network change), else probe every allowed route at once and
    take the first in order that answers:
    ├── Automatic -> Tailscale, then Wi-Fi (LAN)
    ├── Tailscale -> Tailscale routes only
    └── Wi-Fi     -> LAN routes only
    None answer -> browse the LAN for the computer by name for 2 s, then fail
A send to a cached route that fails to connect -> pick again once and resend
Any later failure (a read timeout, a reset after the upload) -> no resend: the
    computer may still be transcribing the first one
A recording that reaches the 16 MiB upload limit (about 8.7 minutes) -> stops
    and sends by itself
```

## API

Plain HTTP/1.1 on the port Settings shows (default 47213). Every request
carries `Authorization: Bearer <code>`: the pairing code, case, spaces,
and dashes ignored, `O` read as `0`, `I` and `L` as `1`. A missing or wrong
code answers `401 {"code":"wrong-code"}` after 400 ms. Peers outside
loopback, link-local, RFC 1918, and Tailscale's 100.64.0.0/10 are closed at
accept.

`GET /phone/v1/hello`

```json
{"app": "hex", "version": "2.4.0", "name": "studio", "port": 47213,
 "routes": [{"kind": "tailscale", "host": "100.101.102.103"},
            {"kind": "lan", "host": "192.168.1.20"}],
 "model": "Parakeet Unified English"}
```

`POST /phone/v1/transcriptions` with `Content-Type: audio/wav` and a
`Content-Length` up to 16 MiB. The phone sends 16 kHz mono PCM16, the
model's own rate; other WAVs are converted. Optional query parameters say
where it was spoken (`phone::origin`), each URL-encoded, at most 200
characters, and ignored when missing, repeated, or malformed:

```ts
?app=Slack                 the app in front on the phone, as its launcher names it
&package=com.Slack         its Android package, used when the name was unreadable
&language=en               the keyboard layout's language (Hinglish sends "hi");
                           ignored unless this computer lists it
```

```ts
200 {"transcript": "…", "durationMs": 4048, "language": "en"}
                     trimmed transcript after the app's mode's Output steps; no History or paste;
                     language is this computer's setting ("auto" when the model chooses)
401 wrong-code       411 length-required        413 resource-exhausted
415 unsupported-audio 400 invalid-audio          408 upload-timeout
409 model-not-ready  429 busy / queue-full       503 service-gone
```

A clip up to 30 s runs on the dictation engine's loaded model when it is the
selected one; otherwise the transcription service loads its own.

The transcript then goes through the same Output steps as a desktop
dictation, in the same order on each OS (`phone::process`): Labs' Hindi in
Latin letters, spoken formatting, spoken symbols, and filler words, then
replacements, the OpenCode rewrite, and transformations. The phone's `app`
(else its `package`) is read as the application name, by the same rule a
desktop app picks a mode: on macOS a mode's application matches the whole
name, ignoring case and `.app`; on Linux and Windows it matches part of the
name. So a mode set up for "Slack" here applies to Slack on the phone. No
app, or none a mode names, applies Global; no website mode ever applies. A
step that fails or misses its deadline keeps the text before it, as on the
desktop.

The phone's `language` never changes what is heard: the computer's model
and language decide that, as for every dictation here (a phone's guess
could force the wrong language, and would load a second copy of the
model). While this computer's language is "auto", the Output steps run with
the phone's language instead of "auto" (English drops filler words); a
language set here always wins. The reply's `language` is still this
computer's setting. The keyboard shapes the text (a capital the model gave the
first word mid-sentence) with the language the reply names, and leaves the
text as it came when that is "auto" or missing. For Hindi typed in Latin letters, set this computer's
language to Hindi (a model that writes Hindi, such as Qwen3-ASR 0.6B) and
turn on Labs › Hindi in Latin letters.

`GET /phone/v1/words`: the words the phone's keyboard starts from (Hex for
Android › Keyboard › Learn from Hex history, on by default, fetched at most
every six hours). Counts of each word and each pair of neighbouring words in
this computer's History, within its retention, never a dictation itself
(`phone::words`). Spellings are as said mid-sentence; pairs are lowercase,
never cross a sentence or clause mark, and are sent only when seen twice.

```json
{"words": [["Vaibhav", 12], ["the", 9]], "pairs": [["the", "build", 3]],
 "vocabulary": ["Raunak", "OpenCode"]}
```

At most 5,000 words and 10,000 pairs, commonest first. `vocabulary` is this
computer's Vocabulary terms (Settings › Output, at most 500), so the names a
dictation here is biased toward are also words on the phone; an older phone
ignores the field and an older computer leaves it out. A term of several
words becomes one word each. The keyboard keeps all of it on the phone,
beside the words you type there (`LearnedWords`), and Forget learned words
clears it.

`POST /phone/v1/corrections` with `Content-Type: application/json` and a
body up to 64 KiB: what the person fixed in text the keyboard dictated, so
the next dictation gets those words right (`phone::corrections`).

```json
{"baseline": "Note: hi rowan ak how are you",
 "inserted": "hi rowan ak how are you",
 "edited": "Note: hi Raunak how are you"}
```

`baseline` is the field's text around the insertion as dictated, `inserted`
the dictated words, `edited` the same text after the fix. The computer
decides what the fix taught with the rule behind Labs › Learn from
corrections on macOS (`correction_terms::learned_terms`: only names and
casing the person put where dictated words were, at most five, none it
already has) and adds them to Vocabulary through the same save as that lab
(`correction_watch::save`), so Parakeet leans toward them from the next
dictation. Labs › Phone dictation is the opt-in: Learn from corrections need
not be on, and it works on every OS Phone dictation does. The text is
compared and dropped, never logged or stored; the log says only how many
terms were learned.

```ts
200 {"learned": ["Raunak"]}   the terms added, [] when the fix taught nothing
400 invalid-body  401 wrong-code  411 length-required
413 resource-exhausted  415 unsupported-body
```

The keyboard (Settings › Keyboard › Learn from my corrections, on by
default) follows a dictation it inserted in a field that learns, never a
password, incognito, or no-personalised-learning field, until the field is
left, another dictation starts, or five minutes pass. It reads the field
once the person pauses editing, and sends only when the edits touch the
dictated words, with about 300 characters of context around them. It also
teaches its own `LearnedWords` the words the fix put there, so typing them
works at once, with or without a computer. A correction the computer can't
be reached for waits in memory (at most five, oldest dropped) and is sent at
the next contact; Forget learned words or turning the setting off drops
them. A computer without the route (404) is skipped. No audio is sent or
kept for this.

`GET /phone/v1/replacements`: Global's text replacements (Settings ›
Output), as the keyboard's text shortcuts, fetched at most hourly whenever
a computer is paired (`phone::replacements`). Phrases and outputs are
trimmed; one that writes nothing is left out. At most 2,000, in Settings'
order.

```json
{"replacements": [["brb", "be right back"], ["github", "GitHub"]]}
```

The strip reads one word at a time, so the keyboard keeps one-word phrases:
typing "brb" offers "be right back", as an Android personal-dictionary
shortcut does, and only a tap puts it in; your own shortcut wins one the
computer also has. A replacement that only changes capitals becomes your
word, so "github" takes "GitHub". Nothing is offered in password fields or
fields that ask for no personalised learning. They stay on the phone until
the computer is forgotten or another paired; Forget learned words leaves
them.

## Say "send it" to send

Hex for Android › Keyboard › Say "send it" to send (off by default): a
keyboard dictation whose last words are "send it" (or "bhej do", "भेज दो")
drops them and presses the field's send key. The phone decides
(`SpokenSend`), from the text the computer sent back, so any computer works.
The phrase must be the
whole dictation or follow a sentence's end or a marked pause ("See you at
six. Send it.", "On my way, send it"); "I'll send it" and "can you send
it" stay text, and "send" alone never sends. Only a field whose enter key
sends or goes (`IME_ACTION_SEND`, `IME_ACTION_GO`), never a password field;
the rest of the dictation goes in first, nothing is sent if it didn't, and
there is no undo. The floating button never sends.

## Security

Anyone holding the code on a reachable network can transcribe with this
computer. Tailscale carries the audio over WireGuard; on Wi-Fi it is plain
HTTP that others on the network can read. New code (Settings) invalidates
every paired phone.
