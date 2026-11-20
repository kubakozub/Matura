package com.matura.app

import com.matura.app.data.Repository
import com.matura.app.data.SampleData
import com.matura.app.model.Entry
import com.matura.app.model.Profile
import com.matura.app.model.SaveData
import com.matura.app.model.WordSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * STAN DYSKU PO UBICIU APLIKACJI W SRODKU ZAPISU.
 *
 * Zapis ma trzy kroki (Repository.writeAtomically):
 *
 *     1. cala tresc do matura.json.tmp, z fsync
 *     2. matura.json      -> matura.json.bak
 *     3. matura.json.tmp  -> matura.json
 *
 * Miedzy krokiem 2 a 3 pliku glownego NIE MA. System potrafi ubic aplikacje dokladnie tam
 * (brak pamieci, wyjeta bateria) i wtedy na dysku lezy kopia z POPRZEDNIM stanem oraz kompletny
 * plik tymczasowy z NAJNOWSZYM. Odczyt znal tylko kopie, wiec ostatnia odpowiedz ucznia albo
 * zapisany przed chwila zestaw przepadaly, choc lezaly w calosci obok.
 *
 * Kazdy test uklada pliki recznie, w tej kolejnosci, w jakiej zostawia je przerwany zapis:
 * samym Repository tego stanu nie da sie wywolac, bo po kazdym zapisie sprzata ono plik
 * tymczasowy.
 */
class PrzerwanyZapisTest {

    @get:Rule
    val katalog = TemporaryFolder()

    /** Drugi katalog, w ktorym powstaje tresc "nastepnego zapisu"; badany zostaje czysty. */
    @get:Rule
    val obok = TemporaryFolder()

    private fun plik(nazwa: String) = File(katalog.root, nazwa)

    private fun stan(imie: String) = SaveData(
        schemaVersion = Repository.SCHEMA_VERSION,
        profile = Profile(name = imie),
        sets = SampleData.builtInSets() + WordSet(
            id = "user-1",
            title = "Moje słówka",
            entries = listOf(Entry("dog", "pies"), Entry(imie, "ostatnio dopisane")),
        ),
    )

    /** Tresc dokladnie taka, jaka Repository kladzie na dysk: zapisana nim samym, w innym katalogu. */
    private fun trescPliku(dane: SaveData): String {
        val osobny = obok.newFolder()
        assertTrue(Repository(osobny).save(dane))
        return File(osobny, Repository.FILE_NAME).readText()
    }

    /**
     * Dwa pelne zapisy, a trzeci ubity miedzy zmianami nazw: kopia ma stan drugiego zapisu,
     * plik tymczasowy caly stan trzeciego, pliku glownego nie ma.
     */
    private fun ubityMiedzyZmianamiNazw(trescTymczasowego: String = trescPliku(stan("trzeci"))) {
        val repo = Repository(katalog.root)
        assertTrue(repo.save(stan("pierwszy")))
        assertTrue(repo.save(stan("drugi")))

        plik(Repository.TEMP_NAME).writeText(trescTymczasowego)                         // krok 1
        assertTrue(plik(Repository.FILE_NAME).renameTo(plik(Repository.BACKUP_NAME)))   // krok 2
        // kroku 3 juz nie bylo
    }

    private fun naDysku(): Map<String, String> =
        katalog.root.listFiles()!!.associate { it.name to it.readText() }

    @Test
    fun `zapis ubity miedzy zmianami nazw oddaje najnowszy stan, nie poprzedni`() {
        ubityMiedzyZmianamiNazw()
        assertFalse("to nie jest stan, o ktory chodzi w tescie", plik(Repository.FILE_NAME).exists())

        val odczytane = Repository(katalog.root).load()

        assertEquals("trzeci", odczytane.profile.name)
        assertEquals(
            "zestaw wlasny wrocil w starszej wersji",
            listOf("dog", "trzeci"),
            odczytane.sets.single { it.id == "user-1" }.entries.map { it.term },
        )
    }

    @Test
    fun `odczyt po przerwanym zapisie niczego na dysku nie rusza`() {
        ubityMiedzyZmianamiNazw()
        val przed = naDysku()
        assertEquals(setOf(Repository.BACKUP_NAME, Repository.TEMP_NAME), przed.keys)

        Repository(katalog.root).load()

        // load() nie pisze: gdyby tu sprzatal albo przenosil pliki, kolejne ubicie w zlym
        // momencie zostawialoby stan, ktorego nikt nie przewidzial.
        assertEquals(przed, naDysku())
    }

    @Test
    fun `pierwszy zapis po odzyskaniu odtwarza plik glowny`() {
        ubityMiedzyZmianamiNazw()
        val repo = Repository(katalog.root)
        repo.load()

        assertTrue(repo.save(stan("czwarty")))

        assertEquals("czwarty", Repository(katalog.root).load().profile.name)
        assertTrue(plik(Repository.FILE_NAME).exists())
        assertFalse("plik tymczasowy przezyl zapis", plik(Repository.TEMP_NAME).exists())
    }

    @Test
    fun `urwany plik tymczasowy nie wygrywa z cala kopia`() {
        // Tak wyglada ubicie w kroku 1 przy brakujacym pliku glownym: ogon JSON-a nie doszedl.
        val caly = trescPliku(stan("trzeci"))
        ubityMiedzyZmianamiNazw(trescTymczasowego = caly.take(caly.length / 2))

        val odczytane = Repository(katalog.root).load()

        assertEquals("drugi", odczytane.profile.name)
        assertTrue("urwanego pliku tez nie kasujemy przy odczycie", plik(Repository.TEMP_NAME).exists())
    }

    @Test
    fun `caly plik glowny dalej ma pierwszenstwo przed tymczasowym`() {
        // Ubicie PRZED krokiem 2: plik glowny jest na miejscu i to on jest ostatnim stanem,
        // ktory zapis zdazyl potwierdzic. Kolejnosc odczytu zmienia sie tylko tam, gdzie
        // pliku glownego nie ma.
        val repo = Repository(katalog.root)
        assertTrue(repo.save(stan("pierwszy")))
        assertTrue(repo.save(stan("drugi")))
        plik(Repository.TEMP_NAME).writeText(trescPliku(stan("trzeci")))

        assertEquals("drugi", Repository(katalog.root).load().profile.name)
    }

    @Test
    fun `pierwszy zapis w zyciu ubity przed zmiana nazwy tez sie odzyskuje`() {
        // Swiezo zainstalowana aplikacja: nie ma ani pliku glownego, ani kopii, wiec krok 2
        // nie ma czego przenosic, a ubicie przed krokiem 3 zostawia sam plik tymczasowy.
        plik(Repository.TEMP_NAME).writeText(trescPliku(stan("pierwszy")))

        assertEquals("pierwszy", Repository(katalog.root).load().profile.name)
    }
}
