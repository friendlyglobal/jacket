# Jacket Privacy Policy

**Effective date: 13 November 2026**

Jacket is a free, open-source e-reader for Android, developed at
[github.com/friendlyglobal/jacket](https://github.com/friendlyglobal/jacket). This policy explains
what happens to your information when you use it.

## Summary

Jacket does not collect or transmit any personal data. It uses the internet for one thing only:
downloading a natural voice from GitHub, once, after you agree (see "Downloading a voice" below).
Nothing is uploaded. The books you open in Jacket cannot connect to the network. There are no
analytics, no ads and no accounts. Jacket sends the developer nothing.

## What Jacket stores on your device

- **Books.** When you import a book (EPUB, PDF, FB2, CBZ or TXT) with *Add books*, *Open with* or
  *Share*, Jacket copies the file into its private storage on your device, along with a cover
  image that it extracts or generates. Text you share to Jacket is saved as a book the same way.
- **Your reading data.** Reading positions, bookmarks, highlights, notes and settings are stored
  on your device, and so is the time you spend with your books: how long you read or listened
  each day (kept for a little over a year), how much of it was listening, and the time spent
  with each book. It is only used for "Your reading", your streak and the widget. Which PDFs
  and comics you set to read right to left is kept with them. Settings include a small count
  of how many times the reader has shown where to swipe for brightness and warmth, so those
  hints stop after the first few books; it is kept with the other settings, in backups too.
- **PDF passwords.** A PDF that is locked with a password asks you for it. Jacket keeps the
  password only while it is running, unless you tick *Remember on this phone*. A remembered
  password is stored encrypted on your device, with a key that Android keeps on that device. It
  is not part of Android's device backup, a phone-to-phone transfer, a backup file you export or
  the "Catch up between devices" file. *Forget password* in the book's menu removes it.

Android keeps this storage private to Jacket; other apps cannot read it.

## Read-aloud

Jacket can read aloud with two kinds of voice.

- **Natural voices** run inside Jacket, on your phone. The text they read is turned into sound on
  the device and is not passed to any other app or sent anywhere. The voices themselves are
  downloaded once, when you agree (see below).
- **Your phone's voice** is the text-to-speech engine installed on your phone, such as Google
  Speech Services. That engine is a separate app with its own privacy policy. When you read
  aloud with it, Jacket passes the engine the text being read. How the engine handles that text
  is covered by its own privacy policy.

You choose the voice in Jacket's Settings or on the player. The natural voices read the
languages they know; for any other language, the phone's voice is used.

## Downloading a voice

The natural voices are not inside the app. The first time you listen to a book whose voice is
not on your phone, Jacket asks whether to download it. Settings › Voice can also download or
remove each set of voices.

- **Only when you agree.** Nothing is downloaded until you choose Download. If you choose your
  phone's voice instead, Jacket does not ask again. On mobile data, Jacket tells you the size and
  asks before it starts. When you are not looking at Jacket (listening from the notification,
  headphones or a car), it never downloads anything new and uses a voice already on your phone.
  A download you started and that was interrupted (for example because Android closed Jacket)
  carries on the next time you open Jacket, over Wi-Fi.
- **Where from.** The voice files come from Jacket's own release on GitHub
  (github.com/friendlyglobal/jacket, release "voices-1"), served by GitHub. Jacket connects to
  GitHub over an encrypted connection (HTTPS) and to no other place.
- **What GitHub sees.** Like any download, GitHub sees your IP address, and the file asked for.
  Jacket sends no account, no identifier, no cookie and nothing about you, your phone or your
  books; it does not even send the usual description of the phone that apps send by default.
  GitHub handles the request under [its own privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
- **Nothing is uploaded.** The download only receives files. The text you read never leaves the
  device.
- **Checked before use.** Each file is checked against a size and fingerprint (SHA-256) built into
  Jacket. A file that doesn't match is deleted, never used.
- **Stored on your phone, outside backups.** Downloaded voices are kept in Jacket's private
  storage, in a place that Android's device backup and phone-to-phone transfer leave out, and
  they are not part of backup files you export. They take about 145 MB (31 languages) or 32 MB
  (the Brazilian voice). Settings › Voice removes them; uninstalling Jacket deletes them.

In the source code, this download is one file, `voice/VoiceDownload.kt`, and an automatic test
fails the build if any other part of Jacket opens a network connection.

## Backups

- **Android device backup.** If you have device backup turned on, Android's Auto Backup may
  include Jacket's library list, reading positions, reading and listening time, bookmarks,
  highlights, notes and settings (including which books read right to left) in your Google
  account backup. Book files are not included. Google stores this backup under its own terms;
  the developer has no access to it. You can turn backup off in your phone's settings.
- **Moving to a new phone.** If you use Android's phone-to-phone transfer, Android may copy
  Jacket's data, including your books, directly to the new phone.
- **Backup files you export.** You can export a backup file from Jacket yourself. It holds the
  same things as your reading data above (reading and listening time included), not the books
  themselves. It is saved wherever you choose, such as your phone's storage or a cloud storage
  app, and is then handled by that location.
- **Catching up between devices.** If you turn on "Catch up between devices", Jacket keeps one
  file in a folder you choose, and reads the files your other devices keep there. The file holds
  the books you have opened, marked or put in a collection (their titles and file names), where
  you are in each, and your
  bookmarks, highlights, notes and collections. It does not hold the books themselves. Jacket
  does not send it anywhere: if the folder belongs to a cloud storage or sync app, that app
  moves it, under its own terms. Turning the feature off stops the file being updated; you can
  delete it like any other file.

## What people near you can see

- The outer-screen cover and the cover-screen widget show the current book's cover and title
  on your phone's outer screen, where people nearby can see them. You can turn off the
  outer-screen cover in Jacket's Settings, and remove the widget from your cover screen or home
  screen at any time.
- While read-aloud is playing, Android shows the book's title in the media notification and on
  the lock screen.

## Permissions

Jacket has Android's internet permission ("have full network access") for one use only:
downloading a natural voice, as described above. It also uses "view network connections" to
know whether the phone is online, and whether it is on mobile data so it can ask first; that
permission cannot connect to anything. Jacket does not request the location, contacts, camera,
microphone or storage permissions. You choose files through Android's own file picker. Jacket
uses a foreground service so that read-aloud can keep playing with the screen off. Android
libraries that Jacket uses for its widget also declare permissions to schedule widget updates.

The books you open cannot use the internet permission: the pages of a book are shown with their
network access blocked, and a book's own scripts cannot open connections.

## Google Play

If you install Jacket from Google Play, Google gives developers aggregate statistics such as
install counts, and crash reports from people who have chosen to share diagnostics with Google.
This information comes from Google Play and Android, not from Jacket, and is covered by
[Google's privacy policy](https://policies.google.com/privacy).

## Deleting your data

Uninstalling Jacket deletes all of its data from your device, downloaded voices included. Backup files you exported, the
file Jacket kept for your other devices, and any Android backup in your Google account are not
affected; you manage those yourself.

## Children

Jacket is not directed at children under 13. It collects no personal information from anyone,
including children.

## Changes to this policy

If this policy changes, the new version will be posted in the Jacket repository with a new
effective date.

## Contact

Questions about this policy: open an issue at
[github.com/friendlyglobal/jacket/issues](https://github.com/friendlyglobal/jacket/issues).
Issues are public, so please don't include personal information.
