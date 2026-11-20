package com.matura.app

import com.matura.app.data.BuiltInSets
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
        spokojna: Boolean = false,
        skonczona: Boolean = false,
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
            calm = spokojna,
            finite = skonczona,
        ),
        random = Random(ziarno),
    )

    /** Kolejne hasla, ktore silnik wystawil na plansze. */
    private fun kolejnosc(e: GameEngine, ile: Int): List<String> =
        (1..ile).map { e.spawnMonster(); e.snapshot().monsters.last().prompt }

    /**
     * Wystawia jedno haslo, odpowiada na nie i czeka, az pocisk doleci. Plansza jest potem znow
     * pusta, wiec kolejny dobor zalezy tylko od kolejki, a nie od tego, co akurat stoi na torach.
     */
    private fun wystawIZalicz(e: GameEngine): String {
        e.spawnMonster()
        val m = e.snapshot().monsters.single()
        var kroki = 0
        while (e.snapshot().monsters.isNotEmpty() && kroki++ < 2000) {
            // Od trzeciego poziomu potworek bywa opancerzony i pierwszy strzal zdejmuje tylko
            // pancerz, wiec odpowiadamy do skutku: za kazdym razem, gdy nic juz do niego nie leci.
            if (e.snapshot().projectiles.isEmpty()) {
                e.setTyped(m.answers.first())
                assertTrue("wlasna odpowiedz potworka musi trafic: " + m.prompt, e.submit() is SubmitResult.Fired)
            }
            e.tick(0.016f)
        }
        assertTrue("potworek mial zejsc z planszy", e.snapshot().monsters.isEmpty())
        return m.prompt
    }

    /**
     * Wystawia jedno haslo i pozwala mu dojsc do dziala bez odpowiedzi. Kroki po pol sekundy:
     * potworek pokonuje w takim kroku duzo mniej niz promien dziala, wiec go nie przeskoczy.
     */
    private fun wystawIPrzepusc(e: GameEngine): String {
        e.spawnMonster()
        val m = e.snapshot().monsters.single()
        var kroki = 0
        while (e.snapshot().monsters.isNotEmpty() && kroki++ < 200) e.tick(0.5f)
        assertTrue("potworek mial dojsc do dziala", e.snapshot().monsters.isEmpty())
        return m.prompt
    }

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

    /**
     * SLOWO, NA KTORYM UCZEN POTKNAL SIE PRZY PIERWSZYM KONTAKCIE, WRACA JAKO PIERWSZE.
     *
     * Po pomylce na swiezym slowie Srs.apply zostawia je w pudelku 0 (pomylka to nie awans),
     * ale zapisuje pomylke, dzien pomocy i termin na dzis. Plan pytal dotad o pudelko
     * (box == NO_BOX znaczylo "nowe"), wiec takie slowo wracalo do puli NOWYCH: stalo w niej
     * w przypadkowym miejscu miedzy haslami, ktorych uczen nigdy nie widzial, a przy limicie
     * mniejszym od liczby nowych slow potrafilo wypasc poza limit, na sam koniec planu.
     *
     * NA STAREJ REGULE TEN TEST PADA: o miejscu slowa decydowalo wtedy tasowanie, wiec dla
     * dwunastu ziaren pierwsze wychodzilo kilka roznych hasel, a nie dwanascie razy "river".
     */
    @Test
    fun slowoZPomylkaPrzyPierwszymKontakcie_wracaPierwsze() {
        // Stan dokladnie taki, jaki zostawia harmonogram, a nie zlozony recznie: gdyby Srs zaczal
        // kiedys po pomylce podnosic pudelko, ten test ma to zauwazyc, zamiast sprawdzac fikcje.
        val poPomylce = Srs.apply(
            WordProgress(),
            Attempt("w8", Direction.TERM_TO_DEF, TaskKind.PRZYPOMNIENIE, Outcome.BLEDNA),
            today = 100,
        )
        assertEquals("pomylka nie awansuje: slowo zostaje w pudelku 0", Srs.NO_BOX, poPomylce.box)
        assertEquals(1, poPomylce.mistakes)

        val pierwsze = (1..12).map { ziarno ->
            // Jedenascie pozostalych slow jest nowych, a limit to trzy: jest gdzie wypasc.
            val e = silnik(postep("w8" to poPomylce), dzis = 100, ziarno = ziarno, nowychNaRunde = 3)
            kolejnosc(e, 1).single()
        }
        assertEquals(List(12) { "river" }, pierwsze)
    }

    /**
     * To samo dla slowa, ktore przy pierwszym kontakcie doszlo do dziala. POKAZANA nie liczy
     * pomylki i nie rusza terminu, wiec taki wpis rozni sie od pustego tylko dniem pomocy.
     * Dla starej reguly bylo to dalej "nowe slowo", chociaz uczen widzial juz jego tlumaczenie
     * na planszy i wlasnie teraz jest pora, zeby sprawdzic, czy cos z tego zostalo.
     */
    @Test
    fun slowoPokazanePrzyPierwszymKontakcie_wracaPrzedNowymi() {
        val poPokazaniu = Srs.apply(
            WordProgress(),
            Attempt("w3", Direction.TERM_TO_DEF, TaskKind.PRZYPOMNIENIE, Outcome.POKAZANA),
            today = 99,
        )
        assertEquals(Srs.NO_BOX, poPokazaniu.box)

        val pierwsze = (1..12).map { ziarno ->
            val e = silnik(postep("w3" to poPokazaniu), dzis = 100, ziarno = ziarno, nowychNaRunde = 3)
            kolejnosc(e, 1).single()
        }
        assertEquals(List(12) { "house" }, pierwsze)
    }

    /**
     * Plan jest kolejka CYKLICZNA: po przejsciu calej puli slowa wracaja w tej samej kolejnosci.
     * Worek z gry o rekord tasuje sie przy kazdym obiegu od nowa, wiec ten test pilnuje, zeby
     * runda z historia nie zaczela z niego korzystac.
     */
    @Test
    fun zHistoria_drugiObiegIdzieWTejSamejKolejnosci() {
        val e = silnik(postep("w5" to WordProgress(box = 1, dueDay = 0)), dzis = 100, ile = 6)
        val wyszly = (1..12).map { wystawIZalicz(e) }
        assertEquals("table", wyszly[0])
        assertEquals("caly pierwszy obieg to cala pula", 6, wyszly.take(6).toSet().size)
        assertEquals("drugi obieg powtarza pierwszy", wyszly.take(6), wyszly.drop(6))
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

    /**
     * W grze o rekord slowa wychodza z przetasowanego WORKA, bez zwracania: kazde haslo pojawia
     * sie raz, zanim ktorekolwiek pojawi sie drugi raz.
     *
     * Losowanie ze zwracaniem potrafilo pokazac jedno haslo trzy razy, zanim inne wyszlo raz,
     * a gra o rekord to PIERWSZA runda kazdego nowego gracza (nie ma jeszcze zadnej historii),
     * czyli dokladnie ta, w ktorej kazde slowo powinno dostac swoja szanse.
     *
     * Ziaren jest wiele celowo. Przy jednym rowny rozklad moglby byc przypadkiem, a regula
     * o styku dwoch workow (nowy nie zaczyna sie haslem, ktorym skonczyl sie poprzedni) bez
     * osobnego pilnowania lamie sie srednio co piaty raz, wiec jedno ziarno by jej nie zlapalo.
     */
    @Test
    fun bezHistorii_kazdeSlowoWychodziRaz_zanimKtorekolwiekWyjdzieDrugiRaz() {
        val wszystkie = slowa.take(5).map { it.first }.toSet()
        for (ziarno in 1..40) {
            val e = silnik(ziarno = ziarno, ile = 5)
            val wyszly = (1..15).map { wystawIZalicz(e) }
            val opis = "ziarno " + ziarno + ": " + wyszly
            assertEquals("pierwszy worek to caly zestaw, " + opis, wszystkie, wyszly.subList(0, 5).toSet())
            assertEquals("drugi worek tez, " + opis, wszystkie, wyszly.subList(5, 10).toSet())
            assertEquals("i trzeci, " + opis, wszystkie, wyszly.subList(10, 15).toSet())
            assertNotEquals("styk pierwszego i drugiego worka, " + opis, wyszly[4], wyszly[5])
            assertNotEquals("styk drugiego i trzeciego worka, " + opis, wyszly[9], wyszly[10])
        }
    }

    /** Worek tasuje sie przy kazdym obiegu od nowa: to nadal gra o rekord, a nie stala lista. */
    @Test
    fun bezHistorii_kolejnyWorekMaInnaKolejnosc() {
        val rozne = (1..12).count { ziarno ->
            val e = silnik(ziarno = ziarno)
            val wyszly = (1..2 * slowa.size).map { wystawIZalicz(e) }
            wyszly.take(slowa.size) != wyszly.drop(slowa.size)
        }
        assertEquals("dwa worki z dwunastu slow nie maja prawa wyjsc w tej samej kolejnosci", 12, rozne)
    }

    /**
     * Haslo stojace na planszy nie wychodzi drugi raz, dopoki jest cokolwiek innego do wystawienia.
     * Nie przepada tez i nie wraca natychmiast: czeka na koncu worka, wiec gdy wreszcie zejdzie
     * z planszy, najpierw wychodza slowa, ktore czekaly dluzej, a ono zamyka obieg.
     *
     * Bez tego slowo, na ktore uczen dopiero co odpowiedzial, potrafilo wrocic w nastepnej fali:
     * nowy worek zaczyna sie zwykle wtedy, gdy ostatnie potworki poprzedniego jeszcze ida.
     */
    @Test
    fun bezHistorii_hasloStojaceNaPlanszyCzekaNaKoncuWorka() {
        for (ziarno in 1..20) {
            val e = silnik(ziarno = ziarno, ile = 4)
            // Pierwsze haslo ZOSTAJE na planszy przez caly pierwszy worek, caly drugi
            // i poczatek trzeciego.
            e.spawnMonster()
            val stojace = e.snapshot().monsters.single()
            val wyszly = ArrayList<String>()
            repeat(7) {
                e.spawnMonster()
                val m = e.snapshot().monsters.last()
                wyszly.add(m.prompt)
                e.setTyped(m.answers.first())
                assertTrue(e.submit() is SubmitResult.Fired)
                var kroki = 0
                while (e.snapshot().monsters.size > 1 && kroki++ < 400) e.tick(0.016f)
            }
            val opis = "ziarno " + ziarno + ": " + stojace.prompt + " stoi, wyszly " + wyszly
            assertTrue("haslo z planszy nie moze stanac na niej drugi raz, " + opis, stojace.prompt !in wyszly)
            assertEquals("reszta pierwszego worka, " + opis, 3, wyszly.subList(0, 3).toSet().size)
            assertEquals("drugi worek bez stojacego hasla, " + opis, 3, wyszly.subList(3, 6).toSet().size)
            // W drugim worku zostalo juz tylko haslo stojace na planszy, wiec siodme wyjscie
            // zaczyna trzeci worek, a ten nie moze zaczac sie haslem sprzed chwili.
            assertNotEquals("styk drugiego i trzeciego worka, " + opis, wyszly[5], wyszly[6])

            // Schodzi z planszy. W trzecim worku czekaja jeszcze dwa inne slowa i to one ida
            // pierwsze; slowo, ktore stalo, zamyka obieg.
            e.setTyped(stojace.answers.first())
            assertTrue(e.submit() is SubmitResult.Fired)
            var kroki = 0
            while (e.snapshot().monsters.isNotEmpty() && kroki++ < 4000) e.tick(0.016f)
            val reszta = (1..3).map { wystawIZalicz(e) }
            assertEquals("zamyka obieg: " + reszta + ", " + opis, stojace.prompt, reszta[2])
            assertEquals("trzeci worek to znow caly zestaw, " + opis, 4, (reszta + wyszly[6]).toSet().size)
            // Wyszlo z worka dokladnie raz: czwarty worek zaczyna sie juz innym haslem.
            assertNotEquals("styk trzeciego i czwartego worka, " + opis, stojace.prompt, wystawIZalicz(e))
        }
    }

    /**
     * Styk dwoch workow liczy sie od pierwszego hasla, ktore NAPRAWDE wyjdzie. Haslo stojace
     * na planszy wyjsc nie moze, wiec o poczatku nowego worka rozstrzyga sie tylko miedzy
     * haslami spoza planszy. Regula pilnujaca samej glowy przetasowanej listy przepuscilaby
     * haslo sprzed chwili drugi raz pod rzad za kazdym razem, gdy na glowie lezaloby to stojace.
     */
    @Test
    fun bezHistorii_stykWorkowLiczySieOdPierwszegoWolnegoHasla() {
        for (ziarno in 1..40) {
            val e = silnik(ziarno = ziarno, ile = 3)
            e.spawnMonster()
            val stojace = e.snapshot().monsters.single()
            // Dwa pozostale hasla schodza z planszy; drugie z nich konczy pierwszy worek.
            val zaliczone = (1..2).map {
                e.spawnMonster()
                val m = e.snapshot().monsters.last()
                e.setTyped(m.answers.first())
                assertTrue(e.submit() is SubmitResult.Fired)
                var kroki = 0
                while (e.snapshot().monsters.size > 1 && kroki++ < 400) e.tick(0.016f)
                m.prompt
            }
            // Nowy worek: jedno haslo stoi na planszy, jedno wyszlo przed chwila, zostaje trzecie.
            e.spawnMonster()
            assertEquals(
                "ziarno " + ziarno + ": stoi " + stojace.prompt + ", wyszly " + zaliczone,
                zaliczone[0], e.snapshot().monsters.last().prompt,
            )
        }
    }

    /** Zestaw mniejszy niz plansza dalej wystawia cele, tak jak przed workiem. */
    @Test
    fun bezHistorii_pulaMniejszaNizPlansza_dalejWystawiaCele() {
        val e = silnik(ile = 2)
        val wyszly = kolejnosc(e, GameEngine.MAX_MONSTERS + 3)
        // Liczymy potworki na planszy, a nie dlugosc listy: lista ma zawsze tyle pozycji,
        // o ile poprosil test, takze wtedy, gdy silnik niczego by nie wystawil.
        assertEquals(
            "gra nie moze przestac wystawiac celow",
            GameEngine.MAX_MONSTERS + 3, e.snapshot().monsters.size,
        )
        assertNotEquals("dopoki jest wybor, sasiedzi maja byc rozni", wyszly[0], wyszly[1])
        assertEquals(setOf("dog", "cat"), wyszly.toSet())
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

    /**
     * Dwa wiersze o tym samym hasle przyjmuja nawzajem swoje odpowiedzi ("reka" to i arm, i hand),
     * ale proba idzie do wpisu, z ktorego powstal potworek. Gra nie wie, o ktorym z dwoch slow
     * uczen myslal. Wie, na ktore haslo odpowiedzial, i zapisuje tylko to, czego byla swiadkiem.
     */
    @Test
    fun wspolneHaslo_probaIdzieDoWpisuPotworka() {
        val e = GameEngine(
            set = WordSet(
                id = "t",
                title = "test",
                entries = listOf(Entry("arm", "ręka", "w1"), Entry("hand", "ręka", "w2")),
            ),
            config = GameConfig(direction = Direction.DEF_TO_TERM, autoSpawn = false),
            random = Random(2),
        )
        val slowoDrugiegoWiersza = mapOf("w1" to "hand", "w2" to "arm")
        val zapisane = (1..2).map {
            e.spawnMonster()
            val m = e.snapshot().monsters.single()
            assertEquals("ręka", m.prompt)

            // Literowka w slowie drugiego wiersza tez jest pomylka przy TYM hasle, nie przy tamtym.
            e.setTyped(slowoDrugiegoWiersza.getValue(m.entryId) + "x")
            assertTrue(e.submit() is SubmitResult.Miss)
            val pomylka = e.takeLearningEvents().single()
            assertEquals(m.entryId, pomylka.entryId)
            assertEquals(Outcome.BLEDNA, pomylka.outcome)

            e.setTyped(slowoDrugiegoWiersza.getValue(m.entryId))
            assertTrue("slowo drugiego wiersza jest poprawna odpowiedzia", e.submit() is SubmitResult.Fired)
            val z = e.takeLearningEvents().single()
            assertEquals("proba nalezy do wpisu potworka, a nie do wpisanego slowa", m.entryId, z.entryId)
            assertEquals(Outcome.POPRAWNA, z.outcome)
            assertEquals(Direction.DEF_TO_TERM, z.direction)

            var kroki = 0
            while (e.snapshot().monsters.isNotEmpty() && kroki++ < 400) e.tick(0.016f)
            z.entryId
        }
        assertEquals("oba wiersze byly na planszy", setOf("w1", "w2"), zapisane.toSet())
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

    // ---- E. runda spokojna i runda skonczona ------------------------------

    /**
     * Runda spokojna nie zabiera zycia, ale harmonogram dostaje to samo co zawsze: POKAZANA
     * przy wpisie potworka. Spokoj dotyczy kary w grze, a nie tego, co gra wie o uczniu.
     * Slowo, ktore przeszlo bez odpowiedzi, ma tego dnia nie awansowac takze tutaj: uczen
     * zobaczy jego tlumaczenie na planszy, wiec poprawna odpowiedz chwile pozniej jest
     * przepisaniem, a nie dowodem pamieci.
     */
    @Test
    fun rundaSpokojna_potworekPrzyDzialeDalejZapisujePokazana() {
        val e = silnik(zycia = 1, spokojna = true)
        e.debugAddMonster(
            "dog", "pies",
            x = GameEngine.CANNON_X + 0.061f, y = GameEngine.CANNON_Y,
            entryId = "w1",
        )
        repeat(200) { e.tick(0.05f) }
        assertTrue("test ma sens tylko, gdy potworek naprawde doszedl", e.snapshot().monsters.isEmpty())
        assertFalse("spokojna runda nie konczy sie przegrana", e.gameOver)
        assertEquals("i nie zabiera zycia", 1, e.lives)

        val z = e.takeLearningEvents().single()
        assertEquals("w1", z.entryId)
        assertEquals(Outcome.POKAZANA, z.outcome)

        // Skutek w harmonogramie: dzien z pomoca jest odnotowany, wiec odpowiedz przepisana
        // z planszy tego samego dnia zostawia slowo tam, gdzie bylo.
        val przed = WordProgress(box = 1, dueDay = 100)
        val poPokazaniu = Srs.apply(
            przed,
            Attempt("w1", Direction.TERM_TO_DEF, TaskKind.PRZYPOMNIENIE, z.outcome),
            today = 100,
        )
        assertEquals(100, poPokazaniu.assistedDay)
        val poPrzepisaniu = Srs.apply(
            poPokazaniu,
            Attempt("w1", Direction.TERM_TO_DEF, TaskKind.PRZYPOMNIENIE, Outcome.POPRAWNA),
            today = 100,
        )
        assertEquals("przepisanie odpowiedzi tego samego dnia nie jest awansem", 1, poPrzepisaniu.box)
        assertEquals("i nie odsuwa terminu", 100, poPrzepisaniu.dueDay)
    }

    /**
     * Runda skonczona bez historii: worek wydaje kazde slowo raz i runda zamyka sie sama.
     * Zadne haslo nie wychodzi drugi raz, bo slowo z poprawna odpowiedzia nie ma po co wracac.
     */
    @Test
    fun rundaSkonczona_bezHistorii_kazdeSlowoRazIKoniec() {
        for (ziarno in 1..10) {
            val e = silnik(ziarno = ziarno, skonczona = true)
            assertFalse("to dalej gra bez planu", e.plannedLearning)
            val wyszly = ArrayList<String>()
            while (!e.gameOver && wyszly.size < 50) wyszly.add(wystawIZalicz(e))

            val opis = "ziarno " + ziarno + ": " + wyszly
            assertEquals(opis, slowa.map { it.first }.toSet(), wyszly.toSet())
            assertEquals("zadne slowo nie wyszlo dwa razy, " + opis, slowa.size, wyszly.size)
            assertTrue(e.completed)
            assertEquals(slowa.size, e.snapshot().targetsDone)
            assertEquals(slowa.size, e.snapshot().targetsTotal)
        }
    }

    /**
     * Kierunek losowy niczego tu nie zmienia: kazdy wiersz dostaje na cala runde jeden kierunek,
     * wiec jest jednym slowem do zaliczenia, a nie dwoma (po jednym na kazda strone).
     */
    @Test
    fun rundaSkonczona_kierunekLosowy_kazdyWierszJestJednymSlowem() {
        val e = GameEngine(
            set = zestaw(),
            config = GameConfig(direction = Direction.RANDOM, autoSpawn = false, finite = true),
            random = Random(3),
        )
        assertEquals(slowa.size, e.snapshot().targetsTotal)
        val wyszly = ArrayList<String>()
        while (!e.gameOver && wyszly.size < 50) wyszly.add(wystawIZalicz(e))

        assertEquals("kazdy wiersz raz: " + wyszly, slowa.size, wyszly.size)
        assertEquals(slowa.size, wyszly.toSet().size)
        assertTrue(e.completed)
    }

    /**
     * Runda skonczona z historia idzie kolejnoscia planu, lacznie z nowymi slowami ponad limit.
     * W rundzie bez konca czekaja one na "bardzo dluga runde"; tutaj sa czescia zadania, wiec
     * wychodza na koncu, ale wychodza.
     */
    @Test
    fun rundaSkonczona_zHistoria_idzieKolejnosciaPlanu() {
        val historia = postep(
            "w5" to WordProgress(box = 2, dueDay = 90),
            "w9" to WordProgress(box = 1, dueDay = 100, mistakes = 3),
            "w2" to WordProgress(box = 3, dueDay = 200),
        )
        // Ten sam zestaw, ta sama historia i to samo ziarno w rundzie bez konca: jej pierwszy
        // obieg to po prostu plan.
        val plan = kolejnosc(silnik(historia, dzis = 100, ziarno = 4, nowychNaRunde = 3), slowa.size)
        assertEquals(slowa.size, plan.toSet().size)

        val e = silnik(historia, dzis = 100, ziarno = 4, nowychNaRunde = 3, skonczona = true)
        val wyszly = ArrayList<String>()
        while (!e.gameOver && wyszly.size < 50) wyszly.add(wystawIZalicz(e))

        assertEquals(plan, wyszly)
        assertEquals("najpilniejsza zaleglosc dalej pierwsza", "mountain", wyszly[0])
        assertTrue(e.completed)
    }

    /**
     * SLOWO, KTORE DOSZLO DO DZIALA, WRACA; slowo z poprawna odpowiedzia nie wraca.
     *
     * W obu kolejkach (worek i plan) wraca dopiero po reszcie obiegu, a nie natychmiast: uczen
     * ma najpierw przeczytac odpowiedz na planszy i zajac sie czyms innym, zanim dostanie to
     * samo haslo drugi raz.
     */
    @Test
    fun rundaSkonczona_slowoPrzepuszczoneWracaPoReszcieKolejki() {
        val bezHistorii = emptyMap<String, WordProgress>()
        val zHistoria = postep("w1" to WordProgress(box = 1, dueDay = 0))
        for (historia in listOf(bezHistorii, zHistoria)) {
            // Jedno zycie i runda spokojna: potworek przy dziale nie ma prawa jej skonczyc.
            val e = silnik(historia, dzis = 100, ile = 5, zycia = 1, spokojna = true, skonczona = true)
            val przepuszczone = wystawIPrzepusc(e)
            assertEquals("nic jeszcze nie ma odpowiedzi", 0, e.snapshot().targetsDone)

            val reszta = (1..4).map { wystawIZalicz(e) }
            assertEquals("cztery pozostale slowa, kazde raz: " + reszta, 4, reszta.toSet().size)
            assertFalse("przepuszczone slowo czeka na koniec kolejki: " + reszta, przepuszczone in reszta)
            assertEquals(4, e.snapshot().targetsDone)
            assertFalse("jedno slowo dalej czeka, wiec runda trwa", e.gameOver)

            assertEquals("teraz wraca", przepuszczone, wystawIPrzepusc(e))
            assertEquals("i jeszcze raz, bo dalej nie ma odpowiedzi", przepuszczone, wystawIZalicz(e))
            assertTrue(e.completed)
            assertEquals(5, e.snapshot().targetsDone)
            assertEquals(listOf(przepuszczone), e.missedWords.map { it.prompt })
            assertEquals(2, e.missedWords.single().times)
        }
    }

    /**
     * Gdy wszystko, co jeszcze czeka, stoi juz na planszy, runda skonczona nie wystawia nic.
     * Runda bez konca powtorzylaby w tym miejscu ktores haslo, zeby nie zostawiac pustego toru;
     * tutaj byloby to slowo juz zaliczone albo drugi egzemplarz tego, nad ktorym uczen mysli.
     */
    @Test
    fun rundaSkonczona_nicNieWychodzi_gdyWszystkoCzekajaceStoiNaPlanszy() {
        val bezHistorii = emptyMap<String, WordProgress>()
        val zHistoria = postep("w1" to WordProgress(box = 1, dueDay = 0))
        for (historia in listOf(bezHistorii, zHistoria)) {
            val e = silnik(historia, dzis = 100, ile = 4, spokojna = true, skonczona = true)
            repeat(9) { e.spawnMonster() }
            val naPlanszy = e.snapshot().monsters.map { it.prompt }
            assertEquals("cztery slowa i ani jednego powtorzenia: " + naPlanszy, 4, naPlanszy.size)
            assertEquals(4, naPlanszy.toSet().size)

            // Jedno slowo zaliczone, trzy stoja dalej: zaliczone nie wraca, wiec znow nic nie wychodzi.
            val pierwszy = e.snapshot().monsters.first()
            e.setTyped(pierwszy.answers.first())
            assertTrue(e.submit() is SubmitResult.Fired)
            var kroki = 0
            while (e.snapshot().monsters.size > 3 && kroki++ < 400) e.tick(0.016f)
            repeat(5) { e.spawnMonster() }
            assertEquals(3, e.snapshot().monsters.size)
            assertFalse(pierwszy.prompt in e.snapshot().monsters.map { it.prompt })
            assertFalse(e.gameOver)
        }
    }

    /**
     * Nowy worek rundy skonczonej tasuje sie tylko ze slow, ktore jeszcze czekaja, i tak jak
     * kazdy worek nie zaczyna sie haslem, ktorym skonczyl sie poprzedni.
     */
    @Test
    fun rundaSkonczona_nowyWorekMaTylkoSlowaCzekajace() {
        for (ziarno in 1..20) {
            val e = silnik(ziarno = ziarno, ile = 6, spokojna = true, skonczona = true)
            val przepuszczone = HashSet<String>()
            repeat(3) {
                przepuszczone.add(wystawIPrzepusc(e))
                wystawIZalicz(e)
            }
            assertEquals(3, e.snapshot().targetsDone)

            val drugiWorek = (1..3).map { wystawIPrzepusc(e) }
            val opis = "ziarno " + ziarno + ": czekaja " + przepuszczone + ", wyszly " + drugiWorek
            assertEquals("drugi worek to trzy czekajace slowa, " + opis, przepuszczone, drugiWorek.toSet())

            val trzeciWorek = (1..3).map { wystawIZalicz(e) }
            assertEquals("trzeci tez, " + opis, przepuszczone, trzeciWorek.toSet())
            assertNotEquals("styk drugiego i trzeciego worka, " + opis, drugiWorek.last(), trzeciWorek.first())
            assertTrue(e.completed)
        }
    }

    /**
     * Potworek, do ktorego juz leci pocisk, jest odpowiedziany takze dla rundy skonczonej:
     * jego slowo jest zaliczone i nie wraca, chociaz sam doszedl do dziala.
     */
    @Test
    fun rundaSkonczona_potworekZLecacymPociskiem_jestZaliczony() {
        val e = silnik(ile = 2, spokojna = true, skonczona = true)
        e.debugAddMonster(
            "dog", "pies",
            x = GameEngine.CANNON_X + 0.0601f, y = GameEngine.CANNON_Y,
            entryId = "w1",
        )
        e.setTyped("pies")
        assertTrue(e.submit() is SubmitResult.Fired)
        repeat(20) { e.tick(0.001f) }
        assertTrue("potworek mial dojsc do dziala", e.snapshot().monsters.isEmpty())

        assertEquals(1, e.snapshot().targetsDone)
        assertTrue("odpowiedz padla, wiec to nie jest slowo do powtorki", e.missedWords.isEmpty())
        assertEquals("zostalo tylko drugie slowo", "cat", wystawIZalicz(e))
        assertTrue(e.completed)
    }

    /**
     * To samo na prawdziwym slowniku: kazdy wbudowany zestaw, w obu kierunkach, da sie przejsc
     * jako runda spokojna i skonczona, z falami automatycznymi.
     *
     * Kilkanascie zestawow ma pary o wspolnym polskim hasle (uprzejmy to kind i polite, reka
     * to arm i hand). To na nich widac, czy wspolne odpowiedzi, zakaz dwoch takich samych
     * napisow na planszy i liczenie slow do zaliczenia nie blokuja sie nawzajem. Limit czasu
     * jest czescia testu: runda, ktora nie umie sie skonczyc, ma byc bledem, a nie zawieszeniem.
     */
    @Test(timeout = 60_000L)
    fun rundaSkonczona_kazdyZestawWbudowanyDaSieSkonczyc() {
        for (zestaw in BuiltInSets.all()) {
            for (kierunek in listOf(Direction.TERM_TO_DEF, Direction.DEF_TO_TERM)) {
                val e = GameEngine(
                    set = zestaw,
                    config = GameConfig(direction = kierunek, calm = true, finite = true),
                    random = Random(zestaw.id.hashCode()),
                )
                val opis = zestaw.title + ", " + kierunek
                assertEquals("kazdy wiersz jest osobnym slowem do zaliczenia, " + opis, zestaw.entries.size, e.snapshot().targetsTotal)

                // Uczen, ktory zna wszystko: odpowiada na kazdego potworka, gdy tylko ten stanie.
                var klatki = 0
                while (!e.gameOver && klatki++ < 20_000) {
                    e.tick(0.05f)
                    val m = e.snapshot().monsters.firstOrNull { it.incoming == 0 } ?: continue
                    e.setTyped(m.answers.first())
                    assertTrue(opis + ": " + m.prompt, e.submit() is SubmitResult.Fired)
                }
                assertTrue("runda ma sie skonczyc sama, " + opis, e.completed)
                assertEquals("kazde slowo raz, " + opis, zestaw.entries.size, e.kills)
                assertTrue("nic nie doszlo do dziala, " + opis, e.missedWords.isEmpty())
            }
        }
    }
}
