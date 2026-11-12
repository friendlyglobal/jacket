# Jacket roadmap

What comes next, why, and roughly how big each piece is. Sizes: **S** is under a day, **M** a
few days, **L** more. Code paths are under `app/src/main/java/io/github/friendlyglobal/jacket/`,
with line numbers as of 1.0.1 (`ce1a143`), so they will drift.

The research behind this list (November 2026) leaned on reader forums, other apps' feature pages
and Google's own guidelines. It shows which way to go, not how many people asked.

Every item has to keep Jacket's rules: no internet permission, no GPL code, no ads or tracking,
plain-language UI, and nothing labelled "AI".

## Shipped in 1.1 (11 November 2026)

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

Shipped: TalkBack, keyboard-only use, large text up to 300% and AA contrast. Still open: making a
highlight or note without press-and-hold, PDF and comic zoom without pinching, and reading the
page's own text with TalkBack inside the paginated view (Listen is the dependable route).

## Done since 1.1, not yet released

### Plain and shared text work like full books (M)
A text file, and text shared from another app, is read in the same reader as an EPUB: pages or
scrolling, read-aloud that keeps going with the screen off and answers to the lock screen and
headphones, the sleep timer, speeds, skipping by sentence and chapter, highlights and notes,
search, and bookmarks.
- The text file stays the book. Jacket makes pages of it (a small EPUB, `importer/TextBook.kt`)
  when it is opened, and makes them again whenever they are missing.
- A place is kept as the page and, as before, as the paragraph: books, bookmarks and backups from
  1.1 and earlier open where they were left, and nothing in them is rewritten.
