package com.matura.app

import com.matura.app.game.GameConfig
import com.matura.app.game.GameEngine
import com.matura.app.game.SubmitResult
import com.matura.app.learn.Attempt
import com.matura.app.learn.Outcome
import com.matura.app.learn.Srs
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.TaskKind
import com.matura.app.model.WordProgress
import com.matura.app.model.WordSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Dobor slow w trybie CLASSIC i uczciwosc tego, co gra zapisuje o uczniu.
 *
 * PO CO TEN PLIK. Do tej pory kazdy potworek niosl slowo wylosowane jednostajnie z calego
 * zestawu, bez zadnej pamieci: slowo umiane od miesiaca wracalo tak samo czesto jak to,
 * na ktorym uczen wlasnie sie potknal, a przy zestawie na 60 hasel polowa z nich nie
 * pojawiala sie w rundzie ani razu. Teraz kolejnosc bierze sie z harmonogramu powtorek
 * (com.matura.app.learn.Srs), a gra dopisuje do niego to, co widziala.
 *
 * DRUGA POLOWA to uczciwosc: gra ma prawo zapisac tylko to, czego naprawde jest swiadkiem.
 * Potworek, ktory doszedl do dziala, nie dowodzi niewiedzy (uczen mogl pisac inne slowo),
 * a chybiona odpowiedz przy pieciu haslach na planszy nie mowi, ktorego z nich dotyczyla.
 * Te dwa przypadki maja tu wlasne testy, bo to one decyduja, czy harmonogram mowi prawde.
 *
 * Testy sa ZWYKLYM JVM-em: silnik dostaje historie parametrem (GameConfig.progress), wiec
 * nie potrzebuje ani Androida, ani repozytorium.
 */
class DoborSlowTest {

    /** Male, jednoznaczne slowa: zadne nie jest podobne do drugiego, wiec dopasowania sie nie myla. */
    private val slowa = listOf(
        "dog" to "pies",
        "cat" to "kot",
        "house" to "dom",
        "book" to "ksiazka",
        "table" to "stol",
        "window" to "okno",
        "flower" to "kwiat",
        "river" to "rzeka",
        "mountain" to "gora",
        "bread" to "chleb",
        "cheese" to "ser",
        "school" to "szkola",
    )

    /** Zestaw z TRWALYMI identyfikatorami: w1..wN, dokladnie tak, jak po migracji zapisu. */
    private fun zestaw(ile: Int = slowa.size) = WordSet(
        id = "t",
        title = "test",
        entries = slowa.take(ile).mapIndexed { i, (t, d) -> Entry(t, d, "w" + (i + 1)) },
    )

    private fun silnik(
        postep: Map<String, WordProgress> = emptyMap(),
        dzis: Int = 0,
        ziarno: Int = 1,
        ile: Int = slowa.size,
        nowychNaRunde: Int = 10,
        zycia: Int = 3,
    ) = GameEngine(
        set = zestaw(ile),
        config = GameConfig(
            lives = zycia,
            direction = Direction.TERM_TO_DEF,
            // Fale automatyczne wylaczone: test steruje tym, co i kiedy wychodzi na plansze.
            autoSpawn = false,
            progress = postep,
            today = dzis,
            newWordsPerRound = nowychNaRunde,
        ),
        random = Random(ziarno),
    )

    /** Kolejne hasla, ktore silnik wystawil na plansze. */
    private fun kolejnosc(e: GameEngine, ile: Int): List<String> =
        (1..ile).map { e.spawnMonster(); e.snapshot().monsters.last().prompt }

    private fun postep(vararg pary: Pair<String, WordProgress>): Map<String, WordProgress> =
        pary.associate { (id, p) -> Srs.key(id, Direction.TERM_TO_DEF) to p }

    // ---- A. dobor z historii ----------------------------------------------

