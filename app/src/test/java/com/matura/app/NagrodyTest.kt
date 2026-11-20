package com.matura.app

import com.matura.app.data.Repository
import com.matura.app.learn.Attempt
import com.matura.app.learn.NagrodaRundy
import com.matura.app.learn.Nagrody
import com.matura.app.learn.Odznaka
import com.matura.app.learn.Odznaki
import com.matura.app.learn.Outcome
import com.matura.app.learn.Ranga
import com.matura.app.learn.Srs
import com.matura.app.learn.Zaliczenie
import com.matura.app.model.Direction
import com.matura.app.model.Dorobek
import com.matura.app.model.Entry
import com.matura.app.model.GameMode
import com.matura.app.model.Options
import com.matura.app.model.SaveData
import com.matura.app.model.ScoreRecord
import com.matura.app.model.TaskKind
import com.matura.app.model.WordProgress
import com.matura.app.model.WordSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * Punkty nauki, cel dnia, seria i szczeble: wszystko, co ksiega dorobku mowi uczniowi.
 *
 * Tak jak w PodsumowanieTest, stany slow NIE sa wpisywane recznie tam, gdzie da sie je policzyc:
 * przejscia robi [Srs.apply], a test pyta, co za nie nalezy. Caly sens ksiegi polega na tym, ze
 * nie da sie w niej zarobic niczego, czego harmonogram nie uznal, wiec gdyby te dwa miejsca
 * kiedys sie rozjechaly, ma to wyjsc tutaj, a nie na ekranie z punktami.
 */
class NagrodyTest {

    /** Tylko dla jednego testu na koncu pliku: odtworzenie rundy kontra prawdziwy zapis. */
    @get:Rule
    val katalog = TemporaryFolder()

    private companion object {
        /** Dowolny dzien od epoki UTC; jeden na caly plik, zeby testy czytalo sie jak kalendarz. */
        const val DZIS = 19_875
        const val WPIS = "w0123456789ab"
        const val CEL = 5
        const val NIGDY = WordProgress.NO_DAY
    }

    private fun proba(
        outcome: Outcome = Outcome.POPRAWNA,
        task: TaskKind = TaskKind.PRZYPOMNIENIE,
        entryId: String = WPIS,
        direction: Direction = Direction.TERM_TO_DEF,
        id: String = "",
    ) = Attempt(entryId, direction, task, outcome, id)

    /** Jedna proba przez harmonogram: stan po niej i to, co ksiega za nia nalicza. */
    private fun krok(
        przed: WordProgress,
        proba: Attempt,
        dzien: Int,
    ): Pair<WordProgress, Zaliczenie?> {
        val po = Srs.apply(przed, proba, dzien)
        return po to Nagrody.zaliczenie(przed, po, proba)
    }

    /** Slowo doprowadzone do pudelka [pudelko] samymi przypomnieniami, kazde w swoim terminie. */
    private fun wPudelku(pudelko: Int): WordProgress {
        var stan = WordProgress()
        repeat(pudelko) { stan = Srs.apply(stan, proba(), stan.dueDay.coerceAtLeast(DZIS - 30)) }
        assertEquals(pudelko, stan.box)
        return stan
    }

    private fun kilka(d: Dorobek, ile: Int, z: Zaliczenie, dzien: Int, cel: Int = CEL): Dorobek {
        var ksiega = d
        repeat(ile) { ksiega = Nagrody.dolicz(ksiega, z, dzien, cel) }
        return ksiega
    }

    // --- tabela zaliczen: kazdy wiersz ------------------------------------------------------

    @Test
    fun `tabela punktow jest taka, jak w zalozeniach`() {
        assertEquals(
            listOf(
                Triple(Zaliczenie.ROZPOZNANE, 2, false),
                Triple(Zaliczenie.NOWE, 10, true),
                Triple(Zaliczenie.AWANS, 10, true),
                Triple(Zaliczenie.UTRWALENIE, 20, true),
                Triple(Zaliczenie.POWTORKA, 5, true),
            ),
            Zaliczenie.entries.map { Triple(it, it.xp, it.zPamieci) },
        )
    }

    @Test
    fun `rozpoznanie nowego slowa jest warte dwa punkty i nie jest dowodem pamieci`() {
        val (po, zaliczenie) = krok(WordProgress(), proba(task = TaskKind.ROZPOZNANIE), DZIS)

        assertEquals(1, po.box)
        assertEquals(Zaliczenie.ROZPOZNANE, zaliczenie)
        assertFalse(Zaliczenie.ROZPOZNANE.zPamieci)
    }

    @Test
    fun `nowe slowo podane z pamieci jest warte dziesiec punktow`() {
        val (po, zaliczenie) = krok(WordProgress(), proba(), DZIS)

        assertEquals(1, po.box)
        assertEquals(Zaliczenie.NOWE, zaliczenie)
    }

    @Test
    fun `pierwsze wejscie do pudelka drugiego to awans`() {
        val poznane = wPudelku(1)

        val (po, zaliczenie) = krok(poznane, proba(), poznane.dueDay)

        assertEquals(2, po.box)
        assertEquals(Zaliczenie.AWANS, zaliczenie)
    }

    @Test
    fun `pierwsze wejscie do pudelka trzeciego to utrwalenie`() {
        val powtarzane = wPudelku(2)

        val (po, zaliczenie) = krok(powtarzane, proba(), powtarzane.dueDay)

        assertEquals(3, po.box)
        assertEquals(Zaliczenie.UTRWALENIE, zaliczenie)
    }

    @Test
    fun `potwierdzenie utrwalonego slowa w terminie to powtorka`() {
        val utrwalone = wPudelku(3)

        val (po, zaliczenie) = krok(utrwalone, proba(), utrwalone.dueDay)

        assertEquals("pudelko stoi, rusza sie sam termin", 3, po.box)
        assertEquals(utrwalone.dueDay + 7, po.dueDay)
        assertEquals(Zaliczenie.POWTORKA, zaliczenie)
    }

    @Test
    fun `droga jednego slowa do utrwalenia jest warta czterdziesci punktow`() {
        var stan = WordProgress()
        val zaliczenia = ArrayList<Zaliczenie?>()
        repeat(3) {
            val (po, zaliczenie) = krok(stan, proba(), stan.dueDay.coerceAtLeast(DZIS))
            stan = po
            zaliczenia += zaliczenie
        }

        assertEquals(listOf(Zaliczenie.NOWE, Zaliczenie.AWANS, Zaliczenie.UTRWALENIE), zaliczenia)
        assertEquals(40, zaliczenia.sumOf { it?.xp ?: 0 })
    }

