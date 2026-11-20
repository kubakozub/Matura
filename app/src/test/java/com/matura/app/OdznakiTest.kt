package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.learn.Attempt
import com.matura.app.learn.GrupaOdznak
import com.matura.app.learn.Liczniki
import com.matura.app.learn.Odznaka
import com.matura.app.learn.Odznaki
import com.matura.app.learn.Outcome
import com.matura.app.learn.Ranga
import com.matura.app.learn.Srs
import com.matura.app.learn.Zaliczenie
import com.matura.app.model.Achievement
import com.matura.app.model.Direction
import com.matura.app.model.Dorobek
import com.matura.app.model.Entry
import com.matura.app.model.GameMode
import com.matura.app.model.Profile
import com.matura.app.model.SaveData
import com.matura.app.model.ScoreRecord
import com.matura.app.model.TaskKind
import com.matura.app.model.WordProgress
import com.matura.app.model.WordSet
import com.matura.app.ui.PixelFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Odznaki: z czego sie licza, kiedy wchodza do ksiegi i jak sa podpisane.
 *
 * Mapy postepu powstaja tu przez [Srs.applyTo], tak jak w grze. Liczniki opieraja sie na
 * szczycie pudelka, a szczyt ustawia wylacznie harmonogram, wiec mapa wpisana recznie
 * sprawdzalaby tylko to, czy test umie przepisac regule.
 */
class OdznakiTest {

    private companion object {
        /** Dowolny dzien od epoki UTC; jeden na caly plik. */
        const val DZIS = 19_875

        /** Znaki, ktorych wlasciciel projektu nie chce w zadnym tekscie: polpauza, pauza i ich krewni. */
        val ZAKAZANE_KRESKI = setOf('\u2012', '\u2013', '\u2014', '\u2015', '\u2212')

        const val LIMIT_TYTULU = 24
    }

    /** Identyfikatory w ksztalcie katalogowym: "w" i 12 znakow szesnastkowych. */
    private fun idy(od: Int, ile: Int): List<String> =
        (od until od + ile).map { "w" + it.toString(16).padStart(12, '0') }

    private fun zestaw(
        id: String,
        tytul: String,
        wpisy: List<String>,
        wbudowany: Boolean = true,
    ) = WordSet(
        id = id,
        title = tytul,
        builtIn = wbudowany,
        entries = wpisy.map { Entry("haslo-$it", "znaczenie-$it", it) },
    )

    /** Slowa doprowadzone do [pudelko] przez harmonogram, kazda proba w swoim terminie. */
    private fun naucz(
        mapa: Map<String, WordProgress>,
        slowa: List<String>,
        pudelko: Int,
        kierunek: Direction = Direction.TERM_TO_DEF,
        zadanie: TaskKind = TaskKind.PRZYPOMNIENIE,
    ): Map<String, WordProgress> {
        var wynik = mapa
        for (id in slowa) {
            repeat(pudelko) {
                val termin = Srs.progressOf(wynik, id, kierunek).dueDay.coerceAtLeast(DZIS)
                wynik = Srs.applyTo(wynik, Attempt(id, kierunek, zadanie, Outcome.POPRAWNA), termin)
            }
            assertEquals("slowo $id nie doszlo tam, gdzie mialo", pudelko, Srs.progressOf(wynik, id, kierunek).box)
        }
        return wynik
    }

    private fun pomyl(mapa: Map<String, WordProgress>, id: String): Map<String, WordProgress> =
        Srs.applyTo(
            mapa,
            Attempt(id, Direction.TERM_TO_DEF, TaskKind.PRZYPOMNIENIE, Outcome.BLEDNA),
            DZIS + 100,
        )

    // --- liczniki slow ----------------------------------------------------------------------

    @Test
    fun `nowy zapis ma same zera i zadnej odznaki`() {
        val liczniki = Odznaki.liczniki(SaveData(sets = BuiltInSets.all()))

        assertEquals(Liczniki(), liczniki)
        for (odznaka in Odznaka.entries) {
            assertFalse("${odznaka.name} spelniona bez zadnej nauki", Odznaki.spelniona(odznaka, liczniki))
            assertEquals(0, Odznaki.postep(odznaka, liczniki))
        }
        val pusta = Dorobek()
        assertSame("nie ma czego dopisac, wiec ksiega wraca ta sama", pusta, Odznaki.odblokuj(pusta, liczniki, DZIS))
    }

