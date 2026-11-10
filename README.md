# Jacket

**Let your foldable wear the book you're reading.**

Jacket is an open-source Android reader for foldable phones. While you read on the inner
screen, the outer screen shows the book's cover edge to edge, like album art, so the people
around you see what you're reading. On flip phones, the cover fills the cover-screen widget instead.

It's built with Material 3 Expressive: springy motion, shape morphing, haptics, and colors taken
from the cover of the book you're reading.

> **Status: 1.0 release candidate.** The outer-screen cover has been confirmed on a book-style
> foldable. Test builds are on the [releases page](https://github.com/friendlyglobal/jacket/releases),
> and reports from Pixel Fold, Galaxy Z Fold/Flip and Razr owners are very welcome.

## What it does

### Reading
- **Your own files:** EPUB, PDF, CBZ comics and plain text. Use **Add books**, or *Open with* /
  *Share* from any app (shared text becomes a book too). Jacket keeps a private copy and pulls out
  the title, author and cover, generating a typographic cover when a file has none.
- **Real pages for EPUB**, rendered by [Readium](https://readium.org), the open-source toolkit
  behind many reading apps: proper pagination, publisher styles, images and links. Pages turn
  like paper: the page lifts off under your finger and slides over the next with a soft shadow,
  then settles with a spring. Or pick another turn: *Curl* folds the page over like a leaf of a
  book, *Cards* deals it away on a spring, *Slide* keeps Readium's plain strip of pages. At the
  first and last page the sheet gives a little and springs back. Swipe or tap the outer edges to
  turn; tap anywhere else for the controls. Unfolded (or in
  landscape), you get a two-page spread. Or switch to continuous scrolling, where taps only
  bring up the controls.
- **Contents and progress:** the book's own table of contents, sub-chapters included, with the
  chapter you're in tracked as you read (even when a whole book is one file), "pages left in
  chapter" (or, once Jacket has seen your pace in the book, the minutes left), and a smooth
  scrubber that previews the chapter under your thumb. After a jump through the contents, a
  search or the scrubber, a "Back" pill returns you to the page you left.
- **Comforts, each with its own switch:** swipe up or down the page's left edge for brightness
  and its right edge for warmth; light pages that turn dark with the phone's dark theme; and a
  tabletop mode that, half-folded on a table, puts the page above the fold and page-turn buttons
  (or the read-aloud player) below it.
- **Typography:** Literata (Google's typeface for long reading), Atkinson Hyperlegible and
  OpenDyslexic, alongside the system serif and sans and the book's own fonts. Text size, line
  spacing, margins, justified text with hyphenation, and Book, Paper, Dark or Black pages.
  Settings apply live and keep your place. Volume keys can turn pages.
- **Bookmarks, highlights and notes:** tap the ribbon to bookmark a page. Press and hold text to
  highlight it in one of five colours, add a note, copy, share, or send it to Translate and other
  text apps. Everything is listed next to the contents, and a book's highlights and notes can be
  shared out as text in one go.
- **Search inside EPUBs**, with results streaming in and the match marked on the page.
- **Finishing a book:** the last page offers to mark it finished and suggests what to read next;
  finished books move to their own shelf.
- PDFs render with Android's `PdfRenderer`, comics page by page, text as flowing paragraphs.
  Pinch or double-tap to zoom PDFs and comics. Your place is saved as you read.

### Listening
- **Audiobook mode** for EPUB and text: the phone's text-to-speech reads the book in its own
  language when a voice is installed. The sentence being read is highlighted, and each word turns
  from soft gray to full ink (full white in dark mode) as it's spoken. The page turns itself to
  follow, and reading carries on into the next chapter.
- EPUB read-aloud keeps going with the screen off, with controls on the lock screen, in the
  notification shade and on headphones. A sleep timer stops it after 15, 30 or 60 minutes or at
  the end of the chapter.
- Play/pause, skip by sentence (EPUB) or paragraph (text), 0.8× to 2× speed. It pauses when
  another app plays audio or your headphones come out.

### In your language
English, Spanish, Brazilian Portuguese, French, Italian, German, Dutch, Polish, Russian, Ukrainian,
Turkish, Arabic (right to left), Hindi, Indonesian, Japanese, Korean, and Simplified and Traditional
Chinese. Android 13+ lets you pick Jacket's language on its own in system settings. Translations
are a first pass; corrections from native speakers are welcome (`app/src/main/res/values-*/`).

### Your library
- **Watched folder:** pick a folder once in Settings, and new books in it are added whenever
  Jacket opens.
- **Reading time and streak:** today's minutes and the run of days read, under the library's
  title (off in Settings if you'd rather not count).
- **Shortcuts:** long-press Jacket's icon for the book you're reading, the one before it, and
  "Listen".

### Your data
- **Backup:** Settings › Backup saves your books list, places, bookmarks, highlights and settings
  to a file you choose (not the book files). Restore it on a new phone, then add the books again:
  each one finds its place and notes. Android's own device backup keeps the same data.

### The cover, where people can see it

| Device | How the cover appears |
| --- | --- |
| Book-style foldables (Pixel Fold line, others where supported) | **Full-screen on the outer display while you read inside**, using Jetpack WindowManager's dual-screen mode (`WindowAreaController`, `OPERATION_PRESENT_ON_AREA`). Optionally, a strip along the bottom follows the book: the chapter as a wave over a line for the whole book, a turning badge and a running wave while it's read aloud, a badge that grows points through the last tenth, a check and the date once finished. After 30 seconds it steps back to an ambient state: a small dim cover on black with a ring and a number. With *Cover when closed* on, the same cover takes the screen when you fold the phone shut on an open book; if it's being read aloud, a tap brings up the sentence being spoken and the player. |
| Galaxy Z Flip | A **Flex Window widget**: the Glance widget declares Samsung's `display="sub_screen"` meta-data. Add it under *Settings › Cover screen › Widgets*. The default *Jacket* style fills the cover screen with the cover. |
| Motorola Razr | Open Jacket on the external display. The UI adapts to small, square windows. |
| Any phone or tablet | The same widget, on the home screen. |

### Material 3 Expressive
- Opening a book: the library recedes, the cover flies in, breathes while the first page
  loads, then swings open on its spine as the page grows to fill the screen. Closing folds the
  page back into the cover, which flies home to its exact spot. Predictive back drives the
  same fold as you swipe.
- Settings show a live preview of your cover screen, drawn by the outer screen's own code and
  walking through a book's stages, and of each widget style.
- A three-page welcome before your first book.
- The play button morphs into a slowly turning cookie while it reads, over a wavy progress line
  that goes flat when paused. The bookmark ribbon drops in with a bounce, and finishing a book
  gets a small celebration.
- Covers squash on press, re-sorting springs the grid into place, and the add button tucks away
  as you scroll.
- Connected button groups, segmented lists, wavy progress, a floating toolbar with its own
  action button, shape-morphing loading indicators, and a large collapsing settings header.
- Haptics throughout: page turns tick, the scrubber ticks as it crosses each chapter, sliders
  tick per step, toggles click, and long-presses rumble. All of it follows the system's touch-feedback setting.
- Colors come from the cover of the book you last read (Palette picks the seed; the tonal
  roles are built in OKLCH). Turn that off in Settings to use your phone's palette.

## Privacy

See [PRIVACY.md](PRIVACY.md).

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

- Read-aloud for PDFs (Android 15's PdfRenderer text API), and in the background for plain text
- Collections and reading stats
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
