export const meta = {
  name: 'matura-matching-modes-voice',
  description: 'Fix confirmed audit defects in answer matching, voice matching rules and the three quick modes (true/false, multiple choice, time attack) in an isolated worktree, with tests, independent review and fixes',
  phases: [
    { title: 'Matching', detail: 'accepted answers, shared prompts, tests' },
    { title: 'Voice rules', detail: 'closed-vocabulary decision, one credit per utterance, spelling variants' },
    { title: 'Modes', detail: 'rounds that end, shared prompts in time attack, hints, labels' },
    { title: 'Review', detail: 'independent reviewers, one lens each' },
    { title: 'Fix', detail: 'apply confirmed findings, full suites' },
  ],
}

const WT = '/home/user/Matura/.claude/worktrees/dopasowanie'
const S = '/tmp/claude-0/-home-user-Matura/0e5748ef-99b0-59e1-b03e-46ac0f545d18/scratchpad'
const BASE = '2b3377e'

const COMMON = `
You are working on an Android app (Kotlin, Jetpack Compose): "Matura", an offline pixel-art game for learning English
vocabulary for the Polish basic-level matura exam. Store version 0.9.18. Audience: Polish 18-19 year olds in their
final school year. No accounts, no server, no INTERNET permission.

WHERE YOU WORK
A dedicated git worktree: ${WT}  (branch wt/dopasowanie, created from commit ${BASE}).
ALL your reading, editing, building and committing happens inside that directory. Use absolute paths under it and
start every shell command with:  cd ${WT} && ...
NEVER edit, build or run git commands in /home/user/Matura itself or in any other worktree under
/home/user/Matura/.claude/worktrees (other teams work there on the game engine, the screens and the save file).
Never push. Never touch git config. Do not create other worktrees or branches.

BUILD AND TEST
  ${S}/tools/wt-test.sh dopasowanie <label>                              all debug unit tests, compact summary
  ${S}/tools/wt-test.sh dopasowanie <label> --tests '*Matching*'         one class or pattern
  ${S}/tools/wt-test.sh dopasowanie <label> :app:testReleaseUnitTest     release variant
  plain Gradle: cd ${WT} && export ANDROID_HOME=/opt/android-sdk && ./gradlew --console=plain :app:testDebugUnitTest
Other Gradle builds run elsewhere on this 4-core machine at the same time: builds can take several minutes. Wait for
them; never kill Gradle or Kotlin daemons, never run "gradlew --stop", never delete ~/.gradle.
Baseline at ${BASE}: testDebugUnitTest and testReleaseUnitTest are green (494 tests in debug, 439 in release).
Tests under app/src/test run in both variants and must not use Compose test rules; Compose and Robolectric screen
tests live under app/src/testDebug.

FILES YOU MAY CHANGE (anything else is owned by another team right now and editing it will cause merge conflicts)
  app/src/main/java/com/matura/app/game/Matching.kt
  app/src/main/java/com/matura/app/game/VoiceMatch.kt
  app/src/main/java/com/matura/app/ui/VoicePolicy.kt
  app/src/main/java/com/matura/app/ui/VoiceInput.kt          (only the one change named in your stage, if any)
  app/src/main/java/com/matura/app/ui/TrueFalseScreen.kt
  app/src/main/java/com/matura/app/ui/MultipleChoiceScreen.kt
  app/src/main/java/com/matura/app/ui/TimeAttackScreen.kt
  tests: existing AnswersForPromptTest, VoiceMatchTest, VoicePolicyTest, VoiceRegresjaTest, RodzajZadaniaTest and
  NEW test files you add. Do not edit EngineTest, DoborSlowTest, PodgladEkranowTest or any other existing test file
  unless a test in it pins behaviour that your stage deliberately changes (then change only that test and say so).
DO NOT TOUCH: game/GameEngine.kt, ui/PlayScreen.kt, ui/App.kt, ui/Screens.kt, ui/Strings.kt, ui/Common.kt,
everything under learn/, data/, model/, the manifest, build files, docs/, README.md. The three mode screens keep
their public composable signatures exactly as they are (App.kt and PodgladEkranowTest call them).

CODE CONVENTIONS OF THIS REPOSITORY (follow them exactly)
- Read the file you change from top to bottom first, and match it. The code is carefully commented: comments explain
  WHY, in Polish. game/ and the Voice* files write Polish without diacritics; the three mode screens write Polish
  with diacritics. Identifiers in game/ are English; in ui/Voice* they are mostly Polish. Write new comments the same
  way and only where they explain a decision a reader could not guess. When you change behaviour that a comment
  describes, update that comment.
- NO em dashes and NO en dashes in anything you write (comments, strings, test names, commit messages): the owner
  removed them from the tree on purpose. Use a colon, a comma, a full stop or brackets. If a line you edit contains
  one, replace it; do not hunt for others.
- Texts a student can see are Polish and English. The mode screens use a private helper t(pl, en, lang); keep using
  it and do not add fields to Str. Polish must be correct and natural, with diacritics; English plain and correct.
  Tone: direct, calm, a little playful, never childish. Text drawn with PixelText uses a bitmap font that has ONLY
  A-Z a-z 0-9, Polish letters, space and . , ! ? : / - _ + ' plus the multiplication sign and infinity: no
  parentheses, no percent sign, no equals sign, no quotes, no arrows. Text drawn with the system font (Text) may use
  any punctuation except the forbidden dashes.
- Project principles your code must respect: honest numbers only; nothing is ever taken away from the student; a
  wrong answer the app caused (rejecting a correct translation) is the worst kind of bug in a learning app.
- Commit your work in the worktree when your stage is done (git add the specific files, then git commit). Commit
  message in English: a short subject, a body that says what and why, no forbidden dashes, ending with exactly:
    Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_016ardp75CeKgBASgp8eAmD5
- Your final message is read by the engineer who merges the branches, not by the student. Report: what you changed
  (file and function), what you deliberately did not change and why, every behaviour change a player can notice,
  test counts (before and after) and anything you are unsure about. Plain prose, no tables wider than three columns.

BACKGROUND MATERIAL (read what your stage needs; these are audit reports written against commit ${BASE})
  ${S}/audit-audit-engine.txt   answer matching and the classic engine
  ${S}/audit-audit-modes.txt    true/false, multiple choice, time attack
  ${S}/audit-audit-voice.txt    speech recognition path
`