    @Test
    fun `poznane, utrwalone i obie strony licza sie ze szczytow, ktore ustawil harmonogram`() {
        val slowa = idy(1, 6)
        val rozpoznane = slowa[0]
        val poznane = slowa[1]
        val powtarzane = slowa[2]
        val utrwalone = slowa[3]
        val wObieStrony = slowa[4]
        val nietkniete = slowa[5]
        val zestaw = zestaw("t001", "Dom · Kuchnia", slowa)
        var mapa = emptyMap<String, WordProgress>()
        mapa = naucz(mapa, listOf(rozpoznane), 1, zadanie = TaskKind.ROZPOZNANIE)
        mapa = naucz(mapa, listOf(poznane), 1)
        mapa = naucz(mapa, listOf(powtarzane), 2)
        mapa = naucz(mapa, listOf(utrwalone), 3, kierunek = Direction.DEF_TO_TERM)
        mapa = naucz(mapa, listOf(wObieStrony), 2)
        mapa = naucz(mapa, listOf(wObieStrony), 2, kierunek = Direction.DEF_TO_TERM)
        // Drugi kierunek tylko w pudelku 1: do "obu stron" to za malo.
        mapa = naucz(mapa, listOf(powtarzane), 1, kierunek = Direction.DEF_TO_TERM)

        val liczniki = Odznaki.liczniki(SaveData(sets = listOf(zestaw), progress = mapa))

        assertEquals("wszystko poza slowem nietknietym ($nietkniete)", 5, liczniki.poznane)
        assertEquals("pudelko 3 w jednym kierunku wystarczy", 1, liczniki.utrwalone)
        assertEquals("pudelko 2 musi byc w OBU kierunkach", 1, liczniki.obieStrony)
        assertEquals(0, liczniki.dzialy)
        assertEquals(0, liczniki.zestawy)
    }

    @Test
    fun `to samo slowo w dwoch zestawach liczy sie raz`() {
        val wspolne = idy(1, 3)
        val zestawy = listOf(
            zestaw("t001", "Dom · Kuchnia", wspolne + idy(10, 2)),
            zestaw("t002", "Żywienie · Posiłki", wspolne + idy(20, 2)),
        )
        val mapa = naucz(emptyMap(), wspolne + idy(10, 2) + idy(20, 2), 3)

        val liczniki = Odznaki.liczniki(SaveData(sets = zestawy, progress = mapa))

        assertEquals("dziesiec wierszy, ale siedem roznych slow", 7, liczniki.poznane)
        assertEquals(7, liczniki.utrwalone)
        assertEquals(2, liczniki.zestawy)
    }

    @Test
    fun `wlasne zestawy nie zarabiaja odznak katalogu`() {
        // Inaczej wystarczylaby wlasna lista latwych slow, zeby "utrwalic caly zestaw".
        val wlasne = (1..12).map { "u" + it.toString(16).padStart(12, '0') }
        val zestawy = listOf(
            zestaw("user-1", "Dom · Moje", wlasne, wbudowany = false),
            zestaw("t001", "Dom · Kuchnia", idy(1, 3)),
        )
        var mapa = naucz(emptyMap(), wlasne, 3)
        mapa = naucz(mapa, wlasne, 3, kierunek = Direction.DEF_TO_TERM)

        val liczniki = Odznaki.liczniki(SaveData(sets = zestawy, progress = mapa))

        assertEquals(0, liczniki.poznane)
        assertEquals(0, liczniki.utrwalone)
        assertEquals(0, liczniki.obieStrony)
        assertEquals("wlasny zestaw nie tworzy dzialu ani go nie zasila", 0, liczniki.dzialy)
        assertEquals(0, liczniki.zestawy)
    }

    @Test
    fun `klucze postepu spoza biezacych zestawow sie nie licza`() {
        // Mapa postepu nie jest sprzatana: po korekcie katalogu lezy w niej stary klucz slowa,
        // a po usunieciu zestawu postep jego slow. Liczone po mapie zawyzalyby wynik.
        val wKatalogu = idy(1, 2)
        val osierocone = idy(50, 8)
        val mapa = naucz(emptyMap(), wKatalogu + osierocone, 3)

        val liczniki = Odznaki.liczniki(
            SaveData(sets = listOf(zestaw("t001", "Dom · Kuchnia", wKatalogu)), progress = mapa),
        )

        assertEquals(2, liczniki.poznane)
        assertEquals(2, liczniki.utrwalone)
    }

