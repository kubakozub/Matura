# FINAL SPECIFICATION: gamification for the release after 0.9.18 ("Matura Angielski: gra w słówka")

Read-only pass: no repository file, git state or Gradle task was touched. Base design: "engagement" (the loop and its moments), with the counting rules of "learning", the integration approach of "feasibility", and every judge correction applied. Where judges disagreed, the choice and its reason are stated in place. Numbers marked "simulated" come from a throwaway model of `Srs` and `Sesja.zaplanuj` (60% of new words already known, recall 85/90/93% in boxes 1/2/3), kept at `/tmp/claude-0/-home-user-Matura/0e5748ef-99b0-59e1-b03e-46ac0f545d18/scratchpad/final/sim.py`. They are calibration, not app data.

## 0. Facts checked in the tree today that bind this spec

1. HEAD is `0aab7c5` "Multiplier badges show x3 and x5". The sprite bug reported by the engagement design is already fixed by redrawing two sprites. There is nothing to do; do not touch the CLASSIC canvas for it.
2. Two parallel worktrees matter:
   - `/home/user/Matura/.claude/worktrees/petla` (commit `5dc1880`, task #4): `GameEngine` already has `GameConfig.calm`, `GameConfig.finite`, and `GameSnapshot.completed`, `calm`, `targetsDone`, `targetsTotal`, `missedWords: List<MissedWord>` (prompt, solution, entryId, direction, times), `reveal: Reveal?`, `REVEAL_TIME = 3.0f`. PlayScreen does not use them yet. This spec defines no engine behaviour; it only reads those fields.
   - `/home/user/Matura/.claude/worktrees/dorobek` (uncommitted): a first draft of the ledger (`Models.kt`, `Srs.kt`, `Repository.kt`, `learn/Nagrody.kt`, `learn/Odznaki.kt`, `learn/Kalendarz.kt`, tests). This spec keeps its file and type names. Section 8 lists exactly what must change in it.
3. The bundled bitmap font has 93 glyphs (`ui/PixelFont.kt`, `ORDER`): A-Z, a-z, digits, Polish diacritics in both cases, and `. , ! ? : / - _ × ∞ + '` plus space. `( ) ; = % < > "` are silently substituted. Every string marked P below uses only supported glyphs; strings marked S go in the system font.
4. Catalogue: 102 sets, 2084 rows, 2018 distinct entry ids, 14 topics with 101 to 365 distinct words, sets of 20 to 25 words.
5. Sprites: `ring_00` (grey) and `ring_04` (green) have the same shape, and the three medals have the same shape. So a ring always stands next to a number, a met goal also gets the `checkmark` sprite, and a badge tier is always written as text.
6. Dates: epoch day 20944 is Thursday 6 May 2027; today is 20777; the difference is 167; day 100 is Tuesday 26 January 2027. Nobody has verified 6 May 2027 against the CKE notice.
7. `android:allowBackup="false"` (`AndroidManifest.xml:31`): a reinstall or a new phone wipes all progress.
8. Task #13 (long-term review schedule) is postponed, so box 3 keeps returning every 7 days and a student with B answers a day plateaus near 7 x B words (simulated: 82 words for a session-only student, 167 at 25 answers a day, 333 at 50, after 167 days). All thresholds below are set for this scheduler.

## 1. Summary: the loop in five sentences

1. On the menu one player card shows the stage (one of 13 matura-year milestones), an XP bar, today's goal ring, the day streak and "DO MATURY: 167 DNI", and the two learning buttons say what waits today ("Na dziś: 8 powtórek i 5 nowych słów, ok. 5 min").
2. During a round nothing new is drawn on the board: every answer that really moves the review schedule is written to a small ledger in the same save as the word's progress (10, 20 and 30 XP for the first, second and third spaced recall of a word, 5 XP for a later on-time review, 3 XP for a first recognition in a choice mode).
3. After a round the game result stays on top, and a separate NAUKA block below shows the XP earned with a per-event breakdown (or a plain sentence saying why it was 0), the daily goal, the streak, a new stage, new badges, then the existing learning summary with missed words as "question = answer" pairs, and a share button.
4. The next day the words that come back pay again, a day with 30 XP counts as a study day and extends the streak, and a missed day is covered by a small reserve of days off and never touches boxes, XP, study days or badges.
5. Every number is a count of real events, game points stay a separate number with their own colour and icon, and there is no percentage, grade or forecast anywhere.

Week one for a session-only student (simulated): day 1: PIERWSZE SŁOWO, stage 2, goal met, streak 1. Day 3: SERIA 3, stage 3. Day 4: W TERMINIE. Day 5: stage 4. Day 7: SERIA 7, 25 SŁÓW, 5 UTRWALONYCH, one day off added. Day 9: stage 5. Day 10: 10 DNI NAUKI, 20 UTRWALONYCH.

## 2. CORE features

Files: pure logic in `learn/Nagrody.kt`, `learn/Odznaki.kt`, `learn/Kalendarz.kt`, new `learn/Podpisy.kt`, `learn/Udostepnianie.kt`; data in `model/Models.kt`; UI in new `ui/Grywalizacja.kt` (composables) and `ui/GryStr.kt` (strings, same pattern as `SesjaStr`; the language is passed in, never guessed with `str.play == "GRAJ"`). Every composable takes plain data and strings, never the repository (same rule as `PodsumowanieRundy`).

### C0. Prerequisites (S, low risk; shared with tasks #4 and #5)

**C0.1 One day for the whole app.** `App.kt:86` remembers the day once per process.
- State: `var dzisiaj by remember { mutableStateOf(Srs.dayOf(now)) }` and `var dzienKalendarza by remember { mutableStateOf(Kalendarz.dzienLokalny(now, TimeZone.getDefault().getOffset(now))) }`.
- Pure function: `Nagrody.dzienPoWznowieniu(obecny: Int, nowMs: Long, wRundzie: Boolean): Int` returns `obecny` when `wRundzie`, else `Srs.dayOf(nowMs)`.
- Both values are recomputed (a) on `Lifecycle.Event.ON_RESUME` when `stack.last() !is Screen.Play` (same observer pattern as `PlayScreen.kt:725`, closure through `rememberUpdatedState`), and (b) in `exit` and in `finish` of the Play branch. The per-round `val dzisiaj` at `App.kt:204` stays fixed for the round.
- Tests: JUnit for the pure function (in a round: unchanged; outside: new day; clock earlier than stored day: follows the clock).

**C0.2 One result layout with a slot and pinned buttons.** The CLASSIC overlay sits inside the board box and is clipped at 360x640 dp (commit `2b3377e`).
- New helper in `ui/Common.kt`: `UkladWyniku(tresc: @Composable ColumnScope.() -> Unit, przyciski: @Composable ColumnScope.() -> Unit)`: a `Column(fillMaxSize)` with a scrolling area (`weight(1f).verticalScroll`) and a button area pinned under it (2 dp top border `Palette.SurfaceHigh`, padding 12 dp).
- The four existing result composables keep their own header and stats and only swap their outer scrolling `Column` for `UkladWyniku`: `GameOverOverlay` (`PlayScreen.kt:976`), `TfGameOver` (`TrueFalseScreen.kt:774`), `McGameOver` (`MultipleChoiceScreen.kt:923`), `ResultScreen` (`TimeAttackScreen.kt:701`). In CLASSIC the result is drawn instead of the board, answer bar and keyboard (full window), not inside the board box.
- Each mode screen gets two optional parameters: `stopka: (@Composable () -> Unit)? = null`, rendered under the mode's stats inside the scrolling area and only after the run was reported (`reported == true`, so a stale block never shows after JESZCZE RAZ); and `przyciskGlowny: Pair<String, () -> Unit>? = null`, which replaces JESZCZE RAZ as the pinned primary button (task #4 passes DALEJ for session rounds).
- Header text of a round that ended by itself belongs to task #4 and #5. Recommended strings (P): `RUNDA ZALICZONA` / `ROUND CLEARED` with `icon_trophy` when `snap.completed`, `KONIEC RUNDY` / `ROUND OVER` with `icon_target` for calm rounds, the existing skull and `KONIEC GRY` only for ranked games.
- Tests (Robolectric): `WynikUkladTest`: at `w320dp-h568dp`, with a long sample `stopka`, the primary button (JESZCZE RAZ or the injected label) is displayed without scrolling in all four modes. `PodgladEkranowTest`: the four "koniec" previews pass a sample `stopka`.

**C0.3 Multiplier badges.** Already fixed in `0aab7c5`. No change.

### C1. Ledger, XP and stages (M, medium risk: touches the save path)

**Rules.** One attempt yields at most one credit, classified from the word's `WordProgress` before and after `Srs.applyTo`, so it can never drift from the scheduler. The table is in section 3.1. Consequences, each covered by a test:
- a word pays at most once a day per translation direction, because `Srs` accepts one qualifying check a day;
- the first climb of a word pays 10 + 20 + 30 = 60 XP once per direction, on at least three different days;
- a climb back to a box the word has already reached (after a mistake) and an on-time confirmation in box 3 pay the flat 5 XP, so forgetting never pays better than remembering;
- recognition pays only for box 0 -> 1 (3 XP) and only for the first 5 such words a day; a recognition answer that merely moves a due date pays nothing;
- words outside the bundled catalogue (own sets, rows the student added anywhere) pay like any other word, but only for the first 10 paying events a day;
- wrong, hinted, shown and interrupted attempts, a second hit the same day, and a duplicate attempt id pay nothing and take nothing away.

XP never decreases. The stage is derived from XP (section 3.2) and is never stored.

**Persisted data.**

```kotlin
// model/Models.kt
data class WordProgress(..., val szczyt: Int = 0)   // highest box ever reached; only Srs.apply writes it

@Serializable
data class Dorobek(
    val xp: Int = 0,                               // never decreases
    val dzien: Int = WordProgress.NO_DAY,          // Srs day the four "today" counters refer to
    val xpDzis: Int = 0,
    val noweDzis: Int = 0,                         // first meetings today (NOWE and ROZPOZNANE, paid or not)
    val rozpoznaneDzis: Int = 0,                   // paid recognition credits today, 0..5
    val wlasneDzis: Int = 0,                       // paid credits from words outside the catalogue today, 0..10
    val seria: Int = 0,                            // goal days in the current chain
    val seriaDzien: Int = WordProgress.NO_DAY,     // last day counted as a goal day
    val wolne: Int = 1,                            // days off in reserve, 0..2
    val rekordSerii: Int = 0,
    val dniNauki: Int = 0,                         // goal days in total, never decreases
    val powtorki: Int = 0,                         // paid recalls of words that came back (AWANS, UTRWALENIE, POWTORKA)
    val naprawione: Int = 0,                       // catalogue words consolidated for the first time after a mistake
    val odznaki: Map<String, Int> = emptyMap(),    // Odznaka.name -> Srs day earned; 0 = before the ledger
    val odznakiNowe: Set<String> = emptySet(),     // earned but not yet shown to the player
)
// SaveData: val dorobek: Dorobek = Dorobek()
// Options:  val celDnia: Int = 30, val maturaDzien: Int = 20944, val odliczanie: Boolean = true
```

**Pure functions.**
- `Srs.apply`: the returned state gets `szczyt = maxOf(before.szczyt, before.box, po.box)`. Invariants: never decreases, never below `box`.
- `Nagrody.zaliczenie(przed: WordProgress, po: WordProgress, proba: Attempt): Zaliczenie?`. Null unless `outcome == POPRAWNA` and (`po.box > przed.box` or (`po.box == przed.box` and `po.dueDay > przed.dueDay`)). Recognition: `ROZPOZNANE` only when `przed.box == 0 && po.box == 1`, else null. Recall: box rose above `maxOf(przed.szczyt, przed.box)` gives `NOWE` (new box 1), `AWANS` (2) or `UTRWALENIE` (3); anything else gives `POWTORKA`.
- `Nagrody.dolicz(d: Dorobek, z: Zaliczenie, dzien: Int, cel: Int, wlasne: Boolean, naprawa: Boolean): Doliczenie`, with `Doliczenie(dorobek: Dorobek, wplyw: Wplyw)`, `Wplyw(rodzaj: Zaliczenie, xp: Int, limit: Limit?)`, `enum class Limit { ROZPOZNANIA, WLASNE }`. Steps:
  1. If `dzien == NO_DAY`: add `z.xp` to `xp` and stop (cannot come from `Srs.dayOf`; a guard).
  2. Day: if `d.dzien == NO_DAY` or `dzien != d.dzien`, the four "today" counters start from 0 and `dzien` becomes the new day. This also covers a clock that went back (`dzien < d.dzien`).
  3. Re-anchor: if `d.seriaDzien != NO_DAY && d.seriaDzien > dzien`, set `seriaDzien = dzien` and increment nothing. The earlier day then counts as already taken, and the next real day counts normally again.
  4. Caps: if `wlasne && wlasneDzis >= 10`, paid = 0 with `Limit.WLASNE`; else if `z == ROZPOZNANE && rozpoznaneDzis >= 5`, paid = 0 with `Limit.ROZPOZNANIA`; else paid = `z.xp`.
  5. Counters: `xp += paid`, `xpDzis += paid`; `noweDzis += 1` for `NOWE` and `ROZPOZNANE`; when paid > 0: `rozpoznaneDzis += 1` for `ROZPOZNANE`, `wlasneDzis += 1` when `wlasne`, `powtorki += 1` for `AWANS`, `UTRWALENIE`, `POWTORKA`; `naprawione += 1` when `naprawa`.
  6. Daily goal check (C2).
- `Nagrody.etap(xp)`, `nastepny(xp): Etap?`, `doNastepnego(xp)`, `okrazenie(xp)` (0 below the top stage, else `1 + (xp - 15000) / 2500`), `pasekEtapu(xp): Pair<Int, Int>` (done and span inside the current stage, or inside the current lap of 2500).
- `Nagrody.punktyNaStart(stan: WordProgress): Int` for the migration: box 1 gives 10 (3 when `lastTask == ROZPOZNANIE`), box 2 gives 30, box 3 gives 60.

**Hook.** `Repository.recordAttempt(attempt, nowMs, sladWSesji, naWplyw: ((Wplyw) -> Unit)? = null)`. After the existing "nothing changed, skip the write" guard (`Repository.kt:176`): take `przed` and `po` of the attempt's key, compute the credit, set `wlasne = attempt.entryId !in idKatalogu` and `naprawa = credit == UTRWALENIE && przed.mistakes > 0 && !wlasne`, call `dolicz`, then `Odznaki.odblokuj`, and write progress, session trace and ledger in the same `save`. `naWplyw` is called only after `save` returned true, also for a capped credit (xp 0). The guard additionally ignores `szczyt` when comparing bookkeeping fields. `idKatalogu` and `katalog` are `by lazy` values built once from `SampleData.builtInSets()` (the catalogue bundled in the app, not the student's editable copies).

