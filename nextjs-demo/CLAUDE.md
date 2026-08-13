# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

## What this is

A Next.js PWA that talks to Plaud recording hardware over Bluetooth. The app is wrapped in a
Capacitor native shell that loads the live Vercel deployment in a WebView and exposes Plaud's
precompiled native SDK to the web layer via a custom Capacitor plugin. **Both iOS and Android
are implemented, at deliberate parity** — one JS surface (`lib/plaud-sdk.ts`), two native
plugins with identical method names, event names, and payload shapes.

**Read `README.md` before making architectural changes** — it is the authoritative, detailed
description of the whole bridge (why the native shell exists, how the three vendor xcframeworks
and the one Android `.aar` are packaged, the non-obvious manual plugin-registration step on each
platform, the full JS↔native call chain, and a table of the few places the two platforms
genuinely differ). Do not duplicate its contents here; this file only adds what the README
doesn't cover.

## Commands

```bash
npm run dev      # start Next.js dev server
npm run build    # production build
npm run start    # run production build
npm run lint     # eslint
```

There is no test suite. To test native/Bluetooth behavior:

```bash
npx cap sync ios     # after any web/plugin/config change
npx cap open ios     # opens Xcode — build/run on a physical iPhone (frameworks are arm64 device-only, no Simulator)

npx cap sync android
npx cap open android                       # or: cd android && ./gradlew :app:assembleDebug
```

