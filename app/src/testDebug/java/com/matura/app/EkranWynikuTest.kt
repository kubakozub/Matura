package com.matura.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.matura.app.game.FlashKind
import com.matura.app.game.MissedWord
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.Options
import com.matura.app.model.ScoreRecord
import com.matura.app.model.Speed
import com.matura.app.model.UiLanguage
import com.matura.app.model.WordSet
import com.matura.app.ui.EkranWyniku
import com.matura.app.ui.ListaDoPowtorki
import com.matura.app.ui.PlayScreen
import com.matura.app.ui.PrzyciskWyniku
import com.matura.app.ui.Palette
import com.matura.app.ui.Str
import com.matura.app.ui.czyStrata
import com.matura.app.ui.pixel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DLACZEGO TEN TEST ISTNIEJE
 *
 * Koniec rundy w trybie klasycznym byl nakladka rysowana w ramce planszy. Na telefonie
 * 320 x 568 dp przyciski JESZCZE RAZ i DO MENU ladowaly pod polem odpowiedzi i klawiatura:
 * nie bylo ich widac, nie dalo sie w nie trafic, a jedynym wyjsciem z rundy bylo systemowe
 * "wstecz". Pod nakladka zostawala tez cala klawiatura, dalej klikalna i dalej czytana przez
 * czytnik ekranu.
 *
 * Wszystko tutaj dzieje sie WLASNIE na 320 x 568 dp, bo to ten rozmiar pokazal blad. Runde
 * konczymy naprawde (potworek dochodzi do dziala albo pada poprawna odpowiedz), a nie
 * podstawionym stanem: ekran wyniku ma sie pojawic tam, gdzie pojawia sie uczniowi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h568dp-xhdpi")
class EkranWynikuTest {

    @get:Rule
    val rule = createComposeRule()

    private val str = Str(UiLanguage.PL)

    /** Jedno slowo: wiadomo, co wyjdzie na plansze i co uczen ma wpisac. */
    private val jednoSlowo = WordSet(
        id = "t",
        title = "Test",
        entries = listOf(Entry("ache", "ból", "w000000000001")),
    )

    private val klawisze: List<String> =
        ('A'..'Z').map { it.toString() } +
            listOf(str.keyBackspace, str.keyEnter, str.keySpace, str.keyCursorLeft, str.keyCursorRight)

    private var wyjscia = 0
    private var dalej = 0
    private var ukonczone = 0
    private val rekordy = ArrayList<ScoreRecord>()

