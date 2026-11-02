package com.matura.app

import com.matura.app.learn.Attempt
import com.matura.app.learn.DzialFiltr
import com.matura.app.learn.Outcome
import com.matura.app.learn.PowodTrudnosci
import com.matura.app.learn.Srs
import com.matura.app.learn.Trudne
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.TaskKind
import com.matura.app.model.WordProgress
import com.matura.app.model.WordSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ekran "Moje trudne slowa" ma prawo powiedziec uczniowi tylko to, co wynika z zapisanego
 * postepu. Testy pilnuja wiec dwoch rzeczy naraz: ze powod jest WYLICZONY (a nie przypisany
 * na oko) i ze na liste nie wchodzi nic, czego dane nie potwierdzaja.
 *
 * Stany nauki budujemy przez [Srs.applyTo], czyli tak, jak robi to gra. Recznie sklejony
 * [WordProgress] potrafilby opisac uklad pol, ktory w aplikacji nigdy nie powstaje, i test
 * bronilby zachowania, ktorego nikt nigdy nie zobaczy.
 */
class TrudneSlowaTest {

    private companion object {
        /** Dowolny dzien od epoki UTC — ten sam co w SrsTest, zeby liczby dalo sie porownywac. */
        const val DZIS = 19_875

        const val SEPARATOR = '·'
    }

    // --- pomoce -------------------------------------------------------------------------

    /** Historia prob budowana tak, jak buduje ja gra: przez [Srs]. */
    private class Historia {
        var mapa: Map<String, WordProgress> = emptyMap()
            private set

        private var licznik = 0

        fun proba(
            id: String,
            outcome: Outcome,
            dzien: Int,
            kierunek: Direction = Direction.TERM_TO_DEF,
            task: TaskKind = TaskKind.PRZYPOMNIENIE,
        ): Historia {
            licznik++
            mapa = Srs.applyTo(mapa, Attempt(id, kierunek, task, outcome, "$id#$licznik"), dzien)
            return this
        }
    }

    private fun wpis(id: String, term: String, def: String) = Entry(term, def, id)

    private fun zestaw(
        id: String,
        tytul: String,
        wpisy: List<Entry>,
        jezykHasla: String = "English",
        jezykZnaczenia: String = "Polski",
    ) = WordSet(
        id = id,
        title = tytul,
        termLanguage = jezykHasla,
        definitionLanguage = jezykZnaczenia,
        entries = wpisy,
    )

    /** Zestaw z jednym slowem w dziale "Zdrowie". */
    private fun zdrowie(vararg wpisy: Entry) =
        zestaw("z1", "Zdrowie $SEPARATOR U lekarza", wpisy.toList())

    private fun praca(vararg wpisy: Entry) =
        zestaw("z2", "Praca $SEPARATOR Rozmowa o prace", wpisy.toList())

    private fun wlasny(vararg wpisy: Entry) =
        zestaw("z3", "Moja lista z korepetycji", wpisy.toList())

    private val ache = wpis("w000000000001", "ache", "ból")
    private val salary = wpis("w000000000002", "salary", "pensja")
    private val homework = wpis("w000000000003", "homework", "praca domowa")

    // --- powod jest wyliczony -----------------------------------------------------------

    @Test
    fun `slowo bez historii nie ma zadnego powodu`() {
        assertEquals(emptyList<PowodTrudnosci>(), Trudne.powody(WordProgress(), DZIS))
    }

    @Test
    fun `slowo nietkniete nie jest zalegle, choc jego termin to dzien zero`() {
        // WordProgress() ma dueDay = 0, wiec formalnie jest "po terminie" od epoki. Gdyby sama
        // zaleglosc wystarczala, lista trudnych slow pokazalaby caly katalog CKE.
        val czyste = WordProgress()
        assertTrue(Srs.isDue(czyste, DZIS))
        assertTrue(Trudne.powody(czyste, DZIS).isEmpty())
    }

