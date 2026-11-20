export const meta = {
  name: 'matura-sets-editor-platform',
  description: 'Fix confirmed audit defects in the set editor and set list, accessibility gaps, bitmap-font fallbacks, save-file hardening and Android 16 platform settings in an isolated worktree, with tests, two independent reviews and fixes',
  phases: [
    { title: 'Platform', detail: 'manifest, activity, repository holder, crash-window read' },
    { title: 'Editor and sets', detail: 'no lost work, own sets first, catalogue read-only, accessibility, font fallback' },
    { title: 'Review', detail: 'data-safety reviewer, platform and accessibility reviewer' },
    { title: 'Fix', detail: 'apply confirmed findings, full suites' },
  ],
}

const WT = '/home/user/Matura/.claude/worktrees/zestawy'
const S = '/tmp/claude-0/-home-user-Matura/0e5748ef-99b0-59e1-b03e-46ac0f545d18/scratchpad'
const BASE = '0aab7c5'

const COMMON = `
You are working on an Android app (Kotlin, Jetpack Compose): "Matura", an offline pixel-art game for learning English
vocabulary for the Polish basic-level matura exam. Store version 0.9.18 (Google Play, package com.matura.app).
Audience: Polish 18-19 year olds in their final school year. No accounts, no server, no INTERNET permission; all
state is one JSON file written by data/Repository.kt.

WHERE YOU WORK
A dedicated git worktree: ${WT}  (branch wt/zestawy, created from commit ${BASE}).
ALL your reading, editing, building and committing happens inside that directory. Use absolute paths under it and
start every shell command with:  cd ${WT} && ...
NEVER edit, build or run git commands in /home/user/Matura itself or in any other worktree under
/home/user/Matura/.claude/worktrees (three other teams work there). Never push. Never touch git config. Do not
create other worktrees or branches.

BUILD AND TEST
  ${S}/tools/wt-test.sh zestawy <label>                              all debug unit tests, compact summary
  ${S}/tools/wt-test.sh zestawy <label> --tests '*Persistence*'      one class or pattern
  ${S}/tools/wt-test.sh zestawy <label> :app:testReleaseUnitTest     release variant
  plain Gradle: cd ${WT} && export ANDROID_HOME=/opt/android-sdk && ./gradlew --console=plain :app:assembleDebug
Three other Gradle builds may run on this 4-core, 16 GB machine at the same time: builds can take several minutes.
Wait for them; never kill Gradle or Kotlin daemons, never run "gradlew --stop", never delete ~/.gradle. Run one
Gradle command at a time.
Baseline at ${BASE}: testDebugUnitTest and testReleaseUnitTest are green (494 tests in debug, 439 in release).
Tests under app/src/test run in both variants and must not use Compose test rules; Compose and Robolectric screen
tests live under app/src/testDebug (look at BladZapisuWidocznyTest.kt, SystemKeyboardTest.kt, A11ySemanticsTest.kt,
TouchTargetAuditTest.kt and PodgladEkranowTest.kt for how screens are composed, driven and rendered to PNG).

WHO ELSE IS CHANGING WHAT (their branches will be merged with yours, so stay out of their lines)
- Team "petla" rewrites ui/PlayScreen.kt, the Play, DzisiejszaSesja and TrudneSlowa branches of ui/App.kt, the
  result and session texts in ui/Strings.kt, learn/Sesja.kt, and adds a parameter to Repository.recordGame.
- Team "dorobek" edits model/Models.kt (new fields on WordProgress, Options, SaveData), learn/Srs.kt, and in
  data/Repository.kt: recordAttempt, recordGame, migrate and SCHEMA_VERSION in the companion object.
- Team "dopasowanie" edits ui/TrueFalseScreen.kt, ui/MultipleChoiceScreen.kt, ui/TimeAttackScreen.kt, game/Matching.kt,
  game/VoiceMatch.kt, ui/VoicePolicy.kt, and may make the helper functions of A11ySemanticsTest and
  TouchTargetAuditTest internal.
- A later team rebuilds MenuScreen, OptionsScreen, AchievementsScreen and ScoresScreen in ui/Screens.kt.
YOUR FILES: ui/Screens.kt (ONLY SetsScreen, SetEditorScreen, FieldInput, ChoiceChip, DirectionRow and helpers you
add for them), ui/Common.kt, ui/PixelFont.kt, MainActivity.kt, AndroidManifest.xml, data/Repository.kt (ONLY
upsertSet, the read path readBestAvailable/read, and new functions you add at the END of the class, far from
recordAttempt, recordGame, migrate and the companion object), new files under data/, the Sets and Editor branches of
ui/App.kt (nothing else in that file), ui/Strings.kt (add your texts as ONE contiguous block directly after the
existing set and editor strings; do not reorder or reformat anything), and NEW test files. Do not edit existing test
files except where a test pins behaviour you deliberately change (say so in your report) and the one helper
visibility change named in your stage. Do not touch model/Models.kt: if you need a new persisted type, put it in
your own new file.

CODE CONVENTIONS OF THIS REPOSITORY (follow them exactly)
- Read each file you change from top to bottom first and match it. Comments explain WHY, in Polish (data/ and
  MainActivity write Polish without diacritics; ui/Screens.kt mixes English and Polish, follow the function you are
  in). Write new comments the same way and only where they explain a decision a reader could not guess. When you
  change behaviour that a comment describes, update the comment.
- NO em dashes and NO en dashes in anything you write (comments, strings, test names, commit messages): the owner
  removed them from the tree on purpose. Use a colon, a comma, a full stop or brackets. If a line you edit contains
  one, replace it; do not hunt for others.
- Texts a student can see go through Str in ui/Strings.kt (val x get() = pick(pl, en)), Polish and English. Polish
  must be correct and natural, with diacritics; English plain and correct. Tone: direct, calm, a little playful,
  never childish. Text drawn with PixelText uses a bitmap font that has ONLY A-Z a-z 0-9, Polish letters, space and
  . , ! ? : / - _ + ' plus the multiplication sign and infinity: no parentheses, no percent sign, no equals sign, no
  quotes, no arrows. Text drawn with the system font (Text) may use any punctuation except the forbidden dashes.
- Project principles: nothing typed by the student is ever thrown away silently; honest messages (say what really
  happened); no new permissions; nothing leaves the device.
- Commit your work in the worktree when your stage is done (git add the specific files, then git commit). Commit
  message in English: a short subject, a body that says what and why, no forbidden dashes, ending with exactly:
    Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_016ardp75CeKgBASgp8eAmD5
- Your final message is read by the engineer who merges the branches. Report: what you changed (file and function),
  what you deliberately did not change and why, every behaviour change a user can notice, test counts before and
  after, anything you could not verify without a real device. Plain prose.

BACKGROUND MATERIAL (audit reports written against the same code; read the findings named in your stage)
  ${S}/res-audit-screens.txt          screens, editor, set list, accessibility, bitmap font
  ${S}/audit-audit-persistence.txt    save file, repository
  ${S}/res-audit-build-release.txt    manifest, build, platform
`

