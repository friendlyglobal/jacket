# Releasing Jacket 1.0.0-rc.1

Your checklist for the first Play and GitHub release. Work top to bottom. Form answers are in
[`docs/play-store.md`](docs/play-store.md); listing text is in
`fastlane/metadata/android/en-US/`.

## 0. Before you start

- [ ] `app/build.gradle.kts` has `versionCode = 4` and `versionName = "1.0.0-rc.1"`.
- [ ] `fastlane/metadata/android/en-US/changelogs/4.txt` still matches what shipped.
- [ ] The known limitations in section 8 are still true. If not, remove them from
      `full_description.txt` and `changelogs/4.txt`.

## 1. Decide the final applicationId

It is currently `io.github.friendlyglobal.jacket`.

- Once an app is published on Play, its applicationId can never change. Changing it later
  means publishing a new app with a new listing (no installs, ratings or reviews carry over),
  and existing testers would need to install the new app and reinstall their books.
- To change it now, edit only `applicationId` in `app/build.gradle.kts`. The `namespace` and the
  Kotlin package (`io.github.friendlyglobal.jacket`) can stay as they are.
- A new applicationId installs as a separate app next to any existing test build.

## 2. Create an upload key

Create it outside the repository, for example in `~/keys`:

```sh
mkdir -p ~/keys && cd ~/keys
keytool -genkeypair -v -keystore jacket-upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload
```

- `keytool` comes with the JDK. If it isn't on your PATH, use Android Studio's:
  `"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"`.
- The keystore is PKCS12, which uses one password for the store and the key. Use the same
  value for both in step 3.
- Keep it out of git. Back up the `.jks` file and its password in a password manager, plus one
  offline copy.
- Why it matters: the GitHub APK is signed with this key, and Android only installs an update
  signed with the same key. If you lose it, GitHub users have to uninstall to update. On Play,
  Google holds the app signing key, and a lost upload key can be reset through Play Console.

## 3. Sign and build

Create `keystore.properties` in the repository root:

