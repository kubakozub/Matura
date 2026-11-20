export const meta = {
  name: 'matura-gamification-domain',
  description: 'Implement the gamification domain layer (study points, ranks, daily goal, day streak, badges, exam countdown, save migration) as pure Kotlin with tests in an isolated worktree, then review, fix and verify',
  phases: [
    { title: 'Implement', detail: 'pure logic, persistence hook, migration, tests' },
    { title: 'Review', detail: 'rules and exploits reviewer, persistence and migration reviewer' },
    { title: 'Fix', detail: 'apply confirmed findings' },
    { title: 'Verify', detail: 'full test suites' },
  ],
}

const WT = '/home/user/Matura/.claude/worktrees/dorobek'
const BASE = '2b3377e'

const COMMON = `
You are working on an Android app (Kotlin, Jetpack Compose): "Matura", an offline pixel-art game for learning English
vocabulary for the Polish basic-level matura exam. Store version 0.9.18. Audience: Polish 18-19 year olds in their
final school year. No accounts, no server, no INTERNET permission; all state is one JSON file.

WHERE YOU WORK
A dedicated git worktree: ${WT}  (branch wt/dorobek, created from commit ${BASE}).
ALL your reading, editing, building and committing happens inside that directory. Use absolute paths under it and
start every shell command with:  cd ${WT} && ...
NEVER edit, build or run git commands in /home/user/Matura itself or in any other worktree under
/home/user/Matura/.claude/worktrees (another team is working there on the game engine and screens). Never push.
Never touch git config. Do not create other worktrees or branches.

BUILD AND TEST (inside the worktree; local.properties already points at the Android SDK)
  cd ${WT} && export ANDROID_HOME=/opt/android-sdk && ./gradlew --console=plain :app:testDebugUnitTest
  single class:    ... :app:testDebugUnitTest --tests 'com.matura.app.SrsTest'     (patterns work: '*Nagrody*')
  release variant: ... :app:testReleaseUnitTest
Other Gradle builds run elsewhere on the machine at the same time; if a build is slow, wait, do not kill daemons.
Baseline at ${BASE}: testDebugUnitTest and testReleaseUnitTest are green (483 plus 11 preview tests in debug, 439
in release). Tests in app/src/test run in both variants and must not use Compose test rules.

CODE CONVENTIONS OF THIS REPOSITORY (follow them exactly)
- Read the surrounding code first and match it. Read at least: model/Models.kt, learn/Srs.kt, learn/Sesja.kt,
  learn/Podsumowanie.kt, learn/Trudne.kt, learn/EntryId.kt, data/Repository.kt, and the tests SrsTest, MigracjaTest,
  PersistenceTest, PostepIntegracjaTest, PodsumowanieTest. The code is carefully commented: comments explain WHY,
  in Polish written without diacritics in the learn/ and data/ packages. Write new comments the same way and only
  where they explain a decision a reader could not guess. New identifiers in learn/ follow the Polish naming used
  there (Sesja, Trudne, Podsumowanie).
- NO em dashes and NO en dashes in anything you write (comments, strings, test names, commit messages): the owner
  removed them from the tree on purpose. Use a colon, a comma, a full stop or brackets. Do not hunt for old ones.
- The learn/ package is pure Kotlin with no Android imports and no clock: the day and every other input is a
  parameter, so everything is testable with plain JUnit. Keep it that way.
- Project principles stated in the existing comments, which your code must respect: honest numbers only (counts of
  real things; no percentage of "readiness", no forecast of the exam result); game score and learning are separate
  numbers; a missed day never lowers a Leitner box and nothing is ever taken away from the student.
- Texts a student can see: in this stage they live as Polish and English fields on the enums you add (the existing
  enum Achievement in model/Models.kt does the same). Polish must be correct and natural, with diacritics. Tone:
  direct, calm, a little playful, never childish. Short titles will be drawn with a bitmap font that has ONLY
  A-Z a-z 0-9, Polish letters, space and . , ! ? : / - _ + ' plus the multiplication sign and infinity: no
  parentheses, no percent sign, no equals sign, no quotes. Titles must fit in 24 characters. Descriptions are drawn
  with the system font and may use any punctuation except the forbidden dashes.
- Do NOT touch any UI file (everything under ui/), ui/Strings.kt, the game engine (game/), learn/Sesja.kt, the voice
  code, the vocabulary (data/BuiltInSets.kt, data/entry-ids.json), docs/, README.md, build files, the manifest.
  The screens will be built later on top of what you deliver, by someone else. The app must still compile and all
  existing tests must stay green.
- Commit your work in the worktree when your stage is done (git add the specific files, then git commit). Commit
  message in English: a short subject, a body that says what and why, no forbidden dashes, ending with exactly:
    Co-Authored-By: Claude <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_016ardp75CeKgBASgp8eAmD5
`

