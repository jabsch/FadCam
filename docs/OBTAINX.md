# Updating FadCam with ObtainX

[ObtainX](https://github.com/LeDimigit/ObtainX) installs and updates apps straight from this repo's GitHub releases. Nothing builds or publishes automatically: you build and sign the APKs yourself, upload them to a release, and ObtainX picks them up from there.

## 1. Publish a release (on your computer)

1. Set `versionName` / `versionNameSuffix` in `app/build.gradle.kts`.
2. Build with your release keystore configured in `local.properties` (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`):
   - Stable: `./gradlew assembleDefaultRelease` → `app/build/outputs/apk/default/release/JaCam_v<version>-arm64-v8a.apk`
   - Beta: `./gradlew assembleDefaultDebug` → `JaCam_v<version>-beta<N>-arm64-v8a.apk` (package `com.jacam.beta`, installs alongside stable and alongside upstream FadCam; signed with your machine's debug key, so always build betas on the same machine)
3. On GitHub, create a release in `jabsch/FadCam`:
   - Tag: `v` plus the exact versionName, e.g. `v4.0.0` or `v4.0.0-beta10.6`. The in-app update checker reads the same tags.
   - Tick **Set as a pre-release** for betas.
   - Attach the `-arm64-v8a.apk` file. Pixel 11 phones are 64-bit only, so that is the one to ship. The `-universal` APK is optional.

Always sign with the same keystore. Android refuses an update signed with a different key.

## 2. Add FadCam to ObtainX (on the phone)

**Stable** (`com.jacam`):

| Field | Value |
|---|---|
| App source URL | `https://github.com/jabsch/FadCam` |
| Include prereleases | Off |
| Filter APKs by regular expression | `^JaCam_v\d+\.\d+\.\d+-arm64-v8a\.apk$` |
| Trim version string with RegEx | `\d+\.\d+\.\d+` |
| Verify the 'latest' tag | On |

**Beta** (`com.jacam.beta`), as a second app entry:

| Field | Value |
|---|---|
| App source URL | `https://github.com/jabsch/FadCam` |
| Include prereleases | On |
| Filter APKs by regular expression | `-beta[\d.]+-arm64-v8a\.apk$` |
| Trim version string with RegEx | `\d+\.\d+\.\d+-beta[\d.]+` |
| Verify the 'latest' tag | Off (it can't be combined with prereleases) |

Leave **GitHub Build Verification** off: releases built on your machine have no GitHub attestations.

If you track FadCam with ObtainX, you can turn off the app's own update check under Settings → App to avoid double notifications.
