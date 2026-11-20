export const meta = {
  name: 'matura-learning-loop',
  description: 'Implement the classic-mode learning loop fixes in an isolated worktree: engine logic with tests, then UI and session flow, then two independent reviews, fixes and a final verification',
  phases: [
    { title: 'Engine', detail: 'pure Kotlin engine changes with JUnit tests' },
    { title: 'UI', detail: 'play screen, result screen, session flow, previews' },
    { title: 'Review', detail: 'logic reviewer and flow/UI reviewer' },
    { title: 'Fix', detail: 'apply confirmed review findings' },
    { title: 'Verify', detail: 'full test suites and previews' },
  ],
}

const WT = '/home/user/Matura/.claude/worktrees/petla'
const BASE = '2b3377e'

const COMMON = `
You are working on an Android app (Kotlin, Jetpack Compose): "Matura", an offline pixel-art game for learning English
vocabulary for the Polish basic-level matura exam. The store version is 0.9.18. Audience: Polish 18-19 year olds.

WHERE YOU WORK
A dedicated git worktree: ${WT}  (branch wt/petla-nauki, created from commit ${BASE}).
ALL your reading, editing, building and committing happens inside that directory. Use absolute paths under it and
start every shell command with:  cd ${WT} && ...
NEVER edit, build or run git commands in /home/user/Matura itself (the main checkout): other work is in progress
there. Never push. Never touch git config. Do not create other worktrees or branches.

BUILD AND TEST (inside the worktree; local.properties already points at the Android SDK)
  cd ${WT} && export ANDROID_HOME=/opt/android-sdk && ./gradlew --console=plain :app:testDebugUnitTest
  single class:   ... :app:testDebugUnitTest --tests 'com.matura.app.EngineTest'      (pattern matching works: '*Dobor*')
  release variant: ... :app:testReleaseUnitTest
  screen previews (PNG files rendered on the JVM, see app/src/testDebug/java/com/matura/app/PodgladEkranowTest.kt):
                  ... :app:testDebugUnitTest --tests 'com.matura.app.PodgladEkranowTest'
                  images land in ${WT}/app/build/podglad/<size>/<name>.png ; you can open them with the Read tool
                  and LOOK at them. Use this to check every layout you touch, especially telefon-maly (320x568 dp)
                  and telefon-niski (360x640 dp).
A Gradle build may be running elsewhere on the machine; if a build is slow, wait, do not kill daemons.
Baseline at ${BASE}: 483 tests pass in testDebugUnitTest (plus the preview tests), 439 in testReleaseUnitTest.
Tests in app/src/test run in both variants and must not use Compose test rules; Compose/Robolectric UI tests live in
app/src/testDebug (the debug-only test manifest is why).

CODE CONVENTIONS OF THIS REPOSITORY (follow them exactly)
- Read the surrounding code first and match it. The code is carefully commented: comments explain WHY, usually in
  Polish written without diacritics in the game/ and learn/ packages. Write new comments the same way, in the same
  language as the file you are in, and only where they explain a decision a reader could not guess.
- NO em dashes and NO en dashes in anything you write (comments, strings, test names, commit messages): the
  repository owner removed them from the whole tree on purpose. Use a colon, a comma, a full stop or brackets.
  Do not go hunting for existing ones in old code; just never add new ones.
- Every text a student can see goes through the two-language table in app/src/main/java/com/matura/app/ui/Strings.kt
  (class Str, pattern: val name get() = pick("polski", "English")). Polish first, natural and correct Polish with
  diacritics in the strings themselves. Tone: direct, calm, for 18-19 year olds, never childish. Earlier the team
  replaced violent wording (Pokonane instead of Zabicia): keep that.
- Text drawn with PixelText or drawPixelText uses a bitmap font that has ONLY: A-Z a-z 0-9, Polish letters with
  diacritics, space and . , ! ? : / - _ + ' plus the multiplication sign and infinity. It has no parentheses, no
  percent sign, no equals sign, no quotes, no arrows. Longer sentences use the normal Text composable instead.
- The engine (game/GameEngine.kt) and the learn/ package are pure Kotlin with no Android imports, so that they can
  be tested with plain JUnit. Keep it that way. Time and randomness are injected, never read from the system inside
  logic.
- Persistence: this task must NOT change the saved data model (model/Models.kt SaveData and friends) and must not
  add disk writes during a round beyond what exists.
- Do not touch: the voice code (ui/VoiceInput.kt, VoicePolicy.kt, VoiceTest.kt, RawMicTest.kt, app/src/vosk), the
  vocabulary (data/BuiltInSets.kt, data/entry-ids.json), docs/, README.md, build files, the manifest.
- Accessibility: clickable things keep at least 48 dp touch targets and a readable label for TalkBack (look at how
  existing buttons do it); never signal by colour alone.
- Commit your work in the worktree when your stage is done (git add the specific files you changed, then git commit).
  Commit message in English: a short subject line, then a body that says what changed and why, no dashes of the kind
  forbidden above, and end the message with exactly these two lines:
    Co-Authored-By: Claude <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_016ardp75CeKgBASgp8eAmD5
`

