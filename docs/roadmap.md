# Jacket roadmap

What comes next, why, and roughly how big each piece is. Sizes: **S** is under a day, **M** a
few days, **L** more. Code paths are under `app/src/main/java/io/github/friendlyglobal/jacket/`,
with line numbers as of 1.0.1 (`ce1a143`), so they will drift.

The research behind this list (November 2026) leaned on reader forums, other apps' feature pages
and Google's own guidelines. It shows which way to go, not how many people asked.

Every item has to keep Jacket's rules: no internet permission, no GPL code, no ads or tracking,
plain-language UI, and nothing labelled "AI".

## 1.1

### Turn pages with a keyboard, remote or mouse (S)
Arrow keys, Page Up/Down and Space turn pages, and Space pauses the voice when it's reading. This
suits a Bluetooth page-turner with the phone half-open on a table.
- Google's large-screen Tier 2 asks for
  [keyboard navigation and media keys](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-2),
  and these guidelines feed Play's
  [large-screen ranking](https://developer.android.com/docs/quality-guidelines/large-screen-app-quality).
- Today `MainActivity.kt:128` handles only the volume keys.

### Footnotes in a pop-up (S)
Tapping a note number shows the note over the page instead of jumping to the end of the chapter.
- Readers call the jump distracting and praise Moon+ Reader's note box
  ([MobileRead](https://www.mobileread.com/forums/showthread.php?p=2972998)).
- Readium hands internal links to the app through
  [`shouldFollowInternalLink`](https://readium.org/kotlin-toolkit/3.1.2/migration-guide).
  Jacket's listener handles only outside links (`reader/EpubReader.kt:268`).
- Risk: check the callback in Readium 3.4. It only works where the book marks its notes
  (`epub:type="noteref"`/`"footnote"`), so links that aren't marked need a fallback.

### Listening comforts (S–M)
Speeds from 0.5× to 3×, a sleep timer of any length, a short step back after a long pause, and
skipping by chapter.
- @Voice's timer runs from
  [1 minute to 5 hours](https://hyperionics.com/atVoice/features/sleep-timer-bedtime-reader.asp),
  and Voice Dream offers [50–500 words a minute](https://www.voicedream.com/text-reader/).
- Jacket stops at 2× (`reader/Listen.kt:182`) and has four fixed timers (`:107`), although saved
  settings already accept 0.5–3× (`data/Model.kt:341`).
- Risk: the natural voices work out speed inside the voice model, so slower phones may not keep up
  at 3×.

### Save highlights and notes as a file (S)
Save one book's highlights, or all books' at once, as a Markdown file through the system file
picker.
- Exporting highlights on Android is
  [widely poor](https://christiantietze.de/posts/2023/05/android-ebook-annotation-exports/).
- Jacket already shares one book's highlights as text (`reader/ReaderScreen.kt:918`), and that text
  is close to Markdown already.

### Accessibility pass (S)
Go through the reader with TalkBack and
[Accessibility Scanner](https://developer.android.com/training/accessibility/testing) and fix what
they find. Text size stops at 200% (`reader/ReadingControls.kt:67`). Allowing larger text is a
suggestion, not something users have been seen asking for.

## Later

### Plain and shared text work like full books (M)
Shared text plays with the screen off, skips by sentence, and can be highlighted and searched.
- The text reader's player (`reader/PageReaders.kt:564`) has no media session, highlights or search.
- One approach is to turn text into a small EPUB when it's imported (`importer/`).
- Risk: places already saved in existing text books would need to move to the new format.

### Say it this way (M)
Fix how a name is pronounced, and skip note numbers when reading aloud.
- Asked for on [MobileRead](https://www.mobileread.com/forums/showthread.php?p=4589542) and on
  [another reader app's board](https://voicereader.featurebase.app/p/option-to-correct-pronunciations-in-voice-reader).
- Nothing like it exists in `voice/` yet.
- Risk: word highlighting (`voice/SpeechTiming.kt`) has to stay lined up with the changed text.

### Galaxy Z Flip cover-screen player: skip and sleep (S)
- `widget/PlayerWidget.kt:152` offers only play/pause.
- On a Flip, full apps on the cover screen need
  [Good Lock](https://www.droid-life.com/2023/08/02/galaxy-z-flip-5-cover-screen-apps-good-lock/),
  so this widget is the whole Flip experience.
- Risk: it needs testing on a real Flip.

### Your reading (S–M)
A page with this week, month and year, books finished, and a calendar.
- KOReader's reading calendar is popular enough that
  [other tools copy it](https://github.com/paviro/KoShelf).
- Jacket already stores minutes per day (`data/Model.kt:229`) but shows only today and the streak.

### Book posture (S–M)
Half-open, Jacket reads like a paperback, with the fold as the spine.
- [Play Books does this](https://goodereader.com/blog/electronic-readers/google-play-books-update-foldable).
- Jacket reacts only to a flat fold (`ui/Expressive.kt:480`).
- Open question: check what the two-page spread does half-open today.

### Resume listening without opening the book, then Android Auto (L)
A headphone button or the car resumes the current book even after Android has closed Jacket.
- The player lives in the reader screen (`reader/ListenService.kt:32`), so after Android closes
  Jacket the button has nothing to play.
- Asked for [for the car](https://discuss.grapheneos.org/d/22043-any-android-auto-compatible-ebook-reader-that-uses-installed-tts-engine),
  and [Librera added it](https://newreleases.io/project/github/foobnix/LibreraReader/release/9.5.7).
- Risk: it's the biggest refactor on this list, and Android Auto apps get their own Play review.

### Read PDFs aloud (L)
- Read-aloud rivals lead with PDFs
  ([comparison](https://www.audeus.com/blog/speechify-alternatives-pdf-epub)).
- Android 15's PdfRenderer can return a page's text.
- Risks: that API is Android 15+ only, and reading order, page headers and scanned pages are hard.

### Catch up on a second device (M)
Two devices share one backup file in a folder the user picks (for example, one synced by Drive),
and opening Jacket takes the newer place.
- Sync is what [ReadEra charges for](https://readera.org/premium).
- Restore already merges books (`data/Backup.kt:34`).
- Risks: restore replaces all settings with the backup's (`:59`), backups don't include the book
  files, and a synced file can be out of date.

### Earlier ideas
- A natural voice for Chinese, and listening to a book in another language, translated on the
  phone.
- "Currently reading" from Hardcover and Open Library. Jacket has no internet permission, so this
  would have to come through a file or another app, not a direct connection.
- ISBN barcode scanning to set a cover for a paper book.
- More formats (FB2) and password-protected PDFs.

## Not planned
- **Cloud sync, accounts, online catalogues (OPDS), fetching web articles:** all need the internet.
  Handing a word off to a dictionary or translate app already works (`reader/EpubReader.kt:718`).
- **Orientation lock:** Android 16 ignores it on screens 600dp and wider
  ([details](https://android-developers.googleblog.com/2025/01/orientation-and-resizability-changes-in-android-16.html)),
  so it would fail on the unfolded screen.
- **MOBI/AZW3 and comic archives (CBR):** there seem to be no permissively licensed parsers, and
  Kindle files invite confusion about DRM.
- **A built-in offline dictionary:** it's large, and most free dictionary data is copyleft.
- **Importing your own fonts:** fiddly in Readium for a small audience. Three good reading fonts
  ship already.
- **Auto-scroll:** read-aloud already turns the pages.

## Size budget
Play's download is about 177 MB against Play's 200 MB limit (see `RELEASING.md`), so any feature
that adds weight, such as a new voice, needs a plan for where those megabytes come from.