    @Test
    fun `pomylka cofa pudelko, ale nie licznik i nie odznake`() {
        val slowa = idy(1, 10)
        val zestaw = zestaw("t001", "Dom · Kuchnia", slowa)
        val nauczone = naucz(emptyMap(), slowa, 3)
        val przed = Odznaki.liczniki(SaveData(sets = listOf(zestaw), progress = nauczone))
        assertEquals(10, przed.utrwalone)
        assertEquals(1, przed.zestawy)
        assertTrue(Odznaki.spelniona(Odznaka.UTRWALONE_10, przed))

        var poBledach = nauczone
        for (id in slowa.take(4)) poBledach = pomyl(poBledach, id)
        assertEquals(1, Srs.progressOf(poBledach, slowa[0], Direction.TERM_TO_DEF).box)

        val po = Odznaki.liczniki(SaveData(sets = listOf(zestaw), progress = poBledach))

        assertEquals("uczniowi niczego nie odbieramy", przed, po)
        assertTrue(Odznaki.spelniona(Odznaka.UTRWALONE_10, po))
        assertTrue(Odznaki.spelniona(Odznaka.ZESTAW_1, po))
    }

    @Test
    fun `stan sprzed migracji liczy sie wedlug pudelka, gdy szczytu jeszcze nie ma`() {
        val slowa = idy(1, 2)
        val mapa = mapOf(
            Srs.key(slowa[0], Direction.TERM_TO_DEF) to WordProgress(box = 3, dueDay = DZIS),
            Srs.key(slowa[1], Direction.DEF_TO_TERM) to WordProgress(box = 1, dueDay = DZIS, szczyt = 2),
        )

        val liczniki = Odznaki.liczniki(
            SaveData(sets = listOf(zestaw("t001", "Dom · Kuchnia", slowa)), progress = mapa),
        )

        assertEquals(2, liczniki.poznane)
        assertEquals(1, liczniki.utrwalone)
    }

    // --- dzialy i zestawy -------------------------------------------------------------------

    @Test
    fun `dzial liczy sie od dziesieciu poznanych slow, zebranych ze wszystkich jego zestawow`() {
        val kuchnia = idy(1, 6)
        val pokoj = idy(6, 6)          // slowo nr 6 jest w obu zestawach dzialu
        val klasa = idy(100, 9) + idy(6, 1)
        val zestawy = listOf(
            zestaw("t001", "Dom · Kuchnia", kuchnia),
            zestaw("t002", "Dom · Pokój", pokoj),
            zestaw("t003", "Szkoła · Klasa", klasa),
            zestaw("t004", "Bez działu", idy(200, 12)),
        )
        fun dzialy(mapa: Map<String, WordProgress>) =
            Odznaki.liczniki(SaveData(sets = zestawy, progress = mapa)).dzialy

        // Dziewiec roznych slow dzialu Dom: szesc z kuchni i trzy kolejne z pokoju.
        var mapa = naucz(emptyMap(), idy(1, 9), 1)
        assertEquals(0, dzialy(mapa))

        mapa = naucz(mapa, idy(10, 1), 1, zadanie = TaskKind.ROZPOZNANIE)
        assertEquals("dziesiate slowo, takze poznane rozpoznaniem, domyka dzial", 1, dzialy(mapa))

        // Szkola ma juz jedno poznane slowo (wspolne z Domem); osiem kolejnych to wciaz dziewiec.
        mapa = naucz(mapa, idy(100, 8), 1)
        assertEquals(1, dzialy(mapa))
        mapa = naucz(mapa, idy(108, 1), 1)
        assertEquals(2, dzialy(mapa))

        // Zestaw wbudowany bez dzialu w tytule nie jest dzialem, chocby byl caly poznany.
        mapa = naucz(mapa, idy(200, 12), 1)
        assertEquals(2, dzialy(mapa))
        assertEquals(10, Odznaki.PROG_DZIALU)
    }

