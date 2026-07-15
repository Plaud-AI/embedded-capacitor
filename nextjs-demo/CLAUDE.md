# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

## What this is

A Next.js PWA that talks to Plaud recording hardware over Bluetooth. iOS has no Web
Bluetooth, so the app is wrapped in a Capacitor native shell that loads the live Vercel
deployment in a `WKWebView` and exposes Plaud's precompiled iOS SDK to the web layer via a
custom Capacitor plugin. **Read `README.md` before making architectural changes** — it is
the authoritative, detailed description of the whole bridge (why the native shell exists,
how the three vendor xcframeworks are packaged, the non-obvious manual plugin-registration
step, and the full JS↔native call chain). Do not duplicate its contents here; this file
only adds what the README doesn't cover.

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
```

`capacitor.config.ts` points the iOS shell at the deployed Vercel URL, not local bundled
assets — **web changes must be deployed to Vercel before they're visible on device.**
Swift changes (the plugin, `MainViewController`) require an Xcode rebuild; a web deploy
alone won't pick them up.

## Architecture

- `app/page.tsx` — the entire demo flow: mint a per-user JWT → `initSDK` → `startScan` →
  `connectBleDevice` → `getFileList` → `exportAudio`, plus a confirm-guarded `depair`
  (unpair) action. Also listens for device-initiated `recordStart`/`recordStop`/
  `recordPause`/`recordResume` events (recording is driven by the physical device, not the
  app) and refreshes the file list after a stop. Each successful `exportAudio` automatically
  feeds the upload + transcription flow below; `app/FileModal.tsx` renders playback +
  transcript state per session.
- `lib/plaud-sdk.ts` — typed wrapper around `registerPlugin<PlaudSdkPlugin>("PlaudSdk")`.
  Outside the Capacitor iOS shell these calls reject with "not implemented" — guard
  native-only calls with `Capacitor.isNativePlatform()`. Because the WebView loads a
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
- `plaud-design-system/` — the design tokens/CSS (`colors_and_type.css`) and UI kit
  reference this app's styling is built from; check it before hand-rolling new colors or
  type styles.

## Extending the native plugin

When adding a Plaud device feature, mirror the existing pattern end to end: add the
`CAPPluginMethod`/`@objc func` in `PlaudSdkPlugin.swift`, forward any SDK delegate
callbacks via `notifyListeners`, and add the matching method/listener types in
`lib/plaud-sdk.ts`. Changing or adding plugin methods changes the native binary — it needs
an Xcode rebuild and redeploy to a physical device, not just a Vercel deploy.

## Notes

- `ios/**/.build/` is build output; ignore it unless specifically debugging a native build
  failure.
- Never commit `.env` (holds `PLAUD_CLIENT_ID`/`PLAUD_SECRET_KEY`/`PLAUD_API_KEY`).