const SPEC_MATCHING = `
STAGE 1 OF 3: ANSWER MATCHING (game/Matching.kt and its tests). Findings "answers-for-prompt-whole-cell-only" and
"apostrophe-and-slash-variants" in the engine audit are confirmed; read them first.

A. Matching.acceptedAnswers(cell) must additionally accept:
   1. Apostrophes dropped: next to every variant, the same text with apostrophe characters (' and the typographic
      forms U+2019, U+2018, U+02BC and the backtick) deleted BEFORE normalisation. "can't stand" must accept
      "cant stand" (today only "can t stand" and "cantstand" work, and the in-game keyboard has no apostrophe);
      "dry cleaner's" must accept "dry cleaners".
   2. Slash inside a phrase: a token that contains "/" expands into its alternatives and every combination of the
      phrase is accepted: "szkoła żeńska/męska" accepts "szkola zenska" and "szkola meska"; "żonaty/zamężna" accepts
      both words. A slash with spaces around it ("his / her") keeps the old meaning (two separate answers). Cap the
      number of combinations per part (16 is plenty) so a pathological custom cell cannot explode.
   3. Glosses in brackets are removed before splitting on commas and semicolons, so a comma INSIDE brackets does not
      split the answer: "syndykat (związek, grupa)" accepts "syndykat" and does not accept "grupa" on its own.
   Compatibility rule, pinned by a test: for every row of every built-in set, in both columns, everything the OLD
   implementation accepted is still accepted (copy the old function into the test file as the reference). The one
   allowed exception is point 3 (a fragment that only existed because a comma inside brackets was split); built-in
   data has no such cell, so the built-in sweep must show a pure superset. Keep the existing no-space twin rule.
   Keep the returned list free of duplicates and stable in order (whole cell first, as now).

B. Same question, different rows. Add
       fun samePrompt(a: String, b: String): Boolean
   true when the two prompt cells share at least one alternative, where the alternatives of a cell are its parts
   split the same way as answers (top-level commas and semicolons, spaced slash, slash tokens expanded, glosses
   dropped, normalised) plus the whole normalised cell. "uczeń" and "uczeń, student" are the same question;
   "towarzyski, kontaktowy" and "towarzyski" are; "kind" and "kind of" are not. Two blank cells are never the same.
   Then make answersForPrompt(entries, prompt, termToDef) use it, so it returns the union of accepted answers of
   every row whose prompt side is the same question in this sense (it must stay a superset of what it returns
   today). Update its KDoc.

C. Call sites inside YOUR files: TrueFalseScreen.kt and MultipleChoiceScreen.kt repeat the whole-cell test
   (normalize(candPrompt) == promptKey) when they skip candidate rows. Replace those with Matching.samePrompt so a
   translation that the set itself lists for one of the shown alternatives can never be presented as false or used as
   a wrong option. (Stage 3 reworks other parts of those screens; here change only these comparisons.)

D. Tests (new file app/src/test/java/com/matura/app/MatchingTest.kt, plus additions to AnswersForPromptTest):
   - every example above, plus the two tests the production tree lost in an old merge: an answer with a hyphen counts
     with a space and with no gap ("open-minded" -> "open minded", "openminded"); a two-word answer counts written
     as one word.
   - the compatibility sweep over all built-in sets (BuiltInSets is in data/; look at BuiltInSetsTest for how tests
     read it).
   - answersForPrompt: "uczeń" against a row "student -> uczeń, student" returns student as well; identical-cell
     cases keep working; a property test: for every built-in set and both directions the new result is a superset
     of the old whole-cell result.
   - a sweep that reports how many built-in rows gained variants from points 1 and 2, asserted as exact numbers so a
     later vocabulary edit that changes them is noticed (print the list in the failure message).
   - mutation check before you finish: break each new rule on purpose (one at a time), confirm a test fails, restore.

Run the full debug suite and the release suite before committing. Report the numbers.
`

