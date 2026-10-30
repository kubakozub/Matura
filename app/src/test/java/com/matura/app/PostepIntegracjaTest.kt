package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.data.Repository
import com.matura.app.learn.Attempt
import com.matura.app.learn.Clock
import com.matura.app.learn.EntryId
import com.matura.app.learn.Outcome
import com.matura.app.learn.PlanSesji
import com.matura.app.learn.Sesja
import com.matura.app.learn.StanSesji
import com.matura.app.learn.Srs
import com.matura.app.learn.Stage
import com.matura.app.model.Direction
import com.matura.app.model.Entry
import com.matura.app.model.Options
import com.matura.app.model.Profile
import com.matura.app.model.SaveData
import com.matura.app.model.ScoreRecord
import com.matura.app.model.TaskKind
import com.matura.app.model.Texture
import com.matura.app.model.WordProgress
import com.matura.app.model.WordSet
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Postep nauki od proby do dysku i z powrotem — na danych, bez UI.
 *
 * SrsTest sprawdza same reguly, MigracjaTest same identyfikatory i podniesienie starego zapisu.
 * Tutaj chodzi o miejsca, w ktorych te dwie rzeczy sie spotykaja, bo tam mieszkaja bledy bez
 * objawow: awans policzony poprawnie, ale nieutrwalony; znacznik jednorazowosci zgubiony w
 * serializacji (i drugi awans z tej samej odpowiedzi po restarcie); postep wyciety z dokumentu
 * przez ekran, ktory zapisuje cos zupelnie innego. Zaden z tych bledow nie wywraca aplikacji —
 * kazdy tylko klamie uczniowi o tym, co umie.
 *
 * Nowa instancja [Repository] to w tych testach ZIMNY START: pamiec podreczna jest pusta, wiec
 * wszystko, co odczytane, naprawde przeszlo przez plik.
 */
class PostepIntegracjaTest {

    @get:Rule
    val katalog = TemporaryFolder()

    private companion object {
        /** Dowolny dzien od epoki UTC; jeden na caly plik, zeby testy czytalo sie jak kalendarz. */
        const val DZIS = 19_875
        const val WLASNY_ZESTAW = "user-1"
    }

    /** Ten sam uklad, ktorego uzywa Repository — inaczej test sprawdzalby inny format. */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Pierwsze slowo katalogu; ma juz nadany identyfikator, bo BuiltInSets jest generowany. */
    private val slowo = BuiltInSets.all().first().entries.first()

    private fun plik(nazwa: String) = File(katalog.root, nazwa)

    /** Poludnie danego dnia UTC — godzina jest tu bez znaczenia i o to wlasnie chodzi. */
    private fun poludnie(dzien: Int) = dzien.toLong() * Srs.MS_PER_DAY + 12 * 3_600_000L

    private fun poRestarcie() = Repository(katalog.root)

    private fun proba(
        entryId: String = slowo.id,
        outcome: Outcome = Outcome.POPRAWNA,
        task: TaskKind = TaskKind.PRZYPOMNIENIE,
        direction: Direction = Direction.TERM_TO_DEF,
        id: String = "",
    ) = Attempt(entryId, direction, task, outcome, id)

    // --- droga jednej proby ----------------------------------------------------------------

