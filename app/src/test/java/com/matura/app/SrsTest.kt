package com.matura.app

import com.matura.app.learn.Attempt
import com.matura.app.learn.Clock
import com.matura.app.learn.Outcome
import com.matura.app.learn.Srs
import com.matura.app.learn.Stage
import com.matura.app.model.Direction
import com.matura.app.model.TaskKind
import com.matura.app.model.WordProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Harmonogram powtorek. Kazdy test odpowiada jednej regule z zalozen — nie chodzi o pokrycie
 * linii, tylko o to, zeby aplikacja nie mowila uczniowi nieprawdy o tym, co umie.
 *
 * Zegar jest wstrzykiwany, wiec polnoc, dluga przerwa i zmiana strefy sa zwyklymi liczbami,
 * a nie czekaniem na kalendarz.
 */
class SrsTest {

    private companion object {
        /** Dowolny dzien od epoki UTC; wybrany raz, zeby testy czytalo sie jako "dzis, jutro". */
        const val DZIS = 19_875
        const val WPIS = "w0123456789ab"
    }

    private fun proba(
        outcome: Outcome,
        task: TaskKind = TaskKind.PRZYPOMNIENIE,
        id: String = "",
    ) = Attempt(WPIS, Direction.TERM_TO_DEF, task, outcome, id)

    // --- doba UTC ------------------------------------------------------------------------

    @Test
    fun `doba lamie sie o polnocy UTC, tak jak na stronie matura uk`() {
        val polnoc = DZIS.toLong() * Srs.MS_PER_DAY
        assertEquals(DZIS, Srs.dayOf(polnoc))
        assertEquals(DZIS - 1, Srs.dayOf(polnoc - 1))
        assertEquals(DZIS, Srs.dayOf(polnoc + Srs.MS_PER_DAY - 1))
        // Zegar telefonu potrafi cofnac sie przed epoke - dzielenie z obcinaniem do zera
        // zlewaloby wtedy dwa dni w jeden.
        assertEquals(-1, Srs.dayOf(-1))
        assertEquals(-1, Srs.dayOf(-Srs.MS_PER_DAY))
        assertEquals(-2, Srs.dayOf(-Srs.MS_PER_DAY - 1))
    }

    @Test
    fun `nauka po polnocy czasu polskiego liczy sie jeszcze do wczorajszej doby`() {
        // Skutek uboczny zgodnosci z WWW, ktory interfejs ma nazwac wprost: granica doby
        // wypada o 02:00 czasu polskiego (lato), wiec 23-30 i 00-30 to TEN SAM dzien nauki.
        val poczatekDoby = DZIS.toLong() * Srs.MS_PER_DAY
        val wieczorem = poczatekDoby + 21 * 3_600_000L + 30 * 60_000L // 23:30 w Polsce
        val poPolnocy = poczatekDoby + 22 * 3_600_000L + 30 * 60_000L // 00:30 w Polsce

        assertEquals(DZIS, Srs.dayOf(wieczorem))
        assertEquals(DZIS, Srs.dayOf(poPolnocy))
    }

