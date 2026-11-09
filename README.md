# Jacket

**Let your foldable wear the book you're reading.**

Jacket is an open-source Android reader for foldable phones. While you read on the inner
screen, the outer screen shows the book's cover edge to edge, like album art, so the people
around you see what you're reading. On flip phones, the cover fills the cover-screen widget instead.

The whole UI is Material 3 Expressive, and it re-tints itself from each book's cover.

> **Status: 0.1.0, early.** It builds and passes Android lint. It hasn't been tested on real
> foldable hardware yet. Reports from Pixel Fold, Galaxy Z Fold/Flip and Razr owners are very welcome.

## What it does

- **Imports your files:** EPUB, PDF, CBZ comics and plain text. Use the **Add books** button,
  or *Open with* / *Share* from any file manager. Jacket keeps a private copy, pulls out the title,
  author and cover, and generates a typographic cover when a file has none.
- **Reads them:** EPUB chapters in a sandboxed WebView themed to match the book, PDF pages via
  Android's `PdfRenderer`, comics page by page, text as flowing paragraphs. Your position is saved
  as you read.
- **Shows the cover where people can see it:**

| Device | How the cover appears |
| --- | --- |
| Book-style foldables (Pixel Fold line, others where supported) | **Full-screen on the outer display while you read inside**, using Jetpack WindowManager's dual-screen mode (`WindowAreaController`, `OPERATION_PRESENT_ON_AREA`). An optional slim progress bar sits at the bottom, and it dims to an ambient state after 30 seconds. |
| Galaxy Z Flip | A **Flex Window widget**: the Glance widget declares Samsung's `display="sub_screen"` meta-data. Add it under *Settings › Cover screen › Widgets*. The default *Jacket* style fills the cover screen with the cover. |
| Motorola Razr | Open Jacket on the external display. The UI adapts to small, square windows. |
| Any phone or tablet | The same widget, on the home screen. |

- **Material 3 Expressive throughout:** connected button groups, wavy progress indicators,
  the floating reader toolbar, shape-morphing loading indicators, the multi-browse cover carousel,
  expressive motion, and a color scheme seeded from each cover (Palette picks the seed, and the
  tonal roles are built in OKLCH).

## Privacy

Everything stays on the device. Jacket has no internet permission, and EPUB content can't reach
the network: the reader answers every non-local request with an empty response.

## Building

Requirements: JDK 17+ (Android Studio's bundled JDK works) and the Android SDK with platform 37.

```sh
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Stack: AGP 9.5, Kotlin 2.4, Jetpack Compose (BOM 2026.09) with Material 3 1.5, WindowManager 1.5,
Glance 1.2. Min SDK 26.

### Trying the outer display without a foldable

Android Studio's **Pixel 9 Pro Fold** emulator supports dual-screen mode. Unfold it, open a book,
and use the emulator's extended controls to view the rear display.

## Project layout

```
app/src/main/java/io/github/friendlyglobal/jacket/
├── data/        Book model, JSON-backed library, settings
├── importer/    EPUB / PDF / CBZ / TXT parsing, covers, generated title cards
├── reader/      Reader screen and the per-format views
├── outer/       Outer-display session (WindowAreaController) and the cover shown on it
├── widget/      Glance widget (home screen and Galaxy Z Flip Flex Window)
└── ui/          Theme (cover-seeded color), home screen, shared pieces
docs/concept.html  The interactive design concept the app started from
```

## Roadmap

- "Currently reading" sync from Hardcover and Open Library, for books you read in other apps
- ISBN barcode scanning to set a cover for a paper book
- Cover art from audiobook apps through the media session
- Two-page spreads on the unfolded inner display
- More formats (FB2, CBR), password-protected PDFs

Kindle, Google Play Books and other stores don't expose what you're reading, and DRM-protected
files can't be opened. Jacket won't try to work around either.

## Contributing

Issues and pull requests are welcome. If you own a foldable, the most useful thing you can do
right now is install a debug build and report how the outer screen and the cover-screen widget
behave on your device.

## License

[Apache License 2.0](LICENSE)