- Chapter headings ("Chapter 4", "Capítulo 12", "第三章", a Markdown file's `#` lines) become the
  contents. Files saved as UTF-16, Windows-1252, Windows-1251, KOI8-R, Shift-JIS, EUC-JP, GBK, Big5
  and EUC-KR are read as such.
- Read-aloud, for every kind of book, now pauses while a call rings or a direction is spoken and
  goes on afterwards (`reader/VoiceFocus.kt`). Before, an EPUB's voice talked over it and a text
  file's stopped for good.
- Still open: a text book's percent is now counted by length, not by paragraphs, so it can change
  once after the update; verse and lists inside a text file are still run together as paragraphs;
  and with TalkBack a text file is now read through Listen, like an EPUB, where the old reader's
  paragraphs could be swiped through one by one.

### Galaxy Z Flip cover-screen player: skip and sleep (S)
While a book is read aloud, the "Jacket player" widget has a sentence back, a sentence on and the
sleep timer beside play and pause, so a Flip can stay shut for all of them.
- On a Flip, full apps on the cover screen need
  [Good Lock](https://www.droid-life.com/2023/08/02/galaxy-z-flip-5-cover-screen-apps-good-lock/),
  so this widget is the whole Flip experience.
- The buttons are the reader's own (`reader/ListenRemote.kt`), not a second player. The moon steps
  the reader's timer: 15 minutes, 30, an hour, your own length, the end of the chapter, off.
- Every button is 48dp or more. As many are shown as the widget's size has room for, from play
  alone on the smallest to large buttons with the timer in words at the cover screen's size.
- Still open: it has not been on a real Flip. Samsung's own
  [sample](https://developer.samsung.com/codelab/galaxy-z/widget-flex-window.html) gives the cover
  screen's widget about 352 by 339dp, which is the size it was laid out and checked at. What size a
  Flip really gives it, how it sits around the camera cut-out, and starting the voice from the
  cover screen (which opens the book, so needs the phone open) are to be seen on one.

### Your reading (S–M)
A page with the time read this week, this month and this year, the longest streak, a calendar of
the days read (the more read on a day, the stronger its colour) and the books finished with their
dates. It opens from the line of today's reading in the library, and from Settings.
- KOReader's reading calendar is popular enough that
  [other tools copy it](https://github.com/paviro/KoShelf).
- All of it comes from the minutes per day Jacket already kept (`data/ReadingStats.kt` does the
  sums). Days are kept for a little over a year, so the calendar goes back that far.
- For a screen reader the calendar is one sentence ("Read on 18 days in November 2026, 6 hours in
  all"), not a grid of numbers.
- Still open: nothing is shown per book (time spent on each), and listening time isn't counted,
  only reading time, as before.

### A clear message for an outdated Android System WebView (S)
On a phone whose web view has never been updated (Android 8.0's is version 58, from 2017), a book
opened on its first page and couldn't be paged through. The reader now says so in place of the
pages, with a button to that app's page in the Play Store.
- The oldest web view let through is 64, the first with `ResizeObserver`, which Readium 3.4's page
  script needs to lay pages out (`reader/WebViewCheck.kt` has the reasoning). Readium itself
  states no minimum.
- Still open: no web view between 64 and 90 has been run for real (58 fails, 91 works; in between
  rests on reading Readium's scripts and on a current web view with the newer parts taken away).
  A phone without the Play Store gets the same message, and its button opens the browser.

### Say it this way (M)
A name the voice gets wrong can be written for it the way it sounds ("Hermione" as
"her-MY-oh-nee"), and the small numbers that point to notes are passed over when a book is read
aloud.
- Asked for on [MobileRead](https://www.mobileread.com/forums/showthread.php?p=4589542) and on
  [another reader app's board](https://voicereader.featurebase.app/p/option-to-correct-pronunciations-in-voice-reader).
- Select a word on the page and choose "Say it this way", or add one from the list (the voice's
  sheet in the reader, and Settings, under Listening). A word is fixed for the book that is open,
  or for every book. "Hear it" says it in the voice that reads the book.
- It is a plain respelling, so every voice reads it: Jacket's natural ones and the phone's own
  (`voice/SayIt.kt`; both are read through `voice/SpokenEngine.kt`). A word saved with a capital
  letter is a name, and is left alone where the page has it in small letters.
- The ink stays on the page's own words: each word the voice reports is told back as its place on
  the page, so "her-MY-oh-nee" inks in "Hermione".
- Notes' numbers are the ones a tap already shows as a note over the page (`reader/Notes.kt`); they
  are found in each chapter file the voice comes to (`reader/SpokenNotes.kt`). "Skip note numbers"
  is a switch, on by default. A number after a full stop no longer runs two sentences together.
- The words are kept in the library and in backups. Backups from 1.1 and earlier restore as before.
- Still open: a fix is a respelling only (no way to give a word's sounds exactly, or its stress,
  beyond capitals and hyphens, and how a voice takes those is its own affair); a word is matched as
  written, so "Hermione" doesn't cover a language's other endings of the name; and a very long
  chapter file (over about a second to read through) starts with its first notes' numbers still
  read out.

### Book posture (S–M)
Held half-open like a paperback, with the fold upright, the fold is the spine: a page on each
side of it, nothing of the reader's across it.
- [Play Books does this](https://goodereader.com/blog/electronic-readers/google-play-books-update-foldable).
- What the spread did half-open before: exactly what it does flat. The two pages already sat
  either side of the middle, 40dp clear of the fold with the usual margins; but the toolbar, the
  chapter slider and the sheets lay across the fold, and text larger than about 112% was one page
  running right across it.
- Now the controls sit on the right-hand page, laid out as on a phone of that width, and sheets
  come up there too; the book's name and the footer's line keep to the left-hand page. Text of any
  size is two pages, and narrow margins widen just enough to keep the words 28dp from the fold
  (`reader/BookPosture.kt`). A soft shadow marks the spine.
- With the usual text size and margins the page itself doesn't change at all, so nothing is laid
  out again as the phone is opened and closed. Tabletop, the closed cover, the flat spread and
  phones that don't fold are as they were.
- Still open: it has only been on the emulator (a real fold's crease may want more or less than
  28dp); a book read by scrolling, a PDF and a comic are left as they are, across the fold; and the
  small dialogs (the sleep timer's length, "Say it this way") still open in the middle.

## Next

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
