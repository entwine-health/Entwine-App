# Entwine · Lucy — Android patient app

Lucy is a Hebrew-first voice companion for people living with Parkinson's disease.
This repository holds the **source of the Android app** — published so that anyone can
verify **exactly what the app records and where it sends it**. Nothing about your data
has to be taken on trust; it is in the code.

> This is a **read-only transparency mirror**, generated automatically from Entwine's
> internal monorepo at each app release. It is **not open to contributions** — see
> [CONTRIBUTING](CONTRIBUTING.md). Issues and pull requests are not monitored.

## What the app does with your voice and data

- **Your microphone audio is not recorded to disk.** Audio exists in memory only for the
  moment it is turned into text, then it is discarded — every single turn. (The one
  exception is a feedback clip you deliberately choose to send.)
- **The AI runs on Entwine's own server, not a third-party AI service.** Your words are
  processed in-house.
- **Traffic is encrypted** end-to-end (TLS/WSS).
- You can **stop** or **erase everything** at any time; data auto-deletes after the pilot.

## Security posture — the app holds no keys

**This app contains no server credentials, API keys, or shared secrets.** Search the
source: the only server values baked in are the public endpoint URLs. The app can only
talk to the backend using a **per-device token that the server issues at runtime**, after
enrollment, and which the operator can revoke instantly. Possessing this source — or the
APK — grants no access to Entwine's backend or to anyone's data.

## Build

Standard Android project (Kotlin + Jetpack Compose, min SDK 31). With Android Studio,
or from the command line:

```bash
./gradlew :app:assembleDebug     # debug APK → app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest # unit tests
```

The debug build points at a local dev server (`10.0.2.2`, the Android emulator's host
loopback); the release build points at the production edge. Release signing uses Entwine's
keystore (not in this repository) — an outside build produces an unsigned APK, which is
expected.

## License

[PolyForm Noncommercial License 1.0.0](LICENSE) — read, run, modify, and share for any
**noncommercial** purpose; commercial use is reserved to Entwine Health.