    @Test
    fun `powrot na szczebel zdobyty wczesniej jest powtorka, a nie drugim awansem`() {
        // Gdyby powrot byl platny jak pierwsze wejscie, oplacaloby sie mylic: kazdy blad
        // otwieralby te same trzydziesci punktow do zdobycia jeszcze raz.
        val utrwalone = wPudelku(3)
        val dzienBledu = utrwalone.dueDay
        val (poBledzie, zaBlad) = krok(utrwalone, proba(Outcome.BLEDNA), dzienBledu)
        assertNull(zaBlad)
        assertEquals(1, poBledzie.box)
        assertEquals(3, poBledzie.szczyt)

        val (wDrugim, zaDrugie) = krok(poBledzie, proba(), dzienBledu + 1)
        val (wTrzecim, zaTrzecie) = krok(wDrugim, proba(), wDrugim.dueDay)

        assertEquals(2, wDrugim.box)
        assertEquals(3, wTrzecim.box)
        assertEquals(Zaliczenie.POWTORKA, zaDrugie)
        assertEquals(Zaliczenie.POWTORKA, zaTrzecie)
    }

    @Test
    fun `slowo poznane rozpoznaniem dostaje awans za pierwsze przypomnienie, a nie nowe slowo`() {
        val (rozpoznane, _) = krok(WordProgress(), proba(task = TaskKind.ROZPOZNANIE), DZIS)

        val (po, zaliczenie) = krok(rozpoznane, proba(), DZIS + 1)

        assertEquals(2, po.box)
        assertEquals(Zaliczenie.AWANS, zaliczenie)
    }

    @Test
    fun `stan sprzed migracji nie dostaje pierwszego razu za szczebel, na ktorym juz stoi`() {
        // Stary zapis: pudelko jest, szczytu jeszcze nie ma. Liczy sie wyzsza z tych dwoch liczb.
        val stary = WordProgress(box = 2, dueDay = DZIS, lastPromotionDay = DZIS - 3)
        assertEquals(0, stary.szczyt)

        assertEquals(Zaliczenie.UTRWALENIE, krok(stary, proba(), DZIS).second)

        val cofniety = WordProgress(box = 1, dueDay = DZIS, lastPromotionDay = DZIS - 3, szczyt = 3)
        assertEquals(Zaliczenie.POWTORKA, krok(cofniety, proba(), DZIS).second)
    }

    // --- kiedy nie nalezy sie nic -----------------------------------------------------------

    @Test
    fun `pomylka, podpowiedz, pokazana odpowiedz i proba przerwana nie daja nic`() {
        val stany = listOf(
            WordProgress(),
            wPudelku(1).let { it.copy(dueDay = DZIS) },
            wPudelku(2).let { it.copy(dueDay = DZIS) },
            wPudelku(3).let { it.copy(dueDay = DZIS) },
        )
        val bezZaliczenia = listOf(Outcome.BLEDNA, Outcome.PODPOWIEDZ, Outcome.POKAZANA, Outcome.PRZERWANA)
        for (stan in stany) {
            for (wynik in bezZaliczenia) {
                for (zadanie in TaskKind.entries) {
                    assertNull(
                        "pudelko ${stan.box}, $wynik, $zadanie",
                        krok(stan, proba(wynik, zadanie), DZIS).second,
                    )
                }
            }
        }
    }

    @Test
    fun `kolejne trafienie tego samego dnia nie daje nic`() {
        var stan = WordProgress()
        val zaliczenia = ArrayList<Zaliczenie?>()
        repeat(6) { nr ->
            val (po, zaliczenie) = krok(stan, proba(id = "p$nr"), DZIS)
            stan = po
            zaliczenia += zaliczenie
        }

        assertEquals(listOf(Zaliczenie.NOWE, null, null, null, null, null), zaliczenia)
    }

    @Test
    fun `poprawka po bledzie tego samego dnia nie daje nic`() {
        val zalegle = wPudelku(1).copy(dueDay = DZIS)
        val (poBledzie, _) = krok(zalegle, proba(Outcome.BLEDNA), DZIS)

        val (po, zaliczenie) = krok(poBledzie, proba(), DZIS)

        assertEquals(1, po.box)
        assertNull("poprawka po bledzie to nie dowod pamieci", zaliczenie)
    }

    @Test
    fun `odpowiedz przed terminem nie daje nic`() {
        val utrwalone = wPudelku(3)

        val (po, zaliczenie) = krok(utrwalone, proba(), utrwalone.dueDay - 1)

        assertEquals(utrwalone.dueDay, po.dueDay)
        assertNull(zaliczenie)
    }

    @Test
    fun `rozpoznanie zarabia tylko za wyjscie z pudelka zero`() {
        // Pudelko 1: kafel przesuwa termin (zmiana w harmonogramie), ale to nie jest dowod pamieci.
        val poznane = wPudelku(1).copy(dueDay = DZIS)
        val (przesuniete, zaPierwsze) = krok(poznane, proba(task = TaskKind.ROZPOZNANIE), DZIS)
        assertEquals(DZIS + 1, przesuniete.dueDay)
        assertNull(zaPierwsze)

        // Pudelka 2 i 3: kafel nie rusza niczego, wiec tym bardziej nie zarabia.
        for (pudelko in 2..3) {
            val wyzej = wPudelku(pudelko).copy(dueDay = DZIS)
            val (po, zaliczenie) = krok(wyzej, proba(task = TaskKind.ROZPOZNANIE), DZIS)
            assertEquals(DZIS, po.dueDay)
            assertNull("pudelko $pudelko", zaliczenie)
        }
    }

    @Test
    fun `ta sama proba przetworzona drugi raz nie daje drugiego zaliczenia`() {
        val powtarzalna = proba(id = "$WPIS#123")
        val (raz, pierwsze) = krok(WordProgress(), powtarzalna, DZIS)
        assertEquals(Zaliczenie.NOWE, pierwsze)

        // Takze nastepnego dnia, gdy slowo jest juz zalegle i nowa proba by awansowala.
        assertNull(krok(raz, powtarzalna, DZIS).second)
        assertNull(krok(raz, powtarzalna, DZIS + 1).second)
    }

    @Test
    fun `stan, ktory sie nie ruszyl, nie daje nic nawet przy poprawnej odpowiedzi`() {
        val stan = wPudelku(2)

        assertNull(Nagrody.zaliczenie(stan, stan, proba()))
        // Cofniecie pudelka przy poprawnej odpowiedzi nie zdarza sie w Srs; gdyby ktos podal
        // takie stany, to tez nie jest zaliczenie.
        assertNull(Nagrody.zaliczenie(stan, stan.copy(box = 1, dueDay = stan.dueDay + 5), proba()))
    }