    private fun pokazGre(
        zycia: Int = 1,
        spokojna: Boolean = false,
        skonczona: Boolean = false,
        zKontynuacja: Boolean = false,
    ) {
        // Petla klatek gry nigdy nie zglasza bezczynnosci, wiec zegar prowadzimy recznie
        // (tak samo jak SystemKeyboardTest).
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PlayScreen(
                set = jednoSlowo,
                options = Options(lives = zycia, soundEnabled = false, speed = Speed.FAST),
                str = str,
                playerName = "Test",
                onExit = { wyjscia++ },
                onFinished = { rekordy += it },
                calm = spokojna,
                finite = skonczona,
                onCompleted = { ukonczone++ },
                onContinue = if (zKontynuacja) ({ dalej++ }) else null,
                onAttempt = {},
            )
        }
        rule.mainClock.advanceTimeByFrame()
    }

    /** Czy w drzewie jest wezel o takim opisie (napisy pikselowe, klawisze, plansza). */
    private fun jest(opis: String): Boolean =
        rule.onAllNodesWithContentDescription(opis).fetchSemanticsNodes().isNotEmpty()

    /** Czy w drzewie jest zwykly napis o takiej tresci. */
    private fun jestTekst(tekst: String): Boolean =
        rule.onAllNodes(hasText(tekst)).fetchSemanticsNodes().isNotEmpty()

    /** Przesuwa zegar gry, az [warunek] bedzie prawda. Potworek idzie do dziala najwyzej 22 s. */
    private fun grajAz(co: String, warunek: () -> Boolean) {
        repeat(240) {
            if (warunek()) {
                // Jeszcze dwie klatki: efekty zalozone po koncu rundy (zgloszenie wyniku) maja ruszyc.
                rule.mainClock.advanceTimeByFrame()
                rule.mainClock.advanceTimeByFrame()
                return
            }
            rule.mainClock.advanceTimeBy(250)
        }
        fail("po 60 s gry dalej nie ma: $co")
    }

    /** Odpowiedz wpisana klawiatura gry, klawisz po klawiszu, i zatwierdzona. */
    private fun wpisz(odpowiedz: String) {
        for (znak in odpowiedz) {
            rule.onNodeWithContentDescription(if (znak == ' ') str.keySpace else znak.uppercase()).performClick()
        }
        rule.onNodeWithContentDescription(str.keyEnter).performClick()
    }

    private fun wymagajWCalosciWOknie(opis: String) {
        val okno = rule.onRoot().getUnclippedBoundsInRoot()
        val wezel = rule.onNodeWithContentDescription(opis)
        wezel.assertIsDisplayed()
        val b = wezel.getUnclippedBoundsInRoot()
        assertTrue(
            "przycisk \"$opis\" wystaje poza okno: $b w oknie $okno",
            b.left >= okno.left && b.top >= okno.top && b.right <= okno.right && b.bottom <= okno.bottom,
        )
        // Cel dotyku: 48 dp w obu osiach (ten sam prog co w TouchTargetAuditTest).
        assertTrue(
            "przycisk \"$opis\" jest mniejszy niz 48 dp: ${b.width} x ${b.height}",
            b.width >= 48.dp && b.height >= 48.dp,
        )
    }

    // ---- koniec zyc --------------------------------------------------------

    @Test
    fun poKoncuZyc_obaPrzyciskiSaWCalosciWOknieIDzialaja() {
        pokazGre()
        grajAz("ekran KONIEC GRY") { jest(str.gameOver) }

        wymagajWCalosciWOknie(str.playAgain)
        wymagajWCalosciWOknie(str.toMenu)

        // Dotkniecie w srodek przycisku ma trafic w przycisk. Pod stara nakladka w tym miejscu
        // lezalo pole odpowiedzi albo klawisz, wiec samo "wezel istnieje" nic by nie dowodzilo.
        rule.onNodeWithContentDescription(str.toMenu).performClick()
        assertEquals("DO MENU nie wywolalo wyjscia", 1, wyjscia)

        rule.onNodeWithContentDescription(str.playAgain).performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertFalse("JESZCZE RAZ nie zaczelo nowej rundy", jest(str.gameOver))
        assertTrue("nowa runda nie ma klawiatury", jest("Q"))
    }

    @Test
    fun przepuszczoneSlowo_stoiNaEkranieWynikuRazemZTlumaczeniem() {
        // Dwa zycia, jedno slowo: to samo haslo dochodzi do dziala dwa razy.
        pokazGre(zycia = 2)
        grajAz("ekran KONIEC GRY") { jest(str.gameOver) }

        rule.onNodeWithContentDescription(str.toReview).assertIsDisplayed()
        rule.onNodeWithText("ache").assertIsDisplayed()
        rule.onNodeWithText("ból").assertIsDisplayed()
        // Haslo, odpowiedz i licznik to JEDEN wiersz, a nie trzy napisy gdzies na ekranie.
        rule.onNode(hasText("ache") and hasText("ból") and hasText("×2")).assertIsDisplayed()
        rule.onNodeWithContentDescription(str.missedTimes(2)).assertIsDisplayed()
        assertFalse("zdanie o pustej liscie obok listy", jestTekst(str.nothingMissed))
    }

    @Test
    fun poKoncuZyc_wynikIdzieDoWolajacegoRaz_aRundaNieJestUkonczona() {
        pokazGre()
        grajAz("ekran KONIEC GRY") { jest(str.gameOver) }
        rule.mainClock.advanceTimeBy(1_000)

        assertEquals(1, rekordy.size)
        assertEquals("koniec zyc to nie jest ukonczona runda", 0, ukonczone)
        assertFalse(jest(str.roundComplete))
    }

    // ---- klawiatura znika --------------------------------------------------

    @Test
    fun naEkranieWyniku_nieMaAniJednegoKlawisza() {
        pokazGre()
        // Kontrola pozytywna: w trakcie rundy klawisze SA. Bez niej test przechodzilby takze
        // wtedy, gdyby te opisy w ogole nie istnialy.
        for (klawisz in klawisze) assertTrue("w trakcie gry brakuje klawisza $klawisz", jest(klawisz))
        assertTrue("w trakcie gry brakuje paska glosu", jestTekst(str.speakAnswer))

        grajAz("ekran KONIEC GRY") { jest(str.gameOver) }

        val zostaly = klawisze.filter { jest(it) }
        assertEquals("klawisze dostepne pod ekranem wyniku", emptyList<String>(), zostaly)
        assertFalse("pasek glosu zostal pod ekranem wyniku", jestTekst(str.speakAnswer))
    }

    // ---- runda jako krok czegos wiekszego ----------------------------------

    @Test
    fun zKontynuacja_jestDalej_aNieMaDoMenu() {
        pokazGre(zKontynuacja = true)
        grajAz("ekran KONIEC GRY") { jest(str.gameOver) }

        wymagajWCalosciWOknie(str.next)
        wymagajWCalosciWOknie(str.playAgain)
        assertFalse("DO MENU obok DALEJ", jest(str.toMenu))

        rule.onNodeWithContentDescription(str.next).performClick()
        assertEquals(1, dalej)
        assertEquals("DALEJ nie jest wyjsciem do menu", 0, wyjscia)
    }

    // ---- runda ukonczona ---------------------------------------------------

    @Test
    fun ukonczonaRunda_maWlasnyEkran_iZglaszaSieRaz() {
        pokazGre(spokojna = true, skonczona = true, zKontynuacja = true)
        // Potworek wychodzi po 0,9 s; odpowiedz pada, gdy juz stoi na planszy.
        rule.mainClock.advanceTimeBy(1_500)
        wpisz("bol")
        grajAz("ekran RUNDA UKONCZONA") { jest(str.roundComplete) }
        rule.mainClock.advanceTimeBy(1_000)

        assertFalse("ukonczona runda pokazala ekran porazki", jest(str.gameOver))
        rule.onNodeWithText(str.nothingMissed).assertIsDisplayed()
        assertFalse("pusta ramka DO POWTORKI", jest(str.toReview))
        wymagajWCalosciWOknie(str.next)
        wymagajWCalosciWOknie(str.playAgain)
        assertEquals(1, ukonczone)
        assertEquals(1, rekordy.size)

        // JESZCZE RAZ: nowa runda tego samego kroku, zgloszona osobno, gdy tez sie skonczy.
        rule.onNodeWithContentDescription(str.playAgain).performClick()
        rule.mainClock.advanceTimeBy(1_500)
        assertFalse(jest(str.roundComplete))
        wpisz("bol")
        grajAz("drugi ekran RUNDA UKONCZONA") { jest(str.roundComplete) }
        assertEquals(2, ukonczone)
        assertEquals(2, rekordy.size)
    }

    @Test
    fun wRundzieSpokojnej_slowoPrzyDziale_pokazujeOdpowiedzINieKonczyRundy() {
        pokazGre(spokojna = true, skonczona = true)

        // Plansza jest rysunkiem, wiec odkryta odpowiedz sprawdzamy po opisie dla czytnika ekranu.
        grajAz("odkryta odpowiedz") { jest("ache: ból") }

        assertFalse("runda spokojna skonczyla sie, bo slowo doszlo do dziala", jest(str.gameOver))
        assertTrue("klawiatura zniknela w trakcie rundy", jest("Q"))
        assertEquals(0, rekordy.size)

        // Odpowiedz gasnie sama po trzech sekundach, a slowo wraca na plansze i da sie je zaliczyc.
        rule.mainClock.advanceTimeBy(3_500)
        assertFalse("odkryta odpowiedz nie zgasla", jest("ache: ból"))
        // Slowo wyszlo ponownie najpozniej 3,8 s po tym, jak doszlo do dziala.
        rule.mainClock.advanceTimeBy(1_000)
        wpisz("bol")
        grajAz("ekran RUNDA UKONCZONA") { jest(str.roundComplete) }

        // Na ukonczonej rundzie lista tez jest: slowo, ktore raz doszlo do dziala.
        rule.onNode(hasText("ache") and hasText("ból")).assertIsDisplayed()
        assertEquals(1, ukonczone)
    }

    // ---- sam uklad, bez gry ------------------------------------------------

    @Test
    fun dlugaLista_przewijaSiePodPrzyciskami_aPrzyciskiZostajaWOknie() {
        val slowa = (1..24).map {
            MissedWord("haslo numer $it", "odpowiedz numer $it", "w$it", Direction.TERM_TO_DEF, 1)
        }
        rule.setContent {
            EkranWyniku(
                ikona = pixel(R.drawable.skull),
                tytul = str.gameOver,
                kolorTytulu = Palette.Danger,
                punkty = 120,
                opisPunktow = str.score + " 120",
                glowny = PrzyciskWyniku(str.playAgain) {},
                drugi = PrzyciskWyniku(str.toMenu) {},
            ) { ListaDoPowtorki(slowa, str) }
        }

        wymagajWCalosciWOknie(str.playAgain)
        wymagajWCalosciWOknie(str.toMenu)
        rule.onNodeWithText("haslo numer 1").assertIsDisplayed()
        // Ostatni wiersz jest ponizej okna, ale da sie do niego dojechac, a przyciski nie uciekaja.
        rule.onNodeWithText("haslo numer 24").performScrollTo().assertIsDisplayed()
        wymagajWCalosciWOknie(str.playAgain)
        wymagajWCalosciWOknie(str.toMenu)
        // Wiersz, do ktorego dojechalismy, konczy sie NAD przyciskami, a nie pod nimi.
        val wiersz = rule.onNodeWithText("haslo numer 24").getUnclippedBoundsInRoot()
        val przycisk = rule.onNodeWithContentDescription(str.playAgain).getUnclippedBoundsInRoot()
        assertTrue("lista wjezdza pod przyciski: $wiersz / $przycisk", wiersz.bottom <= przycisk.top)
    }

    // ---- runda spokojna nie udaje straty -----------------------------------

    @Test
    fun rundaSpokojna_nieRobiZPrzepuszczonegoSlowaStraty() {
        assertTrue(czyStrata(FlashKind.LIFE_LOST, calm = false))
        assertFalse("runda spokojna trzesie plansza i gra ton bledu", czyStrata(FlashKind.LIFE_LOST, calm = true))
        for (inny in FlashKind.values().filter { it != FlashKind.LIFE_LOST }) {
            assertFalse(czyStrata(inny, calm = false))
            assertFalse(czyStrata(inny, calm = true))
        }
        assertFalse(czyStrata(null, calm = false))
    }
}