const SPEC_VOICE = `
STAGE 2 OF 3: VOICE MATCHING RULES (game/VoiceMatch.kt, ui/VoicePolicy.kt, one small change in ui/VoiceInput.kt).
Stage 1 is committed in this worktree: read its commit first (git log -3, git show --stat HEAD).
Read the voice audit: findings "voicematch-accepts-near-neighbours" (high), "one-utterance-two-kills",
"swapped-set-wrong-recognition-language", "status-line-survives-stop", and in the engine audit
"voice-margin-vs-own-nospace-variant". The speech ENGINE code (the recogniser ladder, timeouts, restarts in
VoiceInput.kt) was tuned on real phones and cannot be tested here: do not change any of it. You deliver pure,
tested decision logic; another engineer wires it into ui/PlayScreen.kt afterwards (that file is being rewritten by
another team right now, so you must not touch it). Design the API so that wiring is a few lines.

The principle already stated at the top of VoiceMatch.kt stays: a false shot kills the wrong target and teaches a
wrong pair, a miss only costs a repeat. Today that principle is violated in three ways. Fix all three.

A. Closed vocabulary = the whole set, not only the board.
   Add a decision function (English names, this is game/), for example:
       data class Target(val id: Int, val answers: List<String>)
       fun decide(transcripts: List<String>, board: List<Target>, others: List<Target>, polish: Boolean,
                  isFinal: Boolean): Decision
   board  = one Target per monster or crate currently on screen with ALL its accepted variants;
   others = one Target per row of the set that is NOT on the board, answer side in the language being listened to.
   Decision carries: accepted or not, the answer string to type, the target id, the score, and a reason enum that
   tests and the debug overlay can read.
   Rules:
   1. Whole-utterance claim. For each alternative transcript in order (the recogniser lists the most likely first),
      take its whole normalised form and its joined form (spaces removed). The first alternative whose whole form is
      exactly an accepted answer of ANY target decides: if that target is on the board, accept it; if it is only in
      "others", reject (the student said a different word of the set, e.g. "mature" while only "immature" is on the
      board; "boxer shorts" while only "shorts" is; "nie lubić" while only "lubić" is). Sub-word hypotheses must
      never override a whole-utterance claim.
   2. No claim: fuzzy matching as today (hypotheses, three scorers), but scored against board AND others together.
      The winner must be a board target. The runner-up used for the margin is the best candidate that belongs to a
      target which does NOT accept the winning answer (owner-aware: a target's own no-space twin or second variant is
      not a rival; two targets that share the winning answer are not rivals either). This repairs fuzzy matching for
      every multi-word answer ("pewni siebie" must now be accepted for "pewny siebie") and makes an off-board
      neighbour a rival ("stopa" against "stopy").
   3. Negation and affix guard: a hypothesis and a candidate that differ only by a leading nie, un, in, im, ir, il,
      dis, non or anti (with or without a space or hyphen after it) never match fuzzily, in either direction. Only
      an exact match can hit such a word.
   4. Exact ties go to the longest candidate.
   5. Partial results (isFinal = false): accept only when the score is at least 0.9 (as today) AND the matched
      answer is not a proper prefix and not a whole-word sub-phrase of any other answer on the board or in others
      ("bluza" must wait for the final result when "bluza sportowa" exists). Otherwise report "wait".
   Keep match(), accepts() and the flat overload working for the existing 55 cases; decide() with an empty "others"
   and one target per answer must agree with them (pin that with a test that replays the 55 cases through decide()).

B. British spelling heard through an American recogniser. The app listens in en-US while the vocabulary is
   British, so "centre", "metre", "litre", "grey", "cosy", "tyre", "mum" currently fail (too short for the fuzzy
   threshold). For English only, treat a hypothesis and a candidate as an exact match when they are equal after
   folding the regular differences (-our/-or, -re/-er after a consonant, -ise/-ize and -isation/-ization, -yse/-yze,
   -ogue/-og, doubled l before a suffix as in travelling/traveling, -ence/-ense in licence, defence, offence) and a
   short explicit list of irregular pairs (grey/gray, cosy/cozy, tyre/tire, mum/mom, pyjamas/pajamas,
   programme/program, cheque/check, kerb/curb, plough/plow, moustache/mustache, yoghurt/yogurt, doughnut/donut,
   maths/math, aluminium/aluminum, jewellery/jewelry, storey/story). The fold must not merge two DIFFERENT entries
   of the built-in vocabulary: add a test that walks all built-in English terms and fails if two different terms
   of one set fold to the same string, and a test listing which built-in terms have a folded form different from
   themselves (exact count asserted, list in the failure message).

C. One utterance, one credit (ui/VoicePolicy.kt, zaliczycWypowiedz). Its own comment says "Jedna wypowiedz =
   najwyzej jedno zaliczenie" but it only refuses the SAME answer; a partial result and then the final result of one
   utterance can shoot two different targets. New rule: once an utterance number has scored, every later result
   with that number is refused, whatever the answer. Keep the signature (PlayScreen calls it and you cannot edit
   PlayScreen). Update the comment and the test in VoiceRegresjaTest that pins the old exception (kryterium6);
   say in your report which test changed and why.

D. Language of the answer for a set whose columns were swapped (pure function, in ui/VoicePolicy.kt next to
   odpowiedziPoPolsku): the recognition language must come from the language the SET declares for the answer side
   (WordSet.termLanguage / definitionLanguage; see how learn/Trudne.kt czyAngielski and stronaDoWymowy read them),
   and only fall back to the direction rule (term = English, definition = Polish) when neither side declares Polish
   or English. Deliver a function PlayScreen can call instead of its private answeringPolish() for the two fixed
   directions, with tests including a swapped custom set. Recognise at least: Polski, polski, Polish, PL, pl and
   English, english, Angielski, angielski, EN, en (trimmed).

E. ui/VoiceInput.kt, exactly one change: stop() leaves a transient status line on screen ("próbuję innego silnika
   mowy…") after listening is switched off. Clear the status in stop() for the reasons DOTKNIECIE, PAUZA, TLO and
   EKRAN; keep it for DRABINKA, ZGODA and MIKROFON, where the message is the outcome the student must read. Add a
   Robolectric test next to the existing VoiceInput tests (see VoiceRegresjaTest for the pattern). If any existing
   test pins the old behaviour, report it instead of forcing it.

F. Tests for A (extend VoiceMatchTest or add VoiceDecyzjaTest in app/src/test):
   - every named example in this spec and in the two audit findings, as individual cases;
   - catalogue sweep, both languages: for every built-in set and every entry E, the exact transcript of E's primary
     answer with E on the board is accepted for E; with E NOT on the board (the rest of the set on the board, five
     at a time is enough, but also the whole rest at once) it is never accepted for another entry, unless that
     entry really accepts the same string. Assert ZERO false accepts and zero false rejects and print offenders.
   - noisy sweep: for every multi-word built-in answer, a transcript with one letter changed in the longest word is
     accepted for its own target when the rest of the set is in "others" in at least 90 percent of cases (measure,
     then pin the measured numbers with a small tolerance and print them), and never accepted for a different entry.
   - mutation check: disable each rule (A1, A2 owner-awareness, A3, A5, B, C) one at a time, confirm a test fails.
   Write down in your report the before/after numbers: how many built-in entries were absorbed by a neighbour before
   (the audit measured 191 Polish and 139 English with the whole rest of the set on the board) and after.

Run the full debug and release suites before committing. Describe the new API precisely in your final message:
the engineer wiring PlayScreen will work from it.
`

