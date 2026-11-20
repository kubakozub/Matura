package com.matura.app

import com.matura.app.learn.Attempt
import com.matura.app.learn.FormyLiczby
import com.matura.app.learn.KategoriaDzialu
import com.matura.app.learn.Outcome
import com.matura.app.learn.Podsumowanie
import com.matura.app.learn.Srs
import com.matura.app.learn.TekstyDzialu
import com.matura.app.learn.TekstyRundy
import com.matura.app.model.Direction
import com.matura.app.model.TaskKind
import com.matura.app.model.WordProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Podsumowanie nauki po rundzie — liczby i zdania, ktore zobaczy uczen.
 *
 * Testy NIE wpisuja map postepu recznie: stan "po rundzie" powstaje z przepuszczenia prob przez
 * [Srs.applyTo], czyli przez te sama droge, ktora idzie prawdziwa rozgrywka. Gdyby regula
 * harmonogramu i podsumowanie kiedys sie rozjechaly, ten plik ma to zlapac — bo najgorszy blad
 * na tym ekranie nie wywala aplikacji, tylko cicho klamie uczniowi o tym, co umie.
 */
class PodsumowanieTest {

    private companion object {
        /** Dowolny dzien od epoki UTC — jeden na caly plik, zeby testy czytalo sie jak kalendarz. */
        const val DZIS = 19_875

        val KIERUNEK = Direction.TERM_TO_DEF
    }

    private val tekstyPl = TekstyRundy(
        brakProb = "Ta runda nie sprawdziła żadnego słowa",
        przecwiczone = "Przećwiczone: %s",
        doPonowienia = "Do ponowienia: %s",
        bezBledow = "Bez błędów — nic do ponowienia",
        nowoPoznane = "Nowe słowa: %s",
        jutro = "Jutro wróć do %s",
        zaDni = "Za %1\$s wróć do %2\$s",
        brakPowtorek = "Nic nie czeka na powtórkę",
        zaplanowaneRazem = "Zaplanowanych powtórek: %s",
        slowa = FormyLiczby("słowo", "słowa", "słów"),
        // Dopelniacz, bo zdanie brzmi "wroc DO": do 1 powtorki, do 2 powtorek, do 5 powtorek.
        powtorki = FormyLiczby("powtórki", "powtórek", "powtórek"),
        dni = FormyLiczby("dzień", "dni", "dni"),
    )

    private val tekstyDzialuPl = TekstyDzialu(
        naglowek = "POSTĘP DZIAŁU",
        nowe = "Nowe",
        wNauce = "W nauce",
        doPowtorki = "Do powtórki",
        utrwalone = "Utrwalone",
    )

    private fun proba(
        entryId: String,
        outcome: Outcome,
        task: TaskKind = TaskKind.PRZYPOMNIENIE,
        direction: Direction = KIERUNEK,
    ) = Attempt(entryId, direction, task, outcome)

    /** Stan po rundzie liczony tak, jak liczy go gra: proba po probie, przez reguly [Srs]. */
    private fun poRundzie(
        przed: Map<String, WordProgress> = emptyMap(),
        proby: List<Attempt>,
        dzis: Int = DZIS,
    ): Map<String, WordProgress> {
        var mapa = przed
        proby.forEach { mapa = Srs.applyTo(mapa, it, dzis) }
        return mapa
    }

    private fun postepDla(entryId: String, stan: WordProgress) =
        mapOf(Srs.key(entryId, KIERUNEK) to stan)

    // --- liczby rundy ------------------------------------------------------------------------

    @Test
    fun `liczby zgadzaja sie z danymi wejsciowymi`() {
        val zakres = listOf("w1", "w2", "w3", "w4", "w5")
        val proby = listOf(
            proba("w1", Outcome.POPRAWNA),
            proba("w2", Outcome.BLEDNA),
            proba("w3", Outcome.PODPOWIEDZ),
            proba("w4", Outcome.POPRAWNA),
        )
        val po = poRundzie(proby = proby)

        val wynik = Podsumowanie.rundy(proby, emptyMap(), po, zakres, KIERUNEK, DZIS)

        assertEquals("cztery slowa mialy probe, piate nie bylo ruszone", 4, wynik.przecwiczone)
        assertEquals("blad i podpowiedz", 2, wynik.doPonowienia)
        assertEquals("dwa slowa wyszly z pudelka 0", 2, wynik.nowoPoznane)
        // w1 i w4 dostaly termin na jutro; w2 i w3 wrocily na dzis, wiec nie sa "zaplanowane".
        assertEquals(2, wynik.zaplanowane)
        assertEquals(1, wynik.najblizszaZaDni)
        assertEquals(2, wynik.naNajblizszyDzien)
        assertEquals(listOf("w2", "w3"), wynik.doPoprawki)
        assertFalse(wynik.pusta)
    }

