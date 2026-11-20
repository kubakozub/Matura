package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.learn.Attempt
import com.matura.app.learn.Etap
import com.matura.app.learn.GrupaOdznak
import com.matura.app.learn.Liczniki
import com.matura.app.learn.Limit
import com.matura.app.learn.Odliczanie
import com.matura.app.learn.Odznaka
import com.matura.app.learn.Odznaki
import com.matura.app.learn.Outcome
import com.matura.app.learn.RodzinaOdznak
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Odznaki: z czego sie licza, kiedy wchodza do ksiegi i jak sa podpisane.
 *
 * Mapy postepu powstaja tu przez [Srs.applyTo], tak jak w grze. Liczniki opieraja sie na
 * szczycie pudelka, a szczyt ustawia wylacznie harmonogram, wiec mapa wpisana recznie
 * sprawdzalaby tylko to, czy test umie przepisac regule.
 *
 * Slownik gry jest w tych testach PARAMETREM (trzy male zestawy), tak jak w kodzie: liczniki
 * dostaja katalog dolaczony do aplikacji, a nie zestawy z zapisu ucznia.
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

    /** Liczniki dla samej mapy postepu na tle podanego slownika. */
    private fun liczniki(slownik: List<WordSet>, mapa: Map<String, WordProgress>): Liczniki =
        Odznaki.liczniki(SaveData(sets = slownik, progress = mapa), slownik)

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

    /** Liczniki z jedna liczba ustawiona: licznik rodziny [r] rowny [n], reszta zero. */
    private fun licznikiRodziny(r: RodzinaOdznak, n: Int): Liczniki = when (r) {
        RodzinaOdznak.SLOWA -> Liczniki(poznane = n)
        RodzinaOdznak.UTRWALONE -> Liczniki(utrwalone = n)
        RodzinaOdznak.POWTORKI -> Liczniki(powtorki = n)
        RodzinaOdznak.OBIE -> Liczniki(obieStrony = n)
        RodzinaOdznak.NAPRAWA -> Liczniki(naprawione = n)
        RodzinaOdznak.DNI -> Liczniki(dniNauki = n)
        RodzinaOdznak.SERIA -> Liczniki(rekordSerii = n)
        RodzinaOdznak.ZESTAWY -> Liczniki(zestawy = n)
        RodzinaOdznak.DZIALY -> Liczniki(dzialy = n)
        RodzinaOdznak.TRYBY -> Liczniki(tryby = n)
        RodzinaOdznak.POKONANE -> Liczniki(totalKills = n)
        RodzinaOdznak.GRY -> Liczniki(gamesPlayed = n)
        RodzinaOdznak.MNOZNIK -> Liczniki(bestStreak = n)
        RodzinaOdznak.WYNIK -> Liczniki(bestScore = n)
    }

    // --- liczniki slow ----------------------------------------------------------------------

    @Test
    fun `nowy zapis ma same zera i zadnej odznaki`() {
        val katalog = BuiltInSets.all()
        val liczniki = Odznaki.liczniki(SaveData(sets = katalog), katalog)

        assertEquals(Liczniki(), liczniki)
        for (odznaka in Odznaka.entries) {
            assertFalse("${odznaka.name} spelniona bez zadnej nauki", Odznaki.spelniona(odznaka, liczniki))
            assertEquals(0, Odznaki.postep(odznaka, liczniki))
        }
        val pusta = Dorobek()
        assertSame("nie ma czego dopisac, wiec ksiega wraca ta sama", pusta, Odznaki.odblokuj(pusta, liczniki, DZIS))
        assertFalse(Odznaki.komplet(pusta))
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
        val slownik = listOf(zestaw("t001", "Dom · Kuchnia", slowa))
        var mapa = emptyMap<String, WordProgress>()
        mapa = naucz(mapa, listOf(rozpoznane), 1, zadanie = TaskKind.ROZPOZNANIE)
        mapa = naucz(mapa, listOf(poznane), 1)
        mapa = naucz(mapa, listOf(powtarzane), 2)
        mapa = naucz(mapa, listOf(utrwalone), 3, kierunek = Direction.DEF_TO_TERM)
        mapa = naucz(mapa, listOf(wObieStrony), 2)
        mapa = naucz(mapa, listOf(wObieStrony), 2, kierunek = Direction.DEF_TO_TERM)
        // Drugi kierunek tylko w pudelku 1: do "obu stron" to za malo.
        mapa = naucz(mapa, listOf(powtarzane), 1, kierunek = Direction.DEF_TO_TERM)

        val liczniki = liczniki(slownik, mapa)

        assertEquals("wszystko poza slowem nietknietym ($nietkniete)", 5, liczniki.poznane)
        assertEquals("pudelko 3 w jednym kierunku wystarczy", 1, liczniki.utrwalone)
        assertEquals("pudelko 2 musi byc w OBU kierunkach", 1, liczniki.obieStrony)
        assertEquals(0, liczniki.dzialy)
        assertEquals(0, liczniki.zestawy)
    }

    @Test
    fun `to samo slowo w dwoch zestawach liczy sie raz`() {
        val wspolne = idy(1, 3)
        val slownik = listOf(
            zestaw("t001", "Dom · Kuchnia", wspolne + idy(10, 2)),
            zestaw("t002", "Żywienie · Posiłki", wspolne + idy(20, 2)),
        )
        val mapa = naucz(emptyMap(), wspolne + idy(10, 2) + idy(20, 2), 3)

        val liczniki = liczniki(slownik, mapa)

        assertEquals("dziesiec wierszy, ale siedem roznych slow", 7, liczniki.poznane)
        assertEquals(7, liczniki.utrwalone)
        assertEquals(2, liczniki.zestawy)
    }

    @Test
    fun `wlasne zestawy nie zarabiaja odznak slownika`() {
        // Inaczej wystarczylaby wlasna lista latwych slow, zeby "utrwalic caly zestaw".
        val wlasne = (1..12).map { "u" + it.toString(16).padStart(12, '0') }
        val slownik = listOf(zestaw("t001", "Dom · Kuchnia", idy(1, 3)))
        val wZapisie = slownik + zestaw("user-1", "Dom · Moje", wlasne, wbudowany = false)
        var mapa = naucz(emptyMap(), wlasne, 3)
        mapa = naucz(mapa, wlasne, 3, kierunek = Direction.DEF_TO_TERM)

        val liczniki = Odznaki.liczniki(SaveData(sets = wZapisie, progress = mapa), slownik)

        assertEquals(0, liczniki.poznane)
        assertEquals(0, liczniki.utrwalone)
        assertEquals(0, liczniki.obieStrony)
        assertEquals("wlasny zestaw nie tworzy dzialu ani go nie zasila", 0, liczniki.dzialy)
        assertEquals(0, liczniki.zestawy)
    }

    @Test
    fun `klucze postepu spoza slownika sie nie licza`() {
        // Mapa postepu nie jest sprzatana: po korekcie katalogu lezy w niej stary klucz slowa,
        // a po usunieciu zestawu postep jego slow. Liczone po mapie zawyzalyby wynik.
        val wSlowniku = idy(1, 2)
        val osierocone = idy(50, 8)
        val mapa = naucz(emptyMap(), wSlowniku + osierocone, 3)

        val liczniki = liczniki(listOf(zestaw("t001", "Dom · Kuchnia", wSlowniku)), mapa)

        assertEquals(2, liczniki.poznane)
        assertEquals(2, liczniki.utrwalone)
    }

    @Test
    fun `kopia zestawu wbudowanego poprawiona w edytorze nie zmienia licznikow`() {
        // Zapis trzyma wlasna kopie zestawow wbudowanych i uczen moze ja otworzyc w edytorze.
        // Tu skrocil zestaw do jednego slowa i dopisal wlasny wiersz: liczy sie slownik
        // z aplikacji, wiec ani zestaw nie jest "utrwalony w calosci", ani dopisek slowem.
        val slowa = idy(1, 3)
        val slownik = listOf(zestaw("t001", "Dom · Kuchnia", slowa))
        val dopisane = "w00000000dddd"
        val kopiaUcznia = zestaw("t001", "Dom · Kuchnia", listOf(slowa[0], dopisane))
        val mapa = naucz(emptyMap(), listOf(slowa[0], dopisane), 3)

        val liczniki = Odznaki.liczniki(SaveData(sets = listOf(kopiaUcznia), progress = mapa), slownik)

        assertEquals(1, liczniki.poznane)
        assertEquals(1, liczniki.utrwalone)
        assertEquals("w slowniku zestaw ma trzy slowa, utrwalone jest jedno", 0, liczniki.zestawy)

        // Zapis bez zadnego zestawu (wszystkie usuniete) liczy sie tak samo.
        assertEquals(liczniki, Odznaki.liczniki(SaveData(progress = mapa), slownik))
    }

    @Test
    fun `pomylka cofa pudelko, ale nie licznik i nie odznake`() {
        val slowa = idy(1, 10)
        val slownik = listOf(zestaw("t001", "Dom · Kuchnia", slowa))
        val nauczone = naucz(emptyMap(), slowa, 3)
        val przed = liczniki(slownik, nauczone)
        assertEquals(10, przed.utrwalone)
        assertEquals(1, przed.zestawy)
        assertEquals(1, przed.dzialy)
        val ksiega = Odznaki.odblokuj(Dorobek(), przed, DZIS)
        assertTrue(Odznaki.zdobyta(Odznaka.UTRWALONE_5, ksiega))
        assertTrue(Odznaki.zdobyta(Odznaka.ZESTAW_1, ksiega))
        assertTrue(Odznaki.zdobyta(Odznaka.DZIAL_1, ksiega))

        var poBledach = nauczone
        for (id in slowa.take(7)) poBledach = pomyl(poBledach, id)
        assertEquals(1, Srs.progressOf(poBledach, slowa[0], Direction.TERM_TO_DEF).box)

        val po = liczniki(slownik, poBledach)

        assertEquals("uczniowi niczego nie odbieramy", przed, po)
        assertTrue(Odznaki.spelniona(Odznaka.UTRWALONE_5, po))
        assertTrue(Odznaki.spelniona(Odznaka.ZESTAW_1, po))
        // Ksiega po bledach jest ta sama: nic nie ubylo i nic nie zostalo ogloszone drugi raz.
        assertSame(ksiega, Odznaki.odblokuj(ksiega, po, DZIS + 100))
    }

    @Test
    fun `stan sprzed migracji liczy sie wedlug pudelka, gdy szczytu jeszcze nie ma`() {
        val slowa = idy(1, 2)
        val mapa = mapOf(
            Srs.key(slowa[0], Direction.TERM_TO_DEF) to WordProgress(box = 3, dueDay = DZIS),
            Srs.key(slowa[1], Direction.DEF_TO_TERM) to WordProgress(box = 1, dueDay = DZIS, szczyt = 2),
        )

        val liczniki = liczniki(listOf(zestaw("t001", "Dom · Kuchnia", slowa)), mapa)

        assertEquals(2, liczniki.poznane)
        assertEquals(1, liczniki.utrwalone)
    }

    // --- dzialy i zestawy -------------------------------------------------------------------

    @Test
    fun `dzial liczy sie od dziesieciu slow przypomnianych po przerwie, ze wszystkich jego zestawow`() {
        val kuchnia = idy(1, 6)
        val pokoj = idy(6, 6)          // slowo nr 6 jest w obu zestawach dzialu
        val klasa = idy(100, 9) + idy(6, 1)
        val slownik = listOf(
            zestaw("t001", "Dom · Kuchnia", kuchnia),
            zestaw("t002", "Dom · Pokój", pokoj),
            zestaw("t003", "Szkoła · Klasa", klasa),
            zestaw("t004", "Bez działu", idy(200, 12)),
        )
        fun dzialy(mapa: Map<String, WordProgress>) = liczniki(slownik, mapa).dzialy

        // Jedenascie slow dzialu Dom poznanych, ale tylko poznanych: pudelko 1 to za malo,
        // czy z pamieci, czy przez rozpoznanie.
        var mapa = naucz(emptyMap(), idy(1, 6), 1)
        mapa = naucz(mapa, idy(7, 5), 1, zadanie = TaskKind.ROZPOZNANIE)
        assertEquals(11, liczniki(slownik, mapa).poznane)
        assertEquals("dziesiec trafionych kafli to jeszcze nie znajomosc dzialu", 0, dzialy(mapa))

        // Dziewiec roznych slow dzialu Dom po pierwszej powtorce z pamieci.
        mapa = naucz(emptyMap(), idy(1, 9), 2)
        assertEquals(0, dzialy(mapa))

        // Dziesiate moze byc w drugim kierunku: liczy sie wyzszy z dwoch.
        mapa = naucz(mapa, idy(10, 1), 2, kierunek = Direction.DEF_TO_TERM)
        assertEquals(1, dzialy(mapa))

        // Szkola ma juz jedno takie slowo (wspolne z Domem); osiem kolejnych to wciaz dziewiec.
        mapa = naucz(mapa, idy(100, 8), 2)
        assertEquals(1, dzialy(mapa))
        mapa = naucz(mapa, idy(108, 1), 3)
        assertEquals(2, dzialy(mapa))

        // Zestaw bez dzialu w tytule nie jest dzialem, chocby byl caly utrwalony.
        mapa = naucz(mapa, idy(200, 12), 3)
        assertEquals(2, dzialy(mapa))
        assertEquals(10, Odznaki.PROG_DZIALU)
    }

    @Test
    fun `zestaw liczy sie dopiero wtedy, gdy kazde jego slowo jest utrwalone`() {
        val caly = idy(1, 3)
        val prawie = idy(10, 3)
        val zDziura = idy(20, 2)
        val slownik = listOf(
            zestaw("t001", "Dom · Kuchnia", caly),
            zestaw("t002", "Dom · Pokój", prawie),
            zestaw("t003", "Dom · Pusty", emptyList()),
            // Wpis bez identyfikatora nie ma jak byc utrwalony.
            zestaw("t004", "Dom · Stary", zDziura).let { it.copy(entries = it.entries + Entry("x", "y")) },
        )
        var mapa = naucz(emptyMap(), caly.take(2), 3)
        // Trzecie slowo utrwalone w DRUGIM kierunku: dowolny kierunek wystarczy.
        mapa = naucz(mapa, caly.drop(2), 3, kierunek = Direction.DEF_TO_TERM)
        mapa = naucz(mapa, prawie.take(2), 3)
        mapa = naucz(mapa, prawie.drop(2), 2)
        mapa = naucz(mapa, zDziura, 3)

        val liczniki = liczniki(slownik, mapa)

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
            dorobek = Dorobek(powtorki = 11, naprawione = 2, dniNauki = 4, rekordSerii = 3, seria = 1),
        )

        assertEquals(
            Liczniki(
                powtorki = 11,
                naprawione = 2,
                dniNauki = 4,
                rekordSerii = 3,
                tryby = 2,
                gamesPlayed = 7,
                totalKills = 120,
                bestScore = 640,
                bestStreak = 8,
            ),
            Odznaki.liczniki(dane, emptyList()),
        )
    }

    @Test
    fun `cztery tryby to cztery rozne tryby na liscie wynikow`() {
        fun wynik(tryb: GameMode) = ScoreRecord("Kuba", "Zestaw", 0, 1, 0, 0, 1L, tryb)
        val trzy = SaveData(scores = GameMode.entries.take(3).map(::wynik))
        val cztery = SaveData(scores = GameMode.entries.map(::wynik))

        assertFalse(Odznaki.spelniona(Odznaka.TRYBY_4, Odznaki.liczniki(trzy, emptyList())))
        assertTrue(Odznaki.spelniona(Odznaka.TRYBY_4, Odznaki.liczniki(cztery, emptyList())))
        assertEquals("odznaka obiecuje wszystkie tryby gry", GameMode.entries.size, Odznaka.TRYBY_4.cel)
    }

    // --- progi --------------------------------------------------------------------------------

    /** Jeden wiersz zalozen: rodzina, prog, tytuly. */
    private data class Wiersz(val rodzina: RodzinaOdznak, val cel: Int, val pl: String, val en: String)

    @Test
    fun `nowe odznaki maja rodzine, prog i tytul z zalozen`() {
        val zalozenia = mapOf(
            "SLOWA_1" to Wiersz(RodzinaOdznak.SLOWA, 1, "PIERWSZE SŁOWO", "FIRST WORD"),
            "SLOWA_25" to Wiersz(RodzinaOdznak.SLOWA, 25, "25 SŁÓW", "25 WORDS"),
            "SLOWA_75" to Wiersz(RodzinaOdznak.SLOWA, 75, "75 SŁÓW", "75 WORDS"),
            "SLOWA_250" to Wiersz(RodzinaOdznak.SLOWA, 250, "ĆWIERĆ TYSIĄCA", "QUARTER THOUSAND"),
            "UTRWALONE_5" to Wiersz(RodzinaOdznak.UTRWALONE, 5, "5 UTRWALONYCH", "5 CONSOLIDATED"),
            "UTRWALONE_20" to Wiersz(RodzinaOdznak.UTRWALONE, 20, "20 UTRWALONYCH", "20 CONSOLIDATED"),
            "UTRWALONE_60" to Wiersz(RodzinaOdznak.UTRWALONE, 60, "60 UTRWALONYCH", "60 CONSOLIDATED"),
            "UTRWALONE_200" to Wiersz(RodzinaOdznak.UTRWALONE, 200, "200 UTRWALONYCH", "200 CONSOLIDATED"),
            "POWTORKI_10" to Wiersz(RodzinaOdznak.POWTORKI, 10, "W TERMINIE", "ON TIME"),
            "POWTORKI_100" to Wiersz(RodzinaOdznak.POWTORKI, 100, "STO W TERMINIE", "A HUNDRED ON TIME"),
            "POWTORKI_500" to Wiersz(RodzinaOdznak.POWTORKI, 500, "PIĘĆSET W TERMINIE", "FIVE HUNDRED ON TIME"),
            "POWTORKI_1500" to Wiersz(RodzinaOdznak.POWTORKI, 1500, "1500 W TERMINIE", "1500 ON TIME"),
            "OBIE_10" to Wiersz(RodzinaOdznak.OBIE, 10, "W OBIE STRONY", "BOTH WAYS"),
            "OBIE_50" to Wiersz(RodzinaOdznak.OBIE, 50, "TAM I Z POWROTEM", "THERE AND BACK AGAIN"),
            "NAPRAWA_1" to Wiersz(RodzinaOdznak.NAPRAWA, 1, "DO TRZECH RAZY SZTUKA", "THIRD TIME LUCKY"),
            "NAPRAWA_5" to Wiersz(RodzinaOdznak.NAPRAWA, 5, "TRUDNE? JUŻ NIE", "HARD? NOT ANYMORE"),
            "NAPRAWA_25" to Wiersz(RodzinaOdznak.NAPRAWA, 25, "25 PO POPRAWCE", "25 FIXED"),
            "DNI_10" to Wiersz(RodzinaOdznak.DNI, 10, "10 DNI NAUKI", "10 STUDY DAYS"),
            "DNI_25" to Wiersz(RodzinaOdznak.DNI, 25, "25 DNI NAUKI", "25 STUDY DAYS"),
            "DNI_50" to Wiersz(RodzinaOdznak.DNI, 50, "50 DNI NAUKI", "50 STUDY DAYS"),
            "DNI_100" to Wiersz(RodzinaOdznak.DNI, 100, "STO DNI NAUKI", "A HUNDRED STUDY DAYS"),
            "SERIA_3" to Wiersz(RodzinaOdznak.SERIA, 3, "SERIA 3", "STREAK 3"),
            "SERIA_7" to Wiersz(RodzinaOdznak.SERIA, 7, "SERIA 7", "STREAK 7"),
            "SERIA_14" to Wiersz(RodzinaOdznak.SERIA, 14, "SERIA 14", "STREAK 14"),
            "SERIA_30" to Wiersz(RodzinaOdznak.SERIA, 30, "SERIA 30", "STREAK 30"),
            "ZESTAW_1" to Wiersz(RodzinaOdznak.ZESTAWY, 1, "PIERWSZY ZESTAW", "FIRST SET"),
            "ZESTAWY_3" to Wiersz(RodzinaOdznak.ZESTAWY, 3, "TRZY ZESTAWY", "THREE SETS"),
            "ZESTAWY_8" to Wiersz(RodzinaOdznak.ZESTAWY, 8, "OSIEM ZESTAWÓW", "EIGHT SETS"),
            "ZESTAWY_15" to Wiersz(RodzinaOdznak.ZESTAWY, 15, "PIĘTNAŚCIE ZESTAWÓW", "FIFTEEN SETS"),
            "DZIAL_1" to Wiersz(RodzinaOdznak.DZIALY, 1, "PIERWSZY DZIAŁ", "FIRST TOPIC"),
            "DZIALY_5" to Wiersz(RodzinaOdznak.DZIALY, 5, "PIĘĆ DZIAŁÓW", "FIVE TOPICS"),
            "DZIALY_14" to Wiersz(RodzinaOdznak.DZIALY, 14, "OBJAZDÓWKA", "GRAND TOUR"),
            "TRYBY_4" to Wiersz(RodzinaOdznak.TRYBY, 4, "CZTERY TRYBY", "ALL FOUR MODES"),
        )
        val dawne = Achievement.entries.map { it.name }.toSet()

        assertEquals(
            "kazda nowa odznaka ma wiersz w zalozeniach i odwrotnie",
            Odznaka.entries.map { it.name }.filter { it !in dawne }.toSet(),
            zalozenia.keys,
        )
        for ((nazwa, wiersz) in zalozenia) {
            val o = Odznaka.valueOf(nazwa)
            assertEquals(nazwa, wiersz, Wiersz(o.rodzina, o.cel, o.tytulPl, o.tytulEn))
        }
    }

    @Test
    fun `kazda odznaka spelnia sie dokladnie na swoim progu i tylko ze swojego licznika`() {
        for (o in Odznaka.entries) {
            val tuzPod = licznikiRodziny(o.rodzina, o.cel - 1)
            val naProgu = licznikiRodziny(o.rodzina, o.cel)
            assertFalse("${o.name}: spelniona przy ${o.cel - 1}", Odznaki.spelniona(o, tuzPod))
            assertTrue("${o.name}: niespelniona przy ${o.cel}", Odznaki.spelniona(o, naProgu))
            assertEquals(o.name, o.cel - 1, Odznaki.postep(o, tuzPod))
            assertEquals(o.name, o.cel, Odznaki.postep(o, naProgu))

            // Zaden inny licznik, chocby ogromny, tej odznaki nie daje.
            for (inna in RodzinaOdznak.entries.filter { it != o.rodzina }) {
                assertFalse(
                    "${o.name} spelniona z licznika rodziny ${inna.name}",
                    Odznaki.spelniona(o, licznikiRodziny(inna, 1_000_000)),
                )
            }
        }
    }

    @Test
    fun `rodzina ma swoj licznik i grupe, a odznaka dziedziczy oba`() {
        // Kazdy licznik ma tu inna wartosc, wiec po wyniku widac, z ktorego rodzina czyta.
        val znaczone = Liczniki(
            poznane = 101, utrwalone = 102, obieStrony = 103, dzialy = 104, zestawy = 105,
            powtorki = 106, naprawione = 107, dniNauki = 108, rekordSerii = 109, tryby = 110,
            gamesPlayed = 111, totalKills = 112, bestScore = 113, bestStreak = 114,
        )
        val nauka = GrupaOdznak.NAUKA
        val gra = GrupaOdznak.GRA
        assertEquals(
            mapOf(
                RodzinaOdznak.SLOWA to (nauka to 101),
                RodzinaOdznak.UTRWALONE to (nauka to 102),
                RodzinaOdznak.POWTORKI to (nauka to 106),
                RodzinaOdznak.OBIE to (nauka to 103),
                RodzinaOdznak.NAPRAWA to (nauka to 107),
                RodzinaOdznak.DNI to (GrupaOdznak.REGULARNOSC to 108),
                RodzinaOdznak.SERIA to (GrupaOdznak.REGULARNOSC to 109),
                RodzinaOdznak.ZESTAWY to (GrupaOdznak.DZIALY to 105),
                RodzinaOdznak.DZIALY to (GrupaOdznak.DZIALY to 104),
                RodzinaOdznak.TRYBY to (gra to 110),
                RodzinaOdznak.POKONANE to (gra to 112),
                RodzinaOdznak.GRY to (gra to 111),
                RodzinaOdznak.MNOZNIK to (gra to 114),
                RodzinaOdznak.WYNIK to (gra to 113),
            ),
            RodzinaOdznak.entries.associateWith { it.grupa to it.licznik(znaczone) },
        )
        for (o in Odznaka.entries) {
            assertEquals(o.name, o.rodzina.grupa, o.grupa)
            assertEquals(o.name, o.rodzina.licznik(znaczone), o.licznik(znaczone))
        }
    }

    @Test
    fun `stopnie rodziny stoja obok siebie i maja rosnace progi`() {
        for (r in RodzinaOdznak.entries) {
            val stopnie = Odznaki.stopnie(r)
            assertTrue("rodzina ${r.name} nie ma zadnej odznaki", stopnie.isNotEmpty())
            stopnie.zipWithNext { nizszy, wyzszy ->
                assertTrue("${wyzszy.name} nie jest wyzej niz ${nizszy.name}", wyzszy.cel > nizszy.cel)
                assertEquals("${nizszy.name} i ${wyzszy.name} nie stoja obok siebie", nizszy.ordinal + 1, wyzszy.ordinal)
            }
        }
        assertEquals(
            "kazda odznaka nalezy do dokladnie jednej rodziny",
            Odznaka.entries.toList(),
            RodzinaOdznak.entries.flatMap { Odznaki.stopnie(it) }.sortedBy { it.ordinal },
        )
        assertEquals(
            listOf(Odznaka.SLOWA_1, Odznaka.SLOWA_25, Odznaka.SLOWA_75, Odznaka.SLOWA_250),
            Odznaki.stopnie(RodzinaOdznak.SLOWA),
        )
        assertEquals(listOf(Odznaka.FIRST_BLOOD, Odznaka.SHARPSHOOTER), Odznaki.stopnie(RodzinaOdznak.POKONANE))
    }

    @Test
    fun `postep rodziny to licznik przyciety do progu nastepnego stopnia`() {
        val rodzina = RodzinaOdznak.SLOWA
        var ksiega = Dorobek()
        assertEquals(Odznaka.SLOWA_1, Odznaki.nastepna(rodzina, ksiega))

        // 63 poznane slowa: dwa stopnie zdobyte, pasek pokazuje 63 z 75.
        val liczniki = Liczniki(poznane = 63)
        ksiega = Odznaki.odblokuj(ksiega, liczniki, DZIS)
        val nastepna = Odznaki.nastepna(rodzina, ksiega)
        assertEquals(Odznaka.SLOWA_75, nastepna)
        assertEquals(63, Odznaki.postep(nastepna!!, liczniki))
        assertEquals(75, nastepna.cel)

        // Licznik ponad ostatni prog: nie ma juz nastepnego stopnia, a postep nie wychodzi
        // poza prog ("2033 z 250" na ekranie nie bedzie).
        val wszystko = Liczniki(poznane = 2033)
        ksiega = Odznaki.odblokuj(ksiega, wszystko, DZIS + 1)
        assertNull(Odznaki.nastepna(rodzina, ksiega))
        assertEquals(250, Odznaki.postep(Odznaka.SLOWA_250, wszystko))
        assertEquals(0, Odznaki.postep(Odznaka.SLOWA_250, Liczniki(poznane = -4)))
    }

    // --- przyznawanie i potwierdzanie ---------------------------------------------------------

    @Test
    fun `odblokuj dopisuje nowe odznaki z dniem, odklada je do pokazania i nie rusza starych`() {
        val ksiega = Dorobek(
            xp = 70,
            odznaki = mapOf(
                "SLOWA_1" to 0,
                // Odznaka, ktorej licznik jest dzis ponizej progu: zostaje, bo byla zdobyta.
                "ZESTAW_1" to DZIS - 30,
                // Nazwa z nowszej wersji aplikacji: nie nasza, ale tez nie do skasowania.
                "ODZNAKA_Z_PRZYSZLOSCI" to DZIS - 1,
            ),
            odznakiNowe = setOf("ZESTAW_1"),
        )
        val liczniki = Liczniki(poznane = 25, rekordSerii = 3)

        val po = Odznaki.odblokuj(ksiega, liczniki, DZIS)

        assertEquals(
            mapOf(
                "SLOWA_1" to 0,
                "ZESTAW_1" to DZIS - 30,
                "ODZNAKA_Z_PRZYSZLOSCI" to DZIS - 1,
                "SLOWA_25" to DZIS,
                "SERIA_3" to DZIS,
            ),
            po.odznaki,
        )
        assertEquals("nowe dochodza do czekajacych, a czekajaca zostaje", setOf("ZESTAW_1", "SLOWA_25", "SERIA_3"), po.odznakiNowe)
        assertEquals(
            "odznaki nie daja punktow nauki i nie ruszaja reszty ksiegi",
            ksiega.copy(odznaki = po.odznaki, odznakiNowe = po.odznakiNowe),
            po,
        )
        assertTrue(Odznaki.zdobyta(Odznaka.ZESTAW_1, po))
        assertTrue(Odznaki.zdobyta(Odznaka.SLOWA_25, po))
        assertFalse(Odznaki.zdobyta(Odznaka.SLOWA_75, po))

        // Drugi raz z tymi samymi licznikami: nic nowego, ta sama ksiega, te same dni.
        assertSame(po, Odznaki.odblokuj(po, liczniki, DZIS + 9))
    }

    @Test
    fun `odblokowanie po cichu dopisuje odznaki, ale niczego nie odklada do pokazania`() {
        val liczniki = Liczniki(totalKills = 140, gamesPlayed = 12)

        val po = Odznaki.odblokuj(Dorobek(), liczniki, Odznaki.DZIEN_SPRZED_KSIEGI, poCichu = true)

        assertEquals(mapOf("FIRST_BLOOD" to 0, "SHARPSHOOTER" to 0, "ROOKIE" to 0), po.odznaki)
        assertTrue(po.odznakiNowe.isEmpty())
        assertEquals(0, Odznaki.DZIEN_SPRZED_KSIEGI)
    }

    @Test
    fun `potwierdzenie czysci czekajace i nie rusza zdobytych`() {
        val ksiega = Odznaki.odblokuj(Dorobek(xp = 10), Liczniki(poznane = 1, dniNauki = 10), DZIS)
        assertEquals(setOf("SLOWA_1", "DNI_10"), ksiega.odznakiNowe)

        val po = Odznaki.potwierdz(ksiega)

        assertEquals(ksiega.copy(odznakiNowe = emptySet()), po)
        assertEquals(ksiega.odznaki, po.odznaki)
        assertSame("nie ma czego odhaczac, wiec ksiega wraca ta sama", po, Odznaki.potwierdz(po))
    }

    @Test
    fun `komplet jest dopiero wtedy, gdy w ksiedze sa wszystkie odznaki`() {
        val wszystkie = Odznaka.entries.associate { it.name to DZIS }

        assertTrue(Odznaki.komplet(Dorobek(odznaki = wszystkie)))
        assertTrue("obca nazwa nie przeszkadza", Odznaki.komplet(Dorobek(odznaki = wszystkie + ("OBCA" to 1))))
        assertFalse(Odznaki.komplet(Dorobek(odznaki = wszystkie - "SERIA_30")))
    }

    // --- nazwy: to jest czesc zapisu ----------------------------------------------------------

    @Test
    fun `nazwy odznak sa czescia zapisu i nie zmieniaja sie po cichu`() {
        // Dorobek.odznaki trzyma zdobyte odznaki POD TYMI NAZWAMI. Zmiana albo usuniecie nazwy
        // odbiera uczniowi odznake; jesli ten test pada, potrzebna jest migracja zapisu, a nie
        // poprawka listy ponizej. Nowe odznaki dopisuje sie tu swiadomie, jako nowe stale.
        assertEquals(
            listOf(
                "SLOWA_1", "SLOWA_25", "SLOWA_75", "SLOWA_250",
                "UTRWALONE_5", "UTRWALONE_20", "UTRWALONE_60", "UTRWALONE_200",
                "POWTORKI_10", "POWTORKI_100", "POWTORKI_500", "POWTORKI_1500",
                "OBIE_10", "OBIE_50",
                "NAPRAWA_1", "NAPRAWA_5", "NAPRAWA_25",
                "DNI_10", "DNI_25", "DNI_50", "DNI_100",
                "SERIA_3", "SERIA_7", "SERIA_14", "SERIA_30",
                "ZESTAW_1", "ZESTAWY_3", "ZESTAWY_8", "ZESTAWY_15",
                "DZIAL_1", "DZIALY_5", "DZIALY_14",
                "TRYBY_4",
                "FIRST_BLOOD", "SHARPSHOOTER", "ROOKIE", "VETERAN",
                "COMBO_X3", "COMBO_X5", "SCORE_500", "SCORE_2000",
            ),
            Odznaka.entries.map { it.name },
        )
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
            val liczniki = Odznaki.liczniki(SaveData(profile = profil), emptyList())
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

    private val dawne = Achievement.entries.map { it.name }.toSet()
    private val noweOdznaki = Odznaka.entries.filter { it.name !in dawne }

    /** Krotkie napisy rysowane czcionka pikselowa: nazwa do komunikatu -> tekst. */
    private fun tytuly(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (o in Odznaka.entries) {
            out["${o.name} PL"] = o.tytulPl
            out["${o.name} EN"] = o.tytulEn
        }
        for (e in Etap.entries) {
            out["etap ${e.name} PL"] = e.pl
            out["etap ${e.name} EN"] = e.en
        }
        for (g in GrupaOdznak.entries) {
            out["grupa ${g.name} PL"] = g.pl
            out["grupa ${g.name} EN"] = g.en
        }
        // Odliczanie: trzy stale stany i wzor z liczba, takze z najdluzsza, jaka ma sens
        // (data matury ustawiona na kilka lat naprzod).
        val odliczania = listOf(Odliczanie.Dzis, Odliczanie.Jutro, Odliczanie.Sto) +
            listOf(2, 99, 167, 2500).map { Odliczanie.Dni(it) }
        for (o in odliczania) {
            out["odliczanie $o PL"] = o.pl
            out["odliczanie $o EN"] = o.en
        }
        return out
    }

    /** Zdania i etykiety pisane czcionka systemowa. */
    private fun opisy(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (o in Odznaka.entries) {
            out["opis ${o.name} PL"] = o.opisPl
            out["opis ${o.name} EN"] = o.opisEn
        }
        for (z in Zaliczenie.entries) {
            out["zaliczenie ${z.name} PL"] = z.pl
            out["zaliczenie ${z.name} EN"] = z.en
        }
        for (l in Limit.entries) {
            out["limit ${l.name} PL"] = l.pl
            out["limit ${l.name} EN"] = l.en
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
    fun `tytuly nowych odznak, etapow i grup sa pisane wersalikami`() {
        val wersaliki = noweOdznaki.flatMap { listOf(it.tytulPl, it.tytulEn) } +
            Etap.entries.flatMap { listOf(it.pl, it.en) } +
            GrupaOdznak.entries.flatMap { listOf(it.pl, it.en) }
        for (tekst in wersaliki) assertEquals(tekst.uppercase(), tekst)
    }

    @Test
    fun `teksty nie obiecuja wiecej, niz liczniki licza`() {
        // Zwroty wyciete z tekstow celowo: "na stale" i "na zawsze" obiecuja pamiec, ktorej
        // harmonogram nie gwarantuje; "z rzedu" nie jest prawda przy serii z dniami wolnymi;
        // procent i "gotowosc" brzmia jak prognoza wyniku. Tytuly "Cala podstawa" i "Komplet"
        // obiecywaly caly zakres matury za ulamek slownika.
        val zakazane = listOf(
            "na stałe", "na zawsze", "z rzędu", "cała podstawa", "komplet", "gotowoś", "%",
            "locked in", "in a row", "forever", "whole basic", "full set", "readiness",
        )
        for ((gdzie, tekst) in tytuly() + opisy()) {
            for (zwrot in zakazane) {
                assertFalse("$gdzie: '$tekst' zawiera '$zwrot'", tekst.lowercase().contains(zwrot))
            }
        }
    }

    @Test
    fun `opisy nowych odznak sa zdaniami, wspolnymi dla rodziny, z tymi samymi liczbami w obu jezykach`() {
        val liczba = Regex("\\d+")
        for (o in noweOdznaki) {
            assertTrue("${o.name}: opis PL bez kropki", o.opisPl.endsWith("."))
            assertTrue("${o.name}: opis EN bez kropki", o.opisEn.endsWith("."))
            assertEquals(
                "${o.name}: liczby w opisach PL i EN sie roznia",
                liczba.findAll(o.opisPl).map { it.value }.toList(),
                liczba.findAll(o.opisEn).map { it.value }.toList(),
            )
        }
        // Opis mowi, CO jest liczone, wiec wszystkie stopnie rodziny maja ten sam. Jedyny
        // dopisek ma setny dzien nauki.
        for (r in RodzinaOdznak.entries) {
            val stopnie = Odznaki.stopnie(r).filter { it.name !in dawne && it != Odznaka.DNI_100 }
            assertTrue("${r.name}: stopnie maja rozne opisy PL", stopnie.map { it.opisPl }.toSet().size <= 1)
            assertTrue("${r.name}: stopnie maja rozne opisy EN", stopnie.map { it.opisEn }.toSet().size <= 1)
        }
        assertEquals("${Odznaka.DNI_10.opisPl} Tyle, ile od studniówki do matury.", Odznaka.DNI_100.opisPl)
        assertEquals("${Odznaka.DNI_10.opisEn} As many as from the studniówka ball to the exam.", Odznaka.DNI_100.opisEn)

        // Jedyna liczba w opisach to prog dzialu i ma to byc TEN prog.
        val zLiczba = noweOdznaki.filter { liczba.containsMatchIn(it.opisPl) }
        assertEquals(Odznaki.stopnie(RodzinaOdznak.DZIALY), zLiczba)
        for (o in zLiczba) {
            assertEquals(listOf(Odznaki.PROG_DZIALU.toString()), liczba.findAll(o.opisPl).map { it.value }.toList())
        }
    }

    @Test
    fun `tytul z liczba podaje prog odznaki`() {
        val liczba = Regex("\\d+")
        for (o in noweOdznaki) {
            for (tytul in listOf(o.tytulPl, o.tytulEn)) {
                val liczby = liczba.findAll(tytul).map { it.value.toInt() }.toList()
                if (liczby.isNotEmpty()) assertEquals("${o.name}: '$tytul'", listOf(o.cel), liczby)
            }
        }
    }

    // --- obietnice odznak wobec prawdziwego katalogu -----------------------------------------

    @Test
    fun `katalog ma tyle dzialow i zestawow, ile obiecuja odznaki`() {
        // OBJAZDOWKA ma prog rowny liczbie dzialow wpisany liczba. Gdy katalog urosnie albo sie
        // skurczy, ten test ma przypomniec o progu, zanim odznaka stanie sie nieosiagalna albo
        // przestanie znaczyc "wszystkie dzialy".
        val katalog = BuiltInSets.all()
        val dzialy = katalog.groupBy { it.category }

        assertFalse("zestaw katalogu bez dzialu w tytule", dzialy.containsKey(null))
        assertEquals(Odznaka.DZIALY_14.cel, dzialy.size)
        for ((dzial, zestawy) in dzialy) {
            val slowa = zestawy.flatMap { it.entries }.map { it.id }.toSet()
            assertTrue("dzial $dzial ma za malo slow na odznake", slowa.size >= Odznaki.PROG_DZIALU)
        }
        assertTrue(Odznaki.stopnie(RodzinaOdznak.ZESTAWY).last().cel <= katalog.size)
    }

    @Test
    fun `kazda odznake za slowa, zestawy i dzialy da sie zdobyc na prawdziwym katalogu`() {
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

        val liczniki = Odznaki.liczniki(SaveData(progress = wszystko), katalog)

        assertTrue("katalog nie ma juz slow wspolnych dla kilku zestawow", rozne.size < wiersze)
        assertEquals("slowa wspolne licza sie raz", rozne.size, liczniki.poznane)
        assertEquals(rozne.size, liczniki.utrwalone)
        assertEquals(rozne.size, liczniki.obieStrony)
        assertEquals(katalog.size, liczniki.zestawy)
        assertEquals(Odznaka.DZIALY_14.cel, liczniki.dzialy)

        val zKatalogu = listOf(
            RodzinaOdznak.SLOWA, RodzinaOdznak.UTRWALONE, RodzinaOdznak.OBIE,
            RodzinaOdznak.ZESTAWY, RodzinaOdznak.DZIALY,
        ).flatMap { Odznaki.stopnie(it) }
        assertEquals(17, zKatalogu.size)
        for (o in zKatalogu) {
            assertTrue("${o.name}: prog ${o.cel} jest poza zasiegiem katalogu", Odznaki.spelniona(o, liczniki))
        }
    }

    @Test
    fun `grupy maja stala kolejnosc i tytuly z zalozen`() {
        assertEquals(
            listOf(
                Triple("NAUKA", "NAUKA", "LEARNING"),
                Triple("REGULARNOSC", "REGULARNOŚĆ", "HABIT"),
                Triple("DZIALY", "DZIAŁY", "TOPICS"),
                Triple("GRA", "GRA", "GAME"),
            ),
            GrupaOdznak.entries.map { Triple(it.name, it.pl, it.en) },
        )
        // Odznaki jednej grupy stoja obok siebie: ekran moze ciac liste po zmianie grupy.
        val kolejnoscGrup = Odznaka.entries.map { it.grupa }.fold(ArrayList<GrupaOdznak>()) { acc, g ->
            if (acc.lastOrNull() != g) acc += g
            acc
        }
        assertEquals(GrupaOdznak.entries.toList(), kolejnoscGrup)
    }
}