const SPEC_MODES = `
STAGE 3 OF 3: THE THREE QUICK MODES (ui/TrueFalseScreen.kt, ui/MultipleChoiceScreen.kt, ui/TimeAttackScreen.kt).
Stages 1 and 2 are committed in this worktree (git log -5). Read the whole modes audit first: its integration notes
describe each screen's state, end flow and line numbers, and its findings are confirmed. A later team replaces the
three end-of-round layouts with one shared result screen that shows rewards, so keep your changes to those layouts
small (texts and one icon) and put your effort into rules and state.

A. TRUE/FALSE and MULTIPLE CHOICE never end for a student who stops making mistakes, and leaving throws the score
   away (finding tf-mc-no-end-exit-drops-score). New rules:
   1. A round is one pass: every usable row is asked once, in shuffled order, at most ROUND_LIMIT = 40 questions (a
      larger set contributes a random 40 per round). TRUE/FALSE therefore stops drawing with replacement: use a bag,
      keep about half of the questions true, and never show the same prompt twice in a round unless two rows really
      share it. MULTIPLE CHOICE already asks each row once per pass: stop starting a second pass.
   2. When the pass is done the run ends through the SAME path as losing all lives (onFinished fires once, the
      result layout appears), but the layout says the round is complete instead of game over: title
      "RUNDA UKOŃCZONA" / "ROUND COMPLETE", a medal or star sprite instead of the skull (look in
      app/src/main/res/drawable-nodpi and ui/Sprites.kt for an existing one), and when no life was lost and no
      question timed out an extra line "BEZ BŁĘDU" / "FLAWLESS". Losing all lives keeps "KONIEC GRY".
   3. Leaving mid-run: when at least one question has been answered, the first back press (system back and the
      on-screen back button) ends the run the same way (result layout, score recorded, title
      "RUNDA PRZERWANA" / "ROUND STOPPED"); a back press on the result layout leaves as today. With nothing answered
      yet, back leaves immediately and nothing is recorded. No dialogs.
   4. The HUD shows progress through the round as "7/20" next to or instead of the cosmetic level label, if it fits
      at 320 dp width; otherwise keep the HUD as it is.
   5. After EVERY answer on a false proposal in TRUE/FALSE (whether the student was right or wrong, and on timeout),
      the feedback must show the real pair (prompt and its correct translation) for the feedback time, because a
      correct "FAŁSZ" is saved as a recognition success for a word whose real translation was otherwise never shown.
      Check what the feedback shows today and extend it only if the real pair is missing.
   Make sure onFinished cannot fire twice and cannot fire with zero answered questions; make sure "JESZCZE RAZ"
   starts a new shuffled round; make sure timeouts emitted from long-running effects use current lambdas
   (rememberUpdatedState, the audit explains why this matters for the next team).

B. TIME ATTACK
   1. Shared prompts (finding ta-shared-prompt-rejects-correct-answer): build questions with
      Matching.answersForPrompt and Matching.samePrompt (stage 1). Rows that are the same question become ONE
      question in the pool; any listed translation is accepted; the attempt is credited to the row whose OWN
      accepted answers contain what was typed (fall back to the first row); a wrong answer or a skip is recorded
      for the first row only; the reveal lists the variants of all merged rows ("arm / hand").
   2. Skip hint (finding ta-skip-hint-under-next-prompt): the answer of the skipped word must not look like feedback
      for the next word. Show the skipped pair as one neutral line that names both sides ("mature: dojrzały"),
      visually separated from the new prompt and labelled POMINIĘTE / SKIPPED. The same applies to the hint shown
      after a second wrong ENTER if it can outlive the question.
   3. Short screens (finding ta-short-screen-hint-invisible): at 320x568 dp and 360x640 dp the hint line and a
      two-line prompt must be visible. Prefer moving the hint out of the weighted prompt panel and shrinking the
      prompt glyph when it wraps; a compact HUD below about 620 dp is acceptable. Landscape phones (360 dp tall) are
      out of scope: the app is portrait only.
   4. The quit button gets an accessible name and role and a touch target of at least 48 dp (finding
      ta-quit-button-unlabelled). Back mid-run follows rule A3: with at least one answered or skipped question the
      run ends on the result layout and is recorded, otherwise it leaves.

C. "Not enough entries" texts (finding not-enough-entries-wrong-reason) in MULTIPLE CHOICE and TRUE/FALSE: when the
   set has enough complete rows but wrong answers cannot be built because translations overlap, say that, and do not
   print a count that already meets the requirement. On that screen system back must behave like the on-screen back
   button in both modes.

D. Accessibility of the three screens. The repository has gates that no mode screen is covered by: read
   app/src/testDebug/java/com/matura/app/A11ySemanticsTest.kt (wymagajEtykiet) and TouchTargetAuditTest.kt
   (wymagajProgu). Add a NEW test file app/src/testDebug/java/com/matura/app/TrybyEkranyTest.kt that composes each of
   the three screens (running, feedback, result) and applies both gates, and fix what they find in your three files.
   If the helpers are private to those test classes, make them internal there (the one allowed edit to those files).

E. Behaviour tests in the same new file or a second one: Compose tests driven with mainClock that pin
   - TRUE/FALSE and MULTIPLE CHOICE: a 4-row set played without mistakes ends after 4 questions with the complete
     title, onFinished called exactly once with the right score and kills; back after one answer shows the stopped
     layout and records once; back before any answer records nothing;
   - TIME ATTACK with rows arm -> ręka and hand -> ręka in direction definition -> term: the pool has one "ręka"
     question, typing "hand" is accepted and the attempt carries the id of the hand row; skip shows the labelled
     pair;
   - no question in a TRUE/FALSE round built from set v031 ("Edukacja: Ludzie w szkole") in direction
     definition -> term ever presents a translation as false that the set lists for one of the prompt's alternatives
     (generate many rounds with different seeds; the question builder may need an internal seam for this).
   Extract pure helpers (round building, crediting) as internal top-level functions so they are testable without
   Compose, and test them in app/src/test.

F. Look at the result. Render the three screens (running, feedback, result) at 320x568, 360x640 and 360x740 dp the
   way app/src/testDebug/java/com/matura/app/PodgladEkranowTest.kt does (copy its helper into your new test file; do
   not edit that file), write the PNGs under app/build/podglad-tryby/, and LOOK at them with the Read tool. Fix
   clipping, overlaps and unreadable text that your changes introduce or that the audit names. List the image paths
   in your report.

Run the full debug suite and the release suite, and compile the offline-voice variant
(cd ${WT} && ANDROID_HOME=/opt/android-sdk ./gradlew --console=plain -Pvosk=true :app:compileDebugKotlin) before
committing.
`

