package com.matura.app

import com.matura.app.learn.Outcome
import com.matura.app.learn.Srs
import com.matura.app.learn.Stage
import com.matura.app.model.Direction
import com.matura.app.model.TaskKind
import com.matura.app.model.WordProgress
import com.matura.app.ui.TaWynik
import com.matura.app.ui.TfOutcome
import com.matura.app.ui.probaAbcd
import com.matura.app.ui.probaPrawdaFalsz
import com.matura.app.ui.probaWpisania
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DLACZEGO TEN TEST ISTNIEJE
 *
 * Sufit awansu dla ROZPOZNANIA siedzi w Srs i pilnuje go SrsTest — ale dziala tylko wtedy, gdy
 * tryb gry poda PRAWDZIWY rodzaj zadania. Jedna pomylka w jednym ekranie (ABCD zapisane jako
 * PRZYPOMNIENIE) i aplikacja zaczyna twierdzic, ze uczen "utrwalil" slowo, ktorego nigdy nie
 * napisal, bo przy czterech kaflach zgadywanie trafia co czwarty raz. Sprawdzamy wiec nie
 * regule, tylko to, co kazdy z trzech trybow do niej wklada.
 *
 * Da sie to zrobic bez Compose, bo kazdy z ekranow trzyma klasyfikacje w czystej funkcji
 * (probaAbcd, probaPrawdaFalsz, probaWpisania) — to jest jedyny powod, dla ktorego te funkcje
 * stoja osobno, a nie w srodku obslugi klikniecia.
 */
class RodzajZadaniaTest {

    private companion object {
        /** Dowolny dzien od epoki UTC; wybrany raz, zeby testy czytalo sie jako "dzis". */
        const val DZIS = 19_875
        const val WPIS = "w0123456789ab"
    }

    /**
     * Chwila w obrebie dnia [dzien]. Sluzy wylacznie za znacznik jednorazowosci proby: dwie
     * proby z tym samym znacznikiem Srs celowo uznaje za te sama i drugiej nie liczy.
     */
    private fun chwila(dzien: Int): Long = dzien.toLong() * Srs.MS_PER_DAY + 10 * 3_600_000L

    // --- rodzaj zadania per tryb -----------------------------------------------------------

    @Test
    fun `kazdy tryb podaje swoj rodzaj zadania`() {
        // ABCD i prawda-falsz: uczen WYBIERA sposrod podanych. Na czas: uczen WPISUJE.
        val abcd = requireNotNull(
            probaAbcd(entryId = WPIS, termToDef = true, wybor = 1, poprawny = 1, nowMs = 1)
        )
        val prawdaFalsz = requireNotNull(
            probaPrawdaFalsz(entryId = WPIS, termToDef = true, wynik = TfOutcome.RIGHT, nowMs = 1)
        )
        val naCzas = requireNotNull(
            probaWpisania(entryId = WPIS, termToDef = true, wynik = TaWynik.TRAFIONE, nowMs = 1)
        )

        assertEquals(TaskKind.ROZPOZNANIE, abcd.task)
        assertEquals(TaskKind.ROZPOZNANIE, prawdaFalsz.task)
        assertEquals(TaskKind.PRZYPOMNIENIE, naCzas.task)
    }

    // --- sufit rozpoznania -----------------------------------------------------------------

    @Test
    fun `trafienie w ABCD nie wynosi slowa ponad pierwsze pudelko`() {
        // Siedem trafien w siedmiu roznych tygodniach — wiecej, niz uczen zrobi w praktyce.
        var stan = WordProgress()
        var dzien = DZIS
        repeat(7) {
            val proba = probaAbcd(
                entryId = WPIS,
                termToDef = true,
                wybor = 2,
                poprawny = 2,
                nowMs = chwila(dzien),
            )
            stan = Srs.apply(stan, requireNotNull(proba), dzien)
            dzien += 7
        }

        assertEquals(TaskKind.ROZPOZNANIE, stan.lastTask)
        assertEquals(Srs.RECOGNITION_CEILING, stan.box)
        assertEquals(Stage.POZNANE, Srs.stageOf(stan))
    }

