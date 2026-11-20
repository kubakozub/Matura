package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.data.Repository
import com.matura.app.learn.Attempt
import com.matura.app.learn.Clock
import com.matura.app.learn.Nagrody
import com.matura.app.learn.Odznaka
import com.matura.app.learn.Outcome
import com.matura.app.learn.Ranga
import com.matura.app.learn.Sesja
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
 * Ksiega dorobku od proby do dysku i z powrotem, na prawdziwym [Repository] w katalogu
 * tymczasowym.
 *
 * NagrodyTest i OdznakiTest sprawdzaja same reguly. Tutaj chodzi o jedno miejsce, w ktorym te
 * reguly dotykaja zapisu, bo tam mieszkaja bledy bez objawow: punkty policzone, ale
 * nieutrwalone; punkty w pamieci po zapisie, ktory sie nie udal; dokument przepisywany po
 * kazdym trafieniu, bo ksiega "tez sie zmienila"; odpowiedz zaplacona dwa razy po ponowieniu.
 *
 * Nowa instancja [Repository] to ZIMNY START, tak jak w PostepIntegracjaTest.
 */
class DorobekIntegracjaTest {

    @get:Rule
    val katalog = TemporaryFolder()

    private companion object {
        /** Dowolny dzien od epoki UTC; jeden na caly plik, zeby testy czytalo sie jak kalendarz. */
        const val DZIS = 19_875
        const val WLASNY_ZESTAW = "user-1"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Pierwszy zestaw katalogu: dwadziescia hasel jednego dzialu, kazde z identyfikatorem. */
    private val zestaw = BuiltInSets.all().first()
    private val slowa = zestaw.entries.map { it.id }

    private fun plik(nazwa: String) = File(katalog.root, nazwa)

    /** Poludnie danego dnia UTC; godzina jest tu bez znaczenia. */
    private fun poludnie(dzien: Int) = dzien.toLong() * Srs.MS_PER_DAY + 12 * 3_600_000L

    private fun poRestarcie() = Repository(katalog.root)

    private fun zDysku(): SaveData =
        json.decodeFromString(SaveData.serializer(), plik(Repository.FILE_NAME).readText())

    /**
     * Odcisk dysku: tresc pliku glownego i tresc kopii. Kazdy udany zapis rotuje plik glowny
     * do kopii, wiec odcisk zmienia sie takze po zapisie IDENTYCZNEJ tresci (kopia dostaje
     * wtedy nowa zawartosc albo dopiero powstaje). Rowny odcisk znaczy: zapisu nie bylo.
     */
    private fun odcisk(): Pair<String?, String?> =
        plik(Repository.FILE_NAME).takeIf { it.exists() }?.readText() to
            plik(Repository.BACKUP_NAME).takeIf { it.exists() }?.readText()

    private fun proba(
        entryId: String = slowa[0],
        outcome: Outcome = Outcome.POPRAWNA,
        task: TaskKind = TaskKind.PRZYPOMNIENIE,
        direction: Direction = Direction.TERM_TO_DEF,
        id: String = "",
    ) = Attempt(entryId, direction, task, outcome, id)

    /** Kolejne poprawne odpowiedzi z pamieci na te same slowa, kazda z wlasnym znacznikiem. */
    private fun odpowiedz(repo: Repository, idy: List<String>, dzien: Int) {
        for (id in idy) {
            assertTrue(repo.recordAttempt(proba(entryId = id, id = "$id#$dzien"), poludnie(dzien)))
        }
    }

    // --- jeden zapis na odpowiedz -----------------------------------------------------------

    @Test
    fun `zaliczenie laduje w tym samym zapisie co postep`() {
        val repo = Repository(katalog.root)

        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))