const SPEC_PLATFORM = `
STAGE 1 OF 2: PLATFORM AND SAVE-FILE HARDENING (small, careful changes; every one needs a test or a measured check).

P1. AndroidManifest.xml
  a. Text-to-speech (finding tts-queries-missing): add an <intent> with action android.intent.action.TTS_SERVICE to
     the <queries> block, with a comment in the style of its neighbours saying why (own sets are read by the system
     voice; without the entry Android 11+ can hide the engine and the speaker button does nothing).
  b. Android 16 large screens (finding portrait-lock-android16): the app targets SDK 36, where the portrait lock is
     ignored on displays of 600 dp and wider unless the app is a game. It IS a game: add android:appCategory="game"
     to <application>. The lock also makes portrait an implied REQUIRED feature, which filters devices in Google
     Play exactly like the microphone case the manifest already documents: add
     <uses-feature android:name="android.hardware.screen.portrait" android:required="false" /> with a comment.
     Verify on the built debug APK with aapt2 (under /opt/android-sdk/build-tools/*/aapt2 dump badging <apk>): no
     uses-implied-feature line for screen.portrait or microphone remains, and application-category or the game
     flag shows. Put the before/after lines in your report.
  c. Configuration changes (finding configchanges-state-loss): today a font-size or display-size change, a folded
     or unfolded phone, split screen or a Bluetooth keyboard recreates the activity, and because every screen
     keeps its state in plain remember the student is thrown back to the name screen and loses a running round or
     a half-typed set. The UI is pure Compose and re-reads configuration and density, so declare the remaining
     changes as handled: add screenLayout|smallestScreenSize|keyboard|navigation|density|fontScale|layoutDirection|
     locale to android:configChanges. Then PROVE it is safe: search the UI code for values that are computed once
     from density, font scale or screen size and cached without a key (remember { ... } around toPx(), dp
     conversions, bitmaps scaled for a density, LocalConfiguration reads). Fix what you find by keying on the
     value. Add a Robolectric test that starts MainActivity, sends a configuration change with a different
     fontScale and a different screen size through the ActivityController, and asserts the activity instance is
     the same and was not recreated.
P2. MainActivity.kt
  a. Status bar (finding status-bar-icons-dark-on-dark): the app is always dark, but enableEdgeToEdge() without
     arguments picks dark icons when the phone uses the light system theme. Call it with
     SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) for both bars. Update the comment.
  b. One repository per process (finding per-activity-repository-cache): each activity instance builds its own
     Repository with a private cache, so a second live instance (the well-known launcher / installer double start
     on Android 7 to 9) can overwrite newer data with an old copy. Add a process-wide holder in a NEW file under
     data/ (do not edit Repository's companion object, another team is changing it) that returns the same
     Repository for the same application context, use it in MainActivity, and finish a duplicate launcher instance
     (not task root, action MAIN, category LAUNCHER) before setContent. Tests must still be able to build their own
     Repository over a temporary directory, as they do now.
P3. data/Repository.kt, read path only (persistence audit, point 1): between the two renames of an atomic save the
     main file does not exist; a kill there leaves matura.json.bak (previous state) and a complete matura.json.tmp
     (newest state), and the next start silently loses one save. When the main file is missing, prefer a .tmp
     that parses over .bak. Do not write from load(). Add a test in a NEW test file that builds exactly that
     on-disk state. Do not change anything else in the save or load order.
P4. Small truths in files nobody else touches (comment and tool fixes only, no behaviour):
  - app/build.gradle.kts, the comment above isMinifyEnabled = false says R8 is off because most of the package is
     PNG graphics. Measured: PNG is 0.4 percent of the release APK, audio 49 percent, dex 44 percent, and R8 would
     cut the download by about a third. Leave minification OFF (a minified build needs one manual pass on a phone,
     which cannot happen here) and rewrite the comment to say exactly that, including that rules for the offline
     voice variant (com.sun.jna, org.vosk) are missing. Do not change any build setting.
  - tools/vosk-oov.py and tools/vosk-slim.py point at app/src/main/assets/vosk; the models now live in
     app/src/vosk-assets/vosk (see tools/vosk-fetch.sh). Fix the path constant and the two docstrings, and make
     vosk-oov.py print a hint to run vosk-fetch.sh when the directory is missing.
  - keystore.properties.example says a lost key ends updates forever. With Play App Signing (which this app
     uses) a lost UPLOAD key can be reset through Play Console support: correct the sentence, keep the warning to
     keep the key safe.
Run the full debug and release suites and assembleDebug. Commit. In your report list every hit of the density and
font-scale search in P1c with your verdict on each.
`