const SPEC = `
WHAT YOU BUILD: the gamification DOMAIN LAYER. One small persisted ledger is filled at the single place where
learning is already saved (Repository.recordAttempt). Everything is derived from the spaced-repetition transitions
that learn/Srs.kt already computes, so it cannot be earned by grinding an easy set.

1. SAVED DATA (model/Models.kt). All new fields have defaults, so old files still load.
   - WordProgress gains: szczyt: Int = 0  (the highest box this word and direction ever reached). Maintained only
     in Srs.apply: after computing the new state, szczyt = max(before.szczyt, before.box, new box). It never
     decreases and is always >= box. Purpose: counters and badges must not go down when a later mistake moves a word
     back, and climbing the same boxes again must not pay full points twice.
   - New @Serializable data class Dorobek (the ledger), next to WordProgress:
       xp: Int = 0                               total study points, never decreases
       dzien: Int = WordProgress.NO_DAY          the Srs day the two "today" counters refer to
       slowaDzis: Int = 0                        credits from memory on that day (drives the daily goal)
       xpDzis: Int = 0                           points earned on that day
       seria: Int = 0                            length of the current chain of goal days
       seriaDzien: Int = WordProgress.NO_DAY     the last day on which the daily goal was met
       rekordSerii: Int = 0                      longest chain ever
       dniNauki: Int = 0                         number of days with the goal met, in total
       powtorki: Int = 0                         on-time reviews from memory, in total
       odznaki: Map<String, Int> = emptyMap()    badge name -> Srs day it was earned (0 = before the ledger existed)
     NO_DAY is Int.MIN_VALUE: never do arithmetic with it, compare for equality first.
   - Options gains: celDnia: Int = 5 (allowed values 5, 10, 20), maturaDzien: Int = 20944 (calendar epoch day of
     6 May 2027, the written basic-level English exam), odliczanie: Boolean = true.
   - SaveData gains: dorobek: Dorobek = Dorobek().

2. CREDITS AND POINTS (new file learn/Nagrody.kt, object Nagrody, pure).
   enum Zaliczenie with points and whether it counts as "from memory":
       ROZPOZNANE  2 xp, not from memory   box 0 -> 1 by a recognition task (TaskKind.ROZPOZNANIE)
       NOWE       10 xp, from memory       box 0 -> 1 by recall (TaskKind.PRZYPOMNIENIE), first time (szczyt before < 1)
       AWANS      10 xp, from memory       box 1 -> 2, first time (szczyt before < 2)
       UTRWALENIE 20 xp, from memory       box 2 -> 3, first time (szczyt before < 3)
       POWTORKA    5 xp, from memory       any other recall success that Srs accepted as a qualifying check and that
                                           moved the schedule: climbing a box already reached before, or confirming
                                           a box 3 word on time (box unchanged, dueDay moved forward)
   fun zaliczenie(przed: WordProgress, po: WordProgress, proba: Attempt): Zaliczenie?
     Returns null unless proba.outcome == POPRAWNA and the state really moved (box rose, or dueDay moved forward on
     a word that was due). Recognition earns ROZPOZNANE only for box 0 -> 1 and nothing else, ever. Every wrong,
     hinted, shown or interrupted attempt gives null. Because Srs promotes a word at most once per day and
     direction, a word can produce at most one credit per day and direction: prove it with a test that pushes
     random attempt sequences through Srs.applyTo.
   fun dolicz(d: Dorobek, z: Zaliczenie, dzien: Int, cel: Int): Dorobek
     - If dzien differs from d.dzien and is later: the today counters restart at zero for the new day. If dzien is
       EARLIER than d.dzien (clock moved back): add the points to xp and change nothing else.
     - xp and xpDzis grow by z.xp. If z is from memory, slowaDzis grows by one; if z is POWTORKA, powtorki too.
     - Daily goal: the first moment slowaDzis reaches cel on a day that is not seriaDzien, that day is counted once:
       dniNauki += 1; the chain: no earlier goal day -> seria = 1; the previous goal day was yesterday or the day
       before (ONE missed day is forgiven) -> seria += 1; otherwise seria = 1. seriaDzien = dzien.
       rekordSerii = max(rekordSerii, seria). Raising the goal later the same day never un-counts the day.
   fun seriaNaDzis(d: Dorobek, dzien: Int): Int   the chain to show: d.seria while dzien - seriaDzien <= 2, else 0
   fun slowaNaDzis(d: Dorobek, dzien: Int): Int   d.slowaDzis if d.dzien == dzien, else 0 (same for points)
   fun celZaliczony(d: Dorobek, dzien: Int): Boolean
   fun pierscien(slowa: Int, cel: Int): Int       0..4 for a five-state progress ring: 0 only for zero credits,
                                                 4 only when the goal is met, otherwise 1..3 by quarters
   Ranks: enum Ranga(prog: Int, pl: String, en: String), ten ranks named after the rituals of the final school year,
   in this order with these thresholds (they are data: keep them in one table so they are easy to retitle):
       0 DEKLARACJA ZŁOŻONA / DECLARATION FILED
       30 PRÓBNA MATURA / MOCK EXAM
       150 STUDNIÓWKA / PROM NIGHT
       500 POLONEZ / POLONAISE
       1200 OSTATNI DZWONEK / LAST BELL
       3000 KASZTANY KWITNĄ / CHESTNUTS IN BLOOM
       6000 CZARNY DŁUGOPIS / BLACK PEN
       12000 ARKUSZ OTWARTY / PAPER OPENED
       25000 KARTA ODPOWIEDZI / ANSWER SHEET
       50000 POSTRACH ARKUSZY / TERROR OF EXAM PAPERS
   fun ranga(xp: Int): Ranga, fun nastepna(xp: Int): Ranga? (null at the top), fun doNastepnej(xp: Int): Int.
   The whole day logic uses the Srs day (Srs.dayOf, UTC), the same day the schedule uses, on purpose: one clock.

3. WHAT A ROUND WAS WORTH (same file, pure).
   - fun zaliczeniaRundy(progressPrzed: Map<String, WordProgress>, proby: List<Attempt>, dzien: Int): List<Zaliczenie>
     replays the attempts of a round through Srs.applyTo from the progress map taken at round start and returns the
     credits in order (used only to itemise the result screen; the stored total stays the source of truth).
   - data class NagrodaRundy with: xp gained, the credits grouped by kind with their counts, slowaDzis before and
     after, cel, whether the goal was met by this round, the shown chain before and after, the rank before and
     after (so the screen can announce a promotion), the list of badges earned in this round, and a record flag
     with the previous best (see below).
   - fun roznica(przed: SaveData, po: SaveData, proby: List<Attempt>, wynik: ScoreRecord?, dzien: Int): NagrodaRundy
     a pure difference of two snapshots. Record: only when wynik is not null and its score beats the best earlier
     score of the same GameMode in przed.scores; the first score in a mode is not a record.

4. BADGES (new file learn/Odznaki.kt, pure). Leave the existing enum Achievement and its screen untouched; add a
   new enum Odznaka which contains the eight existing achievements UNDER THE SAME CONSTANT NAMES with the same
   Polish and English titles and descriptions, plus the new ones. Each has: group, target (cel: Int), Polish and
   English title and description, and a counter. Badge names are part of the save from now on (Dorobek.odznaki):
   say so in the KDoc and add a test that pins the full list of names.
   data class Liczniki (the numbers badges are counted from), computed by fun liczniki(data: SaveData): Liczniki:
       poznane       distinct entry ids of BUILT-IN sets with szczyt >= 1 in at least one direction
       utrwalone     distinct built-in ids with szczyt == 3 in at least one direction
       obieStrony    distinct built-in ids with szczyt >= 2 in BOTH directions
       dzialy        number of topics (WordSet.category of built-in sets) with at least 10 poznane words
       zestawy       number of built-in sets in which every entry has szczyt == 3 in at least one direction
       powtorki, dniNauki, rekordSerii   from Dorobek
       tryby         number of distinct GameMode values in data.scores
       gamesPlayed, totalKills, bestScore, bestStreak   from Profile
     Count over the distinct ids of the CURRENT built-in sets, never over the raw progress map: migrations keep old
     keys, and 51 ids are shared between topics. Own sets must not be able to earn catalogue badges.
   Badges (name: Polish title / English title; target; counter). Descriptions follow one plain pattern in both
   languages and say exactly what is counted (for example "Poznaj 50 słów ze słownika gry." / "Learn 50 words from
   the game's dictionary."); the day badges add that the days need not be in a row.
     group NAUKA
       SLOWA_5 Pierwsza piątka / First five; 5 poznane        SLOWA_50 Pół setki / Half a hundred; 50
       SLOWA_250 Ćwierć tysiąca / Quarter thousand; 250       SLOWA_1000 Tysiąc słów / A thousand words; 1000
       UTRWALONE_10 Dziesięć na stałe / Ten locked in; 10 utrwalone      UTRWALONE_50 Pięćdziesiąt na stałe / Fifty locked in; 50
       UTRWALONE_200 Dwieście na stałe / Two hundred locked in; 200      UTRWALONE_500 Pięćset na stałe / Five hundred locked in; 500
       POWTORKI_10 W terminie / On time; 10 powtorki          POWTORKI_100 Sto w terminie / A hundred on time; 100
       POWTORKI_500 Pięćset w terminie / Five hundred on time; 500
       OBIE_10 W obie strony / Both ways; 10 obieStrony       OBIE_100 Tam i z powrotem / There and back again; 100
     group REGULARNOSC
       DNI_1 Pierwszy dzień / Day one; 1 dniNauki             DNI_7 Siedem dni / Seven days; 7
       DNI_30 Trzydzieści dni / Thirty days; 30               DNI_100 Własna studniówka / Your own hundred days; 100
       SERIA_3 Trzy z rzędu / Three in a row; 3 rekordSerii   SERIA_7 Tydzień z rzędu / A week in a row; 7
       SERIA_14 Dwa tygodnie z rzędu / Two weeks in a row; 14 SERIA_30 Miesiąc z rzędu / A month in a row; 30
     group DZIALY
       DZIAL_1 Pierwszy dział / First topic; 1 dzialy         DZIALY_5 Pięć działów / Five topics; 5
       DZIALY_14 Cała podstawa / The whole basic level; 14
       ZESTAW_1 Zestaw na stałe / A set locked in; 1 zestawy  ZESTAWY_5 Pięć zestawów / Five sets; 5
       ZESTAWY_25 Dwadzieścia pięć zestawów / Twenty-five sets; 25      ZESTAWY_102 Komplet / The full set; 102
     group GRA: the eight existing ones with targets FIRST_BLOOD 1 totalKills, ROOKIE 5 gamesPlayed, VETERAN 25
       gamesPlayed, SHARPSHOOTER 100 totalKills, COMBO_X3 6 bestStreak, COMBO_X5 9 bestStreak, SCORE_500 500
       bestScore, SCORE_2000 2000 bestScore, plus TRYBY_4 Cztery tryby / All four modes; 4 tryby.
   fun postep(o: Odznaka, l: Liczniki): Int (the counter capped at the target), fun spelniona(o, l): Boolean,
   fun odblokuj(d: Dorobek, l: Liczniki, dzien: Int): Dorobek adds every satisfied badge that is not yet in
   d.odznaki with the given day; an earned badge is never removed even if a counter could later be lower.
   fun zdobyta(o: Odznaka, d: Dorobek): Boolean.

5. EXAM COUNTDOWN (new file learn/Kalendarz.kt, pure, no java.time at runtime because minSdk is 24).
   Conversions between a calendar epoch day (days since 1970-01-01) and year, month, day; days in a month with leap
   years; fun dzienLokalny(nowMs: Long, przesuniecieMs: Int): Int (the local calendar day from the time and the zone
   offset, floor division); fun dniDoMatury(maturaDzien: Int, dzienLokalny: Int): Int; fun popraw(rok, miesiac,
   dzien) that clamps an impossible date (30 February) to the last day of the month. The countdown is display only,
   so it deliberately uses the LOCAL calendar day, not the Srs day: it has to match the calendar on the wall. Say
   so in the KDoc. Tests may cross-check against java.time (tests run on the JVM): 20944 is 2027-05-06; from
   2026-11-20 it is 167 days; leap years; the daylight-saving days 28 March 2027 and 31 October 2027 in Poland.

6. THE HOOK (data/Repository.kt).
   - recordAttempt: it already has the state before and after Srs.applyTo. Compute the credit for the attempt's key,
     apply Nagrody.dolicz with the day of the attempt and the daily goal from the saved options, then
     Odznaki.odblokuj, and write the ledger in the SAME save as the progress. The existing optimisation must hold:
     an attempt that changes nothing in the schedule earns nothing and must not write the file (extend the existing
     no-change check; prove it with a test that counts writes, the way PostepIntegracjaTest already does). A failed
     save must not leave points in memory. A duplicated attempt id must not pay twice.
   - recordGame gains a parameter nowMs: Long (time is always injected in this class) placed after the existing
     parameters, and runs Odznaki.odblokuj after updating the profile and scores, so game badges and the modes badge
     are stamped. Update its single call site in ui/App.kt minimally (pass System.currentTimeMillis()); this is the
     only UI line you may touch.
   - Migration: SCHEMA_VERSION becomes 4 and migrate() gets a step for files below 4, pure and idempotent like the
     existing steps (read their KDoc): (a) every progress entry gets szczyt = max(szczyt, box); (b) the ledger is
     seeded from real boxes only, never from game points: over the distinct non-blank entry ids of ALL current sets
     and both directions, box 1 gives 10 (2 when lastTask is ROZPOZNANIE), box 2 gives 20, box 3 gives 40; day
     counters, chain and powtorki start at zero ("we count from this version"); (c) badges already satisfied are
     unlocked silently with day 0, so the first round after the update does not announce badges the student has had
     for months. The step must not run again on a file that already has a ledger with points.
   - Update the KDoc of SCHEMA_VERSION and of SaveData for the new step.

7. A FIX IN THE SCHEDULE THAT THE LEDGER DEPENDS ON (learn/Srs.kt, promote). Today a correct recognition answer
   (true/false, multiple choice) on a DUE word that sits in box 2 or 3 cannot raise the box (recognition is capped
   at box 1), but the branch for "ceiling reached" still moves the due date by 3 or 7 days. So one tap, right half
   the time by chance in true/false, cancels the recall check that typing had earned, and the word is no longer
   offered for typing that day. Change it so that recognition moves the due date only for a word at or below its own
   ceiling (box 0 or 1); for a word above the ceiling a recognition success returns the state unchanged, so the
   recall stays due. Recall behaviour is unchanged. Update the two SrsTest cases that pinned the old behaviour
   (say so in the commit message) and add tests for the new rule. Keep every other Srs rule exactly as it is.

TESTS (plain JUnit in app/src/test/java/com/matura/app/, in the style of SrsTest and PodsumowanieTest):
NagrodyTest (every row of the credit table and every zero case; the once-per-day property with random sequences;
dolicz across days: same day, next day, one missed day forgiven, two missed days restart, clock moved back, goal
counted once, goal raised later, NO_DAY never used in arithmetic; rank boundaries; roznica for a first round, a
zero round, a promotion and a record; replay equals what Repository stored for the same attempts), OdznakiTest
(counters on maps built with Srs.applyTo, distinct ids, own sets excluded, szczyt keeps a badge after a mistake,
the pinned name list, the eight legacy badges agree with Achievement.unlockedBy for the same profile, every title
fits 24 characters and uses only the bitmap font characters, no blank texts, no forbidden dashes), KalendarzTest,
an integration test with a real Repository in a temporary folder (credit lands in the same save as the progress, no
extra write when nothing changes, failed save adds no points, survives a restart, recordGame stamps game badges),
MigracjaTest additions (the 0.9.4 fixture reaches schema 4 with zero points and exactly the legacy badges its
profile satisfies stamped with day 0; a schema 3 save with progress gets the exact seed; a second load is identical;
a file that already has a ledger is not reseeded), SrsTest additions (szczyt invariants; the recognition rule).
Run the complete :app:testDebugUnitTest and :app:testReleaseUnitTest before committing. Do not weaken or delete
existing tests; where a test pinned behaviour this spec deliberately changes, change it and say so.

REPORT at the end: the public API you created (names, signatures, one line each), every decision where you deviated
from this spec and why, every existing test you changed and why, the final test counts.
`