    /**
     * Zalegle powtorki ida pierwsze, a najpilniejsza z nich przed pozostalymi.
     *
     * "Pilnosc" to nie jest osobne pojecie do utrzymania: liczy sie z tego, co i tak jest
     * w harmonogramie (pomylki, podpowiedzi, pudelko, pomoc tego samego dnia).
     */
    @Test
    fun doborZaczynaOdZaleglychPowtorek_najpilniejszaNaPrzodzie() {
        val e = silnik(
            postep(
                // zalegle, ale spokojne: pudelko 2, termin minal dawno
                "w5" to WordProgress(box = 2, dueDay = 90),
                // zalegle i pilne: uczen potykal sie na nim trzy razy
                "w9" to WordProgress(box = 1, dueDay = 100, mistakes = 3),
                // utrwalone, termin dopiero za sto dni
                "w2" to WordProgress(box = 3, dueDay = 200),
            ),
            dzis = 100,
        )
        val wyszly = kolejnosc(e, slowa.size)

        assertEquals("najpilniejsza zaleglosc ma wyjsc pierwsza", "mountain", wyszly[0])
        assertEquals("druga zaleglosc zaraz po niej", "table", wyszly[1])
        assertEquals("utrwalone, jeszcze nie w terminie, ma wyjsc na samym koncu", "cat", wyszly.last())
        assertEquals("zadne haslo nie moze stac na planszy dwa razy", wyszly.size, wyszly.toSet().size)
    }

    /**
     * Nowych slow wchodzi do rundy ograniczona liczba. Bez tego sufitu dluga runda przy
     * swiezym zestawie pokazalaby kilkadziesiat nowych hasel po jednym razie i nie wrocila
     * do zadnego - czyli dokladne przeciwienstwo powtorek.
     */
    @Test
    fun nowychSlowWchodziRozsadnaLiczba_potemWracaZnane() {
        val e = silnik(
            postep(
                "w1" to WordProgress(box = 1, dueDay = 0),      // zalegle
                "w2" to WordProgress(box = 3, dueDay = 200),    // utrwalone, nie w terminie
            ),
            dzis = 100,
            nowychNaRunde = 3,
        )
        val wyszly = kolejnosc(e, slowa.size)

        assertEquals("zaleglosc pierwsza", "dog", wyszly[0])
        assertEquals(
            "po trzech nowych wraca to, co juz znane - a nie czwarte nowe slowo",
            "cat", wyszly[4],
        )
        assertTrue(
            "miedzy nimi maja stac wylacznie NOWE slowa: " + wyszly,
            wyszly.subList(1, 4).none { it == "dog" || it == "cat" },
        )
    }

    /** Z historia kolejnosc wynika z harmonogramu, a nie z ziarna losowania. */
    @Test
    fun zHistoria_pierwszeSlowoNieZalezyOdZiarna() {
        val historia = postep("w7" to WordProgress(box = 2, dueDay = 1))
        val pierwsze = (1..12).map { ziarno ->
            kolejnosc(silnik(historia, dzis = 100, ziarno = ziarno), 1).single()
        }.toSet()
        assertEquals(setOf("flower"), pierwsze)
    }

    // ---- B. gra o rekord (bez historii) ------------------------------------

    /**
     * Bez historii wszystko zostaje jak dotad: slowa leca losowo, a runda jest gra o wynik.
     * To jest tryb, ktory ma pozostac dostepny - i to on jest domyslny dla wolajacego,
     * ktory historii nie poda.
     */
    @Test
    fun bezHistorii_doborZostajeLosowy() {
        assertFalse("brak historii = gra o rekord", silnik().plannedLearning)
        assertTrue("z historia = zaplanowana nauka", silnik(postep("w1" to WordProgress(box = 1))).plannedLearning)

        val pierwsze = (1..12).map { ziarno -> kolejnosc(silnik(ziarno = ziarno), 1).single() }.toSet()
        assertTrue(
            "bez historii pierwsze slowo ma zalezec od losowania, a nie od planu: " + pierwsze,
            pierwsze.size > 1,
        )
    }