    @Test
    fun `zestaw liczy sie dopiero wtedy, gdy kazde jego slowo jest utrwalone`() {
        val caly = idy(1, 3)
        val prawie = idy(10, 3)
        val zDziura = idy(20, 2)
        val zestawy = listOf(
            zestaw("t001", "Dom · Kuchnia", caly),
            zestaw("t002", "Dom · Pokój", prawie),
            zestaw("t003", "Dom · Pusty", emptyList()),
            // Wpis bez identyfikatora (zapis sprzed migracji) nie ma jak byc utrwalony.
            zestaw("t004", "Dom · Stary", zDziura).let { it.copy(entries = it.entries + Entry("x", "y")) },
        )
        var mapa = naucz(emptyMap(), caly.take(2), 3)
        // Trzecie slowo utrwalone w DRUGIM kierunku: dowolny kierunek wystarczy.
        mapa = naucz(mapa, caly.drop(2), 3, kierunek = Direction.DEF_TO_TERM)
        mapa = naucz(mapa, prawie.take(2), 3)
        mapa = naucz(mapa, prawie.drop(2), 2)
        mapa = naucz(mapa, zDziura, 3)

        val liczniki = Odznaki.liczniki(SaveData(sets = zestawy, progress = mapa))

        assertEquals(1, liczniki.zestawy)
        assertEquals("slowa utrwalone licza sie takze w zestawach niepelnych", 7, liczniki.utrwalone)
    }

    // --- liczniki ksiegi, profilu i trybow ---------------------------------------------------

    @Test
    fun `liczniki ksiegi i profilu przechodza bez zmian, a tryby licza sie z listy wynikow`() {
        fun wynik(tryb: GameMode) = ScoreRecord("Kuba", "Zestaw", 100, 1, 0, 0, 1L, tryb)
        val dane = SaveData(
            profile = Profile(gamesPlayed = 7, totalKills = 120, bestScore = 640, bestStreak = 8),
            scores = listOf(wynik(GameMode.CLASSIC), wynik(GameMode.CLASSIC), wynik(GameMode.TIME_ATTACK)),
            dorobek = Dorobek(powtorki = 11, dniNauki = 4, rekordSerii = 3, seria = 1),
        )

        assertEquals(
            Liczniki(
                powtorki = 11,
                dniNauki = 4,
                rekordSerii = 3,
                tryby = 2,
                gamesPlayed = 7,
                totalKills = 120,
                bestScore = 640,
                bestStreak = 8,
            ),
            Odznaki.liczniki(dane),
        )
    }

    @Test
    fun `cztery tryby to cztery rozne tryby na liscie wynikow`() {
        fun wynik(tryb: GameMode) = ScoreRecord("Kuba", "Zestaw", 0, 1, 0, 0, 1L, tryb)
        val trzy = SaveData(scores = GameMode.entries.take(3).map(::wynik))
        val cztery = SaveData(scores = GameMode.entries.map(::wynik))

        assertFalse(Odznaki.spelniona(Odznaka.TRYBY_4, Odznaki.liczniki(trzy)))
        assertTrue(Odznaki.spelniona(Odznaka.TRYBY_4, Odznaki.liczniki(cztery)))
        assertEquals("odznaka obiecuje wszystkie tryby gry", GameMode.entries.size, Odznaka.TRYBY_4.cel)
    }

    // --- postep i przyznawanie ---------------------------------------------------------------

    @Test
    fun `postep jest przyciety do progu, a prog spelnia sie od rownosci`() {
        val o = Odznaka.SLOWA_50

        assertEquals(0, Odznaki.postep(o, Liczniki()))
        assertEquals(49, Odznaki.postep(o, Liczniki(poznane = 49)))
        assertFalse(Odznaki.spelniona(o, Liczniki(poznane = 49)))
        assertEquals(50, Odznaki.postep(o, Liczniki(poznane = 50)))
        assertTrue(Odznaki.spelniona(o, Liczniki(poznane = 50)))
        assertEquals("ekran nie pokaze 2033 z 50", 50, Odznaki.postep(o, Liczniki(poznane = 2033)))
        assertEquals(0, Odznaki.postep(o, Liczniki(poznane = -4)))
    }