    @Test
    fun `prawda-falsz tez jest rozpoznaniem i tez konczy na pierwszym pudelku`() {
        // Przy dwoch przyciskach zgadywanie trafia co drugi raz, wiec sufit jest tu tym bardziej
        // na miejscu — ekran ma go dostac przez rodzaj zadania, a nie przez osobna regule.
        var stan = WordProgress()
        var dzien = DZIS
        repeat(4) {
            val proba = probaPrawdaFalsz(
                entryId = WPIS,
                termToDef = true,
                wynik = TfOutcome.RIGHT,
                nowMs = chwila(dzien),
            )
            stan = Srs.apply(stan, requireNotNull(proba), dzien)
            dzien += 7
        }

        assertEquals(TaskKind.ROZPOZNANIE, stan.lastTask)
        assertEquals(Srs.RECOGNITION_CEILING, stan.box)
    }

    // --- wpisywanie awansuje normalnie -----------------------------------------------------

    @Test
    fun `wpisanie odpowiedzi awansuje normalnie az do utrwalenia`() {
        var stan = WordProgress()
        var dzien = DZIS
        val pudelka = mutableListOf<Int>()
        repeat(3) {
            val proba = probaWpisania(
                entryId = WPIS,
                termToDef = true,
                wynik = TaWynik.TRAFIONE,
                nowMs = chwila(dzien),
            )
            stan = Srs.apply(stan, requireNotNull(proba), dzien)
            pudelka += stan.box
            dzien += 7
        }

        assertEquals(listOf(1, 2, 3), pudelka)
        assertEquals(TaskKind.PRZYPOMNIENIE, stan.lastTask)
        assertEquals(Stage.UTRWALONE, Srs.stageOf(stan))
    }

    @Test
    fun `pomylka we wpisywaniu cofa slowo do pierwszego pudelka`() {
        val utrwalone = WordProgress(box = 3, dueDay = DZIS, lastTask = TaskKind.PRZYPOMNIENIE)
        val proba = requireNotNull(
            probaWpisania(
                entryId = WPIS,
                termToDef = true,
                wynik = TaWynik.POMYLKA,
                nowMs = chwila(DZIS),
            )
        )

        assertEquals(Outcome.BLEDNA, proba.outcome)
        val po = Srs.apply(utrwalone, proba, DZIS)
        assertEquals(Srs.FIRST_BOX, po.box)
        assertEquals(1, po.mistakes)
    }

    // --- brak odpowiedzi to nie jest bledna odpowiedz ---------------------------------------

    @Test
    fun `zejscie zegara w ABCD nie jest bledna odpowiedzia ucznia`() {
        // Ekran zabiera za to zycie, bo to gra. Harmonogram widzi co innego: uczen nie powiedzial
        // o tym slowie nic, wiec pudelko zostaje, a licznik pomylek sie nie rusza.
        val utrwalone = WordProgress(
            box = 3,
            dueDay = DZIS,
            lastPromotionDay = DZIS - 7,
            lastTask = TaskKind.PRZYPOMNIENIE,
        )
        val proba = requireNotNull(
            probaAbcd(
                entryId = WPIS,
                termToDef = true,
                wybor = null,
                poprawny = 1,
                nowMs = chwila(DZIS),
            )
        )

        assertEquals(Outcome.POKAZANA, proba.outcome)
        val po = Srs.apply(utrwalone, proba, DZIS)
        assertEquals(3, po.box)
        assertEquals(0, po.mistakes)
        // Kafel z poprawna odpowiedzia byl jednak odsloniety, wiec dzien jest "z pomoca".
        assertEquals(DZIS, po.assistedDay)
    }

    @Test
    fun `pominiecie w trybie na czas nie pozwala awansowac przepisanej odpowiedzi`() {
        val poPominieciu = Srs.apply(
            WordProgress(),
            requireNotNull(
                probaWpisania(
                    entryId = WPIS,
                    termToDef = true,
                    wynik = TaWynik.POMINIETE,
                    nowMs = chwila(DZIS),
                )
            ),
            DZIS,
        )
        assertEquals(Srs.NO_BOX, poPominieciu.box)

        // Pula wraca po kilkunastu sekundach, a poprawna odpowiedz wisiala wlasnie na ekranie.
        val poWpisaniu = Srs.apply(
            poPominieciu,
            requireNotNull(
                probaWpisania(
                    entryId = WPIS,
                    termToDef = true,
                    wynik = TaWynik.TRAFIONE,
                    nowMs = chwila(DZIS) + 15_000L,
                )
            ),
            DZIS,
        )
        assertEquals(
            "odpowiedz przepisana ze swiezo pokazanej podpowiedzi nie jest dowodem pamieci",
            Srs.NO_BOX,
            poWpisaniu.box,
        )
    }

    // --- kierunek ----------------------------------------------------------------------------