    /** Ten sam zestaw i to samo ziarno daja te sama runde - inaczej zaden test gry nie ma sensu. */
    @Test
    fun bezHistorii_toSamoZiarnoDajeTaSamaRunde() {
        assertEquals(kolejnosc(silnik(ziarno = 5), 8), kolejnosc(silnik(ziarno = 5), 8))
        assertNotEquals(kolejnosc(silnik(ziarno = 5), 8), kolejnosc(silnik(ziarno = 6), 8))
    }

    // ---- C. przypadki brzegowe puli ---------------------------------------

    /** Pusty zestaw: nic nie wychodzi, nic sie nie wywraca, nic nie trafia do harmonogramu. */
    @Test
    fun pustaPula_niczegoNieWystawiaINieWywracaGry() {
        val e = GameEngine(
            set = WordSet(id = "e", title = "empty"),
            config = GameConfig(progress = postep("w1" to WordProgress(box = 1)), today = 5),
            random = Random(1),
        )
        assertFalse(e.isPlayable)
        repeat(50) { e.spawnMonster() }
        assertTrue(e.snapshot().monsters.isEmpty())
        // Fale automatyczne sa tu WLACZONE - pusta pula nie moze ich zawiesic ani zakonczyc gry.
        repeat(400) { e.tick(0.05f) }
        assertFalse(e.gameOver)
        assertTrue(e.takeLearningEvents().isEmpty())
    }

    /**
     * Pula mniejsza niz plansza. Gra ma dalej wystawiac cele (inaczej plansza pustoszeje),
     * ale dopoki jest z czego wybierac, dwa te same napisy nie moga stac obok siebie -
     * strzal nie mialby wtedy jednoznacznego celu.
     */
    @Test
    fun pulaMniejszaNizPlansza_dalejWystawiaCele() {
        val e = silnik(
            postep("w1" to WordProgress(box = 2, dueDay = 0)),
            dzis = 10,
            ile = 2,
        )
        val wyszly = kolejnosc(e, GameEngine.MAX_MONSTERS + 3)
        assertEquals("gra nie moze przestac wystawiac celow", GameEngine.MAX_MONSTERS + 3, wyszly.size)
        assertNotEquals("dopoki jest wybor, sasiedzi maja byc rozni", wyszly[0], wyszly[1])
    }

    /**
     * Dobor z planu nie ma prawa sie zapetlic ani przy pelnej planszy, ani przy jednym slowie.
     * Limit czasu jest tu CZESCIA testu: bez niego nieskonczona petla wygladalaby jak zawieszenie
     * calego zestawu testow, a nie jak blad tej jednej funkcji.
     */
    @Test(timeout = 15_000L)
    fun doborSieNieZapetla() {
        val jedno = silnik(postep("w1" to WordProgress(box = 1, dueDay = 0)), dzis = 10, ile = 1)
        repeat(500) { jedno.spawnMonster() }
        assertEquals(500, jedno.snapshot().monsters.size)

        // Pelna runda z falami automatycznymi i skrzyniami, dwadziescia minut gry.
        val pelna = GameEngine(
            set = zestaw(),
            config = GameConfig(
                lives = 99,
                progress = postep("w3" to WordProgress(box = 2, dueDay = 0)),
                today = 10,
            ),
            random = Random(11),
        )
        repeat(24_000) { pelna.tick(0.05f) }
        assertTrue(pelna.snapshot().monsters.size <= GameEngine.MAX_MONSTERS)
    }

    // ---- D. co gra zapisuje o uczniu --------------------------------------