    @Test
    fun `odblokuj dopisuje nowe odznaki z dniem i niczego nie rusza w starych`() {
        val ksiega = Dorobek(
            xp = 70,
            odznaki = mapOf(
                "SLOWA_5" to 0,
                // Odznaka, ktorej licznik jest dzis ponizej progu: zostaje, bo byla zdobyta.
                "ZESTAW_1" to DZIS - 30,
                // Nazwa z nowszej wersji aplikacji: nie nasza, ale tez nie do skasowania.
                "ODZNAKA_Z_PRZYSZLOSCI" to DZIS - 1,
            ),
        )
        val liczniki = Liczniki(poznane = 50, dniNauki = 1)

        val po = Odznaki.odblokuj(ksiega, liczniki, DZIS)

        assertEquals(
            mapOf(
                "SLOWA_5" to 0,
                "ZESTAW_1" to DZIS - 30,
                "ODZNAKA_Z_PRZYSZLOSCI" to DZIS - 1,
                "SLOWA_50" to DZIS,
                "DNI_1" to DZIS,
            ),
            po.odznaki,
        )
        assertEquals("odznaki nie daja punktow nauki", ksiega.copy(odznaki = po.odznaki), po)
        assertTrue(Odznaki.zdobyta(Odznaka.ZESTAW_1, po))
        assertTrue(Odznaki.zdobyta(Odznaka.SLOWA_50, po))
        assertFalse(Odznaki.zdobyta(Odznaka.SLOWA_250, po))

        // Drugi raz z tymi samymi licznikami: nic nowego, ta sama ksiega, te same dni.
        assertSame(po, Odznaki.odblokuj(po, liczniki, DZIS + 9))
    }

    // --- nazwy, progi i liczniki: to jest czesc zapisu ---------------------------------------

    @Test
    fun `nazwy odznak sa czescia zapisu i nie zmieniaja sie po cichu`() {
        // Dorobek.odznaki trzyma zdobyte odznaki POD TYMI NAZWAMI. Zmiana albo usuniecie nazwy
        // odbiera uczniowi odznake; jesli ten test pada, potrzebna jest migracja zapisu, a nie
        // poprawka listy ponizej. Nowe odznaki dopisuje sie tu swiadomie.
        assertEquals(
            listOf(
                "SLOWA_5", "SLOWA_50", "SLOWA_250", "SLOWA_1000",
                "UTRWALONE_10", "UTRWALONE_50", "UTRWALONE_200", "UTRWALONE_500",
                "POWTORKI_10", "POWTORKI_100", "POWTORKI_500",
                "OBIE_10", "OBIE_100",
                "DNI_1", "DNI_7", "DNI_30", "DNI_100",
                "SERIA_3", "SERIA_7", "SERIA_14", "SERIA_30",
                "DZIAL_1", "DZIALY_5", "DZIALY_14",
                "ZESTAW_1", "ZESTAWY_5", "ZESTAWY_25", "ZESTAWY_102",
                "FIRST_BLOOD", "ROOKIE", "VETERAN", "SHARPSHOOTER",
                "COMBO_X3", "COMBO_X5", "SCORE_500", "SCORE_2000",
                "TRYBY_4",
            ),
            Odznaka.entries.map { it.name },
        )
    }

    /** Jeden wiersz zalozen: grupa, prog, licznik (rozpoznawany po wartosci), tytuly. */
    private data class Wiersz(
        val grupa: GrupaOdznak,
        val cel: Int,
        val licznik: Int,
        val pl: String,
        val en: String,
    )