    @Test
    fun `jedno slowo w jednym kierunku daje najwyzej jedno zaliczenie dziennie`() {
        // Wlasnosc, na ktorej stoi cala ksiega: Srs awansuje slowo najwyzej raz na dzien i
        // kierunek, wiec nie ma ciagu odpowiedzi, ktory nabije dwa zaliczenia jednym slowem.
        //
        // Zegar w tym tescie stoi albo idzie do przodu. Przy zegarze cofanym i przestawianym
        // z powrotem wlasnosc nie obowiazuje (dzien "po bledzie" nie jest wtedy dniem bledu),
        // ale to jest majstrowanie przy telefonie, a nie gra, i Srs traktuje to tak samo.
        val slowa = listOf("w000000000001", "w000000000002", "w000000000003")
        val kierunki = listOf(Direction.TERM_TO_DEF, Direction.DEF_TO_TERM)
        // Poprawnych wiecej niz reszty, bo inaczej malo ktore slowo dochodzi do pudelka 3.
        val wyniki = List(6) { Outcome.POPRAWNA } + Outcome.entries
        val zadania = List(3) { TaskKind.PRZYPOMNIENIE } + TaskKind.ROZPOZNANIE
        val widziane = HashMap<Zaliczenie, Int>()

        for (ziarno in 1..400) {
            val los = Random(ziarno)
            var mapa = emptyMap<String, WordProgress>()
            var dzien = DZIS
            val zaliczoneDzis = HashSet<String>()
            repeat(150) { nr ->
                if (los.nextInt(5) == 0) {
                    dzien += los.nextInt(1, 5)
                    zaliczoneDzis.clear()
                }
                val proba = Attempt(
                    entryId = slowa[los.nextInt(slowa.size)],
                    direction = kierunki[los.nextInt(kierunki.size)],
                    task = zadania[los.nextInt(zadania.size)],
                    outcome = wyniki[los.nextInt(wyniki.size)],
                    // Co kilka prob ten sam znacznik: ponowienie tez nie moze niczego dorobic.
                    id = if (los.nextInt(6) == 0) "ponowiona" else "z$ziarno-$nr",
                )
                val klucz = Srs.key(proba.entryId, proba.direction)
                val przed = mapa[klucz] ?: WordProgress()
                mapa = Srs.applyTo(mapa, proba, dzien)
                val po = mapa[klucz] ?: WordProgress()

                val zaliczenie = Nagrody.zaliczenie(przed, po, proba) ?: return@repeat
                widziane[zaliczenie] = (widziane[zaliczenie] ?: 0) + 1
                assertTrue(
                    "ziarno $ziarno, proba $nr: drugie zaliczenie tego samego dnia za $klucz",
                    zaliczoneDzis.add(klucz),
                )
                assertEquals(Outcome.POPRAWNA, proba.outcome)
                assertTrue("zaliczenie bez ruchu w harmonogramie", po.box > przed.box || po.dueDay > przed.dueDay)
                // Rozpoznanie zarabia tylko za wyjscie z pudelka 0, a wszystko inne jest z pamieci.
                if (proba.task == TaskKind.ROZPOZNANIE) {
                    assertEquals(Zaliczenie.ROZPOZNANE, zaliczenie)
                    assertEquals(0, przed.box)
                } else {
                    assertTrue("$zaliczenie za wpisana odpowiedz", zaliczenie.zPamieci)
                }
            }
        }

        // Test o tescie: losowanie ma naprawde przechodzic przez wszystkie wiersze tabeli.
        for (rodzaj in Zaliczenie.entries) {
            assertTrue("losowe ciagi ani razu nie daly $rodzaj", (widziane[rodzaj] ?: 0) > 0)
        }
    }

    // --- ksiega: dzien po dniu --------------------------------------------------------------

    @Test
    fun `pierwsze zaliczenie zaklada dzien w pustej ksiedze`() {
        val ksiega = Nagrody.dolicz(Dorobek(), Zaliczenie.NOWE, DZIS, CEL)

        assertEquals(
            Dorobek(xp = 10, dzien = DZIS, slowaDzis = 1, xpDzis = 10),
            ksiega,
        )
    }

    @Test
    fun `tego samego dnia liczniki rosna dalej`() {
        var ksiega = Nagrody.dolicz(Dorobek(), Zaliczenie.NOWE, DZIS, CEL)
        ksiega = Nagrody.dolicz(ksiega, Zaliczenie.UTRWALENIE, DZIS, CEL)
        ksiega = Nagrody.dolicz(ksiega, Zaliczenie.POWTORKA, DZIS, CEL)

        assertEquals(35, ksiega.xp)
        assertEquals(35, ksiega.xpDzis)
        assertEquals(3, ksiega.slowaDzis)
        assertEquals(1, ksiega.powtorki)
        assertEquals(0, ksiega.dniNauki)
    }

    @Test
    fun `rozpoznanie daje punkty, ale nie zbliza do celu dnia`() {
        val ksiega = kilka(Dorobek(), 12, Zaliczenie.ROZPOZNANE, DZIS)

        assertEquals(24, ksiega.xp)
        assertEquals(24, ksiega.xpDzis)
        assertEquals("wybor z podanych odpowiedzi nie wypelnia celu", 0, ksiega.slowaDzis)
        assertEquals(0, ksiega.dniNauki)
        assertFalse(Nagrody.celZaliczony(ksiega, DZIS))
    }

    @Test
    fun `nastepnego dnia liczniki na dzis zaczynaja od zera, a suma zostaje`() {
        val wczoraj = kilka(Dorobek(), 3, Zaliczenie.NOWE, DZIS)

        val dzisiaj = Nagrody.dolicz(wczoraj, Zaliczenie.AWANS, DZIS + 1, CEL)

        assertEquals(40, dzisiaj.xp)
        assertEquals(DZIS + 1, dzisiaj.dzien)
        assertEquals(1, dzisiaj.slowaDzis)
        assertEquals(10, dzisiaj.xpDzis)
    }

    @Test
    fun `cel dnia liczy sie raz, w chwili, w ktorej zostal osiagniety`() {
        val przedCelem = kilka(Dorobek(), CEL - 1, Zaliczenie.NOWE, DZIS)
        assertEquals(0, przedCelem.dniNauki)
        assertFalse(Nagrody.celZaliczony(przedCelem, DZIS))

        val zCelem = Nagrody.dolicz(przedCelem, Zaliczenie.NOWE, DZIS, CEL)
        assertEquals(1, zCelem.dniNauki)
        assertEquals(1, zCelem.seria)
        assertEquals(1, zCelem.rekordSerii)
        assertEquals(DZIS, zCelem.seriaDzien)
        assertTrue(Nagrody.celZaliczony(zCelem, DZIS))

        // Dalsza nauka tego dnia dopisuje punkty, ale dnia nie liczy drugi raz.
        val dalej = kilka(zCelem, 10, Zaliczenie.POWTORKA, DZIS)
        assertEquals(1, dalej.dniNauki)
        assertEquals(1, dalej.seria)
        assertEquals(CEL + 10, dalej.slowaDzis)
    }

    @Test
    fun `seria rosnie dzien po dniu`() {
        var ksiega = Dorobek()
        for (dzien in DZIS..DZIS + 3) ksiega = kilka(ksiega, CEL, Zaliczenie.NOWE, dzien)

        assertEquals(4, ksiega.seria)
        assertEquals(4, ksiega.rekordSerii)
        assertEquals(4, ksiega.dniNauki)
        assertEquals(DZIS + 3, ksiega.seriaDzien)
    }

    @Test
    fun `jeden opuszczony dzien jest wybaczony`() {
        var ksiega = kilka(Dorobek(), CEL, Zaliczenie.NOWE, DZIS)
        // DZIS + 1 bez nauki.
        ksiega = kilka(ksiega, CEL, Zaliczenie.NOWE, DZIS + 2)

        assertEquals(2, ksiega.seria)
        assertEquals(2, ksiega.dniNauki)
    }