```properties
storeFile=/Users/you/keys/jacket-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

- An absolute `storeFile` path avoids any doubt about where a relative path is resolved from.
- [ ] Confirm git ignores it: `git check-ignore -v keystore.properties` must print a matching
      rule. If it prints nothing, add `keystore.properties` and `*.jks` to `.gitignore` before
      anything else.

Build:

```sh
./gradlew :app:bundleRelease     # app/build/outputs/bundle/release/app-release.aab (Play)
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk (GitHub)
```

Without `keystore.properties`, the release build falls back to the debug key, so test builds
keep installing over each other. Play rejects debug-signed bundles, so check the signature
before uploading:

```sh
keytool -list -v -keystore ~/keys/jacket-upload.jks -alias upload | grep 'SHA256:'
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab | grep 'SHA256:'
```

The two SHA-256 fingerprints must match. Also confirm there is no `INTERNET` permission:
`aapt2 dump permissions app/build/outputs/apk/release/app-release.apk`
(`aapt2` is in `$ANDROID_HOME/build-tools/<version>/`).

Signatures and existing installs:

- Earlier test APKs (`dist/`, 0.1.0 to 0.3.0) are debug-signed. Testers must uninstall them before
  installing the release-signed APK, and uninstalling deletes their library. Those builds have
  no backup export, so they will need to re-add their books.
- Play builds are signed with Google's app signing key, GitHub builds with your upload key. A
  phone cannot update one with the other; switching means uninstalling (export a backup first).

## 4. Host the privacy policy

Play needs the URL before you can finish App content.

1. Push `docs/privacy.html` to `main`.
2. On GitHub: Settings → Pages → Build and deployment → Source: **Deploy from a branch** →
   Branch: **main**, folder **/docs** → Save.
3. After a minute or two, open https://friendlyglobal.github.io/jacket/privacy.html on your phone.

Everything in `docs/` becomes public on that site, including `concept.html` and
`play-store.md`. Keep `PRIVACY.md` and `docs/privacy.html` in sync, and change the effective
date on both when the policy changes.

## 5. Play Console

1. **Create app:** name from `title.txt`, default language English (United States), App, Free.
2. **Play App Signing:** accept the Google-generated app signing key when you upload the first
   bundle. The *App integrity* page then shows both certificates.
3. **Internal testing first:** *Testing → Internal testing → Create new release*. Upload the
   AAB, name the release `1.0.0-rc.1 (4)`, paste `changelogs/4.txt` as release notes, add your
   testers' email list, roll out, and install through the opt-in link.
4. **Main store listing:** paste `title.txt`,
   `short_description.txt` and `full_description.txt`; upload the graphics from
   `fastlane/metadata/android/en-US/images/` (sizes in `docs/play-store.md`, section 5).
5. **Store settings:** category Books & Reference, contact email, website.
6. **App content:** privacy policy URL, ads (No), app access, content rating, target audience,
   Data safety, foreground service declaration, advertising ID, and the remaining
   declarations, all from `docs/play-store.md`.
7. **Production access:** if your developer account is a personal account created after
   13 November 2023, Play requires a closed test with at least 12 opted-in testers for 14
   consecutive days before you can apply for production. Internal testing does not count.
   Check the current numbers on the console dashboard.

## 6. GitHub release

- Tag `v1.0.0-rc.1`, mark it as a pre-release, and attach the APK renamed to
  `Jacket-1.0.0-rc.1.apk`.
- Release notes: `changelogs/4.txt`, plus the signature note from section 3 (uninstall earlier
  test builds first).
- Optional: list the APK's checksum (`shasum -a 256 Jacket-1.0.0-rc.1.apk`).
- Android developer verification: Google has started requiring, region by region, that apps
  installed outside Play on certified devices come from a verified developer with a registered
  package name and signing certificate. Check Play Console's developer verification page and
  register the package name and your upload certificate so the GitHub APK keeps installing.

## 7. Real-device test matrix

| Device | Check |
| --- | --- |
| Pixel Fold / Pixel 9 Pro Fold | Cover appears on the outer screen while you read inside; folding and unfolding keeps your place; two-page spread when unfolded; turning the cover off in Settings works. |
| Galaxy Z Fold | Outer-display support may differ from Pixel. Either the cover works or the option is hidden; reading and the two-page spread work. |
| Galaxy Z Flip | The *Jacket cover* widget can be added under Settings › Cover screen › Widgets, fills the Flex Window, and updates when you switch books. |
| A regular phone | No outer screen: outer-screen features hide gracefully; the home-screen widget works. |

On any device:

- [ ] Import each format (EPUB, PDF, CBZ, TXT) through Add books, Open with and Share.
- [ ] EPUB read-aloud keeps playing with the screen off for 10+ minutes.
- [ ] Lock-screen controls: play, pause, skip.
- [ ] Headphone button play/pause; unplugging wired or disconnecting Bluetooth headphones pauses.
- [ ] Sleep timer, both modes: a set time, and end of chapter.
- [ ] Volume keys turn pages when enabled; check what they do while read-aloud is playing.
- [ ] Export a backup, install fresh (uninstall, or a second device), import it, and check
      positions, bookmarks, highlights, notes and settings.
- [ ] Optional, to confirm the privacy policy's backup claims:
      `adb shell bmgr backupnow io.github.friendlyglobal.jacket`, uninstall, reinstall from
      the same source, and check that reading data returns and book files don't.
- [ ] A fresh install from the Play internal test, as well as the GitHub APK.

## 8. Known limitations

Mention these in the listing and release notes while they are still true (both are currently in
`full_description.txt`; the first is in `changelogs/4.txt`):

- Read-aloud for TXT books stops when the app goes to the background. Only EPUB read-aloud
  keeps playing.
- DRM-protected books can't be opened.