**Strings.** In the ZASADY card (C6) and the reward block (C5).

**JVM tests.** `SrsTest`: `szczyt` invariants under random attempt sequences. `NagrodyTest`: every row of the table; all zero cases; climb 0 to 3 pays exactly 60 over three days; re-climb pays 5 + 5; box-3 confirmation pays 5; recognition on a due box-1 word pays 0 although `dueDay` moved; sixth recognition of a day pays 0 and the next day pays again; eleventh own-set credit of a day pays 0 while catalogue words still pay; property test: random sequences give at most one paying event per key per day and `xp` never decreases; stage boundaries (29 is stage 1, 30 is stage 2, 14999 is 12, 15000 is 13; laps: 17499 is lap 1, 17500 is lap 2); thresholds strictly increasing and starting at 0. `DorobekZapisTest`: the credit lands in the same save as the progress; a failed save adds no XP and does not call `naWplyw`; a duplicate attempt id pays nothing; an attempt that changes nothing writes nothing; the ledger survives a new `Repository` instance. `PacingTest` (the frozen numbers): a seeded 167-day run through the real `Srs.applyTo`, `Sesja.zaplanuj` and `Nagrody` for three budgets asserts: session-only reaches stage 2 on day 1 and stage 6 by day 25, never waits more than 45 days for a stage, never reaches stage 13, and meets the 30 XP goal on at least 160 days; 25 answers a day reaches stage 13 between day 50 and day 100.

### C2. Daily goal and day streak with days off (S on top of C1, medium risk)