    @Test
    fun `zalegly termin poznanego slowa to ZALEGLA_POWTORKA`() {
        val h = Historia()
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 10)   // pudelko 1, termin DZIS-9
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 3)    // pudelko 2, termin DZIS
        val stan = Srs.progressOf(h.mapa, ache.id, Direction.TERM_TO_DEF)

        assertEquals(2, stan.box)
        assertEquals(DZIS, stan.dueDay)
        assertEquals(listOf(PowodTrudnosci.ZALEGLA_POWTORKA), Trudne.powody(stan, DZIS))
    }

    @Test
    fun `pomylka spycha slowo do pierwszego pudelka i daje COFNIETE_PO_BLEDZIE`() {
        val h = Historia()
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 20)   // pudelko 1
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 15)   // pudelko 2
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 10)   // pudelko 3
            .proba(ache.id, Outcome.BLEDNA, DZIS)          // z powrotem do pudelka 1
        val stan = Srs.progressOf(h.mapa, ache.id, Direction.TERM_TO_DEF)

        assertEquals(Srs.FIRST_BOX, stan.box)
        assertEquals(1, stan.mistakes)
        // Termin ustawiony na dzis, wiec slowo jest i zalegle, i cofniete — kolejnosc powodow
        // mowi, ktory z nich zobaczy uczen na pierwszym planie.
        assertEquals(
            listOf(PowodTrudnosci.ZALEGLA_POWTORKA, PowodTrudnosci.COFNIETE_PO_BLEDZIE),
            Trudne.powody(stan, DZIS),
        )
    }

    @Test
    fun `pomylka w slowie, ktore wrocilo wyzej, to POMYLKI, a nie COFNIETE`() {
        val h = Historia()
            .proba(ache.id, Outcome.BLEDNA, DZIS - 20)     // pudelko 0, pierwsza pomylka
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 10)   // pudelko 1
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 1)    // pudelko 2, termin DZIS+2
        val stan = Srs.progressOf(h.mapa, ache.id, Direction.TERM_TO_DEF)

        assertEquals(2, stan.box)
        assertEquals(DZIS + 2, stan.dueDay)
        assertEquals(listOf(PowodTrudnosci.POMYLKI), Trudne.powody(stan, DZIS))
    }

    @Test
    fun `podpowiedz zostaje w powodach, nawet gdy slowo wrocilo na tory`() {
        val h = Historia()
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 10)   // pudelko 1, termin DZIS-9
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 9)    // pudelko 2, termin DZIS-6
            .proba(ache.id, Outcome.PODPOWIEDZ, DZIS - 6)  // termin wraca na DZIS-6
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 5)    // pudelko 3, termin DZIS+2
        val stan = Srs.progressOf(h.mapa, ache.id, Direction.TERM_TO_DEF)

        assertEquals(Srs.MAX_BOX, stan.box)
        assertEquals(1, stan.hints)
        assertEquals(listOf(PowodTrudnosci.PODPOWIEDZI), Trudne.powody(stan, DZIS))
    }

    @Test
    fun `sama pokazana odpowiedz nie robi ze slowa trudnego`() {
        // Outcome.POKAZANA bierze sie w tej grze z limitu czasu (PRAWDA/FALSZ, NA CZAS), czyli
        // z TEMPA. Zalozenia zabraniaja uznawac slowo za trudne na podstawie samej powolnej
        // reakcji, wiec ten stan nie ma prawa dac zadnego powodu.
        val h = Historia().proba(ache.id, Outcome.POKAZANA, DZIS)
        val stan = Srs.progressOf(h.mapa, ache.id, Direction.TERM_TO_DEF)

        assertEquals(DZIS, stan.assistedDay)
        assertEquals(0, stan.mistakes)
        assertEquals(0, stan.hints)
        assertTrue(Trudne.powody(stan, DZIS).isEmpty())
    }

    @Test
    fun `przerwana proba nie dopisuje slowa do trudnych`() {
        val h = Historia().proba(ache.id, Outcome.PRZERWANA, DZIS)
        val lista = Trudne.zbierz(listOf(zdrowie(ache)), h.mapa, Direction.TERM_TO_DEF, DZIS)
        assertTrue(lista.isEmpty())
    }

    // --- lista ---------------------------------------------------------------------------

    @Test
    fun `pusta historia daje pusta liste, a nie awarie`() {
        val zestawy = listOf(zdrowie(ache), praca(salary), wlasny(homework))
        assertEquals(emptyList<Any>(), Trudne.zbierz(zestawy, emptyMap(), Direction.TERM_TO_DEF, DZIS))
        assertEquals(emptyList<Any>(), Trudne.zbierz(zestawy, emptyMap(), Direction.DEF_TO_TERM, DZIS))
        assertEquals(emptyList<Any>(), Trudne.dzialy(zestawy, emptyList()))
    }

    @Test
    fun `kierunek filtruje - klopot w jedna strone nie oskarza drugiej`() {
        val h = Historia()
            .proba(ache.id, Outcome.BLEDNA, DZIS, Direction.TERM_TO_DEF)
            .proba(ache.id, Outcome.POPRAWNA, DZIS, Direction.DEF_TO_TERM)
        val zestawy = listOf(zdrowie(ache))

        val hasloZnaczenie = Trudne.zbierz(zestawy, h.mapa, Direction.TERM_TO_DEF, DZIS)
        val znaczenieHaslo = Trudne.zbierz(zestawy, h.mapa, Direction.DEF_TO_TERM, DZIS)

        assertEquals(listOf(ache.id), hasloZnaczenie.map { it.entry.id })
        assertTrue("w drugim kierunku slowo idzie dobrze", znaczenieHaslo.isEmpty())
    }

    @Test
    fun `filtr dzialu wybiera zestawy z jednego tematu, a wlasne osobno`() {
        val h = Historia()
            .proba(ache.id, Outcome.BLEDNA, DZIS)
            .proba(salary.id, Outcome.BLEDNA, DZIS)
            .proba(homework.id, Outcome.BLEDNA, DZIS)
        val zestawy = listOf(zdrowie(ache), praca(salary), wlasny(homework))

        fun idsW(dzial: DzialFiltr) =
            Trudne.zbierz(zestawy, h.mapa, Direction.TERM_TO_DEF, DZIS, dzial).map { it.entry.id }

        assertEquals(3, idsW(DzialFiltr.Wszystkie).size)
        assertEquals(listOf(ache.id), idsW(DzialFiltr.Nazwany("Zdrowie")))
        assertEquals(listOf(salary.id), idsW(DzialFiltr.Nazwany("Praca")))
        assertEquals(listOf(homework.id), idsW(DzialFiltr.Wlasne))
        assertEquals(emptyList<String>(), idsW(DzialFiltr.Nazwany("Sport")))
    }

    @Test
    fun `zakladki dzialow pokazuja tylko te, ktore maja co pokazac`() {
        val h = Historia().proba(salary.id, Outcome.BLEDNA, DZIS)
        val zestawy = listOf(zdrowie(ache), praca(salary), wlasny(homework))
        val lista = Trudne.wszystkie(zestawy, h.mapa, Direction.TERM_TO_DEF, DZIS)

        assertEquals(listOf(DzialFiltr.Nazwany("Praca")), Trudne.dzialy(zestawy, lista))
    }

    @Test
    fun `zakladki dzialow ida w kolejnosci katalogu, a nie pilnosci`() {
        val h = Historia()
            .proba(ache.id, Outcome.PODPOWIEDZ, DZIS)      // slabszy powod, dzial pierwszy
            .proba(salary.id, Outcome.BLEDNA, DZIS)        // pilniejszy powod, dzial drugi
        val zestawy = listOf(zdrowie(ache), praca(salary))
        val lista = Trudne.wszystkie(zestawy, h.mapa, Direction.TERM_TO_DEF, DZIS)

        // Na liscie slow "Praca" jest wyzej, ale pasek dzialow ma stac w miejscu miedzy dniami.
        assertEquals(salary.id, lista.first().entry.id)
        assertEquals(
            listOf(DzialFiltr.Nazwany("Zdrowie"), DzialFiltr.Nazwany("Praca")),
            Trudne.dzialy(zestawy, lista),
        )
    }

    // --- kolejnosc -----------------------------------------------------------------------

    @Test
    fun `sortowanie stawia zalegle najwyzej`() {
        val h = Historia()
            // ache: podpowiedz w historii, ale termin dopiero za dwa dni
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 10)
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 9)
            .proba(ache.id, Outcome.PODPOWIEDZ, DZIS - 6)
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 5)
            // salary: nic zlego w historii, ale termin wypada dzis
            .proba(salary.id, Outcome.POPRAWNA, DZIS - 10)
            .proba(salary.id, Outcome.POPRAWNA, DZIS - 3)
        val zestawy = listOf(zdrowie(ache), praca(salary))

        val lista = Trudne.zbierz(zestawy, h.mapa, Direction.TERM_TO_DEF, DZIS)

        assertEquals(listOf(salary.id, ache.id), lista.map { it.entry.id })
        assertEquals(PowodTrudnosci.ZALEGLA_POWTORKA, lista.first().powod)
        assertEquals(PowodTrudnosci.PODPOWIEDZI, lista.last().powod)
    }

    @Test
    fun `wsrod zaleglych pierwsze jest to, ktore czeka najdluzej`() {
        val h = Historia()
            // salary: termin minal dzis
            .proba(salary.id, Outcome.POPRAWNA, DZIS - 10)
            .proba(salary.id, Outcome.POPRAWNA, DZIS - 3)
            // ache: termin minal cztery dni temu
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 20)
            .proba(ache.id, Outcome.POPRAWNA, DZIS - 7)
        val zestawy = listOf(zdrowie(ache), praca(salary))

        val lista = Trudne.zbierz(zestawy, h.mapa, Direction.TERM_TO_DEF, DZIS)

        assertEquals(listOf(ache.id, salary.id), lista.map { it.entry.id })
        assertEquals(4, lista.first().zaleglosc)
        assertEquals(0, lista.last().zaleglosc)
    }

    // --- granice -------------------------------------------------------------------------

    @Test
    fun `to samo slowo w dwoch zestawach pokazuje sie raz`() {
        val h = Historia().proba(ache.id, Outcome.BLEDNA, DZIS)
        val zestawy = listOf(zdrowie(ache), praca(ache, salary))

        val lista = Trudne.zbierz(zestawy, h.mapa, Direction.TERM_TO_DEF, DZIS)

        assertEquals(listOf(ache.id), lista.map { it.entry.id })
        // Zostaje wystapienie z pierwszego zestawu, wiec uczen widzi ten dzial, ktory w
        // katalogu jest pierwszy.
        assertEquals("Zdrowie", lista.first().dzial)
    }

    @Test
    fun `wpis bez identyfikatora nie wywraca listy`() {
        // Identyfikatory nadaje migracja zapisu; do tego czasu wpis nie ma jak miec postepu,
        // a Srs.key rzucilby na nim wyjatkiem w srodku rysowania ekranu.
        val bezId = Entry("blister", "pęcherz")
        val h = Historia().proba(ache.id, Outcome.BLEDNA, DZIS)
        val zestawy = listOf(zdrowie(bezId, ache))

        val lista = Trudne.zbierz(zestawy, h.mapa, Direction.TERM_TO_DEF, DZIS)

        assertEquals(listOf(ache.id), lista.map { it.entry.id })
    }

    @Test
    fun `kierunek losowy jest ustawieniem, wiec lista czyta haslo do znaczenia`() {
        val h = Historia().proba(ache.id, Outcome.BLEDNA, DZIS, Direction.TERM_TO_DEF)
        val zestawy = listOf(zdrowie(ache))

        assertEquals(Direction.TERM_TO_DEF, Trudne.kierunekDoListy(Direction.RANDOM))
        val lista = Trudne.zbierz(zestawy, h.mapa, Direction.RANDOM, DZIS)
        assertEquals(listOf(ache.id), lista.map { it.entry.id })
        assertEquals(Direction.TERM_TO_DEF, lista.first().kierunek)
    }

    @Test
    fun `pytanie i odpowiedz ida za kierunkiem`() {
        val h = Historia()
            .proba(ache.id, Outcome.BLEDNA, DZIS, Direction.TERM_TO_DEF)
            .proba(ache.id, Outcome.BLEDNA, DZIS, Direction.DEF_TO_TERM)
        val zestawy = listOf(zdrowie(ache))

        val wPrzod = Trudne.zbierz(zestawy, h.mapa, Direction.TERM_TO_DEF, DZIS).first()
        val wTyl = Trudne.zbierz(zestawy, h.mapa, Direction.DEF_TO_TERM, DZIS).first()

        assertEquals("ache", wPrzod.pytanie)
        assertEquals("ból", wPrzod.odpowiedz)
        assertEquals("ból", wTyl.pytanie)
        assertEquals("ache", wTyl.odpowiedz)
    }

    @Test
    fun `wymowa bierze strone angielska, nawet gdy zestaw ma zamienione strony`() {
        val h = Historia().proba(ache.id, Outcome.BLEDNA, DZIS)
        val zamieniony = zestaw(
            id = "z9",
            tytul = "Moja lista",
            wpisy = listOf(wpis(ache.id, "ból", "ache")),
            jezykHasla = "Polski",
            jezykZnaczenia = "English",
        )

        val lista = Trudne.zbierz(listOf(zamieniony), h.mapa, Direction.TERM_TO_DEF, DZIS)

        assertEquals("ache", lista.first().doWymowy)
        // Zestaw wbudowany ma strony w naturalnej kolejnosci — tam do wymowy idzie haslo.
        val zwykly = Trudne.zbierz(listOf(zdrowie(ache)), h.mapa, Direction.TERM_TO_DEF, DZIS)
        assertEquals("ache", zwykly.first().doWymowy)
    }

    @Test
    fun `bez angielskiej strony do wymowy idzie haslo, a nie zgadywanie`() {
        val h = Historia().proba(ache.id, Outcome.BLEDNA, DZIS)
        val niemiecki = zestaw(
            id = "z8",
            tytul = "Moja lista niemiecka",
            wpisy = listOf(wpis(ache.id, "der Schmerz", "ból")),
            jezykHasla = "Niemiecki",
            jezykZnaczenia = "Polski",
        )

        val lista = Trudne.zbierz(listOf(niemiecki), h.mapa, Direction.TERM_TO_DEF, DZIS)

        assertEquals("der Schmerz", lista.first().doWymowy)
    }
}