    @Test
    fun `dzien z nauka, ale bez celu, nie przedluza serii i jej nie zrywa`() {
        var ksiega = kilka(Dorobek(), CEL, Zaliczenie.NOWE, DZIS)
        ksiega = kilka(ksiega, CEL - 1, Zaliczenie.NOWE, DZIS + 1)
        assertEquals(1, ksiega.seria)
        assertEquals(DZIS, ksiega.seriaDzien)

        ksiega = kilka(ksiega, CEL, Zaliczenie.NOWE, DZIS + 2)
        assertEquals("niepelny dzien to dla serii to samo, co dzien opuszczony", 2, ksiega.seria)
    }

    @Test
    fun `dwa opuszczone dni zaczynaja serie od nowa, a rekord i dni nauki zostaja`() {
        var ksiega = Dorobek()
        for (dzien in DZIS..DZIS + 2) ksiega = kilka(ksiega, CEL, Zaliczenie.NOWE, dzien)
        assertEquals(3, ksiega.seria)

        // DZIS + 3 i DZIS + 4 bez celu.
        ksiega = kilka(ksiega, CEL, Zaliczenie.NOWE, DZIS + 5)

        assertEquals(1, ksiega.seria)
        assertEquals("rekordu nikt nie odbiera", 3, ksiega.rekordSerii)
        assertEquals("dni nauki tylko rosna", 4, ksiega.dniNauki)
        assertEquals(200, ksiega.xp)
    }

    @Test
    fun `zegar cofniety dopisuje same punkty i nie rusza niczego wiecej`() {
        val ksiega = kilka(Dorobek(), CEL, Zaliczenie.NOWE, DZIS)

        for (rodzaj in Zaliczenie.entries) {
            val po = Nagrody.dolicz(ksiega, rodzaj, DZIS - 1, CEL)
            assertEquals("po $rodzaj", ksiega.copy(xp = ksiega.xp + rodzaj.xp), po)
        }
    }

    @Test
    fun `po powrocie zegara dzien liczy sie dalej od tego, co bylo`() {
        var ksiega = kilka(Dorobek(), 3, Zaliczenie.NOWE, DZIS)
        ksiega = Nagrody.dolicz(ksiega, Zaliczenie.NOWE, DZIS - 400, CEL)
        ksiega = kilka(ksiega, 2, Zaliczenie.NOWE, DZIS)

        assertEquals(60, ksiega.xp)
        assertEquals(DZIS, ksiega.dzien)
        assertEquals("zaliczenie z cofnietego zegara nie liczy sie do celu", 5, ksiega.slowaDzis)
        assertEquals(1, ksiega.dniNauki)
    }

    @Test
    fun `podniesienie celu pozniej tego samego dnia nie cofa zaliczonego dnia`() {
        val zCelem = kilka(Dorobek(), 5, Zaliczenie.NOWE, DZIS, cel = 5)
        assertEquals(1, zCelem.dniNauki)

        // Uczen podnosi cel do 20 i uczy sie dalej, az dojdzie takze do nowego celu.
        val dalej = kilka(zCelem, 20, Zaliczenie.POWTORKA, DZIS, cel = 20)

        assertEquals("dzien nie moze sie odliczyc ani policzyc drugi raz", 1, dalej.dniNauki)
        assertEquals(1, dalej.seria)
        assertTrue(Nagrody.celZaliczony(dalej, DZIS))
    }

    @Test
    fun `obnizenie celu zalicza dzien dopiero przy nastepnym zaliczeniu`() {
        val bezCelu = kilka(Dorobek(), 7, Zaliczenie.NOWE, DZIS, cel = 20)
        assertEquals(0, bezCelu.dniNauki)

        // Sama zmiana opcji ksiegi nie rusza; rusza ja nastepna nauka, takze rozpoznanie.
        val poZmianie = Nagrody.dolicz(bezCelu, Zaliczenie.ROZPOZNANE, DZIS, 5)

        assertEquals(7, poZmianie.slowaDzis)
        assertEquals(1, poZmianie.dniNauki)
        assertTrue(Nagrody.celZaliczony(poZmianie, DZIS))
    }

    @Test
    fun `cel zerowy albo ujemny nie zalicza dnia bez ani jednego slowa z pamieci`() {
        for (cel in listOf(0, -1, Int.MIN_VALUE)) {
            val ksiega = Nagrody.dolicz(Dorobek(), Zaliczenie.ROZPOZNANE, DZIS, cel)
            assertEquals("cel $cel", 0, ksiega.dniNauki)
            assertEquals(1, Nagrody.dolicz(ksiega, Zaliczenie.NOWE, DZIS, cel).dniNauki)
        }
    }

    @Test
    fun `znacznik nigdy nie wchodzi do zadnej arytmetyki`() {
        // NO_DAY to Int.MIN_VALUE. Odejmowanie od niego przekreca licznik i z "nigdy" robi
        // "wczoraj", dlatego kazde uzycie zaczyna od porownania rownosciowego. Ksiegi nizej sa
        // celowo niespojne (seria bez dnia), zeby blad w rachunku mial sie czym pokazac.
        val bezDnia = Dorobek(seria = 7, slowaDzis = 3, xpDzis = 30, xp = 30)
        assertEquals(NIGDY, bezDnia.dzien)
        assertEquals(NIGDY, bezDnia.seriaDzien)

        for (dzien in listOf(NIGDY, NIGDY + 1, NIGDY + 2, -1, 0, DZIS, Int.MAX_VALUE)) {
            assertEquals("seria w dniu $dzien", 0, Nagrody.seriaNaDzis(bezDnia, dzien))
            assertFalse("cel w dniu $dzien", Nagrody.celZaliczony(bezDnia, dzien))
            assertEquals("slowa w dniu $dzien", 0, Nagrody.slowaNaDzis(bezDnia, dzien))
            assertEquals("punkty w dniu $dzien", 0, Nagrody.xpNaDzis(bezDnia, dzien))
        }

        // Cel osiagniety dzien "po nigdy": to pierwszy dzien serii, a nie osmy.
        val pierwszy = kilka(bezDnia.copy(slowaDzis = 0, xpDzis = 0), CEL, Zaliczenie.NOWE, NIGDY + 1)
        assertEquals(1, pierwszy.seria)
        assertEquals(NIGDY + 1, pierwszy.seriaDzien)

        // Dzien proby rowny znacznikowi nie jest dniem: dochodza same punkty.
        assertEquals(
            bezDnia.copy(xp = 40),
            Nagrody.dolicz(bezDnia, Zaliczenie.NOWE, NIGDY, CEL),
        )

        // Druga strona zakresu: roznica dni liczona w Int przekrecilaby sie na ujemna.
        val dawno = Dorobek(seria = 3, seriaDzien = -5, dzien = -5)
        assertEquals(0, Nagrody.seriaNaDzis(dawno, Int.MAX_VALUE))
        assertEquals(1, kilka(dawno, CEL, Zaliczenie.NOWE, Int.MAX_VALUE).seria)
    }

