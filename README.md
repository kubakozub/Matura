# Matura: CLASSIC

[![Get it on Google Play](https://img.shields.io/badge/Google%20Play-download-a8e05a?style=for-the-badge&logo=googleplay&logoColor=101322&labelColor=101322)](https://play.google.com/store/apps/details?id=com.matura.app&referrer=utm_source%3Dgithub%26utm_medium%3Dreadme%26utm_campaign%3Drepo)
[![Site and browser demo](https://img.shields.io/badge/matura.uk-browser%20demo-101322?style=for-the-badge&labelColor=a8e05a)](https://matura.uk)

**The app is on Google Play: free, no ads, no account.** It can also be tried without
installing anything, in the browser, at [matura.uk](https://matura.uk).

[![MATURA trailer, 32 s on YouTube](https://matura.uk/assets/zwiastun.527946e5.png)](https://www.youtube.com/watch?v=B8ScJBjyoMg)

*Trailer, 32 s: click the image to watch it on YouTube.*

A game for learning English vocabulary, built directly from the design document
*Verbume: Verbum + Game*. Android, Kotlin, Jetpack Compose. CLASSIC mode with full
progression. The vocabulary covers the Polish *matura* school leaving exam at basic level,
so the answers are Polish and the prompts are English.

**Version 0.9.0** · 102 sets · 2084 entries · 14 topic areas of the basic exam

> The app used to be called **Verbume**. The name and the package identifier changed
> (`com.verbume.app` became `com.matura.app`); the mechanics, the vocabulary and the
> artwork did not.

## New in 0.9.0

- **A layout for large screens.** Android 16 ignores
  `android:screenOrientation="portrait"` on screens of 600 dp and up, so the game reaches
  tablets and foldables in landscape whatever the manifest says. The entry is gone, and
  `PlayScreen` switches to a **two column** layout when the window is wider than it is
  tall: the board on the left, the answer bar and keyboard on the right. Stacked
  vertically, on an 891 by 411 dp phone the board had been squeezed into a strip 13% of
  the height while the keyboard alone took more than half the screen.
- **The post game summary uses the whole window.** The GAME OVER screen now draws over the
  full screen rather than only over the board: in landscape the board is half the width,
  and the TO REVIEW list would not have fitted inside it.
- **Screen previews rendered on the JVM.**
  `./gradlew :app:updateDebugScreenshotTest` draws six layouts (phone portrait and
  landscape, foldable, tablet, keyboard alone) through Layoutlib, with no emulator and no
  device. Those previews are what revealed the landscape layout was broken. The images
  land in `app/build/outputs/screenshotTest-results/` and are **deliberately not
  versioned**: they weigh 1.5 MB and change with every layout fix, and an APK has already
  inflated `.git` once.

## New in 0.8.0

Again, all of it from tester feedback.

- **Hyphenated entries can be typed.** A tester drew "otwarty" and had no way to answer
  `open-minded`: the keyboard holds only A-Z, and a hyphen is folded to a space during
  normalisation anyway. A **no space** variant was added to the accepted answers, so both
  `open minded` and `openminded` count, and prefix highlighting works for both. This
  applies to all 665 multi word entries, not only the 37 containing a hyphen.
- **A TO REVIEW list after the game.** Under the score, the GAME OVER screen lists every
  entry that reached the cannon, together with its translation in the original spelling,
  with diacritics and hyphen rather than in normalised form. The same word lost twice is
  one row with an `x2` counter. The list scrolls inside its own fixed height frame, so a
  long game cannot push the buttons off screen.
- **No more pile up of the same words.** The draw used to be with replacement, so one
  entry could keep returning while another never appeared at all. It now works as a bag:
  the whole set is shuffled and drawn without replacement, and it is reshuffled only once
  exhausted. A new queue does not begin with whatever the previous one ended on. Repeats
  within a single game remain, which is the point of sets of roughly twenty entries, but
  they are now spread evenly.

The browser demo received the same three changes, so that it still matches the engine.

## New in 0.7.0

All three changes follow directly from test feedback.

- **A slower pace.** Base monster speed dropped from `0.030` to `0.024`, the per level
  increment from `0.0055` to `0.0042`, and the waves are sparser (`3.8 s` rather than
  `3.4 s`, with a floor of `1.4 s` rather than `1.0 s`). With longer entries a monster was
  reaching the cannon before the word could be finished.
- **Pace as a setting.** OPTIONS, then *Pace*: `SLOW` (times 0.75), `NORMAL` (times 1),
  `FAST` (times 1.3). The multiplier scales the entire speed curve, the per level increment
  included. Scoring does not change: a score made on SLOW counts the same.
- **The space bar sat too low.** On some phones the bottom keyboard row lay under the
  gesture bar. That row is now taller (56 dp rather than 48 dp), the space bar is wider
  (weight 6.6 rather than 5.5), and the side fillers are gone.
- **One place that handles system chrome.** `MaturaApp` calls `safeDrawingPadding()`, so
  from the root down the content avoids the status bar, the navigation bar, a display
  cutout and the open system keyboard. Previously only the in game keyboard moved out of
  the way: MENU, OPTIONS, SETS and the editor ended with a fixed gap, and the last button
  could fall under the gesture bar. On Android 15 the system clock additionally sat on top
  of the hearts and the ammunition counter.

- Browser demo: **[kubakozub.github.io/Matura](https://kubakozub.github.io/Matura/)**
- Full design documentation, in Polish: [`docs/Matura-dokumentacja.pdf`](docs/Matura-dokumentacja.pdf)

## How it plays

A cannon stands in the middle of the board. Monsters arrive from the sides with English
words above their heads. You type the Polish translation: the cannon turns and fires. A
monster that reaches the cannon costs a life.

Typing matches on a prefix, so `cie` highlights both `ciekawość` and `cierpki`. Enter
fires if the text is an exact match, and when several entries match, the target is the one
nearest the cannon.

## What is implemented

**Gameplay (CLASSIC)**: points 1 to 6 and 9 of the design document

| Item from the document | State |
|---|---|
| A cannon in the centre with a moving barrel | yes, the barrel aims at the monster you are currently typing |
| Monsters entering from the sides of the screen | yes |
| Prefix highlighting | yes, with a test reproducing the example from the document |
| A text field plus a restricted keyboard (A-Z, space, backspace, cursor) | yes |
| Lives (3 by default, configurable from 1 to 5) | yes |
| An ammunition counter, with the weakest round unlimited | yes, `∞ / N` |
| Score (bottom left) and difficulty level (bottom right) | yes |
| Ammunition crates on higher levels | yes, from level 2, collected by typing the translation |
| Armour: the first round strips it, a second translation is needed | yes, from level 3 |
| Heavier rounds kill an armoured monster in one shot | yes, selected automatically from the stock |
| Streak multipliers of x2, x3, x4 and x5 | yes, at 3, 6, 9 and 12 consecutive hits |
| Rising difficulty: faster monsters, denser spawns | yes, a new level every 8 kills |

**The rest of the app**

- A start screen with "play anonymously" (point 7)
- Menu: PLAY / SETS / OPTIONS / ACHIEVEMENTS / SCORES / EXIT (point 8)
- SETS: the list of sets and an editor laid out like Quizlet, with numbered rows, two
  language columns and a swap sides button (points 10 and 11). Your own sets are deleted
  with the bin: the first tap arms the button, the second removes the set. Built in sets
  cannot be deleted.
- OPTIONS: sound, number of lives, textures, monster pace, interface language (Polish or
  English), direction of translation (point 12)
- ACHIEVEMENTS: 8 badges computed from your own statistics
- SCORES: a local leaderboard, best runs, most played set (point 13)
- **102 sets, 2084 entries**: vocabulary for the topic range of the basic exam, split
  across 14 areas (People, Home, Education, Work, Private life, Food, Shopping, Travel,
  Culture, Sport, Health, Science and technology, Nature, Social life). Each set holds 20
  to 25 entries, so that a single game shows every word several times.
- **Pixel art**: 97 sprites scaled by nearest neighbour only, kept in `drawable-nodpi` so
  that Android does not blur them at load time
- **A bitmap font**, `font_8x8`: the whole interface apart from longer prose is drawn from
  5 by 7 glyphs on a sheet, outlined by one pixel in each direction. Polish characters are
  separate glyphs rather than being taken from the system face.
- **Animation**: a two frame walk cycle every 200 ms, a death frame for 350 ms, a hit
  flash, and a new level banner
- **A browser demo**: one HTML file with the same mechanics, for showing the game without
  installing it

## What is missing, and why

- **Accounts, sign in, other people's sets, global leaderboards**: these need a server.
  The app is fully offline and everything lives in a private JSON file on the device, so
  "play anonymously" is the only path.
- **TRUE/FALSE, MULTIPLE CHOICE, TIME ATTACK**: visible in the mode menu as inactive. The
  engine is ready for them, since matching and the set model are shared, but the modes
  themselves are not written.
- **Automatic translation behind the "T" button**: needs the paid Google Translate API and
  a key.
- **Sound**: short tones generated on the device, with no audio files.
- **Quizlet import inside the app**: the browser demo accepts a pasted export, the app
  does not yet.

## One design decision that does not come from the document

The keyboard in the mock up holds only A-Z, with no Polish characters. Without changing
the rules it would have been impossible to type "zręczny", which is an answer from the
mock up itself. Answer matching therefore ignores diacritics, letter case and extra
spaces, so `ZRECZNY`, `zreczny` and `zręczny` are equivalent. A comma in the translation
column separates variants, each of which is accepted on its own, and anything in brackets
is optional.

## Installing the APK

The APK is signed with a debug key, so it installs without Android Studio:

1. Get the file onto the phone. Gmail rejects `.apk`, including inside an archive, so use
   a cable, cloud storage, or download the file directly in the phone's browser.
2. Open the file in a file manager and allow it to "install unknown apps".
3. Install.

Or over a cable: `adb install -r app-debug.apk`

Minimum Android: 7.0 (API 24). The app runs in portrait on phones.

## Building from source

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew :app:bundleRelease          # AAB for Google Play in app/build/outputs/bundle/release/
./gradlew :app:testDebugUnitTest      # 54 unit tests
```

Without `keystore.properties` the release bundle comes out unsigned: signing is done
separately, on the machine that holds the key.

In Android Studio: `File`, then `Open`, on the repository directory. JDK 21 and Android
SDK 36 are required (the bytecode targets Java 17).

## Code map

```
app/src/main/java/com/matura/app/
  MainActivity.kt            entry point
  model/Models.kt            sets, options, profile, scores, badges
  data/Repository.kt         persistence to JSON
  data/BuiltInSets.kt        generated vocabulary sets (do not edit by hand)
  game/Matching.kt           answer normalisation and matching
  game/GameEngine.kt         the CLASSIC simulation, pure Kotlin, no Android
  ui/App.kt                  the screen stack
  ui/PlayScreen.kt           board, drawing, frame loop
  ui/Keyboard.kt             the restricted keyboard from the document
  ui/Screens.kt              menu, sets, editor, options, badges, scores
  ui/Sprites.kt              sprite registry, animation frames, tiling
  ui/PixelFont.kt            the font_8x8 bitmap font renderer
app/src/test/java/com/matura/app/
  EngineTest.kt              GameEngineTest (28) plus MatchingTest (9)
  BuiltInSetsTest.kt         10 tests enforcing the vocabulary rules
  PersistenceTest.kt         7 tests of save and load
```

`GameEngine` deliberately knows nothing about Android or Compose, which is what lets the
whole mechanic be tested with ordinary JUnit and no emulator. 54 tests in total.

## Publishing

`docs/` is the source for GitHub Pages: in the repository settings, branch **master**,
folder **/docs**. The APK is deliberately not versioned, because committing it once
inflated the `.git` directory from a few hundred kilobytes to eleven megabytes.

The APK therefore lives in **GitHub Releases** rather than in `docs/`.
`docs/pobierz.html` links to `releases/latest/download/MaturaV080.apk`, so on every
release the file has to carry exactly that name, or the download button returns a 404. Up
to 0.7.0 the link pointed at a file in `docs/` that was not there, and downloading simply
did not work.

## Sister app

**[Aleima](https://github.com/kubakozub/aleima)**: the same game in English, for learning
Spanish, with the vocabulary split by CEFR level.

## Dla uczniów

Gra jest po polsku, darmowa i bez reklam: [matura.uk](https://matura.uk) albo
[Google Play](https://play.google.com/store/apps/details?id=com.matura.app).
