# Hex for Android

A thin client for Hex: a floating button, always on screen, records you,
sends the audio to a computer running Hex (Labs › Phone dictation), and types
the transcript into the focused text field (or copies it when none has
focus). A silent, minimum-priority notification keeps the button's service in
the foreground so battery savers don't stop it. No model runs on the phone. Protocol:
[`docs/phone-dictation.md`](../docs/phone-dictation.md).

```sh
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

Needs JDK 17+ and the Android SDK (`local.properties` with `sdk.dir`, or
`ANDROID_HOME`). Android 10 (API 29) and up.

`./gradlew assembleRelease` shrinks with R8 and signs with the key named by
the Gradle properties `hexReleaseStoreFile`, `hexReleaseStorePassword`,
`hexReleaseKeyAlias`, and `hexReleaseKeyPassword` (keep them in
`~/.gradle/gradle.properties`, never in the repo). Without them the release
APK is unsigned.

Setup is the app's one page: allow the microphone, turn on Hex dictation in
Accessibility, then connect a computer (found on the LAN, or by address for
Tailscale) with the pairing code Hex shows. Android 13+ blocks Accessibility
for apps installed outside a store until App info › ⋮ › Allow restricted
settings; the page says so.

| File | Role |
| --- | --- |
| `HexAccessibilityService.kt` | keeps the button on screen, records, sends, inserts at the cursor (paste as fallback) |
| `Foreground.kt` | the silent notification that keeps the service in the foreground |
| `BubbleView.kt` | the button: idle mark, level bars, Thinking pill, short messages |
| `Router.kt` | picks and caches the route (Tailscale first), probing all at once |
| `HexClient.kt` | hello and transcription requests |
| `Discovery.kt` | DNS-SD browse for `_hex-phone._tcp` |
| `Recorder.kt` | 16 kHz mono PCM16 capture and the WAV header |
| `MainActivity.kt` | the setup page |