const SPEC_EDITOR = `
STAGE 2 OF 2: SET EDITOR, SET LIST, ACCESSIBILITY, FONT FALLBACK. Stage 1 is committed in this worktree (git log -3).
Findings to read first: editor-loses-unsaved-work, sets-list-own-sets-buried, a11y-editor-and-selection-state,
pixel-font-blank-glyphs (screens audit); editor-discards-set-on-failed-save, column-swap-keeps-direction-keys
(persistence audit). All are confirmed.

E1. The editor must never lose typed work.
  a. Leaving with unsaved changes: when the draft differs from what was opened (title, languages, any row), the
     on-screen back button and system back do NOT leave. They show an inline card at the top of the editor (no
     system dialog; the app has none): "Masz niezapisane zmiany." with three actions: ZAPISZ (same as the save
     button), ODRZUĆ ZMIANY (leave without saving) and WRÓĆ DO EDYCJI. Without changes, back leaves at once.
  b. Incomplete rows: today rows with only one side filled are dropped silently on save. New rule: fully empty
     rows are dropped as before; if any row has exactly one side filled, ZAPISZ does not close the editor: those
     rows are marked (border in Palette.Danger plus a text marker, never colour alone), a message names their
     numbers, and the student chooses UZUPEŁNIĘ (stay) or ZAPISZ BEZ NICH with the count in the label. Saved sets
     must still contain only complete rows: every game mode and the daily plan rely on that.
  c. Failed write: Repository.upsertSet returns false when the file could not be written. Today App closes the
     editor anyway and the typed set is gone. Change the Editor branch of ui/App.kt so the editor closes only when
     the save succeeded; on failure it stays open with a clear message (reuse the tone of str.saveFailed, but say
     the set was NOT saved and the rows are still here). onSave may return Boolean.
  d. Process death and restart (the student switches to a dictionary app and Android kills the game; the
     navigation stack is plain remember, so the app restarts on the name screen): keep a draft on disk. New file
     data/SzkicZestawu.kt: a small @Serializable draft (set id or null for a new set, title, both languages, rows)
     stored in its own file szkic-zestawu.json in filesDir, written atomically (tmp then rename), with
     zapisz / wczytaj / usun. The editor writes the draft about one second after the last edit (debounced in a
     LaunchedEffect) and when the activity stops (lifecycle ON_STOP), and only when it differs from what was
     opened. When the editor opens and a stored draft exists for the same set id (null matches null) and differs
     from the opened content, show a card: "Znaleziono niedokończony szkic z ostatniego razu." with PRZYWRÓĆ and
     ODRZUĆ. A successful save, ODRZUĆ ZMIANY and ODRZUĆ delete the draft. A draft for a different set is left
     alone and offered when that set is opened. A draft for a NEW set must be discoverable: when one exists, the
     "create set" button on the set list shows a second line "masz niedokończony szkic". Unreadable draft file:
     ignore and delete it. Unit-test the store with a temporary directory (see PersistenceTest for the pattern).
  e. Deleting a row with text in it is immediate today. Keep it immediate but offer COFNIJ: a one-row undo bar
     that stays until the next edit or deletion.
E2. Swapping the columns (finding column-swap-keeps-direction-keys): progress is keyed by entry id plus direction.
     After a swap, each word's two direction records describe the opposite skills. In Repository.upsertSet, for an
     own set (not built in), for every row whose id is retained and whose (term, definition) equals the previous
     (definition, term), swap that id's TERM_TO_DEF and DEF_TO_TERM progress records inside the same save. Test it.
E3. The catalogue is read-only (finding sets-list-own-sets-buried, second half). Built-in sets (WordSet.builtIn or
     however the model marks them; read model/Models.kt and data/BuiltInSets.kt) can today be edited with no way
     back, an edit breaks the link to the catalogue ids that the random mix and the daily plan use, and the next
     vocabulary update replaces the set anyway. New rule: a built-in set opens in the editor as a COPY. On the
     set list its button reads SKOPIUJ DO MOICH; the editor then edits a new own set titled "<title> kopia"
     (font-safe), with fresh own-row ids (see learn/EntryId.kt for how own ids are made) and, so the student does
     not start from zero, with the progress records of the catalogue rows copied to the new ids at the moment the
     copy is first saved (new Repository function at the END of the class; test it: both directions copied, the
     catalogue word's own progress untouched, nothing copied if the student cancels). The swap button stays
     available only in own sets. Existing saves in which a built-in set was already edited are left exactly as
     they are (no migration here).
E4. Set list (finding sets-list-own-sets-buried, first half). SetsScreen shows own sets FIRST under a heading
     MOJE ZESTAWY (newest first; an empty state line when there are none), then the catalogue grouped by topic in
     collapsed folders, the way SetPickScreen in the same file already groups them (reuse its grouping; do not
     duplicate logic). After a save the list scrolls to the saved set and marks it with a short ZAPISANO pill until
     the screen is left. Keep delete for own sets with its existing confirmation step.
E5. Accessibility (finding a11y-editor-and-selection-state), in your files only:
  - editor: every text field has a label a screen reader can read ("Hasło 3" / "Term 3", "Tłumaczenie 3" /
     "Translation 3", the title field, the two language fields); the row delete button is named ("Usuń wiersz 3")
     and at least 48 dp; the swap button is named with str.swapLanguages and at least 48 dp.
  - ChoiceChip and DirectionRow expose selected state and a role (Modifier.selectable with Role.RadioButton, or
     toggleable where it is a switch), so TalkBack says which option is active. They are used by OPCJE and WYNIKI:
     do not change their signatures or their look.
  - ui/Common.kt: the "Wróć" button of ScreenScaffold is 37 dp high and GhostButton 47 dp. Make both at least
     48 dp without changing how they look (grow the touch area, not the art, if the layout would otherwise jump);
     check the previews of two screens before and after.
  - Add the editor (empty, filled, with the unsaved-changes card, with marked incomplete rows) and the set list
     (with and without own sets) to both accessibility gates in a NEW test file
     app/src/testDebug/java/com/matura/app/ZestawyEkranyTest.kt. If wymagajEtykiet / wymagajProgu are private to
     A11ySemanticsTest and TouchTargetAuditTest, make them internal there (the one allowed edit; another team may
     make the identical edit, which is fine).
E6. Bitmap font fallback (finding pixel-font-blank-glyphs), ui/PixelFont.kt only. A character outside the 93
     glyphs is drawn as a blank cell: the name "Zoë" shows as "Zo ", "Олена" as an empty line. (1) In glyphIndex,
     before giving up, decompose the character (java.text.Normalizer, NFD), drop combining marks and look the base
     letter up again; map ß to ss and the common ligatures and quotes to their plain forms where the sheet has
     them. (2) If a string still contains a character with no glyph, PixelText draws the WHOLE string with the
     system font instead (monospace, same colour, a size that matches the glyph height, single line with
     ellipsis), so the text is readable rather than full of holes. Built-in vocabulary and every UI label already
     fit the sheet and must render exactly as before: assert that with a test that walks Str (both languages),
     SesjaStr and all built-in titles and entries and checks that none takes the fallback. Add unit tests for the
     new mapping and render one preview image with the names "Zoë", "Олена" and a long name to look at.
E7. Look at what you built. Render the editor and the set list in the states listed in E5 at 320x568, 360x640
     and 360x740 dp into app/build/podglad-zestawy/ (copy the render helper from PodgladEkranowTest.kt into your new
     test file; do not edit that file) and LOOK at the images with the Read tool. Fix clipping, overlaps,
     unreadable contrast. List the image paths in your report.
E8. Behaviour tests (Compose, in ZestawyEkranyTest.kt or a second new file): back with a dirty draft shows the
     card and each of its three actions does what it says; save with incomplete rows keeps the editor open and
     ZAPISZ BEZ NICH saves only complete rows; a failed write (make the files directory read-only or inject a
     failing repository, see BladZapisuWidocznyTest.kt) keeps the editor open with the rows intact; the draft is
     written after an edit, offered on reopening and deleted after save; a built-in set opens as a copy and the
     catalogue set is unchanged after saving the copy.
Run the full debug suite, the release suite and
  cd ${WT} && ANDROID_HOME=/opt/android-sdk ./gradlew --console=plain -Pvosk=true :app:compileDebugKotlin
before committing.
`