    @Test
    fun `nowe odznaki maja grupe, prog, licznik i tytul z zalozen`() {
        // Kazdy licznik ma tu inna wartosc, wiec po wyniku widac, z ktorego odznaka czyta.
        val znaczone = Liczniki(
            poznane = 101, utrwalone = 102, obieStrony = 103, dzialy = 104, zestawy = 105,
            powtorki = 106, dniNauki = 107, rekordSerii = 108, tryby = 109,
            gamesPlayed = 110, totalKills = 111, bestScore = 112, bestStreak = 113,
        )
        val nauka = GrupaOdznak.NAUKA
        val regularnosc = GrupaOdznak.REGULARNOSC
        val dzialy = GrupaOdznak.DZIALY
        val zalozenia = mapOf(
            "SLOWA_5" to Wiersz(nauka, 5, 101, "Pierwsza piątka", "First five"),
            "SLOWA_50" to Wiersz(nauka, 50, 101, "Pół setki", "Half a hundred"),
            "SLOWA_250" to Wiersz(nauka, 250, 101, "Ćwierć tysiąca", "Quarter thousand"),
            "SLOWA_1000" to Wiersz(nauka, 1000, 101, "Tysiąc słów", "A thousand words"),
            "UTRWALONE_10" to Wiersz(nauka, 10, 102, "Dziesięć na stałe", "Ten locked in"),
            "UTRWALONE_50" to Wiersz(nauka, 50, 102, "Pięćdziesiąt na stałe", "Fifty locked in"),
            "UTRWALONE_200" to Wiersz(nauka, 200, 102, "Dwieście na stałe", "Two hundred locked in"),
            "UTRWALONE_500" to Wiersz(nauka, 500, 102, "Pięćset na stałe", "Five hundred locked in"),
            "POWTORKI_10" to Wiersz(nauka, 10, 106, "W terminie", "On time"),
            "POWTORKI_100" to Wiersz(nauka, 100, 106, "Sto w terminie", "A hundred on time"),
            "POWTORKI_500" to Wiersz(nauka, 500, 106, "Pięćset w terminie", "Five hundred on time"),
            "OBIE_10" to Wiersz(nauka, 10, 103, "W obie strony", "Both ways"),
            "OBIE_100" to Wiersz(nauka, 100, 103, "Tam i z powrotem", "There and back again"),
            "DNI_1" to Wiersz(regularnosc, 1, 107, "Pierwszy dzień", "Day one"),
            "DNI_7" to Wiersz(regularnosc, 7, 107, "Siedem dni", "Seven days"),
            "DNI_30" to Wiersz(regularnosc, 30, 107, "Trzydzieści dni", "Thirty days"),
            "DNI_100" to Wiersz(regularnosc, 100, 107, "Własna studniówka", "Your own hundred days"),
            "SERIA_3" to Wiersz(regularnosc, 3, 108, "Trzy z rzędu", "Three in a row"),
            "SERIA_7" to Wiersz(regularnosc, 7, 108, "Tydzień z rzędu", "A week in a row"),
            "SERIA_14" to Wiersz(regularnosc, 14, 108, "Dwa tygodnie z rzędu", "Two weeks in a row"),
            "SERIA_30" to Wiersz(regularnosc, 30, 108, "Miesiąc z rzędu", "A month in a row"),
            "DZIAL_1" to Wiersz(dzialy, 1, 104, "Pierwszy dział", "First topic"),
            "DZIALY_5" to Wiersz(dzialy, 5, 104, "Pięć działów", "Five topics"),
            "DZIALY_14" to Wiersz(dzialy, 14, 104, "Cała podstawa", "The whole basic level"),
            "ZESTAW_1" to Wiersz(dzialy, 1, 105, "Zestaw na stałe", "A set locked in"),
            "ZESTAWY_5" to Wiersz(dzialy, 5, 105, "Pięć zestawów", "Five sets"),
            // Jedyne odstepstwo od tytulow z zalozen: "Dwadzieścia pięć zestawów" ma 25 znakow.
            "ZESTAWY_25" to Wiersz(dzialy, 25, 105, "25 zestawów", "Twenty-five sets"),
            "ZESTAWY_102" to Wiersz(dzialy, 102, 105, "Komplet", "The full set"),
            "TRYBY_4" to Wiersz(GrupaOdznak.GRA, 4, 109, "Cztery tryby", "All four modes"),
        )
        val dawne = Achievement.entries.map { it.name }.toSet()

        assertEquals(
            "kazda nowa odznaka ma wiersz w zalozeniach i odwrotnie",
            Odznaka.entries.map { it.name }.filter { it !in dawne }.toSet(),
            zalozenia.keys,
        )
        for ((nazwa, wiersz) in zalozenia) {
            val o = Odznaka.valueOf(nazwa)
            assertEquals(
                nazwa,
                wiersz,
                Wiersz(o.grupa, o.cel, o.licznik(znaczone), o.tytulPl, o.tytulEn),
            )
        }
    }