    /** Zaliczona odpowiedz to proba PRZY TYM slowie i w kierunku, w ktorym padla. */
    @Test
    fun zaliczonaOdpowiedz_zapisujeProbePrzyWlasciwymSlowie() {
        val e = silnik()
        e.debugAddMonster("dog", "pies", x = 0.9f, y = 0.5f, entryId = "w1")
        e.setTyped("pies")
        assertTrue(e.submit() is SubmitResult.Fired)

        val z = e.takeLearningEvents().single()
        assertEquals("w1", z.entryId)
        assertEquals(Outcome.POPRAWNA, z.outcome)
        assertEquals(Direction.TERM_TO_DEF, z.direction)
        assertTrue("kolejka ma sie oproznic, zeby ta sama proba nie wyszla dwa razy", e.takeLearningEvents().isEmpty())
    }

    /** Skrzynia niesie zwykle slowo z zestawu, wiec jej zdobycie tez jest odpowiedzia. */
    @Test
    fun zabranaSkrzynia_tezJestOdpowiedzia() {
        val e = silnik()
        e.debugAddCrate("cat", "kot", entryId = "w2")
        e.setTyped("kot")
        assertTrue(e.submit() is SubmitResult.CrateTaken)
        assertEquals(Outcome.POPRAWNA, e.takeLearningEvents().single().outcome)
    }

    /**
     * POTWOREK, KTORY DOSZEDL DO DZIALA, NIE JEST POMYLKA.
     *
     * Uczen mogl pisac inne haslo, moglo zabraknac sekundy, mogla zawiesc klawiatura. Gra
     * odnotowuje tylko tyle, ze przy tym slowie potrzebna byla pomoc: pudelko zostaje, termin
     * zostaje, licznik pomylek zostaje - ale awans tego dnia jest zablokowany, wiec slowo
     * wraca do spokojnego sprawdzenia zamiast awansowac z rozpedu.
     */
    @Test
    fun potworekPrzyDziale_nieJestZapisanyJakoPomylka() {
        val e = silnik()
        e.debugAddMonster(
            "dog", "pies",
            x = GameEngine.CANNON_X + 0.061f, y = GameEngine.CANNON_Y,
            entryId = "w1",
        )
        repeat(200) { e.tick(0.05f) }
        assertTrue("test ma sens tylko, gdy potworek naprawde doszedl", e.snapshot().monsters.isEmpty())

        val z = e.takeLearningEvents().single()
        assertEquals("w1", z.entryId)
        assertNotEquals("to nie jest bledna odpowiedz", Outcome.BLEDNA, z.outcome)

        // Skutek w harmonogramie, a nie tylko nazwa wyniku: slowo utrwalone ma takim przejsciem
        // nie stracic ani pudelka, ani terminu, ani reputacji.
        val przed = WordProgress(box = 3, dueDay = 200)
        val po = Srs.apply(
            przed,
            Attempt("w1", Direction.TERM_TO_DEF, TaskKind.PRZYPOMNIENIE, z.outcome),
            today = 100,
        )
        assertEquals("pudelko zostaje", 3, po.box)
        assertEquals("termin zostaje", 200, po.dueDay)
        assertEquals("to nie liczy sie jako pomylka", 0, po.mistakes)
        assertEquals("ale dzien z pomoca jest odnotowany", 100, po.assistedDay)
    }

    /** Potworek, do ktorego juz leci pocisk, jest odpowiedziany - drugiej notatki byc nie moze. */
    @Test
    fun potworekZLecacymPociskiem_nieDopisujeDrugiegoZdarzenia() {
        val e = silnik()
        e.debugAddMonster(
            "dog", "pies",
            x = GameEngine.CANNON_X + 0.0601f, y = GameEngine.CANNON_Y,
            entryId = "w1",
        )
        e.setTyped("pies")
        assertTrue(e.submit() is SubmitResult.Fired)
        assertEquals(Outcome.POPRAWNA, e.takeLearningEvents().single().outcome)

        // Krotkie kroki: potworek pokonuje swoje ostatnie tysieczne czesci zanim doleci pocisk.
        repeat(20) { e.tick(0.001f) }
        assertTrue("potworek mial dojsc do dziala", e.snapshot().monsters.isEmpty())
        assertTrue(
            "odpowiedz juz padla, wiec dojscie do dziala nie dopisuje nic wiecej",
            e.takeLearningEvents().isEmpty(),
        )
    }