const REVIEW_COMMON = `
You are an independent reviewer. Two commits were just made on branch wt/zestawy in the worktree ${WT} (compare
with the base: cd ${WT} && git diff ${BASE}..HEAD --stat, then read the full diff of the files in your lens and the
code around it). You did not write this code and you owe it nothing: your job is to find what is wrong with it
before students do. The specification the authors worked from is included below so you can judge the code against
it, but the specification can be wrong too: say so when it is.

HOW TO WORK
- Do not edit tracked files and do not commit. You may add throwaway probe tests with file names starting with
  ZzSonda under app/src/test/java/com/matura/app/ or app/src/testDebug/java/com/matura/app/ (you are the only one
  building in this worktree while you run), run them with ${S}/tools/wt-test.sh zestawy <label> --tests '*ZzSonda*',
  and you MUST delete them before you finish (git status --short must show a clean tree).
- Prefer evidence over opinion: a failing probe test, a concrete input with the actual output, a rendered image
  you looked at, a line of aapt2 output. A finding without a concrete scenario is not a finding.
- Report only what matters to a student, to the next engineer or to the release: lost data, wrong results,
  crashes, unreachable states, misleading text, a test that would pass with the feature broken, changes outside
  the files this team was allowed to touch (see WHO ELSE IS CHANGING WHAT in the specification: such edits will
  cause merge conflicts), violations of the repository conventions (em or en dashes in new lines, visible text
  the bitmap font cannot draw, missing Polish diacritics in visible text). Do not report style preferences.
- For each finding give: severity (high, medium, low), file and line, what happens, the smallest scenario that
  shows it, and the fix you recommend. Finish with a short list of things you checked and found correct.
`

