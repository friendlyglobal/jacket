# Jacket Privacy Policy

**Effective date: 10 November 2026**

Jacket is a free, open-source e-reader for Android, developed at
[github.com/friendlyglobal/jacket](https://github.com/friendlyglobal/jacket). This policy explains
what happens to your information when you use it.

## Summary

Jacket does not collect or transmit any personal data. It does not have Android's internet
permission, so neither the app nor the books you open in it can connect to the network. There
are no analytics, no ads and no accounts. Jacket sends the developer nothing.

## What Jacket stores on your device

- **Books.** When you import a book (EPUB, PDF, CBZ or TXT) with *Add books*, *Open with* or
  *Share*, Jacket copies the file into its private storage on your device, along with a cover
  image that it extracts or generates. Text you share to Jacket is saved as a book the same way.
- **Your reading data.** Reading positions, bookmarks, highlights, notes and settings are stored
  on your device.

Android keeps this storage private to Jacket; other apps cannot read it.

## Read-aloud

Jacket can read aloud with two kinds of voice.

- **Natural voices** are part of Jacket and run inside the app, on your phone. The text they
  read is turned into sound on the device and is not passed to any other app or sent anywhere.
  Nothing is downloaded to use them.
- **Your phone's voice** is the text-to-speech engine installed on your phone, such as Google
  Speech Services. That engine is a separate app with its own privacy policy. When you read
  aloud with it, Jacket passes the engine the text being read. How the engine handles that text
  is covered by its own privacy policy.

You choose the voice in Jacket's Settings or on the player. The natural voices read the
languages they know; for any other language, the phone's voice is used.

## Backups

- **Android device backup.** If you have device backup turned on, Android's Auto Backup may
  include Jacket's library list, reading positions, bookmarks, highlights, notes and settings
  in your Google account backup. Book files are not included. Google stores this backup under
  its own terms; the developer has no access to it. You can turn backup off in your phone's
  settings.
- **Moving to a new phone.** If you use Android's phone-to-phone transfer, Android may copy
  Jacket's data, including your books, directly to the new phone.
- **Backup files you export.** You can export a backup file from Jacket yourself. It is saved
  wherever you choose, such as your phone's storage or a cloud storage app, and is then handled
  by that location.
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

Jacket does not request the internet, location, contacts, camera, microphone or storage
permissions. You choose files through Android's own file picker. Jacket uses a foreground
service so that read-aloud can keep playing with the screen off. Android libraries that Jacket
uses for its widget also declare permissions to schedule widget updates (including "view
network connections", which only lets the app check whether the phone is online; it cannot
connect).

## Google Play

If you install Jacket from Google Play, Google gives developers aggregate statistics such as
install counts, and crash reports from people who have chosen to share diagnostics with Google.
This information comes from Google Play and Android, not from Jacket, and is covered by
[Google's privacy policy](https://policies.google.com/privacy).

## Deleting your data

Uninstalling Jacket deletes all of its data from your device. Backup files you exported, the
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