const SPEC_ENGINE = `
BACKGROUND (verified facts about the current code, read the files to confirm)
The classic mode (game/GameEngine.kt driven by ui/PlayScreen.kt) shows monsters carrying a word; the student types
the translation. Problems this stage fixes:

E1. A word missed on first contact never comes back soon. In GameEngine.buildPlan a word counts as NEW when
    p.box == Srs.NO_BOX. But learn/Srs.apply leaves a word in box 0 after a wrong answer (it only records the mistake,
    assistedDay and dueDay = today). So a word the student has just got wrong is classified as new and can fall
    behind the new-word limit (GameConfig.newWordsPerRound) instead of returning as overdue. learn/Sesja.zaplanuj
    already uses the right rule: a word is new only when its progress key is ABSENT from the map.
    Fix: in buildPlan a playable is new only if it has no entry id or the progress map has no key for it
    (Srs.key(entryId, direction)); a word with a record but box 0 goes to the overdue list, where the existing
    urgency function (potrzeba) already puts words that needed help today first.

E2. In "record" mode (GameConfig.progress empty, which is every brand-new player's first round) nextTarget draws
    uniformly WITH replacement, so one word can come three times before another appears once. The older master
    branch fixed this with a shuffled bag (see: git -C ${WT} show origin/master:app/src/main/java/com/matura/app/game/GameEngine.kt
    and the test "every word comes up once before any word comes up twice" in
    git -C ${WT} show origin/master:app/src/test/java/com/matura/app/EngineTest.kt).
    Fix: in record mode draw from a shuffled bag without replacement; refill with a fresh shuffle only when the bag is
    exhausted; a new bag must not start with the word the previous one ended on (when the set has more than one
    word); never put a prompt on the board that is already on the board unless nothing else is left (keep the
    existing fallback so tiny sets still spawn).

E3. Entries that share a prompt are judged unfairly. buildPlayable gives each playable only the answers of ITS OWN
    row (Matching.acceptedAnswers). The vocabulary has 15 sets where two English words share one Polish translation
    in the same set (for example arm and hand are both "reka"; kind and polite are both "uprzejmy"), and many more
    across sets (the random mix). When the prompt is "reka" and the monster belongs to the row "arm", typing "hand"
    is counted as a miss. Matching.answersForPrompt(entries, prompt, termToDef) already exists for exactly this and
    the other three game modes use it.
    Fix: a playable accepts the union of the accepted answers of every row of the played set whose prompt side
    normalises to the same text, for the direction of that playable. The learning event still goes to the entry the
    monster was built from. Keep per-row direction handling for Direction.RANDOM intact.

E4. The classic mode never shows the right answer. When a monster reaches the cannon the student loses a life and is
    never told the translation, so an unknown word stays unknown. The master branch carried the solution on the
    monster and listed missed words after the game (MissedWord, missedWords; see the same two master files).
    Fix, engine side:
    - Playable, Monster and Crate carry "solution": the counterpart exactly as the set spells it (trimmed, with
      diacritics, hyphens and any comma-separated variants), not the normalised form.
    - data class MissedWord(prompt, solution, entryId, direction, times). The engine keeps them in first-miss order;
      the same entry and direction missed again increments times instead of adding a row; two different entries that
      share a prompt stay separate rows (key by entry id plus direction when the id is not blank, otherwise by prompt
      plus solution). Expose engine.missedWords and put the list in GameSnapshot.
    - data class Reveal(prompt, solution, ttl). When a monster reaches the cannon the engine sets the current reveal
      (latest wins) with ttl = REVEAL_TIME (3.0 seconds, a public const), counts it down in tick, and exposes it in
      GameSnapshot (null when nothing is shown). A monster that reached the cannon while a projectile was already
      flying at it (incoming > 0) was answered correctly: it is neither a missed word nor a reveal.
    - debugAddMonster and debugAddCrate set solution from their definition parameter.

E5. Calm rounds and finite rounds. Today the daily session ("Dzisiejsze 5 minut") and the "practise hard words"
    button start an ordinary endless round with nine lives; it never ends by itself and the student has to walk out.
    Add two independent switches to GameConfig, both default false:
    - calm: a monster that reaches the cannon does NOT take a life and cannot end the round; it still resets the
      streak, still records Outcome.POKAZANA as today, still becomes a missed word and a reveal. In a calm round
      there is no armour and there are no crates, and difficulty does not rise: level stays 1 for speed, spawn
      interval, armour and score purposes.
    - finite: the round ends by itself once every playable word of the set has been answered correctly at least
      once in this round. Keep a pending collection of the playables not yet answered (a correct answer to a monster
      OR a crate marks that playable done; with answer unions from E3 the playable is the one the target was built
      from). In a finite round nextTarget only offers pending words that are not on the board, in queue order (plan
      order when a plan exists, otherwise bag order), and offers nothing when there is none (no fallback repeat). A
      word that reaches the cannon stays pending and comes back later. When pending is empty and no monsters and no
      projectiles remain, the round is completed.
    - State: add "completed: Boolean" to the engine and the snapshot. To keep every existing guard working, a
      completed round ALSO sets gameOver = true; document that gameOver now means "the round has ended" and completed
      says why. Add targetsDone and targetsTotal to the snapshot (both 0 when the round is not finite) and "calm" to
      the snapshot so the HUD can hide the hearts.
    - A finite round that is not calm still ends early when lives run out (gameOver true, completed false).

TESTS (plain JUnit, app/src/test/java/com/matura/app/). Add them next to the existing ones (EngineTest.kt for board
mechanics, DoborSlowTest.kt for word selection and learning honesty) in the style of those files. At least:
- E1: with a progress map where one word has a record in box 0 with one mistake and the other words have no record,
  and newWordsPerRound smaller than the number of new words, the missed word is the FIRST word spawned. Show that the
  test fails on the old rule (explain in the test comment why it would).
- E2: every word comes up once before any word comes up twice; the next bag does not start with the previous last
  word; with progress given the plan mode behaviour is unchanged (existing tests must stay green untouched).
- E3: with rows arm=reka and hand=reka in one set and direction DEF_TO_TERM, the monster "reka" is hit by "arm" and
  by "hand"; the learning event names the entry the monster came from; prefix highlighting works for both.
- E4: a word that reaches the cannon lands on missedWords with its translation in set spelling; missed twice is one
  row with times 2; two entries sharing a prompt stay apart; a clean run leaves the list empty; the reveal appears
  with the right texts, lasts REVEAL_TIME and then disappears; a monster with a projectile already flying at it does
  not produce a missed word.
- E5: calm: reaching the cannon costs no life and never ends the round, streak resets, POKAZANA is recorded, no
  armoured monsters and no crates ever appear even after many kills, speed does not rise. Finite: after answering
  every word once the round is completed (completed and gameOver true), no word is offered twice before that unless
  it reached the cannon, a word that got through is offered again, targetsDone/targetsTotal count correctly, a
  finite non-calm round still ends by lives.
Run the whole unit test task before you commit: everything that passed before must still pass. Do NOT edit existing
tests to make them pass unless the behaviour they pin was deliberately changed by this spec (then say so in the
commit message); in particular do not weaken assertions.

DO NOT change ui/PlayScreen.kt or any other UI file in this stage, except the minimum needed to keep the project
compiling after the data class changes (give new fields default values where that keeps callers untouched).
`

