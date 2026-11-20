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
import com.matura.app.model.SaveData
import com.matura.app.model.TaskKind
import com.matura.app.model.WordProgress
import kotlinx.serialization.json.Json
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

    // --- trasa dnia jest zamrozona ----------------------------------------------------------

    /** Uczen odpowiada poprawnie na [ids]: nauka idzie do mapy postepu, slad do stanu sesji. */
    private fun przerob(
        ids: List<String>,
        plan: PlanSesji,
        stan: StanSesji,
        progress: Map<String, WordProgress>,
    ): Pair<StanSesji, Map<String, WordProgress>> {
        var s = stan
        var p = progress
        for (id in ids) {
            p = odpowiedz(p, id, DZIS)
            s = Sesja.zaliczOdpowiedz(s, plan, id, Outcome.POPRAWNA)
        }
        return s to p
    }

    @Test
    fun `po pierwszym kroku trasa dalej ma te same piec slow, a licznik domyka sie na 5 z 5`() {
        // Slownik wiekszy niz jedna sesja: to on ujawnial blad. Slowa odpowiedziane poprawnie
        // nie sa juz zalegle, wiec plan ukladany od nowa wpuszczal na ich miejsce piec kolejnych.
        val slownik = slowa(30)
        val plan = Sesja.planNaDzis(null, emptyMap(), slownik, KIERUNEK, DZIS)
        val pierwszaPiatka = slownik.take(Sesja.MAKS_NOWYCH)
        assertEquals(pierwszaPiatka, plan.nowe)

        val (stan, progress) = przerob(plan.nowe, plan, Sesja.rozpocznij(plan, Zegar(0L)), emptyMap())

        // Kontrola: stara droga naprawde zmieniala trase. Bez tej asercji test przechodzilby
        // takze wtedy, gdyby harmonogram w ogole nie ruszal sie po odpowiedziach.
        val poStaremu = Sesja.zaplanuj(progress, slownik, KIERUNEK, DZIS)
        assertEquals(slownik.subList(5, 10), poStaremu.nowe)

        val poPowrocie = Sesja.planNaDzis(stan, progress, slownik, KIERUNEK, DZIS)

        assertEquals(pierwszaPiatka, poPowrocie.nowe)
        assertTrue(poPowrocie.powtorki.isEmpty())
        assertEquals(pierwszaPiatka, krok(poPowrocie, RodzajKroku.POWTORKA).cele)
        // "To samo slownictwo jeszcze raz" ma byc tym samym slownictwem.
        assertEquals(pierwszaPiatka, krok(poPowrocie, RodzajKroku.GRA).cele)
        assertFalse("szoste slowo weszlo na trase", "w06" in poPowrocie.slownictwo)
        assertEquals(
            listOf(RodzajKroku.POWTORKA, RodzajKroku.GRA, RodzajKroku.PODSUMOWANIE),
            poPowrocie.kroki.map { it.rodzaj },
        )
        // Na trasie sa nowe slowa, wiec runda gry zostaje spokojna takze po powrocie.
        assertTrue(krok(poPowrocie, RodzajKroku.GRA).spokojny)

        val postep = Sesja.postep(Sesja.wznow(stan, poPowrocie, Zegar(0L)), poPowrocie)
        assertEquals(5, postep.celeZrobione)
        assertEquals(5, postep.celeRazem)

        val pods = Sesja.podsumuj(poPowrocie, stan, progress, DZIS)
        assertEquals(5, pods.nowe)
        assertEquals(5, pods.zaplanowane)
    }

    @Test
    fun `trasa zostaje ta sama takze w drugim kroku i po kolejnych odpowiedziach`() {
        val slownik = slowa(30)
        val plan0 = Sesja.planNaDzis(null, emptyMap(), slownik, KIERUNEK, DZIS)
        var (stan, progress) = przerob(plan0.nowe, plan0, Sesja.rozpocznij(plan0, Zegar(0L)), emptyMap())

        val plan1 = Sesja.planNaDzis(stan, progress, slownik, KIERUNEK, DZIS)
        stan = Sesja.poUkonczonejRundzie(stan, plan1, 0)
        // Druga runda: te same slowa jeszcze raz. Awansu juz dzis nie bedzie, ale trasa stoi.
        val poDrugiej = przerob(plan1.slownictwo, plan1, stan, progress)
        stan = Sesja.poUkonczonejRundzie(poDrugiej.first, plan1, 1)
        progress = poDrugiej.second

        val plan2 = Sesja.planNaDzis(stan, progress, slownik, KIERUNEK, DZIS)

        assertEquals(plan1, plan2)
        assertEquals(RodzajKroku.PODSUMOWANIE, Sesja.biezacy(stan, plan2)?.rodzaj)
        assertEquals(5, Sesja.postep(stan, plan2).celeRazem)
    }

    @Test
    fun `pierwszy dzien zostaje pierwszym dniem takze po pierwszej rundzie`() {
        val slownik = slowa(30)
        val plan = Sesja.planNaDzis(null, emptyMap(), slownik, KIERUNEK, DZIS)
        assertTrue(plan.bezHistorii)
        val (stan, progress) = przerob(plan.nowe, plan, Sesja.rozpocznij(plan, Zegar(0L)), emptyMap())

        // Slowa trasy maja juz historie, bo uczen wlasnie na nie odpowiedzial. Naglowek ekranu
        // nie moze od tego przestac mowic, ze to pierwszy dzien z tym slownictwem.
        assertTrue(Sesja.planNaDzis(stan, progress, slownik, KIERUNEK, DZIS).bezHistorii)

        // Historia slowa SPOZA trasy to juz co innego: uczen gral tym slownictwem poza sesja.
        val zInnaGra = odpowiedz(progress, "w20", DZIS)
        assertFalse(Sesja.planNaDzis(stan, zInnaGra, slownik, KIERUNEK, DZIS).bezHistorii)
    }

    @Test
    fun `stan z innego dnia nie trzyma trasy - dzis plan powstaje na swiezo`() {
        val slownik = slowa(30)
        val wczoraj = DZIS - 1
        val planWczoraj = Sesja.planNaDzis(null, emptyMap(), slownik, KIERUNEK, wczoraj)
        var stan = Sesja.rozpocznij(planWczoraj, Zegar(0L))
        var progress: Map<String, WordProgress> = emptyMap()
        for (id in planWczoraj.nowe.take(2)) {
            progress = odpowiedz(progress, id, wczoraj)
            stan = Sesja.zaliczOdpowiedz(stan, planWczoraj, id, Outcome.POPRAWNA)
        }

        val dzis = Sesja.planNaDzis(stan, progress, slownik, KIERUNEK, DZIS)

        assertEquals(Sesja.zaplanuj(progress, slownik, KIERUNEK, DZIS), dzis)
        // Dwa wczorajsze slowa wracaja dzis jako powtorki, trzy nietkniete nie maja pierwszenstwa
        // tylko dlatego, ze staly na wczorajszej trasie: dzien bez dokonczenia niczego nie rezerwuje.
        assertEquals(listOf("w01", "w02"), dzis.powtorki)
        assertEquals(DZIS, dzis.dzien)
        // A stan z wczoraj po prostu ustepuje nowej sesji, bez kary i bez sladu.
        val nowy = Sesja.wznow(stan, dzis, Zegar(7L))
        assertEquals(DZIS, nowy.dzien)
        assertEquals(0, nowy.krok)
        assertTrue(nowy.zrobione.isEmpty())
        assertEquals(dzis.powtorki, nowy.trasaPowtorki)
        assertEquals(dzis.nowe, nowy.trasaNowe)
    }

    @Test
    fun `zakonczona sesja nie trzyma trasy - nastepna tego samego dnia jest nowa`() {
        val slownik = slowa(30)
        val plan = Sesja.planNaDzis(null, emptyMap(), slownik, KIERUNEK, DZIS)
        val (stan, progress) = przerob(plan.nowe, plan, Sesja.rozpocznij(plan, Zegar(0L)), emptyMap())
        val zakonczona = Sesja.zakoncz(stan, plan)

        val nastepna = Sesja.planNaDzis(zakonczona, progress, slownik, KIERUNEK, DZIS)

        assertEquals(Sesja.zaplanuj(progress, slownik, KIERUNEK, DZIS), nastepna)
        assertEquals(slownik.subList(5, 10), nastepna.nowe)
    }

    @Test
    fun `stan bez trasy ze starszej wersji dostaje plan na swiezo i trase przy wznowieniu`() {
        // Tak wyglada zapis sprzed wprowadzenia trasy: ten sam dzien, krok i zrobione slowa sa,
        // list trasy nie ma (wartosci domyslne).
        val slownik = slowa(30)
        var progress: Map<String, WordProgress> = emptyMap()
        for (id in slownik.take(5)) progress = odpowiedz(progress, id, DZIS)
        val stary = StanSesji(dzien = DZIS, krok = 1, zrobioneNowe = slownik.take(5), startMs = 99L)
        assertTrue(stary.trasaPowtorki.isEmpty() && stary.trasaNowe.isEmpty())

        val plan = Sesja.planNaDzis(stary, progress, slownik, KIERUNEK, DZIS)
        assertEquals(Sesja.zaplanuj(progress, slownik, KIERUNEK, DZIS), plan)

        val wznowiony = Sesja.wznow(stary, plan, Zegar(0L))
        // Miejsce w trasie, zrobione slowa i chwila startu zostaja; dochodzi sama trasa.
        assertEquals(1, wznowiony.krok)
        assertEquals(slownik.take(5), wznowiony.zrobioneNowe)
        assertEquals(99L, wznowiony.startMs)
        assertEquals(plan.powtorki, wznowiony.trasaPowtorki)
        assertEquals(plan.nowe, wznowiony.trasaNowe)
        assertEquals(plan.kierunek, wznowiony.trasaKierunek)

        // Od tej chwili trasa stoi, cokolwiek uczen odpowie.
        val (poRundzie, poOdpowiedziach) = przerob(plan.nowe, plan, wznowiony, progress)
        assertEquals(plan, Sesja.planNaDzis(poRundzie, poOdpowiedziach, slownik, KIERUNEK, DZIS))
        // Slowa zrobione przed aktualizacja licza sie dalej, wiec licznik sie domyka.
        val postep = Sesja.postep(poRundzie, plan)
        assertEquals(postep.celeRazem, postep.celeZrobione)
    }

    @Test
    fun `stary zapis stanu sesji bez pol trasy dalej sie wczytuje`() {
        // Dokladnie te pola, ktore StanSesji mial przed wprowadzeniem trasy.
        val staryJson = """{"dzien":19875,"krok":1,"zrobionePowtorki":["w01"],""" +
            """"zrobioneNowe":["w07","w08"],"startMs":1234,"odpowiedzWToku":false,"zakonczona":false}"""
        // Ta sama konfiguracja co w Repository.
        val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

        val stan = json.decodeFromString(StanSesji.serializer(), staryJson)

        assertEquals(
            StanSesji(
                dzien = DZIS,
                krok = 1,
                zrobionePowtorki = listOf("w01"),
                zrobioneNowe = listOf("w07", "w08"),
                startMs = 1234L,
            ),
            stan,
        )
        assertTrue(stan.trasaPowtorki.isEmpty())
        assertTrue(stan.trasaNowe.isEmpty())

        // To samo w calym dokumencie zapisu, bo tak ten stan lezy na dysku.
        val dokument = json.decodeFromString(SaveData.serializer(), """{"schemaVersion":3,"sesja":$staryJson}""")
        assertEquals(stan, dokument.sesja)

        // A nowy stan przechodzi przez zapis i odczyt razem z trasa.
        val zTrasa = stan.copy(
            trasaPowtorki = listOf("w01"),
            trasaNowe = listOf("w07", "w08"),
            trasaKierunek = Direction.DEF_TO_TERM,
        )
        assertEquals(zTrasa, json.decodeFromString(StanSesji.serializer(), json.encodeToString(StanSesji.serializer(), zTrasa)))
    }

    @Test
    fun `trasa trzyma kierunek, w ktorym ja ulozono, takze po zmianie ustawienia`() {
        val slownik = slowa(10)
        val plan = Sesja.planNaDzis(null, emptyMap(), slownik, Direction.DEF_TO_TERM, DZIS)
        val stan = Sesja.zaliczOdpowiedz(Sesja.rozpocznij(plan, Zegar(0L)), plan, "w01", Outcome.POPRAWNA)
        assertEquals(Direction.DEF_TO_TERM, stan.trasaKierunek)

        // Uczen przestawil kierunek w opcjach w polowie sesji. Licznik, runda i podsumowanie
        // maja dalej mowic o tych samych slowach w tym samym kierunku.
        val poZmianie = Sesja.planNaDzis(stan, emptyMap(), slownik, Direction.TERM_TO_DEF, DZIS)

        assertEquals(Direction.DEF_TO_TERM, poZmianie.kierunek)
        assertEquals(plan.nowe, poZmianie.nowe)
    }

    @Test
    fun `slowo usuniete ze slownika wypada z trasy, zamiast blokowac licznik`() {
        val slownik = slowa(10)
        val plan = Sesja.planNaDzis(null, emptyMap(), slownik, KIERUNEK, DZIS)
        val (stan, progress) = przerob(listOf("w01"), plan, Sesja.rozpocznij(plan, Zegar(0L)), emptyMap())

        // Uczen usunal wlasny zestaw ze slowami w03 i w05: zadna runda juz ich nie pokaze.
        val bezDwoch = slownik - listOf("w03", "w05")
        val poUsunieciu = Sesja.planNaDzis(stan, progress, bezDwoch, KIERUNEK, DZIS)

        assertEquals(listOf("w01", "w02", "w04"), poUsunieciu.nowe)
        assertEquals(3, Sesja.postep(stan, poUsunieciu).celeRazem)

        // Gdy z trasy nie zostalo nic, plan powstaje na swiezo z tego, co jest.
        val inny = slowa(4, "x")
        assertEquals(
            Sesja.zaplanuj(progress, inny, KIERUNEK, DZIS),
            Sesja.planNaDzis(stan, progress, inny, KIERUNEK, DZIS),
        )
    }

    @Test
    fun `trasa z kierunkiem losowym w zapisie nie wywraca ekranu sesji`() {
        // RANDOM to ustawienie, nie kierunek, wiec w trasie moze sie wziac tylko z uszkodzonego
        // pliku. Srs.key rzuca na nim wyjatkiem, a plan sklada sie w trakcie rysowania ekranu.
        val slownik = slowa(10)
        val plan = Sesja.planNaDzis(null, emptyMap(), slownik, KIERUNEK, DZIS)
        val zepsuty = Sesja.zaliczOdpowiedz(Sesja.rozpocznij(plan, Zegar(0L)), plan, "w01", Outcome.POPRAWNA)
            .copy(trasaKierunek = Direction.RANDOM)

        val odzyskany = Sesja.planNaDzis(zepsuty, emptyMap(), slownik, KIERUNEK, DZIS)

        assertEquals(Sesja.zaplanuj(emptyMap(), slownik, KIERUNEK, DZIS), odzyskany)
        // Wznowienie naprawia stan: dostaje trase i kierunek z planu.
        assertEquals(KIERUNEK, Sesja.wznow(zepsuty, odzyskany, Zegar(0L)).trasaKierunek)
    }

    @Test
    fun `nowa sesja zapamietuje trase w kolejnosci planu`() {
        val zalegle = slowa(3)
        val progress = postepy(zalegle, box = 1, dueDay = DZIS - 1)
        val plan = Sesja.zaplanuj(progress, zalegle + slowa(4, "n"), KIERUNEK, DZIS)

        val stan = Sesja.rozpocznij(plan, Zegar(5L))

        assertEquals(plan.powtorki, stan.trasaPowtorki)
        assertEquals(plan.nowe, stan.trasaNowe)
        assertEquals(KIERUNEK, stan.trasaKierunek)
        // Plan odtworzony z zapisu jest tym samym planem, a nie podobnym.
        assertEquals(plan, Sesja.planNaDzis(stan, progress, zalegle + slowa(4, "n"), KIERUNEK, DZIS))
    }

    // --- co wolno nazwac wznowiona sesja ----------------------------------------------------

    @Test
    fun `sesja tylko otwarta nie jest sesja do wznowienia`() {
        val plan = Sesja.zaplanuj(emptyMap(), slowa(8), KIERUNEK, DZIS)
        val swieza = Sesja.rozpocznij(plan, Zegar(0L))

        assertFalse(Sesja.czyRuszona(swieza))
        assertFalse("plakietka WZNOWIONA nad sesja, w ktorej nic sie nie wydarzylo", Sesja.czyWznawialna(swieza, DZIS))

        // Wystarczy jedna odpowiedz albo jedno przejscie dalej.
        val poOdpowiedzi = Sesja.zaliczOdpowiedz(swieza, plan, "w01", Outcome.BLEDNA)
        assertTrue(Sesja.czyRuszona(poOdpowiedzi))
        assertTrue(Sesja.czyWznawialna(poOdpowiedzi, DZIS))
        val poKroku = Sesja.dalej(swieza, plan)
        assertTrue(Sesja.czyRuszona(poKroku))
        assertTrue(Sesja.czyWznawialna(poKroku, DZIS))
        // Proba przerwana to nie jest nic zrobionego.
        assertFalse(Sesja.czyRuszona(Sesja.zaliczOdpowiedz(swieza, plan, "w01", Outcome.PRZERWANA)))
    }

    @Test
    fun `pusta trasa nie ma czasu, ktory moglby minac`() {
        val zestaw = slowa(4)
        val plan = Sesja.zaplanuj(postepy(zestaw, box = 3, dueDay = DZIS + 7), zestaw, KIERUNEK, DZIS)
        assertTrue(plan.pusty)
        val zegar = Zegar(0L)
        val stan = Sesja.rozpocznij(plan, zegar)

        assertFalse(Sesja.czasNaKoniec(stan, plan, zegar))
        zegar.ms = 3_600_000L
        assertFalse(Sesja.czasNaKoniec(stan, plan, zegar))
    }

    // --- ukonczona runda sama przesuwa trase ------------------------------------------------

    @Test
    fun `ukonczona runda przesuwa trase o jeden krok`() {
        val plan = Sesja.zaplanuj(emptyMap(), slowa(8), KIERUNEK, DZIS)
        val stan = Sesja.rozpocznij(plan, Zegar(0L))

        val po = Sesja.poUkonczonejRundzie(stan, plan, 0)

        assertEquals(1, po.krok)
        assertEquals(RodzajKroku.GRA, Sesja.biezacy(po, plan)?.rodzaj)
        assertFalse(po.zakonczona)
    }

    @Test
    fun `po ostatniej rundzie stoi podsumowanie, a sesja nie konczy sie sama`() {
        val plan = Sesja.zaplanuj(emptyMap(), slowa(8), KIERUNEK, DZIS)
        var stan = Sesja.rozpocznij(plan, Zegar(0L))
        for (id in plan.nowe) stan = Sesja.zaliczOdpowiedz(stan, plan, id, Outcome.POPRAWNA)
        stan = Sesja.poUkonczonejRundzie(stan, plan, 0)
        stan = Sesja.zacznijOdpowiedz(stan)

        val po = Sesja.poUkonczonejRundzie(stan, plan, 1)

        assertEquals(2, po.krok)
        assertEquals(RodzajKroku.PODSUMOWANIE, Sesja.biezacy(po, plan)?.rodzaj)
        // Podsumowanie zamyka uczen, przyciskiem. Sesja zakonczona za jego plecami zniknelaby
        // z zapisu, zanim zobaczyl, co zrobil.
        assertFalse(po.zakonczona)
        assertEquals(plan.nowe, po.zrobioneNowe)
        assertTrue(po.zrobionePowtorki.isEmpty())
        assertFalse(po.odpowiedzWToku)
        assertEquals(stan.trasaNowe, po.trasaNowe)
    }

    @Test
    fun `druga ukonczona runda tego samego kroku niczego juz nie przesuwa`() {
        val plan = Sesja.zaplanuj(emptyMap(), slowa(8), KIERUNEK, DZIS)
        val poPierwszej = Sesja.poUkonczonejRundzie(Sesja.rozpocznij(plan, Zegar(0L)), plan, 0)

        // JESZCZE RAZ na ekranie wyniku: ta sama runda kroku 0 ukonczona drugi raz.
        assertEquals(poPierwszej, Sesja.poUkonczonejRundzie(poPierwszej, plan, 0))

        // I w druga strone: uczen przeszedl dalej reka, zanim runda sie skonczyla.
        val recznie = Sesja.dalej(Sesja.dalej(Sesja.rozpocznij(plan, Zegar(0L)), plan), plan)
        assertEquals(recznie, Sesja.poUkonczonejRundzie(recznie, plan, 0))
        assertEquals(recznie, Sesja.poUkonczonejRundzie(recznie, plan, 1))
        // Numer kroku spoza planu to tez nie jest powod, zeby cokolwiek ruszac.
        val start = Sesja.rozpocznij(plan, Zegar(0L))
        assertEquals(start, Sesja.poUkonczonejRundzie(start, plan, 7))
        assertEquals(start, Sesja.poUkonczonejRundzie(start, plan, -1))
    }

    @Test
    fun `na podsumowaniu i po zakonczeniu ukonczona runda nie zmienia niczego`() {
        val plan = Sesja.zaplanuj(emptyMap(), slowa(8), KIERUNEK, DZIS)
        val naPodsumowaniu = Sesja.dalej(Sesja.dalej(Sesja.rozpocznij(plan, Zegar(0L)), plan), plan)
        assertEquals(RodzajKroku.PODSUMOWANIE, Sesja.biezacy(naPodsumowaniu, plan)?.rodzaj)

        // Podsumowanie nie jest runda, wiec nie ma czego konczyc.
        assertEquals(naPodsumowaniu, Sesja.poUkonczonejRundzie(naPodsumowaniu, plan, 2))

        val zakonczona = Sesja.zakoncz(naPodsumowaniu, plan)
        assertEquals(zakonczona, Sesja.poUkonczonejRundzie(zakonczona, plan, 2))

        // Stan z innego dnia niz plan nie nalezy do tej trasy.
        val wczorajszy = Sesja.rozpocznij(plan, Zegar(0L)).copy(dzien = DZIS - 1)
        assertEquals(wczorajszy, Sesja.poUkonczonejRundzie(wczorajszy, plan, 0))
    }

    @Test
    fun `krok ze zdaniami w kontekscie nie jest runda i nie przesuwa sie sam`() {
        val plan = Sesja.zaplanuj(emptyMap(), slowa(5), KIERUNEK, DZIS, zadaniaWKontekscie = listOf("z1"))
        val naKontekscie = Sesja.dalej(Sesja.dalej(Sesja.rozpocznij(plan, Zegar(0L)), plan), plan)
        assertEquals(RodzajKroku.KONTEKST, Sesja.biezacy(naKontekscie, plan)?.rodzaj)

        assertEquals(naKontekscie, Sesja.poUkonczonejRundzie(naKontekscie, plan, 2))
        // Runda przed nim przesuwa sie normalnie, tez gdy za nia stoi kontekst, a nie podsumowanie.
        val naGrze = Sesja.dalej(Sesja.rozpocznij(plan, Zegar(0L)), plan)
        assertEquals(2, Sesja.poUkonczonejRundzie(naGrze, plan, 1).krok)
    }
}