const FINDINGS = {
  type: 'object',
  properties: {
    summary: { type: 'string' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          severity: { type: 'string', enum: ['high', 'medium', 'low'] },
          file: { type: 'string' },
          line: { type: 'integer' },
          what: { type: 'string' },
          scenario: { type: 'string' },
          fix: { type: 'string' },
          evidence: { type: 'string' },
        },
        required: ['id', 'severity', 'file', 'what', 'scenario', 'fix'],
      },
    },
    checkedOk: { type: 'array', items: { type: 'string' } },
  },
  required: ['summary', 'findings'],
}

phase('Platform')
const r1 = await agent(COMMON + SPEC_PLATFORM, { label: 'implement:platform', phase: 'Platform' })

phase('Editor and sets')
const r2 = await agent(COMMON + SPEC_EDITOR + `\nREPORT OF THE PREVIOUS STAGE (for context)\n${r1}`, { label: 'implement:editor-sets', phase: 'Editor and sets' })

phase('Review')
const LENSES = [
  {
    key: 'data-safety',
    prompt: `YOUR LENS: can a student still lose typed work or progress, and is saved data still what every other
part of the app expects?
Check in particular: (1) every way out of the editor (on-screen back, system back, save, save without incomplete
rows, discard, a failed write, activity stop, process death simulated by recreating the composition with only the
draft file left) and for each one where the typed rows end up; (2) the draft store: torn or empty file, a draft for
a set that was deleted meanwhile, a draft older than later edits made to the same set, two drafts (new set and an
existing set), a draft restored into a set whose rows changed; debounce: is anything written on every keystroke; is
the UI thread blocked noticeably for a 300-row set; (3) the copy of a built-in set: ids really fresh, progress
copied exactly once and only on save, catalogue untouched, title safe for the bitmap font, copy of a copy, what
the daily session plan and the random mix see afterwards (App.kt builds the plan from the ids of every entry of
every set: do duplicated words now appear twice in one day?); (4) the column swap fix: rows edited AND swapped in
one save, duplicate rows, a row whose term equals its definition, built-in sets excluded; (5) the crash-window
read: can a stale or half-written .tmp ever be preferred over a newer main file; (6) the process-wide repository
holder with tests that create several repositories; (7) anything in the diff that changes what is written to
matura.json for existing users.`,
  },
  {
    key: 'platform-a11y',
    prompt: `YOUR LENS: platform settings, accessibility and rendering.
Check in particular: (1) the merged manifest of the debug and release variants (build them; read
app/build/intermediates/merged_manifests/**/AndroidManifest.xml and run aapt2 dump badging on the APK): queries,
appCategory, implied features, configChanges; (2) the claim that handling density, fontScale, screenLayout and
smallestScreenSize in configChanges is safe: hunt yourself for cached pixel sizes, bitmaps, text measurers,
derived layout decisions and anything remembered without a key across the whole ui/ package (not only the files
this team touched), and test at least two screens by changing fontScale and screen size through the activity
controller and rendering before and after; (3) edge-to-edge call: does it compile against the androidx.activity
version in use and does a Robolectric test still start the activity; (4) the duplicate-launcher guard: can it
finish the only instance in a legitimate start (deep link absent; start from Play "Open"; start from recents;
split-screen; a start with FLAG_ACTIVITY_NEW_TASK)? Reason from the Android task rules and say what you could not
verify without a device; (5) accessibility gates: run them, then try to break them (an unnamed clickable, a 40 dp
target) to see that they fail; check selected state on chips with a semantics dump; (6) the bitmap font fallback:
render a mixed list of strings and LOOK at the image; confirm built-in text is pixel-identical to the base commit
for three screens (render at base by checking out the base files into a temporary copy is NOT allowed in this
worktree; instead compare against the images under /home/user/Matura/app/build/podglad/ which were rendered from the
base); (7) rendered editor and set list at 320x568, 360x640 and 360x740 dp: regenerate and LOOK at every image:
clipped buttons, overlapping text, blank glyphs, contrast; (8) Polish and English texts: grammar, diacritics,
tone, no forbidden dashes.`,
  },
]
const reviews = []
for (const lens of LENSES) {
  const r = await agent(
    REVIEW_COMMON + '\n' + lens.prompt + '\n\nSPECIFICATION THE AUTHORS WORKED FROM\n' + COMMON + SPEC_PLATFORM + SPEC_EDITOR +
      '\n\nAUTHORS\' REPORTS\n--- stage 1\n' + r1 + '\n--- stage 2\n' + r2,
    { label: `review:${lens.key}`, phase: 'Review', schema: FINDINGS },
  )
  if (r) reviews.push({ lens: lens.key, ...r })
}