    @Test
    fun `osiem dawnych osiagniec ma te same nazwy, teksty i progi co ekran OSIAGNIECIA`() {
        assertEquals(8, Achievement.entries.size)
        for (a in Achievement.entries) {
            val o = Odznaka.valueOf(a.name)
            assertEquals(a.name, GrupaOdznak.GRA, o.grupa)
            assertEquals(a.name, a.titlePl, o.tytulPl)
            assertEquals(a.name, a.titleEn, o.tytulEn)
            assertEquals(a.name, a.descPl, o.opisPl)
            assertEquals(a.name, a.descEn, o.opisEn)
        }

        // Te same progi: kazda kombinacja wartosci tuz pod progiem, na progu i nad nim.
        val gry = listOf(0, 4, 5, 6, 24, 25, 26)
        val pokonane = listOf(0, 1, 2, 99, 100, 101)
        val wyniki = listOf(0, 499, 500, 501, 1999, 2000, 2001)
        val serie = listOf(0, 5, 6, 7, 8, 9, 10)
        for (g in gry) for (p in pokonane) for (w in wyniki) for (s in serie) {
            val profil = Profile(gamesPlayed = g, totalKills = p, bestScore = w, bestStreak = s)
            val liczniki = Odznaki.liczniki(SaveData(profile = profil))
            for (a in Achievement.entries) {
                assertEquals(
                    "${a.name} dla $profil",
                    a.unlockedBy(profil),
                    Odznaki.spelniona(Odznaka.valueOf(a.name), liczniki),
                )
            }
        }
    }

    // --- teksty -------------------------------------------------------------------------------

    /** Krotkie napisy rysowane czcionka pikselowa: nazwa do komunikatu -> tekst. */
    private fun tytuly(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (o in Odznaka.entries) {
            out["${o.name} PL"] = o.tytulPl
            out["${o.name} EN"] = o.tytulEn
        }
        for (r in Ranga.entries) {
            out["szczebel ${r.name} PL"] = r.pl
            out["szczebel ${r.name} EN"] = r.en
        }
        for (z in Zaliczenie.entries) {
            out["zaliczenie ${z.name} PL"] = z.pl
            out["zaliczenie ${z.name} EN"] = z.en
        }
        for (g in GrupaOdznak.entries) {
            out["grupa ${g.name} PL"] = g.pl
            out["grupa ${g.name} EN"] = g.en
        }
        return out
    }

    private fun opisy(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (o in Odznaka.entries) {
            out["opis ${o.name} PL"] = o.opisPl
            out["opis ${o.name} EN"] = o.opisEn
        }
        return out
    }

    @Test
    fun `kazdy tytul miesci sie w 24 znakach i sklada sie ze znakow czcionki pikselowej`() {
        // Czcionka nie ma nawiasow, procentu, znaku rownosci ani cudzyslowow. Znak spoza arkusza
        // nie wywraca ekranu, tylko zamienia sie po cichu na inny, wiec pilnuje tego test.
        for ((gdzie, tekst) in tytuly()) {
            assertTrue("$gdzie: '$tekst' ma ${tekst.length} znakow", tekst.length <= LIMIT_TYTULU)
            val obce = tekst.filter { it !in PixelFont.SUPPORTED }
            assertTrue("$gdzie: '$tekst' ma znaki spoza czcionki: '$obce'", obce.isEmpty())
        }
    }

    @Test
    fun `zaden tekst nie jest pusty, nie ma zakazanych kresek ani zbednych spacji`() {
        for ((gdzie, tekst) in tytuly() + opisy()) {
            assertTrue("$gdzie jest pusty", tekst.isNotBlank())
            assertEquals("$gdzie ma spacje na brzegu", tekst.trim(), tekst)
            assertFalse("$gdzie ma podwojna spacje", tekst.contains("  "))
            assertTrue(
                "$gdzie ma pauze albo polpauze: '$tekst'",
                tekst.none { it in ZAKAZANE_KRESKI },
            )
        }
    }

    @Test
    fun `opisy nowych odznak sa zdaniami i podaja te same liczby w obu jezykach`() {
        val dawne = Achievement.entries.map { it.name }.toSet()
        val liczba = Regex("\\d+")
        for (o in Odznaka.entries.filter { it.name !in dawne }) {
            assertTrue("${o.name}: opis PL bez kropki", o.opisPl.endsWith("."))
            assertTrue("${o.name}: opis EN bez kropki", o.opisEn.endsWith("."))
            assertEquals(
                "${o.name}: liczby w opisach PL i EN sie roznia",
                liczba.findAll(o.opisPl).map { it.value }.toList(),
                liczba.findAll(o.opisEn).map { it.value }.toList(),
            )
        }
        // Tam, gdzie opis podaje prog cyframi, ma to byc TEN prog. Wyjatki to progi slowne:
        // "jednego", "po raz pierwszy", "czterech".
        val slowne = setOf(Odznaka.DNI_1, Odznaka.DZIAL_1, Odznaka.ZESTAW_1, Odznaka.TRYBY_4)
        for (o in Odznaka.entries.filter { it.name !in dawne && it !in slowne }) {
            assertTrue(
                "${o.name}: opis nie podaje progu ${o.cel}",
                liczba.findAll(o.opisPl).any { it.value == o.cel.toString() },
            )
        }
    }