    @Test
    fun `zdania powtarzaja te same liczby, ktore policzyla logika`() {
        val zakres = listOf("w1", "w2", "w3", "w4", "w5")
        val proby = listOf(
            proba("w1", Outcome.POPRAWNA),
            proba("w2", Outcome.BLEDNA),
            proba("w3", Outcome.PODPOWIEDZ),
            proba("w4", Outcome.POPRAWNA),
        )
        val po = poRundzie(proby = proby)
        val wynik = Podsumowanie.rundy(proby, emptyMap(), po, zakres, KIERUNEK, DZIS)

        assertEquals(
            listOf(
                "Przećwiczone: 4 słowa",
                "Do ponowienia: 2",
                "Nowe słowa: 2",
                "Jutro wróć do 2 powtórek",
            ),
            Podsumowanie.linie(wynik, tekstyPl),
        )
    }

    @Test
    fun `slowo z kilkoma probami liczy sie raz`() {
        val proby = listOf(
            proba("w1", Outcome.BLEDNA),
            proba("w1", Outcome.BLEDNA),
            proba("w1", Outcome.PODPOWIEDZ),
            proba("w1", Outcome.POPRAWNA),
        )
        val po = poRundzie(proby = proby)

        val wynik = Podsumowanie.rundy(proby, emptyMap(), po, listOf("w1"), KIERUNEK, DZIS)

        assertEquals(1, wynik.przecwiczone)
        assertEquals(1, wynik.doPonowienia)
        assertEquals(listOf("w1"), wynik.doPoprawki)
    }

    @Test
    fun `poprawna odpowiedz po bledzie nie zdejmuje slowa z ponowienia`() {
        // Srs trzyma takie slowo zalegle na dzis (assistedDay blokuje awans), wiec podsumowanie
        // nie moze ogloszyc "bez bledow" — inaczej ekran i harmonogram mowilyby co innego.
        val proby = listOf(proba("w1", Outcome.BLEDNA), proba("w1", Outcome.POPRAWNA))
        val po = poRundzie(proby = proby)

        val wynik = Podsumowanie.rundy(proby, emptyMap(), po, listOf("w1"), KIERUNEK, DZIS)

        assertEquals(1, wynik.doPonowienia)
        assertTrue(Srs.isDue(Srs.progressOf(po, "w1", KIERUNEK), DZIS))
    }

    @Test
    fun `proba przerwana nie jest cwiczeniem`() {
        val proby = listOf(proba("w1", Outcome.PRZERWANA), proba("w2", Outcome.PRZERWANA))
        val po = poRundzie(proby = proby)

        val wynik = Podsumowanie.rundy(proby, emptyMap(), po, listOf("w1", "w2"), KIERUNEK, DZIS)

        assertEquals(0, wynik.przecwiczone)
        assertEquals(0, wynik.doPonowienia)
        assertTrue(wynik.pusta)
    }

    @Test
    fun `pokazana odpowiedz tez trafia do ponowienia`() {
        val proby = listOf(proba("w1", Outcome.POKAZANA))
        val po = poRundzie(proby = proby)

        val wynik = Podsumowanie.rundy(proby, emptyMap(), po, listOf("w1"), KIERUNEK, DZIS)

        assertEquals(1, wynik.przecwiczone)
        assertEquals(1, wynik.doPonowienia)
    }

    // --- runda bez ani jednej proby ----------------------------------------------------------