Android requires **JDK 21+** (Capacitor 8 compiles at `sourceCompatibility 21`); a system JDK 17
fails with `invalid source release: 21`. Android Studio's bundled JBR works:
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`.
Run on a physical device on both platforms — an emulator has no Bluetooth radio.

`capacitor.config.ts` points both shells at the deployed Vercel URL, not local bundled
assets — **web changes must be deployed to Vercel before they're visible on device.**
Native changes (the plugins, `MainViewController`, `MainActivity`) require an Xcode/Gradle
rebuild; a web deploy alone won't pick them up.

## Architecture

- `app/page.tsx` — the entire demo flow: mint a per-user JWT → `initSDK` → `startScan` →
  `connectBleDevice` → `getFileList` → `exportAudio`, plus a confirm-guarded `depair`
  (unpair) action. Also listens for device-initiated `recordStart`/`recordStop`/
  `recordPause`/`recordResume` events (recording is driven by the physical device, not the
  app) and refreshes the file list after a stop. Each successful `exportAudio` automatically
  feeds the upload + transcription flow below; `app/FileModal.tsx` renders playback +
  transcript state per session.
- `lib/plaud-sdk.ts` — typed wrapper around `registerPlugin<PlaudSdkPlugin>("PlaudSdk")`,
  shared by both platforms. Outside a Capacitor native shell these calls reject with "not
  implemented" — guard native-only calls with `Capacitor.isNativePlatform()` (never a
  platform-specific check; the plugin surface is the same on iOS and Android). Because the WebView loads a
  **remote** origin (the Vercel URL), plain browser `fetch()` can't read exported files or
  PUT to S3 presigned URLs (cross-origin/CORS). Two extra plugin methods route around
  this: `readFile` (reads an exported file's bytes through native code instead of
  `fetch(Capacitor.convertFileSrc(...))`) and `putBinary` (PUTs bytes to a URL via a native
  request instead of `fetch(url, {method:"PUT"})`, so the `ETag` response header is
  actually readable). Use the `readExportedFile()` / `putBinaryNative()` helpers exported
  from this file rather than calling `fetch` directly.
- `lib/plaud-auth.ts` + `app/api/user-token/route.ts` — server-side two-step OAuth token
  mint (partner token via `PLAUD_CLIENT_ID`/`PLAUD_SECRET_KEY`, then per-user token).
  Requires the Node.js runtime (uses `Buffer`). Also exports the shared `BASE_URL`/
  `requireEnv` used by the transcription helpers below.
- `app/transcription-runner.ts` + `lib/plaud-transcription.ts` + `app/api/transcription/*/route.ts` —
  after `exportAudio`, the exported mp3's bytes (read via `readExportedFile`, native-bridge
  only — see above) are pushed through Plaud's File Upload API (presigned S3 multipart,
  parts PUT via `putBinaryNative`) to get a public download URL, then through the
  Transcription API (submit + poll). The API routes exist so the two credential types never
  reach the client: file upload reuses the per-user Bearer `access_token`; transcription
  submit/status use partner credentials (`X-Client-Id`/`X-Client-Api-Key`, from
  `PLAUD_CLIENT_ID`/`PLAUD_API_KEY`) that must stay server-side. `transcription-runner.ts` is
  the client-side orchestrator (`"use client"`, drives the presign → upload → complete →
  submit → poll sequence) — it lives under `app/`, not `lib/`, to keep `lib/` reserved for
  server-only code (`plaud-auth.ts`, `plaud-transcription.ts`); don't move client orchestration
  logic back into `lib/`.
- `ios/PlaudPlugin/` — local SwiftPM package bridging the vendor SDK to the WebView.
  `Sources/PlaudPlugin/PlaudSdkPlugin.swift` is the `CAPPlugin`/`CAPBridgedPlugin` bridge
  class; `Frameworks/*.xcframework` are the three precompiled Plaud SDKs
  (`PlaudDeviceBasicSDK`, `PlaudBleSDK`, `PlaudWiFiSDK`).
- `ios/App/App/MainViewController.swift` — manually registers the local `PlaudSdk` plugin
  instance in `capacitorDidLoad()`, since Capacitor 8 only auto-registers npm-installed
  plugins, not local SwiftPM packages.
- `ios-sdk-reference.md` — generated reference for the vendor SDK; it can drift, so verify
  real signatures against the `.swiftinterface` files under each `*.framework/Modules/`
  when adding plugin methods.
- `android/` — the Android shell. `app/libs/plaud-sdk.aar` is the vendored SDK;
  `app/src/main/java/ai/plaud/pwademo/PlaudSdkPlugin.java` is the bridge class (the Swift
  plugin's counterpart) and `MainActivity.java` registers it. Two Android-only traps: the
  `.aar` has no dependency metadata, so its transitive deps are declared by hand in
  `app/build.gradle` (a missing one is a *runtime* `NoClassDefFoundError`, not a build error);
  and `android/.gitignore` needs its `!app/libs/plaud-sdk.aar` negation or the vendored SDK
  silently isn't committed. There is no published Android SDK doc — the AAR's bytecode
  (`javap`) and its `proguard.txt` are the reference. It's partly obfuscated: `sdk.*` and
  `com.tinnotech.penblesdk.*` are real API, `process_item_data`-style names are internals.
- `plaud-design-system/` — the design tokens/CSS (`colors_and_type.css`) and UI kit
  reference this app's styling is built from; check it before hand-rolling new colors or
  type styles.

## Extending the native plugin

When adding a Plaud device feature, mirror the existing pattern end to end **on both
platforms**: add the `CAPPluginMethod`/`@objc func` in `PlaudSdkPlugin.swift` *and* the
`@PluginMethod` in `PlaudSdkPlugin.java`, forward any SDK delegate/listener callbacks via
`notifyListeners` (`emit` on Android), and add the matching method/listener types in
`lib/plaud-sdk.ts`. Parity is maintained by hand, so a method added on one platform only
rejects with "not implemented" on the other; if an asymmetry is genuinely unavoidable, record
it in README §2.5 and on the TS type rather than papering over it with invented values.
Changing or adding plugin methods changes the native binary — it needs an Xcode/Gradle rebuild
and redeploy to a physical device, not just a Vercel deploy.

## Notes

- `ios/**/.build/` is build output; ignore it unless specifically debugging a native build
  failure.
- Never commit `.env` (holds `PLAUD_CLIENT_ID`/`PLAUD_SECRET_KEY`/`PLAUD_API_KEY`).