    @Test
    fun `zmiana strefy czasowej nie rusza harmonogramu`() {
        val chwila = DZIS.toLong() * Srs.MS_PER_DAY + 12 * 3_600_000L
        val domyslna = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")) // UTC+14
            val naWschodzie = Srs.apply(WordProgress(), proba(Outcome.POPRAWNA), Srs.dayOf(chwila))
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Niue")) // UTC-11
            val naZachodzie = Srs.apply(WordProgress(), proba(Outcome.POPRAWNA), Srs.dayOf(chwila))
            assertEquals(naWschodzie, naZachodzie)
        } finally {
            TimeZone.setDefault(domyslna)
        }
    }

    @Test
    fun `zegar wchodzi interfejsem, wiec test podaje dowolna chwile`() {
        val zegar = Clock { DZIS.toLong() * Srs.MS_PER_DAY + 1 }
        assertEquals(DZIS, Srs.dayOf(zegar))
    }

    // --- trzy rozne rzeczy: nauka, poprawka, sprawdzenie ---------------------------------

    @Test
    fun `pierwsza nauka daje pudelko pierwsze i termin na jutro`() {
        val po = Srs.apply(WordProgress(), proba(Outcome.POPRAWNA), DZIS)

        assertEquals(1, po.box)
        assertEquals(DZIS + 1, po.dueDay)
        assertEquals(DZIS, po.lastPromotionDay)
        assertEquals(Stage.POZNANE, Srs.stageOf(po))
        assertEquals(TaskKind.PRZYPOMNIENIE, po.lastTask)
    }

    @Test
    fun `poprawka po bledzie w tej samej sesji nie awansuje`() {
        // Slowo awansowalo WCZORAJ i dzis jest zalegle - bez tego blokade dalby juz sam
        // licznik awansow na dzis i test nie sprawdzalby tego, o co chodzi.
        val zaczyna = WordProgress(box = 1, dueDay = DZIS, lastPromotionDay = DZIS - 1)

        val poBledzie = Srs.apply(zaczyna, proba(Outcome.BLEDNA), DZIS)
        val poPoprawce = Srs.apply(poBledzie, proba(Outcome.POPRAWNA), DZIS)

        assertEquals("poprawka po bledzie to nie dowod pamieci", 1, poPoprawce.box)
        assertEquals(DZIS, poPoprawce.dueDay)
        assertEquals(DZIS - 1, poPoprawce.lastPromotionDay)
        assertEquals(1, poPoprawce.mistakes)
    }

    @Test
    fun `sprawdzenie w pozniejszym terminie awansuje`() {
        val poNauce = Srs.apply(WordProgress(), proba(Outcome.POPRAWNA), DZIS)
        val poPowtorce = Srs.apply(poNauce, proba(Outcome.POPRAWNA), DZIS + 1)

        assertEquals(2, poPowtorce.box)
        assertEquals(DZIS + 1 + 3, poPowtorce.dueDay)
        assertEquals(Stage.POWTARZANE, Srs.stageOf(poPowtorce))
    }

    @Test
    fun `jedna kwalifikujaca powtorka awansuje najwyzej raz dziennie`() {
        // Stan spreparowany tak, ze slowo jest zalegle, a awans tego dnia juz byl.
        val juzAwansowane = WordProgress(box = 1, dueDay = DZIS, lastPromotionDay = DZIS)

        val po = Srs.apply(juzAwansowane, proba(Outcome.POPRAWNA), DZIS)

        assertEquals(juzAwansowane.box, po.box)
        assertEquals(juzAwansowane.dueDay, po.dueDay)
    }

    @Test
    fun `kolejne trafienia tego samego dnia nic nie przesuwaja`() {
        var stan = Srs.apply(WordProgress(), proba(Outcome.POPRAWNA, id = "p0"), DZIS)
        val poPierwszym = stan
        repeat(9) { i -> stan = Srs.apply(stan, proba(Outcome.POPRAWNA, id = "p${i + 1}"), DZIS) }

        assertEquals(poPierwszym.box, stan.box)
        assertEquals(poPierwszym.dueDay, stan.dueDay)
    }

    @Test
    fun `powtorzenie po pokazanej odpowiedzi nie awansuje`() {
        val zalegle = WordProgress(box = 1, dueDay = DZIS, lastPromotionDay = DZIS - 1)

        val poPokazaniu = Srs.apply(zalegle, proba(Outcome.POKAZANA), DZIS)
        val poPowtorzeniu = Srs.apply(poPokazaniu, proba(Outcome.POPRAWNA), DZIS)

        assertEquals("przepisanie widocznego slowa to nie pamiec", 1, poPowtorzeniu.box)
        assertEquals(DZIS, poPowtorzeniu.dueDay)
        // Cwiczenie nie jest tez karane: pudelko i liczniki zostaja nietkniete.
        assertEquals(0, poPowtorzeniu.mistakes)
        assertEquals(0, poPowtorzeniu.hints)
    }

    // --- potkniecia -----------------------------------------------------------------------

    @Test
    fun `pomylka cofa do pierwszego pudelka i przyspiesza termin`() {
        val utrwalone = WordProgress(box = 3, dueDay = DZIS + 5, lastPromotionDay = DZIS - 2)

        val po = Srs.apply(utrwalone, proba(Outcome.BLEDNA), DZIS)

        assertEquals(1, po.box)
        assertEquals("slowo ma wrocic do kolejki jeszcze w tej sesji", DZIS, po.dueDay)
        assertEquals(1, po.mistakes)
    }

    @Test
    fun `pomylka nie awansuje slowa bez historii do pierwszego pudelka`() {
        val po = Srs.apply(WordProgress(), proba(Outcome.BLEDNA), DZIS)

        assertEquals("powrot do pudelka 1 dotyczy tych, ktore z niego wyszly", 0, po.box)
        assertEquals(Stage.BRAK_DANYCH, Srs.stageOf(po))
    }

    @Test
    fun `podpowiedz nie awansuje i przyspiesza termin`() {
        val zalegle = WordProgress(box = 2, dueDay = DZIS, lastPromotionDay = DZIS - 3)

        val po = Srs.apply(zalegle, proba(Outcome.PODPOWIEDZ), DZIS)

        assertEquals("podpowiedz nie cofa - uczen jednak odpowiedzial", 2, po.box)
        assertEquals(DZIS, po.dueDay)
        assertEquals(1, po.hints)
        assertEquals(DZIS - 3, po.lastPromotionDay)
    }

    @Test
    fun `blad techniczny, anulowanie i brak mowy nie zmieniaja stanu`() {
        val stan = WordProgress(
            box = 2,
            dueDay = DZIS + 2,
            lastPromotionDay = DZIS - 1,
            mistakes = 4,
            hints = 2,
            lastTask = TaskKind.ROZPOZNANIE,
            lastAttemptId = "wczesniejsza",
        )

        val po = Srs.apply(stan, proba(Outcome.PRZERWANA, id = "nowa"), DZIS)

        assertEquals("przerwana proba nie ma prawa ruszyc niczego", stan, po)
    }

    // --- rodzaj zadania -------------------------------------------------------------------

    @Test
    fun `rozpoznanie nie podnosi slowa wyzej niz pierwsze pudelko`() {
        var stan = Srs.apply(WordProgress(), proba(Outcome.POPRAWNA, TaskKind.ROZPOZNANIE), DZIS)
        assertEquals(1, stan.box)

        // Kolejne dni, same ABCD - i tak nie wyjdzie ponad pudelko 1.
        for (dzien in DZIS + 1..DZIS + 10) {
            stan = Srs.apply(stan, proba(Outcome.POPRAWNA, TaskKind.ROZPOZNANIE), dzien)
        }

        assertEquals("wybor z podanych to nie samodzielne przypomnienie", 1, stan.box)
        assertEquals(TaskKind.ROZPOZNANIE, stan.lastTask)
    }

    @Test
    fun `rozpoznanie na suficie przesuwa termin, zeby slowo nie zostalo zalegle na zawsze`() {
        val zalegle = WordProgress(box = 1, dueDay = DZIS, lastPromotionDay = DZIS - 1)

        val po = Srs.apply(zalegle, proba(Outcome.POPRAWNA, TaskKind.ROZPOZNANIE), DZIS)

        assertEquals(1, po.box)
        assertEquals(DZIS + 1, po.dueDay)
    }

    @Test
    fun `rozpoznanie nie cofa slowa, ktore juz jest utrwalone`() {
        val utrwalone = WordProgress(box = 3, dueDay = DZIS, lastPromotionDay = DZIS - 7)

        val po = Srs.apply(utrwalone, proba(Outcome.POPRAWNA, TaskKind.ROZPOZNANIE), DZIS)

        assertEquals(3, po.box)
        assertEquals(DZIS + 7, po.dueDay)
    }

    // --- utrwalenie -----------------------------------------------------------------------

    @Test
    fun `utrwalone wymaga trzech roznych dni i dwoch samodzielnych przypomnien`() {
        var stan = Srs.apply(WordProgress(), proba(Outcome.POPRAWNA), DZIS)
        assertEquals(Stage.POZNANE, Srs.stageOf(stan))

        stan = Srs.apply(stan, proba(Outcome.POPRAWNA), stan.dueDay)
        assertEquals(Stage.POWTARZANE, Srs.stageOf(stan))

        stan = Srs.apply(stan, proba(Outcome.POPRAWNA), stan.dueDay)
        assertEquals(Stage.UTRWALONE, Srs.stageOf(stan))
        assertEquals(DZIS + 1 + 3 + 7, stan.dueDay)
    }

    @Test
    fun `seria klikniec jednego dnia nie utrwala slowa`() {
        var stan = WordProgress()
        repeat(50) { i ->
            stan = Srs.apply(stan, proba(Outcome.POPRAWNA, id = "proba$i"), DZIS)
        }
        assertEquals(Stage.POZNANE, Srs.stageOf(stan))
        assertNotEquals(Stage.UTRWALONE, Srs.stageOf(stan))
    }

    @Test
    fun `dluga przerwa nie kasuje pudelka, slowo wraca zalegle`() {
        val poRoku = WordProgress(box = 3, dueDay = DZIS - 400, lastPromotionDay = DZIS - 407)
        assertTrue(Srs.isDue(poRoku, DZIS))

        val po = Srs.apply(poRoku, proba(Outcome.POPRAWNA), DZIS)

        assertEquals("przerwa w nauce nie jest bledem ucznia", 3, po.box)
        assertEquals(DZIS + 7, po.dueDay)
    }

    // --- powtorne przetworzenie tej samej proby -------------------------------------------

    @Test
    fun `ta sama proba przetworzona dwa razy daje jeden awans`() {
        val proba = proba(Outcome.POPRAWNA, id = "sesja7-proba3")

        val raz = Srs.apply(WordProgress(), proba, DZIS)
        val dwa = Srs.apply(raz, proba, DZIS)

        assertEquals(raz, dwa)
        assertEquals(1, dwa.box)
    }

    @Test
    fun `powtorzona pomylka nie liczy sie dwa razy`() {
        val proba = proba(Outcome.BLEDNA, id = "sesja7-proba4")
        val stan = WordProgress(box = 2, dueDay = DZIS, lastPromotionDay = DZIS - 3)

        val raz = Srs.apply(stan, proba, DZIS)
        val dwa = Srs.apply(raz, proba, DZIS)

        assertEquals(1, dwa.mistakes)
        assertEquals(raz, dwa)
    }

    // --- mapa postepu ---------------------------------------------------------------------

    @Test
    fun `kierunek jest czescia klucza, wiec kazdy ma wlasny postep`() {
        var mapa = emptyMap<String, WordProgress>()
        mapa = Srs.applyTo(mapa, proba(Outcome.POPRAWNA), DZIS)
        mapa = Srs.applyTo(
            mapa,
            Attempt(WPIS, Direction.DEF_TO_TERM, TaskKind.PRZYPOMNIENIE, Outcome.POPRAWNA),
            DZIS,
        )

        assertEquals(2, mapa.size)
        assertEquals(1, Srs.progressOf(mapa, WPIS, Direction.TERM_TO_DEF).box)
        assertEquals(1, Srs.progressOf(mapa, WPIS, Direction.DEF_TO_TERM).box)
    }

    @Test
    fun `RANDOM to ustawienie, a nie kierunek postepu`() {
        var zlapane = false
        try {
            Srs.key(WPIS, Direction.RANDOM)
        } catch (e: IllegalArgumentException) {
            zlapane = true
        }
        assertTrue("klucz z Direction.RANDOM musi byc odrzucony", zlapane)
    }

    @Test
    fun `proba, ktora nic nie zmienia, nie tworzy nowej mapy`() {
        // Dzieki temu Repository nie rusza dysku po kazdej przerwanej probie glosowej.
        val mapa = mapOf(
            Srs.key(WPIS, Direction.TERM_TO_DEF) to WordProgress(box = 2, dueDay = DZIS + 1),
        )
        val po = Srs.applyTo(mapa, proba(Outcome.PRZERWANA), DZIS)

        assertSame(mapa, po)
    }

    @Test
    fun `slowo bez historii jest zalegle od razu`() {
        assertTrue(Srs.isDue(WordProgress(), DZIS))
        assertFalse(Srs.isDue(WordProgress(box = 1, dueDay = DZIS + 1), DZIS))
        assertEquals(
            listOf("a", "b"),
            Srs.dueEntryIds(emptyMap(), listOf("a", "b"), Direction.TERM_TO_DEF, DZIS),
        )
    }

    @Test
    fun `odstepy to jeden, trzy i siedem dni`() {
        assertEquals(0, Srs.intervalDays(0))
        assertEquals(1, Srs.intervalDays(1))
        assertEquals(3, Srs.intervalDays(2))
        assertEquals(7, Srs.intervalDays(3))
    }
}