const REVIEW_COMMON = `
You are an independent reviewer. Three commits were just made on branch wt/dopasowanie in the worktree ${WT}
(compare with the base: cd ${WT} && git diff ${BASE}..HEAD --stat, then read the full diff of the files in your
lens and the code around it). You did not write this code and you owe it nothing: your job is to find what is wrong
with it before students do. The specification the authors worked from is included below so you can judge the code
against it, but the specification can be wrong too: say so when it is.

HOW TO WORK
- Do not edit tracked files and do not commit. You may add throwaway probe tests under
  app/src/test/java/com/matura/app/ with file names starting with ZzSonda (you are the only one building in this
  worktree while you run), run them with ${S}/tools/wt-test.sh dopasowanie <label> --tests '*ZzSonda*', and you MUST
  delete them before you finish (verify with git status --short that the tree is clean).
- Prefer evidence over opinion: a failing probe test, a concrete input with the actual output, a rendered image.
  A finding without a concrete scenario is not a finding.
- Report only defects and risks that matter to a student, to the next engineer or to the release: wrong results,
  lost data, crashes, unreachable states, misleading text, a test that would pass with the feature broken,
  violations of the repository conventions (em or en dashes in new lines, texts the bitmap font cannot draw, missing
  Polish diacritics in visible text). Do not report style preferences.
- For each finding give: severity (high, medium, low), file and line, what happens, the smallest scenario that shows
  it, and the fix you recommend. Finish with a short list of things you checked and found correct.
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

phase('Matching')
const r1 = await agent(COMMON + SPEC_MATCHING, { label: 'implement:matching', phase: 'Matching' })

phase('Voice rules')
const r2 = await agent(COMMON + SPEC_VOICE + `\nREPORT OF THE PREVIOUS STAGE (for context)\n${r1}`, { label: 'implement:voice-rules', phase: 'Voice rules' })

phase('Modes')
const r3 = await agent(COMMON + SPEC_MODES + `\nREPORTS OF THE PREVIOUS STAGES (for context)\n--- stage 1\n${r1}\n--- stage 2\n${r2}`, { label: 'implement:modes', phase: 'Modes' })

phase('Review')
const LENSES = [
  {
    key: 'matching-voice',
    prompt: `YOUR LENS: answer matching and voice decision logic (game/Matching.kt, game/VoiceMatch.kt,
ui/VoicePolicy.kt, the one change in ui/VoiceInput.kt, and their tests).
Check in particular: (1) anything the old acceptedAnswers accepted that the new one rejects, on built-in data and on
nasty custom cells (empty parts, only brackets, nested brackets, many slashes, very long cells, digits, a cell that
is only punctuation); (2) performance: acceptedAnswers and samePrompt are called in loops over the whole set while
building rounds, and decide() runs on every partial speech result against the whole set: measure the cost on the
largest built-in set and on a 500-row custom set, report anything above a few milliseconds per call on a desktop
JVM; (3) false accepts and false rejects of decide(): write your own sweep, independent of the authors' tests, over
all built-in sets in both languages, including transcripts with a leading article (the, a, an), with a trailing
filler word, with one substituted letter, and the negation pairs; (4) whether decide() is actually wire-able into
ui/PlayScreen.kt onHeard (read that function, lines 380-420 at the base commit; it has monsters and crates with
ids and answers, the set, the listening language and isFinal) without information it does not have; (5) whether
the British spelling fold can turn a wrong word into an exact match; (6) whether the mutation checks claimed in the
reports are real: break two rules yourself and see the tests fail; (7) zaliczycWypowiedz: can a legitimate second
answer now be dropped in a way a student would notice (for example after a recogniser restart that reuses a number)?
Read how VoiceInput numbers utterances.`,
    spec: SPEC_MATCHING + SPEC_VOICE,
  },
  {
    key: 'modes',
    prompt: `YOUR LENS: the three quick modes (ui/TrueFalseScreen.kt, ui/MultipleChoiceScreen.kt,
ui/TimeAttackScreen.kt and their new tests).
Check in particular: (1) state machines: can onFinished fire twice, never, or with a stale score; what happens on
back during the feedback or reveal phase, on back exactly when the last question resolves, on JESZCZE RAZ after a
stopped round, on a set with exactly the minimum number of usable rows, on the random mix (40 words) and on a
200-row custom set; (2) remember keys and LaunchedEffect keys: anything that survives a restart and should not, or
is reset by recomposition when App refreshes its data after onFinished (the modes audit explains that App passes a
new WordSet instance with the same entries list); (3) attempts: each answered question produces exactly one Attempt
with the right entry id, direction and outcome, also for merged rows in time attack and for timeouts; a question on
screen when the run is stopped by back produces none; (4) the true/false generator: proportion of true questions,
no prompt repeated within a round, no false proposal that the set lists as a translation of one of the prompt's
alternatives (run thousands of generated rounds over every built-in set in both directions); (5) multiple choice:
no option that is a correct translation of the prompt other than the intended one, exactly one correct option, no
duplicate option texts; (6) layout: render the screens yourself at 320x568, 360x640 and 360x740 dp (the authors left
images under app/build/podglad-tryby/, but regenerate them) and LOOK at them with the Read tool: clipped buttons,
overlapping text, text the bitmap font cannot draw (it shows as blank), touch targets; (7) texts: Polish grammar and
diacritics, English correctness, tone.`,
    spec: SPEC_MODES,
  },
]
const reviews = []
for (const lens of LENSES) {
  const r = await agent(
    REVIEW_COMMON + '\n' + lens.prompt + '\n\nSPECIFICATION THE AUTHORS WORKED FROM\n' + lens.spec +
      '\n\nAUTHORS\' REPORTS\n--- stage 1\n' + r1 + '\n--- stage 2\n' + r2 + '\n--- stage 3\n' + r3,
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
Three implementation commits are on this branch (git log -5). Two independent reviewers examined them. Their
findings are below as JSON. For EACH finding: reproduce it first (a failing test is best). If it is real, fix it and
add or extend a test that would have caught it. If it is not real, or the recommended fix would break a rule of the
specification, do not change the code and explain why in your report. High and medium findings are mandatory to
resolve one way or the other; low findings: fix when the fix is small and safe.
Then verify the whole branch:
  1. ${S}/tools/wt-test.sh dopasowanie final-debug
  2. ${S}/tools/wt-test.sh dopasowanie final-release :app:testReleaseUnitTest
  3. cd ${WT} && ANDROID_HOME=/opt/android-sdk ./gradlew --console=plain -Pvosk=true :app:compileDebugKotlin
  4. cd ${WT} && git diff ${BASE}..HEAD | grep -nP '^\\+.*[\\x{2013}\\x{2014}]' ; echo "dash check exit=$?"   (must print no added line)
  5. git status --short must be clean after your commit (no probe files left by anyone: delete files named ZzSonda*).
Commit the fixes as one commit. In your final message list every finding with its fate (fixed, not real, deferred
and why), the final test counts for debug and release, the paths of rendered images worth looking at, and a
precise description of the public API the next engineer needs: Matching.samePrompt / answersForPrompt semantics,
the VoiceMatch decision function and its types, the VoicePolicy language function, and for each mode screen what
state marks a finished, completed or stopped round.

REVIEW FINDINGS (JSON)
${JSON.stringify(all, null, 1)}

SPECIFICATIONS OF THE THREE STAGES (for reference)
${SPEC_MATCHING}
${SPEC_VOICE}
${SPEC_MODES}
`,
  { label: 'fix-and-verify', phase: 'Fix' },
)

return { stage1: r1, stage2: r2, stage3: r3, reviews, fix }