const SPEC_UI = `
The previous stage changed the engine (read git -C ${WT} log -3 --stat and the diff of game/GameEngine.kt against
${BASE} first). New engine surface you can rely on: Monster.solution, GameSnapshot.missedWords (MissedWord: prompt,
solution, entryId, direction, times), GameSnapshot.reveal (Reveal: prompt, solution, ttl; GameEngine.REVEAL_TIME),
GameConfig.calm and GameConfig.finite, GameSnapshot.completed / calm / targetsDone / targetsTotal, and the rule that
gameOver is also true when a finite round is completed. If anything of that is missing or named differently, read
the engine and adapt; do not re-implement engine logic in the UI.

YOUR STAGE: make the classic mode teach, make session rounds end, and fix the result screen. Files you will touch:
ui/PlayScreen.kt, ui/App.kt, ui/Strings.kt, ui/DzisiejszaSesjaScreen.kt (texts only), learn/Sesja.kt (one pure
function), data/Repository.kt (one parameter), tests, and the preview test.

U1. Show the answer on the board. While snapshot.reveal is not null, draw it in drawField near the cannon so the
    student sees what the word was: the prompt and, under or next to it, the solution in a clearly different colour
    (Palette.Gold for the solution), with the usual ink outline, kept inside the canvas (reuse the clamp logic of
    drawCentredLabel), scaled down if the text is longer than the canvas, fading out in the last half second. It must
    not cover the HUD corners (lives top-left, pause top-centre, score bottom-left, level bottom-right). Place it
    above the cannon; check the preview image.

U2. Result screen for the classic mode, on the WHOLE window. Today GameOverOverlay is drawn inside the board box
    only. On a 360x640 dp phone the skull and the two buttons are clipped; on 320x568 dp the buttons are completely
    off screen and the only way out is the system back button (run the previews at ${BASE} to see it:
    telefon-niski/gra-klasyczna-koniec.png). Replace it:
    - When the round has ended (snapshot.gameOver) PlayScreen shows a full-window result screen INSTEAD of the board,
      answer bar, voice bar and keyboard (do not just paint over them: keys underneath must not be reachable by
      touch or by TalkBack). Keep all state hoisted in PlayScreen as it is; "JESZCZE RAZ" must still create a new
      engine through the existing restarts counter.
    - Layout: a Column filling the window with safe padding; an upper part that scrolls and takes the remaining
      height (weight(1f)); the buttons pinned at the bottom, always visible. Upper part, top to bottom: icon and
      title, the score with the coin, the existing stats line (level, defeated, best streak), then the list of
      missed words.
    - Two outcomes: lives lost keeps the skull and str.gameOver in Palette.Danger; a completed finite round shows a
      positive icon (R.drawable.checkmark or icon_trophy) and a new title, for example "RUNDA UKONCZONA" /
      "ROUND COMPLETE", in Palette.Accent.
    - Missed words: a panel titled "DO POWTORKI" / "TO REVIEW" listing each missed word as prompt and solution on
      one row (system font via Text, because solutions contain commas and can be long; prompt in Palette.Bone,
      solution in Palette.Gold, and "x2" style counter when times > 1). Never colour alone: the two sides are also
      separated by position. If nothing was missed, show one short positive line instead of an empty panel.
    - Buttons: default "JESZCZE RAZ" (primary) and "DO MENU" (ghost) as today. PlayScreen gets a new optional
      parameter onContinue: (() -> Unit)? = null and a label for it; when it is not null the primary button is
      that continue button ("DALEJ" / "NEXT") and "JESZCZE RAZ" becomes the secondary one; "DO MENU" is then not
      shown (the caller decides where continue leads).
    - Make this result screen a reusable composable in a new file ui/EkranWyniku.kt with slots (a composable
      parameter for the middle section) because the other three modes and a later reward block will use it. Do NOT
      convert the other three modes in this stage.

U3. HUD in calm and finite rounds: when snapshot.calm, do not draw hearts. When the round is finite, draw the
    progress "targetsDone/targetsTotal" (for example 7/12) in the top-left corner with a small target icon, in the
    place the hearts use. Keep everything else.

U3b. Calm rounds promise that no life is lost. The engine still raises FlashKind.LIFE_LOST when a word gets
    through in a calm round (it kept the enum unchanged on purpose). In PlayScreen do not shake the board, do not
    flash it red and do not play the error tone for that event when snapshot.calm is true: the reveal of the answer
    is the feedback. Also correct the stale KDoc of the progress parameter in PlayScreen.kt (it still says words
    fly exactly as before the schedule; a round without history now draws from a shuffled bag).

U4. PlayScreen parameters: add calm: Boolean = false and finite: Boolean = false, pass them into GameConfig, and make
    them part of the remember key of the engine. The round result is still reported once through onFinished when
    snapshot.gameOver becomes true (this now covers completion too). ScoreRecord is unchanged.

U5. App flow (ui/App.kt). Today a round started from the daily session or from hard words ends on the MENU and the
    student has to reopen the session and press DALEJ by hand; calm rounds are faked with nine lives.
    - Session rounds (Screen.DzisiejszaSesja -> onKrok for POWTORKA and GRA) are finite. They are calm exactly when
      krok.spokojny is true. Remove the nine-lives workaround (opcjeRundy) because calm now exists in the engine.
    - Hard-words practice rounds (onCwicz, onCwiczWszystkie) are calm and finite.
    - Where a round returns to: keep a small private enum or sealed type in App for the return target (menu, daily
      session, hard words) next to the existing rundaSpokojna and planSesji flags, set it wherever a round is
      started, and reset it for ordinary rounds from PLAY.
      * Leaving such a round (system back, or the continue button on the result screen) goes back to the screen it
        came from: the stack becomes [Menu, DzisiejszaSesja] or [Menu, TrudneSlowa], after refresh().
      * For these rounds pass onContinue to PlayScreen so the result screen offers DALEJ.
    - FREEZE THE DAY'S ROUTE (a verified defect of the current design, fix it here). Today App rebuilds the plan with
      Sesja.zaplanuj from the LIVE schedule every time the session screen is entered, and StanSesji stores only a
      step index and done lists. Words answered correctly are no longer due, so they drop out of the new plan and the
      freed slots are refilled with five MORE never-tried words: on a fresh install the student answers W1..W5, comes
      back, and step 1 now holds W6..W10; the step "the same vocabulary once more" never replays step 1; the bar
      reads 5 / 10, then 10 / 15, and never converges; the summary counts words that were never shown.
      Fix (EXCEPTION to the rule above about not changing saved data: StanSesji is part of the save file, and it may
      gain new fields WITH DEFAULTS, so that an old save still loads; no schema step is needed, add a test that an
      old JSON without the new fields still decodes):
      * StanSesji gains the route planned when the session started: the review ids and the new-word ids, in plan
        order, and the direction the route was planned for.
      * Sesja.rozpocznij stores the route from the plan. A new pure function in Sesja (for example planNaDzis)
        returns the plan to use: when the saved state is from the same day, not finished and has a non-empty route,
        it rebuilds PlanSesji from the STORED route (same step construction as zaplanuj: a review step and a game
        step over the whole route vocabulary, then the summary; the game step is calm when the route has new
        words); otherwise it plans fresh with zaplanuj. A saved state from an older version (same day, empty route)
        is treated as having no route: plan fresh and store the route then.
      * App uses that function everywhere it needs the plan (the session screen and the code that runs after a
        round). The route never changes during the day, so the counters converge: done / total reaches total.
      * Keep every existing honesty rule of the file: no second schedule (Srs still decides promotions), no
        punishment for a missed day, a state from another day simply starts a new session.
    - Automatic step advance. Add to learn/Sesja.kt a pure function, with a KDoc in the style of the file:
        fun poUkonczonejRundzie(stan: StanSesji, plan: PlanSesji, krokRundy: Int): StanSesji
      Meaning: the student has just COMPLETED the round that belonged to step index krokRundy of this plan. If
      stan.krok is still krokRundy and that step is a round step (POWTORKA or GRA), return dalej(stan, plan);
      otherwise return stan unchanged (the student already moved on by hand). It must not mark the session finished
      when the next step is the summary, and it must not lose zrobionePowtorki / zrobioneNowe.
      App remembers the step index a session round was started from. When that round ends COMPLETED
      (snapshot.completed, pass it out of PlayScreen through a new optional callback or as an extra argument of an
      existing one, your choice, but keep ScoreRecord unchanged), App applies poUkonczonejRundzie to the saved state
      and saves the result with repo.setSesja. A round that ended by lost lives or was left early does not advance.
    - The round is played in the direction the plan was made for (verified defect). With the option "random
      direction" the session plans, counts and summarises TERM_TO_DEF only, but the round was started with the
      unchanged options, so the engine flipped a coin per word, half the answers were recorded under the other
      direction and the session re-planned those words as never tried. Start session rounds with the options copied
      with direction = plan.kierunek. Hard words: pass the student's direction to TrudneSlowaScreen as its initial
      direction (resolve the random setting the same way the list itself does, see learn/Trudne.kt), and start a
      practice round in the direction of the list the student is looking at (the TrudneSlowo items carry it).
    - The schedule day must not go stale (verified defect). App computes "dzisiaj" once per Activity lifetime
      (remember { Srs.dayOf(...) }); the process often survives overnight, and then the session and the hard-words
      list work on yesterday: today's reviews are not offered, yesterday's unfinished session is resumed, "next
      review tomorrow" is said about words due today. Make it a state that is refreshed when the Activity comes back
      to the foreground (a lifecycle observer on ON_START) and when the student opens the daily session or the hard
      words screen. It stays stable while one screen is being shown and during a round (PlayScreen keeps taking its
      own day at round start, as now).
    - Two small truths on the session screen: the pill WZNOWIONA is shown only when Sesja.czyWznawialna says the
      saved session is from today AND something was already done in it (step index above 0 or a non-empty done
      list); leaving an untouched session with back does not save it. The "time is up" card is not shown for an empty
      plan, and its text must not claim that five minutes passed when the estimate for a short route is two (reword
      it to speak about the estimated time, in Polish and English).
    - Leaderboard honesty. A calm round cannot be lost, so it must not compete with real runs or unlock score and
      streak badges. Give Repository.recordGame a third parameter (for example doRankingu: Boolean = true). When
      false: gamesPlayed and totalKills still grow and the set's timesPlayed still grows, but the record is not
      added to scores and bestScore / bestStreak are not raised. App passes false for calm rounds. Add a test in
      app/src/test/java/com/matura/app/PersistenceTest.kt.
    - Texts in ui/DzisiejszaSesjaScreen.kt (class SesjaStr) describe the old workaround: "runda z zapasem dziewieciu
      zyc", the pill "9 ZYC", and comments explaining that the labels were changed instead of the behaviour. Now the
      behaviour exists: the review step is calm (no lives are lost, the round ends when every word has been answered
      once) and the answer is shown when a word gets through. Update the Polish and English texts and the comments so
      that they describe exactly what the code does. Keep the pill short enough for the bitmap font.

U6. Tests.
    - learn (app/src/test/java/com/matura/app/SesjaTest.kt, plain JUnit): the route is frozen: with a catalogue larger
      than one session, after answering the five planned new words correctly the plan for the same day still holds
      exactly those five words in both round steps and no sixth word, and done / total converges to 5 / 5 after the
      first step; a state from another day plans fresh; a same-day state without a route (old save) plans fresh and
      stores the route; an old JSON of StanSesji without the new fields decodes. poUkonczonejRundzie: advances one
      step from a round step; from the second round step moves to the summary without finishing the session and
      keeps the done lists; a wrong krokRundy does nothing; on the summary step does nothing.
    - UI (app/src/testDebug, Robolectric, look at BladZapisuWidocznyTest.kt, SystemKeyboardTest.kt and
      TouchTargetAuditTest.kt for how screens are composed and how the clock is driven): the result screen of the
      classic mode at 320x568 dp shows both buttons fully inside the window (assert displayed and bounds inside the
      root); a missed word and its translation are shown on the result screen; with onContinue set the continue
      button is shown and DO MENU is not; while the result screen is shown no keyboard key is in the semantics tree.
    - Previews: extend PodgladEkranowTest.kt so the classic result is rendered in both outcomes (lives lost with a
      few missed words, and a completed calm finite round), plus a mid-round frame of a calm finite round and a frame
      with a reveal visible. Run it, OPEN the images for telefon-maly, telefon-niski and telefon, and fix what looks
      wrong (clipped text, overlaps, buttons off screen, unreadable contrast) before you commit.
    - Run the complete :app:testDebugUnitTest and :app:testReleaseUnitTest. Everything green.

OTHER TEAMS, SO YOU DO NOT COLLIDE WITH THEM. Three more teams work in other worktrees right now and their
branches will be merged with yours: (a) the save-file team adds a rewards ledger: it edits model/Models.kt (new
fields on WordProgress, Options, SaveData), learn/Srs.kt, and data/Repository.kt (recordAttempt, the migration, and
recordGame, to which it adds a parameter nowMs: Long); keep your own edits to Repository.recordGame and to the model
as small and local as possible and do not reformat neighbouring code; (b) the modes team edits
ui/TrueFalseScreen.kt, ui/MultipleChoiceScreen.kt, ui/TimeAttackScreen.kt, game/Matching.kt, game/VoiceMatch.kt and
ui/VoicePolicy.kt: do not touch those files; (c) a later team wires new speech rules into PlayScreen.onHeard and
answeringPolish: leave the voice functions of PlayScreen as they are, apart from passing the round direction where
you must. Commit trailer for this stage and every later one (this replaces the trailer named earlier):
    Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_016ardp75CeKgBASgp8eAmD5

Report at the end: what you changed file by file, anything you decided differently from this spec and why, anything
you could not verify without a device, and the paths of the preview images that show the new screens.
`

