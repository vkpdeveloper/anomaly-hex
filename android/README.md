# Hex for Android

A keyboard and a floating button that dictate with Hex on your computer. The phone records; the paired computer transcribes with its own model, runs its Output steps, and sends the text back to the cursor. Tap the mic at the strip's end for hands-free dictation, which ends once you stop talking, or hold it and let go. ✕ discards a recording or a transcript still on its way.

## Dictation and privacy

Pair once under **Computer**: Hex finds the computer on the local network, or takes an address (Tailscale works anywhere), then its pairing code. The protocol, routes and limits are in [docs/phone-dictation.md](../docs/phone-dictation.md). The computer turns on **Labs › Phone dictation** and answers only private and Tailscale addresses.

What leaves the phone: each recording, to the paired computer only; with **Learn from Hex history** (on by default), the word counts the keyboard fetches from that computer every few hours; and with **Keyboard › Fix button** on, a one-time download of its files from Hugging Face. Nothing goes to a cloud speech service, and Hex has no telemetry. Typed words, learned words, emoji picks and pinned clips stay on the phone.

A transcript goes back to the field it was spoken in. If that field went away while the computer was transcribing (another field, or the keyboard closed), it's copied instead and the strip's chip offers it; an app that restarts the same field (chat apps do) still gets it. The keyboard shapes it with the language the computer says it heard: mid-sentence, a capital the model gave an ordinary first word goes. When the computer chooses the language itself, the text stays as it came.

**Keyboard › Keyboard layout** is Automatic (the first supported Android system language, else English) or English, Español, Deutsch, Norsk, Français, Italiano or Hinglish.

## Typing assistance

Every layout has a bundled, memory-mapped **HXTM v3** word and word-pair model. English and Spanish keep the original iOS Google Books files. German uses Google Books; Bokmål uses the National Library of Norway’s book counts filtered by Norsk ordbank. Hinglish is English’s model with Hindi written in Latin letters mixed in from the PHINC corpus, so “kya”, “nahi” and “yaar” tap and glide as English words do. Start typing to see ranked suggestions; tap one to insert it. A space brings up next-word predictions. Passwords, addresses and editors that disable suggestions use literal typing.

Spanish has a dedicated ñ and ¿/¡ on the second symbols page. German is QWERTZ with ü, ö and ä; hold s for ß. Bokmål has å, ø and æ. Hinglish uses English’s keys and spelling rules; no phone language picks it, so choose it in Keyboard layout.

**Keyboard › Suggest swear words** is off by default on every layout. Slurs on the
[curated list](app/src/main/assets/typing/never-suggest.tsv) are never offered, even when
swear words are enabled. The filter applies to completions, next words, autocorrect and
both halves of a missed-space correction. Typing a listed word in full keeps your exact
spelling; its literal chip accepts what you typed. The filter never changes the HXTM models.

**Your spellings.** Hindi in Latin letters has no one spelling: nahi, nhi and nahin; acha, accha
and achha; theek and thik; kya and kia. Once you write one spelling of a word at least three times,
and four times as often as another, the strip, autocorrect, next-word predictions and glides give
yours: typing "nahi" offers "nhi", and a slip aimed at "nahi" becomes "nhi". A spelling you type that
is a word is only offered another, never replaced. British and American English spellings ("colour",
"realise", "centre") work the same way on the English and Hinglish layouts. The
[rules](app/src/main/java/ly/anoma/hex/typing/Spellings.kt) are narrow so two words never merge: main
and mein, kya and kiya, bhi and bhai, hai and hain stay apart, and a word English's model has
("sheet") is never read as Hindi. It counts what the keyboard already learns, so password fields and
apps that ask for no personalised learning teach it nothing, and **Forget learned words** clears it.
There is no setting.

**Spell checker.** Hex is also the phone's spell checker once you choose it (**Keyboard › Spell
checker** opens Android's page for it). [HexSpellChecker](app/src/main/java/ly/anoma/hex/keyboard/HexSpellChecker.kt)
underlines a word the layout's language doesn't know, your personal dictionary and learned words
included, so Hinglish isn't marked wrong in Hinglish. It asks the app for no pop-up: put the caret in
the word and its fix shows in the strip's middle slot, with other spellings beside it, as Gboard does.
A word already in the text is read as each key tapped at its centre, so swapped or slipped letters
still find it ("wrods" -> "words"). Addresses, handles, acronyms and camelCase are left alone. With
**Keyboard › Fix button** on (its files downloaded), on English and Hinglish, it also runs the fix
button's checker over each sentence: a real word the sentence says should be another ("form" for
"from") gets Android's grammar underline, and with the caret in it the strip offers the fix in the
middle. The two share one copy of the checker, one check at a time. Apps
built with Compose (Google Messages) don't ask for spell checks.

