package com.matura.app

import com.matura.app.learn.Attempt
import com.matura.app.learn.Doliczenie
import com.matura.app.learn.Etap
import com.matura.app.learn.Limit
import com.matura.app.learn.NagrodaRundy
import com.matura.app.learn.Nagrody
import com.matura.app.learn.Odznaka
import com.matura.app.learn.Odznaki
import com.matura.app.learn.Outcome
import com.matura.app.learn.PozycjaNagrody
import com.matura.app.learn.Srs
import com.matura.app.learn.Wplyw
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
import org.junit.Test
import kotlin.random.Random

/**
 * Punkty nauki, cel dnia, seria z zapasem dni wolnych i etapy: wszystko, co ksiega dorobku
 * mowi uczniowi.
 *
 * Tak jak w PodsumowanieTest, stany slow NIE sa wpisywane recznie tam, gdzie da sie je policzyc:
 * przejscia robi [Srs.apply], a test pyta, co za nie nalezy. Caly sens ksiegi polega na tym, ze
 * nie da sie w niej zarobic niczego, czego harmonogram nie uznal, wiec gdyby te dwa miejsca
 * kiedys sie rozjechaly, ma to wyjsc tutaj, a nie na ekranie z punktami.
 *
 * Zapis na dysk i zaczep w Repository sprawdza DorobekIntegracjaTest; tu sa same reguly.
 */
class NagrodyTest {