phase('Implement')
const report = await agent(`${COMMON}
${SPEC}`, { label: 'implement:domain', phase: 'Implement' })

// ---- Lean continuation: align the draft with the final design, one review, one fix ----
const S = '/tmp/claude-0/-home-user-Matura/0e5748ef-99b0-59e1-b03e-46ac0f545d18/scratchpad'

const ALIGN = `
SECOND STAGE: ALIGN THE DRAFT WITH THE FINAL DESIGN.
The first stage (its report is below) built a draft of the rewards ledger from an early specification and committed
it in this worktree (git log -3 --stat). Since then a panel of three designers and three judges produced the final
design. It keeps the file and type names of the draft and changes its numbers and several rules. Your job is to
bring the committed draft in line with it, with tests, and nothing else.

READ FIRST: ${S}/design-final.md , sections "C1. Ledger, XP and stages", "C2. Daily goal and day streak with days
off", "C3. Matura countdown" (pure parts only), "3. Tables" (3.1 to 3.4), "6. Migration" and, in section 8, the
list "What must change in the draft" (twelve points). That document is the specification for this stage. Where it
and the early specification disagree, the final design wins. Where it leaves something open, keep the draft.

DECISIONS ALREADY TAKEN (do not reopen them):
- All twelve points of "What must change in the draft" are adopted.
- Point 12 (Srs.promote: a recognition success no longer moves the due date of a box 2 or box 3 word): KEEP the fix
  that the draft made. It is a confirmed defect; the owner will be told.
- Own sets: the cap of 10 paid credits a day stays, as one named constant.
- The stage ladder is the thirteen-step table of section 3.2 including WAKACJE and the lap counter.
- Badges: the CORE list of section 3.3 AND the four S2 families (ZESTAWY, DZIALY, OBIE STRONY, NAPRAWA), because the
  counters are built anyway. Ids and thresholds exactly as in the table; legacy titles word for word as in the
  existing enum Achievement.
- Repository.recordGame: another team adds, in its own branch, a parameter doRankingu: Boolean = true as the THIRD
  parameter with this meaning: when false, gamesPlayed, totalKills and the set's timesPlayed still grow, but the
  record is not added to scores and bestScore / bestStreak are not raised. Implement exactly that parameter, with
  that name, position and meaning, so that the two branches merge without a semantic conflict; put any parameter of
  your own (clock, badge confirmation) AFTER it, with defaults.
- UI work is NOT part of this stage: no file under ui/ changes, no strings in ui/Strings.kt. Pure helpers that the
  screens will need and that the final design places in learn/ (for example the share text or menu subtitles) are
  also NOT part of this stage, a later team builds them with the screens. Section C0 belongs to another team.
- Everything in COMMON above still holds (worktree, conventions, no forbidden dashes, comments in Polish without
  diacritics in learn/ and data/, tests in plain JUnit, both variants green before the commit).

DELIVER: the changed model, rules, repository hooks and migration; tests rewritten for the new numbers and rules
(every row of table 3.1 and every zero case; the two daily caps; the goal in points with 30, 60, 120; the streak
with the reserve of days off: start 1, plus 1 per 7 streak days, cap 2, a missed day spends one, no reserve means
the streak restarts; clock moved back; stage boundaries and laps; every badge threshold; the migration cases of
section 6 including the 0.9.4 fixture and "a second load is identical"); a property test that no sequence of
attempts can earn more points for one word and direction than the table allows; the dash check
(git diff ${BASE}..HEAD | grep -nP '^\\+.*[\\x{2013}\\x{2014}]' must print nothing). Run the complete
:app:testDebugUnitTest and :app:testReleaseUnitTest. Commit as one commit with trailers
    Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_016ardp75CeKgBASgp8eAmD5
REPORT: the final public API (names, signatures, one line each: this is what the screens will be built on), every
place where you could not follow the final design and why, the final test counts.

REPORT OF THE FIRST STAGE
`