    // --- obietnice odznak wobec prawdziwego katalogu -----------------------------------------

    @Test
    fun `katalog ma tyle dzialow i zestawow, ile obiecuja odznaki`() {
        // "Komplet" i "Cala podstawa" maja progi wpisane liczba. Gdy katalog urosnie albo sie
        // skurczy, ten test ma przypomniec o progach i opisach, zanim odznaka stanie sie
        // nieosiagalna albo za latwa.
        val katalog = BuiltInSets.all()
        val dzialy = katalog.groupBy { it.category }

        assertEquals(Odznaka.ZESTAWY_102.cel, katalog.size)
        assertFalse("zestaw katalogu bez dzialu w tytule", dzialy.containsKey(null))
        assertEquals(Odznaka.DZIALY_14.cel, dzialy.size)
        for ((dzial, zestawy) in dzialy) {
            val slowa = zestawy.flatMap { it.entries }.map { it.id }.toSet()
            assertTrue("dzial $dzial ma za malo slow na odznake", slowa.size >= Odznaki.PROG_DZIALU)
        }
    }

    @Test
    fun `kazda odznake nauki i dzialow da sie zdobyc na prawdziwym katalogu`() {
        val katalog = BuiltInSets.all()
        val wiersze = katalog.sumOf { it.entries.size }
        val rozne = katalog.flatMap { it.entries }.map { it.id }.toSet()
        // Stan "wszystko utrwalone w obie strony" wpisany recznie: cztery tysiace slow przez
        // harmonogram to minuty kopiowania mapy, a ten test pyta o katalog, nie o reguly.
        val wszystko = HashMap<String, WordProgress>()
        for (id in rozne) {
            for (kierunek in listOf(Direction.TERM_TO_DEF, Direction.DEF_TO_TERM)) {
                wszystko[Srs.key(id, kierunek)] = WordProgress(box = 3, dueDay = DZIS + 7, szczyt = 3)
            }
        }

        val liczniki = Odznaki.liczniki(SaveData(sets = katalog, progress = wszystko))

        assertTrue("katalog nie ma juz slow wspolnych dla kilku zestawow", rozne.size < wiersze)
        assertEquals("slowa wspolne licza sie raz", rozne.size, liczniki.poznane)
        assertEquals(rozne.size, liczniki.utrwalone)
        assertEquals(rozne.size, liczniki.obieStrony)
        assertEquals(katalog.size, liczniki.zestawy)
        assertEquals(Odznaka.DZIALY_14.cel, liczniki.dzialy)

        // Powtorki w terminie licza sie z ksiegi, nie z katalogu, wiec tu ich nie ma.
        val zKsiegi = setOf(Odznaka.POWTORKI_10, Odznaka.POWTORKI_100, Odznaka.POWTORKI_500)
        val zKatalogu = Odznaka.entries.filter {
            (it.grupa == GrupaOdznak.NAUKA || it.grupa == GrupaOdznak.DZIALY) && it !in zKsiegi
        }
        assertEquals(17, zKatalogu.size)
        for (o in zKatalogu) {
            assertTrue("${o.name}: prog ${o.cel} jest poza zasiegiem katalogu", Odznaki.spelniona(o, liczniki))
        }
    }

    @Test
    fun `grupy maja stala kolejnosc, bo ustawia ona ekran`() {
        assertEquals(
            listOf("NAUKA", "REGULARNOSC", "DZIALY", "GRA"),
            GrupaOdznak.entries.map { it.name },
        )
        // Odznaki jednej grupy stoja obok siebie: ekran moze ciac liste po zmianie grupy.
        val kolejnoscGrup = Odznaka.entries.map { it.grupa }.fold(ArrayList<GrupaOdznak>()) { acc, g ->
            if (acc.lastOrNull() != g) acc += g
            acc
        }
        assertEquals(GrupaOdznak.entries.toList(), kolejnoscGrup)
    }
}