    @Test
    fun `nic, co uczen zdobyl, nie maleje, cokolwiek zrobi zegar`() {
        // Zasada projektu: uczniowi niczego nie odbieramy. Dowolne zaliczenia przy dowolnym celu
        // i przy zegarze skaczacym w obie strony: punkty, dni nauki, rekord serii i powtorki
        // moga tylko rosnac, a zaden dzien nie liczy sie do dni nauki dwa razy.
        var zaliczoneDni = 0
        var cofnieciaZegara = 0
        for (ziarno in 1..300) {
            val los = Random(ziarno)
            var ksiega = Dorobek()
            var dzien = DZIS
            val dniZCelem = HashSet<Int>()
            repeat(400) { nr ->
                // Zegar zwykle stoi (dzien nauki ma kilka zaliczen), czasem idzie do przodu,
                // a z rzadka cofa sie o kilka dni.
                val skok = when (los.nextInt(40)) {
                    0 -> -los.nextInt(1, 6)
                    in 1..5 -> los.nextInt(1, 4)
                    else -> 0
                }
                if (skok < 0) cofnieciaZegara++
                dzien += skok
                val cel = Nagrody.CELE_DNIA[los.nextInt(Nagrody.CELE_DNIA.size)]
                val zaliczenie = Zaliczenie.entries[los.nextInt(Zaliczenie.entries.size)]

                val po = Nagrody.dolicz(ksiega, zaliczenie, dzien, cel)

                val gdzie = "ziarno $ziarno, krok $nr"
                assertEquals(gdzie, ksiega.xp + zaliczenie.xp, po.xp)
                assertTrue(gdzie, po.dniNauki - ksiega.dniNauki in 0..1)
                assertTrue(gdzie, po.powtorki - ksiega.powtorki in 0..1)
                assertTrue(gdzie, po.rekordSerii >= ksiega.rekordSerii)
                assertTrue(gdzie, po.rekordSerii >= po.seria)
                assertTrue(gdzie, po.seria in 0..po.dniNauki)
                assertTrue(gdzie, po.slowaDzis >= 0 && po.xpDzis >= 0)
                // Dzien w ksiedze nie cofa sie razem z zegarem.
                assertTrue(gdzie, ksiega.dzien == NIGDY || po.dzien >= ksiega.dzien)
                if (po.dniNauki > ksiega.dniNauki) {
                    assertTrue("$gdzie: dzien $dzien policzony drugi raz", dniZCelem.add(dzien))
                    assertEquals(gdzie, dzien, po.seriaDzien)
                }
                ksiega = po
            }
            zaliczoneDni += ksiega.dniNauki
        }
        // Test o tescie: losowanie ma naprawde zaliczac dni i naprawde cofac zegar.
        assertTrue("za malo zaliczonych dni: $zaliczoneDni", zaliczoneDni > 1000)
        assertTrue("za malo cofniec zegara: $cofnieciaZegara", cofnieciaZegara > 1000)
    }

    // --- to, co ekran pokazuje dzis ---------------------------------------------------------

    @Test
    fun `seria do pokazania zyje przez okno jednego opuszczonego dnia i potem gasnie`() {
        var ksiega = Dorobek()
        for (dzien in DZIS - 2..DZIS) ksiega = kilka(ksiega, CEL, Zaliczenie.NOWE, dzien)

        assertEquals(3, Nagrody.seriaNaDzis(ksiega, DZIS))
        assertEquals("jutro seria czeka na dzisiejszy cel", 3, Nagrody.seriaNaDzis(ksiega, DZIS + 1))
        assertEquals("pojutrze jeszcze da sie ja przedluzyc", 3, Nagrody.seriaNaDzis(ksiega, DZIS + 2))
        assertEquals(0, Nagrody.seriaNaDzis(ksiega, DZIS + 3))
        // W ksiedze stara dlugosc zostaje az do nastepnego celu; ekran ma czytac seriaNaDzis.
        assertEquals(3, ksiega.seria)

        // To, co pokazuje ekran, zgadza sie z tym, co zrobi dolicz.
        assertEquals(4, kilka(ksiega, CEL, Zaliczenie.NOWE, DZIS + 2).seria)
        assertEquals(1, kilka(ksiega, CEL, Zaliczenie.NOWE, DZIS + 3).seria)
    }

    @Test
    fun `slowa i punkty na dzis sa zerem, gdy ksiega pamieta inny dzien`() {
        val ksiega = kilka(Dorobek(), 3, Zaliczenie.NOWE, DZIS)

        assertEquals(3, Nagrody.slowaNaDzis(ksiega, DZIS))
        assertEquals(30, Nagrody.xpNaDzis(ksiega, DZIS))
        assertEquals(0, Nagrody.slowaNaDzis(ksiega, DZIS + 1))
        assertEquals(0, Nagrody.xpNaDzis(ksiega, DZIS + 1))
        assertEquals(0, Nagrody.slowaNaDzis(ksiega, DZIS - 1))
    }

    @Test
    fun `pierscien ma piec stanow i nie klamie na zadnym koncu`() {
        assertEquals(listOf(0, 1, 1, 2, 3, 4, 4), (0..6).map { Nagrody.pierscien(it, 5) })
        assertEquals(listOf(0, 1, 1, 1, 1, 2, 2, 2, 3, 3, 4), (0..10).map { Nagrody.pierscien(it, 10) })
        assertEquals(
            listOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 4),
            (0..20).map { Nagrody.pierscien(it, 20) },
        )

