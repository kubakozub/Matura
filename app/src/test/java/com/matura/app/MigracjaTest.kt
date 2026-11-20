package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.data.Repository
import com.matura.app.game.Matching
import com.matura.app.learn.Attempt
import com.matura.app.learn.EntryId
import com.matura.app.learn.Nagrody
import com.matura.app.learn.Outcome
import com.matura.app.learn.Srs
import com.matura.app.model.Achievement
import com.matura.app.model.Direction
import com.matura.app.model.Dorobek
import com.matura.app.model.Entry
import com.matura.app.model.Profile
import com.matura.app.model.SaveData
import com.matura.app.model.ScoreRecord
import com.matura.app.model.TaskKind
import com.matura.app.model.Texture
import com.matura.app.model.UiLanguage
import com.matura.app.model.WordProgress
import com.matura.app.model.WordSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Aktualizacja zapisu ze sklepowego 0.9.4 (vc21) do biezacego wydania.
 *
 * Sprawdzana jest przede wszystkim ta czesc, ktora latwo zepsuc bez zadnego widocznego objawu:
 * ze nic nie ginie, ze drugie uruchomienie niczego nie zmienia i ze aplikacja NIE wymysla
 * opanowania slow, ktorego uczen nigdy nie pokazal.
 *
 * DLACZEGO FIKSTURA JEST PLIKIEM, A NIE DWOMA WIERSZAMI W KODZIE: poprzednia wersja tego testu
 * miala "stary zapis" z jednym zestawem wbudowanym o dwoch wpisach, i to akurat takich, ktorych
 * klasa zmiany (korekta TLUMACZENIA) byla juz obsluzona tablica aliasow. Testy byly wiec zielone
 * przy calkowicie zepsutej sciezce aktualizacji: zapis trzyma wlasna kopie katalogu, ktorej nikt
 * nie odswiezal, wiec uczen po aktualizacji zostawal z literowka "accomodation" i z trzema
 * starymi tlumaczeniami. Widac to dopiero na PELNYM katalogu (102 zestawy, 2084 hasla), bo
 * rozbiezne sa 4 wpisy z 2084.
 *
 * SKAD FIKSTURA: app/src/test/resources/zapis-0.9.4-vc21.json to prawdziwy ksztalt pliku z
 * telefonu z wersji sklepowej, odtworzony z katalogu z commita bc4396b (0.9.3/vc20). Sam vc21
 * nie ma wlasnego commita, ale miedzy vc20 a vc24 katalog zmienil sie DOKLADNIE raz (36579fb
 * poprawil "accomodation" -> "accommodation"), wiec vc20 niesie tresc, ktora ma na dysku uczen
 * ze sklepu. Zapis nie ma pola schemaVersion, wpisy nie maja identyfikatorow, a Options nie ma
 * pola systemKeyboard (weszlo dopiero w 0.9.7/vc24) — to jest caly sens tej fikstury: ksztalt
 * sprzed wersjonowania, a nie dzisiejszy zapis z usunietymi polami.
 */
class MigracjaTest {

    @get:Rule
    val katalog = TemporaryFolder()

    private companion object {
        const val FIKSTURA = "zapis-0.9.4-vc21.json"

        /** Zestaw, w ktorym siedzi literowka sprzed 0.9.7: "Miejsce zamieszkania · Wynajem". */
        const val ZESTAW_Z_LITEROWKA = "v026"

        /**
         * Identyfikator, ktory dla pary ("accomodation", "zakwaterowanie") liczyl kod PRZED
         * dopisaniem aliasu hasla. Wartosc jest historyczna i dlatego wpisana wprost: dzisiejsze
         * EntryId.builtIn juz jej nie zwroci, a zapis testera z tamtej wersji ma ja na dysku.
         */
        const val ID_SPRZED_ALIASU = "wb9c46bd1016c"
    }

    private fun plik(nazwa: String) = File(katalog.root, nazwa)

