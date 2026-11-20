package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.game.GameConfig
import com.matura.app.game.GameEngine
import com.matura.app.game.Matching
import com.matura.app.game.SubmitResult
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.WordSet
import com.matura.app.ui.daSieWpisac
import com.matura.app.ui.doRundyPisanej
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * DLACZEGO TEN TEST ISTNIEJE
 *
 * Runda spokojna i skonczona (krok dziennej sesji, cwiczenie trudnych slow) zamyka sie dopiero
 * wtedy, gdy kazde slowo dostanie poprawna odpowiedz. Klawiatura gry ma litery A-Z i spacje,
 * a dopasowanie odpowiedzi zachowuje cyfry i litery spoza swojej tabeli zamian. Uczen, ktory
 * dopisal do wlasnego zestawu "3D printer = drukarka 3D", nie mial wiec czym na to slowo
 * odpowiedziec: wracalo po kazdym pokazaniu tlumaczenia, runda nie konczyla sie nigdy, a ze
 * takie slowo jest codziennie najstarsza zalegloscia, zatykalo pierwszy krok kazdej sesji.
 *
 * Wbudowany slownik takich slow nie ma i ten plik tez tego pilnuje.
 */
class KlawiaturaRundyTest {

    private val druk = Entry("3D printer", "drukarka 3D", "w1")
    private val gaz = Entry("CO2", "dwutlenek węgla (CO2)", "w2")
    private val kolega = Entry("schoolmate", "kolega ze szkoły, koleżanka ze szkoły", "w3")
    private val ulica = Entry("street", "Straße", "w4")

    /** Odpowiedzi przychodza tu znormalizowane, tak jak porownuje je gra. */
    @Test
    fun klawiaturaGryPiszeLiteryISpacje_klawiaturaTelefonuKazdaLitere() {
        assertTrue(daSieWpisac("praca domowa", klawiaturaTelefonu = false))
        assertTrue(daSieWpisac("praca domowa", klawiaturaTelefonu = true))

        // Cyfry nie ma ani na klawiaturze gry, ani w tym, co przepuszcza pole klawiatury telefonu.
        assertFalse(daSieWpisac("drukarka 3d", klawiaturaTelefonu = false))
        assertFalse(daSieWpisac("drukarka 3d", klawiaturaTelefonu = true))

        // Litera spoza A-Z: tylko klawiatura telefonu.
        assertEquals("straße", Matching.normalize("Straße"))
        assertFalse(daSieWpisac("straße", klawiaturaTelefonu = false))
        assertTrue(daSieWpisac("straße", klawiaturaTelefonu = true))

        // Pole wpisu konczy sie na czterdziestu znakach, dla obu klawiatur.
        val naStyk = "a".repeat(GameEngine.MAX_TYPED)
        assertTrue(daSieWpisac(naStyk, klawiaturaTelefonu = false))
        assertFalse(daSieWpisac(naStyk + "a", klawiaturaTelefonu = false))
        assertFalse(daSieWpisac(naStyk + "a", klawiaturaTelefonu = true))

        assertFalse("pustej odpowiedzi gra nie przyjmuje", daSieWpisac("", klawiaturaTelefonu = false))
    }

