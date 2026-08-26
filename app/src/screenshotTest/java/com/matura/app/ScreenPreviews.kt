package com.matura.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.matura.app.game.GameSnapshot
import com.matura.app.game.MissedWord
import com.matura.app.model.Entry
import com.matura.app.model.Options
import com.matura.app.model.UiLanguage
import com.matura.app.model.WordSet
import com.matura.app.ui.GameKeyboard
import com.matura.app.ui.GameOverOverlay
import com.matura.app.ui.PlayScreen
import com.matura.app.ui.Str
import com.matura.app.ui.rememberPixelFont
import com.matura.app.ui.rememberSprites

/**
 * Ekrany renderowane na JVM przez Layoutlib — bez emulatora i bez telefonu.
 *
 * Powod: Google ostrzega, ze Android 16 zignoruje `android:screenOrientation="portrait"`
 * na ekranach od 600dp w gore, wiec gra trafi na tablety i skladane w ukladzie, ktorego
 * nikt nigdy nie widzial. Te podglady pokazuja go, zanim zobacza go testerzy.
 */

private const val TELEFON = "spec:width=411dp,height=891dp,dpi=420"
private const val TELEFON_POZIOMO = "spec:width=891dp,height=411dp,dpi=420"
private const val SKLADANY_OTWARTY = "spec:width=674dp,height=841dp,dpi=390"
private const val TABLET_POZIOMO = "spec:width=1280dp,height=800dp,dpi=240"

private val zestaw = WordSet(
    id = "podglad",
    title = "Czlowiek - charakter",
    entries = listOf(
        Entry("open-minded", "otwarty"),
        Entry("hard-working", "pracowity"),
        Entry("kind", "uprzejmy"),
        Entry("agile", "zreczny"),
        Entry("curiosity", "ciekawosc"),
    ),
)

@Composable
private fun Gra() = PlayScreen(
    set = zestaw,
    options = Options(),
    str = Str(UiLanguage.PL),
    playerName = "Podglad",
    onExit = {},
    onFinished = {},
)

@Preview(name = "gra - telefon", device = TELEFON, showBackground = true)
@Composable
fun GraTelefon() = Gra()

@Preview(name = "gra - telefon poziomo", device = TELEFON_POZIOMO, showBackground = true)
@Composable
fun GraTelefonPoziomo() = Gra()

@Preview(name = "gra - skladany otwarty", device = SKLADANY_OTWARTY, showBackground = true)
@Composable
fun GraSkladany() = Gra()

@Preview(name = "gra - tablet poziomo", device = TABLET_POZIOMO, showBackground = true)
@Composable
fun GraTablet() = Gra()

@Preview(name = "klawiatura - telefon", device = TELEFON, showBackground = true)
@Composable
fun KlawiaturaTelefon() = GameKeyboard(
    onChar = {}, onBackspace = {}, onEnter = {}, onSpace = {}, onLeft = {}, onRight = {},
)

@Preview(name = "klawiatura - tablet poziomo", device = TABLET_POZIOMO, showBackground = true)
@Composable
fun KlawiaturaTablet() = GameKeyboard(
    onChar = {}, onBackspace = {}, onEnter = {}, onSpace = {}, onLeft = {}, onRight = {},
)


/**
 * Ekran KONIEC GRY z pelna lista do powtorki. Codex zauwazyl na PR #3, ze w poziomie
 * staly uklad tego ekranu (419 dp) nie miesci sie w oknie telefonu (411 dp) i przyciski
 * wyjezdzaja poza ekran — a to jedyna droga wyjscia. Te dwa podglady to pokazuja.
 */
private val koniecGry = GameSnapshot(
    monsters = emptyList(),
    crates = emptyList(),
    projectiles = emptyList(),
    corpses = emptyList(),
    typed = "",
    cursor = 0,
    lives = 0,
    maxLives = 3,
    heavyAmmo = 0,
    score = 1240,
    level = 4,
    kills = 27,
    streak = 0,
    multiplier = 1,
    bestStreak = 9,
    gameOver = true,
    elapsed = 132f,
    flash = null,
    levelUpBanner = 0f,
    missedWords = listOf(
        MissedWord("open-minded", "otwarty, o szerokich horyzontach", 2),
        MissedWord("hard-working", "pracowity", 1),
        MissedWord("uprzejmy", "kind", 1),
        MissedWord("stan cywilny", "marital status", 1),
    ),
)

@Composable
private fun KoniecGry() = GameOverOverlay(
    snap = koniecGry,
    str = Str(UiLanguage.PL),
    sprites = rememberSprites(),
    font = rememberPixelFont(),
    onAgain = {},
    onMenu = {},
)

@Preview(name = "koniec gry - telefon", device = TELEFON, showBackground = true)
@Composable
fun KoniecGryTelefon() = KoniecGry()

@Preview(name = "koniec gry - telefon poziomo", device = TELEFON_POZIOMO, showBackground = true)
@Composable
fun KoniecGryTelefonPoziomo() = KoniecGry()