phase('Fix')
const all = reviews.flatMap(r => (r.findings || []).map(f => ({ lens: r.lens, ...f })))
log(`review findings: ${all.length} (${all.filter(f => f.severity === 'high').length} high, ${all.filter(f => f.severity === 'medium').length} medium)`)
const fix = await agent(
  COMMON + `
FINAL STAGE: FIX WHAT THE REVIEWERS FOUND, THEN VERIFY EVERYTHING.
Two implementation commits are on this branch (git log -4). Two independent reviewers examined them. Their findings
are below as JSON. For EACH finding: reproduce it first (a failing test is best). If it is real, fix it and add or
extend a test that would have caught it. If it is not real, or the recommended fix would break a rule of the
specification, do not change the code and explain why in your report. High and medium findings are mandatory to
resolve one way or the other; low findings: fix when the fix is small and safe.
Then verify the whole branch:
  1. ${S}/tools/wt-test.sh zestawy final-debug
  2. ${S}/tools/wt-test.sh zestawy final-release :app:testReleaseUnitTest
  3. cd ${WT} && ANDROID_HOME=/opt/android-sdk ./gradlew --console=plain -Pvosk=true :app:compileDebugKotlin
  4. cd ${WT} && ANDROID_HOME=/opt/android-sdk ./gradlew --console=plain :app:assembleDebug :app:bundleRelease
  5. cd ${WT} && git diff ${BASE}..HEAD | grep -nP '^\\+.*[\\x{2013}\\x{2014}]' ; echo "dash check exit=$?"   (must print no added line)
  6. git status --short must be clean after your commit (delete any file named ZzSonda*).
Commit the fixes as one commit. In your final message list every finding with its fate (fixed, not real, deferred
and why), the final test counts for debug and release, the paths of rendered images worth looking at, every file
you touched outside the list of YOUR FILES (there should be none) and what the merging engineer must know:
new public functions and their meaning, new files written to the device, and every existing test you changed.

REVIEW FINDINGS (JSON)
${JSON.stringify(all, null, 1)}

SPECIFICATIONS OF THE TWO STAGES (for reference)
${SPEC_PLATFORM}
${SPEC_EDITOR}
`,
  { label: 'fix-and-verify', phase: 'Fix' },
)

return { stage1: r1, stage2: r2, reviews, fix }