        // Swiezy katalog i JEDEN zapis: plik glowny jest, a kopii nie ma, bo kopia powstaje
        // dopiero z rotacji przy drugim zapisie. Postep i ksiega musza wiec lezec razem.
        assertTrue(plik(Repository.FILE_NAME).exists())
        assertFalse("odpowiedz zapisala dokument wiecej niz raz", plik(Repository.BACKUP_NAME).exists())
        val dane = zDysku()
        assertEquals(1, Srs.progressOf(dane.progress, slowa[0], Direction.TERM_TO_DEF).box)
        assertEquals(1, Srs.progressOf(dane.progress, slowa[0], Direction.TERM_TO_DEF).szczyt)
        assertEquals(Dorobek(xp = 10, dzien = DZIS, slowaDzis = 1, xpDzis = 10), dane.dorobek)
        assertEquals(dane.dorobek, repo.load().dorobek)
    }

    @Test
    fun `postep, slad sesji i ksiega ida jednym zapisem`() {
        val repo = Repository(katalog.root)
        val plan = Sesja.zaplanuj(repo.load().progress, slowa.take(8), Direction.TERM_TO_DEF, DZIS)
        assertTrue(repo.setSesja(Sesja.rozpocznij(plan, Clock { poludnie(DZIS) })))
        val przedOdpowiedzia = plik(Repository.FILE_NAME).readText()

        val cel = plan.slownictwo.first()
        assertTrue(
            repo.recordAttempt(proba(entryId = cel, id = "s1"), poludnie(DZIS)) { stan ->
                Sesja.zaliczOdpowiedz(stan, plan, cel, Outcome.POPRAWNA)
            },
        )

        // Dokladnie jeden zapis: w kopii lezy dokument sprzed odpowiedzi, a nie stan posredni.
        assertEquals(przedOdpowiedzia, plik(Repository.BACKUP_NAME).readText())
        val dane = zDysku()
        assertEquals(1, Srs.progressOf(dane.progress, cel, Direction.TERM_TO_DEF).box)
        assertEquals(listOf(cel), dane.sesja?.zrobione)
        assertEquals(10, dane.dorobek.xp)
    }

    @Test
    fun `proba, ktora niczego nie zmienia, nie zarabia i nie rusza pliku`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))
        val przed = odcisk()
        val ksiega = repo.load().dorobek

        // Zwykla runda: to samo slowo wraca kilka razy, a po awansie nic sie juz nie zmienia.
        repeat(5) { nr ->
            assertTrue(repo.recordAttempt(proba(id = "znowu-$nr"), poludnie(DZIS)))
        }
        // Kafel ABCD na slowie, ktore dzis juz awansowalo.
        assertTrue(repo.recordAttempt(proba(task = TaskKind.ROZPOZNANIE, id = "kafel"), poludnie(DZIS)))
        // Awaria silnika mowy.
        assertTrue(repo.recordAttempt(proba(outcome = Outcome.PRZERWANA, id = "awaria"), poludnie(DZIS)))
        // Ta sama proba dostarczona drugi raz, i to nastepnego dnia, gdy slowo jest juz zalegle.
        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS + 1)))

        assertEquals("proba bez zmian przepisala dokument", przed, odcisk())
        assertEquals(ksiega, repo.load().dorobek)
        assertEquals(ksiega, poRestarcie().load().dorobek)

        // Kontrola pozytywna: odpowiedz, ktora naprawde awansuje, zapisuje i zarabia.
        assertTrue(repo.recordAttempt(proba(id = "p2"), poludnie(DZIS + 1)))
        assertNotEquals(przed, odcisk())
        assertEquals(20, poRestarcie().load().dorobek.xp)
    }

    @Test
    fun `pomylka idzie na dysk, bo zmienia harmonogram, ale ksiegi nie rusza`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))
        val przed = odcisk()
        val ksiega = repo.load().dorobek

        assertTrue(repo.recordAttempt(proba(outcome = Outcome.BLEDNA, id = "zle"), poludnie(DZIS + 1)))
        // Poprawka po bledzie tego samego dnia: harmonogram jej nie przyjmuje, ksiega tez nie.
        assertTrue(repo.recordAttempt(proba(id = "poprawka"), poludnie(DZIS + 1)))

        assertNotEquals(przed, odcisk())
        val dane = poRestarcie().load()
        assertEquals(1, Srs.progressOf(dane.progress, slowa[0], Direction.TERM_TO_DEF).mistakes)
        assertEquals("pomylka niczego nie zarabia i niczego nie odbiera", ksiega, dane.dorobek)
    }

    @Test
    fun `trafiony kafel nie odhacza slowa czekajacego na wpisanie i nie rusza pliku`() {
        val repo = Repository(katalog.root)
        odpowiedz(repo, slowa.take(1), DZIS)       // pudelko 1, termin jutro
        odpowiedz(repo, slowa.take(1), DZIS + 1)   // pudelko 2, termin za trzy dni
        val termin = DZIS + 4
        val przed = odcisk()
        val ksiega = repo.load().dorobek

        assertTrue(repo.recordAttempt(proba(task = TaskKind.ROZPOZNANIE, id = "kafel"), poludnie(termin)))

        assertEquals("rozpoznanie slowa z pudelka 2 przepisalo dokument", przed, odcisk())
        assertEquals(ksiega, repo.load().dorobek)

        // Slowo dalej czeka i tego samego dnia daje sie utrwalic z pamieci.
        assertTrue(repo.recordAttempt(proba(id = "wpisane"), poludnie(termin)))
        val dane = poRestarcie().load()
        assertEquals(3, Srs.progressOf(dane.progress, slowa[0], Direction.TERM_TO_DEF).box)
        assertEquals(ksiega.xp + Zaliczenie.UTRWALENIE.xp, dane.dorobek.xp)
    }

    @Test
    fun `wyrownanie szczytu w stanie zlozonym poza harmonogramem nie jest powodem do zapisu`() {
        // Stan wpisany wprost do pliku z biezacym schematem (tak robia testy, tak moglby wygladac
        // plik poprawiany recznie): pudelko 2, szczytu brak. Srs.apply wyrownuje szczyt przy
        // kazdej probie, takze takiej, ktora w harmonogramie nie zmienia nic. Samo to nie moze
        // przepisywac dokumentu po kazdym trafieniu.
        val klucz = Srs.key(slowa[0], Direction.TERM_TO_DEF)
        val dane = SaveData(
            schemaVersion = Repository.SCHEMA_VERSION,
            sets = BuiltInSets.all(),
            progress = mapOf(klucz to WordProgress(box = 2, dueDay = DZIS + 3, lastPromotionDay = DZIS)),
        )
        assertTrue(Repository(katalog.root).save(dane))
        val repo = poRestarcie()
        val przed = odcisk()

        // Slowo nie jest zalegle, wiec trafienie niczego nie przesuwa.
        assertTrue(repo.recordAttempt(proba(id = "przed-terminem"), poludnie(DZIS + 1)))

        assertEquals("sam szczyt przepisal dokument", przed, odcisk())
        assertEquals(Dorobek(), repo.load().dorobek)

        // Przy pierwszej prawdziwej zmianie szczyt idzie na dysk razem z nia i pamieta pudelko 2,
        // choc pomylka wlasnie je cofnela.
        assertTrue(repo.recordAttempt(proba(outcome = Outcome.BLEDNA, id = "zle"), poludnie(DZIS + 1)))
        val stan = poRestarcie().load().progress.getValue(klucz)
        assertEquals(1, stan.box)
        assertEquals(2, stan.szczyt)
    }

    // --- zapis, ktory sie nie udal, i proba dostarczona dwa razy ------------------------------

    @Test
    fun `nieudany zapis nie zostawia punktow ani w pamieci, ani na dysku`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))
        val ksiega = repo.load().dorobek

        // Katalog podstawiony pod nazwe pliku tymczasowego: ten sam sposob wymuszenia bledu,
        // co w PersistenceTest (prawa dostepu odpadaja, bo pod uzytkownikiem uprzywilejowanym
        // niczego nie blokuja).
        val blokada = plik(Repository.TEMP_NAME)
        assertTrue(blokada.mkdirs())
        File(blokada, "zajete").writeText("x")

        assertFalse(repo.recordAttempt(proba(id = "p2"), poludnie(DZIS + 1)))
        assertNotNull(repo.lastSaveError)
        // Punkty, ktorych nie ma na dysku, nie moga swiecic na ekranie do zamkniecia aplikacji.
        assertEquals(ksiega, repo.load().dorobek)
        assertEquals(ksiega, poRestarcie().load().dorobek)

        File(blokada, "zajete").delete()
        blokada.delete()

        // Ponowienie TEJ SAMEJ proby placi, bo poprzednie nie zostawilo sladu. I placi raz.
        assertTrue(repo.recordAttempt(proba(id = "p2"), poludnie(DZIS + 1)))
        assertEquals(20, repo.load().dorobek.xp)
        assertTrue(repo.recordAttempt(proba(id = "p2"), poludnie(DZIS + 1)))
        assertEquals(20, poRestarcie().load().dorobek.xp)
    }

    @Test
    fun `ta sama proba dostarczona kilka razy placi raz, takze po restarcie i nastepnego dnia`() {
        val powtarzalna = proba(id = "${slowa[0]}#${poludnie(DZIS)}")
        assertTrue(Repository(katalog.root).recordAttempt(powtarzalna, poludnie(DZIS)))

        assertTrue(poRestarcie().recordAttempt(powtarzalna, poludnie(DZIS)))
        assertTrue(poRestarcie().recordAttempt(powtarzalna, poludnie(DZIS + 1)))

        assertEquals(10, poRestarcie().load().dorobek.xp)
        assertEquals(1, poRestarcie().load().dorobek.slowaDzis)

        // Straznik blokuje powtorzenie, a nie nauke: nowa proba nastepnego dnia zarabia.
        assertTrue(poRestarcie().recordAttempt(proba(id = "nowa"), poludnie(DZIS + 1)))
        assertEquals(20, poRestarcie().load().dorobek.xp)
    }

    // --- dni, cel, seria, odznaki ---------------------------------------------------------------

    @Test
    fun `trzy dni nauki - cel dnia, seria i odznaki ze stemplem dnia przezywaja restart`() {
        assertTrue("test potrzebuje dziesieciu slow jednego dzialu", slowa.distinct().size >= 10)

        odpowiedz(poRestarcie(), slowa.take(5), DZIS)
        val pierwszy = poRestarcie().load().dorobek
        assertEquals(50, pierwszy.xp)
        assertEquals(5, pierwszy.slowaDzis)
        assertEquals(1, pierwszy.dniNauki)
        assertEquals(1, pierwszy.seria)
        assertEquals(mapOf("SLOWA_5" to DZIS, "DNI_1" to DZIS), pierwszy.odznaki)

        // Te same slowa w swoim terminie: piec pierwszych powtorek z pamieci.
        odpowiedz(poRestarcie(), slowa.take(5), DZIS + 1)
        val drugi = poRestarcie().load().dorobek
        assertEquals(100, drugi.xp)
        assertEquals(DZIS + 1, drugi.dzien)
        assertEquals(5, drugi.slowaDzis)
        assertEquals(50, drugi.xpDzis)
        assertEquals(2, drugi.seria)

        // Piec nowych slow: dziesiec poznanych w jednym dziale i trzeci dzien z rzedu.
        odpowiedz(poRestarcie(), slowa.drop(5).take(5), DZIS + 2)
        val trzeci = poRestarcie().load().dorobek

        assertEquals(
            Dorobek(
                xp = 150,
                dzien = DZIS + 2,
                slowaDzis = 5,
                xpDzis = 50,
                seria = 3,
                seriaDzien = DZIS + 2,
                rekordSerii = 3,
                dniNauki = 3,
                powtorki = 0,
                odznaki = mapOf(
                    "SLOWA_5" to DZIS,
                    "DNI_1" to DZIS,
                    "SERIA_3" to DZIS + 2,
                    "DZIAL_1" to DZIS + 2,
                ),
            ),
            trzeci,
        )
        assertEquals(trzeci, zDysku().dorobek)
        assertEquals(Ranga.STUDNIOWKA, Nagrody.ranga(trzeci.xp))
    }

    @Test
    fun `cel dnia bierze sie z zapisanych opcji`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.setOptions(Options(celDnia = 10)))

        odpowiedz(repo, slowa.take(9), DZIS)
        assertEquals(0, repo.load().dorobek.dniNauki)
        assertFalse(Nagrody.celZaliczony(repo.load().dorobek, DZIS))

        odpowiedz(repo, slowa.drop(9).take(1), DZIS)
        assertEquals(1, poRestarcie().load().dorobek.dniNauki)
        assertTrue(Nagrody.celZaliczony(poRestarcie().load().dorobek, DZIS))
    }

    @Test
    fun `cel spoza dozwolonych wartosci jest czytany jak domyslny`() {
        // Zero w pliku poprawianym recznie zaliczaloby dzien pierwsza odpowiedzia.
        val repo = Repository(katalog.root)
        assertTrue(repo.setOptions(Options(celDnia = 0)))

        odpowiedz(repo, slowa.take(4), DZIS)
        assertEquals(0, repo.load().dorobek.dniNauki)

        odpowiedz(repo, slowa.drop(4).take(1), DZIS)
        assertEquals(1, repo.load().dorobek.dniNauki)
    }

    @Test
    fun `wlasny zestaw daje punkty nauki i dni, ale nie odznaki katalogu`() {
        val repo = Repository(katalog.root)
        assertTrue(
            repo.upsertSet(
                WordSet(
                    id = WLASNY_ZESTAW,
                    title = "Moje słówka",
                    entries = (1..5).map { Entry("word$it", "słowo$it") },
                ),
            ),
        )
        val wlasne = repo.load().sets.first { it.id == WLASNY_ZESTAW }.entries.map { it.id }

        odpowiedz(repo, wlasne, DZIS)

        val ksiega = poRestarcie().load().dorobek
        assertEquals("nauka wlasnych slow to tez nauka", 50, ksiega.xp)
        assertEquals(1, ksiega.dniNauki)
        assertEquals(
            "piec wlasnych slow nie moze dac odznaki za slowa ze slownika gry",
            mapOf("DNI_1" to DZIS),
            ksiega.odznaki,
        )
    }

    // --- gra ------------------------------------------------------------------------------------

    private fun gra(tryb: GameMode, punkty: Int = 0, pokonane: Int = 0, seria: Int = 0) =
        ScoreRecord("Kuba", zestaw.title, punkty, 1, seria, pokonane, 1_700_000_000_000L, tryb)

    @Test
    fun `gra stempluje odznaki za gre dniem gry, ale punktow nauki nie dopisuje`() {
        val repo = Repository(katalog.root)

        val pierwsza = gra(GameMode.CLASSIC, punkty = 600, pokonane = 120, seria = 6)
        assertTrue(repo.recordGame(pierwsza, zestaw.id, poludnie(DZIS)))

        val poPierwszej = poRestarcie().load()
        assertEquals(
            mapOf("FIRST_BLOOD" to DZIS, "SHARPSHOOTER" to DZIS, "COMBO_X3" to DZIS, "SCORE_500" to DZIS),
            poPierwszej.dorobek.odznaki,
        )
        assertEquals("punkty za gre to nie punkty nauki", 0, poPierwszej.dorobek.xp)
        assertEquals(Dorobek(odznaki = poPierwszej.dorobek.odznaki), poPierwszej.dorobek)

        assertTrue(repo.recordGame(gra(GameMode.TRUE_FALSE), zestaw.id, poludnie(DZIS + 1)))
        assertTrue(repo.recordGame(gra(GameMode.MULTIPLE_CHOICE), zestaw.id, poludnie(DZIS + 1)))
        assertFalse("trzy tryby to jeszcze nie cztery", "TRYBY_4" in repo.load().dorobek.odznaki)
        assertTrue(repo.recordGame(gra(GameMode.TIME_ATTACK), zestaw.id, poludnie(DZIS + 2)))
        assertTrue(repo.recordGame(gra(GameMode.CLASSIC), zestaw.id, poludnie(DZIS + 3)))

        val odznaki = poRestarcie().load().dorobek.odznaki
        assertEquals(
            mapOf(
                // Stare stemple zostaja z dniem, w ktorym padly.
                "FIRST_BLOOD" to DZIS, "SHARPSHOOTER" to DZIS, "COMBO_X3" to DZIS, "SCORE_500" to DZIS,
                "TRYBY_4" to DZIS + 2,
                "ROOKIE" to DZIS + 3,
            ),
            odznaki,
        )
    }

    // --- rozpisanie rundy a to, co lezy w zapisie -----------------------------------------------

    @Test
    fun `odtworzenie rundy zgadza sie z tym, co zapisalo repozytorium`() {
        val repo = Repository(katalog.root)
        val utrwalone = slowa[0]
        val powtarzane = slowa[1]
        val poznane = slowa[2]
        val nowe = slowa[3]
        val doRozpoznania = slowa[4]
        val potkniecie = slowa[5]
        val przerwane = slowa[6]
        // Historia sprzed rundy, kazda proba w swoim terminie (odstepy 1, 3, 7 dni):
        odpowiedz(repo, listOf(utrwalone), DZIS - 11)
        odpowiedz(repo, listOf(utrwalone), DZIS - 10)
        odpowiedz(repo, listOf(utrwalone), DZIS - 7)            // pudelko 3, termin DZIS
        odpowiedz(repo, listOf(powtarzane), DZIS - 4)
        odpowiedz(repo, listOf(powtarzane), DZIS - 3)           // pudelko 2, termin DZIS
        odpowiedz(repo, listOf(poznane), DZIS - 1)              // pudelko 1, termin DZIS

        val przed = repo.load()
        val proby = listOf(
            proba(entryId = utrwalone, id = "r1"),                                   // powtorka w terminie
            proba(entryId = powtarzane, id = "r2"),                                  // utrwalenie
            proba(entryId = poznane, id = "r3"),                                     // awans
            proba(entryId = nowe, id = "r4"),                                        // nowe slowo
            proba(entryId = doRozpoznania, task = TaskKind.ROZPOZNANIE, id = "r5"),  // rozpoznane
            proba(entryId = potkniecie, outcome = Outcome.BLEDNA, id = "r6"),
            proba(entryId = potkniecie, id = "r7"),                                  // poprawka: nic
            proba(entryId = utrwalone, id = "r8"),                                   // drugi raz dzis: nic
            proba(entryId = nowe, id = "r4"),                                        // ta sama proba: nic
            proba(entryId = przerwane, outcome = Outcome.PRZERWANA, id = "r9"),
        )
        for (p in proby) assertTrue(repo.recordAttempt(p, poludnie(DZIS)))
        val wynik = gra(GameMode.CLASSIC, punkty = 340, pokonane = 6, seria = 4)
        assertTrue(repo.recordGame(wynik, zestaw.id, poludnie(DZIS)))
        val po = poRestarcie().load()

        val nagroda = Nagrody.roznica(przed, po, proby, wynik, DZIS)

        assertEquals(
            listOf(
                Zaliczenie.POWTORKA, Zaliczenie.UTRWALENIE, Zaliczenie.AWANS,
                Zaliczenie.NOWE, Zaliczenie.ROZPOZNANE,
            ),
            Nagrody.zaliczeniaRundy(przed.progress, proby, DZIS),
        )
        assertEquals(47, po.dorobek.xp - przed.dorobek.xp)
        assertEquals(47, nagroda.xp)
        assertEquals("rozpisanie i ksiega opowiadaja te sama runde", nagroda.xp, nagroda.xpZaliczen)
        assertEquals(
            mapOf(
                Zaliczenie.ROZPOZNANE to 1,
                Zaliczenie.NOWE to 1,
                Zaliczenie.AWANS to 1,
                Zaliczenie.UTRWALENIE to 1,
                Zaliczenie.POWTORKA to 1,
            ),
            nagroda.zaliczenia,
        )
        assertEquals(0, nagroda.slowaPrzed)
        assertEquals("cztery zaliczenia z pamieci, rozpoznanie sie nie liczy", 4, nagroda.slowaPo)
        assertEquals(4, po.dorobek.slowaDzis)
        assertEquals(47, po.dorobek.xpDzis)
        assertEquals(1, po.dorobek.powtorki - przed.dorobek.powtorki)
        assertFalse(nagroda.celZaliczonyTeraz)
        // Piate poznane slowo padlo w tej rundzie, a pierwszy pokonany potworek w tej grze.
        assertEquals(listOf(Odznaka.SLOWA_5, Odznaka.FIRST_BLOOD), nagroda.odznaki)
        assertFalse("pierwsza gra w trybie nie jest rekordem", nagroda.rekord)

        // I stan slow na dysku jest dokladnie tym, co dalo odtworzenie.
        var odtworzony = przed.progress
        for (p in proby) odtworzony = Srs.applyTo(odtworzony, p, DZIS)
        for (id in slowa.take(7)) {
            val zapisany = Srs.progressOf(po.progress, id, Direction.TERM_TO_DEF)
            val wyliczony = Srs.progressOf(odtworzony, id, Direction.TERM_TO_DEF)
            // Znacznik i rodzaj ostatniej proby sa ksiegowe: proba bez zmian ich nie utrwala.
            assertEquals(
                "slowo $id",
                wyliczony.copy(lastAttemptId = zapisany.lastAttemptId, lastTask = zapisany.lastTask),
                zapisany,
            )
        }
    }
}