    @Test
    fun `runda bez prob nie pokazuje zadnych zer`() {
        val wynik = Podsumowanie.rundy(
            proby = emptyList(),
            przed = emptyMap(),
            po = emptyMap(),
            zakres = listOf("w1", "w2", "w3"),
            kierunek = KIERUNEK,
            dzis = DZIS,
        )

        assertTrue(wynik.pusta)
        assertEquals(0, wynik.przecwiczone)
        assertEquals(0, wynik.doPonowienia)
        assertEquals(0, wynik.zaplanowane)
        assertNull(wynik.najblizszaZaDni)
        assertTrue(wynik.doPoprawki.isEmpty())

        val linie = Podsumowanie.linie(wynik, tekstyPl)
        assertEquals(listOf("Ta runda nie sprawdziła żadnego słowa", "Nic nie czeka na powtórkę"), linie)
        // "Przecwiczone: 0 slow" brzmi jak porazka, a nikt nie odpowiadal. Zadnej cyfry.
        assertTrue(
            "podsumowanie pustej rundy nie moze pokazywac liczb",
            linie.none { linia -> linia.any { it.isDigit() } },
        )
    }

    @Test
    fun `runda bez prob nadal mowi prawde o zaplanowanych powtorkach`() {
        // Uczen wszedl i wyszedl, ale harmonogram sprzed rundy zyje dalej — i tak ma go zobaczyc.
        val przed = postepDla("w1", WordProgress(box = 2, dueDay = DZIS + 3))

        val wynik = Podsumowanie.rundy(emptyList(), przed, przed, listOf("w1"), KIERUNEK, DZIS)

        assertEquals(1, wynik.zaplanowane)
        assertEquals(3, wynik.najblizszaZaDni)
        assertEquals(1, wynik.naNajblizszyDzien)
        assertEquals(
            listOf("Ta runda nie sprawdziła żadnego słowa", "Za 3 dni wróć do 1 powtórki"),
            Podsumowanie.linie(wynik, tekstyPl),
        )
    }

    // --- terminy -----------------------------------------------------------------------------

    @Test
    fun `zdanie o jutrze podaje liczbe powtorek na jutro, nie na caly tydzien`() {
        val przed = mapOf(
            Srs.key("w1", KIERUNEK) to WordProgress(box = 1, dueDay = DZIS + 1),
            Srs.key("w2", KIERUNEK) to WordProgress(box = 1, dueDay = DZIS + 1),
            Srs.key("w3", KIERUNEK) to WordProgress(box = 2, dueDay = DZIS + 3),
            Srs.key("w4", KIERUNEK) to WordProgress(box = 3, dueDay = DZIS + 7),
        )

        val wynik = Podsumowanie.rundy(
            emptyList(), przed, przed, listOf("w1", "w2", "w3", "w4"), KIERUNEK, DZIS,
        )

        assertEquals(4, wynik.zaplanowane)
        assertEquals(2, wynik.naNajblizszyDzien)

        val linie = Podsumowanie.linie(wynik, tekstyPl)
        assertTrue(linie.contains("Jutro wróć do 2 powtórek"))
        assertTrue(linie.contains("Zaplanowanych powtórek: 4"))
    }

    @Test
    fun `termin na dzis nie jest powtorka na przyszlosc`() {
        val przed = postepDla("w1", WordProgress(box = 1, dueDay = DZIS))

        val wynik = Podsumowanie.rundy(emptyList(), przed, przed, listOf("w1"), KIERUNEK, DZIS)

        assertEquals(0, wynik.zaplanowane)
        assertNull(wynik.najblizszaZaDni)
    }

    @Test
    fun `losowany kierunek liczy terminy obu kierunkow`() {
        val przed = mapOf(
            Srs.key("w1", Direction.TERM_TO_DEF) to WordProgress(box = 1, dueDay = DZIS + 1),
            Srs.key("w1", Direction.DEF_TO_TERM) to WordProgress(box = 1, dueDay = DZIS + 1),
        )

        val wynik = Podsumowanie.rundy(
            emptyList(), przed, przed, listOf("w1"), Direction.RANDOM, DZIS,
        )

        assertEquals("jedno slowo, ale dwa cele do powtorzenia", 2, wynik.zaplanowane)
    }

    // --- krotka poprawka ---------------------------------------------------------------------

    @Test
    fun `poprawka zaczyna sie od bledow, potem podpowiedzi, potem pokazane`() {
        val proby = listOf(
            proba("wPokazane", Outcome.POKAZANA),
            proba("wPodpowiedz", Outcome.PODPOWIEDZ),
            proba("wBlad", Outcome.BLEDNA),
        )
        val po = poRundzie(proby = proby)

        val wynik = Podsumowanie.rundy(
            proby, emptyMap(), po, listOf("wPokazane", "wPodpowiedz", "wBlad"), KIERUNEK, DZIS,
        )

        assertEquals(listOf("wBlad", "wPodpowiedz", "wPokazane"), wynik.doPoprawki)
    }