    /** Wystarcza JEDNA odpowiedz do wpisania, liczona dla strony, o ktora runda pyta. */
    @Test
    fun wpisZostaje_gdyChocJednaOdpowiedzDaSieWpisac() {
        val wpisy = listOf(druk, gaz, kolega, ulica)

        // Dopisek w nawiasie jest opcjonalny, wiec "dwutlenek wegla" wystarcza. "drukarka 3D"
        // nie ma wariantu bez cyfry, a "Straße" potrzebuje klawiatury telefonu.
        assertEquals(listOf(gaz, kolega), doRundyPisanej(wpisy, Direction.TERM_TO_DEF, klawiaturaTelefonu = false))
        assertEquals(listOf(gaz, kolega, ulica), doRundyPisanej(wpisy, Direction.TERM_TO_DEF, klawiaturaTelefonu = true))

        // W druga strone odpowiedzia jest haslo: "CO2" i "3D printer" odpadaja.
        assertEquals(listOf(kolega, ulica), doRundyPisanej(wpisy, Direction.DEF_TO_TERM, klawiaturaTelefonu = false))

        // Kierunek losowy rozstrzyga silnik, dla kazdego wiersza osobno: zostaje to, co da sie
        // wpisac w obie strony.
        assertEquals(listOf(kolega), doRundyPisanej(wpisy, Direction.RANDOM, klawiaturaTelefonu = false))
        assertEquals(listOf(kolega, ulica), doRundyPisanej(wpisy, Direction.RANDOM, klawiaturaTelefonu = true))
    }

    /** Dluga komorka nie przeszkadza, dopoki ma krotki wariant; sama dluga odpowiedz tak. */
    @Test
    fun odpowiedzDluzszaNizPoleWpisu_nieLiczySieJakoMozliwa() {
        val dlugaZWariantem = Entry("x", "bardzo dluga odpowiedz ktora nie miesci sie w polu, krotka", "w1")
        val samaDluga = Entry("y", "bardzo dluga odpowiedz ktora nie miesci sie w polu wpisu", "w2")
        assertEquals(
            listOf(dlugaZWariantem),
            doRundyPisanej(listOf(dlugaZWariantem, samaDluga), Direction.TERM_TO_DEF, klawiaturaTelefonu = false),
        )
    }

    /** Wbudowany slownik przechodzi caly, w obie strony, na klawiaturze gry. */
    @Test
    fun wbudowanySlownik_nieTraciAniJednegoSlowa() {
        for (zestaw in BuiltInSets.all()) {
            for (kierunek in Direction.values()) {
                assertEquals(
                    zestaw.title + ", " + kierunek,
                    zestaw.entries,
                    doRundyPisanej(zestaw.entries, kierunek, klawiaturaTelefonu = false),
                )
            }
        }
    }

    /**
     * To samo na silniku: uczen z klawiatura gry odpowiada na wszystko, na co umie. Z calym
     * zestawem runda spokojna i skonczona nie ma jak sie zamknac; z odsianym zamyka sie sama.
     */
    @Test(timeout = 30_000L)
    fun rundaSkonczona_zOdsianymZestawemDaSieSkonczycKlawiaturaGry() {
        val wpisy = listOf(druk, kolega, Entry("ache", "ból", "w5"))

        fun graj(doRundy: List<Entry>): GameEngine {
            val e = GameEngine(
                set = WordSet(id = "t", title = "moj", entries = doRundy),
                config = GameConfig(calm = true, finite = true),
                random = Random(7),
            )
            var klatki = 0
            // Piec minut gry: bez odsiania slowo z cyfra zdazy wrocic kilkanascie razy.
            while (!e.gameOver && klatki++ < 6_000) {
                e.tick(0.05f)
                val m = e.snapshot().monsters.firstOrNull { it.incoming == 0 } ?: continue
                val odpowiedz = m.answers.firstOrNull { daSieWpisac(it, klawiaturaTelefonu = false) } ?: continue
                e.setTyped(odpowiedz)
                assertTrue(e.submit() is SubmitResult.Fired)
            }
            return e
        }

        val caly = graj(wpisy)
        assertFalse("slowo z cyfra trzyma runde bez konca", caly.completed)
        assertEquals(2, caly.snapshot().targetsDone)
        assertTrue(caly.missedWords.single().times > 3)

        val odsiany = graj(doRundyPisanej(wpisy, Direction.TERM_TO_DEF, klawiaturaTelefonu = false))
        assertTrue("po odsianiu runda konczy sie sama", odsiany.completed)
        assertEquals(2, odsiany.snapshot().targetsTotal)
        assertTrue(odsiany.missedWords.isEmpty())
    }
}