    @Test
    fun `jedna proba przechodzi cala droge - zapis, dysk, odczyt, stan`() {
        val repo = Repository(katalog.root)

        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))

        // Dowod, ze postep lezy w PLIKU, a nie tylko w pamieci repozytorium: czytamy zapis
        // wprost. Klucz zawiera znak sterujacy U+001F, ktory musi wyjsc i wrocic bez zmian —
        // gdyby serializacja go zjadla, postep wrocilby pod kluczem, ktorego nikt nie odczyta.
        val klucz = Srs.key(slowo.id, Direction.TERM_TO_DEF)
        assertTrue(klucz.contains(EntryId.UNIT_SEPARATOR))
        val zDysku = json.decodeFromString(
            SaveData.serializer(),
            plik(Repository.FILE_NAME).readText(),
        )
        assertEquals(setOf(klucz), zDysku.progress.keys)

        val stan = poRestarcie().progressOf(slowo.id, Direction.TERM_TO_DEF)
        assertEquals(Srs.FIRST_BOX, stan.box)
        assertEquals(DZIS + 1, stan.dueDay)
        assertEquals(DZIS, stan.lastPromotionDay)
        assertEquals(TaskKind.PRZYPOMNIENIE, stan.lastTask)
        assertEquals("p1", stan.lastAttemptId)
        assertEquals(Stage.POZNANE, Srs.stageOf(stan))
    }

    @Test
    fun `droga do utrwalonego przezywa restart po kazdym dniu`() {
        // Terminy wynikaja z odstepow 1/3/7, wiec kazda proba trafia dokladnie w swoj dzien.
        for (dzien in listOf(DZIS, DZIS + 1, DZIS + 4)) {
            assertTrue(poRestarcie().recordAttempt(proba(id = "dzien-$dzien"), poludnie(dzien)))
        }

        val stan = poRestarcie().progressOf(slowo.id, Direction.TERM_TO_DEF)
        assertEquals(Stage.UTRWALONE, Srs.stageOf(stan))
        assertEquals(DZIS + 11, stan.dueDay)
        assertFalse("utrwalone slowo wrocilo przed terminem", Srs.isDue(stan, DZIS + 10))
        assertTrue(Srs.isDue(stan, DZIS + 11))
    }

    @Test
    fun `dzien samych ABCD zostawia slowo w pierwszym pudelku, dopiero przypomnienie je podnosi`() {
        val repo = Repository(katalog.root)
        // Uczen gra w kolko ten sam zestaw w trybie wyboru odpowiedzi. Sufit rozpoznania musi
        // przezyc zapis tak samo jak sam awans — inaczej wystarczyloby zamknac aplikacje.
        repeat(5) { nr ->
            assertTrue(
                repo.recordAttempt(proba(task = TaskKind.ROZPOZNANIE, id = "abcd-$nr"), poludnie(DZIS)),
            )
        }
        assertEquals(
            Srs.RECOGNITION_CEILING,
            poRestarcie().progressOf(slowo.id, Direction.TERM_TO_DEF).box,
        )

        assertTrue(poRestarcie().recordAttempt(proba(id = "recall"), poludnie(DZIS + 1)))

        val stan = poRestarcie().progressOf(slowo.id, Direction.TERM_TO_DEF)
        assertEquals(2, stan.box)
        assertEquals(TaskKind.PRZYPOMNIENIE, stan.lastTask)
    }

    // --- jednorazowosc proby ---------------------------------------------------------------

    @Test
    fun `ta sama proba przetworzona po restarcie nie dubluje awansu`() {
        val powtarzalna = proba(id = "${slowo.id}#${poludnie(DZIS)}")
        assertTrue(Repository(katalog.root).recordAttempt(powtarzalna, poludnie(DZIS)))

        // Tak wyglada ponowienie po nieudanym zapisie albo drugie zrodlo zdarzen w trybie
        // glosowym: ta sama odpowiedz wraca do zapisu nastepnego dnia, gdy slowo jest juz
        // zalegle. Straznik dziala tylko wtedy, gdy znacznik przetrwal serializacje.
        assertTrue(poRestarcie().recordAttempt(powtarzalna, poludnie(DZIS + 1)))
        assertEquals(Srs.FIRST_BOX, poRestarcie().progressOf(slowo.id, Direction.TERM_TO_DEF).box)

        // Straznik blokuje POWTORZENIE, a nie nauke: nowa proba tego dnia awansuje normalnie.
        assertTrue(poRestarcie().recordAttempt(proba(id = "nowa"), poludnie(DZIS + 1)))
        assertEquals(2, poRestarcie().progressOf(slowo.id, Direction.TERM_TO_DEF).box)
    }

    @Test
    fun `kolejne trafienie tego samego dnia nie rusza pliku`() {
        // Obietnica z KDoc-a recordAttempt, ktora nie dzialala ani razu: ekrany gry skladaja
        // znacznik proby z zegara, wiec nawet niczego niezmieniajaca odpowiedz robila z mapy
        // postepu nowy obiekt i caly dokument szedl na dysk z fsync — w srodku rundy, po
        // kazdym trafieniu. A wlasnie tak wyglada zwykla runda: slowa wracaja po kilka razy.
        val repo = Repository(katalog.root)
        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))
        val przed = plik(Repository.FILE_NAME).readText()

        repeat(5) { nr ->
            assertTrue(repo.recordAttempt(proba(id = "znowu-$nr"), poludnie(DZIS)))
        }

        assertEquals(
            "kolejne trafienie tego samego dnia przepisalo caly dokument",
            przed,
            plik(Repository.FILE_NAME).readText(),
        )
        val stan = poRestarcie().progressOf(slowo.id, Direction.TERM_TO_DEF)
        assertEquals(Srs.FIRST_BOX, stan.box)
        assertEquals(DZIS + 1, stan.dueDay)

        // Kontrola pozytywna: proba, ktora NAPRAWDE cos zmienia, dalej laduje na dysku.
        assertTrue(repo.recordAttempt(proba(outcome = Outcome.BLEDNA, id = "zle"), poludnie(DZIS)))
        assertNotEquals(przed, plik(Repository.FILE_NAME).readText())
        assertEquals(1, poRestarcie().progressOf(slowo.id, Direction.TERM_TO_DEF).mistakes)
    }

    @Test
    fun `przerwana proba nie rusza pliku i nie kasuje postepu`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))
        val przed = plik(Repository.FILE_NAME).readText()

        // Awaria silnika mowy nastepnego dnia, gdy slowo jest juz zalegle. recordAttempt zwraca
        // true, bo zapis sie udal — w tym sensie, ze nie bylo czego zapisywac.
        assertTrue(repo.recordAttempt(proba(outcome = Outcome.PRZERWANA, id = "awaria"), poludnie(DZIS + 1)))

        assertEquals("przerwana proba dotknela pliku", przed, plik(Repository.FILE_NAME).readText())
        val stan = poRestarcie().progressOf(slowo.id, Direction.TERM_TO_DEF)
        assertEquals(Srs.FIRST_BOX, stan.box)
        // Nie zuzyla tez znacznika jednorazowosci — proba, ktora sie nie odbyla, nie jest proba.
        assertEquals("p1", stan.lastAttemptId)
    }

    // --- dzienna sesja: od odpowiedzi do paska postepu --------------------------------------

    /** Zapis proby TAK, jak sklada ja App.kt dla rundy uruchomionej z dziennej sesji. */
    private fun odpowiedzWSesji(
        repo: Repository,
        plan: PlanSesji,
        entryId: String,
        outcome: Outcome,
        znacznik: String,
    ): Boolean = repo.recordAttempt(
        proba(entryId = entryId, outcome = outcome, id = znacznik),
        poludnie(DZIS),
    ) { stan -> Sesja.zaliczOdpowiedz(stan, plan, entryId, outcome) }

    @Test
    fun `dzienna sesja notuje odpowiedzi z rundy - pasek i podsumowanie zgadzaja sie z praca ucznia`() {
        // Petla byla otwarta: runda z sesji konczyla sie powrotem do MENU i nie oddawala sesji
        // ANI JEDNEJ odpowiedzi, wiec pasek stal na "0 / 5" przez cala sesje, a karta
        // podsumowania mowila "Powtorzone: 0 · Nowe: 0" po przerobieniu calej trasy.
        val repo = Repository(katalog.root)
        val slownik = BuiltInSets.all().first().entries.take(8).map { it.id }
        val plan = Sesja.zaplanuj(repo.load().progress, slownik, Direction.TERM_TO_DEF, DZIS)
        assertEquals(Sesja.MAKS_NOWYCH, plan.nowe.size)
        assertTrue(repo.setSesja(Sesja.rozpocznij(plan, Clock { poludnie(DZIS) })))

        val trasa = plan.slownictwo
        assertTrue(odpowiedzWSesji(repo, plan, trasa[0], Outcome.POPRAWNA, "s1"))
        assertTrue(odpowiedzWSesji(repo, plan, trasa[1], Outcome.BLEDNA, "s2"))
        assertTrue(odpowiedzWSesji(repo, plan, trasa[2], Outcome.POPRAWNA, "s3"))
        // To samo slowo drugi raz w rundzie to ta sama praca, a nie druga.
        assertTrue(odpowiedzWSesji(repo, plan, trasa[0], Outcome.POPRAWNA, "s4"))
        // Proba przerwana (awaria silnika mowy, wyjscie z ekranu) sie nie odbyla.
        assertTrue(odpowiedzWSesji(repo, plan, trasa[3], Outcome.PRZERWANA, "s5"))

        val dane = poRestarcie().load()
        val stan = requireNotNull(dane.sesja) { "sesja zniknela z zapisu" }
        assertEquals(listOf(trasa[0], trasa[1], trasa[2]), stan.zrobione)

        // Uczen wraca na ekran sesji: plan liczy sie od nowa z harmonogramu, a stan z zapisu.
        val poPowrocie = Sesja.zaplanuj(dane.progress, slownik, Direction.TERM_TO_DEF, DZIS)
        val wznowiony = Sesja.wznow(stan, poPowrocie, Clock { poludnie(DZIS) })
        val postep = Sesja.postep(wznowiony, poPowrocie)
        assertEquals(3, postep.celeZrobione)
        assertTrue("pasek nie moze pokazywac wiecej zrobionych niz calych", postep.celeRazem >= 3)

        val pods = Sesja.podsumuj(poPowrocie, wznowiony, dane.progress, DZIS)
        assertEquals(3, pods.nowe)
        assertEquals(0, pods.powtorzone)
        // Nauka poszla osobno, do harmonogramu - i to ona ustawia termin nastepnej powtorki.
        assertEquals(Srs.FIRST_BOX, poRestarcie().progressOf(trasa[0], Direction.TERM_TO_DEF).box)
        assertEquals(1, pods.nastepnaPowtorkaZaDni)
    }

    @Test
    fun `odpowiedz spoza sesji nie dopisuje sie do dzisiejszej trasy`() {
        val repo = Repository(katalog.root)
        val stan = StanSesji(dzien = DZIS, krok = 1)
        assertTrue(repo.setSesja(stan))

        // Zwykla gra z MENU: App.kt nie podaje planu, wiec sesja nie dostaje sladu.
        assertTrue(repo.recordAttempt(proba(id = "z-menu"), poludnie(DZIS)))

        assertEquals(stan, poRestarcie().load().sesja)
    }

    // --- postep obok reszty dokumentu ------------------------------------------------------

    @Test
    fun `postep przezywa zapis i odczyt razem z profilem, opcjami, wlasnymi zestawami i rekordami`() {
        val wlasny = Entry("dog", "pies", "u0123456789ab")
        val dane = SaveData(
            schemaVersion = Repository.SCHEMA_VERSION,
            profile = Profile(name = "Kuba", anonymous = false, gamesPlayed = 3, totalKills = 30, bestScore = 800, bestStreak = 6),
            options = Options(lives = 5, soundEnabled = false, texture = Texture.NIGHT, direction = Direction.RANDOM),
            sets = BuiltInSets.all() + WordSet(
                id = WLASNY_ZESTAW,
                title = "Moje słówka",
                entries = listOf(wlasny),
            ),
            scores = listOf(ScoreRecord("Kuba", "Moje słówka", 800, 4, 6, 30, 1_700_000_000_000L)),
            progress = mapOf(
                Srs.key(wlasny.id, Direction.TERM_TO_DEF) to WordProgress(
                    box = 2,
                    dueDay = DZIS + 3,
                    lastPromotionDay = DZIS,
                    mistakes = 1,
                    hints = 2,
                    lastTask = TaskKind.PRZYPOMNIENIE,
                    lastAttemptId = "p7",
                ),
                Srs.key(slowo.id, Direction.DEF_TO_TERM) to WordProgress(
                    box = 1,
                    dueDay = DZIS + 1,
                    lastPromotionDay = DZIS,
                    assistedDay = DZIS,
                    lastTask = TaskKind.ROZPOZNANIE,
                ),
            ),
        )

        assertTrue(Repository(katalog.root).save(dane))
        val odczytane = poRestarcie().load()

        // Rownosc CALEGO dokumentu: nowy dzial nie ma prawa wypchnac z pliku zadnego starego.
        assertEquals(dane, odczytane)
        assertEquals(2, odczytane.progress.size)
        assertEquals(2, Srs.progressOf(odczytane.progress, wlasny.id, Direction.TERM_TO_DEF).box)
        assertEquals("p7", Srs.progressOf(odczytane.progress, wlasny.id, Direction.TERM_TO_DEF).lastAttemptId)
        val rozpoznane = Srs.progressOf(odczytane.progress, slowo.id, Direction.DEF_TO_TERM)
        assertEquals(TaskKind.ROZPOZNANIE, rozpoznane.lastTask)
        assertEquals(DZIS, rozpoznane.assistedDay)
        // "Losuj kierunek" jest USTAWIENIEM i ma prawo lezec w zapisie; do klucza postepu
        // nie wchodzi nigdy (Srs.key by go odrzucil).
        assertEquals(Direction.RANDOM, odczytane.options.direction)
    }

    @Test
    fun `zapis wyniku gry, profilu i ustawien nie kasuje postepu`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))

        // Wszystkie ekrany pisza do TEGO SAMEGO dokumentu przez update(); gdyby ktorykolwiek
        // skladal SaveData od zera zamiast kopiowac, postep znikalby bez zadnego objawu.
        assertTrue(repo.setProfile(Profile(name = "Kuba", anonymous = false)))
        assertTrue(repo.setOptions(Options(lives = 5, texture = Texture.NIGHT, direction = Direction.DEF_TO_TERM)))
        assertTrue(
            repo.upsertSet(
                WordSet(id = WLASNY_ZESTAW, title = "Moje słówka", entries = listOf(Entry("dog", "pies"))),
            ),
        )
        assertTrue(
            repo.recordGame(
                ScoreRecord("Kuba", "Moje słówka", 800, 4, 6, 30, 1_700_000_000_000L),
                WLASNY_ZESTAW,
            ),
        )

        val po = poRestarcie()
        val dane = po.load()
        assertEquals(Srs.FIRST_BOX, po.progressOf(slowo.id, Direction.TERM_TO_DEF).box)
        assertEquals("Kuba", dane.profile.name)
        assertEquals(1, dane.profile.gamesPlayed)
        assertEquals(Texture.NIGHT, dane.options.texture)
        assertEquals(1, dane.scores.size)
        assertEquals(1, dane.sets.first { it.id == WLASNY_ZESTAW }.timesPlayed)
    }

    @Test
    fun `postep wlasnego wpisu przezywa poprawke w edytorze i restart`() {
        val repo = Repository(katalog.root)
        assertTrue(
            repo.upsertSet(
                WordSet(
                    id = WLASNY_ZESTAW,
                    title = "Moje słówka",
                    entries = listOf(Entry("dog", "pies"), Entry("cat", "kot")),
                ),
            ),
        )
        val kot = repo.load().sets.first { it.id == WLASNY_ZESTAW }.entries[1]
        assertTrue("wpis wlasny bez identyfikatora z prefiksem u", EntryId.isUser(kot.id))
        assertTrue(repo.recordAttempt(proba(entryId = kot.id, id = "p1"), poludnie(DZIS)))

        // Edytor sklada wiersze od zera, wiec przychodza BEZ identyfikatorow — dokladnie tak
        // wyglada zwykla poprawka tresci. Uzgodnienie robi upsertSet, nie ekran.
        assertTrue(
            repo.upsertSet(
                WordSet(
                    id = WLASNY_ZESTAW,
                    title = "Moje słówka",
                    entries = listOf(Entry("dog", "pies"), Entry("cat", "kot, kotka")),
                ),
            ),
        )

        val po = poRestarcie()
        val kotPo = po.load().sets.first { it.id == WLASNY_ZESTAW }.entries[1]
        assertEquals(kot.id, kotPo.id)
        assertEquals("kot, kotka", kotPo.definition)
        assertEquals(Srs.FIRST_BOX, po.progressOf(kotPo.id, Direction.TERM_TO_DEF).box)
    }

    @Test
    fun `oba kierunki tego samego slowa maja na dysku osobny stan`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.recordAttempt(proba(id = "ok"), poludnie(DZIS)))
        assertTrue(
            repo.recordAttempt(
                proba(direction = Direction.DEF_TO_TERM, outcome = Outcome.BLEDNA, id = "zle"),
                poludnie(DZIS),
            ),
        )

        val po = poRestarcie()
        val wprzod = po.progressOf(slowo.id, Direction.TERM_TO_DEF)
        val wstecz = po.progressOf(slowo.id, Direction.DEF_TO_TERM)

        assertEquals(Srs.FIRST_BOX, wprzod.box)
        assertEquals(0, wprzod.mistakes)
        // Kto umie przetlumaczyc w jedna strone, nie musi umiec w druga: pomylka w drugim
        // kierunku nie moze dotknac stanu pierwszego (i nie awansuje slowa bez historii).
        assertEquals(Srs.NO_BOX, wstecz.box)
        assertEquals(1, wstecz.mistakes)
        assertEquals(2, po.load().progress.size)
    }

    // --- zapis, ktory sie nie udal ---------------------------------------------------------

    @Test
    fun `nieudany zapis nie udaje, ze postep zostal utrwalony`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))

        // Katalog podstawiony pod nazwe pliku tymczasowego — ten sam sposob wymuszenia bledu,
        // co w PersistenceTest (prawa dostepu odpadaja, bo pod uzytkownikiem uprzywilejowanym
        // niczego nie blokuja).
        val blokada = plik(Repository.TEMP_NAME)
        assertTrue(blokada.mkdirs())
        File(blokada, "zajete").writeText("x")

        assertFalse(
            "awans zglosil sukces, choc nic nie zapisal",
            repo.recordAttempt(proba(id = "p2"), poludnie(DZIS + 1)),
        )
        assertNotNull("blad zapisu przepadl bez sladu", repo.lastSaveError)
        // Ani pamiec, ani dysk nie moga twierdzic, ze uczen awansowal — po restarcie i tak
        // zostaloby stare pudelko, a ekran pokazywalby nowe az do zamkniecia aplikacji.
        assertEquals(Srs.FIRST_BOX, repo.progressOf(slowo.id, Direction.TERM_TO_DEF).box)
        assertEquals(Srs.FIRST_BOX, poRestarcie().progressOf(slowo.id, Direction.TERM_TO_DEF).box)

        File(blokada, "zajete").delete()
        blokada.delete()
        assertTrue(repo.recordAttempt(proba(id = "p2"), poludnie(DZIS + 1)))
        assertEquals(2, poRestarcie().progressOf(slowo.id, Direction.TERM_TO_DEF).box)
    }
}