    @Test
    fun `przy remisie pierwsze idzie slowo z dluzsza historia pomylek`() {
        val przed = mapOf(
            Srs.key("swieze", KIERUNEK) to WordProgress(box = 1, dueDay = DZIS, mistakes = 0),
            Srs.key("uparte", KIERUNEK) to WordProgress(box = 1, dueDay = DZIS, mistakes = 6),
        )
        val proby = listOf(proba("swieze", Outcome.BLEDNA), proba("uparte", Outcome.BLEDNA))
        val po = poRundzie(przed, proby)

        val wynik = Podsumowanie.rundy(proby, przed, po, listOf("swieze", "uparte"), KIERUNEK, DZIS)

        assertEquals(listOf("uparte", "swieze"), wynik.doPoprawki)
    }

    @Test
    fun `poprawka jest krotka`() {
        val slowa = (1..9).map { "w$it" }
        val proby = slowa.map { proba(it, Outcome.BLEDNA) }
        val po = poRundzie(proby = proby)

        val wynik = Podsumowanie.rundy(proby, emptyMap(), po, slowa, KIERUNEK, DZIS)

        assertEquals(9, wynik.doPonowienia)
        assertEquals(Podsumowanie.LIMIT_POPRAWKI, wynik.doPoprawki.size)
    }

    // --- postep dzialu -----------------------------------------------------------------------

    @Test
    fun `kategoria slowa wynika z pudelka i terminu`() {
        assertEquals(
            KategoriaDzialu.NOWE,
            Podsumowanie.kategoria(WordProgress(), DZIS),
        )
        assertEquals(
            KategoriaDzialu.W_NAUCE,
            Podsumowanie.kategoria(WordProgress(box = 1, dueDay = DZIS + 1), DZIS),
        )
        assertEquals(
            KategoriaDzialu.DO_POWTORKI,
            Podsumowanie.kategoria(WordProgress(box = 2, dueDay = DZIS), DZIS),
        )
        assertEquals(
            KategoriaDzialu.UTRWALONE,
            Podsumowanie.kategoria(WordProgress(box = 3, dueDay = DZIS + 7), DZIS),
        )
        // Utrwalone, ktoremu minal termin, wraca do powtorki — inaczej licznik utrwalonych
        // rosnie w nieskonczonosc i nie mowi nic o pracy, ktora dzis czeka.
        assertEquals(
            KategoriaDzialu.DO_POWTORKI,
            Podsumowanie.kategoria(WordProgress(box = 3, dueDay = DZIS), DZIS),
        )
    }

    @Test
    fun `kategorie dzialu sumuja sie do liczby slow w dziale`() {
        val slowa = (1..12).map { "w$it" }
        val progress = mapOf(
            Srs.key("w1", KIERUNEK) to WordProgress(box = 1, dueDay = DZIS + 1),
            Srs.key("w2", KIERUNEK) to WordProgress(box = 2, dueDay = DZIS + 3),
            Srs.key("w3", KIERUNEK) to WordProgress(box = 3, dueDay = DZIS + 7),
            Srs.key("w4", KIERUNEK) to WordProgress(box = 3, dueDay = DZIS),
            Srs.key("w5", KIERUNEK) to WordProgress(box = 1, dueDay = DZIS),
            // w6..w12 nie maja wpisu — to sa slowa nowe.
        )

        val postep = Podsumowanie.dzialu(progress, slowa, KIERUNEK, DZIS)

        assertEquals(12, postep.wszystkie)
        assertEquals(7, postep.nowe)
        assertEquals(2, postep.doPowtorki)
        assertEquals(2, postep.wNauce)
        assertEquals(1, postep.utrwalone)
        assertEquals(
            postep.wszystkie,
            postep.nowe + postep.doPowtorki + postep.wNauce + postep.utrwalone,
        )
    }

    @Test
    fun `powtorzone i puste identyfikatory nie rozsadzaja sumy dzialu`() {
        val slowa = listOf("w1", "w1", "", "w2", "w2", "w3")

        val postep = Podsumowanie.dzialu(emptyMap(), slowa, KIERUNEK, DZIS)

        assertEquals("trzy rozne, niepuste identyfikatory", 3, postep.wszystkie)
        assertEquals(3, postep.nowe)
        assertEquals(
            postep.wszystkie,
            postep.nowe + postep.doPowtorki + postep.wNauce + postep.utrwalone,
        )
    }