phase('Align')
const aligned = await agent(`${COMMON}
${ALIGN}${report}`, { label: 'implement:align-final-design', phase: 'Align' })

const REVIEW_SCHEMA = {
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
        },
        required: ['id', 'severity', 'file', 'what', 'scenario', 'fix'],
      },
    },
  },
  required: ['summary', 'findings'],
}

phase('Review')
const review = await agent(`You are an independent reviewer. Two commits on branch wt/dorobek in the worktree
${WT} add a rewards ledger (study points, stages, daily goal, day streak with days off, badges, exam countdown
helper, save migration) to an offline Android vocabulary game for Polish matura students. Read the diff
(cd ${WT} && git diff ${BASE}..HEAD --stat, then the files) and the specification it must follow:
${S}/design-final.md (sections C1, C2, C3 pure parts, 3.1 to 3.4, 6, and the twelve changes in section 8).
You did not write this code. Find what is wrong with it before students do.

Work read-only: do not edit tracked files and do not commit. You may add throwaway probe tests named ZzSonda*.kt
under app/src/test/java/com/matura/app/, run them with
  ${S}/tools/wt-test.sh dorobek <label> --tests '*ZzSonda*'
and you MUST delete them before you finish (git status --short must be clean). Never kill Gradle daemons.

Look hardest at:
1. Exploits: any sequence of answers, modes, own sets, clock changes or option changes that earns points, goal
   days, streak days or badges without real spaced recall; or earns them twice (duplicate attempt ids, the same
   word in two sets, both directions, a restart between the attempt and the save).
2. Things taken away: any path where points, study days, the record streak, a badge or a box goes DOWN.
3. The save file: an old 0.9.18 file (schema 3, test fixture app/src/test/resources/zapis-0.9.4-vc21.json and
   files written by the current code) must load, migrate once, and load identically the second time; a failed
   write must leave memory and disk consistent; no extra write per answer when nothing changed; NO_DAY
   (Int.MIN_VALUE) never used in arithmetic; behaviour at day boundaries.
4. Numbers against the tables of section 3 (every row), and tests that would still pass if the rule were broken:
   break two rules yourself and see whether a test fails.
5. Files this team must not touch (anything under ui/, game/, learn/Sesja.kt, BuiltInSets, docs, build files).
6. Repository conventions: no em or en dashes in added lines; visible titles only use the bitmap font characters
   (A-Z a-z 0-9, Polish letters, space . , ! ? : / - _ + ' and the multiplication sign); Polish with diacritics.
Report only real defects with a concrete scenario (inputs and the actual result), each with a recommended fix.

AUTHORS' REPORT OF THE SECOND STAGE
${aligned}`, { label: 'review:ledger', phase: 'Review', schema: REVIEW_SCHEMA })