**Rules.**
- Goal: `Options.celDnia` in XP: 30 (default), 60 or 120. Any other stored value reads as 30 (`Nagrody.celDnia(zapisany)`). 30 XP is three new words from memory or six on-time reviews. Recognition can give at most 15 XP a day, so the goal always needs some typed or spoken recall.
- The day is `Srs.dayOf` (UTC), the same day the scheduler uses: in Poland it changes at 1:00, in summer at 2:00.
- A day D is counted once, inside `dolicz`, when `xpDzis >= cel` and `seriaDzien != D`. Raising the goal later does not uncount it. Lowering the goal takes effect at the next XP event (engineer judge's rule: `Repository.setOptions` needs no clock).
- On counting day D: `dniNauki += 1`. If `seriaDzien == NO_DAY`: `seria = 1`. Else `opuszczone = D.toLong() - seriaDzien - 1` (never negative after re-anchoring): 0 gives `seria + 1`; `1..wolne` gives `wolne -= opuszczone`, `seria + 1`; more gives `seria = 1`. Then, if `seria % 7 == 0`, `wolne = min(2, wolne + 1)`. Then `rekordSerii = max(rekordSerii, seria)`, `seriaDzien = D`.
- Views, no state change: `xpNaDzis(d, dzien)` (0 when the ledger remembers another day); `celZaliczony(d, dzien)` is `seriaDzien == dzien`; `seriaNaDzis(d, dzien)`: 0 when never counted, else with `przerwa = dzien.toLong() - seriaDzien - 1`: `seria` while `przerwa <= wolne`, else 0; `seriaCzeka(d, dzien)`: `przerwa in 1..wolne`; `pierscien(xpDzis, cel, zaliczony)`: 4 when counted, else `(xpDzis * 4L / cel)` clamped to 0..3 (no forced first quarter).
- A broken streak changes nothing in boxes, XP, `dniNauki`, `rekordSerii` or badges. No notification, no warning, no repair, no purchase. Streak badges stop at 30.
- Known limit, accepted: a deliberately toggled clock can count one calendar day twice. The alternative (a high-water mark) freezes honest users after one wrong clock setting; nothing here is ranked or shared automatically.
- Simulated behaviour: daily study gives an unbroken streak; six days out of seven keeps a streak for about seven weeks; every other day never gets past 2, so "SERIA 3" cannot be earned that way.

**UI.** Card (C4), reward block (C5), profile stats (C6). Options: one row "Cel dnia" with three `ChoiceChip`s (existing 48 dp chips) and one `InfoCard`. The reserve of days off is shown on the profile and in the reward block when it changes, not on the menu card (the card stays at four numbers; the teacher judge asked for no second number to guard).

**Strings.**

| Where | Font | PL | EN |
|---|---|---|---|
| card, goal open | P | `DZIŚ: 20 / 30 XP` | `TODAY: 20 / 30 XP` |
| card, goal counted (plus `checkmark`) | P | `DZIŚ: 40 XP` | `TODAY: 40 XP` |
| card and block | P | `SERIA DNI: 6` | `DAY STREAK: 6` |
| card, one day bridged | S | Wczoraj było wolne. Seria czeka. | Yesterday was a day off. Your streak is waiting. |
| card, two days bridged | S | Dwa dni wolnego. Seria czeka. | Two days off. Your streak is waiting. |
| block | P | `CEL DNIA ZALICZONY` | `DAILY GOAL DONE` |
| block | S | Cel dnia zaliczony: 40 z 30 XP. Reszta to bonus. | Daily goal done: 40 of 30 XP. The rest is a bonus. |
| block | S | Do celu dnia brakuje 10 XP. | 10 XP to go for today's goal. |
| block, first day ever | S | Pierwszy dzień zaliczony. | First day done. |
| block, chain continues | S | Seria dni: 4. | Day streak: 4. |
| block, day off spent | S | Dzień wolny wykorzystany. Seria trwa: 5. | A day off was used. The streak goes on: 5. |
| block, new chain | S | Nowa seria, dzień 1. Dni nauki razem: 31. | New streak, day 1. Study days in total: 31. |
| block, day off earned | P | `+1 DZIEŃ WOLNY` | `+1 DAY OFF` |
| options label | S | Cel dnia | Daily goal |
| options chips | P | `30 XP`, `60 XP`, `120 XP` | same |
| options note | S | 30 XP to mniej więcej trzy nowe słowa z pamięci albo sześć powtórek w terminie. Nowy cel liczy się od najbliższego zaliczonego słowa. Dzień już zaliczony zostaje zaliczony. | 30 XP is roughly three new words from memory or six on-time reviews. A new goal applies from the next word you pass. A day already counted stays counted. |

**JVM tests** (`NagrodyTest`): crossing 30 counts the day once; a second crossing the same day is a no-op; three new words typed in a first session meet the default goal (the student judge's test); five recognitions alone (15 XP) never meet it; goal raised after counting does not uncount; goal lowered counts at the next credit; consecutive days; gap 1 with reserve 1; gap 1 with reserve 0 restarts; gap 2 with reserve 2; gap 3 restarts; reserve +1 at 7 and 14, capped at 2; every-other-day pattern never reaches 3; `seriaNaDzis` alive and zero; `NO_DAY` without overflow; clock moved back re-anchors, increments nothing, and the next real day counts; a phone set to 2030 and back counts again the next real day; 23:59:59.999 and 00:00:00.000 UTC are different days; random sequences keep `seria <= dniNauki`, `rekordSerii >= seria`, `0 <= wolne <= 2`, `xpDzis <= xp`; `pierscien` is 0 for 0, at most 3 while not counted, 4 only when counted.

### C3. Matura countdown (S, low risk)

**Rules.** `d = Options.maturaDzien - dzienKalendarza`, where `dzienKalendarza = Kalendarz.dzienLokalny(nowMs, TimeZone.getDefault().getOffset(nowMs))` is the local calendar day (display only; it must match the wall calendar, also between midnight and 1:00). The goal and the streak stay on `Srs.dayOf`; the two days are never mixed. `Nagrody.odliczanie(maturaDzien, dzienKalendarza, wlaczone): Odliczanie?`: null when switched off or `d < 0`; `DZIS` for 0; `JUTRO` for 1; `STO` for 100; `DNI(d)` otherwise. Default 20944 (6 May 2027). Neutral colour (`Palette.Bone`), never red, no "tylko".

**Persisted.** `Options.maturaDzien`, `Options.odliczanie`.

**Pure functions** (`learn/Kalendarz.kt`, hand-written because minSdk 24 has no `java.time`): `dzienEpoki(rok, miesiac, dzien)`, `data(dzienEpoki)`, `dniWMiesiacu`, `popraw` (30 February becomes the last day of February), `dzienLokalny(nowMs, przesuniecieMs)`.

**UI.** First line of the player card with `icon_clock` (C4). Options, as an internal composable `OpcjeNauki(options, t, onChange)` so it can be previewed alone: `Toggle` "Odliczanie do matury"; "Data matury" with three `OptionRow`s using the existing 48 dp `Stepper` (day 1..days in month, month 1..12, year 2026..2032, result passed through `popraw`), the date shown as P `06.05.2027`; one `InfoCard`. Last and cuttable: one line under the tagline on the Start screen (`PixelText`, `Palette.Muted`, 8 dp), respecting the switch. The student judge asked for it, the teacher judge asked to keep it off the Start screen: it is one line and one parameter, and it is first on the cut list.

**Strings.**

| Where | Font | PL | EN |
|---|---|---|---|
| d of 2 or more | P | `DO MATURY: 167 DNI` | `MATURA IN 167 DAYS` |
| d = 100 | P | `STO DNI DO MATURY` | `100 DAYS TO MATURA` |
| d = 1 | P | `MATURA JUTRO` | `MATURA TOMORROW` |
| d = 0 | P | `MATURA DZIŚ. POWODZENIA!` | `MATURA TODAY. GOOD LUCK!` |
| options | S | Odliczanie do matury; Data matury; Dzień; Miesiąc; Rok | Exam countdown; Exam date; Day; Month; Year |
| options note | S | Domyślnie: pisemny angielski na poziomie podstawowym, 6 maja 2027. Zdajesz w innym terminie? Ustaw swoją datę. | Default: the written basic-level English exam, 6 May 2027. Sitting it on another day? Set your own date. |
| date in the past | S | Ta data już minęła. Ustaw nową, jeśli chcesz dalej odliczać. | That date has passed. Set a new one to keep counting. |

**JVM tests.** `KalendarzTest`: epoch day and date against `java.time` for sampled days 1970 to 2100; 20944 is 2027-05-06; 20777 gives 167; leap day; clamping; local day with offsets 3 600 000 and 7 200 000 at 00:30 local time on 28 March 2027 and 31 October 2027; all five countdown states plus switched off.

### C4. Player card and a live menu (M, low to medium risk)

**Rules.** Everything on the card is derived by one pure function: `Nagrody.stanKarty(dorobek, options, dzisiaj, dzienKalendarza): StanKarty` with `etap`, `numerEtapu`, `okrazenie`, `xp`, `pasek: Pair<Int, Int>`, `xpDzis`, `cel`, `celZaliczony`, `pierscien`, `seria`, `seriaCzeka: Int` (0, 1 or 2 bridged days), `odliczanie: Odliczanie?`. No new stored data.

**Card** (`KartaGracza`, replaces the name line at `Screens.kt:152`; the name stays above it). `Palette.Surface`, 3 dp border (`Palette.Gold` once today is counted, else `SurfaceHigh`), full width, one click target that opens OSIĄGNIĘCIA. Six stacked lines, so that it fits 320 dp at every density (8 dp glyphs can be 8 dp wide on hdpi):
1. `icon_clock` 14 dp + countdown (P, 8 dp, Bone); omitted when hidden or past.
2. `ETAP 4 Z 13` (P, 8 dp, Muted).
3. `streak_arrow` 18 dp + stage title (P, 10 dp, Bone); on laps `WAKACJE ×2`.
4. `PasekXp` (plain Compose boxes like the existing four-segment bar: 10 dp high, 2 dp Ink border, Cyan fill) + `640 / 1200 XP` (P, 8 dp, Cyan). On the top stage the text is `15400 XP` and the bar shows the lap.
5. ring sprite 18 dp (`ring_00` to `ring_04` by `pierscien`) + goal line; `checkmark` 14 dp when counted.
6. `combo_flame` 18 dp (alpha 0.45 until today is counted) + `SERIA DNI: 6`; under it the S line when a day is being bridged.

XP is always Cyan with `streak_arrow`; game points stay Gold with the coin. All inner `PixelText`s get `opis = null`; the card carries one merged description built by pure `Nagrody.opisKarty`, for example "Etap 4 z 13: Próbna matura. 640 z 1200 XP. Dziś 20 z 30 XP. Seria dni: 6. Do matury 167 dni. Otwórz osiągnięcia." (EN: "Stage 4 of 13: Mock exam. 640 of 1200 XP. Today 20 of 30 XP. Day streak: 6. 167 days to the matura. Open achievements.").

**Live subtitles** (`learn/Podpisy.kt`, pure; remembered on `data.progress`, ids, direction, `dzisiaj`):
- `Podpisy.sesji(plan: PlanSesji, saNieznane: Boolean, naJutro: Int, t): String` from `Sesja.zaplanuj`. `Podpisy.naJutro(progress, ids, kierunek, dzien)`: words with history whose `dueDay <= dzien + 1`.
- `Podpisy.trudnych(n, t)`: `n = Trudne.zbierz(...).size`, the same list and the same label as the TRUDNE SŁOWA screen (`str.hardWordsCount`), so the menu and the screen never disagree; for 0 the existing description stays.
- OSIĄGNIĘCIA button: `trailing = "NOWE: 2"` while `odznakiNowe` is not empty.
- One stateless info card under the player card for players coming from 0.9.18: shown while `dorobek.dzien == NO_DAY && (profile.gamesPlayed > 0 || progress.isNotEmpty())`; it disappears by itself after the first XP event. No extra field.

**Strings.**

| Where | Font | PL | EN |
|---|---|---|---|
| card | P | `ETAP 4 Z 13` | `STAGE 4 OF 13` |
| card | P | `640 / 1200 XP` | same |
| card, laps | P | `WAKACJE ×2` | `SUMMER BREAK ×2` |
| daily, reviews and new | S | Na dziś: 8 powtórek i 5 nowych słów, ok. 5 min | Today: 8 reviews and 5 new words, about 5 min |
| daily, reviews only | S | Na dziś: 12 powtórek, ok. 5 min | Today: 12 reviews, about 5 min |
| daily, new only | S | Na dziś: 5 nowych słów, ok. 2 min | Today: 5 new words, about 2 min |
| appended when 12 reviews, 0 new and unseen words exist | S | Sesja jest pełna powtórek. Po nowe słowa zajrzyj do GRAJ. | The session is full of reviews. For new words go to PLAY. |
| daily, nothing due | S | Na dziś nic nie czeka. | Nothing is due today. |
| appended when tomorrow has reviews | S | Na jutro: 6 powtórek. | For tomorrow: 6 reviews. |
| hard words | S | Słowa do powtórki: 23 | Words to review: 23 |
| achievements button | P | `NOWE: 2` | `NEW: 2` |
| info card | S | Nowe w tej wersji: punkty nauki (XP), etapy, cel dnia, seria dni i odznaki za naukę. Dotychczasowa nauka jest już policzona: 240 XP. | New in this version: study points (XP), stages, a daily goal, a day streak and badges for learning. What you have learned so far is already counted: 240 XP. |

Plural sets through `Podsumowanie.forma`: powtórka / powtórki / powtórek; nowe słowo / nowe słowa / nowych słów. Minutes: `ceil(seconds / 60)`, at least 1.

**JVM tests.** `MenuPodpisyTest` (JUnit): all subtitle cases and plural forms. `KartaGraczaTest` (Robolectric): one clickable node, at least 48 dp, with the merged description; states: fresh install, goal counted, day bridged, countdown hidden, top stage with lap. Update `A11ySemanticsTest`, `TouchTargetAuditTest` (MENU limit stays 0) and `PodgladEkranowTest` (`menu-start`, `menu-dzien7`) for the new `MenuScreen` signature.

### C5. Reward moment in the result slot (M, medium risk)

**Rules and data flow** (`App.kt`, Play branch, wrapped in `key(s, nrRundy)`):
- `poczatek = remember { mutableStateOf(repo.load()) }` (baseline), `proby = remember { ArrayList<Attempt>() }`, `wplywy = remember { ArrayList<Wplyw>() }` (plain lists on purpose: no recomposition during a round), `nagroda` as state.
- `zapiszProbe` appends the attempt and passes `naWplyw = { wplywy += it }`.
- `finish(record)`: `doRankingu = planSesji == null && !rundaSpokojna`; `repo.recordGame(record, set.id, now, doRankingu, potwierdzOdznaki = true)`; `po = repo.load()`; `nagroda = Nagrody.roznica(poczatek.value, po, wplywy.toList(), if (doRankingu) record else null, Srs.dayOf(now))`; then move the baseline (`poczatek.value = po`, clear both lists) so JESZCZE RAZ diffs against the right start; then `refresh()` and the day refresh of C0.1. No refresh during the round, as today.
- `Repository.recordGame(record, setId, nowMs, doRankingu: Boolean = true, potwierdzOdznaki: Boolean = false)`: when `doRankingu` is false, `gamesPlayed`, `totalKills` and `bestStreak` still move, but `scores` and `bestScore` do not: calm rounds (daily session, hard words, fix round, NOWE SŁOWA) neither enter the leaderboard nor set a record. Game badges are unlocked in the same update; with `potwierdzOdznaki` the set `odznakiNowe` is cleared in the same save.
- `Nagrody.roznica(przed: SaveData, po: SaveData, wplywy: List<Wplyw>, wynik: ScoreRecord?, dzien: Int): NagrodaRundy` is a pure diff. `xp` is the ledger difference (never negative). The breakdown comes only from the reported `wplywy` (count and paid XP per kind, caps that applied); nothing is replayed through `Srs`. Goal: `xpDzisPo`, `cel`, `celZaliczonyTeraz`, `celZaliczony`. Streak, all derived from the two ledgers: `pierwszyDzien`, `nowaSeria`, `wolneUzyte`, `wolneDodane`, `seriaPo`, `dniNauki`. Stage: `etapPrzed`, `etapPo`, laps. Badges: every `Odznaka` whose name is in `po.odznaki.keys - przed.odznaki.keys` or in `przed.odznakiNowe`, in enum order. Record: `wynik != null`, and the best earlier score of the same mode in `przed.scores` exists, is above 0 and is beaten. The first score in a mode is not a record. No set record (a `ScoreRecord` has no set id). `noweDzisPrzekroczone`: `przed.noweDzis < 30 <= po.noweDzis` on the same day.
- `Nagrody.wiersze(n: NagrodaRundy, nauka: WynikRundy?, t: TekstyNagrody): List<String>` builds the S sentences (same pattern as `Podsumowanie.linie`), so JUnit checks that sentences repeat the numbers.
- An exit in the middle of a round shows no moment and loses nothing: credits were saved per answer, and unseen badges wait in `odznakiNowe`.

**Composition of the slot** (App fills `stopka`; all static):
1. `RekordBlok`, only on a record: 2 dp Gold border, `crown` 22 dp, P `NOWY REKORD`, S line with the previous best.
2. `NagrodaRundyBlok` (Surface, 2 dp border), header P `NAUKA`:
   - `streak_arrow` + P `+60 XP` (18 dp, Cyan), then one S line per kind present; for 0 XP the S sentence instead of "+0";
   - explanatory S lines (table below), at most three;
   - ring + goal line; when open: "Do celu dnia brakuje 10 XP." and a `GhostButton` `NOWE SŁOWA`;
   - `combo_flame` + `SERIA DNI: 4` and at most one factual S line when something happened;
   - on a stage change: the `banner_levelup` sprite at 4:1 with P `NOWY ETAP` centred on it, the title below (P, 13 dp, Gold); otherwise `ETAP 6 Z 13`, the bar and the XP numbers, small;
   - P `NOWA ODZNAKA` or `NOWE ODZNAKI: 3`, one row per badge (medal sprite, P title, S description), at most three rows, then the S line "Więcej w OSIĄGNIĘCIACH: 2.".
3. `PodsumowanieRundy`, component unchanged, header P `SŁOWA Z TEJ RUNDY`. Its fix block receives ready strings "ache = ból" built by new pure `Podsumowanie.paryDoPoprawki(ids: List<String>, proby: List<Attempt>, wpisy: List<Entry>): List<String>` (question and answer in the direction of the attempt), header P `UCIEKŁY CI`, buttons `POPRAW` and `POMIŃ`. This works in all four modes and is the place where a missed word shows its answer after the round. If task #4 also lists `snap.missedWords` on the CLASSIC result, show one list only (this one, because it has the POPRAW button).
4. `GhostButton` P `UDOSTĘPNIJ`, full width, only for ranked rounds.

A cue, in CORE: when the block first appears with a new stage or a new badge, one short tone `Sfx.play(FlashKind.CRATE, options.soundEnabled)`.

**NOWE SŁOWA** (last core item, first to cut after the Start-screen line): `Podpisy.noweSlowa(katalog: List<WordSet>, progress, kierunek, ile = 5): List<Entry>` returns the first five catalogue entries, in catalogue order, with no progress key in that direction (the same order the daily session uses). Handler: `mixSet = randomMix().copy(entries = ...)`, `rundaSpokojna = true`, `planSesji = null`, `nrRundy++`, `stack = listOf(Screen.Menu, Screen.Play(BuiltInSets.MIX_ID, GameMode.CLASSIC))`. The `key(s, nrRundy)` wrapper is what forces a fresh round when the screen value is unchanged.

**Share.** `Udostepnianie.wynik(tryb, zestaw, punkty, trafienia, seria, rekord, odliczanie, t): String` is pure. The click sends `Intent.createChooser(Intent(ACTION_SEND).setType("text/plain").putExtra(EXTRA_TEXT, tekst), null)` inside `runCatching`, as `PrivacyScreen` does. No player name, no file, no manifest change.

```
Matura Angielski: gra w słówka
KLASYCZNY, Zdrowie · U lekarza
1240 pkt (nowy rekord), 31 trafień, seria 14
Do matury 167 dni. Pobijesz?
https://play.google.com/store/apps/details?id=com.matura.app
```

EN: "Matura Angielski: a vocabulary game / CLASSIC, Zdrowie · U lekarza / 1240 pts (new record), 31 hits, streak 14 / 167 days to the matura. Can you beat it? / link". "(nowy rekord)" only on a record. Fourth line: "Matura jutro. Pobijesz?" for 1, "Matura dziś. Pobijesz?" for 0, just "Pobijesz?" when the countdown is hidden or past. Plural: trafienie / trafienia / trafień.

**Strings.**

| Where | Font | PL | EN |
|---|---|---|---|
| headers | P | `NAUKA`, `SŁOWA Z TEJ RUNDY`, `UCIEKŁY CI` | `STUDY`, `WORDS FROM THIS ROUND`, `GOT AWAY` |
| XP | P | `+60 XP` | same |
| breakdown | S | Nowe z pamięci: 3 (+30 XP) | New from memory: 3 (+30 XP) |
| breakdown | S | Pierwsza powtórka po przerwie: 2 (+40 XP) | First review after a break: 2 (+40 XP) |
| breakdown | S | Utrwalone: 1 (+30 XP) | Consolidated: 1 (+30 XP) |
| breakdown | S | Powtórki w terminie: 4 (+20 XP) | On-time reviews: 4 (+20 XP) |
| breakdown | S | Rozpoznane w trybach wyboru: 3 (+9 XP) | Recognised in choice modes: 3 (+9 XP) |
| 0 XP, nothing checked | S | Ta runda nie sprawdziła żadnego słowa, więc XP stoi w miejscu. | This round checked no words, so XP did not move. |
| 0 XP, correct answers | S | Bez XP w tej rundzie: te słowa masz już dziś zaliczone albo ich termin jeszcze nie minął. Wynik gry liczy się normalnie. | No XP this round: these words are already passed today or not due yet. Your game score counts as usual. |
| any word needed help | S | Słowa z pomyłką albo pokazaną odpowiedzią zaliczysz najwcześniej jutro. | Words you missed or were shown can be passed tomorrow at the earliest. |
| choice round, known words | S | W trybach wyboru XP daje tylko pierwsze rozpoznanie nowego słowa. Wpisz te słowa jutro w KLASYCZNYM albo NA CZAS: wtedy liczą się wyżej. | In choice modes only the first recognition of a new word gives XP. Type these words tomorrow in CLASSIC or TIME ATTACK: that is when they count for more. |
| recognition cap applied | S | Tryby wyboru dają XP za 5 nowych słów dziennie. Kolejne ćwiczą się normalnie. | Choice modes pay XP for 5 new words a day. The rest still count as practice. |
| own-set cap applied | S | Własne zestawy dają XP za 10 słów dziennie. Kolejne ćwiczą się normalnie. | Your own sets pay XP for 10 words a day. The rest still count as practice. |
| fix block present | S | Poprawka nie daje XP. Daje to, że jutro te słowa zaliczysz. | Fixing gives no XP. It gets you ready to pass these words tomorrow. |
| 30 new words crossed today (once) | S | Dziś doszło już 30 nowych słów. Wszystkie wrócą jutro, więc reszta może poczekać. | 30 new words today already. They all come back tomorrow, so the rest can wait. |
| stage | P | `NOWY ETAP` | `NEW STAGE` |
| badges | P | `NOWA ODZNAKA`, `NOWE ODZNAKI: 3` | `NEW BADGE`, `NEW BADGES: 3` |
| record | P | `NOWY REKORD` | `NEW RECORD` |
| record | S | Poprzedni najlepszy wynik w tym trybie: 580 pkt. | Previous best in this mode: 580 pts. |
| buttons | P | `UDOSTĘPNIJ`, `NOWE SŁOWA`, `DALEJ`, `POPRAW`, `POMIŃ` | `SHARE`, `NEW WORDS`, `NEXT`, `FIX`, `SKIP` |
| under NOWE SŁOWA | S | 5 nowych słów, spokojna runda. | 5 new words, a calm round. |

"Tryby wyboru" means WIELOKROTNY WYBÓR and PRAWDA / FAŁSZ; the ZASADY card names them once.

**JVM tests.** `NagrodyTest`: `roznica` for a first round, each zero-XP reason, a stage change, a record (not on a first score, never for a calm round), badges as "new in the round plus pending unseen", and a second diff after the baseline moved being empty; `wiersze` repeating the numbers with correct plural forms. `UdostepnianieTest`: every line, no player name, countdown variants, both languages, the link. `DorobekZapisTest`: `recordGame(doRankingu = false)` leaves `scores` and `bestScore`; `potwierdzOdznaki` clears. Robolectric `NagrodaRundyBlokTest`: zero-XP sentence present; breakdown; new stage; badge rows; record; share click starts a chooser whose inner intent carries the text; NOWE SŁOWA visible only while the goal is open. Previews of the block alone in three states.

### C6. Badges with visible progress; OSIĄGNIĘCIA becomes the profile (M, low to medium risk)

**Rules.**
- `Odznaki.liczniki(data: SaveData, katalog: List<WordSet>): Liczniki`. All word counts iterate the distinct non-blank ids of the bundled catalogue (2018 today), never the progress map (it keeps old keys after catalogue corrections), never own sets, never rows added to a saved copy of a built-in set. Per id: `sT` and `sD` are `maxOf(szczyt, box)` in the two directions; `hi = max`, `lo = min`.
  - `poznane`: `hi >= 1`. `utrwalone`: `hi == 3`. `obieStrony`: `lo >= 2`.
  - `dzialy`: catalogue topics with at least 10 ids at `hi >= 2` (recall after a break, never recognition).
  - `zestawy`: non-empty catalogue sets whose every id has `hi == 3`.
  - `powtorki`, `naprawione`, `dniNauki`, `rekordSerii` from the ledger; `tryby`: distinct modes in `scores`; four profile counters.
- Because the counters use `szczyt` or only ever grow, no bar moves backwards after a mistake.
- `Odznaki.odblokuj(d, l, dzien)` adds every newly satisfied badge to `odznaki` (with the day) and to `odznakiNowe`; it never removes anything and leaves unknown names alone. It runs inside the save of every credit and of `recordGame`; it is skipped when everything is earned. `Odznaki.potwierdz(d)` clears `odznakiNowe`.
- Seen-tracking: the result block shows and confirms (C5). Otherwise the menu button shows `NOWE: n` and opening the profile confirms (`repo.potwierdzOdznaki()`, one write, only when the set is not empty; the screen keeps the names captured at entry so the `NOWA` pills stay for that visit).
- Enum constant names are part of the save from this release on; a test freezes the list. The eight legacy `Achievement` constants stay untouched and reappear under the same names in `Odznaka`; a test checks both agree on every profile.
- The full list is in section 3.3. CORE ships 21 new ids (five learning and habit families plus CZTERY TRYBY) and the 8 legacy ones; four more families are STRETCH S2, but their counters (`naprawione`, `obieStrony`, `dzialy`, `zestawy`) are computed from day one.

**Screen** (`AchievementsScreen`, a `LazyColumn`; menu label stays OSIĄGNIĘCIA):
1. `KartaGracza` (not clickable here) and the S line "Następny etap: POLONEZ, brakuje 240 XP." or "Najwyższy etap. Kolejne okrążenie co 2500 XP.".
2. Stats (`StatRow`): Punkty nauki (XP); Dni nauki; Seria dni; Najdłuższa seria dni; Dni wolne w zapasie; Słowa poznane (`412 / 2018`); Utrwalone choć raz; Powtórki w terminie. Then the four game stats, with "Najdłuższa seria w grze" on this screen so it is not confused with the day streak.
3. P `SŁOWNIK`: the four-segment bar over all catalogue ids from the existing `Podsumowanie.dzialu` (current, due-aware state) with its four counts. `PostepDzialuBlok` (`PodsumowanieRundy.kt:120`) becomes `internal` and its bar is extracted as `PasekDzialu`.
4. P `ODZNAKI`, grouped NAUKA, REGULARNOŚĆ, (DZIAŁY in S2), GRA. One row per family: icon = medal of the highest earned tier (`medal_bronze`, `medal_silver`, `medal_gold`, `crown` for tiers 1 to 4), or `ring_00` to `ring_03` by progress toward tier 1 when none is earned (no padlock); P title of the highest earned tier (first tier's title, dimmed, when none); S family description; P `63 / 200` toward the next tier with `PasekXp`; P `ODZNAKA 2 Z 4`; a `Pill` P `NOWA` when a tier of the family is unseen. One merged TalkBack description per row, for example "Słowa utrwalone, odznaka 2 z 4: 63 z 200.".
5. P `ETAPY`: 13 rows with `checkmark` (passed), `icon_target` (current) or `lock` (ahead), the title and the threshold. Titles are visible in advance.
6. One collapsible card P `ZASADY` (a 48 dp header row toggling an `InfoCard`), text below.

**Strings.**

| Where | Font | PL | EN |
|---|---|---|---|
| sections | P | `SŁOWNIK`, `ODZNAKI`, `ETAPY`, `ZASADY` | `DICTIONARY`, `BADGES`, `STAGES`, `RULES` |
| groups | P | `NAUKA`, `REGULARNOŚĆ`, `DZIAŁY`, `GRA` | `LEARNING`, `HABIT`, `TOPICS`, `GAME` |
| tier | P | `ODZNAKA 2 Z 4` | `BADGE 2 OF 4` |
| pill | P | `NOWA` | `NEW` |
| next stage | S | Następny etap: POLONEZ, brakuje 240 XP. | Next stage: POLONAISE, 240 XP to go. |
| top stage | S | Najwyższy etap. Kolejne okrążenie co 2500 XP. | Top stage. A new lap every 2500 XP. |
| stats | S | Punkty nauki (XP); Dni nauki; Seria dni; Najdłuższa seria dni; Dni wolne w zapasie; Słowa poznane; Utrwalone choć raz; Powtórki w terminie; Najdłuższa seria w grze | Study points (XP); Study days; Day streak; Longest day streak; Days off in reserve; Words met; Consolidated at least once; On-time reviews; Best in-game streak |

ZASADY, PL (S): "XP jest tylko za naukę. Słowo wpisane albo powiedziane z pamięci: pierwsze zaliczenie 10 XP, pierwsza powtórka po przerwie (co najmniej dzień później) 20 XP, pierwsze utrwalenie (kolejna powtórka co najmniej 3 dni później) 30 XP, każda następna powtórka w terminie 5 XP. Rozpoznanie nowego słowa w trybach wyboru (WIELOKROTNY WYBÓR, PRAWDA / FAŁSZ): 3 XP, najwyżej 5 słów dziennie. Własne zestawy: XP za najwyżej 10 słów dziennie. To samo słowo daje XP raz dziennie, osobno w każdym kierunku tłumaczenia, i tylko wtedy, gdy czekało na sprawdzenie, a tego dnia nie było przy nim pomyłki ani pokazanej odpowiedzi. Punkty z gry to osobna liczba: liczą też szybkość i celność, więc XP nie dają. Etap pokazuje włożoną pracę. Nie jest prognozą wyniku matury.

Seria dni liczy dni z zaliczonym celem dnia. Dzień przerwy niczego w nauce nie kasuje: słowa po prostu czekają. Opuszczony dzień pokrywa dzień wolny z zapasu: zaczynasz z jednym, za każde 7 dni serii dochodzi kolejny (najwyżej 2), a zużywa się sam. Doba w grze zmienia się o 1:00 w nocy czasu polskiego (latem o 2:00). Według tej samej doby wracają powtórki.

Postęp jest zapisany tylko na tym telefonie. Po odinstalowaniu gry albo zmianie telefonu zaczyna się od zera."

ZASADY, EN (S): "XP comes from learning only. A word typed or spoken from memory: first pass 10 XP, first review after a break (at least a day later) 20 XP, first consolidation (another review at least 3 days later) 30 XP, every later on-time review 5 XP. Recognising a new word in a choice mode (MULTIPLE CHOICE, TRUE / FALSE): 3 XP, for at most 5 words a day. Your own sets: XP for at most 10 words a day. A word pays once a day, separately for each translation direction, and only when it was due and you needed no help with it that day. Game points are a separate number: they also reward speed and aim, so they give no XP. Your stage shows the work you have put in. It is not a forecast of your exam result.

The day streak counts days with the daily goal met. A missed day erases nothing you have learned: the words simply wait. A missed day is covered by a day off from your reserve: you start with one, earn another for every 7 streak days (2 at most), and it is spent automatically. The game day changes at 1:00 a.m. Polish time (2:00 in summer). Reviews come back by the same clock.

Progress is stored on this phone only. Uninstalling the game or changing phones starts it from zero."

**JVM tests.** `OdznakiTest` (JUnit, three-set fixture catalogue built with `Srs.applyTo`): an id shared by two sets counts once; own-set progress, orphaned keys and rows added to a saved built-in copy are ignored; every family counter; tier maths and `postep`; an unlock survives a later mistake; unlock fills `odznakiNowe`, `potwierdz` clears it; legacy families agree with `Achievement.unlockedBy`; the frozen list of names; no blank text; titles at most 24 characters. Robolectric: the screen in `A11ySemanticsTest` and `TouchTargetAuditTest` (every row labelled, the ZASADY header at least 48 dp), preview `osiagniecia` with a mid-game fixture.

### C7. Topic and set progress in the set picker (S, low risk)

**Rules.** `Podsumowanie.dzialu(progress, ids, options.direction, dzisiaj)`, already written and tested, per topic and per set of the open folder, remembered on (sets, progress, direction, day). Nothing is stored. Four counts and their shape, no percentage.

**UI.** `SetPickScreen` gets `progress`, `kierunek`, `dzis`. `MenuButton` (`Common.kt:79`) gains optional `postep: PostepDzialu?` and `podpisPostepu: String?`: the thin `PasekDzialu` and an S caption under the subtitle. Set rows: the leading icon, today always `icon_star`, becomes `ring_00` to `ring_03` by `4 * utrwaloneChocRaz / size` (count of ids with `hi == 3`), and `icon_star` only when every word of the set got there. No checkmark for a set that was merely seen. The caption carries what the colours and the ring carry.

**Strings.** S: "Utrwalone 12, w nauce 30, do powtórki 5, nowe 318" / "Consolidated 12, learning 30, due 5, new 318".

**JVM tests.** JUnit for the ring index (0 for none, 3 at most while incomplete, star only when all). Preview `wybor-dzialu` and `wybor-zestawu` with a mid-game fixture; a11y gate for the screen.

### C8. Texts that must ship with it (S)

- Privacy text in `ui/Strings.kt` and `docs/prywatnosc.html`, new date. Add to the stored-data list, PL: "• punkty nauki (XP), cel dnia, serię dni, liczbę dni nauki i zdobyte odznaki (same liczby i nazwy odznak, bez listy odpowiedzi)," and "• datę matury ustawioną w opcjach,". EN: "• study points (XP), the daily goal, the day streak, the number of study days and earned badges (numbers and badge names only, no list of answers)," and "• the exam date set in options,". New paragraph, PL: "UDOSTĘPNIANIE. Przycisk UDOSTĘPNIJ przekazuje wybranej przez Ciebie aplikacji sam tekst wyniku, bez nazwy gracza. Wysyła go tamta aplikacja, nie gra." EN: "SHARING. The SHARE button hands the app you choose the result text only, without your player name. That app sends it, not the game." Extend `PolitykaPrywatnosciTest` for both languages.
- Rewrite the header comment at `learn/Sesja.kt:25-28` (it says day streaks are not counted).
- `CzcionkaPikselowaTest`: every P string of `GryStr` in both languages (with sample numbers), every `Etap` title and every `Odznaka` title and group label contains only `PixelFont.SUPPORTED` characters. Put it in `testDebug` if `PixelFont` does not load in plain JUnit.
- Release notes line for Play Console.

## 3. Tables

### 3.1 XP

| Kind | Transition | Task | XP | Counts as a review | Daily cap |
|---|---|---|---|---|---|
| `NOWE` | box 0 -> 1 | typed or spoken | 10 | no | own sets: 10 paid credits a day in total |
| `AWANS` | box 1 -> 2, above `szczyt` | typed or spoken | 20 | yes | same |
| `UTRWALENIE` | box 2 -> 3, above `szczyt` | typed or spoken | 30 | yes | same |
| `POWTORKA` | box rises to a level already reached, or box 3 stays 3 and the due day moves forward | typed or spoken | 5 | yes | same |
| `ROZPOZNANE` | box 0 -> 1 | choice modes | 3 | no | 5 paid a day |
| none | wrong, hint, shown, interrupted, not due, help that day, second hit that day, duplicate attempt id, recognition on box 1 or higher | any | 0 | no | |

Why these values: the student and teacher judges both asked for escalating premiums (10, 20, 30), the student and engineer judges for 5 XP reviews and 3 XP first recognitions. The engineer judge's own 10 / 10 / 20 was not used: constants are equally safe to build, and the ladder below was re-simulated for this table. Check: re-learning a forgotten word pays 5 per answer, the same as any review; learning a new word pays 20 per answer on average.

### 3.2 Stages (label ETAP; copy on `enum class Etap(prog, pl, en)`, not persisted, free to rename)

| # | XP from | PL | EN | Day reached (simulated: session only / 25 answers a day / 50 a day) |
|---|---|---|---|---|
| 1 | 0 | DEKLARACJA ZŁOŻONA | DECLARATION FILED | 1 / 1 / 1 |
| 2 | 30 | KARTKÓWKA | POP QUIZ | 1 / 1 / 1 |
| 3 | 150 | KLASÓWKA | CLASS TEST | 3 / 1 / 1 |
| 4 | 500 | PRÓBNA MATURA | MOCK EXAM | 5 / 2 / 2 |
| 5 | 1200 | PÓŁROCZE | END OF TERM | 9 / 5 / 3 |
| 6 | 2500 | STUDNIÓWKA | PROM NIGHT | 16 / 7 / 5 |
| 7 | 4000 | POLONEZ | POLONAISE | 30 / 12 / 7 |
| 8 | 5500 | OSTATNI DZWONEK | LAST BELL | 48 / 16 / 9 |
| 9 | 7000 | KASZTANY KWITNĄ | CHESTNUTS IN BLOOM | 69 / 21 / 11 |
| 10 | 8500 | CZARNY DŁUGOPIS | BLACK PEN | 95 / 28 / 13 |
| 11 | 10000 | ARKUSZ OTWARTY | PAPER OPENED | 122 / 36 / 15 |
| 12 | 12000 | KARTA ODPOWIEDZI | ANSWER SHEET | 156 / 48 / 19 |
| 13 | 15000 | WAKACJE | SUMMER BREAK | never / 70 / 25 |

After stage 13 a lap counter grows by one every 2500 XP and is shown as `WAKACJE ×2`, `×3` and so on (simulated: a lap every 3 weeks at 25 answers a day, every 10 days at 50). Simulated totals after 167 days: about 12 600 XP session only (so the top cannot be reached by the 12-word session alone, and the longest wait between two stages is 34 days), 26 400 at 25 a day, 52 400 at 50. These figures assume the daily route is frozen as task #4 intends; if step 2 keeps re-planning new words, a both-steps player behaves like the 25-a-day model. Only milestones, no person titles: POSTRACH ARKUSZY, POSTRACH CKE and ŚWIADECTWO are out.

### 3.3 Badges

Progress formula for every family: `min(counter, target of the next tier)` shown as `counter / target` with a bar; an earned tier never returns to locked. C = CORE, S2 = STRETCH S2.

| Id | PL title | EN title | Unlock condition | Wave |
|---|---|---|---|---|
| SLOWA_1 | PIERWSZE SŁOWO | FIRST WORD | `poznane >= 1` | C |
| SLOWA_25 | 25 SŁÓW | 25 WORDS | `poznane >= 25` | C |
| SLOWA_75 | 75 SŁÓW | 75 WORDS | `poznane >= 75` | C |
| SLOWA_250 | ĆWIERĆ TYSIĄCA | QUARTER THOUSAND | `poznane >= 250` | C |
| UTRWALONE_5 | 5 UTRWALONYCH | 5 CONSOLIDATED | `utrwalone >= 5` | C |
| UTRWALONE_20 | 20 UTRWALONYCH | 20 CONSOLIDATED | `utrwalone >= 20` | C |
| UTRWALONE_60 | 60 UTRWALONYCH | 60 CONSOLIDATED | `utrwalone >= 60` | C |
| UTRWALONE_200 | 200 UTRWALONYCH | 200 CONSOLIDATED | `utrwalone >= 200` | C |
| POWTORKI_10 | W TERMINIE | ON TIME | `powtorki >= 10` | C |
| POWTORKI_100 | STO W TERMINIE | A HUNDRED ON TIME | `powtorki >= 100` | C |
| POWTORKI_500 | PIĘĆSET W TERMINIE | FIVE HUNDRED ON TIME | `powtorki >= 500` | C |
| POWTORKI_1500 | 1500 W TERMINIE | 1500 ON TIME | `powtorki >= 1500` | C |
| DNI_10 | 10 DNI NAUKI | 10 STUDY DAYS | `dniNauki >= 10` | C |
| DNI_25 | 25 DNI NAUKI | 25 STUDY DAYS | `dniNauki >= 25` | C |
| DNI_50 | 50 DNI NAUKI | 50 STUDY DAYS | `dniNauki >= 50` | C |
| DNI_100 | STO DNI NAUKI | A HUNDRED STUDY DAYS | `dniNauki >= 100` | C |
| SERIA_3 | SERIA 3 | STREAK 3 | `rekordSerii >= 3` | C |
| SERIA_7 | SERIA 7 | STREAK 7 | `rekordSerii >= 7` | C |
| SERIA_14 | SERIA 14 | STREAK 14 | `rekordSerii >= 14` | C |
| SERIA_30 | SERIA 30 | STREAK 30 | `rekordSerii >= 30` | C |
| TRYBY_4 | CZTERY TRYBY | ALL FOUR MODES | `tryby >= 4` | C |
| FIRST_BLOOD, SHARPSHOOTER | Pierwszy celny strzał, Strzelec wyborowy | First hit, Sharpshooter | `totalKills >= 1`, `>= 100` | C (legacy) |
| ROOKIE, VETERAN | Rekrut, Weteran | Rookie, Veteran | `gamesPlayed >= 5`, `>= 25` | C (legacy) |
| COMBO_X3, COMBO_X5 | Seria x3, Seria x5 | Combo x3, Combo x5 | `bestStreak >= 6`, `>= 9` | C (legacy) |
| SCORE_500, SCORE_2000 | Pięćset, Dwa tysiące | Five hundred, Two thousand | `bestScore >= 500`, `>= 2000` | C (legacy) |
| ZESTAW_1 | PIERWSZY ZESTAW | FIRST SET | `zestawy >= 1` | S2 |
| ZESTAWY_3 | TRZY ZESTAWY | THREE SETS | `zestawy >= 3` | S2 |
| ZESTAWY_8 | OSIEM ZESTAWÓW | EIGHT SETS | `zestawy >= 8` | S2 |
| ZESTAWY_15 | PIĘTNAŚCIE ZESTAWÓW | FIFTEEN SETS | `zestawy >= 15` | S2 |
| DZIAL_1 | PIERWSZY DZIAŁ | FIRST TOPIC | `dzialy >= 1` | S2 |
| DZIALY_5 | PIĘĆ DZIAŁÓW | FIVE TOPICS | `dzialy >= 5` | S2 |
| DZIALY_14 | OBJAZDÓWKA | GRAND TOUR | `dzialy >= 14` | S2 |
| OBIE_10 | W OBIE STRONY | BOTH WAYS | `obieStrony >= 10` | S2 |
| OBIE_50 | TAM I Z POWROTEM | THERE AND BACK AGAIN | `obieStrony >= 50` | S2 |
| NAPRAWA_1 | DO TRZECH RAZY SZTUKA | THIRD TIME LUCKY | `naprawione >= 1` | S2 |
| NAPRAWA_5 | TRUDNE? JUŻ NIE | HARD? NOT ANYMORE | `naprawione >= 5` | S2 |
| NAPRAWA_25 | 25 PO POPRAWCE | 25 FIXED | `naprawione >= 25` | S2 |

Legacy titles and descriptions stay word for word as in `Achievement`.

Family descriptions (S), PL / EN:
- Słowa poznane: "Słowa ze słownika gry zaliczone choć raz: z pamięci albo w trybach wyboru." / "Dictionary words passed at least once: from memory or in a choice mode."
- Utrwalone: "Słowa ze słownika gry wpisane albo powiedziane z pamięci w trzech różnych terminach. Liczy się każde, które choć raz doszło do utrwalenia." / "Dictionary words typed or spoken from memory on three separate scheduled days. Every word that got there at least once counts."
- W terminie: "Powtórki zaliczone z pamięci wtedy, gdy słowo wróciło." / "Reviews passed from memory when the word came back."
- Dni nauki: "Dni z zaliczonym celem dnia. Nie muszą być po kolei." / "Days with the daily goal met. They need not be consecutive." DNI_100 adds: "Tyle, ile od studniówki do matury." / "As many as from the studniówka ball to the exam."
- Seria: "Najdłuższa seria dni z zaliczonym celem. Opuszczony dzień pokrywa dzień wolny z zapasu." / "Your longest streak of days with the goal met. A missed day is covered by a day off from your reserve."
- Cztery tryby: "Zagraj o wynik w każdym z czterech trybów." / "Play a ranked game in each of the four modes."
- Zestawy: "Zestawy ze słownika gry, w których każde słowo choć raz doszło do utrwalenia." / "Dictionary sets in which every word has been consolidated at least once."
- Działy: "Działy, w których co najmniej 10 słów masz zaliczonych z pamięci po przerwie." / "Topics where you have passed at least 10 words from memory after a break."
- W obie strony: "Słowa zaliczone z pamięci po przerwie w obu kierunkach tłumaczenia." / "Words passed from memory after a break in both translation directions."
- Naprawa: "Słowa utrwalone mimo wcześniejszej pomyłki. Każde liczy się raz." / "Words consolidated despite an earlier mistake. Each counts once."

Reach under the current scheduler (simulated, session only / 25 a day / 50 a day): 25 words on day 7 / 2 / 1; 75 on day 52 / 10 / 4; 250 never / never / 27; 60 consolidated on day 32 / 13 / 8; 200 never / never / 22; 500 reviews on day 58 / 31 / 19; 1500 on day 149 / 76 / 44; first set on day 10 / 7 / 10; 8 sets never / 70 / 19; 15 sets never / never / 61. The student judge's 20-set tier was lowered to 15 because nobody reaches 20 in 167 days. Higher tiers can be added later as new ids when the review intervals change.

### 3.4 Daily goal and quests

Daily goal: 30 (default), 60 or 120 XP, section C2. Simulated: a session-only student meets 30 on every day, 60 on 104 of 167 days, 120 on 18. There are no daily quests in this release (section 5).

## 4. STRETCH, in priority order

**S1. Miks dnia (S to M).** The same 20 words for every player on a calendar day: de-duplicated catalogue ids sorted by an own 64-bit FNV-1a hash of `"$dzienKalendarza:$entryId"` (not `kotlin.random`, so it is stable across builds), first 20. CLASSIC with fixed settings (3 lives, NORMAL, hasło to tłumaczenie) so scores are comparable; set id `mix-day`, title "Miks dnia, 20.11". Menu button P `MIKS DNIA` with S "Te same 20 słów dla wszystkich. Dziś: 1240 pkt" / "The same 20 words for everyone. Today: 1240 pts". Share text: "Miks dnia, 20.11: 1240 pkt, 18 z 20 słów. Te same słowa ma dziś każdy. Pobijesz?". XP only from the real events inside the round. Tests: the pick is deterministic, differs between days, has no duplicates.

**S2. Four more badge families (S).** ZESTAWY, DZIAŁY, W OBIE STRONY, NAPRAWA from section 3.3. Counters already exist in CORE; this is 12 enum rows, strings and threshold tests.

**S3. PIERWSZA RUNDA (S, only after task #4 wires `calm` and `finite` for session rounds).** While `progress.isEmpty()`, the daily `MenuButton` is replaced by a `PrimaryButton` P `PIERWSZA RUNDA` / `FIRST ROUND` with S "5 słów na start, spokojna runda, ok. 2 minuty." / "5 starter words, a calm round, about 2 minutes." It launches step 1 of today's plan through the same function as the session screen.

**S4. Profile share (S).** `Udostepnianie.profil`: "Matura Angielski: gra w słówka / Etap: CZARNY DŁUGOPIS, 8600 XP / Dni nauki: 23, słowa utrwalone choć raz: 96 / Do matury 141 dni. / link". Button on the profile.

**S5. "Session done today" mark (S, needs task #4 to store the finished day).** `checkmark` as `trailingIcon` on the daily button and S "Dzisiejsza sesja zrobiona. Na jutro: 6 powtórek." / "Today's session is done. For tomorrow: 6 reviews."

## 5. NOT NOW and REJECTED

**NOT NOW**
- Power-ups (bomb, freeze, shield), boss waves, an XP chip or hint label in the field: engine and canvas work in files task #4 is editing, nothing can be looked at on a device, new balance would make old leaderboard scores incomparable, and a bomb removes words without retrieval.
- The hint as a learning-aligned power-up (records `Outcome.PODPOWIEDZ`, which no mode emits today): right idea, engine work, later.
- Daily quests in any form: a stamp that pays nothing is a chore, one that pays XP breaks the XP rule.
- Week strip, 35-day history, 24-week calendar; a separate topic-map screen with per-direction medals: the engineer judge excluded them from this release (new stored history, a new screen).
- Session planner reserve and longer box-3 intervals (task #13, postponed): they change what students study, not how it is rewarded; owner decision. When intervals change, re-run `PacingTest` and add tiers.
- Re-checking the goal at the moment the option changes: the engineer judge prefers `setOptions` without a clock; the display rule in C2 keeps the screen honest meanwhile.
- Set records: `ScoreRecord` has no set id and the list keeps 50 scores per mode.
- Opt-in reminder notification, home-screen widget, share as an image: new permission or plumbing that only a device can verify.
- Export or backup of the ledger: needed because `allowBackup` is false; its own release. Until then the ZASADY card says progress lives on this phone only.
- Weekly SPRAWDZIAN ("pamiętasz 17 z 20"), cosmetics, teacher or class mode, exchange with matura.uk.

**REJECTED**
- Readiness percentage, predicted score, grades, CEFR-style or ability titles (MÓZG KLASY, POZIOM OLIMPIJSKI, CHODZĄCY SŁOWNIK, DUMA SZKOŁY and the rest of that ladder), ŚWIADECTWO, POSTRACH CKE, POSTRACH ARKUSZY, "na stałe", "na blachę" as a label: against the project's own rule or claiming more than the count.
- XP from score, defeated monsters, speed, time played, multipliers, records, quests or badges: merges game and learning.
- XP for recognition that does not raise the box (farmable daily on the same words, simulated at 10 000 XP without typing), and the opposite, zero reward for the two choice modes.
- Paying the first-climb premium again after a mistake; any counter or bar that can go backwards.
- Date-locked badges (STUDNIÓWKA window, OSTATNIA PROSTA): fear of missing out, forgeable through the editable date, locked forever for late installers. A badge for studying late at night. A perfect-round badge or bonus (rewards easy sets). A "best day" statistic (rewards cramming).
- A streak with unlimited silent grace sold as "z rzędu"; a zero-grace streak that one tap keeps alive; study days or streaks reconstructed in the migration from `lastPromotionDay`.
- Streak-loss warnings, repair, guilt copy, lives or energy that block study, loot boxes, a shop, fake rivals, leagues and anything needing a backend.
- One new `EkranWyniku` replacing four result screens, and any engine work inside this task.
- A countdown computed on the UTC scheduler day (wrong for one to two hours every night, including exam night).
- A checkmark for a set that was only seen once; CAŁA PODSTAWA for 140 of 2018 words.
- Replacing the multiplier sprites with `combo_frame` and text: the sprites were fixed in `0aab7c5`.
- Rules text on every screen: one collapsible card on the profile and at most three lines on the result.

Judge conflicts resolved against the teacher judge, for the record: the goal is in XP and the streak uses a reserve of days off (student and engineer judges; the unlimited grace day gave "7 in a row" in 13 calendar days); recognition counts toward the goal up to 15 XP a day; own sets keep paying XP but under a cap (student judge); WAKACJE stays as the top title and the countdown line stays on the Start screen (student judge). The last three are single constants or single lines and are listed for the owner in section 8.

## 6. Migration

`SaveData` changes: `WordProgress.szczyt: Int = 0`; `SaveData.dorobek: Dorobek = Dorobek()` (section C1); `Options.celDnia: Int = 30`, `Options.maturaDzien: Int = 20944`, `Options.odliczanie: Boolean = true`. All have defaults, so a schema-3 file still parses.

`SCHEMA_VERSION` 3 -> 4, one number for the whole release. In `Repository.migrate`: `if (out.schemaVersion < 4) { out = odswiezZestawyWbudowane(out) /* only if task #14 ships vocabulary corrections */; out = zalozKsiegeDorobku(out) }`. The ledger step must run after any catalogue refresh, because it counts by current entry ids.

`zalozKsiegeDorobku(data)` is pure, clock-free, deterministic and idempotent (`migrate` reruns on every start until the first successful save):
1. Every progress entry gets `szczyt = maxOf(szczyt, box)`.
2. If `dorobek.xp > 0`, stop here (a ledger that already has points is never overwritten).
3. `xp` = sum of `Nagrody.punktyNaStart` over the distinct non-blank entry ids of the current sets (built-in and own) in both directions: box 1 gives 10, or 3 when `lastTask == ROZPOZNANIE`; box 2 gives 30; box 3 gives 60. Never iterate the progress map: `przeniesPostep` keeps old keys and they would count twice. Nothing is taken from game points.
4. `dniNauki`, `seria`, `rekordSerii`, `seriaDzien`, `dzien`, `powtorki`, `naprawione` stay at their defaults ("we count from this version"); `wolne` starts at 1.
5. Badges whose condition already holds are unlocked silently: `odznaki[name] = 0`, and `odznakiNowe` stays empty, so the first round after the update does not announce badges the student has had for months.

Tests (`MigracjaTest`): the 0.9.4 fixture (`zapis-0.9.4-vc21.json`) reaches schema 4 with `xp == 0`, `wolne == 1`, default options, and exactly FIRST_BLOOD, ROOKIE, SHARPSHOOTER, COMBO_X3, COMBO_X5, SCORE_500 with day 0 and nothing in `odznakiNowe`; a schema-3 save with progress gets the exact XP by the table, with a duplicated orphan key not counted twice and `szczyt == box` everywhere; a second load is identical; a non-empty ledger is not overwritten; step order with a catalogue refresh in the same release.

## 7. Copy guidelines

Tone: one adult talking to another. State a fact and the number, then stop. Second person singular in the present or future ("masz", "zaliczysz"), nouns and participles instead of past-tense verbs, so every line is gender-neutral ("Cel dnia zaliczony", never "zaliczyłeś" or "sam(a)"). One idea per sentence. Jokes live only in stage and badge titles, taken from the matura year itself. Short labels in capitals for the bitmap font; whole sentences in the system font. No em or en dashes anywhere: colons, commas, full stops, brackets.

Avoid:
- urgency and loss: "tylko 167 dni", "stracisz serię", "nie przegap", "ostatnia szansa", "wróć, bo", "nie zapomnij", red for the countdown;
- praise and labels for the person: "Brawo!", "Super!", "mistrz", "geniusz", "prymus", rows of exclamation marks;
- anything that sounds like a result: "gotowość", "ocena", "zdasz", "poziom B1", a percent sign, "na stałe", "na zawsze", "opanowane";
- "z rzędu" unless the days are literally consecutive (the streak is "SERIA DNI: 6");
- forced slang and diminutives ("essa", "sztos", "słóweczka"); emoji in pixel strings, sparing elsewhere;
- violent wording ("zabij", "zabicia"): the game says "pokonane";
- "ABCD" as a mode name: the app calls the modes WIELOKROTNY WYBÓR and PRAWDA / FAŁSZ ("tryby wyboru").

Ten example lines:
1. Na dziś: 8 powtórek i 5 nowych słów, ok. 5 min
2. Sesja jest pełna powtórek. Po nowe słowa zajrzyj do GRAJ.
3. Do celu dnia brakuje 10 XP.
4. Cel dnia zaliczony: 40 z 30 XP. Reszta to bonus.
5. Słowa z pomyłką albo pokazaną odpowiedzią zaliczysz najwcześniej jutro.
6. Bez XP w tej rundzie: te słowa masz już dziś zaliczone albo ich termin jeszcze nie minął. Wynik gry liczy się normalnie.
7. Wczoraj było wolne. Seria czeka.
8. Nowa seria, dzień 1. Dni nauki razem: 31.
9. Poprawka nie daje XP. Daje to, że jutro te słowa zaliczysz.
10. Etap pokazuje włożoną pracę. Nie jest prognozą wyniku matury.

## 8. Appendix: build order, cut order, changes to the draft, owner decisions

**Build order and effort** (about 6 to 7 engineer-days in total, as two judges estimated; the logic draft in the `dorobek` worktree covers part of items 2 and 3):
1. C0 day refresh and result layout (with task #5).
2. Ledger, XP, stages, migration (C1, section 6).
3. Goal and streak (C2).
4. Countdown and options (C3).
5. Player card and subtitles (C4).
6. Reward block, record, share, sound (C5).
7. Badges and profile (C6).
8. Set-picker bars (C7).
9. Texts and guard tests (C8).
10. NOWE SŁOWA button.

**Cut order if a day is lost:** the Start-screen countdown line; the NOWE SŁOWA button (the line "Do celu dnia brakuje 10 XP." stays); the ETAPY list on the profile; C7; the date steppers (keep the switch and the default date); days off (plain consecutive streak with the same copy, `wolne` fixed at 0); badge families beyond SŁOWA, UTRWALONE, W TERMINIE and SERIA. Never cut: the zero-XP explanation, the day refresh, the migration tests, the privacy text, the font guard test.

**What must change in the draft at `/home/user/Matura/.claude/worktrees/dorobek`:**
1. `Options.celDnia`: default 5 -> 30; `CELE_DNIA` = 30, 60, 120; the goal compares `xpDzis`, not a word count; remove `slowaDzis`.
2. `Zaliczenie`: `AWANS` 10 -> 20, `UTRWALENIE` 20 -> 30, `ROZPOZNANE` 2 -> 3 with the cap of 5 a day (`rozpoznaneDzis`); add the own-set cap (`wlasneDzis`), `noweDzis`, `naprawione`.
3. Streak: replace `OKNO_SERII` (unlimited grace) with the reserve `wolne` (start 1, +1 per 7 streak days, cap 2).
4. Clock moved back: replace "points only" with re-anchoring (C1 step 3).
5. `powtorki` counts `AWANS`, `UTRWALENIE` and `POWTORKA`, not `POWTORKA` alone.
6. `Ranga` (10 steps to 50 000, top POSTRACH ARKUSZY) -> `Etap` (13 steps to 15 000 plus laps).
7. `pierscien`: no forced first quarter; 4 only when the day is counted.
8. `Nagrody.roznica`: drop the replay (`zaliczeniaRundy`, `xpZaliczen`); take the credits reported by `recordAttempt`; drop `pusta` (a zero round gets a sentence, not silence); record needs an earlier score above 0 and a ranked round.
9. `Repository`: `recordAttempt` gains `naWplyw`; `recordGame` gains `doRankingu` and `potwierdzOdznaki`; add `potwierdzOdznaki()`; catalogue ids from a lazy `SampleData.builtInSets()`.
10. `Odznaki.liczniki(data, katalog)`: iterate the bundled catalogue, not `data.sets.filter { it.builtIn }`; `dzialy` requires `hi >= 2`; add `naprawione`; `odblokuj` also fills `odznakiNowe`.
11. `Odznaka`: the list of section 3.3 (ids and thresholds change now, before anything is released; frozen from this release on). Titles lose "na stałe", "z rzędu", "Cała podstawa", "Komplet".
12. The change in `Srs.promote` (a recognition answer no longer moves the due date of a box-2 or box-3 word) is a scheduler change: keep it only with the owner's approval (decision 4), otherwise revert those lines. The gamification pays nothing for that case either way.

**Decisions for the owner:**
1. Confirm 6 May 2027 against the CKE notice before it is printed on the first screen.
2. Top stage title: WAKACJE (student judge's favourite) or stop the ladder at KARTA ODPOWIEDZI (teacher judge: a title after the exam can read as "it is over").
3. Countdown line on the Start screen: keep (student judge) or menu card only (teacher judge).
4. The `Srs` quirk: a guessed choice answer on a due box-2 or box-3 word postpones its recall check by 3 or 7 days. Recommended fix is drafted; it affects parity with matura.uk.
5. The scheduler ceiling of about 7 x daily answers (82 words for a session-only student by the exam, simulated). No gamification can fix it; task #13 is postponed.
6. Own-set cap of 10 paid words a day (student judge) or no cap (teacher judge: teacher-made lists are real learning). One constant.
7. Starter words: the first session takes the first five catalogue words (mature, immature, modest, immodest, neat). Choosing five easier ones is a content decision and would make day one kinder.
8. No backup: a new phone starts from zero. The ZASADY card says so; an export is a later release.

Paths relevant to this spec: `/home/user/Matura/app/src/main/java/com/matura/app/model/Models.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/data/Repository.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/learn/Srs.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/learn/Sesja.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/learn/Podsumowanie.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/learn/Trudne.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/ui/App.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/ui/Screens.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/ui/Common.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/ui/PodsumowanieRundy.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/ui/PixelFont.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/ui/Strings.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/ui/PlayScreen.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/ui/TrueFalseScreen.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/ui/MultipleChoiceScreen.kt`, `/home/user/Matura/app/src/main/java/com/matura/app/ui/TimeAttackScreen.kt`, `/home/user/Matura/app/src/testDebug/java/com/matura/app/PodgladEkranowTest.kt`, `/home/user/Matura/app/src/test/java/com/matura/app/MigracjaTest.kt`, `/home/user/Matura/docs/prywatnosc.html`, draft logic in `/home/user/Matura/.claude/worktrees/dorobek/app/src/main/java/com/matura/app/learn/Nagrody.kt`, `/home/user/Matura/.claude/worktrees/dorobek/app/src/main/java/com/matura/app/learn/Odznaki.kt`, `/home/user/Matura/.claude/worktrees/dorobek/app/src/main/java/com/matura/app/learn/Kalendarz.kt`, engine fields in `/home/user/Matura/.claude/worktrees/petla/app/src/main/java/com/matura/app/game/GameEngine.kt`, simulation in `/tmp/claude-0/-home-user-Matura/0e5748ef-99b0-59e1-b03e-46ac0f545d18/scratchpad/final/sim.py` and `/tmp/claude-0/-home-user-Matura/0e5748ef-99b0-59e1-b03e-46ac0f545d18/scratchpad/final/sim_extra.py`.