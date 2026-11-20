package com.matura.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.matura.app.data.BuiltInSets
import com.matura.app.learn.Sesja
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.GameMode
import com.matura.app.model.Options
import com.matura.app.model.Profile
import com.matura.app.model.SaveData
import com.matura.app.model.ScoreRecord
import com.matura.app.model.UiLanguage
import com.matura.app.model.WordSet
import com.matura.app.ui.AchievementsScreen
import com.matura.app.ui.DzisiejszaSesjaScreen
import com.matura.app.ui.MenuScreen
import com.matura.app.ui.ModePickScreen
import com.matura.app.ui.MultipleChoiceScreen
import com.matura.app.ui.OptionsScreen
import com.matura.app.ui.PlayScreen
import com.matura.app.ui.ScoresScreen
import com.matura.app.ui.SetPickScreen
import com.matura.app.ui.StartScreen
import com.matura.app.ui.Str
import com.matura.app.ui.TimeAttackScreen
import com.matura.app.ui.TrueFalseScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * PODGLADY EKRANOW RYSOWANE NA JVM, bez emulatora i bez telefonu.
 *
 * Po co: uklad i tak trzeba OBEJRZEC, a testy semantyki (A11y*, TouchTargetAudit) mowia tylko,
 * ze wezel istnieje i ma rozmiar, nie ze ekran sie miesci i wyglada sensownie. Galaz 0.9.0
 * miala do tego wtyczke zrzutow opartych o Layoutlib i to one pokazaly zepsuty uklad w poziomie;
 * drzewo produkcyjne jej nie przejelo. Ten plik robi to samo jednym testem Robolectrica
 * z natywna grafika: sklada ekran, rysuje okno Activity do bitmapy i zapisuje PNG.
 *
 * Obrazy laduja w `app/build/podglad/<rozmiar>/<nazwa>.png`, czyli w katalogu budowania:
 * NIE sa wersjonowane (ta sama zasada co przy APK i obrazach referencyjnych) i nie sa z niczym
 * porownywane. Test pada tylko wtedy, gdy ekran rzuci wyjatkiem przy skladaniu albo rysowaniu,
 * wiec przy okazji jest dymnym testem kazdego ekranu w kazdym z rozmiarow.
 *
 * Uruchomienie:
 *     ./gradlew :app:testDebugUnitTest --tests 'com.matura.app.PodgladEkranowTest'
 *
 * Dlaczego rysowanie widoku, a nie `captureToImage()`: pod Robolectrikiem `captureToImage`
 * czeka na przerysowanie okna, ktore przy wstrzymanej petli glownej nie nadchodzi (konczy sie
 * ComposeTimeoutException). `decorView.draw(Canvas(bitmap))` idzie ta sama sciezka rysowania
 * i z natywna grafika daje pelny obraz, razem ze sprite'ami i fontem bitmapowym.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PodgladEkranowTest {

    @get:Rule val rule = createEmptyComposeRule()

    private val str = Str(UiLanguage.PL)

    /**
     * Rozmiary, w ktorych ogladamy kazdy ekran. Maly telefon jest tu najwazniejszy: to na nim
     * przyciski wypadaja poza ekran. Poziom i tablet sa po to, ze Android 16 ignoruje blokade
     * orientacji na ekranach od 600 dp, wiec gra trafi tam niezaleznie od manifestu.
     */
    private val rozmiary = listOf(
        "telefon-maly" to "w320dp-h568dp-port-xhdpi",
        "telefon-niski" to "w360dp-h640dp-port-xhdpi",
        "telefon" to "w360dp-h740dp-port-xhdpi",
        "telefon-poziomo" to "w740dp-h360dp-land-xhdpi",
        "tablet-poziomo" to "w1280dp-h800dp-land-mdpi",
    )

    /**
     * Sklada [ekran] w kazdym z rozmiarow i zapisuje obraz po kazdym kroku z [kroki].
     * Krok to para: nazwa pliku i to, co zrobic PRZED zrzutem (np. przesunac zegar gry).
     */
    private fun podglad(
        nazwa: String,
        kroki: List<Pair<String, () -> Unit>> = listOf("" to {}),
        tylko: List<String>? = null,
        ekran: @Composable () -> Unit,
    ) {
        for ((rozmiar, kwalifikatory) in rozmiary) {
            if (tylko != null && rozmiar !in tylko) continue
            RuntimeEnvironment.setQualifiers(kwalifikatory)
            // Zegar stoi: gra nie biegnie sama, wiec zrzut "w toku" jest powtarzalny.
            rule.mainClock.autoAdvance = false
            ActivityScenario.launch(ComponentActivity::class.java).use { scenariusz ->
                scenariusz.onActivity { it.setContent { ekran() } }
                for ((przyrostek, przed) in kroki) {
                    przed()
                    rule.mainClock.advanceTimeByFrame()
                    rule.waitForIdle()
                    scenariusz.onActivity { activity ->
                        val widok = activity.window.decorView
                        val bmp = Bitmap.createBitmap(widok.width, widok.height, Bitmap.Config.ARGB_8888)
                        widok.draw(Canvas(bmp))
                        val katalog = File(KATALOG, rozmiar).apply { mkdirs() }
                        val plik = if (przyrostek.isEmpty()) nazwa else "$nazwa-$przyrostek"
                        FileOutputStream(File(katalog, "$plik.png")).use {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
            }
        }
    }

    private fun czas(ms: Long): () -> Unit = { rule.mainClock.advanceTimeBy(ms) }

    private val profil = Profile(name = "Kuba", gamesPlayed = 7, totalKills = 120, bestScore = 640, bestStreak = 7)

    private val dane: SaveData
        get() {
            val zestawy = BuiltInSets.all()
            return SaveData(
                profile = profil,
                sets = zestawy,
                scores = listOf(
                    ScoreRecord("Kuba", zestawy[0].title, 640, 4, 7, 31, 0L, GameMode.CLASSIC),
                    ScoreRecord("Kuba", zestawy[1].title, 410, 3, 5, 22, 0L, GameMode.TIME_ATTACK),
                    ScoreRecord("Kuba", zestawy[2].title, 280, 2, 4, 18, 0L, GameMode.MULTIPLE_CHOICE),
                ),
            )
        }

    @Test
    fun start() = podglad("start") {
        StartScreen(str = str, profile = Profile(), onPlayAnonymously = {})
    }

    @Test
    fun menu() = podglad("menu") {
        MenuScreen(
            str = str, profile = profil,
            onDaily = {}, onHardWords = {}, onPlay = {}, onSets = {}, onOptions = {},
            onAchievements = {}, onScores = {}, onExit = {},
        )
    }

    @Test
    fun wybor_dzialu() = podglad("wybor-dzialu") {
        SetPickScreen(str = str, sets = BuiltInSets.all(), onBack = {}, onPick = {})
    }

    @Test
    fun wybor_trybu() = podglad("wybor-trybu") {
        ModePickScreen(str = str, set = BuiltInSets.all().first(), onBack = {}, onPickMode = {})
    }

    @Test
    fun opcje() = podglad("opcje") {
        OptionsScreen(str = str, options = Options(), onBack = {}, onChange = {})
    }

    @Test
    fun osiagniecia() = podglad("osiagniecia") {
        AchievementsScreen(str = str, profile = profil, onBack = {})
    }

    @Test
    fun wyniki() = podglad("wyniki") {
        ScoresScreen(str = str, data = dane, onBack = {})
    }

    @Test
    fun gra_klasyczna() = podglad(
        "gra-klasyczna",
        kroki = listOf("w-toku" to czas(14_000), "koniec" to czas(150_000)),
    ) {
        PlayScreen(
            set = BuiltInSets.all().first(), options = Options(), str = str,
            playerName = "Kuba", onExit = {}, onFinished = {}, onAttempt = {},
        )
    }

    /**
     * Cztery slowa, zeby runde skonczona dalo sie w podgladzie PRZEJSC klawiatura gry, a nie
     * tylko obejrzec w biegu. "schoolmate" ma najdluzsza odpowiedz w calym katalogu: to na niej
     * widac, czy odkryta odpowiedz i wiersz listy mieszcza sie na waskim telefonie.
     */
    private val malyZestaw = WordSet(
        id = "podglad",
        title = "Podglad",
        entries = listOf(
            Entry("ache", "ból", "w000000000001"),
            Entry("schoolmate", "kolega ze szkoły, koleżanka ze szkoły", "w000000000002"),
            Entry("salary", "pensja", "w000000000003"),
            Entry("homework", "praca domowa", "w000000000004"),
        ),
    )

    private fun jest(opis: String): Boolean =
        rule.onAllNodesWithContentDescription(opis).fetchSemanticsNodes().isNotEmpty()

    /** Przesuwa zegar gry, az [warunek] bedzie prawda (plansza jest rysunkiem, wiec pytamy semantyke). */
    private fun grajAz(co: String, warunek: () -> Boolean) {
        repeat(400) {
            if (warunek()) return
            rule.mainClock.advanceTimeBy(250)
        }
        error("po 100 s gry dalej nie ma: $co")
    }

    /** Odpowiedz wpisana klawiatura gry i zatwierdzona. */
    private fun wpisz(odpowiedz: String) {
        for (znak in odpowiedz) {
            rule.onNodeWithContentDescription(if (znak == ' ') str.keySpace else znak.uppercase()).performClick()
        }
        rule.onNodeWithContentDescription(str.keyEnter).performClick()
    }

    /**
     * Runda spokojna i skonczona od srodka do konca, tak jak idzie krok dziennej sesji:
     *  - "w-toku": cztery slowa na planszy, jedno zaliczone, w rogu licznik zamiast serc;
     *  - "odkrycie": trzy zaliczone, czwarte doszlo do dziala i plansza pokazuje odpowiedz;
     *  - "koniec": czwarte wrocilo i dostalo odpowiedz, wiec runda zamknela sie sama.
     */
    @Test
    fun gra_klasyczna_spokojna() = podglad(
        "gra-klasyczna-spokojna",
        kroki = listOf(
            // Po 13 s wszystkie cztery potworki sa juz na planszy, a zaden jeszcze nie doszedl.
            "w-toku" to { czas(13_000)(); wpisz("pensja"); czas(700)() },
            "odkrycie" to {
                wpisz("bol"); wpisz("praca domowa")
                grajAz("odkryta odpowiedz") { jest("schoolmate: kolega ze szkoły, koleżanka ze szkoły") }
                czas(400)()
            },
            "koniec" to {
                // Slowo wraca na plansze najpozniej 3,8 s po tym, jak doszlo do dziala.
                czas(4_500)()
                wpisz("kolega ze szkoly")
                grajAz("ekran RUNDA UKONCZONA") { jest(str.roundComplete) }
            },
        ),
    ) {
        PlayScreen(
            set = malyZestaw, options = Options(), str = str,
            playerName = "Kuba", onExit = {}, onFinished = {},
            calm = true, finite = true, onContinue = {}, onAttempt = {},
        )
    }

    /** Runda ukonczona bez jednego przepuszczonego slowa: zdanie zamiast pustej ramki listy. */
    @Test
    fun gra_klasyczna_bez_bledow() = podglad(
        "gra-klasyczna-bez-bledow",
        kroki = listOf(
            "koniec" to {
                czas(13_000)()
                wpisz("pensja"); wpisz("bol"); wpisz("praca domowa"); wpisz("kolega ze szkoly")
                grajAz("ekran RUNDA UKONCZONA") { jest(str.roundComplete) }
            },
        ),
    ) {
        PlayScreen(
            set = malyZestaw, options = Options(), str = str,
            playerName = "Kuba", onExit = {}, onFinished = {},
            calm = true, finite = true, onAttempt = {},
        )
    }

    /** Runda skonczona z zyciami (krok GRA bez nowych slow): serca, a pod nimi licznik. */
    @Test
    fun gra_klasyczna_skonczona() = podglad(
        "gra-klasyczna-skonczona",
        kroki = listOf("w-toku" to { czas(13_000)(); wpisz("pensja"); czas(700)() }),
    ) {
        PlayScreen(
            set = malyZestaw, options = Options(lives = 5), str = str,
            playerName = "Kuba", onExit = {}, onFinished = {},
            finite = true, onContinue = {}, onAttempt = {},
        )
    }

    /** Pierwszy dzien: piec nowych slow, wiec runda gry jest spokojna i ma plakietke. */
    @Test
    fun dzisiejsza_sesja() = podglad("dzisiejsza-sesja") {
        val dzien = 19_875
        val plan = Sesja.zaplanuj(
            emptyMap(), BuiltInSets.all().first().entries.map { it.id }, Direction.TERM_TO_DEF, dzien,
        )
        val stan = Sesja.rozpocznij(plan) { 0L }
        DzisiejszaSesjaScreen(
            jezyk = UiLanguage.PL,
            plan = plan,
            stan = stan,
            podsumowanie = Sesja.podsumuj(plan, stan, emptyMap(), dzien),
            czasNaKoniec = true,
            wznowiona = true,
            onPrzerwij = {}, onKrok = {}, onDalej = {}, onZakoncz = {},
        )
    }

    @Test
    fun prawda_falsz() = podglad(
        "prawda-falsz",
        kroki = listOf("w-toku" to czas(1_000), "koniec" to czas(150_000)),
    ) {
        TrueFalseScreen(
            set = BuiltInSets.all().first(), options = Options(), str = str,
            playerName = "Kuba", onExit = {}, onFinished = {}, onAttempt = {},
        )
    }

    @Test
    fun wielokrotny_wybor() = podglad(
        "wielokrotny-wybor",
        kroki = listOf("w-toku" to czas(1_000), "koniec" to czas(150_000)),
    ) {
        MultipleChoiceScreen(
            set = BuiltInSets.all().first(), options = Options(), str = str,
            playerName = "Kuba", onExit = {}, onFinished = {}, onAttempt = {},
        )
    }

    @Test
    fun na_czas() = podglad(
        "na-czas",
        kroki = listOf("w-toku" to czas(3_000), "koniec" to czas(200_000)),
    ) {
        TimeAttackScreen(
            set = BuiltInSets.all().first(), options = Options(), str = str,
            playerName = "Kuba", onExit = {}, onFinished = {}, onAttempt = {},
        )
    }

    private companion object {
        /** Katalog budowania modulu: Gradle uruchamia testy z katalogiem roboczym `app/`. */
        val KATALOG = File(System.getProperty("podglad.dir") ?: "build/podglad")
    }
}
