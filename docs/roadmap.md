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

Shipped: TalkBack, keyboard-only use, large text up to 300% and AA contrast. A highlight or a note
can be made without press-and-hold: a button on the reader's top bar, and TalkBack actions, mark
the sentence being read aloud or the first paragraph on the page. Still open: choosing any other
words to highlight without touching the page, PDF and comic zoom without pinching, and reading the
page's own text with TalkBack inside the paginated view (Listen is the dependable route).

## Shipped in 1.2 (12 November 2026)

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

## Shipped in 1.3 (12 November 2026)

### Resume listening without opening the book (L)
A headphone button, the notification, the lock screen or the player widget starts the voice in the
book being read, at the place it was left at, without opening it: also after Android has closed
Jacket. Opening the book finds the same voice at the sentence it is on, reading on.
- Asked for [for the car](https://discuss.grapheneos.org/d/22043-any-android-auto-compatible-ebook-reader-that-uses-installed-tts-engine),
  and [Librera added it](https://newreleases.io/project/github/foobnix/LibreraReader/release/9.5.7).
- The player used to live in the reader's screen: once Android had closed Jacket a headphone
  button had nothing to play, and the widget's Play had to open the book.
- Now the voice belongs to the app (`reader/Listening.kt`, `reader/ReadAloud.kt`). It reads with
  or without pages, and a reader that opens the book hands it its pages to turn and mark. The
  phone sees one player (`reader/BookPlayer.kt`) behind `reader/ListenService.kt`, which Media3
  can start with nothing playing and ask to resume.
- The sleep timer and the widget's skip and sleep buttons work with no reader open. Next and
  Previous on a headset, the notification and the lock screen move a sentence (they did nothing).
- Closing a book still stops its voice, and so does opening another book or swiping Jacket away
  from Recents.
- The percent never goes down by itself: a place counted again (the book opened after listening
  with it shut, its pages laid out anew by a fold) keeps the number it had, and only going back
  through the book lowers it. The library marks the book being read aloud, and the "Continue
  reading" pane's Listen button pauses it and takes it up again.
- Play with nothing that can be read aloud says so while Jacket is on screen. From the
  background it can't: Android only shows such a message for an app allowed to post
  notifications, and Jacket doesn't ask to be.
- Still open: it has only been on the emulator, with `cmd media_session` standing in for a
  headset.

### Read PDFs aloud (L)
A PDF is read aloud like any other book: by a natural voice or the phone's, at any speed, with
the sleep timer, a sentence on and back, "Say it this way", the screen off or the book shut, from
a headphone button, the notification and the widget. The pages turn with the voice, and the
sentence it is on is marked where it is drawn, each word coming up dark as it is spoken. A tap on
a line has the voice read on from there.
- Read-aloud rivals lead with PDFs
  ([comparison](https://www.audeus.com/blog/speechify-alternatives-pdf-epub)).
- The words come from Android's own PDF reader, which hands over a page's text and says where
  any stretch of it is drawn (`PdfRenderer.Page.getTextContents()` and `selectContent()`): on
  Android 15 and later, and on Android 12 to 14 where a Google Play system update has brought the
  same reader (`PdfRendererPreV`, SDK extension 13). Nothing is added to the app for it, and
  nothing needs the internet (`importer/PdfWords.kt`).
- On Android 8 to 11, and on a 12 to 14 that never had that update, there is no way to a PDF's
  words short of carrying a PDF reader inside Jacket. There, Listen stays on the toolbar and
  says so when it is pressed. `androidx.pdf` (Apache-2.0, no internet permission) was looked at
  and is no help: it is a wrapper for the same two readers, and refuses text on older phones.
- The lines are put in reading order from where they are drawn (`importer/PdfText.kt`): running
  titles and page numbers are left out, columns are read one after the other, a word broken at
  a line's end is one word, a paragraph that runs over a column or a page is one paragraph (no
  sentence is cut at a page's edge), and a caption that sits in the middle of a sentence is read
  after the paragraph.
- The voice reads plain pages made of those paragraphs (a small EPUB, as for a text file; kept
  on the phone and out of backups), which is why it is the same voice and the same player.
  `importer/PdfLayout.kt` goes from a sentence back to the page and the line it is drawn on.
- The place is kept as the page the sentence is on and how far down it. The percent goes up as
  the voice reads and never falls unless the listener went back.
- A PDF with an outline gets its contents: the list to choose from, the player's steps by
  chapter, and "end of chapter" for the sleep timer. Without one, the player steps by page.
  (Android doesn't hand over the outline. It is read from the file: `importer/PdfOutline.kt`.)
- Pages with no words (scans) are passed over. A PDF with none says so when Listen is pressed,
  and a Play from a headphone button goes to the next book that can be read aloud.
- Still open:
  - It has only been on the emulator, on Android 15. The Android 12 to 14 way
    (`PdfRendererPreV`) has since been run on that same emulator, which carries it too, and on
    no Android 12 to 14 phone.
  - Reading order goes by the page's geometry, not its meaning. A table, a sidebar, a footnote
    in the middle of a sentence and columns that the file draws row by row are read as they come.
    A caption is only known by how it opens ("Figure 3."). A compound word broken at its own
    hyphen loses the hyphen ("well-" "known" is read "wellknown").
  - Scans are not read: that would take text recognition, which Jacket doesn't have.
  - A PDF's contents list only shows where its words can be read (Android 12+, as above), though
    the outline itself could be read on any phone.
  - Reading a long PDF's words takes a moment the first time it is opened (about 3 seconds for
    440 pages on the emulator). Listen pressed before then says it is getting ready.
  - A page saved by scrolling is counted as before (only the voice's place goes through the
    rule that the percent never falls).
  - Text that runs right to left, and vertical text, haven't been tried.

### Catch up on a second device (M)
Read on two devices (a foldable and a tablet, say) and pick up on one where you left off on the
other. Each device keeps one small file in a folder they share, written by nobody else:
"Jacket – Tablet.json". The reader's own sync app (Google Drive, Dropbox, Syncthing) or a USB
stick carries the files; Jacket only reads and writes them through Android's file picker, and
still has no internet permission (`data/CatchUp.kt`).
- Sync is what [ReadEra charges for](https://readera.org/premium).
- A place is never taken, only offered: "On Tablet you're on page 212, in Chapter 9. Go there".
  One tap goes there and leaves the way back on offer; ✕ puts it away until that device reads on.
  The library says it once when Jacket opens, the book's own page when it is opened.
- Bookmarks, highlights, notes and collections are merged by id, each change counting one more
  than the last so that no clock is compared (`data/CatchUpRules.kt`). A deletion is kept as one,
  so it doesn't come back from a device that hasn't heard. Only what the reader deleted counts: a
  book removed from one device deletes its highlights nowhere else. Settings, book files, the
  watched folder and covers stay on each device.
- One file per device means two devices writing at once write two files. A sync app's copy of a
  file ("… (1).json"), a file only half arrived and an old copy come round again are all
  harmless: of a device's copies the one it wrote last is used, by its own count of its writes,
  and a file that doesn't read whole is left alone (`data/DeviceFile.kt`).
- Folders: Android's folder picker only shows an app that says it has folders to give. The
  Google Drive app (2.26.447, November 2026) says so on Android 11 and later and not before
  (read from the app itself; not tried with an account). So where a folder can't be chosen, the
  reader saves this device's file and adds the other devices' files one by one instead.
- Still to do: a run against a signed-in Drive, Dropbox and OneDrive on real devices; tidying
  away a device that is no longer used; carrying "finished" and pins.

### FB2 books (M)
FB2 is one XML file that holds a whole book, and the format most books are passed around in
among Russian and Ukrainian readers, as often as not zipped. Jacket opens `.fb2`, `.fb2.zip` and
`.fbz` in the full reader: pages, contents, listening, highlights, notes, search and bookmarks.
- It is read through a small EPUB made from it, the way a text file is (`importer/Fb2.kt`,
  `importer/Fb2Book.kt`). The file stays the book: it is what the library keeps, what a backup
  and catch-up name (by its bytes, zipped or not), and what is added again after a restore. The
  pages are kept where no backup looks and made again when they are missing.
- What the file says becomes what an EPUB says: title, authors, language and annotation (on a
  first page of its own); the cover from the picture the file carries; sections as chapters and
  as the contents, as deep as they are nested; emphasis, epigraphs, quotations, poems, subtitles,
  tables and pictures.
- The notes (the body named "notes") go at the back, each marked as a note and every link to one
  as a note's number. So a tap on the number shows the note in the pop-up, and the voice passes
  the number over, with nothing new in the reader for either.
- It is read with the XML reader Android already has; no library was added. UTF-8, UTF-16,
  windows-1251, KOI8-R and anything else the file names are read; a file that names nothing and
  isn't UTF-8 is taken for windows-1251.
- The file is not trusted. Nothing outside it is ever fetched: the XML reader is never told to
  act on a DOCTYPE, and a file that declares an entity is turned away before it is read. No more
  than 96 MB is read, zipped or not, and elements nested deeper than 64 end the reading. All the
  markup of the pages is written by Jacket from escaped words, links lead only into the book, to
  the web or to a mail address, and only JPEG, PNG, GIF and WebP pictures are taken.
- Android doesn't know what an `.fb2` file is: it calls it "application/octet-stream". So beside
  FB2's own types, Jacket is offered for files of the generic types only when their name ends in
  `.fb2`, `.fb2.zip` or `.fbz`, and stays out of every other file's "Open with". Share takes
  FB2's own types only.
- Still open:
  - A link that doesn't carry the file's name (the Downloads view of Files gives
    `…/document/msf:123`) can't be told to be FB2, so Jacket isn't offered for it: such files
    are added with the + button, which takes them. The same goes for a share from an app that
    calls the file "octet-stream".
  - FB3, and FB2's rarer parts: `<style>` with a named style is set as plain words, and a
    stylesheet in the file is ignored.
  - A picture bigger than 32 MB is left out, and a very large book is read into memory while
    its pages are made (once; they are kept).
  - It has only been on the emulator, with files made for it. No shop's or library's FB2 yet.

### Password-protected PDFs (S–M)
A PDF locked with a password used to fail to import. Now it is added like any other, and the
reader asks for its password in a small dialog; a wrong one says so (`reader/PdfLock.kt`).
- Android opens it: `PdfRenderer` with `LoadParams` on Android 15, `PdfRendererPreV` on Android
  12 to 14 with SDK extension 13 (the same phones that hand over a PDF's words). Reading,
  read-aloud and search all use the one password. Older phones say plainly why they can't.
- The password is held while Jacket runs. With "Remember on this phone" it is kept for that
  book, sealed with a key in Android's keystore, in a file under the app's no-backup storage
  (`data/PdfPasswords.kt`): not in the library, so not in a backup or a catch-up file, and not
  in Android's own backup or a phone-to-phone transfer. "Forget password" in the book's menu
  removes it.
- The words kept of a PDF for reading it aloud are its text unlocked. For a locked PDF they are
  kept only while its password is remembered, and nothing of it is read aloud without the
  password to hand: a Play from a headphone button passes over a locked PDF nobody has opened.
- Still open:
  - A locked PDF has no contents list and no title or cover of its own in the library: its
    outline and its title are encrypted in the file, and Android hands over neither. (The cover
    could be drawn from the first page once it has been opened.)
  - On Android 12 to 14 it has only been run on the Android 15 emulator, made to take that way.
  - Tried with one PDF, made by LibreOffice. Other makers' encryption (AES-256 from Acrobat,
    certificates) hasn't been.

### Search inside PDFs (S–M)
Where Android hands over a PDF's words, they are searched as an EPUB's are: results stream in
with the page they are on and the words around them, and choosing one goes to its page and marks
the match where it is drawn (`reader/PdfSearch.kt`, `reader/TextSearch.kt`).
- What is searched is what is read aloud: paragraphs in reading order. So a word broken at a
  line's end and a phrase that runs over a page are found whole, and running titles and page
  numbers are not. The mark is drawn from the boxes Android gives for those letters, as the
  sentence being read aloud is.
- Typing is forgiving: any case, with or without Latin and Greek accents, plain quotes and
  hyphens for curled ones and dashes, "е" for "ё". Letters that are letters of their own stay so.
- The way back to where the reader was is on offer after the jump, as after any other.
- Still open:
  - A match is found inside one paragraph: words that end one paragraph and begin the next are not.
  - On phones that can't get at a PDF's words (Android 8 to 11) there is no search, and no
    Search button, as before.

## Shipped in 1.4 (12 November 2026)

### Highlights and notes on PDFs (M)
Where Android hands over a PDF's words (Android 15, or Android 12 to 14 with SDK extension 13),
a PDF takes highlights and notes as other books do (`reader/PdfHighlights.kt`,
`reader/PdfMarks.kt`).
- **Choosing words.** A PDF's page is a picture, so Jacket does the selecting itself, from what
  it already knows of the page: the paragraphs it is read aloud as, and where each of their
  lines is drawn (`importer/PdfLayout.kt`). A press and hold selects the word under the finger
  (Android is asked where each word of that line is drawn), the finger can go on to take more,
  and two handles stretch the selection afterwards. What is selected runs in reading order: down
  one column and then the next, and over a page's edge. A handle held at the top or the foot of
  the screen moves the pages.
- **The menu** by the selection: the five colours (one tap highlights in that colour), Note,
  Copy, Share, "Say it this way", "Read aloud from here", and the phone's own text actions
  (Translate and the like). It is Jacket's own menu, not the system's: there is no web page
  here to ask for one. It waits while a finger is choosing or the pages are moving.
- **Without press-and-hold.** The top bar's "Highlight or add a note" marks the sentence being
  read aloud, or the first paragraph on the page (a line by itself that ends no sentence is
  taken for a heading and passed over). It is the same button, menu and screen-reader actions
  as in other books, so it works with TalkBack and a keyboard.
- **Where a highlight is kept.** As the page it begins on, its words, and about 40 letters of
  what stands before and after them, in the highlight's own place (`Annotation.locator`, as
  JSON: `{"pdf":1,"page":…,"before":…,"after":…}`). Never a paragraph number: paragraphs are
  made by Jacket from the page's lines, and are numbered differently whenever the reading order
  gets better. The highlight is found again by looking for its words in the page's own, spaces,
  the hyphens of broken words and ligatures aside; where the same words stand twice, by what
  stood around them; on the pages either side if not on its own; and a highlight of several
  paragraphs that is no longer found whole (they are read in another order) is looked for a
  paragraph at a time. So it survives reopening, a later Jacket, and another device, which
  reads the same file its own way. A Jacket from before this lists such a highlight by its
  words and page and leaves the place alone.
- **Drawing.** Each highlight is a wash of its colour over its lines, from the boxes Android
  gives for those letters (as the search mark and the sentence being read aloud are), with a
  dotted line under one that carries a note. It is drawn inside the zoomed page, so it follows
  zoom and scroll for nothing. A tap on one opens the editor: colour, note, delete.
- **Everywhere else** a highlight goes, a PDF's goes: the list beside the contents (with its
  page, and choosing one goes to its line), "Save as a file" and sharing (the page is said:
  "Chapter · Page 12 · 30%"), backups, and catch-up between devices, deletions included.
  Nothing was added to those: a PDF highlight is a highlight.
- **Older Android** (8 to 11, or 12 to 14 without the update), and scans: nothing can be
  selected or drawn, and nothing is offered. A press and hold says why, once, and so does the
  list of highlights; highlights that came from another device are listed there, and can be
  read, edited and deleted.
- Found on the way: the pages said where they were in the window by what of them was in sight,
  so a tap on a line of a page whose top had scrolled off (to have the voice read from there)
  was counted from the top of the screen. They now say where the whole page is.
- Still open:
  - It has only been on the emulator, on Android 15; the Android 12 to 14 way was run there
    too, and on no such phone.
  - Selecting goes by whole words. In writing with no spaces Chinese and Japanese go by the
    character; Thai, Lao and Khmer go by the whole run between spaces.
  - Text that runs right to left hasn't been tried. The handles are meant to change sides for it.
  - Running titles and page numbers can't be selected: they are no part of what is read.
  - On a page the PDF reader couldn't place the lines of, those lines can't be selected.
  - A highlight whose words are no longer found on its page (another edition of the file) is
    listed and not drawn.
  - The menu is tall. Where it fits neither above nor below nor beside the selection, it sits
    at the foot of the screen, over part of it.
  - The EPUB menu is still the system's, with one Highlight (in the colour last used) and no
    "Read aloud from here".
  - The selection menu can't be reached with a keyboard alone (nor can a press and hold): a
    keyboard has the top bar's button.

## Next

### Android Auto (M)
Choose a book on the car's screen and have it read from where it was left.
- The groundwork is in 1.3: `reader/ListenService.kt` lists "Continue listening" and "Recent
  books", choosing one plays it from its saved place, and a book can be asked for by name. Only
  the system, a car and apps allowed to see notifications are given the lists.
- Debug builds declare it to Android Auto (`app/src/debug/AndroidManifest.xml`), to try it. A
  build that isn't from Google Play only shows in Android Auto with its "Unknown sources"
  developer setting switched on.
- Left before release builds declare it: an activity that takes "play ... on Jacket" from the
  phone's assistant (`android.media.action.MEDIA_PLAY_FROM_SEARCH`), covers in the car's lists
  (they need a content provider), and a run in a real car or the Desktop Head Unit.
- Risk: Android Auto apps get their own Play review, on every release from then on.

## Not planned
- **A natural voice for Chinese:** a voice that reads Chinese well would push the app past
  Google Play's 200 MB download limit (see Size budget). The phone's own voice reads Chinese.
- **Listening to a book in another language, translated on the phone:** translation on the
  phone needs either the internet or models far larger than the whole app.
- **"Currently reading" from Hardcover and Open Library, and ISBN scanning for covers:** both
  need the internet, which Jacket doesn't use. Jacket already makes covers and lets you design one.
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
