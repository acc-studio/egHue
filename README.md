# egHue

Fast, simple control for Philips Hue on Android. One tap toggles a room, zone or light, from the app or
from the simplest home-screen widget we could make. Everything talks to your Hue Bridge on the local
network; there is no account and no cloud.

The full product spec (with screenshots) is in [`docs/SPEC.md`](docs/SPEC.md).

## Install

Download `egHue-<version>-github.apk` from the latest [release](../../releases/latest) and open it on
your phone. After that, egHue keeps itself up to date (Settings › Updates).

## Build

Requirements: JDK 17 and the Android SDK (platform 35). Then:

```sh
./gradlew testGithubDebugUnitTest      # unit tests
./gradlew assembleGithubDebug          # debug APK -> app/build/outputs/apk/github/debug/
./gradlew assembleGithubRelease        # signed release APK (needs keystore.properties)
./gradlew bundlePlayRelease            # Google Play bundle
```

Flavors:

| Flavor   | Updates                                                          |
|----------|------------------------------------------------------------------|
| `github` | Self-updates from this repo's GitHub Releases (silent on Android 12+) |
| `play`   | Delivered by Google Play, with in-app update prompts             |

## Releasing an update

```sh
git tag v0.2.0 && git push origin v0.2.0
```

The `Release` workflow tests, builds and signs both flavors and publishes a GitHub Release. Installed
apps pick it up within a few hours (or immediately via Settings › Check for updates). Version numbers
come from the tag.

### Signing

Release builds are signed with the key in `keystore.properties` (local, git-ignored) or the
`EGHUE_KEYSTORE*` environment variables (CI, from the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` and
`KEY_ALIAS` repository secrets). **Back up the keystore.** Android only installs updates signed with
the same key, so if the key is lost, every user has to uninstall and reinstall.

## Developing without a Hue Bridge

`tools/fake_bridge.py` simulates a bridge (rooms, zones, colour and white lights, scenes, pairing and
the live event stream) over HTTPS:

```sh
python tools/fake_bridge.py --auto-press      # listens on :8443
```

In the Android emulator, choose **Enter IP address instead** and type `10.0.2.2:8443`.
