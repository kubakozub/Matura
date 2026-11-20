package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.data.Repository
import com.matura.app.learn.Attempt
import com.matura.app.learn.Clock
import com.matura.app.learn.Etap
import com.matura.app.learn.Limit
import com.matura.app.learn.Nagrody
import com.matura.app.learn.Odznaka
import com.matura.app.learn.Outcome
import com.matura.app.learn.PozycjaNagrody
import com.matura.app.learn.Sesja
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
import kotlin.random.Random

/**
 * Ksiega dorobku od proby do dysku i z powrotem, na prawdziwym [Repository] w katalogu
 * tymczasowym.
 *
 * NagrodyTest i OdznakiTest sprawdzaja same reguly. Tutaj chodzi o jedno miejsce, w ktorym te
 * reguly dotykaja zapisu, bo tam mieszkaja bledy bez objawow: punkty policzone, ale
 * nieutrwalone; punkty w pamieci po zapisie, ktory sie nie udal; dokument przepisywany po
 * kazdym trafieniu, bo ksiega "tez sie zmienila"; odpowiedz zaplacona dwa razy po ponowieniu;
 * rozpisanie rundy, ktore mowi co innego niz ksiega.
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
    private fun odpowiedz(
        repo: Repository,
        idy: List<String>,
        dzien: Int,
        task: TaskKind = TaskKind.PRZYPOMNIENIE,
        wplywy: MutableList<Wplyw>? = null,
    ) {
        for (id in idy) {
            assertTrue(
                repo.recordAttempt(
                    proba(entryId = id, task = task, id = "$id#$dzien"),
                    poludnie(dzien),
                    naWplyw = wplywy?.let { lista -> { wplyw: Wplyw -> lista += wplyw } },
                ),
            )
        }
    }

    /** Wlasny zestaw ucznia o [ile] wierszach; oddaje identyfikatory, ktore nadalo Repository. */
    private fun wlasneSlowa(repo: Repository, ile: Int): List<String> {
        assertTrue(
            repo.upsertSet(
                WordSet(
                    id = WLASNY_ZESTAW,
                    title = "Moje słówka",
                    entries = (1..ile).map { Entry("word$it", "słowo$it") },
                ),
            ),
        )
        return repo.load().sets.first { it.id == WLASNY_ZESTAW }.entries.map { it.id }
    }

    // --- jeden zapis na odpowiedz -----------------------------------------------------------

    @Test
    fun `zaliczenie laduje w tym samym zapisie co postep`() {
        val repo = Repository(katalog.root)

        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))

        // Swiezy katalog i JEDEN zapis: plik glowny jest, a kopii nie ma, bo kopia powstaje
        // dopiero z rotacji przy drugim zapisie. Postep, ksiega i odznaka musza wiec lezec razem.
        assertTrue(plik(Repository.FILE_NAME).exists())
        assertFalse("odpowiedz zapisala dokument wiecej niz raz", plik(Repository.BACKUP_NAME).exists())
        val dane = zDysku()
        assertEquals(1, Srs.progressOf(dane.progress, slowa[0], Direction.TERM_TO_DEF).box)
        assertEquals(1, Srs.progressOf(dane.progress, slowa[0], Direction.TERM_TO_DEF).szczyt)
        assertEquals(
            Dorobek(
                xp = 10,
                dzien = DZIS,
                xpDzis = 10,
                noweDzis = 1,
                wolne = 1,
                odznaki = mapOf("SLOWA_1" to DZIS),
                odznakiNowe = setOf("SLOWA_1"),
            ),
            dane.dorobek,
        )
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
            repo.recordAttempt(
                proba(entryId = cel, id = "s1"),
                poludnie(DZIS),
                { stan -> Sesja.zaliczOdpowiedz(stan, plan, cel, Outcome.POPRAWNA) },
            ),
        )

        // Dokladnie jeden zapis: w kopii lezy dokument sprzed odpowiedzi, a nie stan posredni.
        assertEquals(przedOdpowiedzia, plik(Repository.BACKUP_NAME).readText())
        val dane = zDysku()
        assertEquals(1, Srs.progressOf(dane.progress, cel, Direction.TERM_TO_DEF).box)
        assertEquals(listOf(cel), dane.sesja?.zrobione)
        assertEquals(10, dane.dorobek.xp)
    }

    @Test
    fun `proba, ktora niczego nie zmienia, nie zarabia, nie rusza pliku i nie oddaje wplywu`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))
        val przed = odcisk()
        val ksiega = repo.load().dorobek
        val wplywy = ArrayList<Wplyw>()
        val zbieraj: (Wplyw) -> Unit = { wplywy += it }

        // Zwykla runda: to samo slowo wraca kilka razy, a po awansie nic sie juz nie zmienia.
        repeat(5) { nr ->
            assertTrue(repo.recordAttempt(proba(id = "znowu-$nr"), poludnie(DZIS), naWplyw = zbieraj))
        }
        // Kafel wyboru na slowie, ktore dzis juz awansowalo.
        assertTrue(repo.recordAttempt(proba(task = TaskKind.ROZPOZNANIE, id = "kafel"), poludnie(DZIS), naWplyw = zbieraj))
        // Awaria silnika mowy.
        assertTrue(repo.recordAttempt(proba(outcome = Outcome.PRZERWANA, id = "awaria"), poludnie(DZIS), naWplyw = zbieraj))
        // Ta sama proba dostarczona drugi raz, i to nastepnego dnia, gdy slowo jest juz zalegle.
        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS + 1), naWplyw = zbieraj))

        assertEquals("proba bez zmian przepisala dokument", przed, odcisk())
        assertEquals(ksiega, repo.load().dorobek)
        assertEquals(ksiega, poRestarcie().load().dorobek)
        assertTrue("proba bez zaliczenia oddala wplyw: $wplywy", wplywy.isEmpty())

        // Kontrola pozytywna: odpowiedz, ktora naprawde awansuje, zapisuje, zarabia i oddaje wplyw.
        assertTrue(repo.recordAttempt(proba(id = "p2"), poludnie(DZIS + 1), naWplyw = zbieraj))
        assertNotEquals(przed, odcisk())
        assertEquals(30, poRestarcie().load().dorobek.xp)
        assertEquals(listOf(Wplyw(Zaliczenie.AWANS, 20, null)), wplywy)
    }

    @Test
    fun `pomylka idzie na dysk, bo zmienia harmonogram, ale ksiegi nie rusza`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))
        val przed = odcisk()
        val ksiega = repo.load().dorobek
        val wplywy = ArrayList<Wplyw>()

        assertTrue(repo.recordAttempt(proba(outcome = Outcome.BLEDNA, id = "zle"), poludnie(DZIS + 1)) { wplywy += it })
        // Poprawka po bledzie tego samego dnia: harmonogram jej nie przyjmuje, ksiega tez nie.
        assertTrue(repo.recordAttempt(proba(id = "poprawka"), poludnie(DZIS + 1)) { wplywy += it })

        assertNotEquals(przed, odcisk())
        val dane = poRestarcie().load()
        assertEquals(1, Srs.progressOf(dane.progress, slowa[0], Direction.TERM_TO_DEF).mistakes)
        assertEquals("pomylka niczego nie zarabia i niczego nie odbiera", ksiega, dane.dorobek)
        assertTrue(wplywy.isEmpty())
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
        assertEquals(ksiega.xp + 30, dane.dorobek.xp)
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
    fun `nieudany zapis nie zostawia punktow ani w pamieci, ani na dysku i nie oddaje wplywu`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.recordAttempt(proba(id = "p1"), poludnie(DZIS)))
        val ksiega = repo.load().dorobek
        val wplywy = ArrayList<Wplyw>()

        // Katalog podstawiony pod nazwe pliku tymczasowego: ten sam sposob wymuszenia bledu,
        // co w PersistenceTest (prawa dostepu odpadaja, bo pod uzytkownikiem uprzywilejowanym
        // niczego nie blokuja).
        val blokada = plik(Repository.TEMP_NAME)
        assertTrue(blokada.mkdirs())
        File(blokada, "zajete").writeText("x")

        assertFalse(repo.recordAttempt(proba(id = "p2"), poludnie(DZIS + 1)) { wplywy += it })
        assertNotNull(repo.lastSaveError)
        // Punkty, ktorych nie ma na dysku, nie moga swiecic na ekranie do zamkniecia aplikacji,
        // ani w ksiedze, ani w rozpisaniu rundy.
        assertEquals(ksiega, repo.load().dorobek)
        assertEquals(ksiega, poRestarcie().load().dorobek)
        assertTrue("wplyw oddany mimo nieudanego zapisu", wplywy.isEmpty())

        File(blokada, "zajete").delete()
        blokada.delete()

        // Ponowienie TEJ SAMEJ proby placi, bo poprzednie nie zostawilo sladu. I placi raz.
        assertTrue(repo.recordAttempt(proba(id = "p2"), poludnie(DZIS + 1)) { wplywy += it })
        assertEquals(30, repo.load().dorobek.xp)
        assertTrue(repo.recordAttempt(proba(id = "p2"), poludnie(DZIS + 1)) { wplywy += it })
        assertEquals(30, poRestarcie().load().dorobek.xp)
        assertEquals(listOf(Wplyw(Zaliczenie.AWANS, 20, null)), wplywy)
    }

    @Test
    fun `ta sama proba dostarczona kilka razy placi raz, takze po restarcie i nastepnego dnia`() {
        val powtarzalna = proba(id = "${slowa[0]}#${poludnie(DZIS)}")
        assertTrue(Repository(katalog.root).recordAttempt(powtarzalna, poludnie(DZIS)))

        assertTrue(poRestarcie().recordAttempt(powtarzalna, poludnie(DZIS)))
        assertTrue(poRestarcie().recordAttempt(powtarzalna, poludnie(DZIS + 1)))

        assertEquals(10, poRestarcie().load().dorobek.xp)
        assertEquals(1, poRestarcie().load().dorobek.noweDzis)

        // Straznik blokuje powtorzenie, a nie nauke: nowa proba nastepnego dnia zarabia.
        assertTrue(poRestarcie().recordAttempt(proba(id = "nowa"), poludnie(DZIS + 1)))
        assertEquals(30, poRestarcie().load().dorobek.xp)
    }

    // --- dni, cel, seria, odznaki ---------------------------------------------------------------

    @Test
    fun `trzy dni nauki - cel dnia, seria i odznaki ze stemplem dnia przezywaja restart`() {
        assertTrue("test potrzebuje dziesieciu slow jednego dzialu", slowa.distinct().size >= 10)

        odpowiedz(poRestarcie(), slowa.take(5), DZIS)
        val pierwszy = poRestarcie().load().dorobek
        assertEquals(50, pierwszy.xp)
        assertEquals(50, pierwszy.xpDzis)
        assertEquals(5, pierwszy.noweDzis)
        assertEquals(1, pierwszy.dniNauki)
        assertEquals(1, pierwszy.seria)
        assertEquals(mapOf("SLOWA_1" to DZIS), pierwszy.odznaki)

        // Te same slowa w swoim terminie: piec pierwszych powtorek z pamieci, po 20 punktow.
        odpowiedz(poRestarcie(), slowa.take(5), DZIS + 1)
        val drugi = poRestarcie().load().dorobek
        assertEquals(150, drugi.xp)
        assertEquals(DZIS + 1, drugi.dzien)
        assertEquals(100, drugi.xpDzis)
        assertEquals(0, drugi.noweDzis)
        assertEquals(5, drugi.powtorki)
        assertEquals(2, drugi.seria)

        // Piec nowych slow i trzeci dzien serii.
        odpowiedz(poRestarcie(), slowa.drop(5).take(5), DZIS + 2)
        val trzeci = poRestarcie().load().dorobek

        assertEquals(
            Dorobek(
                xp = 200,
                dzien = DZIS + 2,
                xpDzis = 50,
                noweDzis = 5,
                seria = 3,
                seriaDzien = DZIS + 2,
                wolne = 1,
                rekordSerii = 3,
                dniNauki = 3,
                powtorki = 5,
                odznaki = mapOf("SLOWA_1" to DZIS, "SERIA_3" to DZIS + 2),
                // Nikt ich jeszcze nie pokazal: czekaja, takze przez restart.
                odznakiNowe = setOf("SLOWA_1", "SERIA_3"),
            ),
            trzeci,
        )
        assertEquals(trzeci, zDysku().dorobek)
        assertEquals(Etap.KLASOWKA, Nagrody.etap(trzeci.xp))
    }

    @Test
    fun `odznaka z licznika ksiegi wchodzi tym samym zapisem co zaliczenie, ktore ja dalo`() {
        // Trzy dni po trzy nowe slowa: cel dnia pada dokladnie OSTATNIA odpowiedzia dnia, wiec
        // odznaka za serie nie ma pozniejszego zapisu, w ktorym moglaby sie "dogonic". Odznaki
        // musza sie liczyc z ksiegi juz po doliczeniu, a nie ze stanu sprzed odpowiedzi.
        val repo = Repository(katalog.root)
        odpowiedz(repo, slowa.take(3), DZIS)
        odpowiedz(repo, slowa.drop(3).take(3), DZIS + 1)
        odpowiedz(repo, slowa.drop(6).take(2), DZIS + 2)
        assertFalse("SERIA_3" in repo.load().dorobek.odznaki)
        val przedOstatnia = plik(Repository.FILE_NAME).readText()

        odpowiedz(repo, slowa.drop(8).take(1), DZIS + 2)

        assertEquals("ostatnia odpowiedz zapisala dokument wiecej niz raz", przedOstatnia, plik(Repository.BACKUP_NAME).readText())
        val ksiega = zDysku().dorobek
        assertEquals(3, ksiega.seria)
        assertEquals(30, ksiega.xpDzis)
        assertEquals(DZIS + 2, ksiega.odznaki["SERIA_3"])
        assertTrue("SERIA_3" in ksiega.odznakiNowe)
    }

    @Test
    fun `cel dnia bierze sie z zapisanych opcji`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.setOptions(Options(celDnia = 60)))

        odpowiedz(repo, slowa.take(5), DZIS)
        assertEquals(50, repo.load().dorobek.xpDzis)
        assertEquals(0, repo.load().dorobek.dniNauki)
        assertFalse(Nagrody.celZaliczony(repo.load().dorobek, DZIS))

        odpowiedz(repo, slowa.drop(5).take(1), DZIS)
        assertEquals(1, poRestarcie().load().dorobek.dniNauki)
        assertTrue(Nagrody.celZaliczony(poRestarcie().load().dorobek, DZIS))
    }

    @Test
    fun `cel spoza dozwolonych wartosci jest czytany jak domyslny`() {
        // Zero w pliku poprawianym recznie zaliczaloby dzien pierwsza odpowiedzia, a piatka
        // zostala w plikach z czasu, gdy cel liczyl slowa.
        for ((nr, zapisany) in listOf(0, 5).withIndex()) {
            val repo = Repository(katalog.newFolder("cel$nr"))
            assertTrue(repo.setOptions(Options(celDnia = zapisany)))

            odpowiedz(repo, slowa.take(2), DZIS)
            assertEquals("cel $zapisany", 0, repo.load().dorobek.dniNauki)

            odpowiedz(repo, slowa.drop(2).take(1), DZIS)
            assertEquals("cel $zapisany", 1, repo.load().dorobek.dniNauki)
        }
    }

    // --- limity dzienne i naprawa: to, co widzi tylko Repository ---------------------------------

    @Test
    fun `wlasny zestaw daje punkty za dziesiec slow dziennie, a slownik gry placi dalej`() {
        val repo = Repository(katalog.root)
        val wlasne = wlasneSlowa(repo, 12)
        val wplywy = ArrayList<Wplyw>()

        odpowiedz(repo, wlasne, DZIS, wplywy = wplywy)

        assertEquals(List(10) { Wplyw(Zaliczenie.NOWE, 10, null) }, wplywy.take(10))
        assertEquals(List(2) { Wplyw(Zaliczenie.NOWE, 0, Limit.WLASNE) }, wplywy.drop(10))
        val ksiega = poRestarcie().load().dorobek
        assertEquals("nauka wlasnych slow to tez nauka, do limitu", 100, ksiega.xp)
        assertEquals(10, ksiega.wlasneDzis)
        assertEquals(12, ksiega.noweDzis)
        assertEquals(1, ksiega.dniNauki)
        assertTrue(
            "wlasne slowa nie moga dac odznaki za slowa ze slownika gry: ${ksiega.odznaki}",
            ksiega.odznaki.isEmpty(),
        )
        // Slowa ponad limit i tak awansowaly w harmonogramie: limit zabiera punkty, nie nauke.
        for (id in wlasne) assertEquals(1, Srs.progressOf(poRestarcie().load().progress, id, Direction.TERM_TO_DEF).box)

        // Slowo ze slownika gry tego samego dnia placi normalnie.
        wplywy.clear()
        odpowiedz(repo, slowa.take(1), DZIS, wplywy = wplywy)
        assertEquals(listOf(Wplyw(Zaliczenie.NOWE, 10, null)), wplywy)
        assertEquals(110, repo.load().dorobek.xp)

        // Nastepnego dnia wlasne slowa placa od nowa: tu dwanascie pierwszych powtorek.
        wplywy.clear()
        odpowiedz(repo, wlasne, DZIS + 1, wplywy = wplywy)
        assertEquals(10, wplywy.count { it.xp == 20 })
        assertEquals(2, wplywy.count { it.limit == Limit.WLASNE })
        assertEquals(110 + 200, poRestarcie().load().dorobek.xp)
        assertEquals("powtorki ponad limit nie wchodza do licznika", 10, poRestarcie().load().dorobek.powtorki)
    }

    @Test
    fun `rozpoznanie daje punkty za piec nowych slow dziennie`() {
        val repo = Repository(katalog.root)
        val wplywy = ArrayList<Wplyw>()

        odpowiedz(repo, slowa.take(7), DZIS, task = TaskKind.ROZPOZNANIE, wplywy = wplywy)

        assertEquals(List(5) { Wplyw(Zaliczenie.ROZPOZNANE, 3, null) }, wplywy.take(5))
        assertEquals(List(2) { Wplyw(Zaliczenie.ROZPOZNANE, 0, Limit.ROZPOZNANIA) }, wplywy.drop(5))
        val ksiega = poRestarcie().load().dorobek
        assertEquals(15, ksiega.xp)
        assertEquals(5, ksiega.rozpoznaneDzis)
        assertEquals(7, ksiega.noweDzis)
        assertEquals("samymi kaflami celu nie da sie zaliczyc", 0, ksiega.dniNauki)

        // Nastepnego dnia rozpoznanie nowego slowa znow placi, a wczorajsze slowa wpisane
        // z pamieci daja awans: takze te, za ktore wczoraj nie bylo punktow.
        wplywy.clear()
        odpowiedz(repo, slowa.drop(7).take(1), DZIS + 1, task = TaskKind.ROZPOZNANIE, wplywy = wplywy)
        odpowiedz(repo, slowa.drop(6).take(1), DZIS + 1, wplywy = wplywy)
        assertEquals(
            listOf(Wplyw(Zaliczenie.ROZPOZNANE, 3, null), Wplyw(Zaliczenie.AWANS, 20, null)),
            wplywy,
        )
        assertEquals(38, repo.load().dorobek.xp)
    }

    @Test
    fun `slowo utrwalone mimo wczesniejszej pomylki liczy sie jako naprawione, raz i tylko ze slownika gry`() {
        val repo = Repository(katalog.root)
        val wlasne = wlasneSlowa(repo, 1)
        val zPomylka = slowa[0]
        val bezPomylki = slowa[1]
        // Pomylka przy pierwszym spotkaniu; poprawka tego samego dnia jeszcze niczego nie daje.
        for (id in listOf(zPomylka, wlasne[0])) {
            assertTrue(repo.recordAttempt(proba(entryId = id, outcome = Outcome.BLEDNA, id = "zle-$id"), poludnie(DZIS)))
        }
        // Trzy terminy: poznane, przypomniane po przerwie, utrwalone.
        for (dzien in listOf(DZIS + 1, DZIS + 2, DZIS + 5)) {
            odpowiedz(repo, listOf(zPomylka, bezPomylki, wlasne[0]), dzien)
            if (dzien < DZIS + 5) assertEquals(0, repo.load().dorobek.naprawione)
        }

        val dane = poRestarcie().load()
        for (id in listOf(zPomylka, bezPomylki, wlasne[0])) {
            assertEquals(3, Srs.progressOf(dane.progress, id, Direction.TERM_TO_DEF).box)
        }
        assertEquals("slowo bez pomylki i slowo wlasne sie nie licza", 1, dane.dorobek.naprawione)
        assertEquals(DZIS + 5, dane.dorobek.odznaki["NAPRAWA_1"])

        // Kolejna pomylka i ponowne dojscie do pudelka 3 to juz zwykle powtorki: licznik stoi.
        assertTrue(repo.recordAttempt(proba(entryId = zPomylka, outcome = Outcome.BLEDNA, id = "znowu"), poludnie(DZIS + 12)))
        odpowiedz(repo, listOf(zPomylka), DZIS + 13)
        odpowiedz(repo, listOf(zPomylka), DZIS + 16)
        val pozniej = poRestarcie().load()
        assertEquals(3, Srs.progressOf(pozniej.progress, zPomylka, Direction.TERM_TO_DEF).box)
        assertEquals(1, pozniej.dorobek.naprawione)
    }

    // --- gra ------------------------------------------------------------------------------------

    private fun gra(tryb: GameMode, punkty: Int = 0, pokonane: Int = 0, seria: Int = 0) =
        ScoreRecord("Kuba", zestaw.title, punkty, 1, seria, pokonane, 1_700_000_000_000L, tryb)

    @Test
    fun `gra stempluje odznaki za gre dniem gry, ale punktow nauki nie dopisuje`() {
        val repo = Repository(katalog.root)

        val pierwsza = gra(GameMode.CLASSIC, punkty = 600, pokonane = 120, seria = 6)
        assertTrue(repo.recordGame(pierwsza, zestaw.id, nowMs = poludnie(DZIS)))

        val poPierwszej = poRestarcie().load()
        val zaPierwsza = mapOf("FIRST_BLOOD" to DZIS, "SHARPSHOOTER" to DZIS, "COMBO_X3" to DZIS, "SCORE_500" to DZIS)
        assertEquals(zaPierwsza, poPierwszej.dorobek.odznaki)
        assertEquals("punkty za gre to nie punkty nauki", 0, poPierwszej.dorobek.xp)
        assertEquals(
            Dorobek(odznaki = zaPierwsza, odznakiNowe = zaPierwsza.keys),
            poPierwszej.dorobek,
        )

        assertTrue(repo.recordGame(gra(GameMode.TRUE_FALSE), zestaw.id, nowMs = poludnie(DZIS + 1)))
        assertTrue(repo.recordGame(gra(GameMode.MULTIPLE_CHOICE), zestaw.id, nowMs = poludnie(DZIS + 1)))
        assertFalse("trzy tryby to jeszcze nie cztery", "TRYBY_4" in repo.load().dorobek.odznaki)
        assertTrue(repo.recordGame(gra(GameMode.TIME_ATTACK), zestaw.id, nowMs = poludnie(DZIS + 2)))
        assertTrue(repo.recordGame(gra(GameMode.CLASSIC), zestaw.id, nowMs = poludnie(DZIS + 3)))

        val odznaki = poRestarcie().load().dorobek.odznaki
        assertEquals(
            // Stare stemple zostaja z dniem, w ktorym padly.
            zaPierwsza + mapOf("TRYBY_4" to DZIS + 2, "ROOKIE" to DZIS + 3),
            odznaki,
        )
    }

    @Test
    fun `gra bez czasu w wywolaniu stempluje odznaki dzisiejszym dniem`() {
        // Tak wola dzis App.kt (rekord i zestaw, nic wiecej): wartosc domyslna bierze zegar.
        val repo = Repository(katalog.root)
        val przed = Srs.dayOf(System.currentTimeMillis())

        assertTrue(repo.recordGame(gra(GameMode.CLASSIC, pokonane = 1), zestaw.id))

        val po = Srs.dayOf(System.currentTimeMillis())
        val stempel = repo.load().dorobek.odznaki.getValue("FIRST_BLOOD")
        assertTrue("stempel $stempel poza $przed..$po", stempel in przed..po)
        assertEquals(1, repo.load().scores.size)
    }

    @Test
    fun `runda nie o wynik liczy gre i pokonanych, ale nie wchodzi na liste i nie rusza rekordow`() {
        val repo = Repository(katalog.root)
        assertTrue(repo.recordGame(gra(GameMode.CLASSIC, punkty = 300, pokonane = 10, seria = 4), zestaw.id, nowMs = poludnie(DZIS)))
        val przed = repo.load()

        // Spokojna runda sesji: zapas zyc, bez presji. Wynik wyzszy niz rekord, seria dluzsza.
        val spokojna = gra(GameMode.TIME_ATTACK, punkty = 2500, pokonane = 95, seria = 12)
        assertTrue(repo.recordGame(spokojna, zestaw.id, doRankingu = false, nowMs = poludnie(DZIS + 1)))

        val po = poRestarcie().load()
        assertEquals(2, po.profile.gamesPlayed)
        assertEquals(105, po.profile.totalKills)
        assertEquals("rekord punktow zostaje", 300, po.profile.bestScore)
        assertEquals("rekord serii zostaje", 4, po.profile.bestStreak)
        assertEquals("wynik nie trafia na liste", przed.scores, po.scores)
        assertEquals(2, po.sets.first { it.id == zestaw.id }.timesPlayed)
        // Odznaki ida za licznikami: sto pokonanych jest, wyniku 2000, mnoznika i trybu nie ma.
        assertEquals(
            mapOf("FIRST_BLOOD" to DZIS, "SHARPSHOOTER" to DZIS + 1),
            po.dorobek.odznaki,
        )

        // Trzecie wywolanie pozycyjnie, tak jak zrobi to ekran gry: rekord, zestaw, ranking.
        assertTrue(repo.recordGame(spokojna, zestaw.id, false))
        assertEquals(przed.scores, repo.load().scores)
        assertEquals(3, repo.load().profile.gamesPlayed)
    }

    @Test
    fun `gra z potwierdzeniem odhacza czekajace odznaki tym samym zapisem`() {
        val repo = Repository(katalog.root)
        odpowiedz(repo, slowa.take(1), DZIS)
        assertEquals(setOf("SLOWA_1"), repo.load().dorobek.odznakiNowe)
        val przedGra = plik(Repository.FILE_NAME).readText()

        assertTrue(
            repo.recordGame(
                gra(GameMode.CLASSIC, pokonane = 3), zestaw.id,
                nowMs = poludnie(DZIS), potwierdzOdznaki = true,
            ),
        )

        assertEquals("gra zapisala dokument wiecej niz raz", przedGra, plik(Repository.BACKUP_NAME).readText())
        val ksiega = poRestarcie().load().dorobek
        assertEquals(mapOf("SLOWA_1" to DZIS, "FIRST_BLOOD" to DZIS), ksiega.odznaki)
        assertTrue("ekran wyniku wlasnie je pokazuje", ksiega.odznakiNowe.isEmpty())

        // Bez potwierdzenia odznaka z gry czeka na pokazanie.
        assertTrue(repo.recordGame(gra(GameMode.CLASSIC, pokonane = 100), zestaw.id, nowMs = poludnie(DZIS)))
        assertEquals(setOf("SHARPSHOOTER"), repo.load().dorobek.odznakiNowe)
    }

    @Test
    fun `potwierdzenie odznak zapisuje raz i tylko wtedy, gdy cos czeka`() {
        val repo = Repository(katalog.root)
        odpowiedz(repo, slowa.take(1), DZIS)
        assertEquals(setOf("SLOWA_1"), repo.load().dorobek.odznakiNowe)
        val przed = odcisk()

        assertTrue(repo.potwierdzOdznaki())

        assertNotEquals(przed, odcisk())
        assertEquals("w kopii lezy dokument sprzed potwierdzenia: byl jeden zapis", przed.first, odcisk().second)
        val ksiega = poRestarcie().load().dorobek
        assertTrue(ksiega.odznakiNowe.isEmpty())
        assertEquals("odznaka zostaje, znika tylko ogloszenie", mapOf("SLOWA_1" to DZIS), ksiega.odznaki)
        assertEquals(10, ksiega.xp)

        // Drugi raz nie ma czego odhaczac: dysk stoi.
        val poPierwszym = odcisk()
        assertTrue(repo.potwierdzOdznaki())
        assertEquals(poPierwszym, odcisk())
    }

    // --- rozpisanie rundy a to, co lezy w zapisie -----------------------------------------------

    @Test
    fun `wyjscie w polowie rundy niczego nie gubi - odznaka czeka na nastepny ekran wyniku`() {
        // Runda przerwana wyjsciem: zaliczenia poszly na dysk odpowiedz po odpowiedzi, ekranu
        // wyniku nie bylo, wiec nikt odznaki nie pokazal.
        odpowiedz(Repository(katalog.root), slowa.take(1), DZIS)

        // Nastepna runda, po restarcie. Punkt odniesienia to zapis z jej poczatku.
        val repo = poRestarcie()
        val przed = repo.load()
        assertEquals(setOf("SLOWA_1"), przed.dorobek.odznakiNowe)
        val wplywy = ArrayList<Wplyw>()
        odpowiedz(repo, slowa.drop(1).take(1), DZIS, wplywy = wplywy)
        val wynik = gra(GameMode.CLASSIC, punkty = 40, pokonane = 2)
        assertTrue(repo.recordGame(wynik, zestaw.id, nowMs = poludnie(DZIS), potwierdzOdznaki = true))
        val po = repo.load()

        val nagroda = Nagrody.roznica(przed, po, wplywy, wynik, DZIS)

        assertEquals(10, nagroda.xp)
        assertEquals(
            "czekajaca z poprzedniej rundy i nowa z tej gry, w kolejnosci stalych",
            listOf(Odznaka.SLOWA_1, Odznaka.FIRST_BLOOD),
            nagroda.odznaki,
        )

        // JESZCZE RAZ: punkt odniesienia przesuwa sie na zapis po rundzie i nic sie nie powtarza.
        val druga = Nagrody.roznica(po, repo.load(), emptyList(), null, DZIS)
        assertEquals(0, druga.xp)
        assertTrue(druga.odznaki.isEmpty())
        assertTrue(druga.rozpisanie.isEmpty())
    }

    @Test
    fun `rozpisanie rundy zgadza sie z tym, co lezy w zapisie`() {
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
        // Odznaki z historii uczen juz widzial (ekran osiagniec).
        assertTrue(repo.potwierdzOdznaki())

        val przed = repo.load()
        assertEquals("jedyny zaliczony dzien to ten z utrwaleniem za 30 punktow", 1, przed.dorobek.dniNauki)
        val proby = listOf(
            proba(entryId = utrwalone, id = "r1"),                                   // powtorka w terminie, 5
            proba(entryId = powtarzane, id = "r2"),                                  // utrwalenie, 30
            proba(entryId = poznane, id = "r3"),                                     // awans, 20
            proba(entryId = nowe, id = "r4"),                                        // nowe slowo, 10
            proba(entryId = doRozpoznania, task = TaskKind.ROZPOZNANIE, id = "r5"),  // rozpoznane, 3
            proba(entryId = potkniecie, outcome = Outcome.BLEDNA, id = "r6"),
            proba(entryId = potkniecie, id = "r7"),                                  // poprawka: nic
            proba(entryId = utrwalone, id = "r8"),                                   // drugi raz dzis: nic
            proba(entryId = nowe, id = "r4"),                                        // ta sama proba: nic
            proba(entryId = przerwane, outcome = Outcome.PRZERWANA, id = "r9"),
        )
        val wplywy = ArrayList<Wplyw>()
        for (p in proby) assertTrue(repo.recordAttempt(p, poludnie(DZIS)) { wplywy += it })
        val wynik = gra(GameMode.CLASSIC, punkty = 340, pokonane = 6, seria = 4)
        assertTrue(repo.recordGame(wynik, zestaw.id, nowMs = poludnie(DZIS), potwierdzOdznaki = true))
        val po = poRestarcie().load()

        val nagroda = Nagrody.roznica(przed, po, wplywy, wynik, DZIS)

        assertEquals(
            listOf(
                Wplyw(Zaliczenie.POWTORKA, 5, null),
                Wplyw(Zaliczenie.UTRWALENIE, 30, null),
                Wplyw(Zaliczenie.AWANS, 20, null),
                Wplyw(Zaliczenie.NOWE, 10, null),
                Wplyw(Zaliczenie.ROZPOZNANE, 3, null),
            ),
            wplywy,
        )
        assertEquals(68, po.dorobek.xp - przed.dorobek.xp)
        assertEquals(68, nagroda.xp)
        assertEquals("rozpisanie i ksiega opowiadaja te sama runde", nagroda.xp, nagroda.rozpisanie.sumOf { it.xp })
        assertEquals(
            listOf(
                PozycjaNagrody(Zaliczenie.NOWE, 1, 10),
                PozycjaNagrody(Zaliczenie.AWANS, 1, 20),
                PozycjaNagrody(Zaliczenie.UTRWALENIE, 1, 30),
                PozycjaNagrody(Zaliczenie.POWTORKA, 1, 5),
                PozycjaNagrody(Zaliczenie.ROZPOZNANE, 1, 3),
            ),
            nagroda.rozpisanie,
        )
        assertTrue(nagroda.limity.isEmpty())
        assertEquals(68, nagroda.xpDzisPo)
        assertEquals(68, po.dorobek.xpDzis)
        assertEquals(2, po.dorobek.noweDzis)
        assertEquals("powtorka, utrwalenie i awans", 3, po.dorobek.powtorki - przed.dorobek.powtorki)
        assertEquals("utrwalone slowo nie mialo pomylki", 0, po.dorobek.naprawione)
        // Cel padl w tej rundzie, tydzien po poprzednim zaliczonym dniu: seria od nowa.
        assertTrue(nagroda.celZaliczonyTeraz)
        assertFalse(nagroda.pierwszyDzien)
        assertTrue(nagroda.nowaSeria)
        assertEquals(1, nagroda.seriaPo)
        assertEquals(2, nagroda.dniNauki)
        // Pierwszy pokonany potworek padl w tej grze; odznaki za slowa uczen mial juz wczesniej.
        assertEquals(listOf(Odznaka.FIRST_BLOOD), nagroda.odznaki)
        assertFalse("pierwsza gra w trybie nie jest rekordem", nagroda.rekord)
        assertTrue(po.dorobek.odznakiNowe.isEmpty())
    }

    @Test
    fun `zaczep w zapisie liczy dokladnie to, co reguly, runda po rundzie`() {
        // Te same proby dwiema drogami. Pierwsza to prawdziwe Repository: kazda proba w swoim
        // zapisie, ksiega na dysku, wplyw oddany wolajacemu. Druga to reguly wolane wprost na
        // stanach slowa sprzed proby i po niej. Roznia sie tym, co wie tylko Repository: czy
        // slowo jest ze slownika gry, czy to naprawa i jaki jest cel dnia. Gdyby zaczep
        // podal ktoras z tych rzeczy zle, uczen mialby w ksiedze co innego, niz mowia zasady.
        val repo = Repository(katalog.root)
        val zeSlownika = slowa.take(6)
        val wlasne = (1..14).map { "u" + it.toString(16).padStart(12, '0') }
        // Maly zapis startowy zamiast calego katalogu: test robi kilkaset zapisow dokumentu.
        val maly = WordSet(id = zestaw.id, title = zestaw.title, builtIn = true, entries = zestaw.entries.take(6))
        assertTrue(repo.save(SaveData(schemaVersion = Repository.SCHEMA_VERSION, sets = listOf(maly), options = Options(celDnia = 60))))
        val los = Random(2027)
        val wyniki = List(6) { Outcome.POPRAWNA } + Outcome.entries
        val zadania = List(3) { TaskKind.PRZYPOMNIENIE } + TaskKind.ROZPOZNANIE
        val kierunki = listOf(Direction.TERM_TO_DEF, Direction.DEF_TO_TERM)
        val widziane = HashSet<Zaliczenie>()
        val limity = HashSet<Limit>()
        var naprawy = 0
        var dzien = DZIS
        var numer = 0

        repeat(60) {
            dzien += los.nextInt(1, 4)
            val przed = repo.load()
            var oczekiwana = przed.dorobek
            val oczekiwaneWplywy = ArrayList<Wplyw>()
            val oddane = ArrayList<Wplyw>()
            val proby = ArrayList<Attempt>()
            repeat(los.nextInt(0, 40)) {
                // Co kilka prob ekran dostarcza TE SAMA probe drugi raz (ponowienie po bledzie
                // zapisu, drugie zrodlo zdarzen): ten sam znacznik, ta sama tresc.
                val proba = if (proby.isNotEmpty() && los.nextInt(8) == 0) {
                    proby[los.nextInt(proby.size)]
                } else {
                    val pula = if (los.nextInt(3) == 0) zeSlownika else wlasne
                    Attempt(
                        entryId = pula[los.nextInt(pula.size)],
                        direction = kierunki[los.nextInt(kierunki.size)],
                        task = zadania[los.nextInt(zadania.size)],
                        outcome = wyniki[los.nextInt(wyniki.size)],
                        id = "p${numer++}",
                    )
                }
                proby += proba
                val klucz = Srs.key(proba.entryId, proba.direction)
                val slowoPrzed = repo.load().progress[klucz] ?: WordProgress()

                assertTrue(repo.recordAttempt(proba, dzien.toLong() * Srs.MS_PER_DAY + 3_600_000L) { oddane += it })

                val slowoPo = repo.load().progress[klucz] ?: WordProgress()
                val zaliczenie = Nagrody.zaliczenie(slowoPrzed, slowoPo, proba) ?: return@repeat
                val jestWlasne = proba.entryId in wlasne
                val naprawa = zaliczenie == Zaliczenie.UTRWALENIE && slowoPrzed.mistakes > 0 && !jestWlasne
                if (naprawa) naprawy++
                val doliczenie = Nagrody.dolicz(oczekiwana, zaliczenie, dzien, 60, jestWlasne, naprawa)
                oczekiwana = doliczenie.dorobek
                oczekiwaneWplywy += doliczenie.wplyw
                widziane += zaliczenie
                doliczenie.wplyw.limit?.let { limity += it }
            }
            val po = repo.load()

            assertEquals("dzien $dzien: wplywy oddane wolajacemu", oczekiwaneWplywy, oddane)
            assertEquals(
                "dzien $dzien: ksiega poza odznakami",
                oczekiwana.copy(odznaki = emptyMap(), odznakiNowe = emptySet()),
                po.dorobek.copy(odznaki = emptyMap(), odznakiNowe = emptySet()),
            )
            assertEquals("dzien $dzien: punkty", oddane.sumOf { it.xp }, po.dorobek.xp - przed.dorobek.xp)
            val nagroda = Nagrody.roznica(przed, po, oddane, null, dzien)
            assertEquals(nagroda.xp, nagroda.rozpisanie.sumOf { it.xp })
            assertEquals(oddane.size, nagroda.rozpisanie.sumOf { it.ile })
            assertEquals(oddane.mapNotNull { it.limit }.toSet(), nagroda.limity)
        }

        // Test o tescie: szescdziesiat rund ma przejsc przez wszystkie rodzaje zaliczen, przez
        // limit wlasnych slow i przez choc jedna naprawe.
        assertEquals(Zaliczenie.entries.toSet(), widziane)
        assertTrue("limit wlasnych slow ani razu nie zadzialal", Limit.WLASNE in limity)
        assertTrue("ani jednej naprawy", naprawy > 0)
        assertEquals(naprawy, repo.load().dorobek.naprawione)
        assertEquals(repo.load().dorobek, Repository(katalog.root).load().dorobek)
    }
}