        for (cel in listOf(1, 2, 3, 5, 7, 10, 20, 1000)) {
            var poprzedni = 0
            for (slowa in 0..cel + 3) {
                val stan = Nagrody.pierscien(slowa, cel)
                assertTrue("cel $cel, slowa $slowa", stan in 0..4)
                assertEquals("zero tylko dla zera zaliczen", slowa == 0, stan == 0)
                assertEquals("pelny pierscien tylko dla osiagnietego celu", slowa >= cel, stan == 4)
                assertTrue("pierscien sie cofnal", stan >= poprzedni)
                poprzedni = stan
            }
        }
        // Liczby spoza zakresu nie wywracaja ekranu.
        assertEquals(0, Nagrody.pierscien(-3, 5))
        assertEquals(4, Nagrody.pierscien(1, 0))
        assertEquals(4, Nagrody.pierscien(Int.MAX_VALUE, 20))
        assertEquals(3, Nagrody.pierscien(Int.MAX_VALUE - 1, Int.MAX_VALUE))
    }

    @Test
    fun `cel dnia przyjmuje tylko dozwolone wartosci`() {
        assertEquals(listOf(5, 10, 20), Nagrody.CELE_DNIA)
        for (cel in Nagrody.CELE_DNIA) assertEquals(cel, Nagrody.celDnia(cel))
        for (zly in listOf(0, -5, 1, 7, 15, 21, Int.MAX_VALUE)) {
            assertEquals("cel $zly", Nagrody.CEL_DOMYSLNY, Nagrody.celDnia(zly))
        }
        assertEquals("wartosc domyslna opcji i ksiegi to jedna liczba", Nagrody.CEL_DOMYSLNY, Options().celDnia)
        assertTrue(Nagrody.CEL_DOMYSLNY in Nagrody.CELE_DNIA)
    }

    // --- szczeble ---------------------------------------------------------------------------

    @Test
    fun `tabela szczebli jest taka, jak w zalozeniach`() {
        assertEquals(
            listOf(
                Triple(0, "DEKLARACJA ZŁOŻONA", "DECLARATION FILED"),
                Triple(30, "PRÓBNA MATURA", "MOCK EXAM"),
                Triple(150, "STUDNIÓWKA", "PROM NIGHT"),
                Triple(500, "POLONEZ", "POLONAISE"),
                Triple(1200, "OSTATNI DZWONEK", "LAST BELL"),
                Triple(3000, "KASZTANY KWITNĄ", "CHESTNUTS IN BLOOM"),
                Triple(6000, "CZARNY DŁUGOPIS", "BLACK PEN"),
                Triple(12000, "ARKUSZ OTWARTY", "PAPER OPENED"),
                Triple(25000, "KARTA ODPOWIEDZI", "ANSWER SHEET"),
                Triple(50000, "POSTRACH ARKUSZY", "TERROR OF EXAM PAPERS"),
            ),
            Ranga.entries.map { Triple(it.prog, it.pl, it.en) },
        )
    }

    @Test
    fun `progi szczebli zaczynaja od zera i rosna`() {
        assertEquals(0, Ranga.entries.first().prog)
        Ranga.entries.zipWithNext { nizsza, wyzsza ->
            assertTrue("${wyzsza.name} nie jest wyzej niz ${nizsza.name}", wyzsza.prog > nizsza.prog)
        }
    }

    @Test
    fun `szczebel zmienia sie dokladnie na progu`() {
        val szczeble = Ranga.entries
        for ((nr, szczebel) in szczeble.withIndex()) {
            assertEquals(szczebel, Nagrody.ranga(szczebel.prog))
            if (nr > 0) {
                val punktPrzed = szczebel.prog - 1
                assertEquals(szczeble[nr - 1], Nagrody.ranga(punktPrzed))
                assertEquals(szczebel, Nagrody.nastepna(punktPrzed))
                assertEquals(1, Nagrody.doNastepnej(punktPrzed))
            }
            val wyzej = szczeble.getOrNull(nr + 1)
            assertEquals(wyzej, Nagrody.nastepna(szczebel.prog))
            assertEquals(if (wyzej == null) 0 else wyzej.prog - szczebel.prog, Nagrody.doNastepnej(szczebel.prog))
        }
    }

    @Test
    fun `na samej gorze nie ma nastepnego szczebla, a ujemna suma to pierwszy szczebel`() {
        assertEquals(Ranga.POSTRACH, Nagrody.ranga(Int.MAX_VALUE))
        assertNull(Nagrody.nastepna(50_000))
        assertNull(Nagrody.nastepna(Int.MAX_VALUE))
        assertEquals(0, Nagrody.doNastepnej(Int.MAX_VALUE))

        assertEquals(Ranga.DEKLARACJA, Nagrody.ranga(-1))
        assertEquals(Ranga.DEKLARACJA, Nagrody.ranga(0))
        assertEquals(Ranga.PROBNA_MATURA, Nagrody.nastepna(0))
        assertEquals(30, Nagrody.doNastepnej(0))
    }

    // --- punkty na start (migracja zapisu) --------------------------------------------------

    @Test
    fun `slowo zastane w pudelku jest warte tyle, ile dalaby dzis ta sama droga`() {
        assertEquals(0, Nagrody.punktyNaStart(WordProgress()))
        assertEquals(10, Nagrody.punktyNaStart(WordProgress(box = 1)))
        assertEquals(10, Nagrody.punktyNaStart(WordProgress(box = 1, lastTask = TaskKind.PRZYPOMNIENIE)))
        assertEquals(2, Nagrody.punktyNaStart(WordProgress(box = 1, lastTask = TaskKind.ROZPOZNANIE)))
        assertEquals(20, Nagrody.punktyNaStart(WordProgress(box = 2)))
        assertEquals(40, Nagrody.punktyNaStart(WordProgress(box = 3)))
        // Wyzej sie nie da, a rozpoznanie jako ostatnia proba nie umniejsza pudelek 2 i 3.
        assertEquals(20, Nagrody.punktyNaStart(WordProgress(box = 2, lastTask = TaskKind.ROZPOZNANIE)))
        assertEquals(40, Nagrody.punktyNaStart(WordProgress(box = 9)))
        assertEquals(0, Nagrody.punktyNaStart(WordProgress(box = -1)))

        // To samo policzone droga: kazde pudelko jest warte sume zaliczen, ktore do niego wioda.
        var stan = WordProgress()
        var suma = 0
        repeat(3) {
            val (po, zaliczenie) = krok(stan, proba(), stan.dueDay.coerceAtLeast(DZIS))
            stan = po
            suma += zaliczenie?.xp ?: 0
            assertEquals("pudelko ${stan.box}", suma, Nagrody.punktyNaStart(stan))
        }
    }

    // --- co dala runda ----------------------------------------------------------------------

    private val slowaZestawu = (1..8).map { "w00000000000$it" }

    /** Maly zestaw wbudowany: odznaki licza sie tylko z takich. */
    private val zestaw = WordSet(
        id = "t001",
        title = "Dom · Kuchnia",
        builtIn = true,
        entries = slowaZestawu.map { Entry("haslo-$it", "znaczenie-$it", it) },
    )

    private val pustyZapis = SaveData(sets = listOf(zestaw))

    /** To, co robi Repository.recordAttempt, tylko bez dysku: postep, zaliczenie, ksiega, odznaki. */
    private fun zapisz(dane: SaveData, proba: Attempt, dzien: Int): SaveData {
        val klucz = Srs.key(proba.entryId, proba.direction)
        val postep = Srs.applyTo(dane.progress, proba, dzien)
        val zaliczenie = Nagrody.zaliczenie(
            dane.progress[klucz] ?: WordProgress(),
            postep[klucz] ?: WordProgress(),
            proba,
        ) ?: return dane.copy(progress = postep)
        val ksiega = Nagrody.dolicz(dane.dorobek, zaliczenie, dzien, Nagrody.celDnia(dane.options.celDnia))
        val zPostepem = dane.copy(progress = postep, dorobek = ksiega)
        return zPostepem.copy(dorobek = Odznaki.odblokuj(ksiega, Odznaki.liczniki(zPostepem), dzien))
    }

    private fun runda(dane: SaveData, proby: List<Attempt>, dzien: Int): SaveData =
        proby.fold(dane) { stan, proba -> zapisz(stan, proba, dzien) }

    private fun wynik(punkty: Int, tryb: GameMode = GameMode.CLASSIC) =
        ScoreRecord("Kuba", "Dom · Kuchnia", punkty, 1, 0, 0, 1_700_000_000_000L, tryb)

    @Test
    fun `odtworzenie rundy oddaje zaliczenia po kolei i pomija proby bez klucza`() {
        val przed = runda(pustyZapis, listOf(proba(entryId = slowaZestawu[0])), DZIS - 1).progress
        val proby = listOf(
            proba(entryId = slowaZestawu[0]),                               // awans 1 -> 2
            proba(entryId = slowaZestawu[1], task = TaskKind.ROZPOZNANIE),  // rozpoznane
            proba(entryId = slowaZestawu[2], outcome = Outcome.BLEDNA),     // nic
            proba(entryId = slowaZestawu[2]),                               // nic: poprawka po bledzie
            proba(entryId = slowaZestawu[3]),                               // nowe
            proba(entryId = slowaZestawu[3]),                               // nic: drugi raz tego dnia
            proba(entryId = ""),                                            // bez identyfikatora
            proba(entryId = slowaZestawu[4], direction = Direction.RANDOM), // ustawienie, nie kierunek
            proba(entryId = slowaZestawu[3], direction = Direction.DEF_TO_TERM), // drugi kierunek: nowe
        )

        assertEquals(
            listOf(Zaliczenie.AWANS, Zaliczenie.ROZPOZNANE, Zaliczenie.NOWE, Zaliczenie.NOWE),
            Nagrody.zaliczeniaRundy(przed, proby, DZIS),
        )
    }

    @Test
    fun `pierwsza runda nowego ucznia`() {
        val proby = slowaZestawu.take(5).map { proba(entryId = it) }
        val po = runda(pustyZapis, proby, DZIS)

        val nagroda = Nagrody.roznica(pustyZapis, po, proby, wynik(120), DZIS)

        assertEquals(
            NagrodaRundy(
                xp = 50,
                zaliczenia = mapOf(Zaliczenie.NOWE to 5),
                slowaPrzed = 0,
                slowaPo = 5,
                cel = 5,
                celZaliczonyTeraz = true,
                seriaPrzed = 0,
                seriaPo = 1,
                rangaPrzed = Ranga.DEKLARACJA,
                rangaPo = Ranga.PROBNA_MATURA,
                odznaki = listOf(Odznaka.SLOWA_5, Odznaka.DNI_1),
                rekord = false,
                poprzedniRekord = null,
            ),
            nagroda,
        )
        assertTrue(nagroda.awansRangi)
        assertEquals(nagroda.xp, nagroda.xpZaliczen)
        assertFalse(nagroda.pusta)
    }

    @Test
    fun `runda, ktora niczego nie dala, jest pusta i nie pokazuje zer`() {
        // Uczen zagral jeszcze raz te same slowa tego samego dnia i pomylil sie przy nowym.
        val rano = slowaZestawu.take(3).map { proba(entryId = it) }
        val przed = runda(pustyZapis, rano, DZIS)
        val proby = rano + proba(entryId = slowaZestawu[5], outcome = Outcome.BLEDNA) +
            proba(entryId = slowaZestawu[6], outcome = Outcome.PRZERWANA)
        val po = runda(przed, proby, DZIS)

        val nagroda = Nagrody.roznica(przed, po, proby, null, DZIS)

        assertTrue(nagroda.pusta)
        assertEquals(0, nagroda.xp)
        assertTrue(nagroda.zaliczenia.isEmpty())
        assertEquals(3, nagroda.slowaPrzed)
        assertEquals(3, nagroda.slowaPo)
        assertFalse(nagroda.celZaliczonyTeraz)
        assertEquals(nagroda.rangaPrzed, nagroda.rangaPo)
        assertFalse(nagroda.awansRangi)
        assertTrue(nagroda.odznaki.isEmpty())
        assertFalse(nagroda.rekord)
        assertNull(nagroda.poprzedniRekord)

        // Runda bez ani jednej proby tez.
        assertTrue(Nagrody.roznica(przed, przed, emptyList(), null, DZIS).pusta)
    }

    @Test
    fun `runda nastepnego dnia rozpisuje awanse i przedluza serie`() {
        val pierwszyDzien = slowaZestawu.take(5).map { proba(entryId = it) }
        val przed = runda(pustyZapis, pierwszyDzien, DZIS)
        val proby = pierwszyDzien + slowaZestawu.drop(5).map { proba(entryId = it, task = TaskKind.ROZPOZNANIE) }
        val po = runda(przed, proby, DZIS + 1)

        val nagroda = Nagrody.roznica(przed, po, proby, null, DZIS + 1)

        assertEquals(56, nagroda.xp)
        assertEquals(mapOf(Zaliczenie.ROZPOZNANE to 3, Zaliczenie.AWANS to 5), nagroda.zaliczenia)
        assertEquals(
            "rozpisanie idzie w kolejnosci stalych, nie w kolejnosci rundy",
            listOf(Zaliczenie.ROZPOZNANE, Zaliczenie.AWANS),
            nagroda.zaliczenia.keys.toList(),
        )
        assertEquals("wczorajsze slowa nie licza sie do dzisiejszego celu", 0, nagroda.slowaPrzed)
        assertEquals("rozpoznania nie wchodza do celu dnia", 5, nagroda.slowaPo)
        assertTrue(nagroda.celZaliczonyTeraz)
        assertEquals(1, nagroda.seriaPrzed)
        assertEquals(2, nagroda.seriaPo)
        assertEquals(Ranga.PROBNA_MATURA, nagroda.rangaPrzed)
        assertEquals(Ranga.PROBNA_MATURA, nagroda.rangaPo)
        assertFalse(nagroda.awansRangi)
        assertTrue("osmiu slow jest za malo na kolejna odznake", nagroda.odznaki.isEmpty())
        assertEquals(nagroda.xp, nagroda.xpZaliczen)
    }

    @Test
    fun `cel zaliczony przed runda nie jest oglaszany drugi raz`() {
        val rano = slowaZestawu.take(5).map { proba(entryId = it) }
        val przed = runda(pustyZapis, rano, DZIS)
        assertTrue(Nagrody.celZaliczony(przed.dorobek, DZIS))
        val proby = slowaZestawu.drop(5).map { proba(entryId = it) }
        val po = runda(przed, proby, DZIS)

        val nagroda = Nagrody.roznica(przed, po, proby, null, DZIS)

        assertEquals(30, nagroda.xp)
        assertEquals(5, nagroda.slowaPrzed)
        assertEquals(8, nagroda.slowaPo)
        assertFalse("cel padl rano, a nie w tej rundzie", nagroda.celZaliczonyTeraz)
        assertEquals(1, nagroda.seriaPrzed)
        assertEquals(1, nagroda.seriaPo)
        assertFalse(nagroda.pusta)
    }

    @Test
    fun `awans na wyzszy szczebel widac w roznicy`() {
        val przed = pustyZapis.copy(dorobek = Dorobek(xp = 145))
        val proby = listOf(proba(entryId = slowaZestawu[0]))
        val po = runda(przed, proby, DZIS)

        val nagroda = Nagrody.roznica(przed, po, proby, null, DZIS)

        assertEquals(10, nagroda.xp)
        assertEquals(Ranga.PROBNA_MATURA, nagroda.rangaPrzed)
        assertEquals(Ranga.STUDNIOWKA, nagroda.rangaPo)
        assertTrue(nagroda.awansRangi)
        assertFalse(nagroda.pusta)
    }

    @Test
    fun `seria wygasla przed runda liczy sie od zera, a nie od starej dlugosci`() {
        var przed = pustyZapis
        for (dzien in DZIS - 9..DZIS - 7) {
            przed = przed.copy(dorobek = kilka(przed.dorobek, CEL, Zaliczenie.NOWE, dzien))
        }
        assertEquals(3, przed.dorobek.seria)
        val proby = slowaZestawu.take(5).map { proba(entryId = it) }
        val po = runda(przed, proby, DZIS)

        val nagroda = Nagrody.roznica(przed, po, proby, null, DZIS)

        assertEquals("po tygodniu przerwy ekran nie moze pokazac starej serii", 0, nagroda.seriaPrzed)
        assertEquals(1, nagroda.seriaPo)
        assertEquals(3, po.dorobek.rekordSerii)
    }

    @Test
    fun `rekord jest tylko wtedy, gdy wynik bije najlepszy wczesniejszy w tym samym trybie`() {
        val przed = pustyZapis.copy(
            scores = listOf(
                wynik(500), wynik(320),
                wynik(900, GameMode.TRUE_FALSE),
            ),
        )
        fun po(nowy: ScoreRecord) = przed.copy(scores = przed.scores + nowy)

        // 501 bije klasyczne 500, choc w innym trybie lezy 900: tryby maja osobne rekordy.
        val pobity = Nagrody.roznica(przed, po(wynik(501)), emptyList(), wynik(501), DZIS)
        assertTrue(pobity.rekord)
        assertEquals(500, pobity.poprzedniRekord)
        assertFalse("runda z samym rekordem tez ma co pokazac", pobity.pusta)

        val wyrownany = Nagrody.roznica(przed, po(wynik(500)), emptyList(), wynik(500), DZIS)
        assertFalse("wyrownanie to nie pobicie", wyrownany.rekord)
        assertEquals(500, wyrownany.poprzedniRekord)

        // I w druga strone: 600 w prawda/falsz bije klasyczny rekord, ale nie swoj wlasny.
        val wInnymTrybie = wynik(600, GameMode.TRUE_FALSE)
        val slabszy = Nagrody.roznica(przed, po(wInnymTrybie), emptyList(), wInnymTrybie, DZIS)
        assertFalse(slabszy.rekord)
        assertEquals(900, slabszy.poprzedniRekord)

        // Pierwszy wynik w trybie nie ma czego pobic.
        val pierwszy = wynik(9999, GameMode.TIME_ATTACK)
        val debiut = Nagrody.roznica(przed, po(pierwszy), emptyList(), pierwszy, DZIS)
        assertFalse(debiut.rekord)
        assertNull(debiut.poprzedniRekord)

        // Runda przerwana wyjsciem nie oddaje wyniku.
        val bezWyniku = Nagrody.roznica(przed, przed, emptyList(), null, DZIS)
        assertFalse(bezWyniku.rekord)
        assertNull(bezWyniku.poprzedniRekord)
    }

    @Test
    fun `rozpisanie, ktore nie zgadza sie z ksiega, da sie rozpoznac`() {
        // Zapis jednej odpowiedzi sie nie udal: ekran ma ja na liscie prob, ksiega jej nie ma.
        val proby = slowaZestawu.take(3).map { proba(entryId = it) }
        val po = runda(pustyZapis, proby.take(2), DZIS)

        val nagroda = Nagrody.roznica(pustyZapis, po, proby, null, DZIS)

        assertEquals("zrodlem prawdy jest ksiega", 20, nagroda.xp)
        assertEquals(30, nagroda.xpZaliczen)
        assertEquals(2, nagroda.slowaPo)
    }

    @Test
    fun `odtworzenie rundy daje to samo, co zapisalo Repository dla tych samych prob`() {
        // Te same proby dwiema drogami. Pierwsza to prawdziwe Repository: kazda proba w swoim
        // zapisie, ksiega na dysku. Druga to odtworzenie rundy z mapy postepu sprzed rundy,
        // czyli to, z czego ekran wyniku rozpisuje punkty. Gdyby sie rozjechaly, uczen
        // widzialby na ekranie inna sume niz ta, ktora ma w ksiedze.
        val repo = Repository(katalog.root)
        // Maly zapis startowy zamiast calego katalogu: test robi kilkaset zapisow dokumentu.
        assertTrue(repo.save(SaveData(schemaVersion = Repository.SCHEMA_VERSION, sets = listOf(zestaw))))
        val los = Random(2027)
        val wyniki = List(5) { Outcome.POPRAWNA } + Outcome.entries
        val zadania = List(3) { TaskKind.PRZYPOMNIENIE } + TaskKind.ROZPOZNANIE
        val kierunki = listOf(Direction.TERM_TO_DEF, Direction.DEF_TO_TERM)
        val widziane = HashSet<Zaliczenie>()
        var dzien = DZIS
        var numer = 0

        repeat(60) {
            dzien += los.nextInt(1, 4)
            val przed = repo.load()
            val proby = ArrayList<Attempt>()
            repeat(los.nextInt(0, 14)) {
                // Co kilka prob ekran dostarcza TE SAMA probe drugi raz (ponowienie po bledzie
                // zapisu, drugie zrodlo zdarzen): ten sam znacznik, ta sama tresc.
                proby += if (proby.isNotEmpty() && los.nextInt(8) == 0) {
                    proby[los.nextInt(proby.size)]
                } else {
                    Attempt(
                        entryId = slowaZestawu[los.nextInt(slowaZestawu.size)],
                        direction = kierunki[los.nextInt(kierunki.size)],
                        task = zadania[los.nextInt(zadania.size)],
                        outcome = wyniki[los.nextInt(wyniki.size)],
                        id = "p${numer++}",
                    )
                }
            }
            for (p in proby) assertTrue(repo.recordAttempt(p, dzien.toLong() * Srs.MS_PER_DAY + 3_600_000L))
            val po = repo.load()

            val odtworzone = Nagrody.zaliczeniaRundy(przed.progress, proby, dzien)
            widziane += odtworzone
            assertEquals("dzien $dzien: punkty", odtworzone.sumOf { it.xp }, po.dorobek.xp - przed.dorobek.xp)
            assertEquals(
                "dzien $dzien: zaliczenia z pamieci",
                odtworzone.count { it.zPamieci },
                Nagrody.slowaNaDzis(po.dorobek, dzien),
            )
            assertEquals(
                "dzien $dzien: powtorki",
                odtworzone.count { it == Zaliczenie.POWTORKA },
                po.dorobek.powtorki - przed.dorobek.powtorki,
            )
            val nagroda = Nagrody.roznica(przed, po, proby, null, dzien)
            assertEquals(nagroda.xp, nagroda.xpZaliczen)
            assertEquals(odtworzone.size, nagroda.zaliczenia.values.sum())
        }

        // Test o tescie: szescdziesiat rund ma przejsc przez wszystkie rodzaje zaliczen.
        assertEquals(Zaliczenie.entries.toSet(), widziane)
        assertEquals(repo.load().dorobek, Repository(katalog.root).load().dorobek)
    }
}
