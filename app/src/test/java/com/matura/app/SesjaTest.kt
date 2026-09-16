package com.matura.app

import com.matura.app.learn.Attempt
import com.matura.app.learn.Clock
import com.matura.app.learn.Outcome
import com.matura.app.learn.PlanSesji
import com.matura.app.learn.RodzajKroku
import com.matura.app.learn.Sesja
import com.matura.app.learn.Srs
import com.matura.app.learn.Stage
import com.matura.app.learn.StanSesji
import com.matura.app.model.Direction
import com.matura.app.model.TaskKind
import com.matura.app.model.WordProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * "Dzisiejsze 5 minut" — trasa dnia.
 *
 * Kazdy test pilnuje JEDNEJ obietnicy zlozonej uczniowi, a nie linii kodu:
 *  - pierwszego dnia dostaje maly zestaw, a nie cala liste;
 *  - zaleglosci maja pierwszenstwo przed nowosciami;
 *  - przerwa nie kasuje nauki i da sie wrocic w to samo miejsce;
 *  - orientacyjne piec minut nigdy nie ucina rozpoczetej odpowiedzi;
 *  - opuszczony dzien nie odbiera ani jednego pudelka.
 *
 * Zwykly JVM: plan i stan sa czystymi danymi, a zegar wchodzi parametrem.
 */
class SesjaTest {

    private companion object {
        /** Dowolny dzien od epoki UTC — jak w SrsTest, zeby czytalo sie "dzis, jutro". */
        const val DZIS = 19_875
        val KIERUNEK = Direction.TERM_TO_DEF
    }

    /** Zegar sterowany recznie; [Clock] jest fun interface, ale potrzebujemy ruchomej chwili. */
    private class Zegar(var ms: Long = 0L) : Clock {
        override fun nowMs(): Long = ms
    }

    private fun slowa(ile: Int, prefiks: String = "w"): List<String> =
        (1..ile).map { prefiks + it.toString().padStart(2, '0') }

    private fun postepy(
        ids: List<String>,
        box: Int,
        dueDay: Int,
    ): Map<String, WordProgress> =
        ids.associate { Srs.key(it, KIERUNEK) to WordProgress(box = box, dueDay = dueDay) }

    /** Poprawna odpowiedz produkcyjna — jedyna, ktora w [Srs] moze awansowac ponad pudelko 1. */
    private fun odpowiedz(
        progress: Map<String, WordProgress>,
        entryId: String,
        dzien: Int,
        outcome: Outcome = Outcome.POPRAWNA,
    ): Map<String, WordProgress> = Srs.applyTo(
        progress,
        Attempt(entryId, KIERUNEK, TaskKind.PRZYPOMNIENIE, outcome, id = "$entryId#$dzien"),
        dzien,
    )

    private fun krok(plan: PlanSesji, rodzaj: RodzajKroku) = plan.kroki.first { it.rodzaj == rodzaj }

    // --- dobor slow ------------------------------------------------------------------------

    @Test
    fun `brak historii daje maly zestaw startowy, a nie caly slownik`() {
        val plan = Sesja.zaplanuj(emptyMap(), slowa(30), KIERUNEK, DZIS)

        assertTrue(plan.bezHistorii)
        assertTrue(plan.powtorki.isEmpty())
        assertEquals(Sesja.MAKS_NOWYCH, plan.nowe.size)
        assertEquals(slowa(30).take(Sesja.MAKS_NOWYCH), plan.nowe)
        assertEquals(
            listOf(RodzajKroku.POWTORKA, RodzajKroku.GRA, RodzajKroku.PODSUMOWANIE),
            plan.kroki.map { it.rodzaj },
        )
        // SPOKOJNY WARIANT: za slowo widziane pierwszy raz nie ma za co karac utrata zycia.
        assertTrue(krok(plan, RodzajKroku.GRA).spokojny)
        assertTrue(krok(plan, RodzajKroku.POWTORKA).spokojny)
    }

    @Test
    fun `same zaleglosci wypelniaja sesje i nie wpuszczaja nowych slow`() {
        val zalegle = slowa(20)
        val progress = postepy(zalegle, box = 1, dueDay = DZIS - 1)

        val plan = Sesja.zaplanuj(progress, zalegle + slowa(4, "n"), KIERUNEK, DZIS)

        assertEquals(Sesja.MAKS_SLOW, plan.powtorki.size)
        assertTrue(plan.nowe.isEmpty())
        assertFalse(plan.bezHistorii)
        // Nie ma nowych slow, wiec runda gry jest zwykla gra — spokojny wariant nie jest
        // nagroda za nic, tylko oslona przy poznawaniu.
        assertFalse(krok(plan, RodzajKroku.GRA).spokojny)
    }

