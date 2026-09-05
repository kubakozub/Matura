package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.data.Repository
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Migracja zapisu do schematu 2 (trwale identyfikatory wpisow + mapa postepu).
 *
 * Sprawdzana jest przede wszystkim ta czesc, ktora latwo zepsuc bez zadnego widocznego objawu:
 * ze nic nie ginie, ze drugie uruchomienie niczego nie zmienia i ze aplikacja NIE wymysla
 * opanowania slow, ktorego uczen nigdy nie pokazal.
 */
class MigracjaTest {

    @get:Rule
    val katalog = TemporaryFolder()

    private fun plik(nazwa: String) = File(katalog.root, nazwa)

    /**
     * Zapis sprzed wersjonowania: bez pola schemaVersion, bez identyfikatorow wpisow i z
     * WLASNA kopia zestawu wbudowanego — w brzmieniu SPRZED korekty tlumaczen z Etapu 1
     * ("rówieśnik"), bo tak wyglada plik na telefonie, ktory nie dostal jeszcze nowej wersji.
     */
    private val staryZapis = """
        {
          "profile": {
            "name": "Stary", "anonymous": false, "gamesPlayed": 12,
            "totalKills": 140, "bestScore": 1500, "bestStreak": 9
          },
          "options": {
            "lives": 5, "soundEnabled": false, "texture": "NIGHT",
            "direction": "DEF_TO_TERM", "uiLanguage": "EN", "speed": "SLOW",
            "systemKeyboard": true
          },
          "sets": [
            {
              "id": "v000",
              "title": "Człowiek · Charakter: dojrzałość",
              "builtIn": true,
              "timesPlayed": 6,
              "entries": [
                {"term": "schoolmate", "definition": "rówieśnik"},
                {"term": "mature", "definition": "dojrzały"}
              ]
            },
            {
              "id": "user-1",
              "title": "Moje słówka",
              "createdAt": 1700000000000,
              "entries": [
                {"term": "dog", "definition": "pies"},
                {"term": "cat", "definition": "kot"}
              ]
            }
          ],
          "scores": [
            {
              "playerName": "Stary", "setTitle": "Moje słówka", "score": 1500,
              "level": 7, "bestStreak": 9, "kills": 80, "playedAt": 1700000000000
            }
          ]
        }
    """.trimIndent()

    private fun zapiszStary() = plik(Repository.FILE_NAME).writeText(staryZapis)

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
        assertTrue("ustawienie klawiatury systemowej przepadlo", dane.options.systemKeyboard)
        assertEquals(1, dane.scores.size)
        assertEquals(1500, dane.scores.first().score)

        val wlasny = dane.sets.first { !it.builtIn }
        assertEquals("Moje słówka", wlasny.title)
        assertEquals(listOf("dog", "cat"), wlasny.entries.map { it.term })
        assertEquals(6, dane.sets.first { it.builtIn }.timesPlayed)
        assertEquals(Repository.SCHEMA_VERSION, dane.schemaVersion)
    }

    @Test
    fun `wpisy bez identyfikatora dostaja go wedlug pochodzenia zestawu`() {
        zapiszStary()

        val dane = Repository(katalog.root).load()

        for (e in dane.sets.first { it.builtIn }.entries) {
            assertTrue("'${e.term}' bez identyfikatora katalogowego", EntryId.isBuiltIn(e.id))
        }
        for (e in dane.sets.first { !it.builtIn }.entries) {
            assertTrue("'${e.term}' bez identyfikatora wlasnego", EntryId.isUser(e.id))
        }
    }

    @Test
    fun `wpis w brzmieniu sprzed korekty tlumaczenia dostaje identyfikator z katalogu`() {
        // To jest cala stawka aliasow: bez nich to samo slowo mialoby inny identyfikator na
        // telefonie z zapisem sprzed korekty niz w swiezo zainstalowanej aplikacji.
        zapiszStary()
        val zKatalogu = BuiltInSets.all().flatMap { it.entries }.first { it.term == "schoolmate" }

        val zMigracji = Repository(katalog.root).load()
            .sets.first { it.builtIn }.entries.first { it.term == "schoolmate" }

        assertEquals("rówieśnik", zMigracji.definition)
        assertEquals(zKatalogu.id, zMigracji.id)
    }

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