    private companion object {
        /** Dowolny dzien od epoki UTC; jeden na caly plik, zeby testy czytalo sie jak kalendarz. */
        const val DZIS = 19_875
        const val WPIS = "w0123456789ab"

        /** Domyslny cel dnia w punktach nauki. */
        const val CEL = 30
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

    private fun dolicz(
        d: Dorobek,
        z: Zaliczenie,
        dzien: Int,
        cel: Int = CEL,
        wlasne: Boolean = false,
        naprawa: Boolean = false,
    ): Dorobek = Nagrody.dolicz(d, z, dzien, cel, wlasne, naprawa).dorobek

    private fun kilka(
        d: Dorobek,
        ile: Int,
        z: Zaliczenie,
        dzien: Int,
        cel: Int = CEL,
        wlasne: Boolean = false,
    ): Dorobek {
        var ksiega = d
        repeat(ile) { ksiega = dolicz(ksiega, z, dzien, cel, wlasne) }
        return ksiega
    }

    /** Dzien z zaliczonym domyslnym celem: trzy nowe slowa z pamieci, razem 30 punktow. */
    private fun dzienNauki(d: Dorobek, dzien: Int): Dorobek = kilka(d, 3, Zaliczenie.NOWE, dzien)

    private fun dniNauki(d: Dorobek, dni: Iterable<Int>): Dorobek =
        dni.fold(d) { ksiega, dzien -> dzienNauki(ksiega, dzien) }

    // --- tabela zaliczen: kazdy wiersz ------------------------------------------------------

    @Test
    fun `tabela punktow jest taka, jak w zalozeniach`() {
        // nazwa, punkty, z pamieci, liczy sie jako powtorka
        assertEquals(
            listOf(
                listOf("NOWE", 10, true, false),
                listOf("AWANS", 20, true, true),
                listOf("UTRWALENIE", 30, true, true),
                listOf("POWTORKA", 5, true, true),
                listOf("ROZPOZNANE", 3, false, false),
            ),
            Zaliczenie.entries.map { listOf(it.name, it.xp, it.zPamieci, it.powtorka) },
        )
    }

    @Test
    fun `nowe slowo podane z pamieci jest warte dziesiec punktow`() {
        val (po, zaliczenie) = krok(WordProgress(), proba(), DZIS)

        assertEquals(1, po.box)
        assertEquals(Zaliczenie.NOWE, zaliczenie)
        assertEquals(10, zaliczenie?.xp)
    }

    @Test
    fun `pierwsze wejscie do pudelka drugiego to awans za dwadziescia punktow`() {
        val poznane = wPudelku(1)

        val (po, zaliczenie) = krok(poznane, proba(), poznane.dueDay)

        assertEquals(2, po.box)
        assertEquals(Zaliczenie.AWANS, zaliczenie)
        assertEquals(20, zaliczenie?.xp)
    }

    @Test
    fun `pierwsze wejscie do pudelka trzeciego to utrwalenie za trzydziesci punktow`() {
        val powtarzane = wPudelku(2)

        val (po, zaliczenie) = krok(powtarzane, proba(), powtarzane.dueDay)

        assertEquals(3, po.box)
        assertEquals(Zaliczenie.UTRWALENIE, zaliczenie)
        assertEquals(30, zaliczenie?.xp)
    }

    @Test
    fun `potwierdzenie utrwalonego slowa w terminie to powtorka za piec punktow`() {
        val utrwalone = wPudelku(3)

        val (po, zaliczenie) = krok(utrwalone, proba(), utrwalone.dueDay)

        assertEquals("pudelko stoi, rusza sie sam termin", 3, po.box)
        assertEquals(utrwalone.dueDay + 7, po.dueDay)
        assertEquals(Zaliczenie.POWTORKA, zaliczenie)
        assertEquals(5, zaliczenie?.xp)
    }

    @Test
    fun `rozpoznanie nowego slowa jest warte trzy punkty i nie jest dowodem pamieci`() {
        val (po, zaliczenie) = krok(WordProgress(), proba(task = TaskKind.ROZPOZNANIE), DZIS)

        assertEquals(1, po.box)
        assertEquals(Zaliczenie.ROZPOZNANE, zaliczenie)
        assertEquals(3, zaliczenie?.xp)
        assertFalse(Zaliczenie.ROZPOZNANE.zPamieci)
    }

    @Test
    fun `pierwsza droga slowa do utrwalenia jest warta szescdziesiat punktow w trzech roznych dniach`() {
        var stan = WordProgress()
        val zaliczenia = ArrayList<Zaliczenie?>()
        val dni = ArrayList<Int>()
        repeat(3) {
            val dzien = stan.dueDay.coerceAtLeast(DZIS)
            val (po, zaliczenie) = krok(stan, proba(), dzien)
            stan = po
            zaliczenia += zaliczenie
            dni += dzien
        }

        assertEquals(listOf(Zaliczenie.NOWE, Zaliczenie.AWANS, Zaliczenie.UTRWALENIE), zaliczenia)
        assertEquals(listOf(10, 20, 30), zaliczenia.map { it?.xp })
        assertEquals(60, zaliczenia.sumOf { it?.xp ?: 0 })
        assertEquals("odstepy 1 i 3 dni: trzy rozne dni", listOf(DZIS, DZIS + 1, DZIS + 4), dni)
    }

    @Test
    fun `powrot na szczebel zdobyty wczesniej jest powtorka, a nie druga premia`() {
        // Gdyby powrot byl platny jak pierwsze wejscie, oplacaloby sie mylic: kazdy blad
        // otwieralby te same piecdziesiat punktow do zdobycia jeszcze raz.
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
        assertEquals("zapominanie nie placi lepiej niz pamietanie", 5 + 5, zaDrugie!!.xp + zaTrzecie!!.xp)
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
    fun `poprawna odpowiedz w dniu, w ktorym byla potrzebna pomoc, nie daje nic`() {
        // Pomylka, podpowiedz i pokazana odpowiedz znacza to samo: tego dnia przy tym slowie
        // byla pomoc, wiec poprawka nie jest dowodem pamieci. Zaliczyc da sie najwczesniej jutro.
        for (pomoc in listOf(Outcome.BLEDNA, Outcome.PODPOWIEDZ, Outcome.POKAZANA)) {
            val zalegle = wPudelku(1).copy(dueDay = DZIS)
            val (poPomocy, _) = krok(zalegle, proba(pomoc), DZIS)

            val (po, zaliczenie) = krok(poPomocy, proba(), DZIS)

            assertEquals("po $pomoc", 1, po.box)
            assertNull("po $pomoc", zaliczenie)
            assertEquals("po $pomoc: jutro juz tak", Zaliczenie.AWANS, krok(poPomocy, proba(), DZIS + 1).second)
        }
    }

    @Test
    fun `odpowiedz przed terminem nie daje nic`() {
        for (pudelko in 1..3) {
            val stan = wPudelku(pudelko)

            val (po, zaliczenie) = krok(stan, proba(), stan.dueDay - 1)

            assertEquals("pudelko $pudelko", stan.dueDay, po.dueDay)
            assertNull("pudelko $pudelko", zaliczenie)
        }
    }

    @Test
    fun `rozpoznanie zarabia tylko za wyjscie z pudelka zero`() {
        // Pudelko 1: kafel przesuwa termin (zmiana w harmonogramie), ale to nie jest dowod
        // pamieci. Placone dawaloby punkty codziennie za te same slowa bez jednej litery.
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

    // --- wlasnosc: ile najwyzej da jedno slowo -----------------------------------------------

    /**
     * Najwiecej punktow, ile tabela pozwala zarobic na jednym slowie w jednym kierunku przez
     * [dni] dni z zaliczeniem: 10, 20 i 30 za pierwsza droge, potem po 5 za kazdy kolejny dzien.
     */
    private fun najwyzej(dni: Int): Int = intArrayOf(0, 10, 30, 60)[minOf(dni, 3)] + 5 * maxOf(0, dni - 3)

    @Test
    fun `zadna sekwencja prob nie zarobi na jednym slowie i kierunku wiecej, niz pozwala tabela`() {
        // Wlasnosc, na ktorej stoi cala ksiega. Srs przyjmuje jedno sprawdzenie slowa na dzien
        // i kierunek, a szczyt pudelka nie maleje, wiec:
        //  - slowo i kierunek to najwyzej jedno zaliczenie dziennie,
        //  - kazda premia (nowe albo rozpoznane, awans, utrwalenie) pada najwyzej raz w zyciu,
        //  - suma po n dniach z zaliczeniem nie przekracza najwyzej(n).
        //
        // Ziarna parzyste maja zegar, ktory stoi albo idzie do przodu, i dla nich obowiazuje
        // wszystko. Ziarna nieparzyste cofaja zegar: wtedy slowo potrafi zaliczyc sie dwa razy
        // w tym samym dniu (Srs traktuje to tak samo), ale premii nadal nie da sie wziac
        // drugi raz, wiec majstrowanie przy dacie daje co najwyzej dodatkowe piatki.
        val slowa = listOf("w000000000001", "w000000000002", "w000000000003")
        val kierunki = listOf(Direction.TERM_TO_DEF, Direction.DEF_TO_TERM)
        // Poprawnych wiecej niz reszty, bo inaczej malo ktore slowo dochodzi do pudelka 3.
        val wyniki = List(6) { Outcome.POPRAWNA } + Outcome.entries
        val zadania = List(3) { TaskKind.PRZYPOMNIENIE } + TaskKind.ROZPOZNANIE
        val premie = setOf(Zaliczenie.NOWE, Zaliczenie.ROZPOZNANE, Zaliczenie.AWANS, Zaliczenie.UTRWALENIE)
        val widziane = HashMap<Zaliczenie, Int>()
        var naGranicy = 0

        for (ziarno in 1..400) {
            val zegarCofany = ziarno % 2 == 1
            val los = Random(ziarno)
            var mapa = emptyMap<String, WordProgress>()
            var ksiega = Dorobek()
            var dzien = DZIS
            val zarobione = HashMap<String, Int>()
            val dniZaliczen = HashMap<String, HashSet<Int>>()
            val wzietePremie = HashMap<String, ArrayList<Zaliczenie>>()
            repeat(150) { nr ->
                when (los.nextInt(10)) {
                    0, 1 -> dzien += los.nextInt(1, 5)
                    2 -> if (zegarCofany) dzien -= los.nextInt(1, 9)
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
                val gdzie = "ziarno $ziarno, proba $nr, $klucz"
                widziane[zaliczenie] = (widziane[zaliczenie] ?: 0) + 1
                assertEquals(gdzie, Outcome.POPRAWNA, proba.outcome)
                assertTrue("$gdzie: zaliczenie bez ruchu w harmonogramie", po.box > przed.box || po.dueDay > przed.dueDay)
                if (proba.task == TaskKind.ROZPOZNANIE) {
                    assertEquals(gdzie, Zaliczenie.ROZPOZNANE, zaliczenie)
                    assertEquals(gdzie, 0, przed.box)
                } else {
                    assertTrue("$gdzie: $zaliczenie za wpisana odpowiedz", zaliczenie.zPamieci)
                }

                val doliczenie = Nagrody.dolicz(ksiega, zaliczenie, dzien, CEL, wlasne = false, naprawa = false)
                val zaplacone = doliczenie.wplyw.xp
                assertTrue("$gdzie: zaplacone $zaplacone za $zaliczenie", zaplacone == 0 || zaplacone == zaliczenie.xp)
                assertEquals("$gdzie: ksiega urosla o co innego niz zaplacone", ksiega.xp + zaplacone, doliczenie.dorobek.xp)
                ksiega = doliczenie.dorobek

                if (zaliczenie in premie) wzietePremie.getOrPut(klucz) { ArrayList() } += zaliczenie
                val suma = (zarobione[klucz] ?: 0) + zaplacone
                zarobione[klucz] = suma
                val nowyDzien = dniZaliczen.getOrPut(klucz) { HashSet() }.add(dzien)
                if (!zegarCofany) {
                    assertTrue("$gdzie: drugie zaliczenie tego samego dnia", nowyDzien)
                    val granica = najwyzej(dniZaliczen.getValue(klucz).size)
                    assertTrue("$gdzie: $suma punktow, tabela pozwala na $granica", suma <= granica)
                    if (suma == granica && suma > 60) naGranicy++
                }
            }
            for ((klucz, lista) in wzietePremie) {
                val gdzie = "ziarno $ziarno, $klucz: $lista"
                assertEquals("$gdzie: premia wzieta dwa razy", lista.size, lista.toSet().size)
                assertFalse(
                    "$gdzie: pierwsze spotkanie zaplacone dwa razy",
                    Zaliczenie.NOWE in lista && Zaliczenie.ROZPOZNANE in lista,
                )
                assertTrue("$gdzie: premie ponad 60 punktow", lista.sumOf { it.xp } <= 60)
            }
            assertEquals("ziarno $ziarno: suma ksiegi", zarobione.values.sum(), ksiega.xp)
        }

        // Test o tescie: losowanie ma przechodzic przez wszystkie wiersze tabeli, a granica
        // ma byc osiagana, a nie tylko daleko nad wynikami.
        for (rodzaj in Zaliczenie.entries) {
            assertTrue("losowe ciagi ani razu nie daly $rodzaj", (widziane[rodzaj] ?: 0) > 0)
        }
        assertTrue("zadne slowo nie doszlo do granicy tabeli", naGranicy > 0)
    }

    // --- ksiega: dzien po dniu --------------------------------------------------------------

    @Test
    fun `pierwsze zaliczenie zaklada dzien w pustej ksiedze`() {
        val doliczenie = Nagrody.dolicz(Dorobek(), Zaliczenie.NOWE, DZIS, CEL, wlasne = false, naprawa = false)

        assertEquals(
            Doliczenie(
                Dorobek(xp = 10, dzien = DZIS, xpDzis = 10, noweDzis = 1),
                Wplyw(Zaliczenie.NOWE, 10, null),
            ),
            doliczenie,
        )
        assertEquals("uczen zaczyna z jednym dniem wolnym w zapasie", 1, doliczenie.dorobek.wolne)
    }

    @Test
    fun `tego samego dnia liczniki rosna dalej`() {
        var ksiega = dolicz(Dorobek(), Zaliczenie.NOWE, DZIS, cel = 120)
        ksiega = dolicz(ksiega, Zaliczenie.UTRWALENIE, DZIS, cel = 120)
        ksiega = dolicz(ksiega, Zaliczenie.POWTORKA, DZIS, cel = 120)
        ksiega = dolicz(ksiega, Zaliczenie.ROZPOZNANE, DZIS, cel = 120)

        assertEquals(
            Dorobek(xp = 48, dzien = DZIS, xpDzis = 48, noweDzis = 2, rozpoznaneDzis = 1, powtorki = 2),
            ksiega,
        )
    }

    @Test
    fun `powtorki licza awans, utrwalenie i powtorke, a nie pierwsze spotkanie`() {
        for (rodzaj in Zaliczenie.entries) {
            val ksiega = dolicz(Dorobek(), rodzaj, DZIS)
            val oczekiwane = if (rodzaj in setOf(Zaliczenie.AWANS, Zaliczenie.UTRWALENIE, Zaliczenie.POWTORKA)) 1 else 0
            assertEquals("po $rodzaj", oczekiwane, ksiega.powtorki)
            assertEquals("po $rodzaj", rodzaj.powtorka, ksiega.powtorki == 1)
        }
    }

    @Test
    fun `nowe na dzis licza oba pierwsze spotkania i nic poza nimi`() {
        for (rodzaj in Zaliczenie.entries) {
            val pierwszeSpotkanie = rodzaj == Zaliczenie.NOWE || rodzaj == Zaliczenie.ROZPOZNANE
            assertEquals("po $rodzaj", if (pierwszeSpotkanie) 1 else 0, dolicz(Dorobek(), rodzaj, DZIS).noweDzis)
        }
    }

    @Test
    fun `naprawa dolicza naprawione slowo i nic innego nie zmienia`() {
        val bez = dolicz(Dorobek(), Zaliczenie.UTRWALENIE, DZIS, naprawa = false)
        val z = dolicz(Dorobek(), Zaliczenie.UTRWALENIE, DZIS, naprawa = true)

        assertEquals(0, bez.naprawione)
        assertEquals(1, z.naprawione)
        assertEquals(bez, z.copy(naprawione = 0))
    }

    @Test
    fun `nastepnego dnia liczniki na dzis zaczynaja od zera, a suma zostaje`() {
        var wczoraj = kilka(Dorobek(), 2, Zaliczenie.NOWE, DZIS)
        wczoraj = kilka(wczoraj, 2, Zaliczenie.ROZPOZNANE, DZIS)
        wczoraj = dolicz(wczoraj, Zaliczenie.NOWE, DZIS, wlasne = true)
        assertEquals(36, wczoraj.xpDzis)
        assertEquals(5, wczoraj.noweDzis)
        assertEquals(2, wczoraj.rozpoznaneDzis)
        assertEquals(1, wczoraj.wlasneDzis)

        val dzisiaj = dolicz(wczoraj, Zaliczenie.AWANS, DZIS + 1)

        assertEquals(56, dzisiaj.xp)
        assertEquals(DZIS + 1, dzisiaj.dzien)
        assertEquals(20, dzisiaj.xpDzis)
        assertEquals(0, dzisiaj.noweDzis)
        assertEquals(0, dzisiaj.rozpoznaneDzis)
        assertEquals(0, dzisiaj.wlasneDzis)
    }

    // --- dzienne limity ---------------------------------------------------------------------

    @Test
    fun `szoste rozpoznanie dnia nie daje punktow, a nastepnego dnia rozpoznanie znow placi`() {
        var ksiega = Dorobek()
        val wplywy = ArrayList<Wplyw>()
        repeat(8) {
            val doliczenie = Nagrody.dolicz(ksiega, Zaliczenie.ROZPOZNANE, DZIS, CEL, wlasne = false, naprawa = false)
            ksiega = doliczenie.dorobek
            wplywy += doliczenie.wplyw
        }

        assertEquals(5, Nagrody.LIMIT_ROZPOZNAN)
        assertEquals(List(5) { Wplyw(Zaliczenie.ROZPOZNANE, 3, null) }, wplywy.take(5))
        assertEquals(List(3) { Wplyw(Zaliczenie.ROZPOZNANE, 0, Limit.ROZPOZNANIA) }, wplywy.drop(5))
        assertEquals(15, ksiega.xp)
        assertEquals(15, ksiega.xpDzis)
        assertEquals("licznik zaplaconych stoi na limicie", 5, ksiega.rozpoznaneDzis)
        assertEquals("spotkania licza sie takze bez punktow", 8, ksiega.noweDzis)

        // Limit jest na rozpoznania, a nie na dzien: slowo z pamieci placi normalnie.
        assertEquals(Wplyw(Zaliczenie.NOWE, 10, null), Nagrody.dolicz(ksiega, Zaliczenie.NOWE, DZIS, CEL, false, false).wplyw)

        val jutro = Nagrody.dolicz(ksiega, Zaliczenie.ROZPOZNANE, DZIS + 1, CEL, wlasne = false, naprawa = false)
        assertEquals(Wplyw(Zaliczenie.ROZPOZNANE, 3, null), jutro.wplyw)
        assertEquals(18, jutro.dorobek.xp)
        assertEquals(1, jutro.dorobek.rozpoznaneDzis)
    }

    @Test
    fun `samymi rozpoznaniami nie da sie zaliczyc celu dnia`() {
        // Piec razy trzy punkty to pietnascie: polowa najnizszego celu. Kolejne nic nie daja.
        val ksiega = kilka(Dorobek(), 200, Zaliczenie.ROZPOZNANE, DZIS)

        assertEquals(15, ksiega.xpDzis)
        assertEquals(0, ksiega.dniNauki)
        assertFalse(Nagrody.celZaliczony(ksiega, DZIS))
        assertTrue(Nagrody.LIMIT_ROZPOZNAN * Zaliczenie.ROZPOZNANE.xp < Nagrody.CELE_DNIA.min())
    }

    @Test
    fun `jedenaste zaliczenie wlasnego slowa nie daje punktow, a slownik gry placi dalej`() {
        assertEquals(10, Nagrody.LIMIT_WLASNYCH)
        var ksiega = Dorobek()
        val wplywy = ArrayList<Wplyw>()
        // Kazdy rodzaj zaliczenia liczy sie do limitu, nie tylko nowe slowa.
        val rodzaje = listOf(Zaliczenie.NOWE, Zaliczenie.AWANS, Zaliczenie.POWTORKA, Zaliczenie.ROZPOZNANE)
        repeat(13) { nr ->
            val doliczenie = Nagrody.dolicz(ksiega, rodzaje[nr % rodzaje.size], DZIS, 120, wlasne = true, naprawa = false)
            ksiega = doliczenie.dorobek
            wplywy += doliczenie.wplyw
        }

        assertTrue("pierwsze dziesiec placi", wplywy.take(10).all { it.limit == null && it.xp == it.rodzaj.xp })
        assertTrue("kolejne nie", wplywy.drop(10).all { it.limit == Limit.WLASNE && it.xp == 0 })
        val zaplacone = wplywy.take(10).sumOf { it.xp }
        assertEquals(zaplacone, ksiega.xp)
        assertEquals(10, ksiega.wlasneDzis)
        assertEquals(
            "powtorki ponad limit nie wchodza do licznika powtorek",
            wplywy.take(10).count { it.rodzaj.powtorka },
            ksiega.powtorki,
        )

        // Slowo ze slownika gry tego samego dnia placi, jakby limitu nie bylo.
        val zeSlownika = Nagrody.dolicz(ksiega, Zaliczenie.NOWE, DZIS, 120, wlasne = false, naprawa = false)
        assertEquals(Wplyw(Zaliczenie.NOWE, 10, null), zeSlownika.wplyw)
        assertEquals(10, zeSlownika.dorobek.wlasneDzis)

        // A nastepnego dnia wlasne slowa placa od nowa.
        val jutro = Nagrody.dolicz(ksiega, Zaliczenie.NOWE, DZIS + 1, 120, wlasne = true, naprawa = false)
        assertEquals(Wplyw(Zaliczenie.NOWE, 10, null), jutro.wplyw)
        assertEquals(1, jutro.dorobek.wlasneDzis)
    }

    @Test
    fun `limit wlasnych slow ma pierwszenstwo przed limitem rozpoznan`() {
        // Piec zaplaconych rozpoznan wlasnych slow: szoste zatrzymuje limit rozpoznan.
        val poPieciu = kilka(Dorobek(), 5, Zaliczenie.ROZPOZNANE, DZIS, wlasne = true)
        val szoste = Nagrody.dolicz(poPieciu, Zaliczenie.ROZPOZNANE, DZIS, CEL, wlasne = true, naprawa = false)
        assertEquals(Limit.ROZPOZNANIA, szoste.wplyw.limit)
        assertEquals("niezaplacone nie zuzywa limitu wlasnych", 5, szoste.dorobek.wlasneDzis)

        // Po dziesieciu zaplaconych wlasnych zatrzymuje juz limit wlasnych, takze rozpoznanie.
        val poDziesieciu = kilka(poPieciu, 5, Zaliczenie.POWTORKA, DZIS, wlasne = true)
        assertEquals(10, poDziesieciu.wlasneDzis)
        val kolejne = Nagrody.dolicz(poDziesieciu, Zaliczenie.ROZPOZNANE, DZIS, CEL, wlasne = true, naprawa = false)
        assertEquals(Wplyw(Zaliczenie.ROZPOZNANE, 0, Limit.WLASNE), kolejne.wplyw)
    }

    @Test
    fun `zdania o limitach podaja te same liczby co stale`() {
        val liczba = Regex("\\d+")
        fun liczby(tekst: String) = liczba.findAll(tekst).map { it.value.toInt() }.toList()

        assertEquals(listOf(Nagrody.LIMIT_ROZPOZNAN), liczby(Limit.ROZPOZNANIA.pl))
        assertEquals(listOf(Nagrody.LIMIT_ROZPOZNAN), liczby(Limit.ROZPOZNANIA.en))
        assertEquals(listOf(Nagrody.LIMIT_WLASNYCH), liczby(Limit.WLASNE.pl))
        assertEquals(listOf(Nagrody.LIMIT_WLASNYCH), liczby(Limit.WLASNE.en))
    }

    // --- cel dnia w punktach ----------------------------------------------------------------

    @Test
    fun `cel dnia przyjmuje tylko dozwolone wartosci`() {
        assertEquals(listOf(30, 60, 120), Nagrody.CELE_DNIA)
        for (cel in Nagrody.CELE_DNIA) assertEquals(cel, Nagrody.celDnia(cel))
        // 5, 10 i 20 to cele z czasow, gdy cel liczyl slowa: dzis czytane jak domyslny.
        for (zly in listOf(0, -5, 1, 5, 10, 20, 29, 31, 90, 121, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals("cel $zly", 30, Nagrody.celDnia(zly))
        }
        assertEquals(30, Nagrody.CEL_DOMYSLNY)
        assertEquals("wartosc domyslna opcji i ksiegi to jedna liczba", Nagrody.CEL_DOMYSLNY, Options().celDnia)
    }

    @Test
    fun `trzy nowe slowa wpisane w pierwszej sesji zaliczaja domyslny cel`() {
        // Nowy uczen, pierwsza sesja: trzy slowa z pamieci, kazde przez harmonogram.
        var ksiega = Dorobek()
        for ((nr, slowo) in listOf("w000000000001", "w000000000002", "w000000000003").withIndex()) {
            val (_, zaliczenie) = krok(WordProgress(), proba(entryId = slowo), DZIS)
            assertFalse("cel zaliczony juz po $nr slowach", Nagrody.celZaliczony(ksiega, DZIS))
            ksiega = dolicz(ksiega, zaliczenie!!, DZIS, cel = Nagrody.celDnia(Options().celDnia))
        }

        assertEquals(30, ksiega.xpDzis)
        assertTrue(Nagrody.celZaliczony(ksiega, DZIS))
        assertEquals(1, ksiega.dniNauki)
        assertEquals(1, ksiega.seria)
    }

    @Test
    fun `kazdy z trzech celow zalicza dzien dokladnie w chwili, w ktorej punkty do niego dochodza`() {
        for (cel in listOf(30, 60, 120)) {
            // Powtorki po 5 punktow: najdrobniejszy krok, wiec granice widac co do zaliczenia.
            val tuzPrzed = kilka(Dorobek(), cel / 5 - 1, Zaliczenie.POWTORKA, DZIS, cel)
            assertEquals("cel $cel", cel - 5, tuzPrzed.xpDzis)
            assertEquals("cel $cel", 0, tuzPrzed.dniNauki)
            assertFalse("cel $cel", Nagrody.celZaliczony(tuzPrzed, DZIS))

            val naCelu = dolicz(tuzPrzed, Zaliczenie.POWTORKA, DZIS, cel)
            assertEquals("cel $cel", cel, naCelu.xpDzis)
            assertEquals("cel $cel", 1, naCelu.dniNauki)
            assertEquals("cel $cel", 1, naCelu.seria)
            assertEquals("cel $cel", 1, naCelu.rekordSerii)
            assertEquals("cel $cel", DZIS, naCelu.seriaDzien)
            assertTrue("cel $cel", Nagrody.celZaliczony(naCelu, DZIS))
        }
    }

    @Test
    fun `cel przekroczony jednym zaliczeniem tez sie liczy`() {
        // 25 punktow i utrwalenie za 30: razem 55, czyli skok ponad cel bez stania na nim.
        val przed = kilka(Dorobek(), 5, Zaliczenie.POWTORKA, DZIS)

        val po = dolicz(przed, Zaliczenie.UTRWALENIE, DZIS)

        assertEquals(55, po.xpDzis)
        assertEquals(1, po.dniNauki)
    }

    @Test
    fun `dzien liczy sie raz, takze gdy punkty przekrocza cel po raz drugi`() {
        val zCelem = dzienNauki(Dorobek(), DZIS)
        assertEquals(1, zCelem.dniNauki)

        // Dalsza nauka tego dnia dopisuje punkty: 60, 90, 120. Dnia nie liczy drugi raz.
        val dalej = kilka(zCelem, 9, Zaliczenie.NOWE, DZIS)

        assertEquals(120, dalej.xpDzis)
        assertEquals(1, dalej.dniNauki)
        assertEquals(1, dalej.seria)
        assertEquals(DZIS, dalej.seriaDzien)
    }

    @Test
    fun `podniesienie celu pozniej tego samego dnia nie cofa zaliczonego dnia`() {
        val zCelem = kilka(Dorobek(), 3, Zaliczenie.NOWE, DZIS, cel = 30)
        assertEquals(1, zCelem.dniNauki)

        // Uczen podnosi cel do 120 i uczy sie dalej, az dojdzie takze do nowego celu.
        val dalej = kilka(zCelem, 20, Zaliczenie.POWTORKA, DZIS, cel = 120)

        assertEquals(130, dalej.xpDzis)
        assertEquals("dzien nie moze sie odliczyc ani policzyc drugi raz", 1, dalej.dniNauki)
        assertEquals(1, dalej.seria)
        assertTrue(Nagrody.celZaliczony(dalej, DZIS))
    }

    @Test
    fun `obnizenie celu zalicza dzien dopiero przy nastepnym zaliczeniu`() {
        val bezCelu = kilka(Dorobek(), 4, Zaliczenie.NOWE, DZIS, cel = 120)
        assertEquals(40, bezCelu.xpDzis)
        assertEquals(0, bezCelu.dniNauki)

        // Sama zmiana opcji ksiegi nie rusza (zmiana opcji nie zna zegara); rusza ja nastepna
        // nauka, takze rozpoznanie.
        val poZmianie = dolicz(bezCelu, Zaliczenie.ROZPOZNANE, DZIS, cel = 30)

        assertEquals(43, poZmianie.xpDzis)
        assertEquals(1, poZmianie.dniNauki)
        assertTrue(Nagrody.celZaliczony(poZmianie, DZIS))
    }

    @Test
    fun `cel zerowy albo ujemny nie zalicza dnia bez ani jednego punktu`() {
        // Zaliczenie ponad limit dopisuje zero punktow; przy celu zero "0 >= 0" liczyloby dzien.
        val naLimicie = kilka(Dorobek(), 5, Zaliczenie.ROZPOZNANE, DZIS, cel = 120)
        val bezPunktow = naLimicie.copy(xpDzis = 0)
        for (cel in listOf(0, -1, Int.MIN_VALUE)) {
            val ksiega = dolicz(bezPunktow, Zaliczenie.ROZPOZNANE, DZIS, cel)
            assertEquals("cel $cel", 0, ksiega.xpDzis)
            assertEquals("cel $cel", 0, ksiega.dniNauki)
            assertEquals("cel $cel: pierwszy punkt juz liczy", 1, dolicz(ksiega, Zaliczenie.NOWE, DZIS, cel).dniNauki)
        }
    }

    // --- seria i zapas dni wolnych ----------------------------------------------------------

    @Test
    fun `seria rosnie dzien po dniu, a zapas zostaje nietkniety`() {
        val ksiega = dniNauki(Dorobek(), DZIS..DZIS + 3)

        assertEquals(4, ksiega.seria)
        assertEquals(4, ksiega.rekordSerii)
        assertEquals(4, ksiega.dniNauki)
        assertEquals(DZIS + 3, ksiega.seriaDzien)
        assertEquals(1, ksiega.wolne)
        assertEquals(1, Dorobek().wolne)
    }

    @Test
    fun `jeden opuszczony dzien zuzywa dzien wolny i seria trwa`() {
        // DZIS + 1 bez nauki.
        val ksiega = dniNauki(Dorobek(), listOf(DZIS, DZIS + 2))

        assertEquals(2, ksiega.seria)
        assertEquals(2, ksiega.dniNauki)
        assertEquals(0, ksiega.wolne)
    }

    @Test
    fun `bez zapasu opuszczony dzien zaczyna serie od nowa, a reszta zostaje`() {
        val przed = dniNauki(Dorobek(), listOf(DZIS, DZIS + 2))
        assertEquals(0, przed.wolne)

        val po = dzienNauki(przed, DZIS + 4)

        assertEquals(1, po.seria)
        assertEquals(0, po.wolne)
        // Przerwana seria niczego nie odbiera: rekord, dni nauki, punkty i odznaki zostaja.
        assertEquals("rekordu nikt nie odbiera", 2, po.rekordSerii)
        assertEquals("dni nauki tylko rosna", 3, po.dniNauki)
        assertEquals(90, po.xp)
        assertEquals(przed.odznaki, po.odznaki)
    }

    @Test
    fun `dzien wolny dochodzi za kazde siedem dni serii, najwyzej do dwoch`() {
        var ksiega = Dorobek()
        val zapasPoDniu = ArrayList<Int>()
        for (dzien in DZIS until DZIS + 15) {
            ksiega = dzienNauki(ksiega, dzien)
            zapasPoDniu += ksiega.wolne
        }

        assertEquals(15, ksiega.seria)
        assertEquals(List(6) { 1 } + List(9) { 2 }, zapasPoDniu)
        assertEquals(2, Nagrody.MAX_WOLNYCH)
        assertEquals(7, Nagrody.DNI_NA_WOLNE)
    }

    @Test
    fun `zapas zuzyty do zera odbudowuje sie po jednym dniu wolnym na siedem dni serii`() {
        // Dzien 1, przerwa, dzien 2 serii: zapas schodzi do zera. Potem nauka dzien po dniu.
        var ksiega = dniNauki(Dorobek(), listOf(DZIS, DZIS + 2))
        val zapasPrzySerii = LinkedHashMap<Int, Int>()
        var dzien = DZIS + 2
        while (ksiega.seria < 22) {
            dzien++
            ksiega = dzienNauki(ksiega, dzien)
            zapasPrzySerii[ksiega.seria] = ksiega.wolne
        }

        assertEquals(0, zapasPrzySerii[6])
        assertEquals(1, zapasPrzySerii[7])
        assertEquals(1, zapasPrzySerii[13])
        assertEquals(2, zapasPrzySerii[14])
        assertEquals("ponad dwa zapas nie rosnie", 2, zapasPrzySerii[21])
    }

    @Test
    fun `dwa opuszczone dni pokrywa zapas dwoch dni wolnych`() {
        val tydzien = dniNauki(Dorobek(), DZIS until DZIS + 7)
        assertEquals(2, tydzien.wolne)

        // DZIS + 7 i DZIS + 8 bez nauki.
        val po = dzienNauki(tydzien, DZIS + 9)

        assertEquals(8, po.seria)
        assertEquals(0, po.wolne)
        assertEquals(8, po.dniNauki)
    }

    @Test
    fun `dwa opuszczone dni przy jednym dniu wolnym zaczynaja serie od nowa i zapasu nie ruszaja`() {
        val przed = dniNauki(Dorobek(), DZIS..DZIS + 2)
        assertEquals(1, przed.wolne)

        // DZIS + 3 i DZIS + 4 bez nauki: jeden dzien wolny nie pokryje dwoch.
        val po = dzienNauki(przed, DZIS + 5)

        assertEquals(1, po.seria)
        assertEquals("zapas zostaje na nastepna serie", 1, po.wolne)
        assertEquals(3, po.rekordSerii)
        assertEquals(4, po.dniNauki)
    }

    @Test
    fun `trzy opuszczone dni zaczynaja serie od nowa nawet przy pelnym zapasie`() {
        val tydzien = dniNauki(Dorobek(), DZIS until DZIS + 7)
        assertEquals(2, tydzien.wolne)

        val po = dzienNauki(tydzien, DZIS + 10)

        assertEquals(1, po.seria)
        assertEquals(2, po.wolne)
        assertEquals(7, po.rekordSerii)
        assertEquals(8, po.dniNauki)
    }

    @Test
    fun `dzien wolny zuzyty i zdobyty tego samego dnia`() {
        val szescDni = dniNauki(Dorobek(), DZIS until DZIS + 6)
        assertEquals(6, szescDni.seria)
        assertEquals(1, szescDni.wolne)

        // Jeden dzien przerwy, a potem siodmy dzien serii: jeden schodzi, jeden dochodzi.
        val po = dzienNauki(szescDni, DZIS + 7)

        assertEquals(7, po.seria)
        assertEquals(1, po.wolne)
    }

    @Test
    fun `nauka co drugi dzien nigdy nie dochodzi do serii trzech`() {
        var ksiega = Dorobek()
        var najdluzsza = 0
        for (nr in 0 until 80) {
            ksiega = dzienNauki(ksiega, DZIS + 2 * nr)
            najdluzsza = maxOf(najdluzsza, ksiega.seria)
        }

        assertEquals(2, najdluzsza)
        assertEquals(2, ksiega.rekordSerii)
        assertEquals("kazdy z tych dni jest dniem nauki", 80, ksiega.dniNauki)
        assertEquals(0, ksiega.wolne)
    }

    @Test
    fun `dzien z nauka, ale bez celu, jest dla serii dniem opuszczonym`() {
        var ksiega = dzienNauki(Dorobek(), DZIS)
        ksiega = kilka(ksiega, 2, Zaliczenie.NOWE, DZIS + 1)
        assertEquals(20, ksiega.xpDzis)
        assertEquals(1, ksiega.seria)
        assertEquals(DZIS, ksiega.seriaDzien)
        assertEquals("zapas schodzi dopiero wtedy, gdy seria idzie dalej", 1, ksiega.wolne)

        ksiega = dzienNauki(ksiega, DZIS + 2)
        assertEquals(2, ksiega.seria)
        assertEquals(0, ksiega.wolne)
    }

    @Test
    fun `zapas spoza zakresu w pliku jest czytany jak dozwolony`() {
        val zaDuzo = Dorobek(seria = 3, seriaDzien = DZIS, dzien = DZIS, wolne = 99, rekordSerii = 3, dniNauki = 3)
        assertEquals("dwa dni to najwiecej", 3, Nagrody.seriaNaDzis(zaDuzo, DZIS + 3))
        assertEquals(0, Nagrody.seriaNaDzis(zaDuzo, DZIS + 4))
        assertEquals(2, Nagrody.seriaCzeka(zaDuzo, DZIS + 3))
        val poPrzerwie = dzienNauki(zaDuzo, DZIS + 4)
        assertEquals(1, poPrzerwie.seria)
        assertEquals(2, poPrzerwie.wolne)

        val ujemny = zaDuzo.copy(wolne = -4)
        assertEquals(3, Nagrody.seriaNaDzis(ujemny, DZIS + 1))
        assertEquals(0, Nagrody.seriaNaDzis(ujemny, DZIS + 2))
        val poDniu = dzienNauki(ujemny, DZIS + 2)
        assertEquals(1, poDniu.seria)
        assertEquals(0, poDniu.wolne)
    }

    // --- zegar ------------------------------------------------------------------------------

    @Test
    fun `zegar cofniety przenosi ostatni zaliczony dzien na dzis i niczego nie dolicza`() {
        val ksiega = dniNauki(Dorobek(), DZIS - 2..DZIS)
        assertEquals(3, ksiega.seria)

        // Zegar telefonu cofniety o piec dni; uczen robi tam caly cel i jeszcze troche.
        val cofniety = kilka(ksiega, 4, Zaliczenie.NOWE, DZIS - 5)

        assertEquals("punkty za nauke zostaja", 130, cofniety.xp)
        assertEquals("ksiega idzie za zegarem", DZIS - 5, cofniety.dzien)
        assertEquals("liczniki na dzis zaczely od zera", 40, cofniety.xpDzis)
        assertEquals(4, cofniety.noweDzis)
        assertEquals("ostatni zaliczony dzien przeniesiony na dzis", DZIS - 5, cofniety.seriaDzien)
        assertEquals("i nic poza tym", ksiega.seria, cofniety.seria)
        assertEquals(ksiega.dniNauki, cofniety.dniNauki)
        assertEquals(ksiega.rekordSerii, cofniety.rekordSerii)
        assertEquals(ksiega.wolne, cofniety.wolne)
        assertTrue("ten dzien uchodzi za juz zajety", Nagrody.celZaliczony(cofniety, DZIS - 5))

        // Nastepny prawdziwy dzien liczy sie znow normalnie.
        val nastepny = dzienNauki(cofniety, DZIS - 4)
        assertEquals(4, nastepny.seria)
        assertEquals(4, nastepny.dniNauki)
        assertEquals(DZIS - 4, nastepny.seriaDzien)
    }

    @Test
    fun `telefon przestawiony na rok 2030 i z powrotem liczy znow od nastepnego prawdziwego dnia`() {
        val ksiega = dniNauki(Dorobek(), DZIS - 1..DZIS)
        val w2030 = dzienNauki(ksiega, DZIS + 1200)
        assertEquals("dzien w przyszlosci liczy sie jak kazdy inny", 3, w2030.dniNauki)
        assertEquals(1, w2030.seria)
        assertEquals(DZIS + 1200, w2030.seriaDzien)

        // Zegar wraca. Uczciwy uczen nie moze przez to stracic celu dnia na trzy lata.
        val zPowrotem = dzienNauki(w2030, DZIS + 1)
        assertEquals("dzien powrotu uchodzi za zajety", 3, zPowrotem.dniNauki)
        assertEquals(1, zPowrotem.seria)
        assertEquals(DZIS + 1, zPowrotem.seriaDzien)
        assertEquals(30, zPowrotem.xpDzis)

        val nastepny = dzienNauki(zPowrotem, DZIS + 2)
        assertEquals(4, nastepny.dniNauki)
        assertEquals(2, nastepny.seria)
    }

    @Test
    fun `ostatnia milisekunda doby i pierwsza nastepnej to dwa rozne dni`() {
        val polnoc = (DZIS + 1).toLong() * Srs.MS_PER_DAY
        val przed = Srs.dayOf(polnoc - 1)       // 23:59:59.999 UTC
        val po = Srs.dayOf(polnoc)              // 00:00:00.000 UTC
        assertEquals(DZIS, przed)
        assertEquals(DZIS + 1, po)

        val wieczorem = dzienNauki(Dorobek(), przed)
        val poPolnocy = dolicz(wieczorem, Zaliczenie.NOWE, po)

        assertEquals(10, poPolnocy.xpDzis)
        assertEquals(1, poPolnocy.noweDzis)
        assertEquals("wczorajszy cel zostal policzony wczoraj", 1, poPolnocy.dniNauki)
        assertFalse(Nagrody.celZaliczony(poPolnocy, po))
        assertEquals(2, dzienNauki(wieczorem, po).seria)
    }

    @Test
    fun `znacznik nigdy nie wchodzi do zadnej arytmetyki`() {
        // NO_DAY to Int.MIN_VALUE. Odejmowanie od niego przekreca licznik i z "nigdy" robi
        // "wczoraj", dlatego kazde uzycie zaczyna od porownania rownosciowego. Ksiegi nizej sa
        // celowo niespojne (seria bez dnia), zeby blad w rachunku mial sie czym pokazac.
        val bezDnia = Dorobek(seria = 7, xpDzis = 30, noweDzis = 3, xp = 30)
        assertEquals(NIGDY, bezDnia.dzien)
        assertEquals(NIGDY, bezDnia.seriaDzien)

        for (dzien in listOf(NIGDY, NIGDY + 1, NIGDY + 2, -1, 0, DZIS, Int.MAX_VALUE)) {
            assertEquals("seria w dniu $dzien", 0, Nagrody.seriaNaDzis(bezDnia, dzien))
            assertEquals("czekanie w dniu $dzien", 0, Nagrody.seriaCzeka(bezDnia, dzien))
            assertFalse("cel w dniu $dzien", Nagrody.celZaliczony(bezDnia, dzien))
            assertEquals("punkty w dniu $dzien", 0, Nagrody.xpNaDzis(bezDnia, dzien))
            assertEquals("nowe w dniu $dzien", 0, Nagrody.noweNaDzis(bezDnia, dzien))
        }

        // Cel osiagniety dzien "po nigdy": to pierwszy dzien serii, a nie osmy.
        val pierwszy = dzienNauki(bezDnia.copy(xpDzis = 0, noweDzis = 0), NIGDY + 1)
        assertEquals(1, pierwszy.seria)
        assertEquals(NIGDY + 1, pierwszy.seriaDzien)

        // Dzien proby rowny znacznikowi nie jest dniem: dochodza same punkty.
        assertEquals(
            Doliczenie(bezDnia.copy(xp = 40), Wplyw(Zaliczenie.NOWE, 10, null)),
            Nagrody.dolicz(bezDnia, Zaliczenie.NOWE, NIGDY, CEL, wlasne = true, naprawa = false),
        )

        // Oba konce zakresu: roznica dni liczona w Int przekrecilaby sie.
        val dawno = Dorobek(seria = 3, seriaDzien = -5, dzien = -5, wolne = 2)
        assertEquals(0, Nagrody.seriaNaDzis(dawno, Int.MAX_VALUE))
        assertEquals(0, Nagrody.seriaCzeka(dawno, Int.MAX_VALUE))
        assertEquals(1, dzienNauki(dawno, Int.MAX_VALUE).seria)

        val wPrzyszlosci = Dorobek(seria = 3, seriaDzien = Int.MAX_VALUE, dzien = Int.MAX_VALUE, dniNauki = 3)
        val cofniety = dzienNauki(wPrzyszlosci, NIGDY + 1)
        assertEquals("przeniesienie, nie doliczenie", 3, cofniety.dniNauki)
        assertEquals(NIGDY + 1, cofniety.seriaDzien)
        assertEquals(3, Nagrody.seriaNaDzis(wPrzyszlosci, NIGDY + 1))
    }

    @Test
    fun `nic, co uczen zdobyl, nie maleje, cokolwiek zrobi zegar`() {
        // Zasada projektu: uczniowi niczego nie odbieramy. Dowolne zaliczenia, cele, slowa
        // wlasne i ze slownika: punkty, dni nauki, rekord serii, powtorki i naprawione moga
        // tylko rosnac, a liczniki trzymaja sie swoich zakresow.
        //
        // Ziarna parzyste: zegar stoi albo idzie do przodu, wiec dodatkowo zaden dzien nie
        // liczy sie do dni nauki dwa razy. Ziarna nieparzyste cofaja zegar; tam jeden dzien
        // kalendarza moze policzyc sie dwa razy (znane i przyjete), ale nic nie maleje.
        var zaliczoneDni = 0
        var cofnieciaZegara = 0
        var zuzyteWolne = 0
        val limity = HashMap<Limit, Int>()
        for (ziarno in 1..300) {
            val zegarCofany = ziarno % 2 == 1
            val los = Random(ziarno)
            var ksiega = Dorobek()
            var dzien = DZIS
            val dniZCelem = HashSet<Int>()
            repeat(400) { nr ->
                // Zegar zwykle stoi (dzien nauki ma kilka zaliczen), czasem idzie do przodu,
                // a z rzadka cofa sie o kilka dni.
                val skok = when (los.nextInt(40)) {
                    0 -> if (zegarCofany) -los.nextInt(1, 6) else 0
                    in 1..5 -> los.nextInt(1, 4)
                    else -> 0
                }
                if (skok < 0) cofnieciaZegara++
                dzien += skok
                val cel = Nagrody.CELE_DNIA[los.nextInt(Nagrody.CELE_DNIA.size)]
                val zaliczenie = Zaliczenie.entries[los.nextInt(Zaliczenie.entries.size)]
                val wlasne = los.nextInt(3) == 0
                val naprawa = !wlasne && zaliczenie == Zaliczenie.UTRWALENIE && los.nextBoolean()

                val doliczenie = Nagrody.dolicz(ksiega, zaliczenie, dzien, cel, wlasne, naprawa)
                val po = doliczenie.dorobek
                val wplyw = doliczenie.wplyw

                val gdzie = "ziarno $ziarno, krok $nr"
                assertEquals(gdzie, zaliczenie, wplyw.rodzaj)
                assertTrue(gdzie, wplyw.xp == 0 || wplyw.xp == zaliczenie.xp)
                assertEquals("$gdzie: zero punktow i limit ida razem", wplyw.xp == 0, wplyw.limit != null)
                wplyw.limit?.let { limity[it] = (limity[it] ?: 0) + 1 }
                assertEquals(gdzie, ksiega.xp + wplyw.xp, po.xp)
                assertEquals(gdzie, dzien, po.dzien)
                assertTrue(gdzie, po.dniNauki - ksiega.dniNauki in 0..1)
                assertTrue(gdzie, po.powtorki - ksiega.powtorki in 0..1)
                assertEquals(gdzie, ksiega.naprawione + (if (naprawa) 1 else 0), po.naprawione)
                assertTrue(gdzie, po.rekordSerii >= ksiega.rekordSerii)
                assertTrue(gdzie, po.rekordSerii >= po.seria)
                assertTrue(gdzie, po.seria in 0..po.dniNauki)
                assertTrue("$gdzie: zapas ${po.wolne}", po.wolne in 0..Nagrody.MAX_WOLNYCH)
                assertTrue(gdzie, po.xpDzis in 0..po.xp)
                assertTrue(gdzie, po.noweDzis >= 0)
                assertTrue(gdzie, po.rozpoznaneDzis in 0..Nagrody.LIMIT_ROZPOZNAN)
                assertTrue(gdzie, po.wlasneDzis in 0..Nagrody.LIMIT_WLASNYCH)
                if (po.wolne < ksiega.wolne) zuzyteWolne++
                if (po.dniNauki > ksiega.dniNauki) {
                    assertEquals(gdzie, dzien, po.seriaDzien)
                    assertTrue("$gdzie: dzien zaliczony przy ${po.xpDzis} punktach i celu $cel", po.xpDzis >= cel)
                    val pierwszyRaz = dniZCelem.add(dzien)
                    if (!zegarCofany) assertTrue("$gdzie: dzien $dzien policzony drugi raz", pierwszyRaz)
                } else {
                    assertEquals("$gdzie: seria ruszyla sie bez zaliczonego dnia", ksiega.seria, po.seria)
                    assertEquals("$gdzie: zapas ruszyl sie bez zaliczonego dnia", ksiega.wolne, po.wolne)
                }
                ksiega = po
            }
            zaliczoneDni += ksiega.dniNauki
        }
        // Test o tescie: losowanie ma naprawde zaliczac dni, cofac zegar, zuzywac dni wolne
        // i dochodzic do obu limitow.
        assertTrue("za malo zaliczonych dni: $zaliczoneDni", zaliczoneDni > 1000)
        assertTrue("za malo cofniec zegara: $cofnieciaZegara", cofnieciaZegara > 500)
        assertTrue("za malo zuzytych dni wolnych: $zuzyteWolne", zuzyteWolne > 100)
        assertEquals("oba limity", Limit.entries.toSet(), limity.keys)
    }

    // --- to, co ekran pokazuje dzis ---------------------------------------------------------

    @Test
    fun `seria do pokazania zyje tak dlugo, jak starcza dni wolnych, i potem gasnie`() {
        val ksiega = dniNauki(Dorobek(), DZIS - 2..DZIS)
        assertEquals(1, ksiega.wolne)

        assertEquals(3, Nagrody.seriaNaDzis(ksiega, DZIS))
        assertEquals("jutro seria czeka na dzisiejszy cel", 3, Nagrody.seriaNaDzis(ksiega, DZIS + 1))
        assertEquals("pojutrze pokryje ja dzien wolny", 3, Nagrody.seriaNaDzis(ksiega, DZIS + 2))
        assertEquals(0, Nagrody.seriaNaDzis(ksiega, DZIS + 3))
        // W ksiedze stara dlugosc zostaje az do nastepnego celu; ekran ma czytac seriaNaDzis.
        assertEquals(3, ksiega.seria)

        assertEquals(listOf(0, 0, 1, 0), (0..3).map { Nagrody.seriaCzeka(ksiega, DZIS + it) })
    }

    @Test
    fun `seria czeka przez jeden albo dwa dni, zaleznie od zapasu`() {
        val tydzien = dniNauki(Dorobek(), DZIS - 6..DZIS)
        assertEquals(2, tydzien.wolne)

        assertEquals(listOf(7, 7, 7, 7, 0), (0..4).map { Nagrody.seriaNaDzis(tydzien, DZIS + it) })
        assertEquals(listOf(0, 0, 1, 2, 0), (0..4).map { Nagrody.seriaCzeka(tydzien, DZIS + it) })

        val bezZapasu = tydzien.copy(wolne = 0)
        assertEquals(listOf(7, 7, 0), (0..2).map { Nagrody.seriaNaDzis(bezZapasu, DZIS + it) })
        assertEquals(listOf(0, 0, 0), (0..2).map { Nagrody.seriaCzeka(bezZapasu, DZIS + it) })
    }

    @Test
    fun `seria pokazana na ekranie zgadza sie z tym, co zrobi doliczenie`() {
        // Ekran obiecuje "seria zyje" dokladnie wtedy, gdy dzisiejszy cel ja przedluzy,
        // i mowi, ile dni wolnych to zuzyje.
        for (zapas in 0..2) {
            val ksiega = dniNauki(Dorobek(), DZIS - 2..DZIS).copy(wolne = zapas)
            for (odstep in 1..5) {
                val dzien = DZIS + odstep
                val gdzie = "zapas $zapas, odstep $odstep"
                val po = dzienNauki(ksiega, dzien)
                val zyje = Nagrody.seriaNaDzis(ksiega, dzien) > 0
                assertEquals(gdzie, zyje, po.seria == ksiega.seria + 1)
                assertEquals(gdzie, if (zyje) 4 else 1, po.seria)
                assertEquals(gdzie, Nagrody.seriaCzeka(ksiega, dzien), zapas - po.wolne)
            }
        }
    }

    @Test
    fun `punkty i nowe slowa na dzis sa zerem, gdy ksiega pamieta inny dzien`() {
        val ksiega = kilka(Dorobek(), 2, Zaliczenie.NOWE, DZIS)

        assertEquals(20, Nagrody.xpNaDzis(ksiega, DZIS))
        assertEquals(2, Nagrody.noweNaDzis(ksiega, DZIS))
        for (inny in listOf(DZIS + 1, DZIS - 1)) {
            assertEquals(0, Nagrody.xpNaDzis(ksiega, inny))
            assertEquals(0, Nagrody.noweNaDzis(ksiega, inny))
        }
    }

    @Test
    fun `pierscien liczy pelne cwiartki i jest pelny tylko dla dnia zaliczonego`() {
        // Co 5 punktow przy celu 30: bez naciaganej pierwszej cwiartki, a przy samych punktach
        // najwyzej trzy cwiartki, chocby punktow bylo ponad cel.
        assertEquals(
            listOf(0, 0, 1, 2, 2, 3, 3, 3, 3),
            (0..40 step 5).map { Nagrody.pierscien(it, 30, zaliczony = false) },
        )
        assertEquals(listOf(0, 1, 2, 3, 3), listOf(0, 15, 30, 45, 60).map { Nagrody.pierscien(it, 60, false) })
        assertEquals(listOf(0, 0, 1, 2, 3, 3), listOf(0, 29, 30, 60, 90, 120).map { Nagrody.pierscien(it, 120, false) })

        for (cel in listOf(1, 30, 60, 120, 1000)) {
            var poprzedni = 0
            for (punkty in 0..cel + 10) {
                val stan = Nagrody.pierscien(punkty, cel, zaliczony = false)
                assertTrue("cel $cel, punkty $punkty", stan in 0..3)
                if (punkty == 0) assertEquals(0, stan)
                assertTrue("pierscien sie cofnal", stan >= poprzedni)
                poprzedni = stan
                assertEquals("dzien zaliczony to zawsze pelny pierscien", 4, Nagrody.pierscien(punkty, cel, zaliczony = true))
            }
        }
        // Liczby spoza zakresu nie wywracaja ekranu.
        assertEquals(0, Nagrody.pierscien(-3, 30, false))
        assertEquals(3, Nagrody.pierscien(1, 0, false))
        assertEquals(3, Nagrody.pierscien(Int.MAX_VALUE, 30, false))
        assertEquals(3, Nagrody.pierscien(Int.MAX_VALUE, 1, false))
        assertEquals(4, Nagrody.pierscien(0, 30, true))
    }

    @Test
    fun `pierscien z ksiegi jest pelny po zaliczeniu i zostaje pelny po podniesieniu celu`() {
        val ksiega = dzienNauki(Dorobek(), DZIS)
        fun pierscien(cel: Int, dzien: Int) =
            Nagrody.pierscien(Nagrody.xpNaDzis(ksiega, dzien), cel, Nagrody.celZaliczony(ksiega, dzien))

        assertEquals(4, pierscien(30, DZIS))
        assertEquals("cel podniesiony po zaliczeniu", 4, pierscien(120, DZIS))
        assertEquals("nowy dzien zaczyna od pustego", 0, pierscien(30, DZIS + 1))
    }

    // --- etapy ------------------------------------------------------------------------------

    @Test
    fun `tabela etapow jest taka, jak w zalozeniach`() {
        assertEquals(
            listOf(
                Triple(0, "DEKLARACJA ZŁOŻONA", "DECLARATION FILED"),
                Triple(30, "KARTKÓWKA", "POP QUIZ"),
                Triple(150, "KLASÓWKA", "CLASS TEST"),
                Triple(500, "PRÓBNA MATURA", "MOCK EXAM"),
                Triple(1200, "PÓŁROCZE", "END OF TERM"),
                Triple(2500, "STUDNIÓWKA", "PROM NIGHT"),
                Triple(4000, "POLONEZ", "POLONAISE"),
                Triple(5500, "OSTATNI DZWONEK", "LAST BELL"),
                Triple(7000, "KASZTANY KWITNĄ", "CHESTNUTS IN BLOOM"),
                Triple(8500, "CZARNY DŁUGOPIS", "BLACK PEN"),
                Triple(10000, "ARKUSZ OTWARTY", "PAPER OPENED"),
                Triple(12000, "KARTA ODPOWIEDZI", "ANSWER SHEET"),
                Triple(15000, "WAKACJE", "SUMMER BREAK"),
            ),
            Etap.entries.map { Triple(it.prog, it.pl, it.en) },
        )
        assertEquals((1..13).toList(), Etap.entries.map { it.numer })
    }

    @Test
    fun `progi etapow zaczynaja od zera i rosna`() {
        assertEquals(0, Etap.entries.first().prog)
        Etap.entries.zipWithNext { nizszy, wyzszy ->
            assertTrue("${wyzszy.name} nie jest wyzej niz ${nizszy.name}", wyzszy.prog > nizszy.prog)
        }
    }

    @Test
    fun `etap zmienia sie dokladnie na progu`() {
        // Cztery granice z zalozen, wpisane liczbami.
        assertEquals(1, Nagrody.etap(29).numer)
        assertEquals(2, Nagrody.etap(30).numer)
        assertEquals(12, Nagrody.etap(14_999).numer)
        assertEquals(13, Nagrody.etap(15_000).numer)

        // I kazdy prog z tabeli.
        val etapy = Etap.entries
        for ((nr, etap) in etapy.withIndex()) {
            assertEquals(etap, Nagrody.etap(etap.prog))
            if (nr > 0) {
                val punktPrzed = etap.prog - 1
                assertEquals(etapy[nr - 1], Nagrody.etap(punktPrzed))
                assertEquals(etap, Nagrody.nastepny(punktPrzed))
                assertEquals(1, Nagrody.doNastepnego(punktPrzed))
            }
            val wyzej = etapy.getOrNull(nr + 1)
            assertEquals(wyzej, Nagrody.nastepny(etap.prog))
            assertEquals(if (wyzej == null) 0 else wyzej.prog - etap.prog, Nagrody.doNastepnego(etap.prog))
        }
    }

    @Test
    fun `na ostatnim etapie nie ma nastepnego, a ujemna suma to pierwszy etap`() {
        assertEquals(Etap.WAKACJE, Nagrody.etap(Int.MAX_VALUE))
        assertNull(Nagrody.nastepny(15_000))
        assertNull(Nagrody.nastepny(Int.MAX_VALUE))
        assertEquals(0, Nagrody.doNastepnego(Int.MAX_VALUE))

        assertEquals(Etap.DEKLARACJA, Nagrody.etap(-1))
        assertEquals(Etap.DEKLARACJA, Nagrody.etap(0))
        assertEquals(Etap.KARTKOWKA, Nagrody.nastepny(0))
        assertEquals(30, Nagrody.doNastepnego(0))
        assertEquals(30, Nagrody.doNastepnego(-7))
        assertEquals(0 to 30, Nagrody.pasekEtapu(-7))
    }

    @Test
    fun `okrazenia licza sie od ostatniego etapu, jedno na 2500 punktow`() {
        assertEquals(2500, Nagrody.OKRAZENIE)
        assertEquals(0, Nagrody.okrazenie(-1))
        assertEquals(0, Nagrody.okrazenie(0))
        assertEquals(0, Nagrody.okrazenie(14_999))
        assertEquals(1, Nagrody.okrazenie(15_000))
        assertEquals(1, Nagrody.okrazenie(17_499))
        assertEquals(2, Nagrody.okrazenie(17_500))
        assertEquals(2, Nagrody.okrazenie(19_999))
        assertEquals(3, Nagrody.okrazenie(20_000))
        // Okrazenie zmienia sie tylko na ostatnim etapie i nigdy sie nie cofa.
        var poprzednie = 0
        for (xp in 0..40_000 step 50) {
            val okrazenie = Nagrody.okrazenie(xp)
            assertEquals("xp $xp", Nagrody.etap(xp) == Etap.WAKACJE, okrazenie >= 1)
            assertTrue(okrazenie >= poprzednie)
            poprzednie = okrazenie
        }
    }

    @Test
    fun `pasek etapu pokazuje droge w obrebie etapu, a na ostatnim w obrebie okrazenia`() {
        assertEquals(0 to 30, Nagrody.pasekEtapu(0))
        assertEquals(29 to 30, Nagrody.pasekEtapu(29))
        assertEquals(0 to 120, Nagrody.pasekEtapu(30))
        assertEquals("640 punktow na etapie od 500 do 1200", 140 to 700, Nagrody.pasekEtapu(640))
        assertEquals(2999 to 3000, Nagrody.pasekEtapu(14_999))
        assertEquals(0 to 2500, Nagrody.pasekEtapu(15_000))
        assertEquals(400 to 2500, Nagrody.pasekEtapu(15_400))
        assertEquals(2499 to 2500, Nagrody.pasekEtapu(17_499))
        assertEquals(0 to 2500, Nagrody.pasekEtapu(17_500))

        for (xp in 0..40_000 step 7) {
            val (zrobione, odcinek) = Nagrody.pasekEtapu(xp)
            assertTrue("xp $xp: $zrobione z $odcinek", zrobione in 0 until odcinek)
            // Pasek i "brakuje N punktow" mowia to samo.
            if (Nagrody.nastepny(xp) != null) assertEquals(odcinek - zrobione, Nagrody.doNastepnego(xp))
        }
    }

    // --- punkty na start (migracja zapisu) --------------------------------------------------

    @Test
    fun `slowo zastane w pudelku jest warte tyle, ile dalaby dzis ta sama droga`() {
        assertEquals(0, Nagrody.punktyNaStart(WordProgress()))
        assertEquals(10, Nagrody.punktyNaStart(WordProgress(box = 1)))
        assertEquals(10, Nagrody.punktyNaStart(WordProgress(box = 1, lastTask = TaskKind.PRZYPOMNIENIE)))
        assertEquals(3, Nagrody.punktyNaStart(WordProgress(box = 1, lastTask = TaskKind.ROZPOZNANIE)))
        assertEquals(30, Nagrody.punktyNaStart(WordProgress(box = 2)))
        assertEquals(60, Nagrody.punktyNaStart(WordProgress(box = 3)))
        // Wyzej sie nie da, a rozpoznanie jako ostatnia proba nie umniejsza pudelek 2 i 3.
        assertEquals(30, Nagrody.punktyNaStart(WordProgress(box = 2, lastTask = TaskKind.ROZPOZNANIE)))
        assertEquals(60, Nagrody.punktyNaStart(WordProgress(box = 9)))
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

    /** Maly slownik gry: jeden zestaw. Odznaki za slowa licza sie tylko z niego. */
    private val zestaw = WordSet(
        id = "t001",
        title = "Dom · Kuchnia",
        builtIn = true,
        entries = slowaZestawu.map { Entry("haslo-$it", "znaczenie-$it", it) },
    )
    private val slownik = listOf(zestaw)

    private val pustyZapis = SaveData(sets = slownik)

    /** Runda bez dysku: zapis po niej i to, co Repository oddaloby przez naWplyw. */
    private class Runda(val po: SaveData, val wplywy: List<Wplyw>)

    /** To, co robi Repository.recordAttempt, tylko bez dysku: postep, zaliczenie, ksiega, odznaki. */
    private fun runda(dane: SaveData, proby: List<Attempt>, dzien: Int): Runda {
        var stan = dane
        val wplywy = ArrayList<Wplyw>()
        for (proba in proby) {
            val klucz = Srs.key(proba.entryId, proba.direction)
            val slowoPrzed = stan.progress[klucz] ?: WordProgress()
            val postep = Srs.applyTo(stan.progress, proba, dzien)
            stan = stan.copy(progress = postep)
            val zaliczenie = Nagrody.zaliczenie(slowoPrzed, postep[klucz] ?: WordProgress(), proba) ?: continue
            val wlasne = proba.entryId !in slowaZestawu
            val doliczenie = Nagrody.dolicz(
                stan.dorobek, zaliczenie, dzien, Nagrody.celDnia(stan.options.celDnia), wlasne,
                naprawa = zaliczenie == Zaliczenie.UTRWALENIE && slowoPrzed.mistakes > 0 && !wlasne,
            )
            wplywy += doliczenie.wplyw
            val zKsiega = stan.copy(dorobek = doliczenie.dorobek)
            stan = zKsiega.copy(
                dorobek = Odznaki.odblokuj(doliczenie.dorobek, Odznaki.liczniki(zKsiega, slownik), dzien),
            )
        }
        return Runda(stan, wplywy)
    }

    /** Zapis po rundzie, ktorej ekran wyniku juz pokazal odznaki (tak jak po recordGame). */
    private fun pokazane(dane: SaveData) = dane.copy(dorobek = Odznaki.potwierdz(dane.dorobek))

    private fun wynik(punkty: Int, tryb: GameMode = GameMode.CLASSIC) =
        ScoreRecord("Kuba", "Dom · Kuchnia", punkty, 1, 0, 0, 1_700_000_000_000L, tryb)

    @Test
    fun `pierwsza runda nowego ucznia`() {
        val proby = slowaZestawu.take(3).map { proba(entryId = it) }
        val r = runda(pustyZapis, proby, DZIS)

        val nagroda = Nagrody.roznica(pustyZapis, r.po, r.wplywy, wynik(120), DZIS)

        assertEquals(
            NagrodaRundy(
                xp = 30,
                rozpisanie = listOf(PozycjaNagrody(Zaliczenie.NOWE, 3, 30)),
                limity = emptySet(),
                xpDzisPo = 30,
                cel = 30,
                celZaliczonyTeraz = true,
                celZaliczony = true,
                pierwszyDzien = true,
                nowaSeria = false,
                wolneUzyte = 0,
                wolneDodane = 0,
                seriaPo = 1,
                dniNauki = 1,
                etapPrzed = Etap.DEKLARACJA,
                etapPo = Etap.KARTKOWKA,
                okrazeniePrzed = 0,
                okrazeniePo = 0,
                odznaki = listOf(Odznaka.SLOWA_1),
                rekord = false,
                poprzedniRekord = null,
                noweDzisPrzekroczone = false,
            ),
            nagroda,
        )
        assertTrue(nagroda.nowyEtap)
        assertFalse(nagroda.noweOkrazenie)
    }

    @Test
    fun `rozpisanie bierze sie z tego, co oddal zapis, w kolejnosci stalych`() {
        val wczoraj = pokazane(runda(pustyZapis, slowaZestawu.take(2).map { proba(entryId = it) }, DZIS - 1).po)
        val proby = listOf(
            proba(entryId = slowaZestawu[4], task = TaskKind.ROZPOZNANIE),  // rozpoznane, 3
            proba(entryId = slowaZestawu[0]),                               // awans, 20
            proba(entryId = slowaZestawu[2], outcome = Outcome.BLEDNA),     // nic
            proba(entryId = slowaZestawu[2]),                               // nic: poprawka po bledzie
            proba(entryId = slowaZestawu[3]),                               // nowe, 10
            proba(entryId = slowaZestawu[3]),                               // nic: drugi raz tego dnia
            proba(entryId = slowaZestawu[1]),                               // awans, 20
            proba(entryId = slowaZestawu[3], direction = Direction.DEF_TO_TERM), // drugi kierunek: nowe, 10
        )
        val r = runda(wczoraj, proby, DZIS)

        val nagroda = Nagrody.roznica(wczoraj, r.po, r.wplywy, null, DZIS)

        assertEquals(63, nagroda.xp)
        assertEquals(
            listOf(
                PozycjaNagrody(Zaliczenie.NOWE, 2, 20),
                PozycjaNagrody(Zaliczenie.AWANS, 2, 40),
                PozycjaNagrody(Zaliczenie.ROZPOZNANE, 1, 3),
            ),
            nagroda.rozpisanie,
        )
        assertEquals("rozpisanie i ksiega opowiadaja te sama runde", nagroda.xp, nagroda.rozpisanie.sumOf { it.xp })
        assertTrue(nagroda.limity.isEmpty())
        assertEquals("wczorajsze punkty nie licza sie do dzisiejszego celu", 63, nagroda.xpDzisPo)
        assertTrue(nagroda.celZaliczonyTeraz)
        assertTrue("wczoraj bylo tylko 20 punktow, wiec dopiero dzis jest pierwszy zaliczony dzien", nagroda.pierwszyDzien)
        assertEquals(1, nagroda.seriaPo)
    }

    @Test
    fun `runda bez zaliczen ma zero punktow i puste rozpisanie, ale swoj obiekt`() {
        // Uczen zagral jeszcze raz te same slowa tego samego dnia i pomylil sie przy nowym.
        val rano = slowaZestawu.take(2).map { proba(entryId = it) }
        val przed = pokazane(runda(pustyZapis, rano, DZIS).po)
        val proby = rano + proba(entryId = slowaZestawu[5], outcome = Outcome.BLEDNA) +
            proba(entryId = slowaZestawu[6], outcome = Outcome.PRZERWANA)
        val r = runda(przed, proby, DZIS)

        val nagroda = Nagrody.roznica(przed, r.po, r.wplywy, null, DZIS)

        assertEquals(0, nagroda.xp)
        assertTrue(nagroda.rozpisanie.isEmpty())
        assertTrue(nagroda.limity.isEmpty())
        assertEquals("ekran dalej wie, ile jest na dzis", 20, nagroda.xpDzisPo)
        assertEquals(30, nagroda.cel)
        assertFalse(nagroda.celZaliczonyTeraz)
        assertFalse(nagroda.celZaliczony)
        assertFalse(nagroda.nowyEtap)
        assertTrue(nagroda.odznaki.isEmpty())
        assertFalse(nagroda.rekord)
        assertNull(nagroda.poprzedniRekord)

        // Runda bez ani jednej proby wyglada tak samo.
        assertEquals(nagroda, Nagrody.roznica(przed, przed, emptyList(), null, DZIS))
    }

    @Test
    fun `zero punktow przez limit rozpoznan widac w rozpisaniu i w limitach`() {
        val piec = slowaZestawu.take(5).map { proba(entryId = it, task = TaskKind.ROZPOZNANIE) }
        val przed = pokazane(runda(pustyZapis, piec, DZIS).po)
        assertEquals(15, przed.dorobek.xp)
        val proby = slowaZestawu.drop(5).map { proba(entryId = it, task = TaskKind.ROZPOZNANIE) }
        val r = runda(przed, proby, DZIS)

        val nagroda = Nagrody.roznica(przed, r.po, r.wplywy, null, DZIS)

        assertEquals(0, nagroda.xp)
        assertEquals(listOf(PozycjaNagrody(Zaliczenie.ROZPOZNANE, 3, 0)), nagroda.rozpisanie)
        assertEquals(setOf(Limit.ROZPOZNANIA), nagroda.limity)
        assertEquals(15, nagroda.xpDzisPo)
    }

    @Test
    fun `zero punktow przez limit wlasnych slow widac w rozpisaniu i w limitach`() {
        val wlasne = (1..12).map { "u" + it.toString(16).padStart(12, '0') }
        val przed = pokazane(runda(pustyZapis, wlasne.take(10).map { proba(entryId = it) }, DZIS).po)
        assertEquals(100, przed.dorobek.xp)
        val r = runda(przed, wlasne.drop(10).map { proba(entryId = it) }, DZIS)

        val nagroda = Nagrody.roznica(przed, r.po, r.wplywy, null, DZIS)

        assertEquals(0, nagroda.xp)
        assertEquals(listOf(PozycjaNagrody(Zaliczenie.NOWE, 2, 0)), nagroda.rozpisanie)
        assertEquals(setOf(Limit.WLASNE), nagroda.limity)

        // Runda mieszana: wlasne ponad limit i slowo ze slownika. Punkty sa, limit tez widac.
        val mieszana = runda(przed, listOf(proba(entryId = wlasne[10]), proba(entryId = slowaZestawu[0])), DZIS)
        val zMieszanej = Nagrody.roznica(przed, mieszana.po, mieszana.wplywy, null, DZIS)
        assertEquals(10, zMieszanej.xp)
        assertEquals(listOf(PozycjaNagrody(Zaliczenie.NOWE, 2, 10)), zMieszanej.rozpisanie)
        assertEquals(setOf(Limit.WLASNE), zMieszanej.limity)
    }

    @Test
    fun `cel zaliczony przed runda nie jest oglaszany drugi raz`() {
        val rano = slowaZestawu.take(3).map { proba(entryId = it) }
        val przed = pokazane(runda(pustyZapis, rano, DZIS).po)
        assertTrue(Nagrody.celZaliczony(przed.dorobek, DZIS))
        val r = runda(przed, slowaZestawu.drop(3).take(2).map { proba(entryId = it) }, DZIS)

        val nagroda = Nagrody.roznica(przed, r.po, r.wplywy, null, DZIS)

        assertEquals(20, nagroda.xp)
        assertEquals(50, nagroda.xpDzisPo)
        assertFalse("cel padl rano, a nie w tej rundzie", nagroda.celZaliczonyTeraz)
        assertTrue(nagroda.celZaliczony)
        assertFalse(nagroda.pierwszyDzien)
        assertFalse(nagroda.nowaSeria)
        assertEquals(1, nagroda.seriaPo)
        assertEquals(1, nagroda.dniNauki)
    }

    /** Zapis z sama ksiega; do pytan o serie wystarcza, slowa nie sa tu potrzebne. */
    private fun zKsiega(d: Dorobek) = pustyZapis.copy(dorobek = d)

    @Test
    fun `nastepnego dnia roznica nie pokazuje wczorajszych punktow jako dzisiejszych`() {
        // Uczen otworzyl gre nazajutrz i wyszedl z rundy bez zaliczen: ksiega pamieta jeszcze
        // wczorajszy dzien, a ekran wyniku pyta o dzisiejszy.
        val wczoraj = zKsiega(dzienNauki(Dorobek(), DZIS))

        val nagroda = Nagrody.roznica(wczoraj, wczoraj, emptyList(), null, DZIS + 1)

        assertEquals(0, nagroda.xpDzisPo)
        assertFalse(nagroda.celZaliczony)
        assertFalse(nagroda.celZaliczonyTeraz)
        assertEquals("seria czeka na dzisiejszy cel", 1, nagroda.seriaPo)
        assertEquals(1, nagroda.dniNauki)
    }

    @Test
    fun `seria idzie dalej - roznica mowi, ze nic sie nie zuzylo i nic nie doszlo`() {
        val przed = dniNauki(Dorobek(), DZIS - 3..DZIS - 1)
        val po = dzienNauki(przed, DZIS)

        val nagroda = Nagrody.roznica(zKsiega(przed), zKsiega(po), emptyList(), null, DZIS)

        assertTrue(nagroda.celZaliczonyTeraz)
        assertFalse(nagroda.pierwszyDzien)
        assertFalse(nagroda.nowaSeria)
        assertEquals(0, nagroda.wolneUzyte)
        assertEquals(0, nagroda.wolneDodane)
        assertEquals(4, nagroda.seriaPo)
        assertEquals(4, nagroda.dniNauki)
    }

    @Test
    fun `dzien wolny wykorzystany - roznica mowi ile`() {
        val przed = dniNauki(Dorobek(), DZIS - 4..DZIS - 2)
        val po = dzienNauki(przed, DZIS)

        val nagroda = Nagrody.roznica(zKsiega(przed), zKsiega(po), emptyList(), null, DZIS)

        assertTrue(nagroda.celZaliczonyTeraz)
        assertEquals(1, nagroda.wolneUzyte)
        assertEquals(0, nagroda.wolneDodane)
        assertFalse(nagroda.nowaSeria)
        assertEquals(4, nagroda.seriaPo)

        // Dwa dni naraz, z pelnego zapasu.
        val tydzien = dniNauki(Dorobek(), DZIS - 9..DZIS - 3)
        val poDwoch = Nagrody.roznica(zKsiega(tydzien), zKsiega(dzienNauki(tydzien, DZIS)), emptyList(), null, DZIS)
        assertEquals(2, poDwoch.wolneUzyte)
        assertEquals(8, poDwoch.seriaPo)
    }

    @Test
    fun `nowa seria po dluzszej przerwie - roznica podaje dni nauki razem`() {
        val przed = dniNauki(Dorobek(), DZIS - 12..DZIS - 8)
        val po = dzienNauki(przed, DZIS)

        val nagroda = Nagrody.roznica(zKsiega(przed), zKsiega(po), emptyList(), null, DZIS)

        assertTrue(nagroda.celZaliczonyTeraz)
        assertTrue(nagroda.nowaSeria)
        assertFalse("pierwszy dzien jest tylko raz w zyciu ksiegi", nagroda.pierwszyDzien)
        assertEquals(0, nagroda.wolneUzyte)
        assertEquals(1, nagroda.seriaPo)
        assertEquals(6, nagroda.dniNauki)
        assertEquals(5, po.rekordSerii)
    }

    @Test
    fun `dzien wolny zdobyty widac takze wtedy, gdy tego samego dnia inny sie zuzyl`() {
        val szesc = dniNauki(Dorobek(), DZIS - 6..DZIS - 1)
        val siodmy = Nagrody.roznica(zKsiega(szesc), zKsiega(dzienNauki(szesc, DZIS)), emptyList(), null, DZIS)
        assertEquals(0, siodmy.wolneUzyte)
        assertEquals(1, siodmy.wolneDodane)
        assertEquals(7, siodmy.seriaPo)

        // Szesc dni, dzien przerwy, siodmy dzien serii: zapas przed i po to jeden, a mimo to
        // jeden dzien wolny zostal zuzyty i jeden zdobyty. Samo odejmowanie pokazaloby zero.
        val zPrzerwa = dniNauki(Dorobek(), DZIS - 7..DZIS - 2)
        val po = dzienNauki(zPrzerwa, DZIS)
        assertEquals(zPrzerwa.wolne, po.wolne)
        val nagroda = Nagrody.roznica(zKsiega(zPrzerwa), zKsiega(po), emptyList(), null, DZIS)
        assertEquals(1, nagroda.wolneUzyte)
        assertEquals(1, nagroda.wolneDodane)
    }

    @Test
    fun `seria wygasla przed runda liczy sie od zera, a nie od starej dlugosci`() {
        val przed = dniNauki(Dorobek(), DZIS - 9..DZIS - 7)
        assertEquals(3, przed.seria)

        // Runda bez celu: 20 punktow.
        val po = kilka(przed, 2, Zaliczenie.NOWE, DZIS)
        val nagroda = Nagrody.roznica(zKsiega(przed), zKsiega(po), emptyList(), null, DZIS)

        assertEquals("po tygodniu przerwy ekran nie moze pokazac starej serii", 0, nagroda.seriaPo)
        assertFalse(nagroda.celZaliczonyTeraz)
        assertFalse(nagroda.nowaSeria)
        assertEquals(3, nagroda.dniNauki)
    }

    @Test
    fun `dzien przeniesiony po cofnieciu zegara nie jest oglaszany jako zaliczony w rundzie`() {
        val przed = dniNauki(Dorobek(), DZIS - 1..DZIS)
        val cofniety = dzienNauki(przed, DZIS - 3)
        assertTrue("ksiega uznaje ten dzien za zajety", Nagrody.celZaliczony(cofniety, DZIS - 3))

        val nagroda = Nagrody.roznica(zKsiega(przed), zKsiega(cofniety), emptyList(), null, DZIS - 3)

        assertEquals(30, nagroda.xp)
        assertFalse("nic nie zostalo doliczone, wiec nie ma czego oglaszac", nagroda.celZaliczonyTeraz)
        assertTrue(nagroda.celZaliczony)
        assertFalse(nagroda.nowaSeria)
        assertEquals(0, nagroda.wolneUzyte)
        assertEquals(2, nagroda.dniNauki)
    }

    @Test
    fun `nowy etap i nowe okrazenie widac w roznicy`() {
        fun poDziesieciu(xp: Int): NagrodaRundy {
            val przed = pustyZapis.copy(dorobek = Dorobek(xp = xp))
            val r = runda(przed, listOf(proba(entryId = slowaZestawu[0])), DZIS)
            return Nagrody.roznica(przed, r.po, r.wplywy, null, DZIS)
        }

        val etap = poDziesieciu(145)
        assertEquals(10, etap.xp)
        assertEquals(Etap.KARTKOWKA, etap.etapPrzed)
        assertEquals(Etap.KLASOWKA, etap.etapPo)
        assertTrue(etap.nowyEtap)
        assertFalse(etap.noweOkrazenie)

        val bezZmiany = poDziesieciu(200)
        assertFalse(bezZmiany.nowyEtap)
        assertFalse(bezZmiany.noweOkrazenie)
        assertEquals(0, bezZmiany.okrazeniePo)

        // Wejscie na ostatni etap to nowy etap, a nie okrazenie.
        val ostatni = poDziesieciu(14_995)
        assertEquals(Etap.WAKACJE, ostatni.etapPo)
        assertTrue(ostatni.nowyEtap)
        assertFalse(ostatni.noweOkrazenie)
        assertEquals(0, ostatni.okrazeniePrzed)
        assertEquals(1, ostatni.okrazeniePo)

        val okrazenie = poDziesieciu(17_495)
        assertFalse(okrazenie.nowyEtap)
        assertTrue(okrazenie.noweOkrazenie)
        assertEquals(1, okrazenie.okrazeniePrzed)
        assertEquals(2, okrazenie.okrazeniePo)
    }

    @Test
    fun `rekord wymaga wczesniejszego wyniku powyzej zera w tym samym trybie`() {
        val przed = pustyZapis.copy(
            scores = listOf(
                wynik(500), wynik(320),
                wynik(900, GameMode.TRUE_FALSE),
                wynik(0, GameMode.MULTIPLE_CHOICE),
            ),
        )
        fun po(nowy: ScoreRecord) = przed.copy(scores = przed.scores + nowy)

        // 501 bije klasyczne 500, choc w innym trybie lezy 900: tryby maja osobne rekordy.
        val pobity = Nagrody.roznica(przed, po(wynik(501)), emptyList(), wynik(501), DZIS)
        assertTrue(pobity.rekord)
        assertEquals(500, pobity.poprzedniRekord)

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

        // Wczesniejsze zero to nie poprzeczka: pobicie go nie jest nowina.
        val poZerze = wynik(40, GameMode.MULTIPLE_CHOICE)
        val odZera = Nagrody.roznica(przed, po(poZerze), emptyList(), poZerze, DZIS)
        assertFalse(odZera.rekord)
        assertEquals(0, odZera.poprzedniRekord)
    }

    @Test
    fun `spokojna runda nigdy nie jest rekordem`() {
        // Runda sesji dziennej nie idzie o wynik: wolajacy podaje null zamiast rekordu, takze
        // wtedy, gdy liczba punktow bylaby najwyzsza w historii.
        val przed = pustyZapis.copy(scores = listOf(wynik(500)))

        val nagroda = Nagrody.roznica(przed, przed, emptyList(), null, DZIS)

        assertFalse(nagroda.rekord)
        assertNull(nagroda.poprzedniRekord)
    }

    @Test
    fun `odznaki do ogloszenia to nowe z rundy i czekajace niepokazane, w kolejnosci stalych`() {
        // Przed runda: ROOKIE zdobyty wczesniej i jeszcze niepokazany (runda przerwana
        // wyjsciem), FIRST_BLOOD zdobyty i pokazany dawno temu.
        val przed = pustyZapis.copy(
            dorobek = Dorobek(
                odznaki = mapOf("FIRST_BLOOD" to DZIS - 9, "ROOKIE" to DZIS - 1),
                odznakiNowe = setOf("ROOKIE"),
            ),
        )
        val r = runda(przed, listOf(proba(entryId = slowaZestawu[0])), DZIS)
        assertEquals(setOf("ROOKIE", "SLOWA_1"), r.po.dorobek.odznakiNowe)

        val nagroda = Nagrody.roznica(przed, r.po, r.wplywy, null, DZIS)

        assertEquals(listOf(Odznaka.SLOWA_1, Odznaka.ROOKIE), nagroda.odznaki)

        // Ekran je pokazal i potwierdzil, punkt odniesienia sie przesunal: druga roznica jest
        // pusta, wiec JESZCZE RAZ nie oglosi tych samych rzeczy.
        val potwierdzony = r.po.copy(dorobek = Odznaki.potwierdz(r.po.dorobek))
        val druga = Nagrody.roznica(potwierdzony, potwierdzony, emptyList(), null, DZIS)
        assertEquals(0, druga.xp)
        assertTrue(druga.rozpisanie.isEmpty())
        assertTrue(druga.odznaki.isEmpty())
        assertFalse(druga.celZaliczonyTeraz)
        assertFalse(druga.nowyEtap)
        assertFalse(druga.rekord)
        assertFalse(druga.noweDzisPrzekroczone)
    }

    @Test
    fun `odznaka zdobyta w rundzie jest oglaszana takze wtedy, gdy zapis juz ja potwierdzil`() {
        // Repository.recordGame z potwierdzeniem czysci zbior czekajacych tym samym zapisem,
        // w ktorym stempluje odznaki za gre. Roznica widzi je po kluczach, ktorych nie bylo.
        val r = runda(pustyZapis, listOf(proba(entryId = slowaZestawu[0])), DZIS)
        val poGrze = r.po.copy(
            dorobek = Odznaki.potwierdz(
                r.po.dorobek.copy(odznaki = r.po.dorobek.odznaki + ("FIRST_BLOOD" to DZIS)),
            ),
        )
        assertTrue(poGrze.dorobek.odznakiNowe.isEmpty())

        val nagroda = Nagrody.roznica(pustyZapis, poGrze, r.wplywy, wynik(10), DZIS)

        assertEquals(listOf(Odznaka.SLOWA_1, Odznaka.FIRST_BLOOD), nagroda.odznaki)
    }

    @Test
    fun `trzydzieste nowe slowo dnia jest zauwazane raz`() {
        assertEquals(30, Nagrody.DUZO_NOWYCH)
        val dwadziesciaDziewiec = kilka(Dorobek(), 29, Zaliczenie.NOWE, DZIS)
        val trzydziesci = dolicz(dwadziesciaDziewiec, Zaliczenie.ROZPOZNANE, DZIS)
        val wiecej = kilka(trzydziesci, 4, Zaliczenie.NOWE, DZIS)
        fun przekroczone(przed: Dorobek, po: Dorobek, dzien: Int = DZIS) =
            Nagrody.roznica(zKsiega(przed), zKsiega(po), emptyList(), null, dzien).noweDzisPrzekroczone

        assertFalse(przekroczone(Dorobek(), dwadziesciaDziewiec))
        assertTrue(przekroczone(dwadziesciaDziewiec, trzydziesci))
        assertTrue("takze jednym skokiem przez granice", przekroczone(Dorobek(), wiecej))
        assertFalse("druga runda tego dnia juz o tym nie mowi", przekroczone(trzydziesci, wiecej))
        // Nastepnego dnia licznik sprzed rundy to zero, a nie wczorajsze 34.
        val jutro = kilka(wiecej, 30, Zaliczenie.NOWE, DZIS + 1)
        assertTrue(przekroczone(wiecej, jutro, DZIS + 1))
    }

    @Test
    fun `cel w roznicy bierze sie z opcji po rundzie i tylko z dozwolonych wartosci`() {
        val po = dzienNauki(Dorobek(), DZIS)
        fun cel(zapisany: Int) = Nagrody.roznica(
            pustyZapis,
            pustyZapis.copy(dorobek = po, options = Options(celDnia = zapisany)),
            emptyList(), null, DZIS,
        ).cel

        assertEquals(60, cel(60))
        assertEquals(120, cel(120))
        assertEquals(30, cel(5))
        assertEquals(30, cel(0))
    }
}
