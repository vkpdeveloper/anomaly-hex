# Hex for Android

A thin client for Hex: a floating button over any focused text field records
you, sends the audio to a computer running Hex (Labs › Phone dictation), and
types the transcript into the field. No model runs on the phone. Protocol:
[`docs/phone-dictation.md`](../docs/phone-dictation.md).

```sh
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

Needs JDK 17+ and the Android SDK (`local.properties` with `sdk.dir`, or
`ANDROID_HOME`). Android 10 (API 29) and up.

Setup is the app's one page: allow the microphone, turn on Hex dictation in
Accessibility, then connect a computer (found on the LAN, or by address for
Tailscale) with the pairing code Hex shows. Android 13+ blocks Accessibility
for apps installed outside a store until App info › ⋮ › Allow restricted
settings; the page says so.

| File | Role |
| --- | --- |
| `HexAccessibilityService.kt` | shows the button over a focused editable field, records, sends, inserts at the cursor (paste as fallback) |
| `BubbleView.kt` | the button: idle mark, level bars, Thinking pill, short messages |
| `Router.kt` | picks and caches the route (Tailscale first), probing all at once |
| `HexClient.kt` | hello and transcription requests |
| `Discovery.kt` | DNS-SD browse for `_hex-phone._tcp` |
| `Recorder.kt` | 16 kHz mono PCM16 capture and the WAV header |
| `MainActivity.kt` | the setup page |
