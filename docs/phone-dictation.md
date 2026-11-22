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
model's own rate; other WAVs are converted.

```ts
200 {"transcript": "…", "durationMs": 4048}    trimmed transcript after Global's Output steps; no History or paste
401 wrong-code       411 length-required        413 resource-exhausted
415 unsupported-audio 400 invalid-audio          408 upload-timeout
409 model-not-ready  429 busy / queue-full       503 service-gone
```

A clip up to 30 s runs on the dictation engine's loaded model when it is the
selected one; otherwise the transcription service loads its own.

The transcript then goes through the same Output steps as a desktop
dictation, in the same order on each OS (`phone::process`): Labs' Hindi in
Latin letters, spoken formatting, spoken symbols, and filler words, then
replacements, the OpenCode rewrite, and transformations. No desktop app or
page is behind the phone's text field, so the Global mode applies, never an
app or website mode. A step that fails or misses its deadline keeps the text
before it, as on the desktop.

The phone sends no language, so the computer's model and language decide
what is heard. For Hindi typed in Latin letters, set this computer's
language to Hindi (a model that writes Hindi, such as Qwen3-ASR 0.6B) and
turn on Labs › Hindi in Latin letters.

`GET /phone/v1/words`: the words the phone's keyboard starts from (Hex for
Android › Keyboard › Learn from Hex history, on by default, fetched at most
every six hours). Counts of each word and each pair of neighbouring words in
this computer's History, within its retention, never a dictation itself
(`phone_words`). Spellings are as said mid-sentence; pairs are lowercase,
never cross a sentence or clause mark, and are sent only when seen twice.

```json
{"words": [["Vaibhav", 12], ["the", 9]], "pairs": [["the", "build", 3]]}
```

At most 5,000 words and 10,000 pairs, commonest first. The keyboard keeps
them on the phone, beside the words you type there (`LearnedWords`), and
Forget learned words clears both.

## Security

Anyone holding the code on a reachable network can transcribe with this
computer. Tailscale carries the audio over WireGuard; on Wi-Fi it is plain
HTTP that others on the network can read. New code (Settings) invalidates
every paired phone.