    @Test
    fun `najdawniej przeterminowane slowo idzie na poczatek trasy`() {
        val progress = mapOf(
            Srs.key("w01", KIERUNEK) to WordProgress(box = 3, dueDay = DZIS),
            Srs.key("w02", KIERUNEK) to WordProgress(box = 1, dueDay = DZIS - 10),
            Srs.key("w03", KIERUNEK) to WordProgress(box = 2, dueDay = DZIS - 3),
        )

        val plan = Sesja.zaplanuj(progress, listOf("w01", "w02", "w03"), KIERUNEK, DZIS)

        assertEquals(listOf("w02", "w03", "w01"), plan.powtorki)
    }

    @Test
    fun `slowo z pomylka sprzed chwili jest powtorka, a nie nowym slowem`() {
        // Pudelko wciaz 0, ale historia juz jest. Gdyby liczylo sie jako nowe, podsumowanie
        // meldowaloby "nowe slowo" za kazdym razem, gdy uczen sie na nim potknie.
        val progress = odpowiedz(emptyMap(), "w01", DZIS, Outcome.BLEDNA)

        val plan = Sesja.zaplanuj(progress, listOf("w01", "w02"), KIERUNEK, DZIS)

        assertEquals(listOf("w01"), plan.powtorki)
        assertEquals(listOf("w02"), plan.nowe)
        assertFalse(plan.bezHistorii)
    }

    @Test
    fun `nic zaleglego i nic nowego zostawia sama droge do podsumowania`() {
        val zestaw = slowa(5)
        val progress = postepy(zestaw, box = 2, dueDay = DZIS + 3)

        val plan = Sesja.zaplanuj(progress, zestaw, KIERUNEK, DZIS)

        assertTrue(plan.pusty)
        assertEquals(listOf(RodzajKroku.PODSUMOWANIE), plan.kroki.map { it.rodzaj })
        assertEquals(0, plan.orientacyjneSekundy)
    }

    @Test
    fun `wpis bez identyfikatora nie wywraca sesji, a duplikat nie liczy sie dwa razy`() {
        val plan = Sesja.zaplanuj(emptyMap(), listOf("", "w01", "w01", "w02"), KIERUNEK, DZIS)

        assertEquals(listOf("w01", "w02"), plan.nowe)
    }