phase('Engine')
const engineReport = await agent(`${COMMON}

STAGE 1 OF 2: ENGINE (pure Kotlin logic and JUnit tests)
${SPEC_ENGINE}

Work test-first where practical. When done: run the full unit test task, commit, and report what you changed, the
exact new public surface of the engine (names and types), every existing test you had to touch and why, and the test
counts from the final run.`,
  { label: 'implement:engine', phase: 'Engine' })

phase('UI')
const uiReport = await agent(`${COMMON}

STAGE 2 OF 2: UI AND FLOW
Report from the engine stage (read the actual code as the source of truth):
---
${engineReport}
---
${SPEC_UI}`,
  { label: 'implement:ui', phase: 'UI' })

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    verdict: { type: 'string', enum: ['ship', 'fix-first'] },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string' },
          file: { type: 'string' },
          line: { type: 'integer' },
          severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
          problem: { type: 'string', description: 'What is wrong and the concrete scenario in which it shows' },
          fix: { type: 'string' },
        },
        required: ['title', 'file', 'line', 'severity', 'problem', 'fix'],
      },
    },
    checked: { type: 'string', description: 'What you actually verified and how (commands run, images opened)' },
  },
  required: ['verdict', 'findings', 'checked'],
}

phase('Review')
const reviews = (await parallel([
  () => agent(`${COMMON}

YOU ARE A REVIEWER, NOT AN AUTHOR. Do not modify any file and do not commit. You may run tests and read anything.
Review the LOGIC of the change set:  cd ${WT} && git diff ${BASE}..HEAD -- app/src/main/java/com/matura/app/game app/src/main/java/com/matura/app/learn app/src/main/java/com/matura/app/data app/src/test
The intended behaviour was specified as follows.
${SPEC_ENGINE}
And for learn/Sesja.poUkonczonejRundzie and Repository.recordGame:
${SPEC_UI}

Hunt for: behaviour that deviates from the spec; regressions in ordinary rounds (not calm, not finite), especially
the plan mode and what gets recorded for learning (LearningEvent outcomes: a wrong learning record damages a
student's schedule); rounds that can never end or end too early (think of sets with one or two words, duplicate
prompts, Direction.RANDOM, armour, crates, a monster hit while another answer is in flight); state that is not reset
between rounds; tests that do not actually test what their name says or that would pass on the old code. Write small
throwaway experiments if needed but delete them. Report only real problems with a concrete scenario.`,
    { label: 'review:logic', phase: 'Review', schema: REVIEW_SCHEMA }),
  () => agent(`${COMMON}

YOU ARE A REVIEWER, NOT AN AUTHOR. Do not modify any file and do not commit. You may run tests and read anything.
Review the UI and FLOW of the change set:  cd ${WT} && git diff ${BASE}..HEAD -- app/src/main/java/com/matura/app/ui app/src/testDebug
The intended behaviour was specified as follows.
${SPEC_UI}

Do this concretely:
1. Run the preview test and OPEN the images with the Read tool for the sizes telefon-maly, telefon-niski, telefon,
   telefon-poziomo and tablet-poziomo: the classic mode mid-round, with a reveal, calm finite round, and both result
   screens. Look for clipped or overlapping text, buttons off screen or cut, unreadable contrast, hearts shown in a
   calm round, text the bitmap font cannot draw (it shows as a slash or a blank).
2. Trace the navigation in ui/App.kt by reading: daily session -> round -> completed -> continue; -> left early by
   back; -> lost all lives in a non-calm step; hard words -> practise -> continue; ordinary PLAY -> round -> menu.
   Check the flags (return target, calm, finite, planSesji, remembered step index) cannot leak from one round into
   the next ordinary round, and that learning attempts are still saved on every path.
3. Check every new or changed string in ui/Strings.kt and ui/DzisiejszaSesjaScreen.kt: correct natural Polish,
   matching English, no forbidden dashes, pixel-font labels only use supported characters, and the texts say what
   the code really does.
4. Accessibility: TalkBack labels and 48 dp targets on the new result screen; nothing reachable underneath it.
Report only real problems with a concrete scenario.`,
    { label: 'review:ui-flow', phase: 'Review', schema: REVIEW_SCHEMA }),
])).filter(Boolean)