    /** Zapis w ksztalcie 0.9.4 (vc21) — patrz KDoc klasy. */
    private val staryZapis: String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(FIKSTURA),
    ) { "brak fikstury $FIKSTURA na sciezce testow" }.use { it.readBytes().decodeToString() }

    private fun zapiszStary() = plik(Repository.FILE_NAME).writeText(staryZapis)

    private fun katalogPoId() = BuiltInSets.all().associateBy { it.id }

    @Test
    fun `fikstura naprawde jest zapisem sprzed wersjonowania, z pelnym katalogiem`() {
        // Test o tescie: gdyby fikstura zjechala do dzisiejszego ksztaltu (identyfikatory,
        // schemaVersion, biezaca tresc katalogu), wszystkie testy nizej przechodzilyby z powodu,
        // ktory nie ma nic wspolnego z aktualizacja.
        assertFalse("fikstura ma pole schemaVersion", staryZapis.contains("schemaVersion"))
        assertFalse("fikstura ma pole systemKeyboard", staryZapis.contains("systemKeyboard"))
        assertFalse("fikstura ma juz identyfikatory wpisow", staryZapis.contains("\"id\":\"w"))
        assertTrue("fikstura nie ma literowki sprzed 0.9.7", staryZapis.contains("accomodation"))

        zapiszStary()
        val surowe = Repository(katalog.root).load()
        assertEquals(BuiltInSets.all().size, surowe.sets.count { s -> s.builtIn })
        assertEquals(
            BuiltInSets.all().sumOf { s -> s.entries.size },
            surowe.sets.filter { s -> s.builtIn }.sumOf { s -> s.entries.size },
        )
    }

    // --- co zostaje ucznia ------------------------------------------------------------------

    @Test
    fun `stary zapis zachowuje profil, ustawienia, wyniki i wlasne zestawy`() {
        zapiszStary()

        val dane = Repository(katalog.root).load()

        assertEquals("Stary", dane.profile.name)
        assertEquals(12, dane.profile.gamesPlayed)
        assertEquals(140, dane.profile.totalKills)
        assertEquals(1500, dane.profile.bestScore)
        assertEquals(Texture.NIGHT, dane.options.texture)
        assertEquals(Direction.DEF_TO_TERM, dane.options.direction)
        assertEquals(UiLanguage.EN, dane.options.uiLanguage)
        assertEquals(1, dane.scores.size)
        assertEquals(1500, dane.scores.first().score)

        val wlasny = dane.sets.first { !it.builtIn }
        assertEquals("Moje słówka", wlasny.title)
        assertEquals(listOf("dog", "cat", "accommodation"), wlasny.entries.map { it.term })
        assertEquals(3, wlasny.timesPlayed)
        // Licznik rozgrywek nalezy do ucznia, a nie do katalogu - odswiezenie tresci go nie tyka.
        assertEquals(6, dane.sets.first { it.id == BuiltInSets.all().first().id }.timesPlayed)
        assertEquals(Repository.SCHEMA_VERSION, dane.schemaVersion)
    }

    @Test
    fun `pole nieznane staremu zapisowi dostaje wartosc domyslna, a nie wywala odczytu`() {
        // systemKeyboard weszlo w 0.9.7 (vc24), wiec w pliku ze sklepu go NIE MA.
        zapiszStary()

        assertFalse(Repository(katalog.root).load().options.systemKeyboard)
    }

    @Test
    fun `wpisy bez identyfikatora dostaja go wedlug pochodzenia zestawu`() {
        zapiszStary()

        val dane = Repository(katalog.root).load()

        for (set in dane.sets.filter { it.builtIn }) {
            for (e in set.entries) {
                assertTrue("'${e.term}' bez identyfikatora katalogowego", EntryId.isBuiltIn(e.id))
            }
        }
        for (e in dane.sets.first { !it.builtIn }.entries) {
            assertTrue("'${e.term}' bez identyfikatora wlasnego", EntryId.isUser(e.id))
        }
    }

    // --- (a) slownictwo jest biezace --------------------------------------------------------

    @Test
    fun `tresc zestawow wbudowanych po aktualizacji rowna sie katalogowi`() {
        // Bez tej asercji zadna przyszla korekta slownictwa nie mialaby straznika: przeszlaby
        // cicho obok wszystkich, ktorzy aktualizuja, bo zapis ma wlasna kopie katalogu.
        zapiszStary()

        val dane = Repository(katalog.root).load()

        val katalogi = katalogPoId()
        for (set in dane.sets.filter { it.builtIn }) {
            val biezacy = katalogi.getValue(set.id)
            assertEquals("tytul zestawu ${set.id}", biezacy.title, set.title)
            assertEquals("tresc zestawu ${set.id}", biezacy.entries, set.entries)
        }
    }

    @Test
    fun `literowka w hasle angielskim znika z zapisu, a poprawna pisownia jest zaliczana`() {
        zapiszStary()

        val dane = Repository(katalog.root).load()

        val wpis = dane.sets.first { it.id == ZESTAW_Z_LITEROWKA }.entries
            .first { it.definition == "zakwaterowanie" }
        assertEquals("accommodation", wpis.term)
        assertTrue(
            "literowka zostala gdziekolwiek w zapisie",
            dane.sets.flatMap { it.entries }.none { it.term == "accomodation" },
        )
        // Kierunek ZNACZENIE -> HASLO: przed poprawka uczen musial wpisac blad, zeby zaliczyc.
        assertTrue(Matching.isExactMatch("accommodation", Matching.acceptedAnswers(wpis.term)))
    }

    @Test
    fun `poprawione tlumaczenia docieraja do ucznia, ktory aktualizuje`() {
        zapiszStary()

        val dane = Repository(katalog.root).load()
        val wpisy = dane.sets.filter { it.builtIn }.flatMap { it.entries }

        for (haslo in listOf("schoolmate", "boarding school", "single ticket")) {
            val zZapisu = wpisy.first { it.term == haslo }
            val zKatalogu = BuiltInSets.all().flatMap { it.entries }.first { it.term == haslo }
            assertEquals(zKatalogu.definition, zZapisu.definition)
            // Skutek dla ucznia: poprawne znaczenie przestaje byc bledna odpowiedzia.
            assertTrue(
                "'${zKatalogu.definition}' nadal odrzucane",
                Matching.isExactMatch(
                    zKatalogu.definition.substringBefore(","),
                    Matching.acceptedAnswers(zZapisu.definition),
                ),
            )
        }
    }

    // --- (b) zestawy wlasne sa nietkniete ---------------------------------------------------

    @Test
    fun `aktualizacja nie dotyka ani jednego zestawu wlasnego`() {
        zapiszStary()

        val dane = Repository(katalog.root).load()
        val wlasny = dane.sets.first { !it.builtIn }

        assertEquals("Moje słówka", wlasny.title)
        assertEquals(
            listOf("dog" to "pies", "cat" to "kot", "accommodation" to "zakwaterowanie"),
            wlasny.entries.map { it.term to it.definition },
        )
        assertEquals(1700000000000L, wlasny.createdAt)
        // Wpis wlasny o tresci IDENTYCZNEJ z katalogowym nie moze dostac identyfikatora
        // katalogowego - inaczej dzielilby postep ze slowem, ktorego uczen nie wpisywal.
        val blizniak = wlasny.entries.first { it.term == "accommodation" }
        assertTrue(EntryId.isUser(blizniak.id))
        assertNotEquals(
            BuiltInSets.all().flatMap { it.entries }.first { it.term == "accommodation" }.id,
            blizniak.id,
        )
    }

    @Test
    fun `zestaw wbudowany, ktorego nie ma juz w katalogu, zostaje nietkniety`() {
        // Kasowanie jest nieodwracalne, a nadmiarowy zestaw nikomu nie szkodzi.
        val osierocony = WordSet(
            id = "v999",
            title = "Dzial zdjety z katalogu",
            builtIn = true,
            entries = listOf(Entry("ghost", "duch", "w0123456789ab")),
        )
        val dane = SaveData(schemaVersion = 2, sets = BuiltInSets.all() + osierocony)
        assertTrue(Repository(katalog.root).save(dane))

        val po = Repository(katalog.root).load()

        assertEquals(osierocony, po.sets.first { it.id == "v999" })
    }

    // --- (c) postep nauki nadal wskazuje te same slowa --------------------------------------

    @Test
    fun `postep spod identyfikatora sprzed korekty hasla laduje pod katalogowym`() {
        // Zapis testera ze schematu 2: wpis ma STARA tresc i policzony z niej identyfikator.
        // Odswiezenie katalogu zmienia tresc, a wraz z nia identyfikator - bez przeprowadzki
        // postepu cala nauka tego slowa zostalaby pod kluczem, ktorego zaden ekran nie odczyta.
        val zKatalogu = BuiltInSets.all().first { it.id == ZESTAW_Z_LITEROWKA }
        val stareV026 = zKatalogu.copy(
            entries = listOf(Entry("accomodation", "zakwaterowanie", ID_SPRZED_ALIASU)) +
                zKatalogu.entries.drop(1),
        )
        val klucz = Srs.key(ID_SPRZED_ALIASU, Direction.DEF_TO_TERM)
        val dane = SaveData(
            schemaVersion = 2,
            sets = BuiltInSets.all().map { if (it.id == ZESTAW_Z_LITEROWKA) stareV026 else it },
            progress = mapOf(klucz to WordProgress(box = 2, dueDay = 19_900, mistakes = 3)),
        )
        assertTrue(Repository(katalog.root).save(dane))

        val po = Repository(katalog.root).load()

        val wpis = po.sets.first { it.id == ZESTAW_Z_LITEROWKA }.entries.first()
        assertEquals("accommodation", wpis.term)
        assertEquals(zKatalogu.entries.first().id, wpis.id)
        // To jest cala stawka: pudelko, termin i liczba pomylek wskazuja na TO SAMO slowo.
        val przeniesiony = Srs.progressOf(po.progress, wpis.id, Direction.DEF_TO_TERM)
        assertEquals(2, przeniesiony.box)
        assertEquals(19_900, przeniesiony.dueDay)
        assertEquals(3, przeniesiony.mistakes)
        // Starego klucza nie kasujemy - mapy postepu nie sprzatamy nigdy (patrz SaveData).
        // Od schematu 4 kazdy wpis postepu, takze ten pod starym kluczem, ma szczyt rowny
        // co najmniej pudelku, wiec stan wraca z jednym polem wiecej niz wszedl.
        assertEquals(
            WordProgress(box = 2, dueDay = 19_900, mistakes = 3, szczyt = 2),
            po.progress[klucz],
        )
    }

    @Test
    fun `postep slowa, ktorego tresc sie nie zmienila, przezywa aktualizacje bez ruchu`() {
        val slowo = BuiltInSets.all().first().entries.first()
        val klucz = Srs.key(slowo.id, Direction.TERM_TO_DEF)
        val dane = SaveData(
            schemaVersion = 2,
            sets = BuiltInSets.all(),
            progress = mapOf(klucz to WordProgress(box = 3, dueDay = 19_950)),
        )
        assertTrue(Repository(katalog.root).save(dane))

        val po = Repository(katalog.root).load()

        assertEquals(1, po.progress.size)
        assertEquals(3, Srs.progressOf(po.progress, slowo.id, Direction.TERM_TO_DEF).box)
    }

    @Test
    fun `swiezsza historia pod identyfikatorem katalogowym wygrywa ze starsza`() {
        // Uczen cwiczyl to slowo w "Losowaniu", ktore czyta swiezy katalog, wiec pod
        // identyfikatorem katalogowym cos juz jest. Przeprowadzka nie ma prawa tego nadpisac.
        val zKatalogu = BuiltInSets.all().first { it.id == ZESTAW_Z_LITEROWKA }
        val stareV026 = zKatalogu.copy(
            entries = listOf(Entry("accomodation", "zakwaterowanie", ID_SPRZED_ALIASU)) +
                zKatalogu.entries.drop(1),
        )
        val idKatalogowe = zKatalogu.entries.first().id
        val dane = SaveData(
            schemaVersion = 2,
            sets = BuiltInSets.all().map { if (it.id == ZESTAW_Z_LITEROWKA) stareV026 else it },
            progress = mapOf(
                Srs.key(ID_SPRZED_ALIASU, Direction.TERM_TO_DEF) to WordProgress(box = 1),
                Srs.key(idKatalogowe, Direction.TERM_TO_DEF) to WordProgress(box = 3, dueDay = 19_990),
            ),
        )
        assertTrue(Repository(katalog.root).save(dane))

        val po = Repository(katalog.root).load()

        assertEquals(3, Srs.progressOf(po.progress, idKatalogowe, Direction.TERM_TO_DEF).box)
        assertEquals(19_990, Srs.progressOf(po.progress, idKatalogowe, Direction.TERM_TO_DEF).dueDay)
    }

    @Test
    fun `kazdy wpis wbudowany po aktualizacji ma identyfikator z katalogu`() {
        // Sierota (identyfikator spoza katalogu) znaczy, ze postep tego slowa liczy sie osobno
        // w dziale tematycznym i osobno w "Losowaniu" - dwie prawdy o jednym slowie.
        zapiszStary()

        val dane = Repository(katalog.root).load()

        val zKatalogu = BuiltInSets.all().flatMap { it.entries }.map { it.id }.toHashSet()
        val sieroty = dane.sets.filter { it.builtIn }
            .flatMap { it.entries }
            .filter { it.id !in zKatalogu }
        assertTrue("identyfikatory spoza katalogu: ${sieroty.map { it.term }}", sieroty.isEmpty())
    }

    // --- niezmienniki migracji --------------------------------------------------------------

    @Test
    fun `drugie uruchomienie niczego nie zmienia`() {
        zapiszStary()

        val pierwsze = Repository(katalog.root).load()
        val drugie = Repository(katalog.root).load()

        // load() celowo nie pisze po dysku, wiec migracja rusza od nowa przy kazdym starcie
        // az do pierwszego zapisu - i musi dawac dokladnie ten sam wynik.
        assertEquals(pierwsze, drugie)

        val repo = Repository(katalog.root)
        assertEquals(pierwsze, repo.migrate(pierwsze))
        assertEquals(repo.migrate(pierwsze), repo.migrate(repo.migrate(pierwsze)))

        assertTrue(repo.save(pierwsze))
        assertEquals(pierwsze, Repository(katalog.root).load())
    }

    @Test
    fun `migracja nie wymysla opanowania slow z dawnych punktow za gry`() {
        zapiszStary()

        val dane = Repository(katalog.root).load()

        assertTrue(
            "punkty za gry nie mowia, KTORE slowo uczen umie - postep ma startowac pusty",
            dane.progress.isEmpty(),
        )
        val slowo = dane.sets.first { it.builtIn }.entries.first()
        assertEquals(0, Srs.progressOf(dane.progress, slowo.id, Direction.TERM_TO_DEF).box)
    }

    @Test
    fun `postep slowa spoza biezacego katalogu przezywa zapis i odczyt`() {
        // Regula: mapy postepu nie sprzatamy. Slowo usuniete z katalogu moze do niego wrocic,
        // a przy okazji sprzatania najlatwiej skasowac cudzy wiersz.
        val nieistniejace = "wdeadbeef0000"
        val klucz = Srs.key(nieistniejace, Direction.TERM_TO_DEF)
        val dane = SaveData(
            schemaVersion = Repository.SCHEMA_VERSION,
            sets = BuiltInSets.all(),
            progress = mapOf(klucz to WordProgress(box = 3, dueDay = 19_900)),
        )

        assertTrue(Repository(katalog.root).save(dane))
        val odczytane = Repository(katalog.root).load()

        assertEquals(1, odczytane.progress.size)
        assertEquals(3, odczytane.progress.getValue(klucz).box)
    }

    @Test
    fun `postep zapisany przez recordAttempt wraca po restarcie`() {
        val repo = Repository(katalog.root)
        val slowo = BuiltInSets.all().first().entries.first()
        val chwila = 19_875L * Srs.MS_PER_DAY + 10 * 3_600_000L

        assertTrue(
            repo.recordAttempt(
                Attempt(slowo.id, Direction.TERM_TO_DEF, TaskKind.PRZYPOMNIENIE, Outcome.POPRAWNA),
                chwila,
            )
        )

        val poRestarcie = Repository(katalog.root)
        assertEquals(1, poRestarcie.progressOf(slowo.id, Direction.TERM_TO_DEF).box)
        assertEquals(0, poRestarcie.progressOf(slowo.id, Direction.DEF_TO_TERM).box)
    }

    @Test
    fun `przerwana proba nie rusza dysku`() {
        val repo = Repository(katalog.root)
        val slowo = BuiltInSets.all().first().entries.first()

        assertTrue(
            repo.recordAttempt(
                Attempt(slowo.id, Direction.TERM_TO_DEF, TaskKind.PRZYPOMNIENIE, Outcome.PRZERWANA),
                19_875L * Srs.MS_PER_DAY,
            )
        )

        assertTrue("nic sie nie zmienilo, wiec nie ma czego zapisywac", !plik(Repository.FILE_NAME).exists())
    }

    @Test
    fun `sesja z zapisu nie ginie przy aktualizacji`() {
        // Przerwana sesja jest zwyklym polem dokumentu; krok migracji dotyka zestawow i postepu,
        // wiec gdyby skladal SaveData od zera, trasa dnia przepadlaby przy pierwszym starcie.
        zapiszStary()
        val repo = Repository(katalog.root)
        val stan = com.matura.app.learn.StanSesji(dzien = 19_875, krok = 1)
        assertTrue(repo.setSesja(stan))

        assertEquals(stan, Repository(katalog.root).load().sesja)
    }

    @Test
    fun `poprawka literowki we wlasnym zestawie nie kasuje nauki`() {
        // Edytor sklada wpisy od zera, wiec bez uzgodnienia w Repository.upsertSet kazdy zapis
        // zestawu zaczynalby nauke od poczatku.
        val repo = Repository(katalog.root)
        repo.upsertSet(
            WordSet(
                id = "user-1",
                title = "Moje słówka",
                entries = listOf(Entry("dog", "pies"), Entry("cat", "kot")),
            )
        )
        val przed = repo.load().sets.first { it.id == "user-1" }.entries
        assertTrue(przed.all { EntryId.isUser(it.id) })

        // Uczen poprawia literowke w drugim wierszu; ekran oddaje wpisy BEZ identyfikatorow.
        repo.upsertSet(
            WordSet(
                id = "user-1",
                title = "Moje słówka",
                entries = listOf(Entry("dog", "pies"), Entry("cat", "kot, kotka")),
            )
        )
        val po = repo.load().sets.first { it.id == "user-1" }.entries

        assertEquals(przed.map { it.id }, po.map { it.id })
        assertEquals("kot, kotka", po[1].definition)
    }

    @Test
    fun `nowy wiersz we wlasnym zestawie dostaje wlasny identyfikator`() {
        val repo = Repository(katalog.root)
        repo.upsertSet(
            WordSet(id = "user-2", title = "Nowe", entries = listOf(Entry("dog", "pies")))
        )
        val pies = repo.load().sets.first { it.id == "user-2" }.entries.first().id

        repo.upsertSet(
            WordSet(
                id = "user-2",
                title = "Nowe",
                entries = listOf(Entry("dog", "pies"), Entry("cat", "kot")),
            )
        )
        val po = repo.load().sets.first { it.id == "user-2" }.entries

        assertEquals(pies, po[0].id)
        assertNotEquals(pies, po[1].id)
        assertTrue(EntryId.isUser(po[1].id))
    }

    // --- krok 3 -> 4: szczyty pudelek i ksiega dorobku ----------------------------------------

    @Test
    fun `zapis ze sklepu dochodzi do schematu 4 z pusta ksiega i odznakami, ktore uczen juz ma`() {
        zapiszStary()

        val dane = Repository(katalog.root).load()

        assertEquals(4, Repository.SCHEMA_VERSION)
        assertEquals(4, dane.schemaVersion)
        // Punkty za gry nie mowia nic o nauce: ksiega startuje od zera, tak samo jak postep.
        val ksiega = dane.dorobek
        assertEquals(0, ksiega.xp)
        assertEquals("zapas dni wolnych jak u nowego ucznia", 1, ksiega.wolne)
        assertTrue("odznaki sprzed ksiegi nie czekaja na ogloszenie", ksiega.odznakiNowe.isEmpty())
        assertEquals("poza odznakami ksiega ma byc nietknieta", Dorobek(odznaki = ksiega.odznaki), ksiega)
        // Profil z fikstury: 12 gier, 140 pokonanych, najlepszy wynik 1500, seria 9. Dzien zero
        // znaczy "sprzed ksiegi": te odznaki uczen ma od dawna i nikt nie zapisal, od kiedy.
        assertEquals(
            mapOf(
                "FIRST_BLOOD" to 0,
                "ROOKIE" to 0,
                "SHARPSHOOTER" to 0,
                "COMBO_X3" to 0,
                "COMBO_X5" to 0,
                "SCORE_500" to 0,
            ),
            ksiega.odznaki,
        )
        // Czyli dokladnie to, co ekran OSIAGNIECIA pokazuje temu uczniowi jako zdobyte.
        assertEquals(
            Achievement.entries.filter { it.unlockedBy(dane.profile) }.map { it.name }.toSet(),
            ksiega.odznaki.keys,
        )
    }

    @Test
    fun `nowe ustawienia dostaja wartosci domyslne w starym zapisie`() {
        // celDnia, maturaDzien i odliczanie weszly razem z ksiega; w pliku ze sklepu ich nie ma.
        zapiszStary()

        val opcje = Repository(katalog.root).load().options

        assertEquals(30, opcje.celDnia)
        assertEquals(20944, opcje.maturaDzien)
        assertTrue(opcje.odliczanie)
        // A to, co uczen ustawil sam, zostaje.
        assertEquals(5, opcje.lives)
        assertEquals(Texture.NIGHT, opcje.texture)
    }

    @Test
    fun `pierwsza gra po aktualizacji nie oglasza odznak, ktore uczen ma od miesiecy`() {
        // Po to jest ciche przyznanie w migracji: bez niego ekran wyniku pierwszej rundy
        // witalby ucznia szescioma "nowymi" odznakami za rzeczy zrobione dawno temu.
        zapiszStary()
        val repo = Repository(katalog.root)
        val przed = repo.load()
        val wynik = ScoreRecord("Stary", "Moje słówka", 100, 1, 2, 5, 1_800_000_000_000L)

        assertTrue(repo.recordGame(wynik, "user-1", nowMs = 19_875L * Srs.MS_PER_DAY))
        val po = Repository(katalog.root).load()

        assertTrue(Nagrody.roznica(przed, po, emptyList(), wynik, 19_875).odznaki.isEmpty())
        assertEquals(przed.dorobek, po.dorobek)
        assertEquals(13, po.profile.gamesPlayed)
    }

    /** Zapis ze schematu 3 z postepem w kazdym pudelku; komentarze podaja punkty na start. */
    private fun zapisZPostepem(): SaveData {
        val pierwszyZestaw = BuiltInSets.all().first().entries.map { it.id }
        // Slowo, ktore lezy w wiecej niz jednym zestawie katalogu (wspolny identyfikator).
        val wspolne = BuiltInSets.all()
            .flatMap { set -> set.entries.map { it.id }.distinct() }
            .groupingBy { it }.eachCount()
            .entries.first { it.value > 1 && it.key !in pierwszyZestaw }.key
        val wlasny = Entry("dog", "pies", "u0123456789ab")
        fun klucz(id: String, kierunek: Direction = Direction.TERM_TO_DEF) = Srs.key(id, kierunek)
        return SaveData(
            schemaVersion = 3,
            profile = Profile(name = "Ala"),
            sets = BuiltInSets.all() + WordSet(id = "user-1", title = "Moje słówka", entries = listOf(wlasny)),
            progress = mapOf(
                klucz(pierwszyZestaw[0]) to WordProgress(box = 3, dueDay = 19_900),                 // 60
                klucz(pierwszyZestaw[0], Direction.DEF_TO_TERM) to
                    WordProgress(box = 1, dueDay = 19_880, lastTask = TaskKind.PRZYPOMNIENIE),      // 10
                klucz(pierwszyZestaw[1]) to WordProgress(box = 2, dueDay = 19_890, mistakes = 2),   // 30
                klucz(pierwszyZestaw[2]) to
                    WordProgress(box = 1, dueDay = 19_880, lastTask = TaskKind.ROZPOZNANIE),        // 3
                klucz(pierwszyZestaw[3]) to WordProgress(box = 0, dueDay = 19_875, mistakes = 1),   // 0
                klucz(pierwszyZestaw[4]) to WordProgress(box = 1, dueDay = 19_880),                 // 10
                klucz(wspolne) to WordProgress(box = 2, dueDay = 19_890),                           // 30, raz
                klucz(wlasny.id) to WordProgress(box = 3, dueDay = 19_900),                         // 60
                // Slowo spoza biezacych zestawow: postep zostaje w mapie, ale nie w ksiedze.
                klucz("wdeadbeef0000") to WordProgress(box = 3, dueDay = 19_900),                   // 0
            ),
        )
    }

    @Test
    fun `zapis ze schematu 3 dostaje szczyty i punkty dokladnie za pudelka, do ktorych uczen doszedl`() {
        val dane = zapisZPostepem()
        assertTrue(Repository(katalog.root).save(dane))

        val po = Repository(katalog.root).load()

        assertEquals(4, po.schemaVersion)
        assertEquals(60 + 10 + 30 + 3 + 0 + 10 + 30 + 60, po.dorobek.xp)
        // Dni nauki, seria, powtorki i naprawione zaczynaja od zera, a zapas dni wolnych od
        // jednego: tego stary zapis nie notowal. Pierwsze poznane slowo ze slownika gry to
        // odznaka, ktora uczen ma juz dzis: wchodzi po cichu, z dniem zero i bez ogloszenia.
        // Utrwalone jest jedno slowo slownika, wiec do "5 utrwalonych" jeszcze daleko; wlasne
        // slowo i slowo spoza zestawow do odznak nie doliczaja.
        assertEquals(Dorobek(xp = 203, wolne = 1, odznaki = mapOf("SLOWA_1" to 0)), po.dorobek)
        assertTrue(po.dorobek.odznakiNowe.isEmpty())

        // Kazdy wpis postepu ma szczyt rowny pudelku, takze ten spoza zestawow; reszta bez zmian.
        assertEquals(dane.progress.keys, po.progress.keys)
        for ((klucz, stan) in dane.progress) {
            assertEquals(stan.copy(szczyt = stan.box), po.progress[klucz])
        }
    }

    @Test
    fun `drugie wczytanie zapisu ze schematu 3 daje identyczna ksiege`() {
        val dane = zapisZPostepem()
        assertTrue(Repository(katalog.root).save(dane))

        val pierwsze = Repository(katalog.root).load()
        val drugie = Repository(katalog.root).load()

        // load() nie pisze po dysku, wiec krok biegnie od nowa przy kazdym starcie.
        assertEquals(pierwsze, drugie)
        val repo = Repository(katalog.root)
        assertEquals(pierwsze, repo.migrate(dane))
        assertEquals(pierwsze, repo.migrate(pierwsze))

        // Po pierwszym zapisie plik ma schemat 4 i ksiege: kolejny start niczego nie dolicza.
        assertTrue(repo.save(pierwsze))
        assertEquals(pierwsze, Repository(katalog.root).load())
        assertEquals(203, Repository(katalog.root).load().dorobek.xp)
    }

    @Test
    fun `ksiega, ktora ma juz punkty, nie jest zakladana drugi raz`() {
        // Plik ze schematem ponizej 4, ale z ksiega: wyliczenie z pudelek nadpisaloby punkty
        // zarobione nauka, a odznaki czekajace na prawdziwy dzien dostalyby stempel zero.
        val slowo = BuiltInSets.all().first().entries.first()
        val klucz = Srs.key(slowo.id, Direction.TERM_TO_DEF)
        val ksiega = Dorobek(
            xp = 7,
            dzien = 19_870,
            xpDzis = 5,
            noweDzis = 1,
            rozpoznaneDzis = 1,
            wlasneDzis = 2,
            seria = 2,
            seriaDzien = 19_870,
            wolne = 0,
            rekordSerii = 4,
            dniNauki = 9,
            powtorki = 3,
            naprawione = 1,
            odznaki = mapOf("SERIA_3" to 19_800),
            odznakiNowe = setOf("SERIA_3"),
        )
        val dane = SaveData(
            schemaVersion = 3,
            // Profil spelnia wszystkie osiem dawnych osiagniec, a pudelko 3 daloby 60 punktow.
            profile = Profile(gamesPlayed = 30, totalKills = 500, bestScore = 2500, bestStreak = 10),
            sets = BuiltInSets.all(),
            progress = mapOf(klucz to WordProgress(box = 3, dueDay = 19_900)),
            dorobek = ksiega,
        )
        assertTrue(Repository(katalog.root).save(dane))

        val po = Repository(katalog.root).load()

        assertEquals(4, po.schemaVersion)
        assertEquals(ksiega, po.dorobek)
        // Szczyt jest wyrownywany zawsze: to nie ksiega, tylko stan slowa.
        assertEquals(3, po.progress.getValue(klucz).szczyt)
    }

    @Test
    fun `slowo przeprowadzone pod katalogowy identyfikator wchodzi do ksiegi raz`() {
        // Zapis ze schematu 2 przechodzi w jednym starcie przez OBA kroki, w tej kolejnosci:
        // najpierw odswiezenie katalogu (przeprowadzka postepu pod biezacy identyfikator),
        // potem ksiega. Po korekcie katalogu ten sam postep lezy pod starym i pod nowym kluczem
        // (mapy nie sprzatamy). Ksiega liczona po mapie dalaby za jedno slowo podwojne punkty,
        // a zalozona przed odswiezeniem nie znalazlaby tego slowa w zestawach wcale.
        val zKatalogu = BuiltInSets.all().first { it.id == ZESTAW_Z_LITEROWKA }
        val stareV026 = zKatalogu.copy(
            entries = listOf(Entry("accomodation", "zakwaterowanie", ID_SPRZED_ALIASU)) +
                zKatalogu.entries.drop(1),
        )
        val dane = SaveData(
            schemaVersion = 2,
            sets = BuiltInSets.all().map { if (it.id == ZESTAW_Z_LITEROWKA) stareV026 else it },
            progress = mapOf(
                Srs.key(ID_SPRZED_ALIASU, Direction.DEF_TO_TERM) to WordProgress(box = 2, dueDay = 19_900),
            ),
        )
        assertTrue(Repository(katalog.root).save(dane))

        val po = Repository(katalog.root).load()

        assertEquals("postep lezy pod oboma kluczami", 2, po.progress.size)
        assertEquals("ale slowo jest jedno", 30, po.dorobek.xp)
        // Odznaka liczy sie po biezacym slowniku gry, wiec przeprowadzone slowo ja daje.
        assertEquals(mapOf("SLOWA_1" to 0), po.dorobek.odznaki)
    }

    @Test
    fun `odznaki w migracji licza sie ze slownika gry, a nie z kopii zestawu w zapisie`() {
        // Uczen skrocil w edytorze kopie zestawu wbudowanego do jednego slowa i to slowo ma
        // utrwalone. Punkty na start dostaje (nauka jest nauka), ale "pierwszy zestaw" nie:
        // w slowniku z aplikacji ten zestaw ma dalej wszystkie swoje slowa.
        val pierwszy = BuiltInSets.all().first()
        val skrocony = pierwszy.copy(entries = pierwszy.entries.take(1))
        val dane = SaveData(
            schemaVersion = 3,
            sets = listOf(skrocony) + BuiltInSets.all().drop(1),
            progress = mapOf(
                Srs.key(pierwszy.entries.first().id, Direction.TERM_TO_DEF) to WordProgress(box = 3, dueDay = 19_900),
            ),
        )
        assertTrue(Repository(katalog.root).save(dane))

        val po = Repository(katalog.root).load()

        assertEquals(60, po.dorobek.xp)
        assertEquals(mapOf("SLOWA_1" to 0), po.dorobek.odznaki)
    }
}
