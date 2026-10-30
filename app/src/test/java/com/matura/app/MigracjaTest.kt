package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.data.Repository
import com.matura.app.game.Matching
import com.matura.app.learn.Attempt
import com.matura.app.learn.EntryId
import com.matura.app.learn.Outcome
import com.matura.app.learn.Srs
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.SaveData
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
        assertEquals(WordProgress(box = 2, dueDay = 19_900, mistakes = 3), po.progress[klucz])
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
}