**Emoji.** On the English and Hinglish layouts the strip shows up to two emoji for the word being
typed, or the word just before a space, in the third word's place, as Gboard does ("medicine" 💊 💉,
"chai" ☕ 🫖). English's come from Unicode CLDR 46's emoji names and keywords
([en.emoji](app/src/main/assets/typing/en.emoji)), with Hex's own picks for chat words first;
Hinglish adds Hex's list of Hindi words ([hien.emoji](app/src/main/assets/typing/hien.emoji)). A tap
ends the word, then types the emoji and a space. The emoji you pick for a word come first next time,
most picked first; one picked from the emoji panel right after a word shows for that word once
picked twice. Picks are counts per word (at most 1,000 words), never learned in password fields or
apps that ask for no personalised learning, and **Forget learned words** clears them.

**Grid menu.** The grid at the strip's start opens a menu in place of the strip and keys:
**Clipboard**, **Fix mistakes** (only where the Fix button works) and **Settings**.

**Text shortcuts** from Android's personal dictionary put their phrase in the strip, as Gboard does;
tap it to replace the shortcut. Space, marks and return leave the shortcut as typed.

**Email addresses.** While you type one, the strip offers addresses that complete it: the phone's
accounts that Android shows Hex without a permission, addresses you've typed before, and, with
**Keyboard › Suggest contacts' emails** (off by default; it asks for the Contacts permission), your
contacts'. An address is learned once a space, comma, semicolon or return follows it, or you leave the
field, and never in password fields, URL fields or apps that ask for no personalised learning. Email
fields offer up to three from the first letter, before the strip's words, and a tap puts one in with
no space after it. Elsewhere one address takes the strip's last slot once three characters match, and
contacts' only after an @. Contacts are read in the background, at most 5,000, and again a moment
after they change. Typed addresses stay on the phone with the learned words.