    @Test
    fun `kierunek losowy jest odrzucony, tak jak w Srs`() {
        try {
            Sesja.zaplanuj(emptyMap(), slowa(3), Direction.RANDOM, DZIS)
            fail("RANDOM to ustawienie, nie kierunek — plan nie ma prawa powstac")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("RANDOM"))
        }
    }

    // --- czas jest orientacja, nie stoperem -------------------------------------------------

    @Test
    fun `orientacyjny czas wynika z liczby slow i miesci sie w zalozonych pieciu minutach`() {
        val zalegle = slowa(20)
        val plan = Sesja.zaplanuj(postepy(zalegle, 1, DZIS - 1), zalegle, KIERUNEK, DZIS)

        // Kazde slowo przechodzi przez dwa kroki z odpowiedziami: powtorke i runde gry.
        assertEquals(
            plan.slownictwo.size * 2 * Sesja.SEKUND_NA_ODPOWIEDZ,
            plan.orientacyjneSekundy,
        )
        assertTrue(plan.orientacyjneSekundy <= Sesja.ORIENTACYJNE_SEKUNDY)
    }

    @Test
    fun `sesja nie przerywa odpowiedzi po uplywie orientacyjnego czasu`() {
        val zalegle = slowa(6)
        val plan = Sesja.zaplanuj(postepy(zalegle, 1, DZIS - 1), zalegle, KIERUNEK, DZIS)
        val zegar = Zegar(0L)
        val start = Sesja.rozpocznij(plan, zegar)

        // Dziesiec minut ponad szacunek — i to w polowie odpowiedzi.
        zegar.ms = (plan.orientacyjneSekundy + 600).toLong() * 1000L
        val wTrakcie = Sesja.zacznijOdpowiedz(start)

        assertFalse(Sesja.czasNaKoniec(wTrakcie, plan, zegar))
        // Krok i jego cele zostaja nietkniete: nic nie znika ze srodka pytania.
        assertEquals(RodzajKroku.POWTORKA, Sesja.biezacy(wTrakcie, plan)?.rodzaj)
        assertEquals(plan.slownictwo, Sesja.biezacy(wTrakcie, plan)?.cele)

        val po = Sesja.zaliczOdpowiedz(wTrakcie, plan, plan.slownictwo.first(), Outcome.POPRAWNA)

        // Dopiero MIEDZY odpowiedziami wolno zaproponowac koniec — i to nadal tylko propozycja.
        assertTrue(Sesja.czasNaKoniec(po, plan, zegar))
        val dalej = Sesja.dalej(po, plan)
        assertFalse(dalej.zakonczona)
        assertEquals(RodzajKroku.GRA, Sesja.biezacy(dalej, plan)?.rodzaj)
    }

    @Test
    fun `cofniety zegar nie robi z sesji ujemnego czasu`() {
        val plan = Sesja.zaplanuj(emptyMap(), slowa(3), KIERUNEK, DZIS)
        val zegar = Zegar(10_000L)
        val stan = Sesja.rozpocznij(plan, zegar)
        zegar.ms = 0L

        assertEquals(0, Sesja.uplyneloSekund(stan, zegar))
    }

    // --- przerwanie i wznowienie ------------------------------------------------------------

    @Test
    fun `przerwanie i wznowienie zachowuje postep sesji i nauki`() {
        val zalegle = slowa(8)
        var progress = postepy(zalegle, box = 1, dueDay = DZIS - 1)
        val plan = Sesja.zaplanuj(progress, zalegle, KIERUNEK, DZIS)
        val zegar = Zegar(0L)
        var stan = Sesja.rozpocznij(plan, zegar)

        // Trzy slowa przerobione. Nauka idzie do mapy postepu OD RAZU — dokladnie tak, jak
        // Repository.recordAttempt robi to po kazdej odpowiedzi.
        val zrobione = plan.slownictwo.take(3)
        for (id in zrobione) {
            stan = Sesja.zacznijOdpowiedz(stan)
            progress = odpowiedz(progress, id, DZIS)
            stan = Sesja.zaliczOdpowiedz(stan, plan, id, Outcome.POPRAWNA)
        }
        // Czwarte slowo: uczen wychodzi w polowie odpowiedzi.
        stan = Sesja.zacznijOdpowiedz(stan)
        val przerwana = Sesja.przerwij(stan)

        // Awans jest na dysku, a nie w sesji — to on decyduje, ze slowo juz nie zalega.
        for (id in zrobione) {
            assertEquals(2, Srs.progressOf(progress, id, KIERUNEK).box)
        }

        val poPowrocie = Sesja.zaplanuj(progress, zalegle, KIERUNEK, DZIS)
        val wznowiony = Sesja.wznow(przerwana, poPowrocie, zegar)

        assertTrue(Sesja.czyWznawialna(przerwana, DZIS))
        assertEquals(zrobione, wznowiony.zrobionePowtorki)
        assertFalse(wznowiony.odpowiedzWToku)
        assertFalse(wznowiony.zakonczona)
        assertEquals(przerwana.startMs, wznowiony.startMs)
        // Slowa juz zrobione nie wracaja do trasy, bo maja termin w przyszlosci.
        assertTrue(poPowrocie.slownictwo.none { it in zrobione })
        assertEquals(5, poPowrocie.powtorki.size)
        // Postep na ekranie ma sie zgadzac po powrocie.
        assertEquals(3, Sesja.postep(wznowiony, poPowrocie).celeZrobione)
    }

    @Test
    fun `przerwana proba nie liczy sie jako przerobione slowo, bledna liczy sie`() {
        val plan = Sesja.zaplanuj(postepy(slowa(3), 1, DZIS - 1), slowa(3), KIERUNEK, DZIS)
        val stan = Sesja.zacznijOdpowiedz(Sesja.rozpocznij(plan, Zegar(0L)))

        val poPrzerwanej = Sesja.zaliczOdpowiedz(stan, plan, "w01", Outcome.PRZERWANA)
        assertTrue(poPrzerwanej.zrobione.isEmpty())
        assertFalse(poPrzerwanej.odpowiedzWToku)

        // Bledna odpowiedz TO jest przerobione slowo. O tym, czy uczen je umie, mowi pudelko
        // w Srs, a nie ten licznik — sesja liczy prace, nie wiedze.
        val poBlednej = Sesja.zaliczOdpowiedz(stan, plan, "w01", Outcome.BLEDNA)
        assertEquals(listOf("w01"), poBlednej.zrobionePowtorki)
    }

    @Test
    fun `to samo slowo dwa razy w sesji liczy sie raz`() {
        val plan = Sesja.zaplanuj(postepy(slowa(3), 1, DZIS - 1), slowa(3), KIERUNEK, DZIS)
        var stan = Sesja.rozpocznij(plan, Zegar(0L))

        stan = Sesja.zaliczOdpowiedz(stan, plan, "w01", Outcome.BLEDNA)
        stan = Sesja.zaliczOdpowiedz(stan, plan, "w01", Outcome.POPRAWNA)

        assertEquals(listOf("w01"), stan.zrobionePowtorki)
    }

    @Test
    fun `wznowienie po zakonczeniu trasy zaczyna nowa sesje tego samego dnia`() {
        val plan = Sesja.zaplanuj(postepy(slowa(3), 1, DZIS - 1), slowa(3), KIERUNEK, DZIS)
        val zegar = Zegar(0L)
        var stan = Sesja.rozpocznij(plan, zegar)
        repeat(plan.kroki.size) { stan = Sesja.dalej(stan, plan) }

        assertTrue(stan.zakonczona)
        assertNull(Sesja.biezacy(stan, plan))
        assertFalse(Sesja.czyWznawialna(stan, DZIS))

        val nowa = Sesja.wznow(stan, plan, zegar)
        assertEquals(0, nowa.krok)
        assertFalse(nowa.zakonczona)
    }

    // --- opuszczony dzien -------------------------------------------------------------------

    @Test
    fun `opuszczony dzien nie odbiera wiedzy, tylko wydluza kolejke`() {
        val progress = mapOf(
            Srs.key("w01", KIERUNEK) to WordProgress(
                box = Srs.MAX_BOX,
                dueDay = DZIS + 7,
                lastPromotionDay = DZIS,
            ),
        )
        val pozniej = DZIS + 30

        val plan = Sesja.zaplanuj(progress, listOf("w01"), KIERUNEK, pozniej)

        // Slowo wraca do powtorki, ale zostaje UTRWALONE — miesiac przerwy nic nie obnizyl.
        assertEquals(listOf("w01"), plan.powtorki)
        val stan = Srs.progressOf(progress, "w01", KIERUNEK)
        assertEquals(Srs.MAX_BOX, stan.box)
        assertEquals(Stage.UTRWALONE, Srs.stageOf(stan))

        // Trasa sprzed miesiaca nie jest wznawiana, ale tez niczego nie kasuje: nowy dzien
        // to nowa trasa, a pudelka sa poza sesja.
        val stary = StanSesji(dzien = DZIS, krok = 1, zrobionePowtorki = listOf("w01"))
        assertFalse(Sesja.czyWznawialna(stary, pozniej))
        val dzisiejszy = Sesja.wznow(stary, plan, Zegar(0L))
        assertEquals(pozniej, dzisiejszy.dzien)
        assertEquals(0, dzisiejszy.krok)
        assertTrue(dzisiejszy.zrobione.isEmpty())
        assertEquals(Srs.MAX_BOX, Srs.progressOf(progress, "w01", KIERUNEK).box)
    }

    // --- zadania w kontekscie ---------------------------------------------------------------

    @Test
    fun `krok w kontekscie pojawia sie dopiero, gdy zadania naprawde sa`() {
        val bez = Sesja.zaplanuj(emptyMap(), slowa(5), KIERUNEK, DZIS)
        assertFalse(bez.kroki.any { it.rodzaj == RodzajKroku.KONTEKST })

        // Bez zadan sesja przechodzi z rundy gry PROSTO do podsumowania — nie ma udawanego
        // kroku, ktory niczego nie sprawdza.
        var stan = Sesja.rozpocznij(bez, Zegar(0L))
        stan = Sesja.dalej(stan, bez)
        stan = Sesja.dalej(stan, bez)
        assertEquals(RodzajKroku.PODSUMOWANIE, Sesja.biezacy(stan, bez)?.rodzaj)

        val z = Sesja.zaplanuj(
            emptyMap(),
            slowa(5),
            KIERUNEK,
            DZIS,
            zadaniaWKontekscie = listOf("z1", "z2"),
        )
        assertEquals(
            listOf(
                RodzajKroku.POWTORKA,
                RodzajKroku.GRA,
                RodzajKroku.KONTEKST,
                RodzajKroku.PODSUMOWANIE,
            ),
            z.kroki.map { it.rodzaj },
        )
        assertEquals(listOf("z1", "z2"), krok(z, RodzajKroku.KONTEKST).cele)
    }

    // --- podsumowanie -----------------------------------------------------------------------

    @Test
    fun `podsumowanie liczy to, co bylo, i termin bierze z harmonogramu`() {
        val zalegle = slowa(3)
        var progress = postepy(zalegle, box = 1, dueDay = DZIS - 1)
        val plan = Sesja.zaplanuj(progress, zalegle, KIERUNEK, DZIS)
        var stan = Sesja.rozpocznij(plan, Zegar(0L))

        for (id in plan.slownictwo.take(2)) {
            progress = odpowiedz(progress, id, DZIS)
            stan = Sesja.zaliczOdpowiedz(stan, plan, id, Outcome.POPRAWNA)
        }

        val pods = Sesja.podsumuj(plan, stan, progress, DZIS)

        assertEquals(2, pods.powtorzone)
        assertEquals(0, pods.nowe)
        assertEquals(3, pods.zaplanowane)
        // Awans z pudelka 1 do 2 daje odstep trzech dni — liczba pochodzi z Srs, nie z ekranu.
        assertEquals(Srs.intervalDays(2), pods.nastepnaPowtorkaZaDni)
        // Trzecie slowo zostalo zalegle, wiec jutro nadal czeka.
        assertEquals(1, pods.naJutro)
    }

    @Test
    fun `po przerobieniu calej trasy licznik i podsumowanie nie gubia tego, co uczen zrobil`() {
        // Plan powstaje na nowo z harmonogramu przy kazdym wejsciu na ekran, a slowo
        // odpowiedziane poprawnie nie jest juz zalegle i z planu wypada. Liczac z samego planu,
        // uczen po przerobieniu calej trasy zobaczylby "3 / 0" i podsumowanie bez terminu
        // nastepnej powtorki — mimo ze to jego odpowiedzi ten termin wlasnie ustawily.
        val zalegle = slowa(3)
        var progress = postepy(zalegle, box = 1, dueDay = DZIS - 1)
        val plan = Sesja.zaplanuj(progress, zalegle, KIERUNEK, DZIS)
        var stan = Sesja.rozpocznij(plan, Zegar(0L))
        for (id in plan.slownictwo) {
            progress = odpowiedz(progress, id, DZIS)
            stan = Sesja.zaliczOdpowiedz(stan, plan, id, Outcome.POPRAWNA)
        }

        val poPowrocie = Sesja.zaplanuj(progress, zalegle, KIERUNEK, DZIS)
        assertTrue("slowa zrobione nie moga wrocic do trasy", poPowrocie.pusty)

        val postep = Sesja.postep(stan, poPowrocie)
        assertEquals(3, postep.celeZrobione)
        assertEquals(3, postep.celeRazem)

        val pods = Sesja.podsumuj(poPowrocie, stan, progress, DZIS)
        assertEquals(3, pods.powtorzone)
        assertEquals(3, pods.zaplanowane)
        // Awans z pudelka 1 do 2 daje odstep trzech dni — termin bierze sie z Srs.
        assertEquals(Srs.intervalDays(2), pods.nastepnaPowtorkaZaDni)
        assertEquals(0, pods.naJutro)
    }

    @Test
    fun `podsumowanie pustej sesji nie zapowiada powtorki, ktorej nie ma`() {
        val zestaw = slowa(4)
        val progress = postepy(zestaw, box = 3, dueDay = DZIS + 7)
        val plan = Sesja.zaplanuj(progress, zestaw, KIERUNEK, DZIS)
        val stan = Sesja.rozpocznij(plan, Zegar(0L))

        val pods = Sesja.podsumuj(plan, stan, progress, DZIS)

        assertEquals(0, pods.zaplanowane)
        assertEquals(0, pods.powtorzone)
        // Puste slownictwo nie ma terminow, wiec nie ma czego zapowiadac — null, a nie zero.
        assertNull(pods.nastepnaPowtorkaZaDni)
        assertEquals(0, pods.naJutro)
    }

    @Test
    fun `nowe slowo poznane w sesji liczy sie jako nowe, a nie jako powtorka`() {
        val plan = Sesja.zaplanuj(emptyMap(), slowa(3), KIERUNEK, DZIS)
        var stan = Sesja.rozpocznij(plan, Zegar(0L))
        val progress = odpowiedz(emptyMap(), plan.nowe.first(), DZIS)

        stan = Sesja.zaliczOdpowiedz(stan, plan, plan.nowe.first(), Outcome.POPRAWNA)
        val pods = Sesja.podsumuj(plan, stan, progress, DZIS)

        assertEquals(1, pods.nowe)
        assertEquals(0, pods.powtorzone)
        // Pierwsza nauka to awans 0 -> 1, czyli powrot jutro.
        assertEquals(Srs.intervalDays(Srs.FIRST_BOX), pods.nastepnaPowtorkaZaDni)
    }
}