const findings = reviews.flatMap(r => r.findings)
log(`reviews: ${reviews.map(r => r.verdict).join(', ')}; ${findings.length} finding(s)`)

let fixReport = 'No review findings to apply.'
if (findings.length > 0) {
  phase('Fix')
  fixReport = await agent(`${COMMON}

Two independent reviewers examined the work on this branch (git -C ${WT} log --oneline ${BASE}..HEAD). Their findings
are below as JSON. For EACH finding: check it against the code yourself. If it is real, fix it properly (root cause,
with a test that fails before and passes after where a test is possible); if it is not real, do not change anything
for it and say why. Do not make unrelated changes. Then run the complete :app:testDebugUnitTest and
:app:testReleaseUnitTest, re-render the previews and look at the ones you affected, commit, and report finding by
finding what you did.

The specification the work must satisfy:
${SPEC_ENGINE}
${SPEC_UI}

FINDINGS
${JSON.stringify(findings, null, 1)}`,
    { label: 'fix:review-findings', phase: 'Fix' })
}

phase('Verify')
const VERIFY_SCHEMA = {
  type: 'object',
  properties: {
    debug_tests: { type: 'string', description: 'tests / failures / errors from testDebugUnitTest' },
    release_tests: { type: 'string', description: 'tests / failures / errors from testReleaseUnitTest' },
    vosk_variant: { type: 'string', description: 'result of compileDebugKotlin and testDebugUnitTest with -Pvosk=true' },
    assemble: { type: 'string', description: 'result of assembleDebug and assembleRelease' },
    clean_tree: { type: 'boolean', description: 'git status shows no uncommitted changes in the worktree' },
    commits: { type: 'array', items: { type: 'string' }, description: 'git log --oneline BASE..HEAD' },
    preview_images: { type: 'array', items: { type: 'string' }, description: 'absolute paths of the most informative preview images of the new screens' },
    remaining_concerns: { type: 'array', items: { type: 'string' } },
  },
  required: ['debug_tests', 'release_tests', 'vosk_variant', 'assemble', 'clean_tree', 'commits', 'preview_images', 'remaining_concerns'],
}
const verification = await agent(`${COMMON}

YOU ARE THE FINAL VERIFIER. Do not change source files. In the worktree run, one after another, and report exact
numbers (count tests from app/build/test-results/<task>/*.xml: tests, failures, errors):
  export ANDROID_HOME=/opt/android-sdk
  ./gradlew --console=plain :app:testDebugUnitTest
  ./gradlew --console=plain :app:testReleaseUnitTest
  ./gradlew --console=plain :app:compileDebugKotlin :app:testDebugUnitTest -Pvosk=true
  ./gradlew --console=plain :app:assembleDebug :app:assembleRelease
then run the preview test once more WITHOUT the vosk flag so the images are fresh, list git log --oneline
${BASE}..HEAD and git status --short, and open three or four of the new preview images to confirm they render.
Report anything that still worries you.`,
  { label: 'verify:final', phase: 'Verify', schema: VERIFY_SCHEMA })

return { engineReport, uiReport, reviews, fixReport, verification }