phase('Fix')
const findings = (review && review.findings) || []
log(`review findings: ${findings.length}`)
const fixReport = await agent(`${COMMON}
FINAL STAGE: FIX WHAT THE REVIEWER FOUND, THEN VERIFY.
The ledger was aligned with the final design (${S}/design-final.md) in the previous stage. An independent reviewer
examined it; the findings are below as JSON. For EACH finding: reproduce it first (a failing test is best). If it
is real, fix it and add a test that would have caught it. If it is not real, or the fix would contradict the final
design, leave the code and explain why. High and medium findings must be resolved one way or the other.
Then verify: ${S}/tools/wt-test.sh dorobek final-debug ; ${S}/tools/wt-test.sh dorobek final-release
:app:testReleaseUnitTest ; cd ${WT} && git diff ${BASE}..HEAD | grep -nP '^\\+.*[\\x{2013}\\x{2014}]' (must print
nothing) ; git status --short clean after your commit (delete any ZzSonda* file).
Commit the fixes as one commit (trailers: Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com> and
Claude-Session: https://claude.ai/code/session_016ardp75CeKgBASgp8eAmD5).
REPORT: every finding with its fate, the final test counts, and the final public API the screens will be built on
(names, signatures, meaning, one line each), including what Repository.recordAttempt and recordGame now take and
return.

REVIEW FINDINGS (JSON)
${JSON.stringify(findings, null, 1)}

REPORT OF THE ALIGN STAGE
${aligned}`, { label: 'fix-and-verify', phase: 'Fix' })

return { report, aligned, review, fixReport }
