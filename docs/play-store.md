# Play Console working sheet

Answers for the Play Console forms for **Jacket 1.0.0-rc.1 (versionCode 4)**,
applicationId `io.github.friendlyglobal.jacket`. Play changes its form wording from time to
time: where an answer below depends on Play's definitions, check it against the current form
before you submit.

Store listing text lives in `fastlane/metadata/android/en-US/` (`title.txt`,
`short_description.txt`, `full_description.txt`, `changelogs/4.txt`).

## 1. Data safety

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **No** |
| Data collected | None |
| Data shared | None |

When you answer No, the form skips the per-data-type, encryption-in-transit and deletion
questions, and the listing shows "No data collected" and "No data shared with third parties".

**Why this is accurate.** The merged manifest has no `android.permission.INTERNET`, so the app
cannot send anything off the device. Play defines collection as transmitting data off the
device, and everything Jacket handles (book files, covers, reading positions, bookmarks,
highlights, notes, settings) stays in app-private storage. There are no SDKs for analytics,
ads, crash reporting or sign-in.

Edge cases, in case a reviewer asks (verify against the current form wording):

- **Android Auto Backup.** If the user has device backup on, Android may copy the library
  list, reading positions, bookmarks, highlights, notes and settings (not book files) to the
  user's Google account backup. That is a Google-run device backup the developer cannot
  access; Play's form does not count it as collection by the developer.
- **Read-aloud.** At the user's request, Jacket passes the text being read to the
  text-to-speech engine installed on the phone (a separate app chosen by the user, such as
  Google Speech Services). This is a user-initiated, on-device hand-off, not sharing by the
  developer.
- **Open with, Share, backup export.** These go through Android's own share sheet and file
  picker, started by the user, to a destination the user picks.

## 2. Content rating (IARC questionnaire)

- **Category:** a reader app. Pick the reference / education / reading category if the
  questionnaire offers one; otherwise the general "other apps" category.
- **The app's own content:** Jacket ships no books or other content. It displays files the user
  supplies. Answer **No** to violence, fear, sexuality, profanity, drugs, alcohol, tobacco,
  gambling and crude humour.
- **User-supplied content:** books stay on the user's device and are never shown to other users.
- **Interaction:** no user-to-user communication or content sharing, no sharing of location,
  no purchases, no unrestricted internet access (no browser, no INTERNET permission). Answer
  **No** to each.
- **Expected result:** the lowest rating in each region (for example, IARC 3+ / Everyone).

## 3. Listing settings and other declarations

| Field | Answer |
| --- | --- |
| App or game | App |
| Category | Books & Reference |
| Price | Free |
| Contains ads | No |
| In-app purchases | None |
| Target audience | 13–15, 16–17, 18+ (leave every under-13 group unticked, so the Families policy does not apply) |
| Appeals to children? | No |
| App access | All functionality is available without special access (no login) |
| Advertising ID | No; the app does not use it and the merged manifest has no `AD_ID` permission |
| News, government, financial features, health | No / None |
| Privacy policy URL | https://friendlyglobal.github.io/jacket/privacy.html |
| Website (optional) | https://github.com/friendlyglobal/jacket |
| Contact email | Required by Play and shown publicly; use an address you are happy to publish |

## 4. Permissions

Check the final list before you submit: Android Studio's *Merged Manifest* tab for
`app/src/main/AndroidManifest.xml`, or
`aapt2 dump permissions app/build/outputs/apk/release/app-release.apk`. The last build
(versionCode 3) merged in `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and
`FOREGROUND_SERVICE`; tonight's background read-aloud adds `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
`INTERNET` must not appear.

| Permission | Source | Justification |
| --- | --- | --- |
| `FOREGROUND_SERVICE` | Jacket (read-aloud service); also declared by AndroidX WorkManager | Read-aloud runs as a media playback foreground service so that listening continues with the screen off or the app in the background, with a visible media notification. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Jacket | Required on Android 14+ for the `mediaPlayback` service type. Used only while the user is listening to a book read aloud by text-to-speech; started when the user presses Play and stopped when playback stops. |
| `POST_NOTIFICATIONS` (only if present) | Jacket or Media3 | Shows the read-aloud playback notification with play/pause and skip. Media-session notifications are exempt from this permission on Android 13+, so it may not be declared at all; if it isn't, skip this line. |
| `RECEIVE_BOOT_COMPLETED` | AndroidX WorkManager (used by the Glance widget) | WorkManager reschedules pending widget update work after a reboot. Jacket starts nothing of its own at boot. |
| `WAKE_LOCK` | AndroidX WorkManager (used by the Glance widget) | Held briefly while widget update work runs. |
| `ACCESS_NETWORK_STATE` | AndroidX WorkManager (used by the Glance widget) | Lets WorkManager evaluate network constraints on scheduled work. Jacket schedules no network work and, without `INTERNET`, cannot connect; this permission only reports whether a connection exists. |
| `io.github.friendlyglobal.jacket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX Core (generated) | Signature-level, internal to the app. No declaration needed. |

Play asks for a declaration only for the foreground service type; the other rows are normal
permissions, listed here for reviewer or user questions.

**Foreground service declaration** (*App content → Foreground service permissions*):

- Type: **Media playback**.
- Description: "Jacket reads the user's book aloud with the phone's text-to-speech engine. The
  media playback foreground service keeps reading while the screen is off or another app is in
  front, and provides the media notification and lock-screen controls. It runs only while the
  user is listening."
- Impact if deferred or interrupted: "Read-aloud would stop mid-sentence as soon as the screen
  turns off, which defeats listening to a book with the phone in a pocket."
- Video: a short screen recording (unlisted YouTube or a shared Drive link) that starts
  read-aloud in an EPUB, locks the phone, shows audio continuing and the lock-screen controls.

WorkManager's `SystemForegroundService` is also in the merged manifest. Jacket never starts it;
if a reviewer asks, it is an unused library component.

## 5. Graphic assets

Put files under `fastlane/metadata/android/en-US/images/` (Play and F-Droid both use this
layout).

| Asset | Size and format | Path |
| --- | --- | --- |
| App icon | 512 × 512 px, 32-bit PNG with alpha, up to 1 MB | `images/icon.png` |
| Feature graphic | 1024 × 500 px, JPEG or 24-bit PNG (no alpha) | `images/featureGraphic.png` |
| Phone screenshots | 2–8, JPEG or 24-bit PNG; each side 320–3840 px; the long side at most twice the short side. At least 4 at 1080 px or more to be eligible for promotion | `images/phoneScreenshots/1.png`, `2.png`, ... |
| 7-inch tablet screenshots | Up to 8, same format; for large-screen showcasing, 16:9 or 9:16 with each side 1080–7680 px | `images/sevenInchScreenshots/` |
| 10-inch tablet screenshots | As for 7-inch | `images/tenInchScreenshots/` |

Raw captures from tall phone screens (for example 1080 × 2424) exceed the 2:1 limit; crop or
pad them to 9:16 (1080 × 1920) or 1:2 (1080 × 2160). Unfolded inner-screen captures are close to
square, so pad them to 16:9 for the tablet slots. Verify the current limits in the console when
you upload.