    /**
     * Chybienie przy kilku haslach na planszy nie mowi, ktorego z nich dotyczylo. Zapisujemy
     * je tylko wtedy, gdy jest bliskie dokladnie jednemu haslu (literowka, przekrecona
     * koncowka); w kazdym innym przypadku milczymy.
     */
    @Test
    fun chybienieNieDoPrzypisania_niczegoNieZapisuje() {
        val e = silnik()
        e.debugAddMonster("dog", "pies", x = 0.9f, y = 0.5f, entryId = "w1")
        e.debugAddMonster("cat", "kot", x = 0.8f, y = 0.3f, entryId = "w2")
        e.setTyped("okno")   // odpowiedz na slowo, ktorego na planszy w ogole nie ma
        assertTrue(e.submit() is SubmitResult.Miss)
        assertTrue(
            "pomylka dopisana do zgadnietego slowa cofnelaby pudelko komus, kto to slowo umial",
            e.takeLearningEvents().isEmpty(),
        )
    }

    @Test
    fun literowkaPrzyJednymHasle_jestZapisanaJakoPomylka() {
        val e = silnik()
        e.debugAddMonster("dog", "pies", x = 0.9f, y = 0.5f, entryId = "w1")
        e.debugAddMonster("cat", "kot", x = 0.8f, y = 0.3f, entryId = "w2")
        e.setTyped("piez")
        assertTrue(e.submit() is SubmitResult.Miss)

        val z = e.takeLearningEvents().single()
        assertEquals("w1", z.entryId)
        assertEquals(Outcome.BLEDNA, z.outcome)
    }

    /** Wpis bez identyfikatora (sprzed wersjonowania) nie ma gdzie trzymac postepu. */
    @Test
    fun wpisBezIdentyfikatora_nieTrafiaDoHarmonogramu() {
        val e = silnik()
        e.debugAddMonster("dog", "pies", x = 0.9f, y = 0.5f)
        e.setTyped("pies")
        assertTrue(e.submit() is SubmitResult.Fired)
        assertTrue(e.takeLearningEvents().isEmpty())
    }

    /**
     * Kierunek LOSOWY jest ustawieniem, nie kierunkiem. Silnik losuje go dla kazdego wiersza
     * osobno, wiec do harmonogramu musi trafic kierunek TEGO wiersza - inaczej Srs.key
     * odrzucilby zapis, a polowa nauki wyladowalaby pod kluczem, ktorego nikt nie czyta.
     */
    @Test
    fun kierunekLosowy_zapisujeSieJakoKierunekWiersza() {
        val e = GameEngine(
            set = zestaw(),
            config = GameConfig(direction = Direction.RANDOM, autoSpawn = false),
            random = Random(3),
        )
        val terminy = slowa.map { it.first }.toSet()
        val sensy = slowa.map { it.second }.toSet()

        repeat(slowa.size) {
            e.spawnMonster()
            val m = e.snapshot().monsters.last()
            assertNotEquals("RANDOM nie jest kierunkiem", Direction.RANDOM, m.direction)
            if (m.direction == Direction.TERM_TO_DEF) {
                assertTrue("przy TERM_TO_DEF pytamy o strone angielska: " + m.prompt, m.prompt in terminy)
            } else {
                assertTrue("przy DEF_TO_TERM pytamy o strone polska: " + m.prompt, m.prompt in sensy)
            }
            e.setTyped(m.answers.first())
            e.submit()
        }

        val z = e.takeLearningEvents()
        assertEquals(slowa.size, z.size)
        // Klucz postepu musi dac sie zlozyc dla KAZDEJ proby - to jest cala pointa tego testu.
        for (p in z) Srs.key(p.entryId, p.direction)
    }
}