**Keyboard › Fix button** is off by default. Turning it on downloads tinyfix's model and word
lists (3.5 MB, MIT, pinned to one Hugging Face revision and checked by size and SHA-256) into
the app's files; the APK ships none of it, and turning it off deletes them. The grid menu then
shows **Fix mistakes** on the English and Hinglish layouts (tinyfix knows no other language). On Hinglish it leaves Hindi words alone: those Hinglish's typing model has and
English's lacks, so "nahi" never becomes "nazi"; a Hindi word that is also English ("main") is
still checked as English. Tapping it checks up to 1,000 characters on each
side of the caret with a [Kotlin port](app/src/main/java/ly/anoma/hex/fix/) of tinyfix 0.1.1
(misspellings, and real words that don't fit, such as "form" for "from") and rewrites the
fixed words in one batch, keeping the caret. **Undo fixes** puts them back while the field is
unchanged, and those words are left alone until the keyboard restarts. Password fields never
show the tile. `TinyfixTest.fixesAsThePackageDoes` compares the port with the npm package's
output on 55 sentences when `HEX_TINYFIX_DIR` points at the downloaded files. Each checked word
costs one model run (about 3 ms on a Pixel emulator), and the first tap also reads the files
(0.7 to 1.6 s); another tap waits until the check finishes.

On space, the Kotlin decoder compares each tap with the measured key centres and combines those distances with the previous word’s probability. It can recover a missing space. A known spelling requires a higher confidence margin than a nonword. Immediately press **Delete** to restore exactly what you typed, without the space. Undo the same replacement twice and Hex keeps that spelling in later sessions. Only rejection counts keyed by hashes are saved locally; there is no typing journal.

**Keyboard › Glide typing** (on by default) types a word when you slide across its letters. A drag that starts on a letter key becomes a glide once it has moved about half a key (a key and a half right after a tapped letter, so a quick tap that slides stays a tap); passwords, numbers, addresses and the symbol pages take none. On lifting, [GlideDecoder](app/src/main/java/ly/anoma/hex/typing/GlideDecoder.kt) walks the same HXTM word list along the path, matching each letter in order to a point where the path passes its key and charging corners no letter explains, then adds the word-pair cost after the previous word; your personal dictionary is walked too. The likeliest word goes in, after a space when it follows a word, capitalised with shift and in capitals with caps lock; the strip shows it highlighted with two other readings, and tapping one replaces it. Delete right after a glide removes the whole word. A mark such as a comma goes against the glided word, while the next word, glided or tapped, puts its space in first. **Show gesture trail** draws a line that follows the finger and fades.

**Keyboard settings and Hex menu → Typing feedback** control key pop-ups and key haptics independently. Both default on; Hex follows system keyboard vibration. Hold a letter, slide to an accent, and release. Turning off pop-ups leaves the deliberate accent picker available.

[English/Spanish typing benchmark, model sources and licences](benchmark/typing/README.md)

## Clipboard

The grid menu's Clipboard tile opens the clipboard in place of the strip and keys, at their height: recent screenshots in a row, then copies in two columns, pinned first and newest first. A tap pastes a clip or puts an image in and goes back to the keys; touch and hold one for Pin and Delete (TalkBack has both as actions); Clear removes everything not pinned. The strip's chip offers the last copy for 10 minutes, or a screenshot taken in the last 5 minutes, whichever is newer, in place of next-word guesses. An image (a screenshot or a copied picture) is offered only in a field that takes images (`EditorInfo.contentMimeTypes`), as Gboard does. It goes when it is tapped, when a word is typed, or once it is too old, and the clipboard keeps it.

[ClipKeeper](app/src/main/java/ly/anoma/hex/keyboard/ClipKeeper.kt) watches the clipboard while the keyboard runs, so copies made in other apps join too. Unpinned clips go an hour after they were copied, as Gboard's do: the clipboard carries one-time codes, addresses and things copied in passing, and an hour covers copying here to paste there without the keyboard keeping a record. At most 20 stay, 5 of them images; text over 50,000 characters pastes from the chip but isn't kept. Up to 50 pinned clips stay until unpinned (which starts their hour) or deleted, and only they are written to disk: `clipboard/pinned.txt` and the pinned images beside it, in Hex's private files (owner-only, never backed up). Recent clips live in memory and go when the keyboard's process ends. A copy marked sensitive (`EXTRA_IS_SENSITIVE`, as password managers mark theirs) or made in a password field is never kept; the chip shows it masked.

A copied image is copied into that folder under its SHA-256 (16 MB at most), since the copying app's grant ends when the clipboard changes. Putting an image in calls `commitContent` with a read grant on a `FileProvider` URI when the editor's `contentMimeTypes` takes its type (Google Messages does); elsewhere the image goes on the clipboard and the strip says so.

**Keyboard › Recent screenshots** is off until turned on, and asks for photos (`READ_MEDIA_IMAGES`, or storage on Android 12). Android 14's "Select photos" grants only the chosen ones, which leaves out new screenshots; the row says so, and turning it on again asks again. While on, the keyboard reads MediaStore off the main thread when it comes up and when images change: at most 40 rows from the last hour, of which only screenshots (a `Screenshots/` folder) are offered, never camera photos or what other apps saved, such as a chat's downloads; the panel shows up to 8. A screenshot is copied into the cache for the editor and kept a day. Thumbnails are decoded on their own thread at 256 px and cached up to 8 MB.

`ClipHistoryTest` and `RecentImagesTest` cover expiry, pins, bounds, secret copies, the saved format and which photos are offered; no device has run it yet.

## Autofill

Saved passwords and usernames from the phone's autofill service, and a verification code it found in a text message, show as chips in the strip, as Gboard's do. They use Android's inline suggestions ([InlineAutofill](app/src/main/java/ly/anoma/hex/keyboard/InlineAutofill.kt)): the keyboard asks for chips the height of the clipboard chip in its own light or dark colours, the service draws them, and a tap is the service's to handle, which fills the field. Hex never sees the saved password or the message, reads no SMS and asks for no SMS permission. The chips take the strip's place, ahead of the clipboard chip and next-word guesses, with the service's pinned ones at the end by the mic; the grid button and the mic stay. A word being typed takes the strip back for its suggestions, and the chips return once it is done; they go when the field changes or the service sends none. Without an autofill service, nothing changes. `InlineChipsTest` covers the order and when they show or go.

## Settings

After setup, Home shows **Ready** or **Finish setup**, then **Computer** (pairing), **Keyboard** (layout, autocorrect, the Fix button, suggestions, key pop-ups, haptics, glide typing, recent screenshots, Learn from Hex history and a practice field) and **Privacy**. **Floating button** dictates into any app without the keyboard, and can hide while the Hex keyboard is up.

**Privacy › Privacy policy** opens the [Android policy text](play/policy/privacy-policy.txt) even in airplane mode.

## Phase 4 verification

On identical held-out synthetic taps and **unchanged English and Spanish iOS models**, Android’s English word error rate fell from 10.53% to **7.40%**, against **8.39%** for iOS R1 without offsets. Spanish was **10.33%** against **10.43%** for iOS (phase 3 Android: 10.06%). The [step-by-step benchmark](benchmark/typing/README.md#where-the-298-english-errors-came-from) explains the original 298 extra English errors; [the replay table](benchmark/typing/phase4.tsv) gives raw counts, exact sentences and timing.

The Studio verified Spanish, German and Bokmål suggestions and space-triggered corrections in a separate Android app, including **¿¡**, **ß** and **æøå**. The German and Bokmål assets were also copied, byte-for-byte, into the iOS typing-model folder in local commit `230a437e`. The production Swift reader checked all 95,000 words per language and every stored pair score; no iOS source or existing English/Spanish model changed.

With airplane mode on and Wi-Fi off, fixed audio went through the emulator microphone and Whisper small into that editor. Spanish **“Nos vemos mañana en la estación”** and German **“Wir treffen uns morgen am Bahnhof”** matched. Bokmål **“Tusen takk for hjelpen”** also matched; on corpus clip 40 Hex inserted **“Biblioteket stenger tydelig på lørdag”** instead of **“Biblioteket stenger tidlig på lørdag.”** The same Spanish clip also produced a transcript from Home’s Dictate bar.

Five unit tests passed in each of the debug and release variants. Home, the four settings pages and all four detail pages were captured in light and dark. Deselecting Hex as the current keyboard changed **Ready** to **Finish setup · 1 step**; selecting it again through setup restored **Ready**. The local APK, full-size screenshots, replay results and Studio log are under `build/phase4-proof/`.

## How it works

- **App:** Kotlin and Jetpack Compose. Welcome → microphone permission and Android’s keyboard settings/picker → a real practice field. Setup reads live system state; it remembers the page, not the words.
- **Keyboard:** [HexKeyboard](app/src/main/java/ly/anoma/hex/keyboard/HexKeyboard.kt) is an `InputMethodService` with native key views. Letters, shift/caps lock, symbols, delete, editor actions and next keyboard use Android input APIs. It commits through the active `InputConnection`; changing editors or hiding the keyboard discards pending results. Password fields allow typing only. Typing lives in [KeyboardEngine](app/src/main/java/ly/anoma/hex/keyboard/KeyboardEngine.kt), which reaches the editor only through `KeyboardTextTarget`; the views are `KeyboardView` (strip, keys or Hex menu), `StripView` and `KeyPlaneView`; each layout's letters are data in `KeyboardLetters`.
- **Audio:** [MicCapture](app/src/main/java/ly/anoma/hex/speech/MicCapture.kt) records up to 60 s of mono 16 kHz PCM in RAM and releases the microphone before CPU inference. The [recognizer interface](app/src/main/java/ly/anoma/hex/speech/Whisper.kt) operates on that capture and its language/task. Hex requests no audio focus and does not change playback routing.
- **Type:** unmodified [InterVariable.ttf](app/src/main/res/font/inter_variable.ttf) from [Rasmus Andersson and the Inter Project, v4.1](https://github.com/rsms/inter/releases/tag/v4.1), licensed under [SIL OFL 1.1](app/src/main/assets/licenses/inter-ofl.txt). One [Compose font family and native TextView helper](app/src/main/java/ly/anoma/hex/HexType.kt) cover Hex’s text, including keys, menus, strip labels and tabular digits (`tnum`). Android supplies missing glyphs; permission dialogs and the system keyboard picker use the OS font. Gradle verifies the bundled TTF’s SHA-256.
- **Licences:** OpenAI Whisper weights and whisper.cpp/ggml are MIT. Kotlin/AndroidX are Apache 2.0; libc++ is Apache 2.0 with LLVM exceptions. [Bundled credits and licence texts](app/src/main/assets/licenses/). No ML Kit, Parakeet or third-party language packs are shipped.

## Build and run

Use the Studio’s JDK 21, SDK platform 36, NDK 27.2.12479018 and CMake 3.22.1. The Play packaging checks use Build Tools 36.0.0 and bundletool 1.18.3. From the synced `android/` directory, with an ARM64 emulator or phone running:

```sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
./gradlew test
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ly.anoma.hex/.MainActivity
```

On the headless Studio, start an ARM64 AVD with `-audio none -grpc 8566 -grpc-use-token`. [inject.py](benchmark/inject.py) uses the matching emulator discovery record and streams paced mono PCM16 into the guest microphone. Generate the bindings from the SDK emulator’s `lib/emulator_controller.proto` with `grpcio-tools`, then put them on `PYTHONPATH`. No recording or transcript is injected directly into the application.

For the upload bundle, run `./gradlew :app:bundleRelease`. Release packaging requires a private properties file at `~/.config/hex-android/keystore.properties` or the path in `HEX_ANDROID_KEYSTORE_PROPERTIES`. It must contain `storeFile` (relative to that file, or absolute), `keyAlias`, `storePassword` and `keyPassword`. Release uses R8 and resource shrinking and never falls back to the debug key. Gradle writes `app/build/outputs/bundle/release/app-release.aab`.

**Scope:** Android 12+ (min API 31), target 36, ARM64, four typing models and four dictation languages. See the [Play release evidence, listing drafts, phone screenshots and real-device checklist](play/README.md). The Studio also exercised offline fallback, all four styles, per-dictation translation, both appearances and insertion into a separate app with music playing in earlier phases.
