# Jacket

**Let your foldable wear the book you're reading.**

Jacket is an open-source Android reader for foldable phones. While you read on the inner
screen, the outer screen shows the book's cover edge to edge, like album art, so the people
around you see what you're reading. On flip phones, the cover fills the cover-screen widget instead.

It's built with Material 3 Expressive: springy motion, shape morphing, haptics, and colors taken
from the cover of the book you're reading.

> **Status: early alpha.** The outer-screen cover has been confirmed on a book-style foldable;
> the rest is young. Test builds are on the [releases page](https://github.com/friendlyglobal/jacket/releases),
> and reports from Pixel Fold, Galaxy Z Fold/Flip and Razr owners are very welcome.

## What it does

### Reading
- **Your own files:** EPUB, PDF, CBZ comics and plain text. Use **Add books**, or *Open with* /
  *Share* from any app (shared text becomes a book too). Jacket keeps a private copy and pulls out
  the title, author and cover, generating a typographic cover when a file has none.
- **Real pages for EPUB**, rendered by [Readium](https://readium.org), the open-source toolkit
  behind many reading apps: proper pagination, publisher styles, images and links. Swipe or tap
  the outer edges to turn the page; tap anywhere else for the controls. Unfolded (or in
  landscape), you get a two-page spread. Or switch to continuous scrolling, where taps only
  bring up the controls.
- **Contents and progress:** the book's own table of contents, sub-chapters included, with the
  chapter you're in tracked as you read (even when a whole book is one file), "pages left in
  chapter", and a smooth scrubber that previews the chapter under your thumb.
- **Typography:** Literata (Google's typeface for long reading), Atkinson Hyperlegible and
  OpenDyslexic, alongside the system serif and sans and the book's own fonts. Text size, and
  Book, Paper, Dark or Black pages. Settings apply live and keep your place.
- PDFs render with Android's `PdfRenderer`, comics page by page, text as flowing paragraphs.
  Your place is saved as you read.

### Listening
- **Audiobook mode** for EPUB and text: the phone's text-to-speech reads the book in its own
  language when a voice is installed. The sentence being read is highlighted, and each word turns
  from soft gray to full ink (full white in dark mode) as it's spoken. The page turns itself to
  follow, and reading carries on into the next chapter.
- Play/pause, skip by sentence (EPUB) or paragraph (text), 0.8× to 2× speed. It pauses when
  another app plays audio or your headphones come out.

### The cover, where people can see it

| Device | How the cover appears |
| --- | --- |
| Book-style foldables (Pixel Fold line, others where supported) | **Full-screen on the outer display while you read inside**, using Jetpack WindowManager's dual-screen mode (`WindowAreaController`, `OPERATION_PRESENT_ON_AREA`). An optional slim progress bar sits at the bottom, and it dims to an ambient state after 30 seconds. |
| Galaxy Z Flip | A **Flex Window widget**: the Glance widget declares Samsung's `display="sub_screen"` meta-data. Add it under *Settings › Cover screen › Widgets*. The default *Jacket* style fills the cover screen with the cover. |
| Motorola Razr | Open Jacket on the external display. The UI adapts to small, square windows. |
| Any phone or tablet | The same widget, on the home screen. |

### Material 3 Expressive
- The cover flies from the library into the book (a shared-element transition) and "opens" into
  the first page. Predictive back shrinks the page as you swipe.
- Covers squash on press, re-sorting springs the grid into place, and the add button tucks away
  as you scroll.
- Connected button groups, segmented lists, wavy progress, a floating toolbar with its own
  action button, shape-morphing loading indicators, and a large collapsing settings header.
- Haptics throughout: page turns tick, the scrubber ticks as it crosses each chapter, sliders
  tick per step, toggles click, and long-presses rumble. All of it follows the system's touch-feedback setting.
- Colors come from the cover of the book you last read (Palette picks the seed; the tonal
  roles are built in OKLCH). Turn that off in Settings to use your phone's palette.

## Privacy

Everything stays on the device. Jacket has no internet permission, so neither the app nor the
books you open in it can reach the network.

## Building

Requirements: JDK 17+ (Android Studio's bundled JDK works) and the Android SDK with platform 37.

```sh
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Stack: AGP 9.5, Kotlin 2.4, Jetpack Compose (BOM 2026.09) with Material 3 1.5, WindowManager 1.5,
Glance 1.2, Readium kotlin-toolkit 3.4. Min SDK 26.

### Trying the outer display without a foldable

Android Studio's **Pixel 9 Pro Fold** emulator supports dual-screen mode. Unfold it, open a book,
and use the emulator's extended controls to view the rear display.

## Project layout

```
app/src/main/java/io/github/friendlyglobal/jacket/
├── data/        Book model, JSON-backed library, settings
├── importer/    EPUB / PDF / CBZ / TXT import: metadata, covers, title cards
├── reader/      Reader screen, EPUB on Readium, per-format views, read-aloud, reading controls
├── outer/       Outer-display session (WindowAreaController) and the cover shown on it
├── widget/      Glance widget (home screen and Galaxy Z Flip Flex Window)
└── ui/          Theme (cover-seeded color), home, settings, motion and haptics helpers
app/src/main/assets/fonts/   Bundled reading fonts and their licenses
app/src/main/assets/licenses/ Third-party license texts
docs/concept.html            The interactive design concept the app started from
```

## Roadmap

- Background listening with lock-screen controls (a media session and foreground service)
- Read-aloud for PDFs (Android 15's PdfRenderer text API)
- "Currently reading" sync from Hardcover and Open Library, for books you read in other apps
- ISBN barcode scanning to set a cover for a paper book
- More formats (FB2, CBR), password-protected PDFs

Kindle, Google Play Books and other stores don't expose what you're reading, and DRM-protected
files can't be opened. Jacket won't try to work around either.

## Contributing

Issues and pull requests are welcome. If you own a foldable, the most useful thing you can do
right now is install a test build and report how the outer screen and the cover-screen widget
behave on your device.

## License

Jacket is licensed under the [Apache License 2.0](LICENSE).
EPUB rendering and read-aloud use the [Readium kotlin-toolkit](https://github.com/readium/kotlin-toolkit),
© Readium Foundation, under the BSD 3-Clause license (text in `app/src/main/assets/licenses/`).
Literata and Atkinson Hyperlegible are licensed under the SIL Open Font License 1.1; their license
texts ship next to the font files in `app/src/main/assets/fonts/`. OpenDyslexic (OFL) is bundled
with Readium.