    @Test
    fun `przy losowaniu kierunku liczy sie slabsza strona slowa`() {
        // Uczen zna "term -> definicja", drugiej strony nie probowal. Slowo nie jest utrwalone.
        val progress = mapOf(
            Srs.key("w1", Direction.TERM_TO_DEF) to WordProgress(box = 3, dueDay = DZIS + 7),
        )

        val postep = Podsumowanie.dzialu(progress, listOf("w1"), Direction.RANDOM, DZIS)

        assertEquals(1, postep.wszystkie)
        assertEquals(1, postep.nowe)
        assertEquals(0, postep.utrwalone)
    }

    @Test
    fun `pusty dzial daje same zera`() {
        val postep = Podsumowanie.dzialu(emptyMap(), emptyList(), KIERUNEK, DZIS)

        assertEquals(0, postep.wszystkie)
        assertEquals(0, postep.nowe + postep.doPowtorki + postep.wNauce + postep.utrwalone)
    }

    // --- odmiana liczebnikow -----------------------------------------------------------------

    @Test
    fun `polska liczba mnoga odmienia sie poprawnie`() {
        val slowa = tekstyPl.slowa
        assertEquals("1 słowo", Podsumowanie.liczba(1, slowa))
        assertEquals("2 słowa", Podsumowanie.liczba(2, slowa))
        assertEquals("4 słowa", Podsumowanie.liczba(4, slowa))
        assertEquals("5 słów", Podsumowanie.liczba(5, slowa))
        assertEquals("11 słów", Podsumowanie.liczba(11, slowa))
        assertEquals("12 słów", Podsumowanie.liczba(12, slowa))
        assertEquals("14 słów", Podsumowanie.liczba(14, slowa))
        assertEquals("22 słowa", Podsumowanie.liczba(22, slowa))
        assertEquals("112 słów", Podsumowanie.liczba(112, slowa))
        assertEquals("0 słów", Podsumowanie.liczba(0, slowa))
    }

    @Test
    fun `angielski dostaje jedna forme mnoga`() {
        val words = FormyLiczby("word", "words", "words")
        assertEquals("1 word", Podsumowanie.liczba(1, words))
        assertEquals("5 words", Podsumowanie.liczba(5, words))
    }

    @Test
    fun `szablon bez znacznika nie wywala ekranu`() {
        // Literowka w tabeli tlumaczen ma zepsuc zdanie, a nie skonczona runde.
        val zepsute = tekstyPl.copy(przecwiczone = "Przećwiczone")
        val proby = listOf(proba("w1", Outcome.POPRAWNA))
        val po = poRundzie(proby = proby)
        val wynik = Podsumowanie.rundy(proby, emptyMap(), po, listOf("w1"), KIERUNEK, DZIS)

        val linie = Podsumowanie.linie(wynik, zepsute)

        assertEquals("Przećwiczone", linie.first())
    }

    @Test
    fun `bez bledow nie ma zdania o ponowieniu`() {
        val proby = listOf(proba("w1", Outcome.POPRAWNA), proba("w2", Outcome.POPRAWNA))
        val po = poRundzie(proby = proby)
        val wynik = Podsumowanie.rundy(proby, emptyMap(), po, listOf("w1", "w2"), KIERUNEK, DZIS)

        val linie = Podsumowanie.linie(wynik, tekstyPl)

        assertTrue(linie.contains("Bez błędów — nic do ponowienia"))
        assertTrue(linie.none { it.startsWith("Do ponowienia") })
    }

    @Test
    fun `etykiety dzialu sa krotkie na plakietke`() {
        // Plakietka rysuje sie czcionka pikselowa w jednej linii; dwie obok siebie musza zmiescic
        // sie na ekranie 360 dp. Nazwa dluzsza niz kilkanascie znakow wyjdzie poza krawedz.
        listOf(
            tekstyDzialuPl.nowe,
            tekstyDzialuPl.wNauce,
            tekstyDzialuPl.doPowtorki,
            tekstyDzialuPl.utrwalone,
        ).forEach { assertTrue("za dluga etykieta: $it", it.length <= 12) }
    }
}