    @Test
    fun `historia w jedna strone nie daje opanowania w druga`() {
        var mapa = emptyMap<String, WordProgress>()
        var dzien = DZIS
        repeat(3) {
            val proba = probaWpisania(
                entryId = WPIS,
                termToDef = true,
                wynik = TaWynik.TRAFIONE,
                nowMs = chwila(dzien),
            )
            mapa = Srs.applyTo(mapa, requireNotNull(proba), dzien)
            dzien += 7
        }

        assertEquals(1, mapa.size)
        assertEquals(Stage.UTRWALONE, Srs.stageOf(Srs.progressOf(mapa, WPIS, Direction.TERM_TO_DEF)))
        assertEquals(
            "kto umie 'mature' -> 'dojrzaly', nie musi umiec tego w druga strone",
            Stage.BRAK_DANYCH,
            Srs.stageOf(Srs.progressOf(mapa, WPIS, Direction.DEF_TO_TERM)),
        )
    }

    @Test
    fun `kierunek bierze sie ze strony pytania i nigdy nie jest losowy`() {
        // Direction.RANDOM to ustawienie, a nie kierunek — Srs.key odrzuca go wyjatkiem, wiec
        // gdyby ktorykolwiek tryb podal go dalej, gra wywracalaby sie przy zapisie postepu.
        val proby = listOf(
            requireNotNull(
                probaAbcd(entryId = WPIS, termToDef = true, wybor = 0, poprawny = 0, nowMs = 1)
            ),
            requireNotNull(
                probaAbcd(entryId = WPIS, termToDef = false, wybor = 0, poprawny = 0, nowMs = 2)
            ),
            requireNotNull(
                probaPrawdaFalsz(entryId = WPIS, termToDef = true, wynik = TfOutcome.RIGHT, nowMs = 3)
            ),
            requireNotNull(
                probaPrawdaFalsz(entryId = WPIS, termToDef = false, wynik = TfOutcome.WRONG, nowMs = 4)
            ),
            requireNotNull(
                probaWpisania(entryId = WPIS, termToDef = true, wynik = TaWynik.TRAFIONE, nowMs = 5)
            ),
            requireNotNull(
                probaWpisania(entryId = WPIS, termToDef = false, wynik = TaWynik.POMYLKA, nowMs = 6)
            ),
        )

        assertTrue(proby.none { it.direction == Direction.RANDOM })
        assertEquals(Direction.TERM_TO_DEF, proby[0].direction)
        assertEquals(Direction.DEF_TO_TERM, proby[1].direction)
        // Klucz musi dac sie zlozyc dla kazdej proby — inaczej Srs.key rzuci wyjatkiem.
        proby.forEach { Srs.key(it.entryId, it.direction) }
    }

    // --- stary zapis nie ma prawa wywrocic gry ------------------------------------------------

    @Test
    fun `wpis bez identyfikatora nie trafia do harmonogramu`() {
        // Zestaw sprzed wersjonowania dostaje identyfikatory dopiero w migracji zapisu. Zanim
        // to sie stanie, tryb ma po prostu nic nie zapisac: Srs.key rzuca dla pustego
        // identyfikatora, a wyjatek w srodku rozgrywki bylby najgorsza z mozliwych reakcji.
        assertNull(probaAbcd(entryId = "", termToDef = true, wybor = 1, poprawny = 1, nowMs = 1))
        assertNull(
            probaPrawdaFalsz(entryId = "", termToDef = true, wynik = TfOutcome.RIGHT, nowMs = 1)
        )
        assertNull(
            probaWpisania(entryId = "", termToDef = true, wynik = TaWynik.TRAFIONE, nowMs = 1)
        )
    }

    @Test
    fun `ta sama odpowiedz przetworzona dwa razy daje jeden awans`() {
        // Znacznik jednorazowosci sklada sie z identyfikatora wpisu i chwili, wiec dwa zapisy
        // TEJ SAMEJ odpowiedzi (ponowienie, powrot na ekran) nie moga awansowac slowa dwa razy.
        val proba = requireNotNull(
            probaWpisania(
                entryId = WPIS,
                termToDef = true,
                wynik = TaWynik.TRAFIONE,
                nowMs = chwila(DZIS),
            )
        )
        val raz = Srs.apply(WordProgress(), proba, DZIS)
        val dwa = Srs.apply(raz, proba, DZIS)

        assertEquals(Srs.FIRST_BOX, raz.box)
        assertEquals(raz, dwa)
    }
}
